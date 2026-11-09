package dev.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static dev.knowledge.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuestionJobsTest {
    @TempDir Path directory;
    DocumentStore store;
    QuestionJobs jobs;
    final DocumentAccess access=new DocumentAccess("configured");
    final AskRequest request=new AskRequest("budget allowance",null,null);
    @BeforeEach void create() throws Exception { store=new DocumentStore(directory.resolve("store.json"),new ObjectMapper(),new MarkdownChunker(),false); }
    @AfterEach void cleanup() throws Exception { if(jobs!=null)jobs.close();store.close();SecurityContextHolder.clearContext(); }
    private void login(String name,String...authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
    private QuestionJobs.View complete(String id) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end) {
            var view=jobs.get(id);
            if(!view.status().equals("RUNNING") && !jobs.busy())return view;
            Thread.sleep(5);
        }
        throw new AssertionError("Fixture job did not finish");
    }
    private AnswerService realAnswers() {
        return new AnswerService(store,new KeywordRetriever(),new OfflineExcerptProvider(),RetrievalConfiguration.baseline(),access,null);
    }
    @Test void workerUsesCapturedServerIdentityAndOtherUsersCannotReadItsResult() throws Exception {
        store.create(new ImportRequest("private-budget","Budget","## Budget\nbudget allowance equals 4800.",new Metadata("hr","owner",null,List.of("HR"),List.of())));
        login("hr-user","ROLE_HR","DEPARTMENT_hr");
        jobs=new QuestionJobs(realAnswers(),store,access);
        var created=jobs.submit(request);assertThat(created.answer()).isNull();
        var answer=complete(created.id());assertThat(answer.status()).isEqualTo("COMPLETE");
        assertThat(answer.answer().sources()).extracting(Source::documentId).containsExactly("private-budget");
        login("other-user","ROLE_ADMIN");
        assertThatThrownBy(()->jobs.get(created.id())).isInstanceOf(ApiException.class).hasMessageContaining("unavailable");
    }
    @Test void completedResultsRecheckExpiryAndDocumentRevisionBeforePublication() throws Exception {
        var clock=new MutableClock(Instant.now());
        store.create(new ImportRequest("budget","Budget","## Budget\nbudget allowance equals 4800.",new Metadata("public","public-demo",clock.instant().plusSeconds(90).toString(),List.of(),List.of())));
        jobs=new QuestionJobs(realAnswers(),store,access,clock,Duration.ofMinutes(10),20);
        var created=jobs.submit(request);assertThat(complete(created.id()).answer().sources()).hasSize(1);
        clock.value=clock.value.plusSeconds(120);
        assertThatThrownBy(()->jobs.get(created.id())).isInstanceOf(ApiException.class).hasMessageContaining("no longer current");
        clock.value=clock.value.minusSeconds(120);
        var doc=store.get("budget");store.update(doc.id(),new UpdateRequest(doc.title(),doc.markdown()+"\nnew fact",doc.version(),doc.metadata()));
        assertThatThrownBy(()->jobs.get(created.id())).isInstanceOf(ApiException.class).hasMessageContaining("Documents changed");
    }
    @Test void admissionIsBoundedAndUnexpectedFailureDoesNotExposeInternalDetails() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var answers=mock(AnswerService.class);
        when(answers.ask(eq(request),any(DocumentAccess.Actor.class))).thenAnswer(invocation->{entered.countDown();release.await(5,TimeUnit.SECONDS);throw new IllegalStateException("private diagnostic must not be returned");});
        jobs=new QuestionJobs(answers,store,access);
        var created=jobs.submit(request);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(()->jobs.submit(request)).isInstanceOf(ApiException.class).hasMessageContaining("processing another");
        assertThat(jobs.get(created.id()).answer()).isNull();
        release.countDown();var failed=complete(created.id());
        assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.error().code()).isEqualTo("JOB_FAILED");
        assertThat(failed.error().message()).doesNotContain("private diagnostic");
    }
    @Test void expiredJobIdentifiersStopResolving() throws Exception {
        var clock=new MutableClock(Instant.now());jobs=new QuestionJobs(realAnswers(),store,access,clock,Duration.ofMinutes(10),20);
        var created=jobs.submit(request);complete(created.id());clock.value=clock.value.plusSeconds(601);
        assertThatThrownBy(()->jobs.get(created.id())).isInstanceOf(ApiException.class).hasMessageContaining("expired");
    }
    static class MutableClock extends Clock {
        Instant value;MutableClock(Instant value){this.value=value;}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}
    }
}
