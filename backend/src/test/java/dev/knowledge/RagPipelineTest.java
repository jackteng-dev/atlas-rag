package dev.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static dev.knowledge.Models.*;
import static org.assertj.core.api.Assertions.*;

class RagPipelineTest {
    @TempDir Path directory;
    DocumentStore store;
    FixtureModels models;
    FixtureVectors vectors;
    AnswerService answers;
    @BeforeEach void setup() throws Exception {
        store=new DocumentStore(directory.resolve("store.json"),new ObjectMapper(),new MarkdownChunker(),false);
        models=new FixtureModels();vectors=new FixtureVectors();
        answers=new AnswerService(store,new KeywordRetriever(),new OfflineExcerptProvider(),RetrievalConfiguration.baseline(),
                new DocumentAccess(),new RagEngine(true,models,vectors,10,-6));
    }
    @AfterEach void close() throws Exception { store.close(); }
    @Test void unauthorizedExpiredAndStaleVectorCandidatesNeverReachRerankerOrGeneration() {
        var publicDoc=store.create(new ImportRequest("public-guide","Public","## Stack\nJava Spring Boot Vue public evidence"));
        var secret=store.create(new ImportRequest("hr-guide","Secret","SALARY_SECRET forbidden body",new Metadata("hr","hr-owner",null,List.of("HR"),List.of())));
        var expired=store.create(new ImportRequest("expired","Expired","EXPIRED_SECRET body",new Metadata("public","public-demo","2000-01-01T00:00:00Z",List.of(),List.of())));
        vectors.extra=List.of(new VectorIndex.Match(secret.id(),secret.version(),secret.chunks().getFirst().id(),100),
                new VectorIndex.Match(expired.id(),expired.version(),expired.chunks().getFirst().id(),100),
                new VectorIndex.Match(publicDoc.id(),0,publicDoc.chunks().getFirst().id(),100));
        var result=answers.ask(new AskRequest("用什麼架構",null,5));
        assertThat(result.status()).isEqualTo("ANSWER_GENERATED");
        assertThat(vectors.lastScope).extracting(Document::id).containsExactly("public-guide");
        assertThat(models.rerankTexts).allMatch(s->!s.contains("SECRET"));
        assertThat(models.context).extracting(Source::documentId).containsOnly("public-guide");
        assertThat(result.sources()).extracting(Source::documentId).containsOnly("public-guide");
        assertThatThrownBy(()->answers.ask(new AskRequest("salary",List.of("hr-guide"),5))).isInstanceOf(ApiException.class);
        assertThat(result.generated().getFirst().chunkIds()).containsExactly(publicDoc.chunks().getFirst().id());
    }
    @Test void updateDeleteAndMetadataChangeReplaceVectorVersionsAndDoNotReuseOldCitations() {
        var before=store.create(new ImportRequest("guide","Guide","oldtoken original evidence"));
        var a=answers.ask(new AskRequest("oldtoken",null,5));
        assertThat(a.sources().getFirst().version()).isEqualTo(1);
        var updated=store.update("guide",new UpdateRequest("Guide","newtoken updated evidence",1));
        vectors.extra=List.of(new VectorIndex.Match(before.id(),before.version(),before.chunks().getFirst().id(),100));
        var b=answers.ask(new AskRequest("newtoken",null,5));
        assertThat(b.sources()).allMatch(s->s.version()==2 && !s.excerpt().contains("oldtoken"));
        assertThat(vectors.points).extracting(p->p.document().version()).containsOnly(2);
        store.update("guide",new UpdateRequest("Guide",updated.markdown(),2,new Metadata("public","public-demo","2000-01-01T00:00:00Z",List.of(),List.of())));
        var refused=answers.ask(new AskRequest("newtoken",null,5));
        assertThat(refused.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(refused.sources()).isEmpty();assertThat(vectors.points).isEmpty();
        store.delete("guide",3);
        assertThat(answers.ask(new AskRequest("newtoken",null,5)).sources()).isEmpty();
    }
    @Test void forgedGenerationAndRerankingFailClosed() {
        store.create(new ImportRequest("guide","Guide","public evidence"));
        models.forgeCitation=true;
        assertThatThrownBy(()->answers.ask(new AskRequest("public",null,5))).isInstanceOf(ApiException.class).hasMessageContaining("outside the authorized");
        models.forgeCitation=false;models.forgeRank=true;
        assertThatThrownBy(()->answers.ask(new AskRequest("public",null,5))).isInstanceOf(ApiException.class).hasMessageContaining("invalid candidate");
    }
    @Test void weakRerankerEvidenceSkipsGenerationAndReportsMeasuredStages() {
        store.create(new ImportRequest("guide","Guide","backend Spring Boot"));models.score=-10;
        var answer=answers.ask(new AskRequest("salary",null,5));
        assertThat(answer.status()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(models.context).isEmpty();
        assertThat(answer.pipeline()).anyMatch(s->s.name().equals("generation") && s.status().equals("SKIPPED_NO_EVIDENCE"));
        assertThat(answer.modelInputTokens()).isNull();
        assertThat(answer.evaluation()).containsEntry("faithfulness","NOT_AUTOMATICALLY_MEASURED");
    }
    @Test void widerEvidenceFloorStillExcludesUnrelatedContextAndDoesNotAcceptWeakHits() {
        var doc=store.create(new ImportRequest("guide","Guide","## A\naccount roles are assigned by the server\n\n## B\nother unrelated deployment detail"));
        models.rankScores=List.of(-7.5,-10.0);
        answers=new AnswerService(store,new KeywordRetriever(),new OfflineExcerptProvider(),RetrievalConfiguration.baseline(),
                new DocumentAccess(),new RagEngine(true,models,vectors,10,-8));
        var result=answers.ask(new AskRequest("roles",null,5));
        assertThat(result.generated()).hasSize(1);
        assertThat(models.context).hasSize(1);
        models.rankScores=List.of(-9.0,-10.0);
        models.context=List.of();
        assertThat(answers.ask(new AskRequest("salary",null,5)).status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(models.context).isEmpty();
    }
    @Test void rrfRewardsAgreementDeduplicatesAndBreaksTiesDeterministically() {
        var doc=store.create(new ImportRequest("guide","Guide","## A\nalpha\n\n## B\nbeta\n\n## C\ngamma"));
        var a=RagEngine.source(doc,doc.chunks().get(0),1);var b=RagEngine.source(doc,doc.chunks().get(1),2);var c=RagEngine.source(doc,doc.chunks().get(2),3);
        var fused=RagEngine.fuse(List.of(a,b,a),List.of(c,b));
        assertThat(fused).hasSize(3);assertThat(fused.getFirst().chunkId()).isEqualTo(b.chunkId());
        assertThat(RagEngine.fuse(List.of(a,c),List.of(c,a))).extracting(Source::chunkId).isSorted();
    }
    @Test void semanticGroupsPreserveExactWhitespaceHeadingBoundariesAndSizeLimits() {
        String markdown="## Topic\nFirst related paragraph.\n\n \nSecond related paragraph.\n\n## Other\nSeparate topic.";
        var chunks=new MarkdownChunker().split("semantic",markdown);
        var merged=new SemanticBoundaryMerger(models,800).merge("semantic",chunks,markdown);
        assertThat(merged).hasSize(2);
        assertThat(merged.getFirst().text()).isEqualTo("First related paragraph.\n\n \nSecond related paragraph.");
        assertThat(markdown).contains(merged.getFirst().text());
        assertThat(merged.getFirst().lineStart()).isEqualTo(2);assertThat(merged.getFirst().lineEnd()).isEqualTo(5);
        assertThat(new SemanticBoundaryMerger(models,32).merge("semantic",chunks,markdown)).hasSize(3);
    }
    static class FixtureModels implements RagModels {
        List<String> rerankTexts=List.of();List<Source> context=List.of();List<Double> rankScores=List.of();boolean forgeCitation,forgeRank;double score=0;
        public List<List<Double>> embed(List<String> texts) { return texts.stream().map(t->List.of(1.0,0.0)).toList(); }
        public List<Ranked> rerank(String q,List<String> texts) {
            rerankTexts=texts;
            if(forgeRank) return List.of(new Ranked(999,0));
            var results=new ArrayList<Ranked>();for(int i=0;i<texts.size();i++) results.add(new Ranked(i,rankScores.isEmpty()?score:rankScores.get(i)));return results;
        }
        public Generation generate(String q,List<Source> sources) {
            context=sources;return new Generation(List.of(new GeneratedStatement("Fixture statement",List.of(forgeCitation?"forged":sources.getFirst().chunkId()))),12,4);
        }
    }
    static class FixtureVectors implements VectorIndex {
        List<Point> points=List.of();List<Match> extra=List.of();List<Document> lastScope=List.of();
        public void replace(List<Point> points) { this.points=points; }
        public List<Match> query(List<Double> vector,List<Document> scope,int k) {
            lastScope=scope;var results=new ArrayList<>(extra);
            for(var p:points) results.add(new Match(p.document().id(),p.document().version(),p.chunk().id(),.8));return results;
        }
    }
}
