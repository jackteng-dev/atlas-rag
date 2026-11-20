package dev.knowledge.platform.rag;

import java.util.*;

/**
 * Tenant-scoped derived vector index. Points always carry org_id and document_version_id; queries must pass the
 * explicit list of version ids the caller was authorised for, so a vector can never surface outside that set.
 * The index is never the source of truth: chunk text is read back from PostgreSQL by (version, chunk) id.
 */
public interface VectorStore {
    record Point(UUID orgId, UUID kbId, UUID documentId, UUID versionId, String chunkId, List<Double> vector) {}
    record Match(UUID versionId, String chunkId, double score) {}
    void upsert(List<Point> points);
    void deleteVersion(UUID orgId, UUID versionId);
    void deleteDocument(UUID orgId, UUID documentId);
    List<Match> query(UUID orgId, List<Double> vector, Collection<UUID> authorizedVersionIds, int limit);
    String health();
}
