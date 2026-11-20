package dev.knowledge.platform.chat;

import dev.knowledge.ApiException;
import dev.knowledge.platform.core.Json;
import dev.knowledge.platform.jobs.*;
import dev.knowledge.platform.ops.UsageService;
import dev.knowledge.platform.rag.PlatformRag;
import dev.knowledge.platform.tenant.TenantAccess;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Answers a queued chat message. Authorisation is recomputed when the job runs, never trusted from enqueue time. */
@Component @Profile("platform")
public class ChatAnswerHandler implements JobHandler {
    private final JdbcClient db;
    private final TenantAccess access;
    private final PlatformRag rag;
    private final UsageService usage;
    private final TransactionTemplate tx;
    private final Json json;
    public ChatAnswerHandler(JdbcClient db, TenantAccess access, PlatformRag rag, UsageService usage, TransactionTemplate tx, Json json) {
        this.db = db; this.access = access; this.rag = rag; this.usage = usage; this.tx = tx; this.json = json;
    }
    @Override public String type() { return ChatService.CHAT_ANSWER; }

    @Override public Object handle(JobQueue.Job job, JobContext context) {
        var p = job.payload();
        UUID conversation = UUID.fromString((String) p.get("conversationId"));
        UUID message = UUID.fromString((String) p.get("messageId"));
        UUID user = UUID.fromString((String) p.get("userId"));
        String status = db.sql("select status from messages where id = :m and org_id = :o").param("m", message).param("o", job.orgId()).query(String.class).optional().orElse("GONE");
        if (!"PENDING".equals(status)) return Map.of("skipped", status);
        var ctx = access.contextFor(job.orgId(), user).orElseThrow(() -> new JobFailure("ACCESS_REVOKED: the requester is no longer an active member", false));
        var kbIds = db.sql("select kb_ids from conversations where id = :c and org_id = :o and user_id = :u and deleted_at is null")
                .param("c", conversation).param("o", job.orgId()).param("u", user)
                .query((rs, n) -> dev.knowledge.platform.core.Db.uuidArray(rs, "kb_ids")).optional()
                .orElseThrow(() -> new JobFailure("CONVERSATION_GONE: conversation was deleted", false));
        var readable = access.readableKbIds(ctx);
        var allowed = kbIds.stream().filter(readable::contains).toList();

        String question = (String) p.get("question");
        // Short follow-ups are retrieved together with the previous question (lexical/dense recall only).
        var previous = db.sql("""
                select content from messages where conversation_id = :c and role = 'USER' and id <> :m order by created_at desc offset 1 limit 1""")
                .param("c", conversation).param("m", message).query(String.class).optional();
        String retrievalQuery = previous.isPresent() && question.length() < 40 ? previous.get() + "\n" + question : question;
        String generationQuestion = previous.isPresent() && question.length() < 40 ? "（延續前一個問題：" + previous.get() + "）" + question : question;
        boolean quality = "quality".equals(p.get("model"));
        context.checkpoint();
        long start = System.currentTimeMillis();
        PlatformRag.Result result;
        try {
            result = rag.answer(job.orgId(), allowed, retrievalQuery, generationQuestion, quality);
        } catch (ApiException e) {
            usage.record(job.orgId(), user, null, "CHAT", quality ? "quality" : "fast", 0, 0, System.currentTimeMillis() - start, "FAILURE");
            throw e;
        }
        long latency = System.currentTimeMillis() - start;
        context.checkpoint();
        String answer = result.statements().isEmpty() ? "根據目前可存取的文件，找不到足夠的證據回答這個問題。" :
                String.join("\n", result.statements().stream().map(s -> s.text().strip()).toList());
        String finalStatus = result.statements().isEmpty() ? "REFUSED" : "COMPLETE";
        var meta = new LinkedHashMap<String, Object>();
        meta.put("pipeline", result.stages());
        meta.put("statements", result.statements().stream().map(s -> Map.of("text", s.text(), "citations", s.chunkIds().stream()
                .map(id -> indexOf(result.evidence(), id) + 1).toList())).toList());
        meta.put("retrieval", rag.configuration());
        tx.executeWithoutResult(s -> {
            int updated = db.sql("update messages set content = :t, status = :s, model = :m, meta = cast(:meta as jsonb) where id = :id and status = 'PENDING'")
                    .param("t", answer).param("s", finalStatus).param("m", result.model() == null ? (quality ? "quality" : "fast") : result.model())
                    .param("meta", json.write(meta)).param("id", message).update();
            if (updated == 0) return;
            int ordinal = 1;
            for (var e : result.evidence()) {
                db.sql("""
                        insert into message_citations(org_id, message_id, ordinal, document_id, document_version_id, version_no, chunk_id, title, quote, locator)
                        values (:o, :m, :n, :d, :v, :vn, :c, :t, :q, cast(:l as jsonb))""")
                        .param("o", job.orgId()).param("m", message).param("n", ordinal++).param("d", e.documentId()).param("v", e.versionId())
                        .param("vn", e.versionNo()).param("c", e.chunkId()).param("t", e.title()).param("q", e.content()).param("l", json.write(e.locator())).update();
            }
            db.sql("update conversations set updated_at = now() where id = :c").param("c", conversation).update();
        });
        usage.record(job.orgId(), user, null, "CHAT", result.model(), result.inputTokens(), result.outputTokens(), latency, "SUCCESS");
        return Map.of("status", finalStatus, "citations", result.evidence().size());
    }
    private static int indexOf(List<PlatformRag.Evidence> evidence, String key) {
        for (int i = 0; i < evidence.size(); i++) if ((evidence.get(i).versionId() + "|" + evidence.get(i).chunkId()).equals(key)) return i;
        return -1;
    }

    @Override public void onFinalFailure(JobQueue.Job job, String error) {
        UUID message = UUID.fromString((String) job.payload().get("messageId"));
        String code = error != null && error.contains(":") ? error.substring(0, error.indexOf(':')) : "ANSWER_FAILED";
        db.sql("update messages set status = 'FAILED', content = :t, meta = cast(:meta as jsonb) where id = :id and status = 'PENDING'")
                .param("t", "回答失敗，可稍後重試。").param("meta", json.write(Map.of("error", Map.of("code", code, "message", error == null ? "" : error))))
                .param("id", message).update();
    }
}
