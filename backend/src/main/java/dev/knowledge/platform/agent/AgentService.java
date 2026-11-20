package dev.knowledge.platform.agent;

import dev.knowledge.ApiException;
import dev.knowledge.platform.agent.AgentModels.*;
import dev.knowledge.platform.core.*;
import dev.knowledge.platform.integration.ToolRegistry;
import dev.knowledge.platform.jobs.JobQueue;
import dev.knowledge.platform.ops.*;
import dev.knowledge.platform.tenant.*;
import dev.knowledge.platform.tenant.TenantAccess.OrgContext;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("platform")
public class AgentService {
    public static final String AGENT_RUN = "AGENT_RUN";
    public record Agent(String id, String name, String template, String description, boolean enabled, int version, AgentDefinition definition, String updatedAt) {}
    public record RunStep(int ordinal, String type, String name, String status, Object output, String error, String startedAt, String finishedAt) {}
    public record Run(String id, String agentId, String agentName, int agentVersion, String status, Map<String, Object> input, Object output, String error,
                      String traceId, String actorId, String createdAt, String updatedAt, List<RunStep> steps) {}
    public record Approval(String id, String runId, String agentName, String status, String summary, Object payload, String requestedBy, String decidedBy,
                           String decidedAt, String comment, String createdAt) {}

    private final JdbcClient db;
    private final Json json;
    private final TenantAccess access;
    private final ToolRegistry tools;
    private final JobQueue jobs;
    private final UsageService usage;
    private final AuditService audit;
    public AgentService(JdbcClient db, Json json, TenantAccess access, ToolRegistry tools, JobQueue jobs, UsageService usage, AuditService audit) {
        this.db = db; this.json = json; this.access = access; this.tools = tools; this.jobs = jobs; this.usage = usage; this.audit = audit;
    }

    private Agent map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Agent(rs.getString("id"), rs.getString("name"), rs.getString("template"), rs.getString("description"), rs.getBoolean("enabled"),
                rs.getInt("current_version"), json.read(rs.getString("definition"), AgentDefinition.class), Db.iso(rs, "updated_at"));
    }
    private static final String SELECT = "select a.*, v.definition from agents a join agent_versions v on v.agent_id = a.id and v.version_no = a.current_version and v.org_id = a.org_id";

    public List<Agent> list(OrgContext ctx) { return db.sql(SELECT + " where a.org_id = :o order by a.updated_at desc").param("o", ctx.orgId()).query((rs, n) -> map(rs)).list(); }
    public Agent get(OrgContext ctx, String idText) {
        return db.sql(SELECT + " where a.org_id = :o and a.id = :id").param("o", ctx.orgId()).param("id", Db.parse(idText, "agent"))
                .query((rs, n) -> map(rs)).optional().orElseThrow(() -> Errors.notFound("agent"));
    }

    @Transactional
    public Agent create(OrgContext ctx, String name, String template, String description, AgentDefinition definition) {
        access.requireRole(ctx, Role.EDITOR);
        if (AgentModels.templates().stream().noneMatch(t -> t.template().equals(template))) throw Errors.bad("INVALID_TEMPLATE", "Unknown agent template.");
        validate(ctx, name, description, definition);
        var id = UUID.randomUUID();
        db.sql("insert into agents(id, org_id, name, template, description, created_by) values (:id, :o, :n, :t, :d, :u)")
                .param("id", id).param("o", ctx.orgId()).param("n", name.strip()).param("t", template).param("d", description == null ? "" : description.strip()).param("u", ctx.userId()).update();
        db.sql("insert into agent_versions(org_id, agent_id, version_no, definition, created_by) values (:o, :a, 1, cast(:d as jsonb), :u)")
                .param("o", ctx.orgId()).param("a", id).param("d", json.write(definition)).param("u", ctx.userId()).update();
        audit.record(ctx.orgId(), ctx.userId(), "agent.create", "agent", id, AuditService.Result.SUCCESS, Map.of("template", template, "tools", definition.tools()));
        return get(ctx, id.toString());
    }
    /** Every update creates a new immutable version; running/queued runs keep the version they started with. */
    @Transactional
    public Agent update(OrgContext ctx, String idText, String name, String description, AgentDefinition definition, Boolean enabled) {
        access.requireRole(ctx, Role.EDITOR);
        var agent = get(ctx, idText);
        var def = definition == null ? agent.definition() : definition;
        validate(ctx, name == null ? agent.name() : name, description, def);
        int version = agent.version();
        if (definition != null) {
            version++;
            db.sql("insert into agent_versions(org_id, agent_id, version_no, definition, created_by) values (:o, :a, :v, cast(:d as jsonb), :u)")
                    .param("o", ctx.orgId()).param("a", UUID.fromString(agent.id())).param("v", version).param("d", json.write(def)).param("u", ctx.userId()).update();
        }
        db.sql("update agents set name = :n, description = :d, enabled = :e, current_version = :v, updated_at = now() where id = :id and org_id = :o")
                .param("n", name == null ? agent.name() : name.strip()).param("d", description == null ? agent.description() : description.strip())
                .param("e", enabled == null ? agent.enabled() : enabled).param("v", version).param("id", UUID.fromString(agent.id())).param("o", ctx.orgId()).update();
        audit.record(ctx.orgId(), ctx.userId(), "agent.update", "agent", agent.id(), AuditService.Result.SUCCESS, Map.of("version", version));
        return get(ctx, idText);
    }

    void validate(OrgContext ctx, String name, String description, AgentDefinition def) {
        if (name == null || name.isBlank() || name.length() > 120) throw Errors.bad("INVALID_NAME", "Name must be 1–120 characters.");
        if (description != null && description.length() > 1000) throw Errors.bad("INVALID_DESCRIPTION", "Description is too long.");
        if (def == null || def.steps().isEmpty() || def.steps().size() > 20) throw Errors.bad("INVALID_DEFINITION", "An agent needs 1–20 steps.");
        if (!Set.of("fast", "quality").contains(def.model())) throw Errors.bad("INVALID_MODEL", "Model must be fast or quality.");
        if (def.systemPrompt().length() > 4000) throw Errors.bad("INVALID_DEFINITION", "System prompt is limited to 4000 characters.");
        if (def.maxRunsPerDay() < 1 || def.maxRunsPerDay() > 10_000) throw Errors.bad("INVALID_DEFINITION", "maxRunsPerDay must be 1–10000.");
        var readable = access.readableKbIds(ctx);
        for (var kb : def.kbIds()) if (!readable.contains(Db.parse(kb, "knowledge base"))) throw Errors.notFound("knowledge base");
        for (var tool : def.tools()) if (tools.find(ctx.orgId(), tool).isEmpty()) throw Errors.bad("UNKNOWN_TOOL", "Tool is not available: " + tool);
        boolean approvalSeen = false;
        for (var step : def.steps()) {
            if (step.type() == null || !AgentModels.STEP_TYPES.contains(step.type())) throw Errors.bad("INVALID_STEP", "Unknown step type.");
            var config = step.config() == null ? Map.<String, Object>of() : step.config();
            if (step.type().equals("APPROVAL")) approvalSeen = true;
            if (step.type().equals("TOOL")) {
                var tool = String.valueOf(config.get("tool"));
                if (!def.tools().contains(tool)) throw Errors.bad("TOOL_NOT_WHITELISTED", "Step uses a tool that is not in the agent's tool whitelist: " + tool);
                var info = tools.find(ctx.orgId(), tool).orElseThrow(() -> Errors.bad("UNKNOWN_TOOL", "Tool is not available: " + tool));
                if (info.sideEffect()) throw Errors.bad("SIDE_EFFECT_TOOL", "Side-effecting tools can only be used through an OUTPUT step after APPROVAL.");
            }
            if (step.type().equals("OUTPUT") && "AGENT_OUTPUTS".equals(config.get("target"))) {
                if (!def.tools().contains("output.record")) throw Errors.bad("TOOL_NOT_WHITELISTED", "Writing results requires the output.record tool in the whitelist.");
                if (!approvalSeen) throw Errors.bad("APPROVAL_REQUIRED", "A write OUTPUT step must come after an APPROVAL step.");
            }
            if (step.type().equals("EXTRACT") && config.get("fields") instanceof List<?> fields)
                for (var f : fields) if (f instanceof Map<?, ?> m) {
                    try { java.util.regex.Pattern.compile(String.valueOf(m.get("pattern"))); }
                    catch (RuntimeException e) { throw Errors.bad("INVALID_PATTERN", "Invalid extraction pattern."); }
                    if (String.valueOf(m.get("pattern")).length() > 300) throw Errors.bad("INVALID_PATTERN", "Extraction pattern too long.");
                }
        }
    }

    // ----- runs -----
    @Transactional
    public Run start(OrgContext ctx, String agentText, Map<String, Object> input) {
        var agent = get(ctx, agentText);
        if (!agent.enabled()) throw Errors.conflict("AGENT_DISABLED", "This agent is disabled.");
        if (input == null || json.write(input).length() > 20_000) throw Errors.bad("INVALID_INPUT", "Input is required and limited to 20 KB.");
        var readable = access.readableKbIds(ctx);
        for (var kb : agent.definition().kbIds()) if (!readable.contains(UUID.fromString(kb))) throw Errors.forbidden("You cannot read all knowledge bases this agent uses.");
        long today = db.sql("select count(*) from workflow_runs where org_id = :o and agent_id = :a and created_at > now() - interval '1 day'")
                .param("o", ctx.orgId()).param("a", UUID.fromString(agent.id())).query(Long.class).single();
        if (today >= agent.definition().maxRunsPerDay()) throw new ApiException(429, "AGENT_RUN_LIMIT", "This agent reached its daily run limit.");
        usage.admit(ctx.orgId());
        var runId = UUID.randomUUID();
        db.sql("insert into workflow_runs(id, org_id, agent_id, agent_version, actor_id, status, input, trace_id) values (:id, :o, :a, :v, :u, 'QUEUED', cast(:i as jsonb), :t)")
                .param("id", runId).param("o", ctx.orgId()).param("a", UUID.fromString(agent.id())).param("v", agent.version()).param("u", ctx.userId())
                .param("i", json.write(input)).param("t", Trace.current()).update();
        int ordinal = 0;
        for (var step : agent.definition().steps())
            db.sql("insert into workflow_steps(id, org_id, run_id, ordinal, type, name, status) values (:id, :o, :r, :n, :t, :name, 'PENDING')")
                    .param("id", UUID.randomUUID()).param("o", ctx.orgId()).param("r", runId).param("n", ordinal++).param("t", step.type())
                    .param("name", step.name() == null ? step.type() : step.name()).update();
        jobs.enqueue(ctx.orgId(), AGENT_RUN, Map.of("runId", runId.toString()), ctx.userId(), "run:" + runId + ":0", 120, 3);
        audit.record(ctx.orgId(), ctx.userId(), "agent.run.start", "workflow_run", runId, AuditService.Result.SUCCESS, Map.of("agent", agent.id(), "version", agent.version()));
        return run(ctx, runId.toString());
    }

    public Run run(OrgContext ctx, String runText) {
        UUID id = Db.parse(runText, "run");
        var run = db.sql("select r.*, a.name as agent_name from workflow_runs r join agents a on a.id = r.agent_id where r.org_id = :o and r.id = :id")
                .param("o", ctx.orgId()).param("id", id).query((rs, n) -> new Run(rs.getString("id"), rs.getString("agent_id"), rs.getString("agent_name"),
                        rs.getInt("agent_version"), rs.getString("status"), json.map(rs.getString("input")), json.any(rs.getString("output")), rs.getString("error"),
                        rs.getString("trace_id"), rs.getString("actor_id"), Db.iso(rs, "created_at"), Db.iso(rs, "updated_at"), List.of()))
                .optional().orElseThrow(() -> Errors.notFound("run"));
        // Runs are visible to their actor, approvers (EDITOR+) and admins; viewers only see their own.
        if (!ctx.role().atLeast(Role.EDITOR) && !run.actorId().equals(ctx.userId().toString())) throw Errors.notFound("run");
        var steps = db.sql("select * from workflow_steps where run_id = :r order by ordinal").param("r", id)
                .query((rs, n) -> new RunStep(rs.getInt("ordinal"), rs.getString("type"), rs.getString("name"), rs.getString("status"), json.any(rs.getString("output")),
                        rs.getString("error"), Db.iso(rs, "started_at"), Db.iso(rs, "finished_at"))).list();
        return new Run(run.id(), run.agentId(), run.agentName(), run.agentVersion(), run.status(), run.input(), run.output(), run.error(), run.traceId(), run.actorId(),
                run.createdAt(), run.updatedAt(), steps);
    }
    public List<Run> runs(OrgContext ctx, String agentText) {
        var sql = new StringBuilder("select r.id from workflow_runs r where r.org_id = :o");
        if (agentText != null) sql.append(" and r.agent_id = :a");
        if (!ctx.role().atLeast(Role.EDITOR)) sql.append(" and r.actor_id = :u");
        sql.append(" order by r.created_at desc limit 100");
        var q = db.sql(sql.toString()).param("o", ctx.orgId());
        if (agentText != null) q = q.param("a", Db.parse(agentText, "agent"));
        if (!ctx.role().atLeast(Role.EDITOR)) q = q.param("u", ctx.userId());
        return q.query(String.class).list().stream().map(id -> run(ctx, id)).toList();
    }
    public Run cancel(OrgContext ctx, String runText) {
        var run = run(ctx, runText);
        if (!run.actorId().equals(ctx.userId().toString()) && !ctx.admin()) throw Errors.forbidden("Only the run's actor or an admin can cancel it.");
        int n = db.sql("update workflow_runs set status = 'CANCELLED', updated_at = now() where id = :id and status in ('QUEUED','RUNNING','WAITING_APPROVAL')")
                .param("id", UUID.fromString(run.id())).update();
        if (n == 0) throw Errors.conflict("NOT_CANCELLABLE", "The run already finished.");
        db.sql("update workflow_steps set status = 'CANCELLED' where run_id = :id and status in ('PENDING','RUNNING','WAITING_APPROVAL')").param("id", UUID.fromString(run.id())).update();
        db.sql("update approvals set status = 'CANCELLED' where run_id = :id and status = 'PENDING'").param("id", UUID.fromString(run.id())).update();
        audit.record(ctx.orgId(), ctx.userId(), "agent.run.cancel", "workflow_run", run.id());
        return run(ctx, runText);
    }
    public Run retry(OrgContext ctx, String runText) {
        var run = run(ctx, runText);
        if (!run.actorId().equals(ctx.userId().toString()) && !ctx.admin()) throw Errors.forbidden("Only the run's actor or an admin can retry it.");
        if (!run.status().equals("FAILED")) throw Errors.conflict("NOT_RETRYABLE", "Only failed runs can be retried.");
        db.sql("update workflow_steps set status = 'PENDING', error = null, output = null where run_id = :id and status = 'FAILED'").param("id", UUID.fromString(run.id())).update();
        db.sql("update workflow_runs set status = 'QUEUED', error = null, updated_at = now() where id = :id").param("id", UUID.fromString(run.id())).update();
        jobs.enqueue(ctx.orgId(), AGENT_RUN, Map.of("runId", run.id()), ctx.userId(), "run:" + run.id() + ":retry:" + UUID.randomUUID(), 120, 3);
        audit.record(ctx.orgId(), ctx.userId(), "agent.run.retry", "workflow_run", run.id());
        return run(ctx, runText);
    }

    // ----- approvals -----
    public List<Approval> approvals(OrgContext ctx, String status) {
        access.requireRole(ctx, Role.EDITOR);
        var q = db.sql("select ap.*, a.name as agent_name from approvals ap join workflow_runs r on r.id = ap.run_id join agents a on a.id = r.agent_id where ap.org_id = :o"
                + (status == null ? "" : " and ap.status = :s") + " order by ap.created_at desc limit 200").param("o", ctx.orgId());
        if (status != null) q = q.param("s", status);
        return q.query((rs, n) -> new Approval(rs.getString("id"), rs.getString("run_id"), rs.getString("agent_name"), rs.getString("status"), rs.getString("summary"),
                json.any(rs.getString("payload")), rs.getString("requested_by"), rs.getString("decided_by"), Db.iso(rs, "decided_at"), rs.getString("comment"), Db.iso(rs, "created_at"))).list();
    }
    @Transactional
    public Approval decide(OrgContext ctx, String approvalText, boolean approve, String comment) {
        access.requireRole(ctx, Role.EDITOR);
        UUID id = Db.parse(approvalText, "approval");
        var row = db.sql("select run_id, step_id, requested_by, status from approvals where org_id = :o and id = :id for update").param("o", ctx.orgId()).param("id", id)
                .query((rs, n) -> new Object[]{Db.uuid(rs, "run_id"), Db.uuid(rs, "step_id"), Db.uuid(rs, "requested_by"), rs.getString("status")})
                .optional().orElseThrow(() -> Errors.notFound("approval"));
        if (!"PENDING".equals(row[3])) throw Errors.conflict("ALREADY_DECIDED", "This approval was already decided.");
        if (ctx.userId().equals(row[2]) && !ctx.admin()) {
            audit.record(ctx.orgId(), ctx.userId(), "approval.self", "approval", id, AuditService.Result.DENIED, Map.of());
            throw Errors.forbidden("Requesters cannot approve their own runs unless they are an org admin.");
        }
        if (comment != null && comment.length() > 500) throw Errors.bad("INVALID_COMMENT", "Comment is limited to 500 characters.");
        db.sql("update approvals set status = :s, decided_by = :u, decided_at = now(), comment = :c where id = :id")
                .param("s", approve ? "APPROVED" : "REJECTED").param("u", ctx.userId()).param("c", comment).param("id", id).update();
        UUID run = (UUID) row[0];
        if (approve) {
            db.sql("update workflow_steps set status = 'SUCCEEDED', finished_at = now(), output = cast(:o as jsonb) where id = :s")
                    .param("o", json.write(Map.of("approvedBy", ctx.userId().toString(), "comment", comment == null ? "" : comment))).param("s", row[1]).update();
            db.sql("update workflow_runs set status = 'QUEUED', updated_at = now() where id = :r and status = 'WAITING_APPROVAL'").param("r", run).update();
            jobs.enqueue(ctx.orgId(), AGENT_RUN, Map.of("runId", run.toString()), ctx.userId(), "run:" + run + ":approval:" + id, 120, 3);
        } else {
            db.sql("update workflow_steps set status = 'REJECTED', finished_at = now() where id = :s").param("s", row[1]).update();
            db.sql("update workflow_steps set status = 'SKIPPED' where run_id = :r and status = 'PENDING'").param("r", run).update();
            db.sql("update workflow_runs set status = 'REJECTED', updated_at = now() where id = :r").param("r", run).update();
        }
        audit.record(ctx.orgId(), ctx.userId(), approve ? "approval.approve" : "approval.reject", "approval", id, AuditService.Result.SUCCESS, Map.of("run", run.toString()));
        return approvals(ctx, null).stream().filter(a -> a.id().equals(id.toString())).findFirst().orElseThrow();
    }
}
