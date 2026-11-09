package dev.knowledge;

import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import static dev.knowledge.Models.*;

/** One admitted inference, bounded ephemeral results, captured server identity and publication checks. */
@Service
public class QuestionJobs implements AutoCloseable {
    public record JobError(int httpStatus,String code,String message) {}
    public record View(String id,String status,Answer answer,JobError error,long elapsedMs) {}
    private record Outcome(String status,Answer answer,JobError error) {}
    private static class Job {
        final String id=UUID.randomUUID().toString();
        final DocumentAccess.Actor actor;
        final Instant expires;
        final long started=System.nanoTime();
        volatile Outcome outcome=new Outcome("RUNNING",null,null);
        Job(DocumentAccess.Actor actor,Instant expires) { this.actor=actor;this.expires=expires; }
    }
    private final AnswerService answers;
    private final DocumentStore store;
    private final DocumentAccess access;
    private final Clock clock;
    private final Duration ttl;
    private final int capacity;
    private final ConcurrentHashMap<String,Job> jobs=new ConcurrentHashMap<>();
    private final AtomicBoolean active=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{
        var thread=new Thread(task,"question-job");thread.setDaemon(true);return thread;
    });
    @org.springframework.beans.factory.annotation.Autowired
    public QuestionJobs(AnswerService answers,DocumentStore store,DocumentAccess access) {
        this(answers,store,access,Clock.systemUTC(),Duration.ofMinutes(10),20);
    }
    QuestionJobs(AnswerService answers,DocumentStore store,DocumentAccess access,Clock clock,Duration ttl,int capacity) {
        this.answers=answers;this.store=store;this.access=access;this.clock=clock;this.ttl=ttl;this.capacity=capacity;
    }
    public View submit(AskRequest request) {
        if(!active.compareAndSet(false,true)) throw new ApiException(503,"MODEL_BUSY","The local model is processing another question. Retry shortly.");
        Job job=null;
        try {
            jobs.values().removeIf(value->!value.expires.isAfter(clock.instant()));
            if(jobs.size()>=capacity) jobs.values().stream().min(Comparator.comparingLong(value->value.started)).ifPresent(value->jobs.remove(value.id));
            job=new Job(access.current(),clock.instant().plus(ttl));
            jobs.put(job.id,job);
            Job admitted=job;
            worker.execute(()->{
                try { admitted.outcome=new Outcome("COMPLETE",answers.ask(request,admitted.actor),null); }
                catch(ApiException error) { admitted.outcome=new Outcome("FAILED",null,new JobError(error.status,error.code,error.getMessage())); }
                catch(Exception error) { admitted.outcome=new Outcome("FAILED",null,new JobError(500,"JOB_FAILED","Question processing failed. No answer was published.")); }
                finally { active.set(false); }
            });
            // Creation never includes an answer, even if a tiny offline query has already finished.
            return new View(job.id,"RUNNING",null,null,0);
        } catch(RuntimeException error) {
            if(job!=null) jobs.remove(job.id);
            active.set(false);throw error;
        }
    }
    public View get(String id) {
        Job job=jobs.get(id);
        var actor=access.current();
        if(job==null || !job.actor.name().equals(actor.name()) || !job.expires.isAfter(clock.instant())) {
            if(job!=null && !job.expires.isAfter(clock.instant())) jobs.remove(id);
            throw new ApiException(404,"JOB_NOT_FOUND","Question job is unavailable or expired.");
        }
        Outcome result=job.outcome;
        if(result.answer()!=null) {
            var snapshot=store.snapshot();
            if(snapshot.revision()!=result.answer().indexRevision()) throw new ApiException(409,"INDEX_CHANGED","Documents changed. Run the question again.");
            for(var source:result.answer().sources()) {
                var doc=DocumentStore.get(snapshot,source.documentId());
                if(doc.version()!=source.version() || !access.eligible(doc,actor,clock.instant()))
                    throw new ApiException(409,"INDEX_CHANGED","Question evidence is no longer current or readable.");
            }
        }
        return new View(id,result.status(),result.answer(),result.error(),(System.nanoTime()-job.started)/1_000_000);
    }
    boolean busy() { return active.get(); }
    @PreDestroy public void close() { worker.shutdownNow();jobs.clear(); }
}
