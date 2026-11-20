package dev.knowledge.platform.knowledge;

import dev.knowledge.ApiException;
import dev.knowledge.platform.core.*;
import dev.knowledge.platform.ingestion.DocumentParser;
import dev.knowledge.platform.jobs.JobQueue;
import dev.knowledge.platform.ops.AuditService;
import dev.knowledge.platform.storage.ObjectStore;
import dev.knowledge.platform.tenant.TenantAccess;
import dev.knowledge.platform.tenant.TenantAccess.*;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document lifecycle. Every method resolves the document through the caller's readable knowledge bases first, so a
 * foreign or unreadable document id behaves exactly like a missing one (404).
 */
@Service @Profile("platform")
public class DocumentService {
    public static final String INGEST = "INGEST_VERSION", PURGE = "PURGE_DOCUMENT";
    public record Version(int versionNo, String filename, String mimeType, long sizeBytes, String sha256, String pipelineState, String errorCode,
                          String errorMessage, Integer pageCount, int chunkCount, String parserVersion, String createdAt, String readyAt, String jobId) {}
    public record DocumentItem(String id, String kbId, String title, String status, Integer currentVersion, int latestVersion, String ownerId,
                               List<String> tags, String expiresAt, String updatedAt, List<Version> versions) {}
    public record Chunk(String chunkId, int ordinal, String heading, String content, Map<String, Object> locator, String documentId, int versionNo,
                        String title, boolean current) {}
    /** Internal row with the ids needed for authorisation decisions. */
    public record DocRow(UUID id, UUID kbId, String title, String status, UUID currentVersionId, Integer currentVersionNo, int latestVersion,
                         UUID ownerId, List<String> tags, Instant expiresAt, String updatedAt, String legacyId) {
        public boolean expired() { return expiresAt != null && !expiresAt.isAfter(Instant.now()); }
    }

    private final JdbcClient db;
    private final TenantAccess access;
    private final ObjectStore objects;
    private final JobQueue jobs;
    private final AuditService audit;
    private final Json json;
    public DocumentService(JdbcClient db, TenantAccess access, ObjectStore objects, JobQueue jobs, AuditService audit, Json json) {
        this.db = db; this.access = access; this.objects = objects; this.jobs = jobs; this.audit = audit; this.json = json;
    }

    private static final String DOC_SELECT = """
            select d.id, d.kb_id, d.title, d.status, d.current_version_id, v.version_no as current_no, d.latest_version_no, d.owner_id, d.tags,
                   d.expires_at, d.updated_at, d.legacy_id
            from documents d left join document_versions v on v.id = d.current_version_id and v.org_id = d.org_id
            """;
    private DocRow mapDoc(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DocRow(Db.uuid(rs, "id"), Db.uuid(rs, "kb_id"), rs.getString("title"), rs.getString("status"), Db.uuid(rs, "current_version_id"),
                Db.intOrNull(rs, "current_no"), rs.getInt("latest_version_no"), Db.uuid(rs, "owner_id"), Db.textArray(rs, "tags"),
                Db.instant(rs, "expires_at"), Db.iso(rs, "updated_at"), rs.getString("legacy_id"));
    }

    /** Loads a live document and checks the caller's KB access (404 when not readable). */
    public DocRow require(OrgContext ctx, String docText, KbAccess minimum) {
        UUID id = Db.parse(docText, "document");
        var doc = db.sql(DOC_SELECT + " where d.org_id = :o and d.id = :id and d.deleted_at is null").param("o", ctx.orgId()).param("id", id)
                .query((rs, n) -> mapDoc(rs)).optional().orElseThrow(() -> Errors.notFound("document"));
        var kbAccess = access.knowledgeBaseAccess(ctx).get(doc.kbId());
        if (kbAccess == null) throw Errors.notFound("document");
        if (!kbAccess.atLeast(minimum)) {
            audit.record(ctx.orgId(), ctx.userId(), "document.access", "document", id, AuditService.Result.DENIED, Map.of("required", minimum.name()));
            throw Errors.forbidden("This document requires " + minimum.name() + " access to its knowledge base.");
        }
        return doc;
    }

    public List<DocumentItem> list(OrgContext ctx, String kbText) {
        var readable = access.readableKbIds(ctx);
        if (kbText != null && !kbText.isBlank()) {
            UUID kb = Db.parse(kbText, "knowledge base");
            if (!readable.contains(kb)) throw Errors.notFound("knowledge base");
            readable = Set.of(kb);
        }
        if (readable.isEmpty()) return List.of();
        return db.sql(DOC_SELECT + " where d.org_id = :o and d.kb_id in (:kbs) and d.deleted_at is null order by d.updated_at desc limit 1000")
                .param("o", ctx.orgId()).param("kbs", readable).query((rs, n) -> mapDoc(rs)).list().stream().map(d -> view(d, null)).toList();
    }
    public DocumentItem detail(OrgContext ctx, String docText) {
        var doc = require(ctx, docText, KbAccess.READ);
        return view(doc, versions(ctx.orgId(), doc.id()));
    }
    private DocumentItem view(DocRow d, List<Version> versions) {
        return new DocumentItem(d.id().toString(), d.kbId().toString(), d.title(), d.status(), d.currentVersionNo(), d.latestVersion(), d.ownerId().toString(),
                d.tags(), d.expiresAt() == null ? null : d.expiresAt().toString(), d.updatedAt(), versions);
    }
    public List<Version> versions(UUID orgId, UUID docId) {
        return db.sql("""
                select version_no, filename, mime_type, size_bytes, sha256, pipeline_state, error_code, error_message, page_count, chunk_count,
                       parser_version, created_at, ready_at, job_id
                from document_versions where org_id = :o and document_id = :d order by version_no desc""").param("o", orgId).param("d", docId)
                .query((rs, n) -> new Version(rs.getInt("version_no"), rs.getString("filename"), rs.getString("mime_type"), rs.getLong("size_bytes"),
                        rs.getString("sha256"), rs.getString("pipeline_state"), rs.getString("error_code"), rs.getString("error_message"),
                        Db.intOrNull(rs, "page_count"), rs.getInt("chunk_count"), rs.getString("parser_version"), Db.iso(rs, "created_at"),
                        Db.iso(rs, "ready_at"), rs.getString("job_id"))).list();
    }

    @Transactional
    public DocumentItem upload(OrgContext ctx, String kbText, String filename, byte[] bytes, String title) {
        UUID kb = Db.parse(kbText, "knowledge base");
        access.requireKb(ctx, kb, KbAccess.EDIT);
        var kind = DocumentParser.detect(filename, bytes);
        String docTitle = title == null || title.isBlank() ? stripExtension(filename) : title.strip();
        if (docTitle.length() > 200) throw Errors.bad("INVALID_TITLE", "Title is limited to 200 characters.");
        var id = UUID.randomUUID();
        db.sql("insert into documents(id, org_id, kb_id, title, owner_id, status) values (:id, :o, :kb, :t, :u, 'PROCESSING')")
                .param("id", id).param("o", ctx.orgId()).param("kb", kb).param("t", docTitle).param("u", ctx.userId()).update();
        addVersion(ctx, id, 1, filename, bytes, kind);
        audit.record(ctx.orgId(), ctx.userId(), "document.upload", "document", id, AuditService.Result.SUCCESS, Map.of("kb", kb.toString(), "bytes", bytes.length));
        return detail(ctx, id.toString());
    }

    @Transactional
    public DocumentItem uploadVersion(OrgContext ctx, String docText, String filename, byte[] bytes) {
        var doc = require(ctx, docText, KbAccess.EDIT);
        var kind = DocumentParser.detect(filename, bytes);
        // Serialise concurrent uploads of the same document.
        int next = db.sql("update documents set latest_version_no = latest_version_no + 1, status = case when current_version_id is null then 'PROCESSING' else status end, updated_at = now() where org_id = :o and id = :id returning latest_version_no")
                .param("o", ctx.orgId()).param("id", doc.id()).query(Integer.class).single();
        String sha = sha256(bytes);
        if (doc.currentVersionId() != null) {
            String currentSha = db.sql("select sha256 from document_versions where id = :v").param("v", doc.currentVersionId()).query(String.class).single();
            if (currentSha.equals(sha)) throw Errors.conflict("DUPLICATE_CONTENT", "This file is identical to the current version.");
        }
        addVersion(ctx, doc.id(), next, filename, bytes, kind);
        audit.record(ctx.orgId(), ctx.userId(), "document.version.upload", "document", doc.id(), AuditService.Result.SUCCESS, Map.of("version", next));
        return detail(ctx, docText);
    }

    private void addVersion(OrgContext ctx, UUID docId, int versionNo, String filename, byte[] bytes, DocumentParser.Kind kind) {
        String sha = sha256(bytes);
        String key = ObjectStore.key(ctx.orgId(), docId, versionNo, sha);
        var versionId = UUID.randomUUID();
        db.sql("""
                insert into document_versions(id, org_id, document_id, version_no, sha256, object_key, filename, mime_type, size_bytes, pipeline_state, created_by)
                values (:id, :o, :d, :n, :sha, :key, :f, :m, :s, 'RECEIVED', :u)""")
                .param("id", versionId).param("o", ctx.orgId()).param("d", docId).param("n", versionNo).param("sha", sha).param("key", key)
                .param("f", filename).param("m", DocumentParser.mimeType(kind)).param("s", (long) bytes.length).param("u", ctx.userId()).update();
        db.sql("update documents set latest_version_no = greatest(latest_version_no, :n) where id = :d").param("n", versionNo).param("d", docId).update();
        objects.put(key, bytes, DocumentParser.mimeType(kind));
        db.sql("update document_versions set pipeline_state = 'STORED' where id = :id").param("id", versionId).update();
        var job = jobs.enqueue(ctx.orgId(), INGEST, Map.of("versionId", versionId.toString(), "documentId", docId.toString()), ctx.userId(),
                "version:" + versionId, 50, 4);
        db.sql("update document_versions set job_id = :j where id = :id").param("j", job).param("id", versionId).update();
    }

    public DocumentItem patch(OrgContext ctx, String docText, String title, List<String> tags, String expiresAt) {
        var doc = require(ctx, docText, KbAccess.EDIT);
        String t = title == null ? doc.title() : title.strip();
        if (t.isBlank() || t.length() > 200) throw Errors.bad("INVALID_TITLE", "Title must be 1–200 characters.");
        var tagList = tags == null ? doc.tags() : tags;
        if (tagList.size() > 20 || tagList.stream().anyMatch(x -> x == null || x.isBlank() || x.length() > 40)) throw Errors.bad("INVALID_TAGS", "Up to 20 tags of 1–40 characters.");
        Instant expires;
        if (expiresAt == null) expires = doc.expiresAt();
        else if (expiresAt.isBlank()) expires = null;
        else try { expires = Instant.parse(expiresAt); } catch (RuntimeException e) { throw Errors.bad("INVALID_EXPIRY", "expiresAt must be an ISO-8601 instant."); }
        db.sql("update documents set title = :t, tags = :tags, expires_at = :x, updated_at = now() where org_id = :o and id = :id")
                .param("t", t).param("tags", tagList.toArray(String[]::new)).param("x", Db.ts(expires)).param("o", ctx.orgId()).param("id", doc.id()).update();
        audit.record(ctx.orgId(), ctx.userId(), "document.update", "document", doc.id());
        return detail(ctx, docText);
    }

    /** Marks the document deleted immediately (unreadable everywhere) and lets a job purge vectors, chunks and files. */
    @Transactional
    public void delete(OrgContext ctx, String docText) {
        var doc = require(ctx, docText, KbAccess.EDIT);
        db.sql("update documents set deleted_at = now(), status = 'DELETING', updated_at = now() where org_id = :o and id = :id")
                .param("o", ctx.orgId()).param("id", doc.id()).update();
        enqueuePurge(ctx.orgId(), doc.id(), ctx.userId());
        audit.record(ctx.orgId(), ctx.userId(), "document.delete", "document", doc.id());
    }
    public void enqueuePurge(UUID orgId, UUID docId, UUID actor) {
        jobs.enqueue(orgId, PURGE, Map.of("documentId", docId.toString()), actor, "purge:" + docId, 80, 6);
    }

    public DocumentItem retry(OrgContext ctx, String docText, int versionNo) {
        var doc = require(ctx, docText, KbAccess.EDIT);
        var version = versionId(ctx.orgId(), doc.id(), versionNo);
        int n = db.sql("update document_versions set pipeline_state = 'STORED', error_code = null, error_message = null where id = :v and pipeline_state in ('FAILED','NEEDS_OCR','CANCELLED')")
                .param("v", version).update();
        if (n == 0) throw Errors.conflict("NOT_RETRYABLE", "Only failed, cancelled or OCR-pending versions can be retried.");
        var job = jobs.enqueue(ctx.orgId(), INGEST, Map.of("versionId", version.toString(), "documentId", doc.id().toString()), ctx.userId(), "version:" + version, 50, 4);
        db.sql("update document_versions set job_id = :j where id = :v").param("j", job).param("v", version).update();
        if (doc.currentVersionId() == null) db.sql("update documents set status = 'PROCESSING' where id = :d").param("d", doc.id()).update();
        audit.record(ctx.orgId(), ctx.userId(), "document.version.retry", "document", doc.id(), AuditService.Result.SUCCESS, Map.of("version", versionNo));
        return detail(ctx, docText);
    }
    public DocumentItem cancel(OrgContext ctx, String docText, int versionNo) {
        var doc = require(ctx, docText, KbAccess.EDIT);
        var version = versionId(ctx.orgId(), doc.id(), versionNo);
        var jobId = db.sql("select job_id from document_versions where id = :v").param("v", version).query(UUID.class).optional();
        int n = db.sql("update document_versions set pipeline_state = 'CANCELLED' where id = :v and pipeline_state not in ('READY','SUPERSEDED','FAILED','CANCELLED')")
                .param("v", version).update();
        if (n == 0) throw Errors.conflict("NOT_CANCELLABLE", "This version is not being processed.");
        jobId.ifPresent(j -> jobs.cancel(ctx.orgId(), j));
        if (doc.currentVersionId() == null) db.sql("update documents set status = 'CANCELLED' where id = :d").param("d", doc.id()).update();
        audit.record(ctx.orgId(), ctx.userId(), "document.version.cancel", "document", doc.id(), AuditService.Result.SUCCESS, Map.of("version", versionNo));
        return detail(ctx, docText);
    }
    public DocumentItem reindex(OrgContext ctx, String docText) {
        var doc = require(ctx, docText, KbAccess.EDIT);
        if (doc.currentVersionId() == null) throw Errors.conflict("NO_CURRENT_VERSION", "There is no ready version to re-index.");
        jobs.enqueue(ctx.orgId(), INGEST, Map.of("versionId", doc.currentVersionId().toString(), "documentId", doc.id().toString(), "reindex", true),
                ctx.userId(), "reindex:" + doc.currentVersionId(), 60, 4);
        audit.record(ctx.orgId(), ctx.userId(), "document.reindex", "document", doc.id());
        return detail(ctx, docText);
    }
    UUID versionId(UUID orgId, UUID docId, int versionNo) {
        return db.sql("select id from document_versions where org_id = :o and document_id = :d and version_no = :n").param("o", orgId).param("d", docId).param("n", versionNo)
                .query(UUID.class).optional().orElseThrow(() -> Errors.notFound("version"));
    }

    public record FileDownload(String filename, String mimeType, long size, InputStream stream) {}
    public FileDownload file(OrgContext ctx, String docText, int versionNo) {
        var doc = require(ctx, docText, KbAccess.READ);
        var row = db.sql("select object_key, filename, mime_type, size_bytes, pipeline_state from document_versions where org_id = :o and document_id = :d and version_no = :n")
                .param("o", ctx.orgId()).param("d", doc.id()).param("n", versionNo)
                .query((rs, n) -> Map.of("key", rs.getString("object_key"), "name", rs.getString("filename"), "mime", rs.getString("mime_type"), "size", rs.getLong("size_bytes")))
                .optional().orElseThrow(() -> Errors.notFound("version"));
        audit.record(ctx.orgId(), ctx.userId(), "document.download", "document", doc.id(), AuditService.Result.SUCCESS, Map.of("version", versionNo));
        return new FileDownload((String) row.get("name"), (String) row.get("mime"), (Long) row.get("size"), objects.get((String) row.get("key")));
    }

    public List<Chunk> chunks(OrgContext ctx, String docText, int versionNo, int limit) {
        var doc = require(ctx, docText, KbAccess.READ);
        if (doc.expired() && !access.knowledgeBaseAccess(ctx).get(doc.kbId()).atLeast(KbAccess.EDIT)) throw Errors.notFound("document");
        var version = versionId(ctx.orgId(), doc.id(), versionNo);
        return db.sql("select chunk_id, ordinal, heading, content, locator from document_chunks where org_id = :o and document_version_id = :v order by ordinal limit :l")
                .param("o", ctx.orgId()).param("v", version).param("l", Math.min(Math.max(limit, 1), 2000))
                .query((rs, n) -> new Chunk(rs.getString("chunk_id"), rs.getInt("ordinal"), rs.getString("heading"), rs.getString("content"),
                        json.map(rs.getString("locator")), doc.id().toString(), versionNo, doc.title(), version.equals(doc.currentVersionId()))).list();
    }

    /** Canonical citation source: only the current, unexpired version's text is returned; older versions answer 409 without text. */
    public Chunk chunk(OrgContext ctx, String docText, int versionNo, String chunkId) {
        var doc = require(ctx, docText, KbAccess.READ);
        if (doc.expired()) throw Errors.notFound("document");
        if (doc.currentVersionNo() == null || doc.currentVersionNo() != versionNo) {
            versionId(ctx.orgId(), doc.id(), versionNo); // 404 for versions that never existed
            throw new ApiException(409, "VERSION_SUPERSEDED", "This citation refers to version " + versionNo + "; the current version is "
                    + (doc.currentVersionNo() == null ? "not available" : doc.currentVersionNo()) + ".");
        }
        return db.sql("select chunk_id, ordinal, heading, content, locator from document_chunks where org_id = :o and document_version_id = :v and chunk_id = :c")
                .param("o", ctx.orgId()).param("v", doc.currentVersionId()).param("c", chunkId)
                .query((rs, n) -> new Chunk(rs.getString("chunk_id"), rs.getInt("ordinal"), rs.getString("heading"), rs.getString("content"),
                        json.map(rs.getString("locator")), doc.id().toString(), versionNo, doc.title(), true))
                .optional().orElseThrow(() -> Errors.notFound("chunk"));
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String stripExtension(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
}
