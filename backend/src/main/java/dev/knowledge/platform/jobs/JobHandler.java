package dev.knowledge.platform.jobs;

/** A background job type. Handlers must be idempotent: a job can run again after a crash or lease expiry. */
public interface JobHandler {
    String type();
    /** Returns a small JSON-serialisable result. Throw {@link JobFailure} to control retry behaviour. */
    Object handle(JobQueue.Job job, JobContext context) throws Exception;
    /** Called once when the job will not be retried any more (failed permanently or cancelled). */
    default void onFinalFailure(JobQueue.Job job, String error) {}

    interface JobContext {
        /** Throws {@link JobFailure} (non-retryable, CANCELLED) when the user asked to cancel. */
        void checkpoint();
    }
    final class JobFailure extends RuntimeException {
        public final boolean retryable;
        public JobFailure(String message, boolean retryable) { super(message); this.retryable = retryable; }
    }
}
