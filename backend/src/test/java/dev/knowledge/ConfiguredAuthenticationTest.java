package dev.knowledge;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.file.*;
import java.util.*;
import static dev.knowledge.Models.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

@SpringBootTest @AutoConfigureMockMvc
class ConfiguredAuthenticationTest {
    @Autowired MockMvc mvc;
    @Autowired DocumentStore store;
    static final Path directory,users;
    static { try {
        directory=Files.createTempDirectory("atlas-auth-");users=directory.resolve("fixture-accounts.json");
        // Only a test fixture, not a production credential; production files are never included in Git.
        String hash=new BCryptPasswordEncoder(4).encode("test-only-password");
        Files.writeString(users,"[{\"username\":\"fixture-reader\",\"passwordHash\":\""+hash+"\",\"roles\":[\"HR\"],\"departments\":[\"hr\"]}]");
    } catch(Exception e){throw new ExceptionInInitializerError(e);} }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("knowledge.store",()->directory.resolve("store.json").toString());r.add("knowledge.auth-users",()->users.toString());r.add("knowledge.rag.enabled",()->false); }
    @Test void bcryptBasicAuthenticationEstablishesServerRolesAndRejectsSpoofedIdentities() throws Exception {
        var doc=store.create(new ImportRequest("auth-private","Private","authenticatedword",new Metadata("hr","hr-owner",null,List.of("HR"),List.of())));
        try {
            mvc.perform(get("/api/status")).andExpect(status().isOk()).andExpect(jsonPath("$.authentication").value("BASIC_SERVER_ACCOUNTS"))
                    .andExpect(jsonPath("$.roles").isEmpty()).andExpect(jsonPath("$.departments").isEmpty());
            mvc.perform(get("/api/status").with(httpBasic("fixture-reader","test-only-password")).header("X-Role","ADMIN").header("X-Department","engineering"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.actor").value("fixture-reader"))
                    .andExpect(jsonPath("$.roles[0]").value("HR")).andExpect(jsonPath("$.roles.length()").value(1))
                    .andExpect(jsonPath("$.departments[0]").value("hr"));
            mvc.perform(get("/api/documents/auth-private").header("X-Role","ADMIN")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/documents/auth-private").with(httpBasic("fixture-reader","wrong"))).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/documents/auth-private").with(httpBasic("fixture-reader","test-only-password"))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("auth-private"));
            mvc.perform(post("/api/questions").with(httpBasic("fixture-reader","test-only-password")).contentType("application/json").content("{\"question\":\"authenticatedword\",\"documentIds\":[\"auth-private\"]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.sources[0].documentId").value("auth-private"));
            mvc.perform(delete("/api/documents/auth-private").param("expectedVersion","1").with(httpBasic("fixture-reader","test-only-password"))).andExpect(status().isForbidden());
        } finally { store.delete(doc.id(),doc.version()); }
    }
    @Test void packagedLoginPageCanLoadWithoutGrantingAnonymousApiAccess() throws Exception {
        mvc.perform(get("/auth-ui-test.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Public login shell")));
        mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
    }
}
