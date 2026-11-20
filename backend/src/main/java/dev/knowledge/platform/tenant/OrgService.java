package dev.knowledge.platform.tenant;

import dev.knowledge.platform.core.*;
import dev.knowledge.platform.identity.*;
import dev.knowledge.platform.identity.UserRepository.User;
import dev.knowledge.platform.ops.AuditService;
import dev.knowledge.platform.tenant.TenantAccess.OrgContext;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("platform")
public class OrgService {
    public record Membership(String orgId, String orgName, String orgSlug, String role) {}
    public record Org(String id, String name, String slug, String plan, String status, String role) {}
    public record Member(String userId, String email, String displayName, String role, String status, String joinedAt) {}
    public record Invitation(String id, String email, String role, String expiresAt, String acceptedAt, String revokedAt, String token) {}
    public record Workspace(String id, String name, String description, String createdAt) {}
    public record WorkspaceMember(String userId, String email, String displayName, String role) {}

    private final JdbcClient db;
    private final AuditService audit;
    private final UserRepository users;
    private final Passwords passwords;
    private final SessionRevoker revoker;
    private final SecureRandom random = new SecureRandom();
    public OrgService(JdbcClient db, AuditService audit, UserRepository users, Passwords passwords, SessionRevoker revoker) {
        this.db = db; this.audit = audit; this.users = users; this.passwords = passwords; this.revoker = revoker;
    }

    public List<Membership> memberships(UUID userId) {
        return db.sql("""
                select o.id, o.name, o.slug, m.role from memberships m join organizations o on o.id = m.org_id
                where m.user_id = :u and m.status = 'ACTIVE' and o.status = 'ACTIVE' order by o.name""")
                .param("u", userId).query((rs, n) -> new Membership(rs.getString("id"), rs.getString("name"), rs.getString("slug"), rs.getString("role"))).list();
    }

    @Transactional
    public Org create(User owner, String name, String slug) {
        if (name == null || name.isBlank() || name.length() > 120) throw Errors.bad("INVALID_NAME", "Organisation name must be 1–120 characters.");
        if (slug == null || !slug.matches("[a-z0-9][a-z0-9-]{1,63}")) throw Errors.bad("INVALID_SLUG", "Slug: 2–64 lowercase letters, digits or hyphens.");
        if (db.sql("select count(*) from organizations where slug = :s").param("s", slug).query(Long.class).single() > 0)
            throw Errors.conflict("SLUG_TAKEN", "This slug is already used.");
        var id = UUID.randomUUID();
        db.sql("insert into organizations(id, name, slug) values (:id, :n, :s)").param("id", id).param("n", name.trim()).param("s", slug).update();
        db.sql("insert into memberships(org_id, user_id, role) values (:o, :u, 'OWNER')").param("o", id).param("u", owner.id()).update();
        db.sql("insert into quotas(org_id) values (:o)").param("o", id).update();
        var ws = UUID.randomUUID();
        db.sql("insert into workspaces(id, org_id, name, description) values (:id, :o, 'General', 'Default workspace')").param("id", ws).param("o", id).update();
        audit.record(id, owner.id(), "org.create", "organization", id);
        return new Org(id.toString(), name.trim(), slug, "SELF_HOSTED", "ACTIVE", "OWNER");
    }

    public Org get(OrgContext ctx) {
        return db.sql("select id, name, slug, plan, status from organizations where id = :id").param("id", ctx.orgId())
                .query((rs, n) -> new Org(rs.getString("id"), rs.getString("name"), rs.getString("slug"), rs.getString("plan"), rs.getString("status"), ctx.role().name())).single();
    }
    public Org rename(OrgContext ctx, String name) {
        if (name == null || name.isBlank() || name.length() > 120) throw Errors.bad("INVALID_NAME", "Organisation name must be 1–120 characters.");
        db.sql("update organizations set name = :n where id = :id").param("n", name.trim()).param("id", ctx.orgId()).update();
        audit.record(ctx.orgId(), ctx.userId(), "org.rename", "organization", ctx.orgId());
        return get(ctx);
    }

    public List<Member> members(OrgContext ctx) {
        return db.sql("""
                select u.id, u.email, u.display_name, m.role, m.status, m.created_at from memberships m join users u on u.id = m.user_id
                where m.org_id = :o order by m.created_at""").param("o", ctx.orgId())
                .query((rs, n) -> new Member(rs.getString("id"), rs.getString("email"), rs.getString("display_name"), rs.getString("role"),
                        rs.getString("status"), Db.iso(rs, "created_at"))).list();
    }

    @Transactional
    public Member updateMember(OrgContext ctx, String userIdText, String roleText, String status) {
        UUID userId = Db.parse(userIdText, "member");
        var currentRole = db.sql("select role from memberships where org_id = :o and user_id = :u for update").param("o", ctx.orgId()).param("u", userId)
                .query(String.class).optional().map(Role::valueOf).orElseThrow(() -> Errors.notFound("member"));
        if (roleText != null) {
            var role = Role.parse(roleText);
            if ((role == Role.OWNER || currentRole == Role.OWNER) && ctx.role() != Role.OWNER) throw Errors.forbidden("Only an owner can grant or revoke the owner role.");
            if (currentRole == Role.OWNER && role != Role.OWNER) requireAnotherOwner(ctx.orgId(), userId);
            db.sql("update memberships set role = :r where org_id = :o and user_id = :u").param("r", role.name()).param("o", ctx.orgId()).param("u", userId).update();
        }
        if (status != null) {
            if (!Set.of("ACTIVE", "DISABLED").contains(status)) throw Errors.bad("INVALID_STATUS", "Status must be ACTIVE or DISABLED.");
            if (userId.equals(ctx.userId())) throw Errors.bad("SELF_CHANGE", "You cannot disable your own membership.");
            if (currentRole == Role.OWNER && ctx.role() != Role.OWNER) throw Errors.forbidden("Only an owner can disable an owner.");
            if (currentRole == Role.OWNER && "DISABLED".equals(status)) requireAnotherOwner(ctx.orgId(), userId);
            db.sql("update memberships set status = :s where org_id = :o and user_id = :u").param("s", status).param("o", ctx.orgId()).param("u", userId).update();
        }
        audit.record(ctx.orgId(), ctx.userId(), "member.update", "user", userId, AuditService.Result.SUCCESS,
                Map.of("role", roleText == null ? "" : roleText, "status", status == null ? "" : status));
        return members(ctx).stream().filter(m -> m.userId().equals(userId.toString())).findFirst().orElseThrow();
    }

    @Transactional
    public void removeMember(OrgContext ctx, String userIdText) {
        UUID userId = Db.parse(userIdText, "member");
        var role = db.sql("select role from memberships where org_id = :o and user_id = :u for update").param("o", ctx.orgId()).param("u", userId)
                .query(String.class).optional().map(Role::valueOf).orElseThrow(() -> Errors.notFound("member"));
        if (role == Role.OWNER) {
            if (ctx.role() != Role.OWNER) throw Errors.forbidden("Only an owner can remove an owner.");
            requireAnotherOwner(ctx.orgId(), userId);
        }
        db.sql("delete from memberships where org_id = :o and user_id = :u").param("o", ctx.orgId()).param("u", userId).update();
        audit.record(ctx.orgId(), ctx.userId(), "member.remove", "user", userId);
    }
    private void requireAnotherOwner(UUID orgId, UUID except) {
        long owners = db.sql("select count(*) from memberships where org_id = :o and role = 'OWNER' and status = 'ACTIVE' and user_id <> :u")
                .param("o", orgId).param("u", except).query(Long.class).single();
        if (owners == 0) throw Errors.conflict("LAST_OWNER", "An organisation must keep at least one active owner.");
    }

    // ----- invitations -----
    public Invitation invite(OrgContext ctx, String emailText, String roleText) {
        var email = Passwords.normalizeEmail(emailText);
        var role = Role.parse(roleText);
        if (role == Role.OWNER) throw Errors.bad("INVALID_ROLE", "Invite as ADMIN, EDITOR or VIEWER; promote to owner afterwards.");
        if (role == Role.ADMIN && ctx.role() != Role.OWNER && ctx.role() != Role.ADMIN) throw Errors.forbidden("Admin role required.");
        long existing = db.sql("select count(*) from memberships m join users u on u.id = m.user_id where m.org_id = :o and lower(u.email) = :e")
                .param("o", ctx.orgId()).param("e", email).query(Long.class).single();
        if (existing > 0) throw Errors.conflict("ALREADY_MEMBER", "This person is already a member.");
        var token = randomToken();
        var id = UUID.randomUUID();
        var expires = Instant.now().plus(Duration.ofDays(7));
        db.sql("""
                insert into invitations(id, org_id, email, role, token_hash, expires_at, created_by)
                values (:id, :o, :e, :r, :h, :x, :by)""").param("id", id).param("o", ctx.orgId()).param("e", email).param("r", role.name())
                .param("h", sha256(token)).param("x", Db.ts(expires)).param("by", ctx.userId()).update();
        audit.record(ctx.orgId(), ctx.userId(), "invitation.create", "invitation", id, AuditService.Result.SUCCESS, Map.of("role", role.name()));
        return new Invitation(id.toString(), email, role.name(), expires.toString(), null, null, token);
    }
    public List<Invitation> invitations(OrgContext ctx) {
        return db.sql("select id, email, role, expires_at, accepted_at, revoked_at from invitations where org_id = :o order by created_at desc limit 200")
                .param("o", ctx.orgId()).query((rs, n) -> new Invitation(rs.getString("id"), rs.getString("email"), rs.getString("role"),
                        Db.iso(rs, "expires_at"), Db.iso(rs, "accepted_at"), Db.iso(rs, "revoked_at"), null)).list();
    }
    public void revokeInvitation(OrgContext ctx, String idText) {
        int n = db.sql("update invitations set revoked_at = now() where org_id = :o and id = :id and accepted_at is null and revoked_at is null")
                .param("o", ctx.orgId()).param("id", Db.parse(idText, "invitation")).update();
        if (n == 0) throw Errors.notFound("invitation");
        audit.record(ctx.orgId(), ctx.userId(), "invitation.revoke", "invitation", idText);
    }

    /** Accepts an invitation. A signed-in user joins directly (email must match); a new person creates an account. */
    @Transactional
    public User accept(String token, User signedIn, String displayName, String password) {
        if (token == null || token.length() < 20 || token.length() > 100) throw Errors.notFound("invitation");
        var inv = db.sql("""
                select id, org_id, email, role from invitations where token_hash = :h and accepted_at is null and revoked_at is null and expires_at > now() for update""")
                .param("h", sha256(token)).query((rs, n) -> Map.of("id", Db.uuid(rs, "id"), "org", Db.uuid(rs, "org_id"), "email", rs.getString("email"), "role", rs.getString("role")))
                .optional().orElseThrow(() -> Errors.notFound("invitation"));
        String email = (String) inv.get("email");
        User user;
        if (signedIn != null) {
            if (!signedIn.email().equalsIgnoreCase(email)) throw Errors.forbidden("This invitation was issued to another email address.");
            user = signedIn;
        } else {
            var existing = users.byEmail(email);
            if (existing.isPresent()) throw new dev.knowledge.ApiException(409, "SIGN_IN_REQUIRED", "An account already exists for this email. Sign in first, then accept.");
            if (displayName == null || displayName.isBlank() || displayName.length() > 120) throw Errors.bad("INVALID_NAME", "Display name is required.");
            Passwords.validate(password, email);
            user = users.create(email, displayName, passwords.hash(password), "NONE");
        }
        UUID org = (UUID) inv.get("org");
        db.sql("insert into memberships(org_id, user_id, role) values (:o, :u, :r) on conflict (org_id, user_id) do nothing")
                .param("o", org).param("u", user.id()).param("r", inv.get("role")).update();
        db.sql("update invitations set accepted_at = now() where id = :id").param("id", inv.get("id")).update();
        audit.record(org, user.id(), "invitation.accept", "invitation", inv.get("id"));
        return user;
    }

    // ----- workspaces -----
    public List<Workspace> workspaces(OrgContext ctx) {
        return db.sql("select id, name, description, created_at from workspaces where org_id = :o order by created_at").param("o", ctx.orgId())
                .query((rs, n) -> new Workspace(rs.getString("id"), rs.getString("name"), rs.getString("description"), Db.iso(rs, "created_at"))).list();
    }
    public Workspace createWorkspace(OrgContext ctx, String name, String description) {
        if (name == null || name.isBlank() || name.length() > 120) throw Errors.bad("INVALID_NAME", "Workspace name must be 1–120 characters.");
        var id = UUID.randomUUID();
        try {
            db.sql("insert into workspaces(id, org_id, name, description) values (:id, :o, :n, :d)").param("id", id).param("o", ctx.orgId())
                    .param("n", name.trim()).param("d", description == null ? "" : description.trim()).update();
        } catch (org.springframework.dao.DuplicateKeyException e) { throw Errors.conflict("NAME_TAKEN", "A workspace with this name exists."); }
        audit.record(ctx.orgId(), ctx.userId(), "workspace.create", "workspace", id);
        return workspaces(ctx).stream().filter(w -> w.id().equals(id.toString())).findFirst().orElseThrow();
    }
    public UUID requireWorkspace(OrgContext ctx, String idText) {
        UUID id = Db.parse(idText, "workspace");
        long n = db.sql("select count(*) from workspaces where org_id = :o and id = :id").param("o", ctx.orgId()).param("id", id).query(Long.class).single();
        if (n == 0) throw Errors.notFound("workspace");
        return id;
    }
    public List<WorkspaceMember> workspaceMembers(OrgContext ctx, String wsText) {
        UUID ws = requireWorkspace(ctx, wsText);
        return db.sql("""
                select u.id, u.email, u.display_name, wm.role from workspace_members wm join users u on u.id = wm.user_id
                where wm.org_id = :o and wm.workspace_id = :w order by u.email""").param("o", ctx.orgId()).param("w", ws)
                .query((rs, n) -> new WorkspaceMember(rs.getString("id"), rs.getString("email"), rs.getString("display_name"), rs.getString("role"))).list();
    }
    public void setWorkspaceMember(OrgContext ctx, String wsText, String userText, String role) {
        UUID ws = requireWorkspace(ctx, wsText);
        UUID user = requireMember(ctx, userText);
        if (!Set.of("MANAGER", "EDITOR", "VIEWER").contains(role)) throw Errors.bad("INVALID_ROLE", "Role must be MANAGER, EDITOR or VIEWER.");
        db.sql("""
                insert into workspace_members(org_id, workspace_id, user_id, role) values (:o, :w, :u, :r)
                on conflict (workspace_id, user_id) do update set role = excluded.role""")
                .param("o", ctx.orgId()).param("w", ws).param("u", user).param("r", role).update();
        audit.record(ctx.orgId(), ctx.userId(), "workspace.member.set", "workspace", ws, AuditService.Result.SUCCESS, Map.of("user", user.toString(), "role", role));
    }
    public void removeWorkspaceMember(OrgContext ctx, String wsText, String userText) {
        UUID ws = requireWorkspace(ctx, wsText);
        db.sql("delete from workspace_members where org_id = :o and workspace_id = :w and user_id = :u")
                .param("o", ctx.orgId()).param("w", ws).param("u", Db.parse(userText, "member")).update();
        audit.record(ctx.orgId(), ctx.userId(), "workspace.member.remove", "workspace", ws);
    }
    public UUID requireMember(OrgContext ctx, String userText) {
        UUID user = Db.parse(userText, "member");
        long n = db.sql("select count(*) from memberships where org_id = :o and user_id = :u").param("o", ctx.orgId()).param("u", user).query(Long.class).single();
        if (n == 0) throw Errors.notFound("member");
        return user;
    }

    private String randomToken() { var b = new byte[32]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public SessionRevoker revoker() { return revoker; }
}
