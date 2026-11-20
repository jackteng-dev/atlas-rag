package dev.knowledge.platform.tenant;

import dev.knowledge.platform.core.*;
import dev.knowledge.platform.identity.CurrentUser;
import dev.knowledge.platform.identity.UserRepository.User;
import dev.knowledge.platform.ops.AuditService;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The single place that turns a client-supplied org id into a verified tenant context, and that decides what a member
 * may do with a knowledge base. Every tenant repository call takes the verified {@link OrgContext#orgId()}.
 */
@Component @Profile("platform")
public class TenantAccess {
    public enum KbAccess { MANAGE, EDIT, READ;
        public boolean atLeast(KbAccess other) { return ordinal() <= other.ordinal(); } }
    public record OrgContext(UUID orgId, User user, Role role) {
        public UUID userId() { return user.id(); }
        public boolean admin() { return role.atLeast(Role.ADMIN); }
    }
    public record KbRef(UUID id, UUID workspaceId, String visibility) {}

    private final JdbcClient db;
    private final CurrentUser current;
    private final AuditService audit;
    private final dev.knowledge.platform.identity.UserRepository users;
    public TenantAccess(JdbcClient db, CurrentUser current, AuditService audit, dev.knowledge.platform.identity.UserRepository users) {
        this.db = db; this.current = current; this.audit = audit; this.users = users;
    }

    public OrgContext require(String orgIdText) {
        var user = current.require();
        UUID orgId = Db.parse(orgIdText, "organization");
        var role = db.sql("""
                select m.role from memberships m join organizations o on o.id = m.org_id
                where m.org_id = :org and m.user_id = :user and m.status = 'ACTIVE' and o.status = 'ACTIVE'""")
                .param("org", orgId).param("user", user.id()).query(String.class).optional();
        if (role.isEmpty()) {
            // Non-members cannot learn whether the organisation exists.
            audit.record(null, user.id(), "tenant.access", "organization", orgId, AuditService.Result.DENIED, Map.of());
            throw Errors.notFound("organization");
        }
        return new OrgContext(orgId, user, Role.valueOf(role.get()));
    }
    /**
     * Rebuilds a tenant context for background work at execution time (not at enqueue time): a member who was removed,
     * disabled or lost KB access after queuing a job no longer gets results.
     */
    public Optional<OrgContext> contextFor(UUID orgId, UUID userId) {
        var user = users.byId(userId).filter(dev.knowledge.platform.identity.UserRepository.User::active);
        if (user.isEmpty()) return Optional.empty();
        return db.sql("""
                select m.role from memberships m join organizations o on o.id = m.org_id
                where m.org_id = :org and m.user_id = :user and m.status = 'ACTIVE' and o.status = 'ACTIVE'""")
                .param("org", orgId).param("user", userId).query(String.class).optional()
                .map(role -> new OrgContext(orgId, user.get(), Role.valueOf(role)));
    }
    public OrgContext require(String orgIdText, Role minimum) {
        var ctx = require(orgIdText);
        requireRole(ctx, minimum);
        return ctx;
    }
    public void requireRole(OrgContext ctx, Role minimum) {
        if (!ctx.role().atLeast(minimum)) {
            audit.record(ctx.orgId(), ctx.userId(), "tenant.role", "organization", ctx.orgId(), AuditService.Result.DENIED, Map.of("required", minimum.name()));
            throw Errors.forbidden(minimum.name() + " role or higher is required.");
        }
    }

    /** Effective access of the caller to every live knowledge base of the org (absent = no access). */
    public Map<UUID, KbAccess> knowledgeBaseAccess(OrgContext ctx) {
        var result = new LinkedHashMap<UUID, KbAccess>();
        db.sql("""
                select kb.id, kb.visibility, wm.role as ws_role, km.role as kb_role
                from knowledge_bases kb
                left join workspace_members wm on wm.workspace_id = kb.workspace_id and wm.user_id = :user and wm.org_id = kb.org_id
                left join kb_members km on km.kb_id = kb.id and km.user_id = :user and km.org_id = kb.org_id
                where kb.org_id = :org and kb.deleted_at is null order by kb.created_at""")
                .param("org", ctx.orgId()).param("user", ctx.userId())
                .query((rs, n) -> {
                    var access = effective(ctx.role(), rs.getString("visibility"), rs.getString("ws_role"), rs.getString("kb_role"));
                    if (access != null) result.put(Db.uuid(rs, "id"), access);
                    return null;
                }).list();
        return result;
    }
    static KbAccess effective(Role orgRole, String visibility, String workspaceRole, String kbRole) {
        if (orgRole.atLeast(Role.ADMIN) || "MANAGER".equals(workspaceRole)) return KbAccess.MANAGE;
        KbAccess best = null;
        if ("EDITOR".equals(kbRole) || "EDITOR".equals(workspaceRole)) best = KbAccess.EDIT;
        else if ("VIEWER".equals(kbRole) || "VIEWER".equals(workspaceRole)) best = KbAccess.READ;
        if ("ORG".equals(visibility)) {
            var fromOrg = orgRole == Role.EDITOR ? KbAccess.EDIT : KbAccess.READ;
            if (best == null || fromOrg.atLeast(best)) best = fromOrg;
        }
        return best;
    }
    public KbAccess requireKb(OrgContext ctx, UUID kbId, KbAccess minimum) {
        var access = knowledgeBaseAccess(ctx).get(kbId);
        if (access == null) throw Errors.notFound("knowledge base");
        if (!access.atLeast(minimum)) {
            audit.record(ctx.orgId(), ctx.userId(), "kb.access", "knowledge_base", kbId, AuditService.Result.DENIED, Map.of("required", minimum.name()));
            throw Errors.forbidden("This knowledge base requires " + minimum.name() + " access.");
        }
        return access;
    }
    public Set<UUID> readableKbIds(OrgContext ctx) { return knowledgeBaseAccess(ctx).keySet(); }
}
