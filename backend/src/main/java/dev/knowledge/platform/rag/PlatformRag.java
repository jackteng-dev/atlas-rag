package dev.knowledge.platform.rag;

import dev.knowledge.*;
import dev.knowledge.Models.*;
import dev.knowledge.platform.core.*;
import dev.knowledge.platform.rag.ModelGateway.GatedModels;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Tenant-aware hybrid RAG, preserving the verified Atlas pipeline: BM25 (lexical-v1) + BGE-M3 dense retrieval, RRF
 * (k=60), BGE reranker with absolute/relative score gates, and grounded generation whose citations must be a subset of
 * the reranked, authorised evidence. The candidate set is computed from PostgreSQL first (org, readable KBs, READY,
 * current version, not expired, not deleted) and every vector hit is re-resolved against it.
 */
@Service @Profile("platform")
public class PlatformRag {
    public record Evidence(UUID documentId, UUID versionId, int versionNo, String chunkId, String title, String heading, String content,
                           Map<String, Object> locator, double score) {}
    public record Stage(String name, String status, long elapsedMs, int inputCount, int outputCount) {}
    public record Result(String status, List<GeneratedStatement> statements, List<Evidence> evidence, List<Stage> stages,
                         Integer inputTokens, Integer outputTokens, String model) {}
    private record Candidate(UUID documentId, UUID versionId, int versionNo, String title) {}

    private final JdbcClient db;
    private final GatedModels models;
    private final VectorStore vectors;
    private final Json json;
    private final KeywordRetriever keywords = new KeywordRetriever();
    private final int candidateK, defaultTopK;
    private final double minScore, maxGap;
    static final int MAX_LEXICAL_CHUNKS = 50_000;

    public PlatformRag(JdbcClient db, GatedModels models, VectorStore vectors, Json json,
                       @Value("${atlas.rag.candidate-k:10}") int candidateK, @Value("${atlas.rag.top-k:5}") int topK,
                       @Value("${atlas.rag.min-rerank-score:-8.0}") double minScore, @Value("${atlas.rag.max-rerank-gap:5.0}") double maxGap) {
        if (candidateK < 5 || candidateK > 50 || topK < 1 || topK > 8 || !Double.isFinite(minScore) || maxGap < 0 || maxGap > 20)
            throw new IllegalArgumentException("Invalid RAG configuration");
        this.db = db; this.models = models; this.vectors = vectors; this.json = json;
        this.candidateK = candidateK; this.defaultTopK = topK; this.minScore = minScore; this.maxGap = maxGap;
    }

    public Map<String, Object> configuration() {
        return Map.of("retrieval", "HYBRID_BM25_DENSE", "fusion", "RRF_K60", "embedding", "BGE_M3", "reranker", "BGE_RERANKER_V2_M3",
                "candidateK", candidateK, "topK", defaultTopK, "rerankerMinLogit", minScore, "rerankerMaxLogitGap", maxGap, "paidApiCalls", 0);
    }

    /** kbIds must already be the caller's authorised, readable knowledge bases for this org. */
    public Result answer(UUID orgId, Collection<UUID> kbIds, String question, boolean quality) {
        return run(orgId, kbIds, question, question, quality, true);
    }
    public Result retrieveOnly(UUID orgId, Collection<UUID> kbIds, String question) {
        return run(orgId, kbIds, question, question, false, false);
    }
    public Result answer(UUID orgId, Collection<UUID> kbIds, String retrievalQuery, String generationQuestion, boolean quality) {
        return run(orgId, kbIds, retrievalQuery, generationQuestion, quality, true);
    }

    private Result run(UUID orgId, Collection<UUID> kbIds, String retrievalQuery, String generationQuestion, boolean quality, boolean generate) {
        if (quality && !models.hasQuality()) throw new ApiException(400, "MODEL_UNAVAILABLE", "The quality model is not configured on this server.");
        var stages = new ArrayList<Stage>();
        long t = System.nanoTime();
        var candidates = kbIds.isEmpty() ? List.<Candidate>of() : db.sql("""
                select d.id, d.title, d.current_version_id, v.version_no from documents d
                join document_versions v on v.id = d.current_version_id and v.org_id = d.org_id
                join knowledge_bases kb on kb.id = d.kb_id and kb.org_id = d.org_id and kb.deleted_at is null
                where d.org_id = :o and d.kb_id in (:kbs) and d.deleted_at is null and d.status = 'READY' and v.pipeline_state = 'READY'
                  and (d.expires_at is null or d.expires_at > now())""").param("o", orgId).param("kbs", kbIds)
                .query((rs, n) -> new Candidate(Db.uuid(rs, "id"), Db.uuid(rs, "current_version_id"), rs.getInt("version_no"), rs.getString("title"))).list();
        stages.add(stage("authorization_and_expiry", t, kbIds.size(), candidates.size()));
        if (candidates.isEmpty()) return new Result("NO_EVIDENCE", List.of(), List.of(), stages, null, null, null);

        var byVersion = new HashMap<UUID, Candidate>();
        candidates.forEach(c -> byVersion.put(c.versionId(), c));
        // Lexical index over the authorised, current chunks (bounded; see docs for the scale limit).
        t = System.nanoTime();
        var chunkRows = db.sql("select document_version_id, chunk_id, heading, content, locator from document_chunks where org_id = :o and document_version_id in (:v) order by document_version_id, ordinal limit :l")
                .param("o", orgId).param("v", byVersion.keySet()).param("l", MAX_LEXICAL_CHUNKS)
                .query((rs, n) -> {
                    var c = byVersion.get(Db.uuid(rs, "document_version_id"));
                    return new Evidence(c.documentId(), c.versionId(), c.versionNo(), rs.getString("chunk_id"), c.title(), rs.getString("heading"),
                            rs.getString("content"), json.map(rs.getString("locator")), 0);
                }).list();
        var evidenceByKey = new HashMap<String, Evidence>();
        var docs = new LinkedHashMap<UUID, List<Chunk>>();
        for (var e : chunkRows) {
            evidenceByKey.put(e.versionId() + "|" + e.chunkId(), e);
            docs.computeIfAbsent(e.versionId(), k -> new ArrayList<>()).add(new Chunk(e.versionId() + "|" + e.chunkId(), e.heading(), e.content(), 0, 0));
        }
        var legacyDocs = new ArrayList<Document>();
        docs.forEach((version, chunks) -> legacyDocs.add(new Document(version.toString(), byVersion.get(version).title(), byVersion.get(version).versionNo(), "", "", "", chunks)));
        var sparse = keywords.retrieve(new StoreState(1, 0, Map.of(), legacyDocs), retrievalQuery, null, candidateK).hits().stream()
                .map(h -> evidenceByKey.get(h.chunk().id())).filter(Objects::nonNull).toList();
        stages.add(stage("bm25", t, chunkRows.size(), sparse.size()));

        t = System.nanoTime();
        var queryVector = models.embed(List.of(retrievalQuery)).getFirst();
        var dense = new ArrayList<Evidence>();
        for (var m : vectors.query(orgId, queryVector, byVersion.keySet(), candidateK)) {
            var e = evidenceByKey.get(m.versionId() + "|" + m.chunkId());
            if (e != null) dense.add(e); // stale / foreign points are dropped here
        }
        stages.add(stage("dense_retrieval", t, byVersion.size(), dense.size()));

        t = System.nanoTime();
        var fused = fuse(sparse, dense);
        stages.add(stage("rrf_fusion", t, sparse.size() + dense.size(), fused.size()));
        if (fused.isEmpty()) return new Result("NO_EVIDENCE", List.of(), List.of(), stages, null, null, null);

        t = System.nanoTime();
        var ranked = models.rerank(retrievalQuery, fused.stream().map(e -> e.title() + "\n" + e.heading() + "\n" + e.content()).toList());
        double best = ranked.stream().mapToDouble(RagModels.Ranked::score).max().orElse(Double.NEGATIVE_INFINITY);
        var chosen = new ArrayList<Evidence>(); var seen = new HashSet<Integer>();
        for (var r : ranked) {
            if (r.index() < 0 || r.index() >= fused.size() || !seen.add(r.index()) || !Double.isFinite(r.score()))
                throw new ApiException(502, "MODEL_INVALID_OUTPUT", "Reranker selected an invalid candidate.");
            if (r.score() >= minScore && r.score() >= best - maxGap && chosen.size() < defaultTopK) {
                var e = fused.get(r.index());
                chosen.add(new Evidence(e.documentId(), e.versionId(), e.versionNo(), e.chunkId(), e.title(), e.heading(), e.content(), e.locator(), r.score()));
            }
        }
        stages.add(stage("reranker", t, fused.size(), chosen.size()));
        if (chosen.isEmpty()) {
            stages.add(new Stage("generation", "SKIPPED_NO_EVIDENCE", 0, 0, 0));
            return new Result("NO_EVIDENCE", List.of(), List.of(), stages, null, null, null);
        }
        if (!generate) return new Result("RETRIEVED", List.of(), chosen, stages, null, null, null);

        t = System.nanoTime();
        var sources = chosen.stream().map(e -> new Source(e.versionId() + "|" + e.chunkId(), e.documentId().toString(), e.title(), e.versionNo(), e.heading(),
                e.content(), 0, 0, e.score(), "")).toList();
        var output = models.generate(generationQuestion, sources, quality);
        var permitted = new HashSet<>(sources.stream().map(Source::chunkId).toList());
        if (output.statements() == null || output.statements().size() > 3) throw new ApiException(502, "INVALID_CITATION", "Invalid generated answer.");
        var statements = new ArrayList<GeneratedStatement>();
        var cited = new LinkedHashSet<String>();
        for (var s : output.statements()) {
            if (s.text() == null || s.text().isBlank() || s.chunkIds() == null || s.chunkIds().isEmpty() || !permitted.containsAll(s.chunkIds()))
                throw new ApiException(502, "INVALID_CITATION", "Generated text cited evidence outside the authorised context.");
            statements.add(s); cited.addAll(s.chunkIds());
        }
        stages.add(stage(quality ? "generation_quality" : "generation", t, chosen.size(), statements.size()));
        var citedEvidence = chosen.stream().filter(e -> cited.contains(e.versionId() + "|" + e.chunkId())).toList();
        return new Result(statements.isEmpty() ? "REFUSED" : "ANSWERED", statements, statements.isEmpty() ? List.of() : citedEvidence, stages,
                output.inputTokens(), output.outputTokens(), models.modelName(quality));
    }

    static List<Evidence> fuse(List<Evidence> sparse, List<Evidence> dense) {
        var scores = new HashMap<String, Double>(); var items = new HashMap<String, Evidence>();
        for (var list : List.of(sparse, dense)) {
            var seen = new HashSet<String>();
            for (int i = 0; i < list.size(); i++) {
                var e = list.get(i); var key = e.versionId() + "|" + e.chunkId();
                if (!seen.add(key)) continue;
                items.put(key, e); scores.merge(key, 1.0 / (60 + i + 1), Double::sum);
            }
        }
        return items.entrySet().stream().sorted(Comparator.<Map.Entry<String, Evidence>>comparingDouble(en -> scores.get(en.getKey())).reversed()
                .thenComparing(Map.Entry::getKey)).map(Map.Entry::getValue).toList();
    }
    private static Stage stage(String name, long start, int in, int out) { return new Stage(name, "COMPLETE", (System.nanoTime() - start) / 1_000_000, in, out); }
    public static boolean expired(Instant at) { return at != null && !at.isAfter(Instant.now()); }
}
