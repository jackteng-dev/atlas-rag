package dev.knowledge.platform.jobs;

import dev.knowledge.ApiException;
import dev.knowledge.platform.core.Trace;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** In-process workers polling the durable queue. Several app instances can run them safely (SKIP LOCKED). */
@Component @Profile("platform")
public class JobWorker implements AutoCloseable {
    private final JobQueue queue;
    private final Map<String, JobHandler> handlers = new HashMap<>();
    private final int workers;
    private final long pollMs;
    private final Duration lease;
    private final String instance = "w-" + UUID.randomUUID().toString().substring(0, 8);
    private ScheduledExecutorService executor;
    /** Renews leases of running jobs, so a short lease detects crashed workers quickly while long CPU inference keeps its claim. */
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "atlas-job-heartbeat"); t.setDaemon(true); return t; });

    public JobWorker(JobQueue queue, List<JobHandler> handlers, @Value("${atlas.jobs.workers:2}") int workers,
                     @Value("${atlas.jobs.poll-ms:500}") long pollMs, @Value("${atlas.jobs.lease-seconds:900}") long leaseSeconds) {
        this.queue = queue; this.workers = workers; this.pollMs = pollMs; this.lease = Duration.ofSeconds(leaseSeconds);
        handlers.forEach(h -> this.handlers.put(h.type(), h));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (workers <= 0 || executor != null) return;
        executor = Executors.newScheduledThreadPool(workers, r -> { var t = new Thread(r, "atlas-job-" + instance); t.setDaemon(true); return t; });
        for (int i = 0; i < workers; i++) {
            String name = instance + "-" + i;
            executor.scheduleWithFixedDelay(() -> drain(name), pollMs, pollMs, TimeUnit.MILLISECONDS);
        }
    }

    private void drain(String worker) {
        try { while (runOnce(worker)) { /* keep draining while work exists */ } }
        catch (Throwable t) { LoggerFactory.getLogger(JobWorker.class).warn("Job worker loop error", t); }
    }

    /** Runs a single job if one is available. Public for tests. */
    public boolean runOnce(String worker) {
        var claimed = queue.claim(worker, lease, handlers.keySet());
        if (claimed.isEmpty()) return false;
        var job = claimed.get();
        var handler = handlers.get(job.type());
        Trace.set(job.traceId());
        long every = Math.max(1, lease.toSeconds() / 3);
        var beat = heartbeats.scheduleAtFixedRate(() -> { try { queue.heartbeat(job.id(), lease); } catch (RuntimeException ignored) { } }, every, every, TimeUnit.SECONDS);
        try {
            var result = handler.handle(job, () -> {
                if (queue.cancelRequested(job.id())) throw new JobHandler.JobFailure("Cancelled by user", false);
                queue.heartbeat(job.id(), lease);
            });
            queue.succeed(job.id(), result);
        } catch (JobHandler.JobFailure f) {
            failed(handler, job, f.getMessage(), f.retryable);
        } catch (ApiException e) {
            // 5xx from local services (model busy/timeout, storage unavailable) are transient; 4xx are not.
            failed(handler, job, e.code + ": " + e.getMessage(), e.status >= 500);
        } catch (Exception e) {
            LoggerFactory.getLogger(JobWorker.class).warn("Job {} ({}) failed", job.id(), job.type(), e);
            failed(handler, job, "INTERNAL_ERROR: " + e.getClass().getSimpleName(), true);
        } finally { beat.cancel(false); Trace.clear(); }
        return true;
    }

    private void failed(JobHandler handler, JobQueue.Job job, String error, boolean retryable) {
        if (!queue.fail(job.id(), error, retryable)) {
            try { handler.onFinalFailure(job, error); }
            catch (Exception e) { LoggerFactory.getLogger(JobWorker.class).warn("Final-failure hook for job {} failed", job.id(), e); }
        }
    }

    @Override public void close() { if (executor != null) executor.shutdownNow(); heartbeats.shutdownNow(); }
}
