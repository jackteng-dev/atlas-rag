package dev.knowledge.platform.integration;

import dev.knowledge.platform.core.*;
import dev.knowledge.platform.ops.AuditService;
import dev.knowledge.platform.tenant.TenantAccess.OrgContext;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Connector definitions per organisation. Configuration is non-secret JSON; credentials are referenced by an
 * environment variable name ({@code secretRef}) that only the server operator can set, so no secret is stored in the DB
 * or returned by the API. Hosts / MCP endpoints must additionally be on the server allow-lists.
 */
@Service @Profile("platform")
public class ConnectorService {
    public record Connector(String id, String type, String name, Map<String, Object> config, String secretRef, boolean enabled, String createdAt) {}
    private final JdbcClient db;
    private final Json json;
    private final AuditService audit;
    public ConnectorService(JdbcClient db, Json json, AuditService audit) { this.db = db; this.json = json; this.audit = audit; }

    private Connector map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Connector(rs.getString("id"), rs.getString("type"), rs.getString("name"), json.map(rs.getString("config")), rs.getString("secret_ref"),
                rs.getBoolean("enabled"), Db.iso(rs, "created_at"));
    }
    public List<Connector> list(UUID orgId) { return db.sql("select * from connectors where org_id = :o order by created_at").param("o", orgId).query((rs, n) -> map(rs)).list(); }
    public List<Connector> enabled(UUID orgId) { return list(orgId).stream().filter(Connector::enabled).toList(); }
    public Connector require(UUID orgId, String id) {
        return db.sql("select * from connectors where org_id = :o and id = :id and enabled").param("o", orgId).param("id", Db.parse(id, "connector"))
                .query((rs, n) -> map(rs)).optional().orElseThrow(() -> Errors.notFound("connector"));
    }

    public Connector create(OrgContext ctx, String type, String name, Map<String, Object> config, String secretRef) {
        validate(type, name, config, secretRef);
        var id = UUID.randomUUID();
        try {
            db.sql("insert into connectors(id, org_id, type, name, config, secret_ref, created_by) values (:id, :o, :t, :n, cast(:c as jsonb), :s, :u)")
                    .param("id", id).param("o", ctx.orgId()).param("t", type).param("n", name.strip()).param("c", json.write(config))
                    .param("s", secretRef == null || secretRef.isBlank() ? null : secretRef).param("u", ctx.userId()).update();
        } catch (org.springframework.dao.DuplicateKeyException e) { throw Errors.conflict("NAME_TAKEN", "A connector with this name exists."); }
        audit.record(ctx.orgId(), ctx.userId(), "connector.create", "connector", id, AuditService.Result.SUCCESS, Map.of("type", type));
        return db.sql("select * from connectors where id = :id").param("id", id).query((rs, n) -> map(rs)).single();
    }
    public Connector update(OrgContext ctx, String idText, Boolean enabled, Map<String, Object> config) {
        UUID id = Db.parse(idText, "connector");
        var current = db.sql("select * from connectors where org_id = :o and id = :id").param("o", ctx.orgId()).param("id", id).query((rs, n) -> map(rs))
                .optional().orElseThrow(() -> Errors.notFound("connector"));
        var newConfig = config == null ? current.config() : config;
        validate(current.type(), current.name(), newConfig, current.secretRef());
        db.sql("update connectors set enabled = :e, config = cast(:c as jsonb) where id = :id")
                .param("e", enabled == null ? current.enabled() : enabled).param("c", json.write(newConfig)).param("id", id).update();
        audit.record(ctx.orgId(), ctx.userId(), "connector.update", "connector", id);
        return db.sql("select * from connectors where id = :id").param("id", id).query((rs, n) -> map(rs)).single();
    }
    public void delete(OrgContext ctx, String idText) {
        int n = db.sql("delete from connectors where org_id = :o and id = :id").param("o", ctx.orgId()).param("id", Db.parse(idText, "connector")).update();
        if (n == 0) throw Errors.notFound("connector");
        audit.record(ctx.orgId(), ctx.userId(), "connector.delete", "connector", idText);
    }

    static void validate(String type, String name, Map<String, Object> config, String secretRef) {
        if (!Set.of("HTTP_GET", "SQL_READONLY", "MCP").contains(type)) throw Errors.bad("INVALID_TYPE", "Type must be HTTP_GET, SQL_READONLY or MCP.");
        if (name == null || name.isBlank() || name.length() > 120) throw Errors.bad("INVALID_NAME", "Name must be 1–120 characters.");
        if (config == null) throw Errors.bad("INVALID_CONFIG", "Configuration is required.");
        if (secretRef != null && !secretRef.isBlank() && !secretRef.matches("ATLAS_CONNECTOR_[A-Z0-9_]{1,80}"))
            throw Errors.bad("INVALID_SECRET_REF", "secretRef must name a server environment variable starting with ATLAS_CONNECTOR_.");
        var text = config.toString().toLowerCase(Locale.ROOT);
        for (var forbidden : List.of("password", "secret", "token", "apikey", "api_key", "authorization"))
            if (config.keySet().stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).contains(forbidden)))
                throw Errors.bad("SECRET_IN_CONFIG", "Do not put credentials in connector configuration; use secretRef.");
        switch (type) {
            case "HTTP_GET" -> {
                if (!(config.get("baseUrl") instanceof String base) || !base.matches("https?://[^/@\\s]+(/[^@\\s]*)?")) throw Errors.bad("INVALID_CONFIG", "baseUrl must be an http(s) URL.");
                if (!(config.get("allowedPaths") instanceof List<?> paths) || paths.isEmpty()) throw Errors.bad("INVALID_CONFIG", "allowedPaths must list at least one path prefix.");
            }
            case "MCP" -> {
                if (!(config.get("endpoint") instanceof String) || !(config.get("allowedTools") instanceof List<?> tools) || tools.isEmpty())
                    throw Errors.bad("INVALID_CONFIG", "MCP needs endpoint and allowedTools.");
            }
            case "SQL_READONLY" -> {
                if (!(config.get("queries") instanceof Map<?, ?> queries) || queries.isEmpty()) throw Errors.bad("INVALID_CONFIG", "queries must map names to SELECT statements.");
                for (var q : queries.values()) {
                    var sql = String.valueOf(q).strip().toLowerCase(Locale.ROOT);
                    if (!sql.startsWith("select ") || sql.contains(";") || !sql.contains("demo_erp.")) throw Errors.bad("INVALID_CONFIG", "Only single SELECT statements over demo_erp are allowed in v1.");
                }
            }
            default -> { }
        }
        if (text.length() > 20_000) throw Errors.bad("INVALID_CONFIG", "Configuration is too large.");
    }
}
