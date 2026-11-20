package dev.knowledge.platform.agent;

import dev.knowledge.platform.agent.AgentModels.*;
import dev.knowledge.platform.integration.*;
import dev.knowledge.platform.tenant.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("platform") @RequestMapping("/api/v2/orgs/{orgId}")
public class AgentController {
    public record AgentRequest(String name, String template, String description, AgentDefinition definition, Boolean enabled) {}
    public record RunRequest(Map<String, Object> input) {}
    public record Decision(String comment) {}
    public record ConnectorRequest(String type, String name, Map<String, Object> config, String secretRef, Boolean enabled) {}

    private final TenantAccess access;
    private final AgentService agents;
    private final ToolRegistry tools;
    private final ConnectorService connectors;
    public AgentController(TenantAccess access, AgentService agents, ToolRegistry tools, ConnectorService connectors) {
        this.access = access; this.agents = agents; this.tools = tools; this.connectors = connectors;
    }

    @GetMapping("/agent-templates") List<TemplateInfo> templates(@PathVariable String orgId) { access.require(orgId); return AgentModels.templates(); }
    @GetMapping("/agents") List<AgentService.Agent> list(@PathVariable String orgId) { return agents.list(access.require(orgId)); }
    @PostMapping("/agents") ResponseEntity<AgentService.Agent> create(@PathVariable String orgId, @RequestBody AgentRequest b) {
        return ResponseEntity.status(201).body(agents.create(access.require(orgId), b.name(), b.template(), b.description(), b.definition()));
    }
    @GetMapping("/agents/{id}") AgentService.Agent get(@PathVariable String orgId, @PathVariable String id) { return agents.get(access.require(orgId), id); }
    @PutMapping("/agents/{id}") AgentService.Agent update(@PathVariable String orgId, @PathVariable String id, @RequestBody AgentRequest b) {
        return agents.update(access.require(orgId), id, b.name(), b.description(), b.definition(), b.enabled());
    }
    @DeleteMapping("/agents/{id}") AgentService.Agent disable(@PathVariable String orgId, @PathVariable String id) {
        return agents.update(access.require(orgId), id, null, null, null, false);
    }
    @PostMapping("/agents/{id}/runs") ResponseEntity<AgentService.Run> start(@PathVariable String orgId, @PathVariable String id, @RequestBody RunRequest b) {
        return ResponseEntity.accepted().body(agents.start(access.require(orgId), id, b.input()));
    }
    @GetMapping("/runs") List<AgentService.Run> runs(@PathVariable String orgId, @RequestParam(required = false) String agentId) { return agents.runs(access.require(orgId), agentId); }
    @GetMapping("/runs/{runId}") AgentService.Run run(@PathVariable String orgId, @PathVariable String runId) { return agents.run(access.require(orgId), runId); }
    @PostMapping("/runs/{runId}/cancel") AgentService.Run cancel(@PathVariable String orgId, @PathVariable String runId) { return agents.cancel(access.require(orgId), runId); }
    @PostMapping("/runs/{runId}/retry") AgentService.Run retry(@PathVariable String orgId, @PathVariable String runId) { return agents.retry(access.require(orgId), runId); }

    @GetMapping("/approvals") List<AgentService.Approval> approvals(@PathVariable String orgId, @RequestParam(required = false) String status) {
        return agents.approvals(access.require(orgId), status);
    }
    @PostMapping("/approvals/{id}/approve") AgentService.Approval approve(@PathVariable String orgId, @PathVariable String id, @RequestBody(required = false) Decision b) {
        return agents.decide(access.require(orgId), id, true, b == null ? null : b.comment());
    }
    @PostMapping("/approvals/{id}/reject") AgentService.Approval reject(@PathVariable String orgId, @PathVariable String id, @RequestBody(required = false) Decision b) {
        return agents.decide(access.require(orgId), id, false, b == null ? null : b.comment());
    }

    @GetMapping("/tools") List<ToolRegistry.ToolInfo> tools(@PathVariable String orgId) { return tools.catalog(access.require(orgId).orgId()); }
    @GetMapping("/connectors") List<ConnectorService.Connector> connectors(@PathVariable String orgId) { return connectors.list(access.require(orgId, Role.ADMIN).orgId()); }
    @PostMapping("/connectors") ResponseEntity<ConnectorService.Connector> createConnector(@PathVariable String orgId, @RequestBody ConnectorRequest b) {
        return ResponseEntity.status(201).body(connectors.create(access.require(orgId, Role.ADMIN), b.type(), b.name(), b.config(), b.secretRef()));
    }
    @PatchMapping("/connectors/{id}") ConnectorService.Connector patchConnector(@PathVariable String orgId, @PathVariable String id, @RequestBody ConnectorRequest b) {
        return connectors.update(access.require(orgId, Role.ADMIN), id, b.enabled(), b.config());
    }
    @DeleteMapping("/connectors/{id}") ResponseEntity<Void> deleteConnector(@PathVariable String orgId, @PathVariable String id) {
        connectors.delete(access.require(orgId, Role.ADMIN), id); return ResponseEntity.noContent().build();
    }
    /** Validates reachability rules without sending tenant data: allow-list + DNS/private-address checks only. */
    @PostMapping("/connectors/{id}/test") Map<String, Object> testConnector(@PathVariable String orgId, @PathVariable String id) {
        var ctx = access.require(orgId, Role.ADMIN);
        var c = connectors.require(ctx.orgId(), id);
        try {
            switch (c.type()) {
                case "HTTP_GET" -> tools.validateTarget(java.net.URI.create(String.valueOf(c.config().get("baseUrl"))));
                case "MCP" -> {
                    if (!tools.allowedMcpEndpoints().contains(String.valueOf(c.config().get("endpoint")).toLowerCase(Locale.ROOT)))
                        return Map.of("ok", false, "detail", "Endpoint is not on the server allow-list (ATLAS_MCP_ENDPOINTS).");
                }
                default -> { }
            }
            return Map.of("ok", true, "detail", "Configuration passes server policy checks.");
        } catch (dev.knowledge.ApiException e) { return Map.of("ok", false, "detail", e.getMessage()); }
    }
}
