package dev.knowledge.platform.agent;

import dev.knowledge.ApiException;
import dev.knowledge.Models.*;
import dev.knowledge.platform.agent.AgentModels.*;
import dev.knowledge.platform.core.*;
import dev.knowledge.platform.integration.ToolRegistry;
import dev.knowledge.platform.jobs.*;
import dev.knowledge.platform.ops.*;
import dev.knowledge.platform.rag.ModelGateway.GatedModels;
import dev.knowledge.platform.rag.PlatformRag;
import dev.knowledge.platform.tenant.TenantAccess;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes workflow steps in order and persists every step's result (timeline). Safety rules enforced here:
 * <ul><li>authorisation is rebuilt for the run's actor at execution time;</li>
 * <li>templates substitute data only ({{input.x}}, {{steps.N.field}}); document text and model output never select tools or SQL;</li>
 * <li>TOOL steps only run whitelisted, read-only tools; the single write path (OUTPUT → agent_outputs) requires an APPROVED approval of this run;</li>
 * <li>every tool call is recorded in tool_calls with actor/tenant/trace.</li></ul>
 */
@Component @Profile("platform")
public class AgentRunHandler implements JobHandler {
    private final JdbcClient db;
    private final Json json;
    private final TenantAccess access;
    private final PlatformRag rag;
    private final GatedModels models;
    private final ToolRegistry tools;
    private final UsageService usage;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public AgentRunHandler(JdbcClient db, Json json, TenantAccess access, PlatformRag rag, GatedModels models, ToolRegistry tools, UsageService usage,
                           AuditService audit, TransactionTemplate tx) {
        this.db = db; this.json = json; this.access = access; this.rag = rag; this.models = models; this.tools = tools; this.usage = usage; this.audit = audit; this.tx = tx;
    }
    @Override public String type() { return AgentService.AGENT_RUN; }

    private record RunRow(UUID id, UUID orgId, UUID agentId, int version, UUID actor, String status, Map<String, Object> input) {}

    @Override public Object handle(JobQueue.Job job, JobContext context) {
        UUID runId = UUID.fromString((String) job.payload().get("runId"));
        var run = db.sql("select * from workflow_runs where id = :id and org_id = :o").param("id", runId).param("o", job.orgId())
                .query((rs, n) -> new RunRow(Db.uuid(rs, "id"), Db.uuid(rs, "org_id"), Db.uuid(rs, "agent_id"), rs.getInt("agent_version"), Db.uuid(rs, "actor_id"),
                        rs.getString("status"), json.map(rs.getString("input")))).optional().orElseThrow(() -> new JobFailure("RUN_GONE", false));
        if (!Set.of("QUEUED", "RUNNING").contains(run.status())) return Map.of("skipped", run.status());
        var ctx = access.contextFor(run.orgId(), run.actor()).orElse(null);
        if (ctx == null) { finish(run.id(), "FAILED", null, "ACCESS_REVOKED: the requester is no longer an active member"); return Map.of("status", "FAILED"); }
        var def = json.read(db.sql("select definition from agent_versions where agent_id = :a and version_no = :v and org_id = :o")
                .param("a", run.agentId()).param("v", run.version()).param("o", run.orgId()).query(String.class).single(), AgentDefinition.class);
        var readable = access.readableKbIds(ctx);
        var kbs = def.kbIds().stream().map(UUID::fromString).filter(readable::contains).toList();
        if (kbs.size() != def.kbIds().size()) { finish(run.id(), "FAILED", null, "ACCESS_REVOKED: the requester can no longer read every knowledge base of this agent"); return Map.of("status", "FAILED"); }
        db.sql("update workflow_runs set status = 'RUNNING', updated_at = now() where id = :id and status = 'QUEUED'").param("id", run.id()).update();

        var outputs = new ArrayList<Object>();
        var stepRows = db.sql("select id, ordinal, status, output from workflow_steps where run_id = :r order by ordinal").param("r", run.id())
                .query((rs, n) -> new Object[]{Db.uuid(rs, "id"), rs.getInt("ordinal"), rs.getString("status"), json.any(rs.getString("output"))}).list();
        for (int i = 0; i < def.steps().size(); i++) {
            context.checkpoint();
            if (isCancelled(run.id())) return Map.of("status", "CANCELLED");
            var row = stepRows.get(i);
            var step = def.steps().get(i);
            if ("SUCCEEDED".equals(row[2])) { outputs.add(row[3]); continue; }
            if ("WAITING_APPROVAL".equals(row[2])) return Map.of("status", "WAITING_APPROVAL");
            UUID stepId = (UUID) row[0];
            db.sql("update workflow_steps set status = 'RUNNING', started_at = now(), error = null where id = :id").param("id", stepId).update();
            try {
                var config = step.config() == null ? Map.<String, Object>of() : step.config();
                Object output = switch (step.type()) {
                    case "RETRIEVE" -> retrieve(run, kbs, config, outputs);
                    case "LLM" -> llm(run, def, kbs, config, outputs);
                    case "EXTRACT" -> extract(ctx, run, config, outputs);
                    case "RULE" -> rule(run, config, outputs);
                    case "TOOL" -> tool(run, def, stepId, config, outputs);
                    case "APPROVAL" -> { requestApproval(run, stepId, config, outputs); yield null; }
                    case "OUTPUT" -> output(run, def, stepId, config, outputs);
                    default -> throw new ApiException(400, "INVALID_STEP", "Unknown step type.");
                };
                if (step.type().equals("APPROVAL")) return Map.of("status", "WAITING_APPROVAL");
                db.sql("update workflow_steps set status = 'SUCCEEDED', output = cast(:o as jsonb), finished_at = now() where id = :id")
                        .param("o", json.write(output)).param("id", stepId).update();
                outputs.add(output);
            } catch (ApiException e) {
                db.sql("update workflow_steps set status = 'FAILED', error = :e, finished_at = now() where id = :id").param("e", e.code + ": " + e.getMessage()).param("id", stepId).update();
                if (e.status >= 500) throw e; // transient: the job retries from this step
                finish(run.id(), "FAILED", null, e.code + ": " + e.getMessage());
                return Map.of("status", "FAILED");
            }
        }
        var last = outputs.isEmpty() ? null : outputs.getLast();
        finish(run.id(), "SUCCEEDED", last, null);
        return Map.of("status", "SUCCEEDED");
    }

    private boolean isCancelled(UUID run) {
        return "CANCELLED".equals(db.sql("select status from workflow_runs where id = :id").param("id", run).query(String.class).single());
    }
    private void finish(UUID run, String status, Object output, String error) {
        db.sql("update workflow_runs set status = :s, output = cast(:o as jsonb), error = :e, updated_at = now() where id = :id and status not in ('CANCELLED','REJECTED')")
                .param("s", status).param("o", output == null ? null : json.write(output)).param("e", error).param("id", run).update();
    }

    // ----- template rendering: data substitution only -----
    static final Pattern VAR = Pattern.compile("\\{\\{\\s*(input|steps)\\.([a-zA-Z0-9_.]+)\\s*}}");
    Object render(Object value, RunRow run, List<Object> outputs) {
        if (value instanceof String s) {
            var whole = VAR.matcher(s);
            if (whole.matches()) return lookup(whole.group(1), whole.group(2), run, outputs);
            var m = VAR.matcher(s); var sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf(Objects.requireNonNullElse(lookup(m.group(1), m.group(2), run, outputs), ""))));
            m.appendTail(sb);
            return sb.toString();
        }
        if (value instanceof Map<?, ?> map) { var r = new LinkedHashMap<String, Object>(); map.forEach((k, v) -> r.put(String.valueOf(k), render(v, run, outputs))); return r; }
        if (value instanceof List<?> list) return list.stream().map(v -> render(v, run, outputs)).toList();
        return value;
    }
    @SuppressWarnings("unchecked")
    private Object lookup(String root, String path, RunRow run, List<Object> outputs) {
        Object current;
        var parts = new ArrayList<>(List.of(path.split("\\.")));
        if (root.equals("input")) current = run.input();
        else { int idx = Integer.parseInt(parts.removeFirst()); current = idx < outputs.size() ? outputs.get(idx) : null; }
        for (var p : parts) current = current instanceof Map<?, ?> m ? ((Map<String, Object>) m).get(p) : null;
        return current;
    }

    // ----- step implementations -----
    private Object retrieve(RunRow run, List<UUID> kbs, Map<String, Object> config, List<Object> outputs) {
        String query = String.valueOf(render(config.getOrDefault("query", "{{input.question}}"), run, outputs));
        var result = rag.retrieveOnly(run.orgId(), kbs, query);
        return Map.of("evidence", result.evidence().stream().map(this::evidence).toList());
    }
    private Map<String, Object> evidence(PlatformRag.Evidence e) {
        return Map.of("documentId", e.documentId().toString(), "versionNo", e.versionNo(), "chunkId", e.chunkId(), "title", e.title(), "quote", e.content(), "locator", e.locator());
    }

    private Object llm(RunRow run, AgentDefinition def, List<UUID> kbs, Map<String, Object> config, List<Object> outputs) {
        boolean quality = "quality".equals(def.model());
        String question = String.valueOf(render(config.getOrDefault("question", "{{input.question}}"), run, outputs));
        if (question.isBlank() || question.length() > 2000) throw new ApiException(400, "INVALID_INPUT", "The question must be 1–2000 characters.");
        long start = System.currentTimeMillis();
        if ("SUMMARIZE_STEPS".equals(config.get("mode"))) {
            // Grounded summary over previous step outputs (tool rows etc.), with the same citation constraint as RAG.
            var sources = new ArrayList<Source>();
            for (int i = 0; i < outputs.size() && sources.size() < 5; i++) {
                if (outputs.get(i) == null) continue;
                String text = json.write(outputs.get(i));
                sources.add(new Source("step-" + i, "step", "步驟 " + (i + 1) + " 結果", 1, "", text.length() > 3000 ? text.substring(0, 3000) : text, 0, 0, 1, ""));
            }
            if (sources.isEmpty()) throw new ApiException(400, "NO_EVIDENCE", "Nothing to summarise.");
            var gen = models.generate(question, sources, quality);
            usage.record(run.orgId(), run.actor(), run.agentId(), "AGENT", models.modelName(quality), gen.inputTokens(), gen.outputTokens(), System.currentTimeMillis() - start, "SUCCESS");
            var ids = new HashSet<>(sources.stream().map(Source::chunkId).toList());
            for (var s : gen.statements()) if (!ids.containsAll(s.chunkIds())) throw new ApiException(502, "INVALID_CITATION", "Summary cited unknown evidence.");
            return Map.of("answer", gen.statements().stream().map(GeneratedStatement::text).toList(),
                    "citations", gen.statements().stream().map(GeneratedStatement::chunkIds).toList(), "status", gen.statements().isEmpty() ? "REFUSED" : "ANSWERED");
        }
        if (kbs.isEmpty()) throw new ApiException(400, "KB_REQUIRED", "This agent has no knowledge base configured.");
        var result = rag.answer(run.orgId(), kbs, question, quality);
        usage.record(run.orgId(), run.actor(), run.agentId(), "AGENT", result.model(), result.inputTokens(), result.outputTokens(), System.currentTimeMillis() - start, "SUCCESS");
        return Map.of("status", result.status(), "answer", result.statements().stream().map(GeneratedStatement::text).toList(),
                "citations", result.evidence().stream().map(this::evidence).toList());
    }

    /** Deterministic regex extraction over the current version of documents the actor may read. */
    private Object extract(TenantAccess.OrgContext ctx, RunRow run, Map<String, Object> config, List<Object> outputs) {
        var docsValue = render(config.get("documents"), run, outputs);
        var ids = docsValue instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(String.valueOf(docsValue));
        if (ids.isEmpty() || ids.size() > 20) throw new ApiException(400, "INVALID_INPUT", "Select 1–20 documents.");
        var fields = config.get("fields") instanceof List<?> f ? f : List.of();
        var readable = access.readableKbIds(ctx);
        var rows = new ArrayList<Map<String, Object>>();
        for (var idText : ids) {
            var doc = db.sql("""
                    select d.id, d.title, d.kb_id, v.id as version_id, v.version_no from documents d join document_versions v on v.id = d.current_version_id
                    where d.org_id = :o and d.id = :id and d.deleted_at is null and d.status = 'READY' and (d.expires_at is null or d.expires_at > now())""")
                    .param("o", run.orgId()).param("id", Db.parse(idText, "document"))
                    .query((rs, n) -> new Object[]{rs.getString("id"), rs.getString("title"), Db.uuid(rs, "kb_id"), Db.uuid(rs, "version_id"), rs.getInt("version_no")})
                    .optional().filter(d -> readable.contains((UUID) d[2])).orElseThrow(() -> new ApiException(404, "DOCUMENT_NOT_FOUND", "A selected document is not available."));
            var chunks = db.sql("select chunk_id, content, locator from document_chunks where document_version_id = :v order by ordinal").param("v", doc[3])
                    .query((rs, n) -> new Object[]{rs.getString("chunk_id"), rs.getString("content"), json.map(rs.getString("locator"))}).list();
            var values = new LinkedHashMap<String, Object>();
            var sources = new LinkedHashMap<String, Object>();
            for (var f : fields) {
                if (!(f instanceof Map<?, ?> field)) continue;
                var name = String.valueOf(field.get("name"));
                var pattern = Pattern.compile(String.valueOf(field.get("pattern")));
                for (var c : chunks) {
                    var m = pattern.matcher((String) c[1]);
                    if (m.find()) {
                        values.put(name, m.groupCount() >= 1 ? m.group(1) : m.group());
                        sources.put(name, Map.of("chunkId", c[0], "locator", c[2]));
                        break;
                    }
                }
                values.putIfAbsent(name, null);
            }
            rows.add(Map.of("documentId", doc[0], "title", doc[1], "versionNo", doc[4], "values", values, "sources", sources));
        }
        return Map.of("documents", rows);
    }

    @SuppressWarnings("unchecked")
    private Object rule(RunRow run, Map<String, Object> config, List<Object> outputs) {
        var rules = config.get("rules") instanceof List<?> l ? l : List.of();
        var subjects = new ArrayList<Map<String, Object>>();
        var previous = outputs.isEmpty() ? null : outputs.getLast();
        // Subjects: extracted documents, or a flat record built from tool rows.
        if (previous instanceof Map<?, ?> p && p.get("documents") instanceof List<?> docs) {
            for (var d : docs) if (d instanceof Map<?, ?> dm) {
                var vals = new LinkedHashMap<String, Object>((Map<String, Object>) dm.get("values"));
                vals.put("_title", dm.get("title")); vals.put("_documentId", dm.get("documentId"));
                subjects.add(vals);
            }
        } else {
            var flat = new LinkedHashMap<String, Object>();
            long totalOnHand = 0; boolean sawInventory = false;
            for (var o : outputs) if (o instanceof Map<?, ?> m && m.get("rows") instanceof List<?> rows)
                for (var r : rows) if (r instanceof Map<?, ?> row) {
                    row.forEach((k, v) -> flat.putIfAbsent(String.valueOf(k), v));
                    if (row.get("on_hand") instanceof Number num) { totalOnHand += num.longValue(); sawInventory = true; }
                }
            if (sawInventory) flat.put("totalOnHand", totalOnHand);
            subjects.add(flat);
        }
        var results = new ArrayList<Map<String, Object>>();
        boolean allPass = true;
        for (var s : subjects) {
            var checks = new ArrayList<Map<String, Object>>();
            for (var r : rules) {
                if (!(r instanceof Map<?, ?> rule)) continue;
                String field = String.valueOf(rule.get("field")), op = String.valueOf(rule.get("op"));
                Object expected = render(rule.get("value"), run, outputs);
                Object actual = s.get(field);
                boolean pass = evaluate(actual, op, expected);
                allPass &= pass;
                checks.add(new LinkedHashMap<>(Map.of("field", field, "op", op, "expected", String.valueOf(expected), "actual", String.valueOf(actual), "pass", pass)));
            }
            var entry = new LinkedHashMap<String, Object>();
            entry.put("subject", s.getOrDefault("_title", s.getOrDefault("part_no", "result")));
            entry.put("checks", checks);
            results.add(entry);
        }
        return Map.of("pass", allPass, "results", results);
    }
    static boolean evaluate(Object actual, String op, Object expected) {
        if (op.equals("exists")) return actual != null && !String.valueOf(actual).isBlank();
        if (actual == null) return false;
        if (op.equals("contains")) return String.valueOf(actual).toLowerCase(Locale.ROOT).contains(String.valueOf(expected).toLowerCase(Locale.ROOT));
        if (op.equals("eq")) return String.valueOf(actual).equalsIgnoreCase(String.valueOf(expected));
        try {
            double a = Double.parseDouble(String.valueOf(actual)), e = Double.parseDouble(String.valueOf(expected));
            return switch (op) { case "gte" -> a >= e; case "lte" -> a <= e; case "gt" -> a > e; case "lt" -> a < e; default -> false; };
        } catch (NumberFormatException ex) { return false; }
    }

    private Object tool(RunRow run, AgentDefinition def, UUID stepId, Map<String, Object> config, List<Object> outputs) {
        String toolId = String.valueOf(config.get("tool"));
        @SuppressWarnings("unchecked")
        var params = (Map<String, Object>) render(config.getOrDefault("params", Map.of()), run, outputs);
        if (!def.tools().contains(toolId)) { recordCall(run, stepId, toolId, false, params, null, "DENIED"); throw new ApiException(403, "TOOL_NOT_WHITELISTED", "Tool is not whitelisted for this agent."); }
        try {
            var result = tools.execute(run.orgId(), toolId, params);
            recordCall(run, stepId, toolId, false, params, result.data(), "SUCCEEDED");
            return Map.of("tool", toolId, "rows", result.data() instanceof List<?> l ? l : List.of(result.data()), "summary", result.summary());
        } catch (ApiException e) {
            recordCall(run, stepId, toolId, false, params, Map.of("error", e.code), e.status == 403 ? "DENIED" : "FAILED");
            throw e;
        }
    }

    private void requestApproval(RunRow run, UUID stepId, Map<String, Object> config, List<Object> outputs) {
        String summary = String.valueOf(render(config.getOrDefault("summary", "請審核此執行結果"), run, outputs));
        var payload = Map.of("previous", outputs.isEmpty() ? Map.of() : Objects.requireNonNullElse(outputs.getLast(), Map.of()));
        tx.executeWithoutResult(s -> {
            db.sql("insert into approvals(id, org_id, run_id, step_id, status, summary, payload, requested_by) values (:id, :o, :r, :s, 'PENDING', :sum, cast(:p as jsonb), :u)")
                    .param("id", UUID.randomUUID()).param("o", run.orgId()).param("r", run.id()).param("s", stepId)
                    .param("sum", summary.length() > 500 ? summary.substring(0, 500) : summary).param("p", json.write(payload)).param("u", run.actor()).update();
            db.sql("update workflow_steps set status = 'WAITING_APPROVAL' where id = :id").param("id", stepId).update();
            db.sql("update workflow_runs set status = 'WAITING_APPROVAL', updated_at = now() where id = :id").param("id", run.id()).update();
        });
    }

    /** The only side-effecting step. AGENT_OUTPUTS writes require an APPROVED approval in this same run (HITL) and are idempotent per run. */
    private Object output(RunRow run, AgentDefinition def, UUID stepId, Map<String, Object> config, List<Object> outputs) {
        var last = outputs.isEmpty() ? Map.of() : Objects.requireNonNullElse(outputs.getLast(), Map.of());
        if (!"AGENT_OUTPUTS".equals(config.get("target"))) return Map.of("target", "RUN", "result", findResult(outputs));
        if (!def.tools().contains("output.record")) { recordCall(run, stepId, "output.record", true, Map.of(), null, "DENIED"); throw new ApiException(403, "TOOL_NOT_WHITELISTED", "output.record is not whitelisted."); }
        var approval = db.sql("select id from approvals where run_id = :r and status = 'APPROVED' order by decided_at desc limit 1").param("r", run.id()).query(UUID.class).optional();
        if (approval.isEmpty()) {
            recordCall(run, stepId, "output.record", true, Map.of(), null, "DENIED");
            audit.record(run.orgId(), run.actor(), "agent.output.denied", "workflow_run", run.id(), AuditService.Result.DENIED, Map.of("reason", "no approval"));
            throw new ApiException(403, "APPROVAL_REQUIRED", "Writing results requires an approved review.");
        }
        var payload = Map.of("result", findResult(outputs), "approvalId", approval.get().toString());
        var id = UUID.randomUUID();
        db.sql("insert into agent_outputs(id, org_id, run_id, approval_id, payload) values (:id, :o, :r, :a, cast(:p as jsonb)) on conflict (run_id) do nothing")
                .param("id", id).param("o", run.orgId()).param("r", run.id()).param("a", approval.get()).param("p", json.write(payload)).update();
        recordCall(run, stepId, "output.record", true, Map.of("approvalId", approval.get().toString()), Map.of("recorded", true), "SUCCEEDED");
        audit.record(run.orgId(), run.actor(), "agent.output.record", "workflow_run", run.id(), AuditService.Result.SUCCESS, Map.of("approval", approval.get().toString()));
        return Map.of("target", "AGENT_OUTPUTS", "recorded", true, "result", payload.get("result"), "previous", last);
    }
    private Object findResult(List<Object> outputs) {
        for (int i = outputs.size() - 1; i >= 0; i--) if (outputs.get(i) != null) return outputs.get(i);
        return Map.of();
    }
    private void recordCall(RunRow run, UUID stepId, String tool, boolean sideEffect, Object request, Object response, String status) {
        db.sql("insert into tool_calls(id, org_id, run_id, step_id, tool, side_effect, request, response, status) values (:id, :o, :r, :s, :t, :se, cast(:req as jsonb), cast(:res as jsonb), :st)")
                .param("id", UUID.randomUUID()).param("o", run.orgId()).param("r", run.id()).param("s", stepId).param("t", tool).param("se", sideEffect)
                .param("req", json.write(request)).param("res", response == null ? null : json.write(response)).param("st", status).update();
    }

    @Override public void onFinalFailure(JobQueue.Job job, String error) {
        finish(UUID.fromString((String) job.payload().get("runId")), "FAILED", null, error);
    }
}
