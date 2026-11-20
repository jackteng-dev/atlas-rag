package dev.knowledge.platform.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.knowledge.ApiException;
import dev.knowledge.LocalJsonHttp;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Qdrant REST adapter. Payload holds ids only (no document text). Every query is filtered by org_id AND version ids. */
public final class QdrantVectorStore implements VectorStore {
    private final LocalJsonHttp http;
    private final String collection;
    private volatile Integer dimensions;

    public QdrantVectorStore(String origin, String collection, ObjectMapper mapper) {
        if (!collection.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid Qdrant collection");
        this.collection = "/collections/" + collection;
        this.http = new LocalJsonHttp(origin, mapper);
    }

    private void ensureCollection(int size) {
        if (dimensions != null && dimensions == size) return;
        var current = http.request("GET", collection, null, true);
        if (current.isNull()) {
            http.request("PUT", collection, Map.of("vectors", Map.of("size", size, "distance", "Cosine")));
            for (var field : List.of("org_id", "document_version_id", "document_id", "kb_id"))
                http.request("PUT", collection + "/index?wait=true", Map.of("field_name", field, "field_schema", "keyword"));
        } else if (current.path("result").path("config").path("params").path("vectors").path("size").asInt() != size) {
            throw new ApiException(503, "VECTOR_CONFIGURATION_CHANGED", "Vector dimensions changed; configure a new Qdrant collection.");
        }
        dimensions = size;
    }

    static String pointId(UUID versionId, String chunkId) {
        return UUID.nameUUIDFromBytes((versionId + ":" + chunkId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Override public void upsert(List<Point> points) {
        if (points.isEmpty()) return;
        ensureCollection(points.getFirst().vector().size());
        for (int start = 0; start < points.size(); start += 64) {
            var batch = new ArrayList<Map<String, Object>>();
            for (var p : points.subList(start, Math.min(start + 64, points.size()))) {
                var payload = Map.of("org_id", p.orgId().toString(), "kb_id", p.kbId().toString(), "document_id", p.documentId().toString(),
                        "document_version_id", p.versionId().toString(), "chunk_id", p.chunkId());
                batch.add(Map.of("id", pointId(p.versionId(), p.chunkId()), "vector", p.vector(), "payload", payload));
            }
            http.request("PUT", collection + "/points?wait=true", Map.of("points", batch));
        }
    }

    private void deleteBy(UUID orgId, String key, UUID value) {
        if (http.request("GET", collection, null, true).isNull()) return;
        http.request("POST", collection + "/points/delete?wait=true", Map.of("filter", Map.of("must", List.of(
                Map.of("key", "org_id", "match", Map.of("value", orgId.toString())),
                Map.of("key", key, "match", Map.of("value", value.toString()))))));
    }
    @Override public void deleteVersion(UUID orgId, UUID versionId) { deleteBy(orgId, "document_version_id", versionId); }
    @Override public void deleteDocument(UUID orgId, UUID documentId) { deleteBy(orgId, "document_id", documentId); }

    @Override public List<Match> query(UUID orgId, List<Double> vector, Collection<UUID> versions, int limit) {
        if (versions.isEmpty()) return List.of();
        if (http.request("GET", collection, null, true).isNull()) return List.of();
        var filter = Map.of("must", List.of(
                Map.of("key", "org_id", "match", Map.of("value", orgId.toString())),
                Map.of("key", "document_version_id", "match", Map.of("any", versions.stream().map(UUID::toString).toList()))));
        var response = http.request("POST", collection + "/points/query", Map.of("query", vector, "filter", filter, "limit", limit, "with_payload", true));
        var points = response.path("result").path("points");
        if (!points.isArray()) throw new ApiException(502, "VECTOR_INVALID_OUTPUT", "Qdrant returned an invalid result list.");
        var allowed = new HashSet<>(versions);
        var matches = new ArrayList<Match>();
        for (var point : points) {
            var payload = point.path("payload");
            double score = point.path("score").asDouble(Double.NaN);
            if (!Double.isFinite(score)) throw new ApiException(502, "VECTOR_INVALID_OUTPUT", "Qdrant returned a non-finite score.");
            UUID version;
            try { version = UUID.fromString(payload.path("document_version_id").asText()); } catch (RuntimeException e) { continue; }
            // Defence in depth: re-check the filter result on our side.
            if (!orgId.toString().equals(payload.path("org_id").asText()) || !allowed.contains(version)) continue;
            matches.add(new Match(version, payload.path("chunk_id").asText(), score));
        }
        return matches;
    }

    @Override public String health() {
        try { http.request("GET", "/collections", null, false); return "UP"; } catch (RuntimeException e) { return "DOWN"; }
    }
}
