package dev.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DocumentStore store;
    static final Path file;
    static { try {file=Files.createTempDirectory("knowledge-api-test").resolve("store.json");}catch(Exception e){throw new ExceptionInInitializerError(e);} }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("knowledge.store",()->file.toString()); registry.add("knowledge.provider",()->"offline");
    }
    @Test void questionJobsValidateInputAndReturnFastNonCacheableAdmission() throws Exception {
        mvc.perform(post("/api/question-jobs").contentType("application/json").content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/question-jobs").contentType("application/json").content("{\"question\":\"nonexistent-job-fixture\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.status").value("RUNNING")).andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.answer").isEmpty());
        mvc.perform(get("/api/question-jobs/unknown"))
                .andExpect(status().isNotFound());
    }
    @Test void fullLifecycleAndCanonicalCitationHttpContract() throws Exception {
        mvc.perform(post("/api/documents").contentType("application/json").content("{\"id\":\"api-test\",\"title\":\"API Test\",\"markdown\":\"## Old\\nhttpoldtoken evidence\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(1));
        String chunk=store.get("api-test").chunks().getFirst().id();
        String path="/api/documents/api-test/chunks/"+chunk;
        mvc.perform(get(path).param("version","1")).andExpect(status().isOk()).andExpect(jsonPath("$.excerpt").value("httpoldtoken evidence"));
        mvc.perform(put("/api/documents/api-test").contentType("application/json").content("{\"title\":\"API Test\",\"markdown\":\"httpnewtoken evidence\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get(path).param("version","1")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_CITATION"));
        mvc.perform(post("/api/questions").contentType("application/json").content("{\"question\":\"httpoldtoken\",\"documentIds\":[\"api-test\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE")).andExpect(jsonPath("$.sources").isEmpty());
        mvc.perform(delete("/api/documents/api-test").param("expectedVersion","1")).andExpect(status().isConflict());
        mvc.perform(delete("/api/documents/api-test").param("expectedVersion","2")).andExpect(status().isNoContent());
        mvc.perform(get(path).param("version","1")).andExpect(status().isNotFound());
        mvc.perform(post("/api/documents").contentType("application/json").content("{\"id\":\"api-test\",\"title\":\"Recreated\",\"markdown\":\"httpoldtoken evidence\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(3));
        mvc.perform(get(path).param("version","1")).andExpect(status().isConflict());
        mvc.perform(delete("/api/documents/api-test").param("expectedVersion","3")).andExpect(status().isNoContent());
    }
    @Test void validationAndModeContract() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isOk()).andExpect(jsonPath("$.embedding").value("NONE")).andExpect(jsonPath("$.mode").value("OFFLINE_EXTRACTIVE"));
        mvc.perform(post("/api/questions").contentType("application/json").content("{\"question\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/questions").contentType("application/json").content("{\"question\":\"port\",\"topK\":6}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/documents").contentType("application/json").content("{\"id\":\"../bad\",\"title\":\"Bad\",\"markdown\":\"text\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/documents").contentType("application/json").content("{\"id\":\"blank\",\"title\":\"Bad\",\"markdown\":\"# Only heading\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents/architecture/chunks/fake").param("version","1")).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/architecture/chunks/fake").param("version","oops")).andExpect(status().isBadRequest());
    }
}
