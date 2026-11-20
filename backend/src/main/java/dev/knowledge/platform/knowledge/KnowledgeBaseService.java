package dev.knowledge.platform.knowledge;

import dev.knowledge.platform.core.*;
import dev.knowledge.platform.ops.AuditService;
import dev.knowledge.platform.tenant.*;
import dev.knowledge.platform.tenant.TenantAccess.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("platform")
public class KnowledgeBaseService {
    public record KnowledgeBase(String id, String workspaceId, String name, String description, String visibility, long documentCount,
                                long readyCount, String access, String createdAt) {}
    public record KbMember(String userId, String email, String displayName, String role) {}

    private final JdbcClient db;
    private final TenantAccess access;
    private final OrgService orgs;
    private final AuditService audit;
    public KnowledgeBaseService(JdbcClient db, TenantAccess access, OrgService orgs, AuditService audit) {
        this.db = db; this.access = access; this.orgs = orgs; this.audit = audit;
    }

    public List<KnowledgeBase> list(OrgContext ctx) {
        var accessMap = access.knowledgeBaseAccess(ctx);
        if (accessMap.isEmpty()) return List.of();
        return db.sql("""
                select kb.id, kb.workspace_id, kb.name, kb.description, kb.visibility, kb.created_at,
                  (select count(*) from documents d where d.kb_id = kb.id and d.org_id = kb.org_id and d.deleted_at is null) as docs,
                  (select count(*) from documents d where d.kb_id = kb.id and d.org_id = kb.org_id and d.deleted_at is null and d.status = 'READY') as ready
                from knowledge_bases kb where kb.org_id = :o and kb.id in (:ids) order by kb.name""")
                .param("o", ctx.orgId()).param("ids", accessMap.keySet())
                .query((rs, n) -> new KnowledgeBase(rs.getString("id"), rs.getString("workspace_id"), rs.getString("name"), rs.getString("description"),
                        rs.getString("visibility"), rs.getLong("docs"), rs.getLong("ready"), accessMap.get(Db.uuid(rs, "id")).name(), Db.iso(rs, "created_at"))).list();
    }
    public KnowledgeBase get(OrgContext ctx, String kbText) {
        UUID kb = Db.parse(kbText, "knowledge base");
        access.requireKb(ctx, kb, KbAccess.READ);
        return list(ctx).stream().filter(k -> k.id().equals(kb.toString())).findFirst().orElseThrow(() -> Errors.notFound("knowledge base"));
    }

    @Transactional
    public KnowledgeBase create(OrgContext ctx, String workspaceText, String name, String description, String visibility) {
        UUID ws = orgs.requireWorkspace(ctx, workspaceText);
        boolean manager = db.sql("select count(*) from workspace_members where org_id = :o and workspace_id = :w and user_id = :u and role = 'MANAGER'")
                .param("o", ctx.orgId()).param("w", ws).param("u", ctx.userId()).query(Long.class).single() > 0;
        if (!ctx.admin() && !manager) throw Errors.forbidden("Creating a knowledge base requires org admin or workspace manager.");
        validate(name, description, visibility == null ? "ORG" : visibility);
        var id = UUID.randomUUID();
        db.sql("insert into knowledge_bases(id, org_id, workspace_id, name, description, visibility, created_by) values (:id, :o, :w, :n, :d, :v, :by)")
                .param("id", id).param("o", ctx.orgId()).param("w", ws).param("n", name.trim()).param("d", description == null ? "" : description.trim())
                .param("v", visibility == null ? "ORG" : visibility).param("by", ctx.userId()).update();
        audit.record(ctx.orgId(), ctx.userId(), "kb.create", "knowledge_base", id);
        return get(ctx, id.toString());
    }
    public KnowledgeBase update(OrgContext ctx, String kbText, String name, String description, String visibility) {
        UUID kb = Db.parse(kbText, "knowledge base");
        access.requireKb(ctx, kb, KbAccess.MANAGE);
        var current = get(ctx, kbText);
        String n = name == null ? current.name() : name, d = description == null ? current.description() : description, v = visibility == null ? current.visibility() : visibility;
        validate(n, d, v);
        db.sql("update knowledge_bases set name = :n, description = :d, visibility = :v where org_id = :o and id = :id")
                .param("n", n.trim()).param("d", d.trim()).param("v", v).param("o", ctx.orgId()).param("id", kb).update();
        audit.record(ctx.orgId(), ctx.userId(), "kb.update", "knowledge_base", kb, AuditService.Result.SUCCESS, Map.of("visibility", v));
        return get(ctx, kbText);
    }
    /** Soft-deletes the KB and marks its documents for purge; access stops immediately because readable sets skip deleted KBs. */
    @Transactional
    public List<UUID> delete(OrgContext ctx, String kbText) {
        UUID kb = Db.parse(kbText, "knowledge base");
        access.requireKb(ctx, kb, KbAccess.MANAGE);
        db.sql("update knowledge_bases set deleted_at = now() where org_id = :o and id = :id").param("o", ctx.orgId()).param("id", kb).update();
        var docs = db.sql("update documents set deleted_at = now(), status = 'DELETING' where org_id = :o and kb_id = :kb and deleted_at is null returning id")
                .param("o", ctx.orgId()).param("kb", kb).query(UUID.class).list();
        audit.record(ctx.orgId(), ctx.userId(), "kb.delete", "knowledge_base", kb, AuditService.Result.SUCCESS, Map.of("documents", docs.size()));
        return docs;
    }
    private static void validate(String name, String description, String visibility) {
        if (name == null || name.isBlank() || name.length() > 120) throw Errors.bad("INVALID_NAME", "Name must be 1–120 characters.");
        if (description != null && description.length() > 1000) throw Errors.bad("INVALID_DESCRIPTION", "Description is limited to 1000 characters.");
        if (!Set.of("ORG", "RESTRICTED").contains(visibility)) throw Errors.bad("INVALID_VISIBILITY", "Visibility must be ORG or RESTRICTED.");
    }

    public List<KbMember> members(OrgContext ctx, String kbText) {
        UUID kb = Db.parse(kbText, "knowledge base");
        access.requireKb(ctx, kb, KbAccess.READ);
        return db.sql("""
                select u.id, u.email, u.display_name, km.role from kb_members km join users u on u.id = km.user_id
                where km.org_id = :o and km.kb_id = :kb order by u.email""").param("o", ctx.orgId()).param("kb", kb)
                .query((rs, n) -> new KbMember(rs.getString("id"), rs.getString("email"), rs.getString("display_name"), rs.getString("role"))).list();
    }
    public void setMember(OrgContext ctx, String kbText, String userText, String role) {
        UUID kb = Db.parse(kbText, "knowledge base");
        access.requireKb(ctx, kb, KbAccess.MANAGE);
        UUID user = orgs.requireMember(ctx, userText);
        if (!Set.of("EDITOR", "VIEWER").contains(role)) throw Errors.bad("INVALID_ROLE", "Role must be EDITOR or VIEWER.");
        db.sql("insert into kb_members(org_id, kb_id, user_id, role) values (:o, :kb, :u, :r) on conflict (kb_id, user_id) do update set role = excluded.role")
                .param("o", ctx.orgId()).param("kb", kb).param("u", user).param("r", role).update();
        audit.record(ctx.orgId(), ctx.userId(), "kb.member.set", "knowledge_base", kb, AuditService.Result.SUCCESS, Map.of("user", user.toString(), "role", role));
    }
    public void removeMember(OrgContext ctx, String kbText, String userText) {
        UUID kb = Db.parse(kbText, "knowledge base");
        access.requireKb(ctx, kb, KbAccess.MANAGE);
        db.sql("delete from kb_members where org_id = :o and kb_id = :kb and user_id = :u").param("o", ctx.orgId()).param("kb", kb)
                .param("u", Db.parse(userText, "member")).update();
        audit.record(ctx.orgId(), ctx.userId(), "kb.member.remove", "knowledge_base", kb);
    }
}
