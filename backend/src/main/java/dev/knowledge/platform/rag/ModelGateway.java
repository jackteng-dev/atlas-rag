package dev.knowledge.platform.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.knowledge.*;
import dev.knowledge.Models.Source;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

/**
 * Local model provider routing. Only loopback llama.cpp endpoints are accepted ({@link LocalJsonHttp} enforces it), so
 * no document can silently leave the host and no paid API exists in this code path. A semaphore bounds concurrent CPU
 * inference: queues absorb load, they do not multiply model throughput.
 */
@Configuration @Profile("platform")
public class ModelGateway {
    public record ModelOption(String id, String model, boolean available) {}

    /** Wraps the provider with a fairness gate shared by chat answers, agents and ingestion embeddings. */
    public static final class GatedModels implements RagModels {
        private final RagModels delegate;
        private final Semaphore generation;
        private final Semaphore embedding;
        private final String fastModel, qualityModel;
        GatedModels(RagModels delegate, int maxInference, String fastModel, String qualityModel) {
            this.delegate = delegate; generation = new Semaphore(maxInference, true); embedding = new Semaphore(Math.max(1, maxInference), true);
            this.fastModel = fastModel; this.qualityModel = qualityModel;
        }
        private <T> T gated(Semaphore gate, Callable<T> call) {
            try {
                if (!gate.tryAcquire(10, TimeUnit.MINUTES)) throw new ApiException(503, "MODEL_BUSY", "Local models are saturated. The job will be retried.");
                try { return call.call(); } finally { gate.release(); }
            } catch (ApiException e) { throw e; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException(503, "MODEL_INTERRUPTED", "Inference was interrupted."); }
            catch (Exception e) { throw new ApiException(502, "MODEL_FAILURE", "Local model call failed."); }
        }
        @Override public List<List<Double>> embed(List<String> texts) { return gated(embedding, () -> delegate.embed(texts)); }
        @Override public List<Ranked> rerank(String q, List<String> texts) { return gated(embedding, () -> delegate.rerank(q, texts)); }
        @Override public Generation generate(String q, List<Source> sources) { return gated(generation, () -> delegate.generate(q, sources)); }
        @Override public Generation generate(String q, List<Source> sources, boolean quality) { return gated(generation, () -> delegate.generate(q, sources, quality)); }
        @Override public boolean hasQuality() { return delegate.hasQuality(); }
        public String modelName(boolean quality) { return quality && hasQuality() ? qualityModel : fastModel; }
        public List<ModelOption> options() {
            var list = new ArrayList<ModelOption>();
            list.add(new ModelOption("fast", fastModel, !(delegate instanceof DisabledModels)));
            list.add(new ModelOption("quality", qualityModel, hasQuality() && !(delegate instanceof DisabledModels)));
            return list;
        }
        public RagModels delegate() { return delegate; }
    }

    /** Used when models are switched off: every call fails clearly instead of fabricating output. */
    public static final class DisabledModels implements RagModels {
        private static ApiException off() { return new ApiException(503, "MODELS_DISABLED", "Local models are not enabled on this server."); }
        @Override public List<List<Double>> embed(List<String> texts) { throw off(); }
        @Override public List<Ranked> rerank(String question, List<String> texts) { throw off(); }
        @Override public Generation generate(String question, List<Source> sources) { throw off(); }
    }

    @Bean @Primary GatedModels gatedModels(@Value("${atlas.models.enabled:true}") boolean enabled,
                                  @Value("${atlas.models.embedding-url}") String embeddingUrl, @Value("${atlas.models.reranker-url}") String rerankerUrl,
                                  @Value("${atlas.models.llm-url}") String llmUrl, @Value("${atlas.models.llm-model}") String llmModel,
                                  @Value("${atlas.models.quality-llm-url:}") String qualityUrl, @Value("${atlas.models.quality-llm-model:qwen3-8b}") String qualityModel,
                                  @Value("${atlas.models.max-concurrent-inference:1}") int maxInference, ObjectMapper mapper,
                                  Optional<TestModels> override) {
        RagModels provider;
        if (override.isPresent()) provider = override.get();
        else if (!enabled) provider = new DisabledModels();
        else {
            var llama = new LlamaRagModels(embeddingUrl, rerankerUrl, llmUrl, llmModel, mapper);
            if (!qualityUrl.isBlank()) llama.withQuality(qualityUrl, qualityModel);
            provider = llama;
        }
        if (maxInference < 1 || maxInference > 16) throw new IllegalArgumentException("atlas.models.max-concurrent-inference must be 1–16");
        return new GatedModels(provider, maxInference, llmModel, qualityModel);
    }

    @Bean @Primary VectorStore vectorStore(@Value("${atlas.qdrant.url}") String url, @Value("${atlas.qdrant.collection}") String collection,
                                  @Value("${atlas.network.internal-hosts:}") String internalHosts, ObjectMapper mapper, Optional<TestVectorStore> override) {
        if (override.isPresent()) return override.get();
        var hosts = new java.util.HashSet<String>();
        for (var h : internalHosts.split(",")) if (!h.isBlank()) hosts.add(h.strip());
        return new QdrantVectorStore(url, collection, mapper, java.util.Set.copyOf(hosts));
    }

    /** Marker for test-provided deterministic models (never used in production wiring). */
    public interface TestModels extends RagModels {}
    /** Marker for a test-provided in-memory vector store. */
    public interface TestVectorStore extends VectorStore {}
}
