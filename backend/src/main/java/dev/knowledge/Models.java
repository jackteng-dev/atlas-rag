package dev.knowledge;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class Models {
    private Models() {}
    public record Chunk(String id, String heading, String text, int lineStart, int lineEnd) {}
    public record Document(String id, String title, int version, String contentHash, String updatedAt,
                           String markdown, List<Chunk> chunks, Metadata metadata) {
        public Document { metadata = metadata == null ? Metadata.publicDocument() : metadata; }
        public Document(String id,String title,int version,String hash,String updated,String markdown,List<Chunk> chunks) {
            this(id,title,version,hash,updated,markdown,chunks,Metadata.publicDocument());
        }
    }
    public record Metadata(@NotBlank @Size(max=64) String department, @NotBlank @Size(max=64) String owner,
                           @Size(max=64) String expiresAt, @Size(max=20) List<@Pattern(regexp="[A-Z][A-Z0-9_]{0,31}") String> allowedRoles,
                           @Size(max=20) List<@NotBlank @Size(max=64) String> tags) {
        public Metadata {
            department = department == null ? "public" : department;
            owner = owner == null ? "public-demo" : owner;
            allowedRoles = allowedRoles == null ? List.of() : List.copyOf(allowedRoles);
            tags = tags == null ? List.of() : List.copyOf(tags);
            expiresAt = expiresAt == null || expiresAt.isBlank() ? null : Instant.parse(expiresAt).toString();
        }
        public static Metadata publicDocument() { return new Metadata("public","public-demo",null,List.of(),List.of()); }
        public boolean expired(Instant now) { return expiresAt != null && !Instant.parse(expiresAt).isAfter(now); }
        public boolean publicAccess() { return allowedRoles.isEmpty() && department.equals("public"); }
    }
    public record DocumentSummary(String id, String title, int version, String contentHash, String updatedAt, int chunkCount, Metadata metadata) {}
    public record StoreState(int schemaVersion, long revision, Map<String, Integer> versions, List<Document> documents) {}
    public record ImportRequest(@NotBlank @Pattern(regexp="[a-z0-9][a-z0-9-]{0,63}") String id,
                                @NotBlank @Size(max=120) String title,
                                @NotBlank @Size(max=200000) String markdown, @Valid Metadata metadata) {
        public ImportRequest(String id,String title,String markdown) { this(id,title,markdown,null); }
    }
    public record UpdateRequest(@NotBlank @Size(max=120) String title,
                                @NotBlank @Size(max=200000) String markdown, @Min(1) int expectedVersion, @Valid Metadata metadata) {
        public UpdateRequest(String title,String markdown,int expectedVersion) { this(title,markdown,expectedVersion,null); }
    }
    /** model: "fast" (default local LLM) or "quality" (optional larger local LLM, loaded on demand). */
    public record AskRequest(@NotBlank @Size(max=1000) String question, @Size(max=50) List<String> documentIds,
                             @Min(1) @Max(5) Integer topK, @Pattern(regexp="fast|quality") String model) {
        public AskRequest(String question,List<String> documentIds,Integer topK) { this(question,documentIds,topK,null); }
        public boolean quality() { return "quality".equals(model); }
    }
    public record Source(String chunkId, String documentId, String title, int version, String heading,
                         String excerpt, int lineStart, int lineEnd, double score, String href) {}
    public record Segment(String chunkId, String quote) {}
    public record Answer(String requestId, String status, String mode, String retrieval, String message,
                         List<Segment> segments, List<Source> sources, double coverage, List<String> missingTerms,
                         long indexRevision, long elapsedMs, Integer modelInputTokens, Integer modelOutputTokens,
                         Map<String,Object> configuration, List<GeneratedStatement> generated,
                         List<PipelineStage> pipeline, Map<String,Object> evaluation) {}
    public record GeneratedStatement(String text, List<String> chunkIds) {}
    public record PipelineStage(String name, String status, long elapsedMs, int inputCount, int outputCount) {}
    public record ProviderResult(List<Segment> segments, Integer inputTokens, Integer outputTokens) {}
}
