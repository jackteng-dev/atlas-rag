package dev.knowledge.platform.chat;

import dev.knowledge.platform.core.*;
import dev.knowledge.platform.jobs.JobQueue;
import dev.knowledge.platform.ops.*;
import dev.knowledge.platform.tenant.TenantAccess;
import dev.knowledge.platform.tenant.TenantAccess.*;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversations are private to their author inside one organisation. Citations are re-authorised on every read, so a
 * document that was deleted, expired or became unreadable cannot keep leaking through chat history.
 */
@Service @Profile("platform")
public class ChatService {
    public static final String CHAT_ANSWER = "CHAT_ANSWER";
    public static final String HIDDEN = "此回答引用的文件已刪除、到期或您已無權限存取，內容已隱藏。";
    public record Conversation(String id, String title, List<String> kbIds, String createdAt, String updatedAt) {}
    public record Citation(int ordinal, String documentId, int versionNo, String chunkId, String title, String quote, Map<String, Object> locator, String state) {}
    public record Message(String id, String role, String content, String status, String model, String createdAt, String jobId, List<Citation> citations, Map<String, Object> meta) {}
    public record Detail(Conversation conversation, List<Message> messages) {}
    public record Posted(Message userMessage, Message assistantMessage, JobQueue.View job) {}

    private final JdbcClient db;
    private final TenantAccess access;
    private final JobQueue jobs;
    private final UsageService usage;
    private final AuditService audit;
    private final Json json;
    public ChatService(JdbcClient db, TenantAccess access, JobQueue jobs, UsageService usage, AuditService audit, Json json) {
        this.db = db; this.access = access; this.jobs = jobs; this.usage = usage; this.audit = audit; this.json = json;
    }

    private Conversation mapConversation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Conversation(rs.getString("id"), rs.getString("title"), Db.uuidArray(rs, "kb_ids").stream().map(UUID::toString).toList(),
                Db.iso(rs, "created_at"), Db.iso(rs, "updated_at"));
    }
    public List<Conversation> list(OrgContext ctx) {
        return db.sql("select * from conversations where org_id = :o and user_id = :u and deleted_at is null order by updated_at desc limit 200")
                .param("o", ctx.orgId()).param("u", ctx.userId()).query((rs, n) -> mapConversation(rs)).list();
    }
    public Conversation create(OrgContext ctx, String title, List<String> kbTexts) {
        var kbs = validateKbs(ctx, kbTexts);
        var id = UUID.randomUUID();
        String t = title == null || title.isBlank() ? "新對話" : title.strip();
        if (t.length() > 200) t = t.substring(0, 200);
        db.sql("insert into conversations(id, org_id, user_id, title, kb_ids) values (:id, :o, :u, :t, :k)")
                .param("id", id).param("o", ctx.orgId()).param("u", ctx.userId()).param("t", t).param("k", kbs.toArray(UUID[]::new)).update();
        return own(ctx, id.toString());
    }
    List<UUID> validateKbs(OrgContext ctx, List<String> kbTexts) {
        if (kbTexts == null || kbTexts.isEmpty()) throw Errors.bad("KB_REQUIRED", "Select at least one knowledge base.");
        if (kbTexts.size() > 20) throw Errors.bad("TOO_MANY_KBS", "Select up to 20 knowledge bases.");
        var readable = access.readableKbIds(ctx);
        var result = new ArrayList<UUID>();
        for (var text : kbTexts) {
            var id = Db.parse(text, "knowledge base");
            if (!readable.contains(id)) throw Errors.notFound("knowledge base");
            if (!result.contains(id)) result.add(id);
        }
        return result;
    }
    public Conversation own(OrgContext ctx, String idText) {
        return db.sql("select * from conversations where org_id = :o and id = :id and user_id = :u and deleted_at is null")
                .param("o", ctx.orgId()).param("id", Db.parse(idText, "conversation")).param("u", ctx.userId())
                .query((rs, n) -> mapConversation(rs)).optional().orElseThrow(() -> Errors.notFound("conversation"));
    }
    public void delete(OrgContext ctx, String idText) {
        var c = own(ctx, idText);
        db.sql("update conversations set deleted_at = now() where id = :id").param("id", UUID.fromString(c.id())).update();
        audit.record(ctx.orgId(), ctx.userId(), "conversation.delete", "conversation", c.id());
    }

    @Transactional
    public Posted post(OrgContext ctx, String conversationText, String content, String model) {
        var conv = own(ctx, conversationText);
        if (content == null || content.isBlank() || content.length() > 2000) throw Errors.bad("INVALID_MESSAGE", "Messages must be 1–2000 characters.");
        if (model != null && !Set.of("fast", "quality").contains(model)) throw Errors.bad("INVALID_MODEL", "Model must be fast or quality.");
        long pending = db.sql("select count(*) from messages where conversation_id = :c and status = 'PENDING'").param("c", UUID.fromString(conv.id())).query(Long.class).single();
        if (pending > 0) throw Errors.conflict("ANSWER_PENDING", "Wait for the current answer before asking again.");
        usage.admit(ctx.orgId());
        var convId = UUID.fromString(conv.id());
        var userMsg = UUID.randomUUID(); var assistantMsg = UUID.randomUUID();
        db.sql("insert into messages(id, org_id, conversation_id, role, content, status) values (:id, :o, :c, 'USER', :t, 'COMPLETE')")
                .param("id", userMsg).param("o", ctx.orgId()).param("c", convId).param("t", content.strip()).update();
        var job = jobs.enqueue(ctx.orgId(), CHAT_ANSWER, Map.of("conversationId", conv.id(), "messageId", assistantMsg.toString(),
                "question", content.strip(), "model", model == null ? "fast" : model, "userId", ctx.userId().toString()), ctx.userId(), null, 100, 2);
        db.sql("insert into messages(id, org_id, conversation_id, role, content, status, job_id, model) values (:id, :o, :c, 'ASSISTANT', '', 'PENDING', :j, :m)")
                .param("id", assistantMsg).param("o", ctx.orgId()).param("c", convId).param("j", job).param("m", model == null ? "fast" : model).update();
        db.sql("update conversations set updated_at = now(), title = case when title = '新對話' then :t else title end where id = :c")
                .param("t", content.strip().length() > 60 ? content.strip().substring(0, 60) : content.strip()).param("c", convId).update();
        var detail = detail(ctx, conv.id());
        Message u = detail.messages().stream().filter(m -> m.id().equals(userMsg.toString())).findFirst().orElseThrow();
        Message a = detail.messages().stream().filter(m -> m.id().equals(assistantMsg.toString())).findFirst().orElseThrow();
        return new Posted(u, a, JobQueue.view(jobs.byId(job).orElseThrow()));
    }

    public Detail detail(OrgContext ctx, String idText) {
        var conv = own(ctx, idText);
        var convId = UUID.fromString(conv.id());
        var readable = access.knowledgeBaseAccess(ctx).keySet();
        // Current authorisation state of every cited document, computed once.
        var docState = new HashMap<UUID, Object[]>(); // doc -> {readable, currentVersionNo}
        db.sql("""
                select distinct d.id, d.kb_id, d.deleted_at is not null as deleted, d.expires_at, v.version_no as current_no
                from message_citations mc join documents d on d.id = mc.document_id and d.org_id = mc.org_id
                left join document_versions v on v.id = d.current_version_id
                where mc.org_id = :o and mc.message_id in (select id from messages where conversation_id = :c)""")
                .param("o", ctx.orgId()).param("c", convId).query((rs, n) -> {
                    boolean ok = !rs.getBoolean("deleted") && readable.contains(Db.uuid(rs, "kb_id")) && !dev.knowledge.platform.rag.PlatformRag.expired(Db.instant(rs, "expires_at"));
                    docState.put(Db.uuid(rs, "id"), new Object[]{ok, Db.intOrNull(rs, "current_no")});
                    return null;
                }).list();
        var citations = new HashMap<UUID, List<Citation>>();
        db.sql("select * from message_citations where org_id = :o and message_id in (select id from messages where conversation_id = :c) order by message_id, ordinal")
                .param("o", ctx.orgId()).param("c", convId).query((rs, n) -> {
                    var doc = Db.uuid(rs, "document_id");
                    var st = docState.getOrDefault(doc, new Object[]{false, null});
                    boolean ok = (Boolean) st[0];
                    int versionNo = rs.getInt("version_no");
                    String state = !ok ? "UNAVAILABLE" : Objects.equals(st[1], versionNo) ? "CURRENT" : "SUPERSEDED";
                    citations.computeIfAbsent(Db.uuid(rs, "message_id"), k -> new ArrayList<>()).add(new Citation(rs.getInt("ordinal"), doc.toString(), versionNo,
                            rs.getString("chunk_id"), ok ? rs.getString("title") : "（無法存取的文件）", ok ? rs.getString("quote") : "",
                            ok ? json.map(rs.getString("locator")) : Map.of(), state));
                    return null;
                }).list();
        var messages = db.sql("select * from messages where org_id = :o and conversation_id = :c order by created_at, role desc")
                .param("o", ctx.orgId()).param("c", convId).query((rs, n) -> {
                    var id = Db.uuid(rs, "id");
                    var cites = citations.getOrDefault(id, List.of());
                    String content = rs.getString("content");
                    // Hide derived answer text when any of its evidence is no longer accessible.
                    if (cites.stream().anyMatch(c -> c.state().equals("UNAVAILABLE"))) content = HIDDEN;
                    return new Message(id.toString(), rs.getString("role"), content, rs.getString("status"), rs.getString("model"), Db.iso(rs, "created_at"),
                            rs.getString("job_id"), cites, json.map(rs.getString("meta")));
                }).list();
        return new Detail(conv, messages);
    }
    public static Instant now() { return Instant.now(); }
}
