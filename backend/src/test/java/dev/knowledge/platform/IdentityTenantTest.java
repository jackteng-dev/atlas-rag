package dev.knowledge.platform;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IdentityTenantTest extends PlatformTestBase {

    @Test void anonymousAccessIsRejectedAndNoAnonymousWritesExist() throws Exception {
        mvc.perform(get("/api/v2/auth/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/api/v2/orgs/" + UUID.randomUUID() + "/documents")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v2/orgs").with(csrf()).contentType("application/json").content("{\"name\":\"x\",\"slug\":\"xx\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void mutatingRequestsWithoutCsrfTokenAreRejected() throws Exception {
        var auth = login(ROOT, ROOT_PASSWORD);
        mvc.perform(post("/api/v2/orgs").cookie(auth.cookie()).contentType("application/json").content("{\"name\":\"No CSRF\",\"slug\":\"no-csrf\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }


    @Test void bootstrapAdminCanSignInAndBadPasswordsAreThrottled() throws Exception {
        var auth = login(ROOT, ROOT_PASSWORD);
        var me = call(auth, get("/api/v2/auth/me"), null, 200);
        assertThat(me.path("user").path("systemRole").asText()).isEqualTo("SYSTEM_ADMIN");
        assertThat(me.path("memberships").size()).isGreaterThanOrEqualTo(1);
        String email = "throttle-" + UUID.randomUUID() + "@atlas.test";
        for (int i = 0; i < 5; i++) call(Auth.NONE, post("/api/v2/auth/login"), Map.of("email", email, "password", "wrong-password-x"), 401);
        call(Auth.NONE, post("/api/v2/auth/login"), Map.of("email", email, "password", "wrong-password-x"), 429);
    }

    @Test void invitationFlowCreatesMemberWithRoleAndForeignOrgIsInvisible() throws Exception {
        var a = newTenant("alpha");
        var b = newTenant("beta");
        var inv = call(a.auth(), post("/api/v2/orgs/" + a.orgId() + "/invitations"), Map.of("email", "viewer-" + UUID.randomUUID() + "@atlas.test", "role", "VIEWER"), 201);
        String token = inv.path("token").asText();
        assertThat(token).hasSizeGreaterThan(30);
        var list = call(a.auth(), get("/api/v2/orgs/" + a.orgId() + "/invitations"), null, 200);
        assertThat(list.toString()).doesNotContain(token);
        var viewer = callForSession(post("/api/v2/invitations/accept"), Map.of("token", token, "displayName", "Viewer", "password", "Viewer-secret-777"));
        var me = call(viewer, get("/api/v2/auth/me"), null, 200);
        assertThat(me.path("memberships").path(0).path("role").asText()).isEqualTo("VIEWER");
        // Single use.
        call(Auth.NONE, post("/api/v2/invitations/accept"), Map.of("token", token, "displayName", "X", "password", "Another-secret-77"), 404);
        // Viewers cannot administer.
        call(viewer, post("/api/v2/orgs/" + a.orgId() + "/invitations"), Map.of("email", "x@atlas.test", "role", "VIEWER"), 403);
        // Cross-tenant: foreign and non-existent orgs are indistinguishable (404).
        call(b.auth(), get("/api/v2/orgs/" + a.orgId()), null, 404);
        call(b.auth(), get("/api/v2/orgs/" + a.orgId() + "/members"), null, 404);
        call(b.auth(), get("/api/v2/orgs/" + UUID.randomUUID() + "/members"), null, 404);
        call(b.auth(), post("/api/v2/orgs/" + a.orgId() + "/invitations"), Map.of("email", "x@atlas.test", "role", "ADMIN"), 404);
        var denied = db.sql("select count(*) from audit_events where action = 'tenant.access' and result = 'DENIED' and resource_id = :o")
                .param("o", a.orgId()).query(Long.class).single();
        assertThat(denied).isGreaterThan(0);
    }

    @Test void lastOwnerCannotBeDemotedAndDisabledMembersLoseAccess() throws Exception {
        var a = newTenant("owner");
        call(a.auth(), patch("/api/v2/orgs/" + a.orgId() + "/members/" + a.userId()), Map.of("role", "VIEWER"), 409);
        var ed = invite(a, "EDITOR");
        call(ed.auth(), get("/api/v2/orgs/" + a.orgId()), null, 200);
        call(a.auth(), patch("/api/v2/orgs/" + a.orgId() + "/members/" + ed.userId()), Map.of("status", "DISABLED"), 200);
        call(ed.auth(), get("/api/v2/orgs/" + a.orgId()), null, 404);
    }

    @Test void passwordChangeRevokesOtherSessions() throws Exception {
        var a = newTenant("pw");
        var second = login(a.email(), a.password());
        call(a.auth(), post("/api/v2/auth/password"), Map.of("currentPassword", a.password(), "newPassword", "Changed-secret-42x"), 204);
        call(second, get("/api/v2/auth/me"), null, 401);
        login(a.email(), "Changed-secret-42x");
    }
}
