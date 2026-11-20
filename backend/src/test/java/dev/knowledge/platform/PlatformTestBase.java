package dev.knowledge.platform;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Platform integration tests run against a real, throw-away PostgreSQL (Testcontainers, one container per JVM) and a
 * temporary filesystem object store. Model and vector services are deterministic in-memory fakes
 * ({@link FakeModelsConfiguration}); no network model or paid API is ever called.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=platform",
        "atlas.storage.type=filesystem",
        "atlas.models.enabled=false",
        "atlas.jobs.poll-ms=50",
        "atlas.bootstrap.admin-email=root@atlas.test",
        "atlas.bootstrap.admin-password=Atlas-admin-pass-123",
        "atlas.registration.open=true"})
@AutoConfigureMockMvc
@Import(FakeModelsConfiguration.class)
public abstract class PlatformTestBase {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final Path OBJECTS;
    static {
        POSTGRES.start();
        try { OBJECTS = Files.createTempDirectory("atlas-objects"); } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("atlas.storage.filesystem.root", OBJECTS::toString);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected JdbcClient db;

    protected static final String ROOT = "root@atlas.test", ROOT_PASSWORD = "Atlas-admin-pass-123";

    /** A signed-in browser: carries the Spring Session cookie like a real client. */
    public record Auth(Cookie cookie) { public static final Auth NONE = new Auth(null); }

    protected Auth login(String email, String password) throws Exception {
        var res = mvc.perform(post("/api/v2/auth/login").with(csrf()).contentType("application/json")
                .content(json(Map.of("email", email, "password", password)))).andExpect(status().isOk()).andReturn().getResponse();
        return new Auth(sessionCookie(res));
    }
    private static Cookie sessionCookie(org.springframework.mock.web.MockHttpServletResponse res) {
        var c = res.getCookie("ATLAS_SESSION");
        if (c == null) c = res.getCookie("SESSION");
        if (c == null) throw new AssertionError("No session cookie issued");
        return new Cookie(c.getName(), c.getValue());
    }
    protected JsonNode call(Auth auth, MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        if (auth != null && auth.cookie() != null) request.cookie(auth.cookie());
        request.with(csrf());
        if (body != null) request.contentType("application/json").content(json(body));
        var res = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse();
        var text = res.getContentAsString();
        return text.isBlank() ? mapper.nullNode() : mapper.readTree(text);
    }
    /** Like call() but returns the new session cookie (register / invitation acceptance sign the user in). */
    protected Auth callForSession(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.with(csrf()).contentType("application/json").content(json(body));
        var res = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        return new Auth(sessionCookie(res));
    }
    protected JsonNode upload(Auth auth, String orgId, String kbId, String filename, byte[] bytes, int expected) throws Exception {
        var req = multipart("/api/v2/orgs/" + orgId + "/knowledge-bases/" + kbId + "/documents").file(new MockMultipartFile("file", filename, "application/octet-stream", bytes));
        return call(auth, req, null, expected);
    }
    protected String json(Object value) throws Exception { return mapper.writeValueAsString(value); }

    /** Creates a fresh user + organisation through the public API and returns its signed-in session. */
    protected Tenant newTenant(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@atlas.test";
        String password = "Tenant-secret-" + prefix + "-42";
        call(Auth.NONE, post("/api/v2/auth/register"), Map.of("email", email, "password", password, "displayName", prefix, "orgName", prefix + " Org"), 200);
        var auth = login(email, password);
        var me = call(auth, get("/api/v2/auth/me"), null, 200);
        var orgId = me.path("memberships").path(0).path("orgId").asText();
        var ws = call(auth, get("/api/v2/orgs/" + orgId + "/workspaces"), null, 200).path(0).path("id").asText();
        return new Tenant(email, password, orgId, ws, auth, me.path("user").path("id").asText());
    }
    protected record Tenant(String email, String password, String orgId, String workspaceId, Auth auth, String userId) {}

    /** Invites a new person into the tenant with the given role and returns their signed-in session. */
    protected Member invite(Tenant t, String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@atlas.test";
        var inv = call(t.auth(), post("/api/v2/orgs/" + t.orgId() + "/invitations"), Map.of("email", email, "role", role), 201);
        var auth = callForSession(post("/api/v2/invitations/accept"), Map.of("token", inv.path("token").asText(), "displayName", role, "password", "Member-secret-9876"));
        var me = call(auth, get("/api/v2/auth/me"), null, 200);
        return new Member(email, auth, me.path("user").path("id").asText());
    }
    protected record Member(String email, Auth auth, String userId) {}

    protected JsonNode createKb(Tenant t, String name, String visibility) throws Exception {
        return call(t.auth(), post("/api/v2/orgs/" + t.orgId() + "/knowledge-bases"),
                Map.of("workspaceId", t.workspaceId(), "name", name, "description", "", "visibility", visibility), 201);
    }
    protected JsonNode awaitDocument(Auth auth, String orgId, String docId, Predicate<JsonNode> done) throws Exception {
        var deadline = Instant.now().plus(Duration.ofSeconds(30));
        JsonNode doc;
        do {
            doc = call(auth, get("/api/v2/orgs/" + orgId + "/documents/" + docId), null, 200);
            if (done.test(doc)) return doc;
            Thread.sleep(100);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Document did not reach the expected state: " + doc);
    }

    @BeforeEach void resetFakes() { FakeModelsConfiguration.reset(); }
}
