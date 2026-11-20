package dev.knowledge.platform.ops;

import dev.knowledge.platform.core.*;
import dev.knowledge.platform.identity.*;
import dev.knowledge.platform.jobs.JobQueue;
import dev.knowledge.platform.rag.ModelGateway.GatedModels;
import dev.knowledge.platform.rag.VectorStore;
import dev.knowledge.platform.storage.ObjectStore;
import dev.knowledge.platform.tenant.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("platform")
public class OpsController {
    public record QuotaRequest(long monthlyTokenLimit, long monthlyRequestLimit, int requestsPerMinute) {}
    public record AuditEvent(long id, String actorId, String actorEmail, String action, String resourceType, String resourceId, String result,
                             String traceId, Map<String, Object> detail, String createdAt) {}
    public record Component(String name, String status, String detail) {}
    public record Health(String status, List<Component> components) {}
    public record UserPatch(String status) {}

    private final TenantAccess access;
    private final CurrentUser current;
    private final UsageService usage;
    private final JdbcClient db;
    private final Json json;
    private final GatedModels models;
    private final VectorStore vectors;
    private final ObjectStore objects;
    private final JobQueue jobs;
    private final UserRepository users;
    private final SessionRevoker revoker;
    private final AuditService audit;
    private final boolean registrationOpen;
    private final String llmUrl, embeddingUrl, rerankerUrl;

    public OpsController(TenantAccess access, CurrentUser current, UsageService usage, JdbcClient db, Json json, GatedModels models, VectorStore vectors,
                         ObjectStore objects, JobQueue jobs, UserRepository users, SessionRevoker revoker, AuditService audit,
                         @Value("${atlas.registration.open:false}") boolean registrationOpen, @Value("${atlas.models.llm-url}") String llmUrl,
                         @Value("${atlas.models.embedding-url}") String embeddingUrl, @Value("${atlas.models.reranker-url}") String rerankerUrl) {
        this.access = access; this.current = current; this.usage = usage; this.db = db; this.json = json; this.models = models; this.vectors = vectors;
        this.objects = objects; this.jobs = jobs; this.users = users; this.revoker = revoker; this.audit = audit; this.registrationOpen = registrationOpen;
        this.llmUrl = llmUrl; this.embeddingUrl = embeddingUrl; this.rerankerUrl = rerankerUrl;
    }

    @GetMapping("/api/status") Map<String, Object> status() {
        return Map.of("mode", "PLATFORM", "version", "atlas-platform-v1", "registrationOpen", registrationOpen,
                "models", models.options(), "ocr", "DISABLED");
    }

    @GetMapping("/api/v2/orgs/{orgId}/usage") UsageService.Summary usage(@PathVariable String orgId, @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        var ctx = access.require(orgId, Role.ADMIN);
        Instant end = to == null ? Instant.now() : parse(to);
        Instant start = from == null ? end.minus(Duration.ofDays(30)) : parse(from);
        if (!start.isBefore(end) || Duration.between(start, end).toDays() > 366) throw Errors.bad("INVALID_RANGE", "Choose a range of up to one year.");
        return usage.summary(ctx.orgId(), start, end);
    }
    private static Instant parse(String value) { try { return Instant.parse(value); } catch (RuntimeException e) { throw Errors.bad("INVALID_RANGE", "Use ISO-8601 instants."); } }

    @GetMapping("/api/v2/orgs/{orgId}/quota") UsageService.Quota quota(@PathVariable String orgId) { return usage.quota(access.require(orgId).orgId()); }
    @PutMapping("/api/v2/orgs/{orgId}/quota") UsageService.Quota setQuota(@PathVariable String orgId, @RequestBody QuotaRequest b) {
        var user = current.require();
        var ctx = access.require(orgId);
        if (ctx.role() != Role.OWNER && !user.systemAdmin()) throw Errors.forbidden("Only an owner or system administrator can change quotas.");
        var q = usage.updateQuota(ctx.orgId(), b.monthlyTokenLimit(), b.monthlyRequestLimit(), b.requestsPerMinute());
        audit.record(ctx.orgId(), ctx.userId(), "quota.update", "organization", ctx.orgId(), AuditService.Result.SUCCESS,
                Map.of("tokens", b.monthlyTokenLimit(), "requests", b.monthlyRequestLimit(), "perMinute", b.requestsPerMinute()));
        return q;
    }

    @GetMapping("/api/v2/orgs/{orgId}/audit") List<AuditEvent> audit(@PathVariable String orgId, @RequestParam(defaultValue = "100") int limit,
                                                                     @RequestParam(required = false) Long before, @RequestParam(required = false) String action) {
        var ctx = access.require(orgId, Role.ADMIN);
        var sql = new StringBuilder("select e.*, u.email from audit_events e left join users u on u.id = e.actor_id where e.org_id = :o");
        if (before != null) sql.append(" and e.id < :b");
        if (action != null && !action.isBlank()) sql.append(" and e.action like :a");
        sql.append(" order by e.id desc limit :l");
        var q = db.sql(sql.toString()).param("o", ctx.orgId()).param("l", Math.min(Math.max(limit, 1), 500));
        if (before != null) q = q.param("b", before);
        if (action != null && !action.isBlank()) q = q.param("a", action.replace("%", "") + "%");
        return q.query((rs, n) -> new AuditEvent(rs.getLong("id"), rs.getString("actor_id"), rs.getString("email"), rs.getString("action"), rs.getString("resource_type"),
                rs.getString("resource_id"), rs.getString("result"), rs.getString("trace_id"), json.map(rs.getString("detail")), Db.iso(rs, "created_at"))).list();
    }

    /** Frozen evaluation artefacts are read-only; new evaluations are written to separate files (see EVALUATION_REPORT.md). */
    @GetMapping("/api/v2/orgs/{orgId}/evaluation") Map<String, Object> evaluation(@PathVariable String orgId) {
        access.require(orgId);
        var reports = new ArrayList<Map<String, Object>>();
        for (var name : List.of("eval/results.json", "eval/retrieval-comparison.json", "eval/platform-v1-results.json")) {
            var path = Path.of(name);
            if (!Files.isRegularFile(path)) continue;
            try {
                var tree = json.tree(Files.readString(path));
                var summary = new LinkedHashMap<String, Object>();
                summary.put("file", name);
                summary.put("frozen", !name.contains("platform-v1"));
                for (var key : List.of("summary", "metrics", "conditions", "generatedAt", "configuration", "totals"))
                    if (tree.has(key)) summary.put(key, json.mapper().convertValue(tree.get(key), Object.class));
                reports.add(summary);
            } catch (Exception e) { reports.add(Map.of("file", name, "error", "unreadable")); }
        }
        return Map.of("reports", reports, "note", "Frozen benchmark files are never rewritten by the platform. Citation validity is not semantic accuracy.");
    }

    // ----- system administration -----
    @GetMapping("/api/v2/admin/health") Health health() {
        current.requireSystemAdmin();
        var components = new ArrayList<Component>();
        try { db.sql("select 1").query(Integer.class).single(); components.add(new Component("postgresql", "UP", "connected")); }
        catch (RuntimeException e) { components.add(new Component("postgresql", "DOWN", "unreachable")); }
        components.add(new Component("object-storage", objects.health(), ""));
        components.add(new Component("qdrant", vectors.health(), ""));
        for (var option : models.options()) components.add(new Component("model:" + option.id(), option.available() ? probe(option.id()) : "DISABLED", option.model()));
        components.add(new Component("embedding", models.options().getFirst().available() ? probeUrl(embeddingUrl) : "DISABLED", "bge-m3"));
        components.add(new Component("reranker", models.options().getFirst().available() ? probeUrl(rerankerUrl) : "DISABLED", "bge-reranker-v2-m3"));
        components.add(new Component("jobs", "UP", json.write(jobs.countsByState())));
        components.add(new Component("ocr", "DISABLED", "No OCR engine is bundled; scanned PDFs report NEEDS_OCR."));
        boolean down = components.stream().anyMatch(c -> c.status().equals("DOWN"));
        return new Health(down ? "DEGRADED" : "UP", components);
    }
    private String probe(String id) { return id.equals("fast") ? probeUrl(llmUrl) : "ON_DEMAND"; }
    private static String probeUrl(String origin) {
        try {
            var client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var res = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/health")).timeout(Duration.ofSeconds(3)).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            return res.statusCode() == 200 ? "UP" : "DOWN";
        } catch (Exception e) { return "DOWN"; }
    }
    @GetMapping("/api/v2/admin/orgs") List<Map<String, Object>> orgs() {
        current.requireSystemAdmin();
        return db.sql("""
                select o.id, o.name, o.slug, o.status, o.plan, o.created_at,
                  (select count(*) from memberships m where m.org_id = o.id) members,
                  (select count(*) from documents d where d.org_id = o.id and d.deleted_at is null) documents
                from organizations o order by o.created_at""")
                .query((rs, n) -> Map.<String, Object>of("id", rs.getString("id"), "name", rs.getString("name"), "slug", rs.getString("slug"), "status", rs.getString("status"),
                        "plan", rs.getString("plan"), "createdAt", Db.iso(rs, "created_at"), "members", rs.getLong("members"), "documents", rs.getLong("documents"))).list();
    }
    @GetMapping("/api/v2/admin/users") List<Map<String, Object>> users() {
        current.requireSystemAdmin();
        return users.all(1000).stream().map(u -> Map.<String, Object>of("id", u.id().toString(), "email", u.email(), "displayName", u.displayName(),
                "status", u.status(), "systemRole", u.systemRole())).toList();
    }
    @PatchMapping("/api/v2/admin/users/{id}") Map<String, Object> patchUser(@PathVariable String id, @RequestBody UserPatch b) {
        var admin = current.requireSystemAdmin();
        UUID userId = Db.parse(id, "user");
        if (!Set.of("ACTIVE", "DISABLED").contains(b.status())) throw Errors.bad("INVALID_STATUS", "Status must be ACTIVE or DISABLED.");
        if (userId.equals(admin.id())) throw Errors.bad("SELF_CHANGE", "You cannot disable yourself.");
        users.byId(userId).orElseThrow(() -> Errors.notFound("user"));
        users.updateStatus(userId, b.status());
        if (b.status().equals("DISABLED")) revoker.revokeAll(userId, null);
        audit.record(null, admin.id(), "admin.user.status", "user", userId, AuditService.Result.SUCCESS, Map.of("status", b.status()));
        return Map.of("id", id, "status", b.status());
    }
    /** Job metadata only (no payloads: they may contain questions). */
    @GetMapping("/api/v2/admin/jobs") List<Map<String, Object>> adminJobs() {
        current.requireSystemAdmin();
        return jobs.listAll(200).stream().map(j -> Map.<String, Object>of("id", j.id().toString(), "orgId", j.orgId().toString(), "type", j.type(), "state", j.state(),
                "attempts", j.attempts(), "lastError", j.lastError() == null ? "" : j.lastError(), "createdAt", j.createdAt(), "updatedAt", j.updatedAt(), "traceId", j.traceId())).toList();
    }
}
