package dev.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static dev.knowledge.Models.*;
import static org.assertj.core.api.Assertions.*;

class RetrievalEvaluationTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    @Test void fixedBaselineRetainsAllHistoricalStatusesReferencesAndCitations() throws Exception {
        var baseline = mapper.readTree(Path.of(System.getProperty("basedir"), "../eval/results.json").toFile());
        try (var store = new DocumentStore(directory.resolve("baseline.json"), mapper, new MarkdownChunker(), true)) {
            var service = new AnswerService(store, new KeywordRetriever(), new OfflineExcerptProvider());
            for (var row : baseline.get("results")) {
                var answer = service.ask(new AskRequest(row.get("question").asText(), null, 5));
                assertThat(answer.status()).as(row.get("id").asText()).isEqualTo(row.get("actualStatus").asText());
                var references = answer.sources().stream().map(s->s.documentId()+"::"+s.heading()).toList();
                long hits = 0;
                for (var ref : row.get("references")) if (references.contains(ref.asText())) hits++;
                assertThat(hits).isEqualTo(row.get("referenceHits").asLong());
                assertThat(answer.segments()).hasSize(row.get("citationCount").asInt());
                assertThat(answer.configuration()).containsEntry("topK",5).containsEntry("chunkMaxChars",800);
            }
        }
    }
    @Test void stricterGateFixesFalsePremiseButRefusesAnswerablePortQuestion() throws Exception {
        var configuration = new RetrievalConfiguration(800,5,.8);
        try (var store = new DocumentStore(directory.resolve("strict.json"),mapper,new MarkdownChunker(configuration),true)) {
            var service = new AnswerService(store,new KeywordRetriever(),new OfflineExcerptProvider(),configuration);
            var falsePremise = service.ask(new AskRequest("What backend frontend port salary is approved?",null,null));
            assertThat(falsePremise.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(falsePremise.coverage()).isEqualTo(.6);
            assertThat(falsePremise.segments()).isEmpty();
            var port = service.ask(new AskRequest("What backend port is used?",null,null));
            assertThat(port.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(port.sources()).anySatisfy(s->assertThat(s.heading()).isEqualTo("Backend 後端"));
            assertThat(port.configuration()).containsEntry("coverageThreshold",.8);
        }
    }
    @Test void relaxedGateAcceptsSynonymQuestionWithoutRetrievingExpectedUpdateSource() throws Exception {
        var configuration = new RetrievalConfiguration(800,5,.4);
        try (var store = new DocumentStore(directory.resolve("relaxed.json"),mapper,new MarkdownChunker(configuration),true)) {
            var service = new AnswerService(store,new KeywordRetriever(),new OfflineExcerptProvider(),configuration);
            var answer = service.ask(new AskRequest("How are obsolete passages purged after revisions?",null,null));
            assertThat(answer.status()).isEqualTo("EXTRACTS_FOUND");
            assertThat(answer.sources()).noneSatisfy(s->assertThat(s.documentId()+"::"+s.heading()).isEqualTo("data-policy::Update 更新"));
            assertThat(answer.configuration()).containsEntry("coverageThreshold",.4);
        }
    }
    @Test void smallerTopKLosesCrossSectionReferenceAndRecordsOverride() throws Exception {
        try (var store = new DocumentStore(directory.resolve("topk.json"),mapper,new MarkdownChunker(),true)) {
            var service = new AnswerService(store,new KeywordRetriever(),new OfflineExcerptProvider(),new RetrievalConfiguration(800,1,.6));
            var one = service.ask(new AskRequest("What backend and frontend ports are used?",null,null));
            assertThat(one.sources()).hasSize(1);
            assertThat(one.configuration()).containsEntry("topK",1);
            var five = service.ask(new AskRequest("What backend and frontend ports are used?",null,5));
            assertThat(five.sources().stream().map(Source::heading)).contains("Backend 後端","Frontend 前端");
            assertThat(five.configuration()).containsEntry("topK",5);
        }
    }
    @Test void customChunkingPreservesUnicodeAndRejectsIncompatiblePersistedChunks() throws Exception {
        var configuration = new RetrievalConfiguration(160,5,.6);
        var chunker = new MarkdownChunker(configuration);
        String body = "x".repeat(159)+"🌿"+"y".repeat(300);
        var chunks = chunker.split("unicode",body);
        assertThat(chunks).allSatisfy(c->assertThat(c.text().length()).isLessThanOrEqualTo(160));
        assertThat(String.join("",chunks.stream().map(Chunk::text).toList())).isEqualTo(body);
        Path file = directory.resolve("custom.json");
        try (var store = new DocumentStore(file,mapper,chunker,false)) {store.create(new ImportRequest("unicode","Unicode",body));}
        byte[] original = Files.readAllBytes(file);
        assertThatThrownBy(()->new DocumentStore(file,mapper,new MarkdownChunker(),false)).hasMessageContaining("Stored chunks differ");
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        try (var store = new DocumentStore(file,mapper,chunker,false)) {assertThat(store.get("unicode").chunks()).isEqualTo(chunks);}
    }
    @Test void rejectsInvalidStartupConfiguration() {
        assertThatThrownBy(()->new RetrievalConfiguration(1,5,.6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new RetrievalConfiguration(800,6,.6)).isInstanceOf(IllegalArgumentException.class);
        for (double invalid : new double[]{-.1,1.1,Double.NaN,Double.POSITIVE_INFINITY})
            assertThatThrownBy(()->new RetrievalConfiguration(800,5,invalid)).isInstanceOf(IllegalArgumentException.class);
    }
}
