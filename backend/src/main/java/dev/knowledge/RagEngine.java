package dev.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import static dev.knowledge.Models.*;

@Component
public class RagEngine {
    private final boolean enabled;
    private final RagModels models;
    private final VectorIndex vectors;
    private final int candidateK;
    private final double minRerankScore;
    private double maxRerankGap=5.0;
    private String llmModel="qwen2.5-1.5b-instruct";
    private String chunkStrategy="structural";
    private final ReentrantLock inference=new ReentrantLock();
    private String indexedSignature="";
    private final Map<String,List<Double>> embeddingCache=new HashMap<>();
    public record Result(List<Source> sources,List<Segment> excerpts,List<GeneratedStatement> generated,
                         List<PipelineStage> stages,Integer inputTokens,Integer outputTokens,boolean evidencePassed) {}
    @Autowired
    public RagEngine(@Value("${knowledge.rag.enabled:false}") boolean enabled,
            @Value("${knowledge.rag.embedding-url:http://127.0.0.1:18112}") String embeddingUrl,
            @Value("${knowledge.rag.reranker-url:http://127.0.0.1:18113}") String rerankerUrl,
            @Value("${knowledge.rag.llm-url:http://127.0.0.1:18111}") String llmUrl,
            @Value("${knowledge.rag.qdrant-url:http://127.0.0.1:16333}") String qdrantUrl,
            @Value("${knowledge.rag.collection:atlas_rag_v1}") String collection,
            @Value("${knowledge.rag.candidate-k:10}") int candidateK,
            @Value("${knowledge.rag.min-rerank-score:-8.0}") double threshold,
            @Value("${knowledge.rag.max-rerank-gap:5.0}") double maxRerankGap,
            @Value("${knowledge.rag.llm-model:qwen3-4b-instruct-2507}") String llmModel,
            @Value("${knowledge.chunk-strategy:structural}") String chunkStrategy,ObjectMapper mapper) {
        this(enabled,enabled?new LlamaRagModels(embeddingUrl,rerankerUrl,llmUrl,llmModel,mapper):null,
                enabled?new QdrantVectorIndex(qdrantUrl,collection,mapper):null,candidateK,threshold);
        this.chunkStrategy=chunkStrategy;
        if(!Double.isFinite(maxRerankGap) || maxRerankGap<0 || maxRerankGap>20) throw new IllegalArgumentException("Invalid reranker score window");
        if(!llmModel.matches("[a-zA-Z0-9._-]{1,100}")) throw new IllegalArgumentException("Invalid local model alias");
        this.maxRerankGap=maxRerankGap;this.llmModel=llmModel;
    }
    public RagEngine(boolean enabled,RagModels models,VectorIndex vectors,int candidateK,double threshold) {
        if(candidateK<5 || candidateK>50 || !Double.isFinite(threshold)) throw new IllegalArgumentException("Invalid RAG candidate count or reranker threshold");
        this.enabled=enabled;this.models=models;this.vectors=vectors;this.candidateK=candidateK;minRerankScore=threshold;
    }
    public boolean enabled() { return enabled; }
    public Map<String,Object> configuration() {
        return Map.ofEntries(Map.entry("enabled",enabled),Map.entry("embedding",enabled?"BGE_M3":"NONE"),Map.entry("reranker",enabled?"BGE_RERANKER_V2_M3":"NONE"),
                Map.entry("llm",enabled?llmModel:"NONE"),Map.entry("vectorStore",enabled?"QDRANT":"NONE"),
                Map.entry("fusion","RRF_K60"),Map.entry("candidateK",candidateK),Map.entry("rerankerMinLogit",minRerankScore),
                Map.entry("rerankerMaxLogitGap",maxRerankGap),Map.entry("generationPromptVersion","complete-answer-v4"),
                Map.entry("chunking",chunkStrategy.equals("semantic")?"BGE_ADJACENT_PARAGRAPH_BOUNDARIES":"MARKDOWN_STRUCTURAL_BOUNDARIES"),Map.entry("paidApiCalls",0));
    }
    public Result run(StoreState all,StoreState authorized,KeywordRetriever retriever,String question,int topK) {
        if(!inference.tryLock()) throw new ApiException(503,"MODEL_BUSY","The local CPU model is processing another question. Retry shortly.");
        try {
            var stages=new ArrayList<PipelineStage>();
            long start=System.nanoTime();
            synchronizeIndex(all);
            stages.add(stage("vector_index","READY",start,authorized.documents().size(),authorized.documents().size()));
            start=System.nanoTime();
            var sparse=retriever.retrieve(authorized,question,null,candidateK).hits();
            stages.add(stage("bm25","COMPLETE",start,authorized.documents().size(),sparse.size()));
            start=System.nanoTime();
            var query=models.embed(List.of(question)).getFirst();
            var dense=vectors.query(query,authorized.documents(),candidateK);
            // Verify database results against this authorized, current snapshot before fusion/reranking/generation.
            var resolved=new ArrayList<Source>();var seen=new HashSet<String>();
            for(var match:dense) {
                var doc=authorized.documents().stream().filter(d->d.id().equals(match.documentId()) && d.version()==match.version()).findFirst();
                if(doc.isEmpty()) continue;
                var chunk=doc.get().chunks().stream().filter(c->c.id().equals(match.chunkId())).findFirst();
                if(chunk.isPresent() && seen.add(match.chunkId())) resolved.add(source(doc.get(),chunk.get(),match.score()));
            }
            stages.add(stage("dense_retrieval","COMPLETE",start,authorized.documents().size(),resolved.size()));
            start=System.nanoTime();
            var sparseSources=sparse.stream().map(h->source(h.document(),h.chunk(),h.score())).toList();
            var fused=fuse(sparseSources,resolved);
            stages.add(stage("rrf_fusion","COMPLETE",start,sparseSources.size()+resolved.size(),fused.size()));
            if(fused.isEmpty()) return new Result(List.of(),List.of(),List.of(),List.copyOf(stages),null,null,false);
            start=System.nanoTime();
            var ranked=models.rerank(question,fused.stream().map(s->s.title()+"\n"+s.heading()+"\n"+s.excerpt()).toList());
            double bestScore=ranked.stream().mapToDouble(RagModels.Ranked::score).max().orElse(Double.NEGATIVE_INFINITY);
            var chosen=new ArrayList<Source>();var indices=new HashSet<Integer>();
            for(var rank:ranked) {
                if(rank.index()<0 || rank.index()>=fused.size() || !indices.add(rank.index()) || !Double.isFinite(rank.score()))
                    throw new ApiException(502,"MODEL_INVALID_OUTPUT","Reranker selected an invalid candidate.");
                if(rank.score()>=minRerankScore && rank.score()>=bestScore-maxRerankGap && chosen.size()<topK) {
                    var s=fused.get(rank.index());chosen.add(new Source(s.chunkId(),s.documentId(),s.title(),s.version(),s.heading(),s.excerpt(),s.lineStart(),s.lineEnd(),rank.score(),s.href()));
                }
            }
            stages.add(stage("reranker","COMPLETE",start,fused.size(),chosen.size()));
            if(chosen.isEmpty()) {
                stages.add(new PipelineStage("generation","SKIPPED_NO_EVIDENCE",0,0,0));
                return new Result(List.of(),List.of(),List.of(),List.copyOf(stages),null,null,false);
            }
            start=System.nanoTime();
            var output=models.generate(question,List.copyOf(chosen));
            var permitted=new HashSet<>(chosen.stream().map(Source::chunkId).toList());
            var cited=new LinkedHashSet<String>();
            if(output.statements()==null || output.statements().size()>3) throw new ApiException(502,"INVALID_CITATION","Invalid generated answer.");
            for(var statement:output.statements()) {
                if(statement.text()==null || statement.text().isBlank() || statement.chunkIds()==null || statement.chunkIds().isEmpty()
                        || !permitted.containsAll(statement.chunkIds())) throw new ApiException(502,"INVALID_CITATION","Generated text cited evidence outside the authorized context.");
                cited.addAll(statement.chunkIds());
            }
            var excerpts=chosen.stream().filter(s->cited.contains(s.chunkId())).map(s->new Segment(s.chunkId(),s.excerpt())).toList();
            stages.add(stage("generation",output.statements().isEmpty()?"REFUSED":"COMPLETE",start,chosen.size(),output.statements().size()));
            return new Result(List.copyOf(chosen),excerpts,List.copyOf(output.statements()),List.copyOf(stages),output.inputTokens(),output.outputTokens(),!output.statements().isEmpty());
        } finally { inference.unlock(); }
    }
    private void synchronizeIndex(StoreState state) {
        var current=state.documents().stream().filter(d->!d.metadata().expired(Instant.now())).toList();
        String signature=MarkdownChunker.hash(current.stream().map(d->d.id()+":"+d.version()+":"+d.contentHash()+":"+d.metadata()).reduce("",(a,b)->a+"\n"+b));
        if(signature.equals(indexedSignature)) return;
        var pending=new ArrayList<String>();var keys=new ArrayList<String>();var ordered=new ArrayList<Map.Entry<Document,Chunk>>();
        for(var doc:current) for(var chunk:doc.chunks()) {
            String text=doc.title()+"\n"+chunk.heading()+"\n"+chunk.text();
            String key=MarkdownChunker.hash(text);keys.add(key);ordered.add(Map.entry(doc,chunk));
            if(!embeddingCache.containsKey(key)) pending.add(text);
        }
        if(!pending.isEmpty()) {
            var encoded=models.embed(pending);
            if(encoded.size()!=pending.size()) throw new ApiException(502,"MODEL_INVALID_OUTPUT","Embedding count changed.");
            for(int i=0;i<pending.size();i++) embeddingCache.put(MarkdownChunker.hash(pending.get(i)),encoded.get(i));
        }
        var points=new ArrayList<VectorIndex.Point>();
        for(int i=0;i<ordered.size();i++) points.add(new VectorIndex.Point(ordered.get(i).getKey(),ordered.get(i).getValue(),embeddingCache.get(keys.get(i))));
        vectors.replace(points); // On failure the signature is not advanced, so the next query retries synchronization.
        embeddingCache.keySet().retainAll(new HashSet<>(keys));indexedSignature=signature;
    }
    static List<Source> fuse(List<Source> sparse,List<Source> dense) {
        var scores=new HashMap<String,Double>();var sources=new HashMap<String,Source>();
        for(var list:List.of(sparse,dense)) {
            var seen=new HashSet<String>();
            for(int i=0;i<list.size();i++) {
                var source=list.get(i);if(!seen.add(source.chunkId())) continue;
                sources.put(source.chunkId(),source);scores.merge(source.chunkId(),1.0/(60+i+1),Double::sum);
            }
        }
        return sources.values().stream().sorted(Comparator.<Source>comparingDouble(s->scores.get(s.chunkId())).reversed().thenComparing(Source::chunkId)).toList();
    }
    static Source source(Document doc,Chunk chunk,double score) {
        return new Source(chunk.id(),doc.id(),doc.title(),doc.version(),chunk.heading(),chunk.text(),chunk.lineStart(),chunk.lineEnd(),score,
                "/api/documents/"+doc.id()+"/chunks/"+chunk.id()+"?version="+doc.version());
    }
    private PipelineStage stage(String name,String status,long start,int inputs,int outputs) { return new PipelineStage(name,status,(System.nanoTime()-start)/1_000_000,inputs,outputs); }
}
