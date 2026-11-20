package dev.knowledge.platform;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AgentWorkflowTest extends PlatformTestBase {

    JsonNode template(Tenant t, String name) throws Exception {
        for (var tpl : call(t.auth(), get("/api/v2/orgs/" + t.orgId() + "/agent-templates"), null, 200))
            if (tpl.path("template").asText().equals(name)) return tpl;
        throw new AssertionError("template missing");
    }
    JsonNode awaitRun(Auth auth, String orgId, String runId, Predicate<JsonNode> done) throws Exception {
        var deadline = Instant.now().plusSeconds(30);
        JsonNode run;
        do {
            run = call(auth, get("/api/v2/orgs/" + orgId + "/runs/" + runId), null, 200);
            if (done.test(run)) return run;
            Thread.sleep(100);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Run did not finish: " + run);
    }
    String readyDoc(Tenant t, String kb, String name, byte[] bytes) throws Exception {
        var id = upload(t.auth(), t.orgId(), kb, name, bytes, 202).path("id").asText();
        awaitDocument(t.auth(), t.orgId(), id, d -> d.path("status").asText().equals("READY"));
        return id;
    }

    @Test void documentCompareRequiresHumanApprovalBeforeTheControlledWrite() throws Exception {
        var t = newTenant("agents");
        var editor = invite(t, "EDITOR");
        var kb = createKb(t, "Datasheets", "ORG").path("id").asText();
        String d1 = readyDoc(t, kb, "mcu100.pdf", DocumentChatIsolationTest.pdf("MCU-100 operates from 1.8 V to 3.6 V.", "Operating temperature is -40 C to 85 C."));
        String d2 = readyDoc(t, kb, "mcu200.pdf", DocumentChatIsolationTest.pdf("MCU-200 operates from 2.7 V to 5.5 V.", "Operating temperature is -40 C to 105 C."));
        var tpl = template(t, "DOCUMENT_COMPARE");
        var base = "/api/v2/orgs/" + t.orgId();
        var agent = call(editor.auth(), post(base + "/agents"), Map.of("name", "Datasheet compare", "template", "DOCUMENT_COMPARE",
                "description", "", "definition", mapper.convertValue(tpl.path("definition"), Map.class)), 201);
        var run = call(editor.auth(), post(base + "/agents/" + agent.path("id").asText() + "/runs"),
                Map.of("input", Map.of("documentIds", List.of(d1, d2), "minVoltage", "5", "minTempC", "100")), 202);
        String runId = run.path("id").asText();
        var waiting = awaitRun(editor.auth(), t.orgId(), runId, r -> r.path("status").asText().equals("WAITING_APPROVAL"));
        var rule = waiting.path("steps").path(1).path("output");
        assertThat(rule.path("pass").asBoolean()).isFalse(); // MCU-100 fails the 5 V / 100 C thresholds
        assertThat(waiting.path("steps").path(0).path("output").toString()).contains("3.6").contains("5.5").contains("105");
        // No write happened yet.
        assertThat(db.sql("select count(*) from agent_outputs where run_id = cast(:r as uuid)").param("r", runId).query(Long.class).single()).isZero();

        var approval = call(editor.auth(), get(base + "/approvals?status=PENDING"), null, 200).path(0);
        assertThat(approval.path("runId").asText()).isEqualTo(runId);
        // The requester (non-admin) cannot approve their own run.
        call(editor.auth(), post(base + "/approvals/" + approval.path("id").asText() + "/approve"), Map.of("comment", "self"), 403);
        // Another tenant cannot see or decide it.
        var other = newTenant("intruder");
        call(other.auth(), post(base + "/approvals/" + approval.path("id").asText() + "/approve"), Map.of(), 404);
        call(other.auth(), post("/api/v2/orgs/" + other.orgId() + "/approvals/" + approval.path("id").asText() + "/approve"), Map.of(), 404);
        call(other.auth(), get("/api/v2/orgs/" + other.orgId() + "/runs/" + runId), null, 404);
        // The owner approves → the controlled write happens exactly once.
        call(t.auth(), post(base + "/approvals/" + approval.path("id").asText() + "/approve"), Map.of("comment", "reviewed"), 200);
        var done = awaitRun(editor.auth(), t.orgId(), runId, r -> r.path("status").asText().equals("SUCCEEDED"));
        assertThat(done.path("steps").path(3).path("output").path("recorded").asBoolean()).isTrue();
        assertThat(db.sql("select count(*) from agent_outputs where run_id = cast(:r as uuid)").param("r", runId).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from tool_calls where run_id = cast(:r as uuid) and tool = 'output.record' and status = 'SUCCEEDED' and side_effect")
                .param("r", runId).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("select count(*) from audit_events where action = 'approval.approve' and org_id = cast(:o as uuid)").param("o", t.orgId()).query(Long.class).single()).isEqualTo(1);

        // Rejection path: nothing is written.
        var run2 = call(editor.auth(), post(base + "/agents/" + agent.path("id").asText() + "/runs"),
                Map.of("input", Map.of("documentIds", List.of(d2), "minVoltage", "5", "minTempC", "100")), 202).path("id").asText();
        awaitRun(editor.auth(), t.orgId(), run2, r -> r.path("status").asText().equals("WAITING_APPROVAL"));
        var approval2 = call(t.auth(), get(base + "/approvals?status=PENDING"), null, 200).path(0).path("id").asText();
        call(t.auth(), post(base + "/approvals/" + approval2 + "/reject"), Map.of("comment", "no"), 200);
        assertThat(call(editor.auth(), get(base + "/runs/" + run2), null, 200).path("status").asText()).isEqualTo("REJECTED");
        assertThat(db.sql("select count(*) from agent_outputs where run_id = cast(:r as uuid)").param("r", run2).query(Long.class).single()).isZero();
    }

    @Test void definitionsCannotBypassWhitelistsOrApproval() throws Exception {
        var t = newTenant("policy");
        var base = "/api/v2/orgs/" + t.orgId();
        var writeWithoutApproval = Map.of("model", "fast", "kbIds", List.of(), "systemPrompt", "", "tools", List.of("output.record"),
                "steps", List.of(Map.of("type", "OUTPUT", "name", "write", "config", Map.of("target", "AGENT_OUTPUTS"))), "maxRunsPerDay", 10);
        call(t.auth(), post(base + "/agents"), Map.of("name", "bad", "template", "READONLY_TOOL", "description", "", "definition", writeWithoutApproval), 400);
        var toolNotWhitelisted = Map.of("model", "fast", "kbIds", List.of(), "systemPrompt", "", "tools", List.of(),
                "steps", List.of(Map.of("type", "TOOL", "name", "q", "config", Map.of("tool", "sql.part_lookup", "params", Map.of("partNo", "MCU-100")))), "maxRunsPerDay", 10);
        call(t.auth(), post(base + "/agents"), Map.of("name", "bad2", "template", "READONLY_TOOL", "description", "", "definition", toolNotWhitelisted), 400);
        var sideEffectAsTool = Map.of("model", "fast", "kbIds", List.of(), "systemPrompt", "", "tools", List.of("output.record"),
                "steps", List.of(Map.of("type", "TOOL", "name", "q", "config", Map.of("tool", "output.record"))), "maxRunsPerDay", 10);
        call(t.auth(), post(base + "/agents"), Map.of("name", "bad3", "template", "READONLY_TOOL", "description", "", "definition", sideEffectAsTool), 400);
        var viewer = invite(t, "VIEWER");
        call(viewer.auth(), post(base + "/agents"), Map.of("name", "v", "template", "KNOWLEDGE_QA", "description", "",
                "definition", mapper.convertValue(template(t, "KNOWLEDGE_QA").path("definition"), Map.class)), 403);
    }

    @Test void readOnlyToolAgentQueriesAllowListedSqlAndRejectsInjection() throws Exception {
        var t = newTenant("tools");
        var base = "/api/v2/orgs/" + t.orgId();
        var agent = call(t.auth(), post(base + "/agents"), Map.of("name", "Parts", "template", "READONLY_TOOL", "description", "",
                "definition", mapper.convertValue(template(t, "READONLY_TOOL").path("definition"), Map.class)), 201).path("id").asText();
        var ok = call(t.auth(), post(base + "/agents/" + agent + "/runs"), Map.of("input", Map.of("partNo", "MCU-100")), 202).path("id").asText();
        var run = awaitRun(t.auth(), t.orgId(), ok, r -> Set.of("SUCCEEDED", "FAILED").contains(r.path("status").asText()));
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("steps").path(1).path("output").toString()).contains("TPE-1").contains("1200");
        assertThat(run.path("steps").path(2).path("output").path("pass").asBoolean()).isTrue();
        var bad = call(t.auth(), post(base + "/agents/" + agent + "/runs"), Map.of("input", Map.of("partNo", "x' or '1'='1")), 202).path("id").asText();
        var failed = awaitRun(t.auth(), t.orgId(), bad, r -> Set.of("SUCCEEDED", "FAILED").contains(r.path("status").asText()));
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("error").asText()).contains("INVALID_PARAMETER");
    }

    @Test void knowledgeQaAgentAnswersWithCitationsFromItsKnowledgeBase() throws Exception {
        var t = newTenant("qa");
        var kb = createKb(t, "Policies", "ORG").path("id").asText();
        readyDoc(t, kb, "policy.md", "# Security\n\nPasswords must be rotated every ninety days.\n".getBytes(StandardCharsets.UTF_8));
        var base = "/api/v2/orgs/" + t.orgId();
        @SuppressWarnings("unchecked")
        Map<String, Object> def = mapper.convertValue(template(t, "KNOWLEDGE_QA").path("definition"), Map.class);
        def.put("kbIds", List.of(kb));
        var agent = call(t.auth(), post(base + "/agents"), Map.of("name", "QA", "template", "KNOWLEDGE_QA", "description", "", "definition", def), 201).path("id").asText();
        var runId = call(t.auth(), post(base + "/agents/" + agent + "/runs"), Map.of("input", Map.of("question", "How often must passwords be rotated?")), 202).path("id").asText();
        var run = awaitRun(t.auth(), t.orgId(), runId, r -> Set.of("SUCCEEDED", "FAILED").contains(r.path("status").asText()));
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("steps").path(0).path("output").path("answer").toString()).contains("ninety");
        assertThat(run.path("steps").path(0).path("output").path("citations").path(0).path("versionNo").asInt()).isEqualTo(1);
        assertThat(db.sql("select count(*) from usage_events where agent_id = cast(:a as uuid)").param("a", agent).query(Long.class).single()).isEqualTo(1);
    }
}
