package dev.knowledge.platform.ingestion;

import dev.knowledge.platform.core.*;
import dev.knowledge.platform.jobs.*;
import dev.knowledge.platform.knowledge.DocumentService;
import dev.knowledge.platform.rag.ModelGateway.GatedModels;
import dev.knowledge.platform.rag.VectorStore;
import dev.knowledge.platform.storage.ObjectStore;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RECEIVED → STORED → PARSING → CHUNKING → EMBEDDING → INDEXING → READY. A version only becomes the document's current
 * version in one final transaction after all of its vectors are written, so a half-indexed version is never READY and
 * the previous version stays answerable until then. Re-running the job is safe (chunks and points are replaced).
 */
@Component @Profile("platform")
public class IngestionHandler implements JobHandler {
    private final JdbcClient db;
    private final ObjectStore objects;
    private final GatedModels models;
    private final VectorStore vectors;
    private final TransactionTemplate tx;
    private final Json json;
    private final DocumentParser parser;

    public IngestionHandler(JdbcClient db, ObjectStore objects, GatedModels models, VectorStore vectors, TransactionTemplate tx, Json json,
                            @Value("${atlas.rag.chunk-max-chars:800}") int maxChars) {
        this.db = db; this.objects = objects; this.models = models; this.vectors = vectors; this.tx = tx; this.json = json;
        this.parser = new DocumentParser(maxChars);
    }
    @Override public String type() { return DocumentService.INGEST; }

    private record VersionRow(UUID id, UUID orgId, UUID documentId, UUID kbId, int versionNo, String sha256, String objectKey, String filename,
                              String state, String legacyId, boolean documentDeleted) {}

    @Override public Object handle(JobQueue.Job job, JobContext ctx) throws Exception {
        UUID versionId = UUID.fromString((String) job.payload().get("versionId"));
        boolean reindex = Boolean.TRUE.equals(job.payload().get("reindex"));
        var v = db.sql("""
                select v.id, v.org_id, v.document_id, d.kb_id, v.version_no, v.sha256, v.object_key, v.filename, v.pipeline_state, d.legacy_id,
                       d.deleted_at is not null as deleted
                from document_versions v join documents d on d.id = v.document_id and d.org_id = v.org_id
                where v.id = :id and v.org_id = :o""").param("id", versionId).param("o", job.orgId())
                .query((rs, n) -> new VersionRow(Db.uuid(rs, "id"), Db.uuid(rs, "org_id"), Db.uuid(rs, "document_id"), Db.uuid(rs, "kb_id"),
                        rs.getInt("version_no"), rs.getString("sha256"), rs.getString("object_key"), rs.getString("filename"),
                        rs.getString("pipeline_state"), rs.getString("legacy_id"), rs.getBoolean("deleted"))).optional()
                .orElseThrow(() -> new JobFailure("Version no longer exists", false));
        if (v.documentDeleted()) return Map.of("skipped", "document deleted");
        if (!reindex && Set.of("CANCELLED", "SUPERSEDED", "READY").contains(v.state())) return Map.of("skipped", v.state());
        if (reindex && !"READY".equals(v.state())) return Map.of("skipped", "not current");

        List<DocumentParser.ParsedChunk> chunks;
        if (reindex) {
            chunks = db.sql("select chunk_id, heading, content, locator from document_chunks where document_version_id = :v order by ordinal").param("v", v.id())
                    .query((rs, n) -> new DocumentParser.ParsedChunk(rs.getString("chunk_id"), rs.getString("heading"), rs.getString("content"), json.map(rs.getString("locator")))).list();
        } else {
            state(v.id(), "PARSING", null, null);
            byte[] bytes;
            try (var in = objects.get(v.objectKey())) { bytes = in.readAllBytes(); }
            if (!DocumentService.sha256(bytes).equals(v.sha256())) throw new JobFailure("STORED_FILE_MISMATCH: stored bytes do not match the recorded SHA-256", false);
            var kind = DocumentParser.detect(v.filename(), bytes);
            String prefix = v.legacyId() != null ? v.legacyId() : v.documentId().toString();
            DocumentParser.Parsed parsed;
            try { parsed = parser.parse(kind, prefix, bytes); }
            catch (dev.knowledge.ApiException e) { throw new JobFailure(e.code + ": " + e.getMessage(), false); }
            ctx.checkpoint();
            if (parsed.needsOcr()) {
                tx.executeWithoutResult(s -> {
                    db.sql("update document_versions set pipeline_state = 'NEEDS_OCR', page_count = :p, parser_version = :pv, error_code = 'NEEDS_OCR', error_message = 'No text layer found; OCR is not enabled on this server.' where id = :v")
                            .param("p", parsed.pageCount()).param("pv", DocumentParser.VERSION).param("v", v.id()).update();
                    db.sql("update documents set status = 'NEEDS_OCR', updated_at = now() where id = :d and current_version_id is null").param("d", v.documentId()).update();
                });
                return Map.of("state", "NEEDS_OCR", "pages", parsed.pageCount());
            }
            if (parsed.chunks().isEmpty()) throw new JobFailure("EMPTY_DOCUMENT: no extractable text", false);
            chunks = parsed.chunks();
            state(v.id(), "CHUNKING", null, null);
            tx.executeWithoutResult(s -> {
                db.sql("delete from document_chunks where document_version_id = :v").param("v", v.id()).update();
                int ordinal = 0;
                for (var c : parsed.chunks()) {
                    db.sql("""
                            insert into document_chunks(org_id, document_version_id, chunk_id, ordinal, heading, content, content_hash, locator)
                            values (:o, :v, :c, :n, :h, :t, :hash, cast(:l as jsonb))""")
                            .param("o", v.orgId()).param("v", v.id()).param("c", c.chunkId()).param("n", ordinal++)
                            .param("h", c.heading().length() > 500 ? c.heading().substring(0, 500) : c.heading()).param("t", c.content())
                            .param("hash", dev.knowledge.MarkdownChunker.hash(c.content())).param("l", json.write(c.locator())).update();
                }
                db.sql("update document_versions set chunk_count = :n, page_count = :p, parser_version = :pv where id = :v")
                        .param("n", parsed.chunks().size()).param("p", parsed.pageCount()).param("pv", DocumentParser.VERSION).param("v", v.id()).update();
            });
        }

        if (!reindex) state(v.id(), "EMBEDDING", null, null);
        var points = new ArrayList<VectorStore.Point>();
        for (int start = 0; start < chunks.size(); start += 16) {
            ctx.checkpoint();
            var batch = chunks.subList(start, Math.min(start + 16, chunks.size()));
            var embedded = models.embed(batch.stream().map(c -> c.heading() + "\n" + c.content()).toList());
            if (embedded.size() != batch.size()) throw new JobFailure("MODEL_INVALID_OUTPUT: embedding count changed", true);
            for (int i = 0; i < batch.size(); i++)
                points.add(new VectorStore.Point(v.orgId(), v.kbId(), v.documentId(), v.id(), batch.get(i).chunkId(), embedded.get(i)));
        }
        if (!reindex) state(v.id(), "INDEXING", null, null);
        ctx.checkpoint();
        vectors.upsert(points);
        if (reindex) return Map.of("state", "READY", "chunks", chunks.size(), "reindexed", true);

        // Atomic switch: the newest successfully indexed version becomes current; older ones are superseded.
        var previous = tx.execute(s -> {
            var doc = db.sql("select current_version_id, deleted_at is not null as deleted from documents where id = :d for update").param("d", v.documentId())
                    .query((rs, n) -> new Object[]{Db.uuid(rs, "current_version_id"), rs.getBoolean("deleted")}).single();
            if ((Boolean) doc[1]) return Optional.<UUID>empty();
            String latestState = db.sql("select pipeline_state from document_versions where id = :v").param("v", v.id()).query(String.class).single();
            if ("CANCELLED".equals(latestState)) return Optional.<UUID>empty();
            UUID current = (UUID) doc[0];
            if (current != null) {
                int currentNo = db.sql("select version_no from document_versions where id = :c").param("c", current).query(Integer.class).single();
                if (currentNo > v.versionNo()) {
                    db.sql("update document_versions set pipeline_state = 'SUPERSEDED' where id = :v").param("v", v.id()).update();
                    return Optional.of(v.id());
                }
                db.sql("update document_versions set pipeline_state = 'SUPERSEDED' where id = :c").param("c", current).update();
            }
            db.sql("update document_versions set pipeline_state = 'READY', ready_at = now(), error_code = null, error_message = null where id = :v").param("v", v.id()).update();
            db.sql("update documents set current_version_id = :v, status = 'READY', updated_at = now() where id = :d").param("v", v.id()).param("d", v.documentId()).update();
            return Optional.ofNullable(current);
        });
        // Superseded vectors are removed after the switch; queries are already restricted to current version ids.
        previous.ifPresent(old -> vectors.deleteVersion(v.orgId(), old));
        if (Boolean.TRUE.equals(db.sql("select deleted_at is not null from documents where id = :d").param("d", v.documentId()).query(Boolean.class).single()))
            vectors.deleteVersion(v.orgId(), v.id());
        return Map.of("state", "READY", "chunks", chunks.size());
    }

    private void state(UUID version, String state, String code, String message) {
        db.sql("update document_versions set pipeline_state = :s, error_code = :c, error_message = :m where id = :v and pipeline_state not in ('CANCELLED','SUPERSEDED')")
                .param("s", state).param("c", code).param("m", message).param("v", version).update();
    }

    @Override public void onFinalFailure(JobQueue.Job job, String error) {
        UUID versionId = UUID.fromString((String) job.payload().get("versionId"));
        if (Boolean.TRUE.equals(job.payload().get("reindex"))) return; // current version stays READY with its old vectors
        String code = error != null && error.contains(":") ? error.substring(0, error.indexOf(':')) : "INGESTION_FAILED";
        String message = error != null && error.contains(":") ? error.substring(error.indexOf(':') + 1).strip() : error;
        tx.executeWithoutResult(s -> {
            db.sql("update document_versions set pipeline_state = case when pipeline_state = 'CANCELLED' then 'CANCELLED' else 'FAILED' end, error_code = :c, error_message = :m where id = :v")
                    .param("c", code.length() > 64 ? "INGESTION_FAILED" : code).param("m", message == null ? null : message.length() > 480 ? message.substring(0, 480) : message)
                    .param("v", versionId).update();
            db.sql("update documents d set status = case when d.current_version_id is null then (select case when pipeline_state = 'CANCELLED' then 'CANCELLED' else 'FAILED' end from document_versions where id = :v) else d.status end, updated_at = now() where d.id = (select document_id from document_versions where id = :v)")
                    .param("v", versionId).update();
        });
        try { vectors.deleteVersion(job.orgId(), versionId); } catch (RuntimeException ignored) { /* points are filtered out anyway */ }
    }
}
