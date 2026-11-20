package dev.knowledge.platform.jobs;

import dev.knowledge.platform.core.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable PostgreSQL job queue: {@code FOR UPDATE SKIP LOCKED} claims, leases that expire if a worker dies, bounded
 * retries with exponential backoff, cooperative cancel and idempotency keys. Jobs survive restarts; a job whose lease
 * expired during a crash is picked up again (handlers are written to be idempotent).
 */
@Repository @Profile("platform")
public class JobQueue {
    public record Job(UUID id, UUID orgId, String type, String state, Map<String, Object> payload, int attempts, int maxAttempts,
                      String lastError, UUID createdBy, String traceId, String createdAt, String updatedAt, Object result, boolean cancelRequested) {}
    public record View(String id, String type, String state, int attempts, int maxAttempts, String lastError, String createdAt, String updatedAt, String traceId, Object result) {}

    private final JdbcClient db;
    private final Json json;
    public JobQueue(JdbcClient db, Json json) { this.db = db; this.json = json; }

    private Job map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Job(Db.uuid(rs, "id"), Db.uuid(rs, "org_id"), rs.getString("type"), rs.getString("state"), json.map(rs.getString("payload")),
                rs.getInt("attempts"), rs.getInt("max_attempts"), rs.getString("last_error"), Db.uuid(rs, "created_by"), rs.getString("trace_id"),
                Db.iso(rs, "created_at"), Db.iso(rs, "updated_at"), json.any(rs.getString("result")), rs.getBoolean("cancel_requested"));
    }
    public static View view(Job j) { return new View(j.id().toString(), j.type(), j.state(), j.attempts(), j.maxAttempts(), j.lastError(), j.createdAt(), j.updatedAt(), j.traceId(), j.result()); }

    public UUID enqueue(UUID orgId, String type, Map<String, ?> payload, UUID createdBy, String idempotencyKey, int priority, int maxAttempts) {
        if (idempotencyKey != null) {
            var existing = db.sql("select id from jobs where org_id = :o and type = :t and idempotency_key = :k and state in ('QUEUED','RUNNING')")
                    .param("o", orgId).param("t", type).param("k", idempotencyKey).query(UUID.class).optional();
            if (existing.isPresent()) return existing.get();
        }
        var id = UUID.randomUUID();
        db.sql("""
                insert into jobs(id, org_id, type, state, payload, max_attempts, priority, idempotency_key, created_by, trace_id)
                values (:id, :o, :t, 'QUEUED', cast(:p as jsonb), :m, :pr, :k, :by, :trace)""")
                .param("id", id).param("o", orgId).param("t", type).param("p", json.write(payload)).param("m", maxAttempts).param("pr", priority)
                .param("k", idempotencyKey).param("by", createdBy).param("trace", Trace.current()).update();
        return id;
    }

    /** Claims one runnable job (queued, or running with an expired lease) for this worker. */
    @Transactional
    public Optional<Job> claim(String worker, Duration lease, Set<String> types) {
        var row = db.sql("""
                select * from jobs where type in (:types) and (
                    (state = 'QUEUED' and run_after <= now()) or (state = 'RUNNING' and lease_until < now()))
                order by priority, run_after limit 1 for update skip locked""").param("types", types).query((rs, n) -> map(rs)).optional();
        if (row.isEmpty()) return Optional.empty();
        var job = row.get();
        if (job.cancelRequested()) {
            db.sql("update jobs set state = 'CANCELLED', lease_owner = null, lease_until = null, updated_at = now() where id = :id").param("id", job.id()).update();
            return Optional.empty();
        }
        db.sql("update jobs set state = 'RUNNING', attempts = attempts + 1, lease_owner = :w, lease_until = :l, updated_at = now() where id = :id")
                .param("w", worker).param("l", Db.ts(Instant.now().plus(lease))).param("id", job.id()).update();
        return byId(job.id());
    }
    public void succeed(UUID id, Object result) {
        db.sql("update jobs set state = 'SUCCEEDED', result = cast(:r as jsonb), lease_owner = null, lease_until = null, last_error = null, updated_at = now() where id = :id")
                .param("r", json.write(result == null ? Map.of() : result)).param("id", id).update();
    }
    /** Retries with exponential backoff (5s, 20s, 80s …) until max attempts, then FAILED. Returns true if it will retry. */
    public boolean fail(UUID id, String error, boolean retryable) {
        var job = byId(id).orElseThrow();
        String message = error == null ? "Unknown error" : error.length() > 480 ? error.substring(0, 480) : error;
        if (retryable && job.attempts() < job.maxAttempts() && !job.cancelRequested()) {
            long delay = (long) (5 * Math.pow(4, Math.max(0, job.attempts() - 1)));
            db.sql("update jobs set state = 'QUEUED', run_after = now() + make_interval(secs => :d), last_error = :e, lease_owner = null, lease_until = null, updated_at = now() where id = :id")
                    .param("d", delay).param("e", message).param("id", id).update();
            return true;
        }
        db.sql("update jobs set state = :s, last_error = :e, lease_owner = null, lease_until = null, updated_at = now() where id = :id")
                .param("s", job.cancelRequested() ? "CANCELLED" : "FAILED").param("e", message).param("id", id).update();
        return false;
    }
    public void heartbeat(UUID id, Duration lease) {
        db.sql("update jobs set lease_until = :l where id = :id and state = 'RUNNING'").param("l", Db.ts(Instant.now().plus(lease))).param("id", id).update();
    }
    /** Queued jobs cancel immediately; running jobs are flagged and stop at their next checkpoint. */
    public boolean cancel(UUID orgId, UUID id) {
        int queued = db.sql("update jobs set state = 'CANCELLED', cancel_requested = true, updated_at = now() where org_id = :o and id = :id and state = 'QUEUED'")
                .param("o", orgId).param("id", id).update();
        if (queued > 0) return true;
        return db.sql("update jobs set cancel_requested = true, updated_at = now() where org_id = :o and id = :id and state = 'RUNNING'")
                .param("o", orgId).param("id", id).update() > 0;
    }
    public boolean cancelRequested(UUID id) {
        return db.sql("select cancel_requested from jobs where id = :id").param("id", id).query(Boolean.class).optional().orElse(true);
    }
    public boolean retry(UUID orgId, UUID id) {
        return db.sql("""
                update jobs set state = 'QUEUED', attempts = 0, cancel_requested = false, run_after = now(), last_error = null, updated_at = now()
                where org_id = :o and id = :id and state in ('FAILED','CANCELLED')""").param("o", orgId).param("id", id).update() > 0;
    }
    public Optional<Job> byId(UUID id) { return db.sql("select * from jobs where id = :id").param("id", id).query((rs, n) -> map(rs)).optional(); }
    public Optional<Job> byId(UUID orgId, UUID id) {
        return db.sql("select * from jobs where org_id = :o and id = :id").param("o", orgId).param("id", id).query((rs, n) -> map(rs)).optional();
    }
    public List<Job> list(UUID orgId, UUID createdBy, String type, String state, int limit) {
        var sql = new StringBuilder("select * from jobs where org_id = :o");
        if (createdBy != null) sql.append(" and created_by = :by");
        if (type != null) sql.append(" and type = :t");
        if (state != null) sql.append(" and state = :s");
        sql.append(" order by created_at desc limit :l");
        var q = db.sql(sql.toString()).param("o", orgId).param("l", limit);
        if (createdBy != null) q = q.param("by", createdBy);
        if (type != null) q = q.param("t", type);
        if (state != null) q = q.param("s", state);
        return q.query((rs, n) -> map(rs)).list();
    }
    public List<Job> listAll(int limit) { return db.sql("select * from jobs order by created_at desc limit :l").param("l", limit).query((rs, n) -> map(rs)).list(); }
    public Map<String, Long> countsByState() {
        var map = new LinkedHashMap<String, Long>();
        db.sql("select state, count(*) c from jobs group by state").query((rs, n) -> map.put(rs.getString("state"), rs.getLong("c"))).list();
        return map;
    }
}
