package dev.knowledge;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.nio.file.*;
import java.util.*;
import static dev.knowledge.Models.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@SpringBootTest @AutoConfigureMockMvc
class DocumentAuthorizationTest {
    @Autowired MockMvc mvc;
    @Autowired DocumentStore store;
    static Path file;
    static { try {file=Files.createTempDirectory("atlas-acl-").resolve("store.json");}catch(Exception e){throw new ExceptionInInitializerError(e);} }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("knowledge.store",()->file.toString());r.add("knowledge.rag.enabled",()->false); }
    @AfterEach void cleanup() { for(var doc:store.snapshot().documents()) if(doc.id().startsWith("acl-")) store.delete(doc.id(),doc.version()); }
    @Test void realHttpFiltersListDirectDocumentCitationQueryAndSpoofedHeaders() throws Exception {
        var doc=store.create(new ImportRequest("acl-hr","HR restricted","salary secret evidence",new Metadata("hr","hr-owner",null,List.of("HR"),List.of("payroll"))));
        String citation="/api/documents/acl-hr/chunks/"+doc.chunks().getFirst().id();
        mvc.perform(get("/api/documents")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == 'acl-hr')]").isEmpty());
        mvc.perform(get("/api/documents/acl-hr").header("X-Role","ADMIN").header("X-Department","hr")).andExpect(status().isNotFound());
        mvc.perform(get(citation).param("version","1")).andExpect(status().isNotFound());
        mvc.perform(post("/api/questions").contentType("application/json").content("{\"question\":\"salary\",\"documentIds\":[\"acl-hr\"]}")).andExpect(status().isNotFound());
        var hr=user("reader").authorities(new SimpleGrantedAuthority("ROLE_HR"),new SimpleGrantedAuthority("DEPARTMENT_hr"));
        mvc.perform(get(citation).param("version","1").with(hr)).andExpect(status().isOk()).andExpect(jsonPath("$.excerpt").value("salary secret evidence"));
        mvc.perform(get(citation).param("version","1").with(user("wrong-department").authorities(new SimpleGrantedAuthority("ROLE_HR"),new SimpleGrantedAuthority("DEPARTMENT_sales")))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/documents/acl-hr").param("expectedVersion","1").with(hr)).andExpect(status().isForbidden());
    }
    @Test void anonymousCannotAssignPrivatePolicyAndExpiredCitationIsUnavailable() throws Exception {
        mvc.perform(post("/api/documents").contentType("application/json").content("{\"id\":\"acl-spoof\",\"title\":\"Forged\",\"markdown\":\"body\",\"metadata\":{\"department\":\"hr\",\"owner\":\"admin\",\"allowedRoles\":[\"ADMIN\"]}}"))
                .andExpect(status().isForbidden());
        var doc=store.create(new ImportRequest("acl-expired","Expired","expiredword",new Metadata("public","public-demo","2000-01-01T00:00:00Z",List.of(),List.of())));
        mvc.perform(get("/api/documents/acl-expired/chunks/"+doc.chunks().getFirst().id()).param("version","1")).andExpect(status().isNotFound());
        mvc.perform(post("/api/questions").contentType("application/json").content("{\"question\":\"expiredword\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sources").isEmpty()).andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"));
    }
}
