package dev.knowledge.platform;

import dev.knowledge.KeywordRetriever;
import dev.knowledge.Models.*;
import dev.knowledge.platform.rag.ModelGateway;
import dev.knowledge.platform.rag.VectorStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Deterministic stand-ins for the local models and Qdrant. Embeddings are hashed token counts, reranking is token
 * overlap and "generation" quotes the best source. They exercise the platform's wiring, authorisation and citation
 * checks — they say nothing about answer quality.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeModelsConfiguration {
    static final AtomicInteger GENERATIONS = new AtomicInteger();
    static volatile boolean failEmbedding = false;
    static final Map<String, VectorStore.Point> POINTS = new ConcurrentHashMap<>();
    static void reset() { GENERATIONS.set(0); failEmbedding = false; }

    static List<Double> vector(String text) {
        var v = new double[64];
        for (var token : KeywordRetriever.tokens(text)) v[Math.floorMod(token.hashCode(), 64)] += 1;
        double norm = 0; for (double d : v) norm += d * d;
        var list = new ArrayList<Double>();
        for (double d : v) list.add(norm == 0 ? 0.0 : d / Math.sqrt(norm));
        if (norm == 0) list.set(0, 1.0);
        return list;
    }

    @Bean ModelGateway.TestModels fakeModels() {
        return new ModelGateway.TestModels() {
            @Override public List<List<Double>> embed(List<String> texts) {
                if (failEmbedding) throw new dev.knowledge.ApiException(502, "LOCAL_SERVICE_FAILURE", "fake embedding failure");
                return texts.stream().map(FakeModelsConfiguration::vector).toList();
            }
            @Override public List<Ranked> rerank(String question, List<String> texts) {
                var q = new HashSet<>(KeywordRetriever.tokens(question));
                var ranked = new ArrayList<Ranked>();
                for (int i = 0; i < texts.size(); i++) {
                    long overlap = KeywordRetriever.tokens(texts.get(i)).stream().distinct().filter(q::contains).count();
                    ranked.add(new Ranked(i, overlap == 0 ? -10.0 : overlap));
                }
                ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
                return ranked;
            }
            @Override public Generation generate(String question, List<Source> sources) {
                GENERATIONS.incrementAndGet();
                if (sources.isEmpty()) return new Generation(List.of(), 10, 1);
                var first = sources.getFirst();
                return new Generation(List.of(new GeneratedStatement(first.excerpt().lines().findFirst().orElse(first.excerpt()), List.of(first.chunkId()))), 120, 20);
            }
            @Override public boolean hasQuality() { return true; }
        };
    }

    @Bean ModelGateway.TestVectorStore fakeVectors() {
        return new ModelGateway.TestVectorStore() {
            @Override public void upsert(List<Point> points) { points.forEach(p -> POINTS.put(p.versionId() + ":" + p.chunkId(), p)); }
            @Override public void deleteVersion(UUID orgId, UUID versionId) { POINTS.values().removeIf(p -> p.orgId().equals(orgId) && p.versionId().equals(versionId)); }
            @Override public void deleteDocument(UUID orgId, UUID documentId) { POINTS.values().removeIf(p -> p.orgId().equals(orgId) && p.documentId().equals(documentId)); }
            @Override public List<Match> query(UUID orgId, List<Double> vector, Collection<UUID> versions, int limit) {
                return POINTS.values().stream().filter(p -> p.orgId().equals(orgId) && versions.contains(p.versionId()))
                        .map(p -> new Match(p.versionId(), p.chunkId(), dot(vector, p.vector())))
                        .sorted(Comparator.comparingDouble(Match::score).reversed()).limit(limit).toList();
            }
            @Override public String health() { return "UP"; }
        };
    }
    static double dot(List<Double> a, List<Double> b) { double s = 0; for (int i = 0; i < a.size(); i++) s += a.get(i) * b.get(i); return s; }
}
