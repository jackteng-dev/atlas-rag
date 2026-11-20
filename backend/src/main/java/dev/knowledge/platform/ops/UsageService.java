package dev.knowledge.platform.ops;

import dev.knowledge.ApiException;
import dev.knowledge.platform.core.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Metering in real units only (requests, tokens, latency). No currency is computed because no unit price is configured.
 * Quotas: monthly tokens and requests (hard limits) plus per-minute request rate per organisation.
 */
@Service @Profile("platform")
public class UsageService {
    public record Quota(long monthlyTokenLimit, long monthlyRequestLimit, int requestsPerMinute, long usedTokens, long usedRequests) {}
    public record Summary(String from, String to, Map<String, Long> totals, List<Map<String, Object>> byUser, List<Map<String, Object>> byModel,
                          List<Map<String, Object>> byAgent, List<Map<String, Object>> daily) {}
    private final JdbcClient db;
    public UsageService(JdbcClient db) { this.db = db; }

    public void record(UUID orgId, UUID userId, UUID agentId, String kind, String model, Integer in, Integer out, long latencyMs, String status) {
        db.sql("""
                insert into usage_events(org_id, user_id, agent_id, kind, model, input_tokens, output_tokens, latency_ms, status, trace_id)
                values (:o, :u, :a, :k, :m, :i, :out, :l, :s, :t)""")
                .param("o", orgId).param("u", userId).param("a", agentId).param("k", kind).param("m", model)
                .param("i", in == null ? 0 : in).param("out", out == null ? 0 : out).param("l", latencyMs).param("s", status).param("t", Trace.current()).update();
    }

    public Quota quota(UUID orgId) {
        var monthStart = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return db.sql("""
                select q.monthly_token_limit, q.monthly_request_limit, q.requests_per_minute,
                  coalesce((select sum(input_tokens + output_tokens) from usage_events where org_id = q.org_id and created_at >= :m), 0) as tokens,
                  coalesce((select count(*) from usage_events where org_id = q.org_id and created_at >= :m), 0) as requests
                from quotas q where q.org_id = :o""").param("o", orgId).param("m", Db.ts(monthStart))
                .query((rs, n) -> new Quota(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getLong("tokens"), rs.getLong("requests"))).optional()
                .orElse(new Quota(0, 0, 0, 0, 0));
    }
    public Quota updateQuota(UUID orgId, long tokens, long requests, int perMinute) {
        if (tokens < 0 || requests < 0 || perMinute < 1 || perMinute > 10_000) throw Errors.bad("INVALID_QUOTA", "Quota values are out of range.");
        db.sql("""
                insert into quotas(org_id, monthly_token_limit, monthly_request_limit, requests_per_minute) values (:o, :t, :r, :p)
                on conflict (org_id) do update set monthly_token_limit = :t, monthly_request_limit = :r, requests_per_minute = :p, updated_at = now()""")
                .param("o", orgId).param("t", tokens).param("r", requests).param("p", perMinute).update();
        return quota(orgId);
    }

    /** Admission control before queueing model work. Pending model jobs count towards the per-minute rate. */
    public void admit(UUID orgId) {
        var q = quota(orgId);
        if (q.monthlyTokenLimit() > 0 && q.usedTokens() >= q.monthlyTokenLimit())
            throw new ApiException(429, "QUOTA_EXCEEDED", "This organisation has used its monthly token quota.");
        if (q.monthlyRequestLimit() > 0 && q.usedRequests() >= q.monthlyRequestLimit())
            throw new ApiException(429, "QUOTA_EXCEEDED", "This organisation has used its monthly request quota.");
        long recent = db.sql("""
                select (select count(*) from jobs where org_id = :o and type in ('CHAT_ANSWER','AGENT_RUN') and created_at > now() - interval '1 minute')""")
                .param("o", orgId).query(Long.class).single();
        if (q.requestsPerMinute() > 0 && recent >= q.requestsPerMinute())
            throw new ApiException(429, "RATE_LIMITED", "Too many model requests this minute. Please wait and retry.");
    }

    public Summary summary(UUID orgId, Instant from, Instant to) {
        var p = Map.<String, Object>of("o", orgId, "f", Db.ts(from), "t", Db.ts(to));
        var totals = db.sql("""
                select count(*) as requests, coalesce(sum(input_tokens),0) as input_tokens, coalesce(sum(output_tokens),0) as output_tokens,
                       count(*) filter (where status <> 'SUCCESS') as failed
                from usage_events where org_id = :o and created_at >= :f and created_at < :t""").params(p)
                .query((rs, n) -> Map.of("requests", rs.getLong(1), "inputTokens", rs.getLong(2), "outputTokens", rs.getLong(3), "failed", rs.getLong(4))).single();
        var byUser = db.sql("""
                select e.user_id, u.email, count(*) r, coalesce(sum(input_tokens + output_tokens),0) tk from usage_events e left join users u on u.id = e.user_id
                where e.org_id = :o and e.created_at >= :f and e.created_at < :t group by e.user_id, u.email order by tk desc limit 50""").params(p)
                .query((rs, n) -> Map.<String, Object>of("userId", String.valueOf(rs.getString(1)), "email", String.valueOf(rs.getString(2)), "requests", rs.getLong(3), "tokens", rs.getLong(4))).list();
        var byModel = db.sql("""
                select coalesce(model,'none') m, count(*) r, coalesce(sum(input_tokens + output_tokens),0) tk, coalesce(avg(latency_ms),0) avg_ms,
                       coalesce(percentile_cont(0.95) within group (order by latency_ms),0) p95
                from usage_events where org_id = :o and created_at >= :f and created_at < :t group by model order by r desc""").params(p)
                .query((rs, n) -> Map.<String, Object>of("model", rs.getString(1), "requests", rs.getLong(2), "tokens", rs.getLong(3),
                        "avgLatencyMs", Math.round(rs.getDouble(4)), "p95LatencyMs", Math.round(rs.getDouble(5)))).list();
        var byAgent = db.sql("""
                select e.agent_id, a.name, count(*) r, coalesce(sum(input_tokens + output_tokens),0) tk from usage_events e join agents a on a.id = e.agent_id and a.org_id = e.org_id
                where e.org_id = :o and e.created_at >= :f and e.created_at < :t and e.agent_id is not null group by e.agent_id, a.name order by r desc""").params(p)
                .query((rs, n) -> Map.<String, Object>of("agentId", rs.getString(1), "name", rs.getString(2), "runs", rs.getLong(3), "tokens", rs.getLong(4))).list();
        var daily = db.sql("""
                select to_char(date_trunc('day', created_at at time zone 'UTC'), 'YYYY-MM-DD') d, count(*) r, coalesce(sum(input_tokens + output_tokens),0) tk
                from usage_events where org_id = :o and created_at >= :f and created_at < :t group by 1 order by 1""").params(p)
                .query((rs, n) -> Map.<String, Object>of("day", rs.getString(1), "requests", rs.getLong(2), "tokens", rs.getLong(3))).list();
        return new Summary(from.toString(), to.toString(), totals, byUser, byModel, byAgent, daily);
    }
}
