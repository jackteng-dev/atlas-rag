package dev.knowledge.platform.ingestion;

import dev.knowledge.platform.jobs.*;
import dev.knowledge.platform.knowledge.DocumentService;
import dev.knowledge.platform.rag.VectorStore;
import dev.knowledge.platform.storage.ObjectStore;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Physically removes a deleted document's vectors, chunk text and original files. The document row and version
 * metadata stay (without content) so audit trails and old citations can report "UNAVAILABLE" instead of dangling.
 */
@Component @Profile("platform")
public class PurgeHandler implements JobHandler {
    private final JdbcClient db;
    private final VectorStore vectors;
    private final ObjectStore objects;
    public PurgeHandler(JdbcClient db, VectorStore vectors, ObjectStore objects) { this.db = db; this.vectors = vectors; this.objects = objects; }
    @Override public String type() { return DocumentService.PURGE; }

    @Override public Object handle(JobQueue.Job job, JobContext ctx) {
        UUID doc = UUID.fromString((String) job.payload().get("documentId"));
        boolean deleted = db.sql("select deleted_at is not null from documents where org_id = :o and id = :d").param("o", job.orgId()).param("d", doc)
                .query(Boolean.class).optional().orElse(false);
        if (!deleted) return Map.of("skipped", "document is not deleted");
        vectors.deleteDocument(job.orgId(), doc);
        var keys = db.sql("select object_key from document_versions where org_id = :o and document_id = :d").param("o", job.orgId()).param("d", doc).query(String.class).list();
        for (var key : keys) objects.delete(key);
        int chunks = db.sql("delete from document_chunks where org_id = :o and document_version_id in (select id from document_versions where document_id = :d)")
                .param("o", job.orgId()).param("d", doc).update();
        db.sql("update document_versions set pipeline_state = case when pipeline_state in ('READY','SUPERSEDED') then 'SUPERSEDED' else 'CANCELLED' end where org_id = :o and document_id = :d")
                .param("o", job.orgId()).param("d", doc).update();
        db.sql("update documents set current_version_id = null where org_id = :o and id = :d").param("o", job.orgId()).param("d", doc).update();
        return Map.of("files", keys.size(), "chunks", chunks);
    }
}
