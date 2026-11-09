package dev.knowledge;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Startup configuration; response metadata records the effective query topK. */
@Component
public record RetrievalConfiguration(int chunkMaxChars, int topK, double coverageThreshold) {
    public static final double BM25_K1 = 1.2;
    public static final double BM25_B = 0.75;
    public static RetrievalConfiguration baseline() { return new RetrievalConfiguration(800, 5, 0.60); }
    @Autowired
    public RetrievalConfiguration(
            @Value("${knowledge.chunk-max-chars:800}") int chunkMaxChars,
            @Value("${knowledge.top-k:5}") int topK,
            @Value("${knowledge.coverage-threshold:0.60}") double coverageThreshold) {
        if (chunkMaxChars < 32 || chunkMaxChars > 8000) throw new IllegalArgumentException("chunkMaxChars must be 32..8000 UTF-16 units");
        if (topK < 1 || topK > 5) throw new IllegalArgumentException("topK must be 1..5");
        if (!Double.isFinite(coverageThreshold) || coverageThreshold < 0 || coverageThreshold > 1)
            throw new IllegalArgumentException("coverageThreshold must be finite and 0..1");
        this.chunkMaxChars = chunkMaxChars;
        this.topK = topK;
        this.coverageThreshold = coverageThreshold;
    }
    public java.util.Map<String, Object> effective(int queryTopK) {
        return java.util.Map.of("chunkMaxChars", chunkMaxChars, "topK", queryTopK,
                "coverageThreshold", coverageThreshold, "bm25K1", BM25_K1, "bm25B", BM25_B,
                "chunkLengthUnit", "UTF16_CODE_UNITS", "tokenizer", "lexical-v1");
    }
}
