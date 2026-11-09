package dev.knowledge;

import java.util.*;
import org.springframework.stereotype.Service;
import static dev.knowledge.Models.*;

@Service
public class AnswerService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AnswerService.class);
    public static final double MIN_COVERAGE=0.60;
    private final DocumentStore store;
    private final KeywordRetriever retriever;
    private final ExcerptProvider provider;
    private final RetrievalConfiguration configuration;
    private final DocumentAccess access;
    private final RagEngine rag;
    public AnswerService(DocumentStore store,KeywordRetriever retriever,ExcerptProvider provider) {
        this(store,retriever,provider,RetrievalConfiguration.baseline());
    }
    public AnswerService(DocumentStore store,KeywordRetriever retriever,ExcerptProvider provider,RetrievalConfiguration configuration) {
        this(store,retriever,provider,configuration,new DocumentAccess(),null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AnswerService(DocumentStore store,KeywordRetriever retriever,ExcerptProvider provider,RetrievalConfiguration configuration,DocumentAccess access,RagEngine rag) {
        this.store=store; this.retriever=retriever; this.provider=provider;
        this.configuration=configuration;this.access=access;this.rag=rag;
    }
    public Answer ask(AskRequest request) {
        return ask(request,access.current());
    }
    public Answer ask(AskRequest request,DocumentAccess.Actor actor) {
        long start=System.nanoTime(); String requestId=UUID.randomUUID().toString();
        try {
            Answer answer=execute(request,start,requestId,actor);
            LOG.info("knowledge_query requestId={} mode={} status={} elapsedMs={} inputTokens={} outputTokens={} stages={}",
                    requestId,answer.mode(),answer.status(),answer.elapsedMs(),answer.modelInputTokens(),answer.modelOutputTokens(),answer.pipeline());
            return answer;
        } catch(ApiException e) {
            LOG.warn("knowledge_query_failed requestId={} code={} elapsedMs={}",requestId,e.code,(System.nanoTime()-start)/1_000_000);
            throw e;
        }
    }
    private Answer execute(AskRequest request,long start,String requestId,DocumentAccess.Actor actor) {
        StoreState snapshot=store.snapshot();
        var now=java.time.Instant.now();
        var readable=snapshot.documents().stream().filter(d->access.eligible(d,actor,now)).toList();
        if(request.documentIds()!=null) request.documentIds().forEach(id->{
            if(readable.stream().noneMatch(d->d.id().equals(id))) throw new ApiException(404,"DOCUMENT_NOT_FOUND","Document is unavailable in the current scope.");
        });
        var scoped=readable.stream().filter(d->request.documentIds()==null || request.documentIds().isEmpty() || request.documentIds().contains(d.id())).toList();
        StoreState authorized=new StoreState(snapshot.schemaVersion(),snapshot.revision(),Map.of(),scoped);
        int topK=request.topK()==null?configuration.topK():request.topK();
        if(rag!=null && rag.enabled()) {
            var result=rag.run(snapshot,authorized,retriever,request.question(),topK);
            long validationStart=System.nanoTime();
            validate(result.excerpts(),result.sources());
            checkCurrent(snapshot,result.sources(),actor);
            var stages=new ArrayList<PipelineStage>();stages.add(new PipelineStage("authorization_and_expiry","COMPLETE",0,scoped.size(),scoped.size()));stages.addAll(result.stages());
            stages.add(new PipelineStage("citation_validation","COMPLETE",(System.nanoTime()-validationStart)/1_000_000,result.sources().size(),result.excerpts().size()));
            return new Answer(requestId,result.evidencePassed()?"ANSWER_GENERATED":"INSUFFICIENT_EVIDENCE","LOCAL_RAG_GENERATION","HYBRID_BM25_DENSE",
                    result.evidencePassed()?"本地模型依據來源生成回答；引用原文與版本可核對，語意忠實度仍需人工檢查。":"目前可讀且未過期的文件不足以支持回答。",
                    result.excerpts(),result.sources(),retriever.retrieve(authorized,request.question(),null,topK).coverage(),List.of(),
                    snapshot.revision(),(System.nanoTime()-start)/1_000_000,result.inputTokens(),result.outputTokens(),effectiveConfiguration(topK),result.generated(),List.copyOf(stages),
                    Map.of("citationProvenance","VALIDATED","faithfulness","NOT_AUTOMATICALLY_MEASURED","answerRelevance","NOT_AUTOMATICALLY_MEASURED","contextPrecision","REQUIRES_REFERENCE_LABELS","paidApiCost",0));
        }
        var result=retriever.retrieve(authorized,request.question(),null,topK);
        List<Source> sources=result.hits().stream().map(h->new Source(h.chunk().id(),h.document().id(),h.document().title(),
                h.document().version(),h.chunk().heading(),h.chunk().text(),h.chunk().lineStart(),h.chunk().lineEnd(),
                Math.round(h.score()*10000)/10000.0,"/api/documents/"+h.document().id()+"/chunks/"+h.chunk().id()+"?version="+h.document().version())).toList();
        String status; String message; ProviderResult selected=new ProviderResult(List.of(),null,null);
        if(result.hits().isEmpty() || result.coverage()<configuration.coverageThreshold()) {
            status="INSUFFICIENT_EVIDENCE";
            message="證據不足：目前來源未涵蓋足夠查詢詞。請縮小問題或匯入相關文件。候選段落僅供檢查，不構成答案。";
        } else {
            selected=provider.select(request.question(),sources);
            validate(selected.segments(),sources);
            status=selected.segments().isEmpty()?"INSUFFICIENT_EVIDENCE":"EXTRACTS_FOUND";
            message=selected.segments().isEmpty()?"模型未選出可核對的原文證據。":"以下為相關來源原文摘錄；關鍵字涵蓋率不代表語意正確性，也不確認問題中的假設。";
        }
        checkCurrent(snapshot,sources,actor);
        return new Answer(requestId,status,provider.mode(),"BM25_KEYWORD",message,selected.segments(),sources,result.coverage(),result.missingTerms(),
                snapshot.revision(),(System.nanoTime()-start)/1_000_000,selected.inputTokens(),selected.outputTokens(),configuration.effective(topK),List.of(),
                List.of(new PipelineStage("authorization_and_expiry","COMPLETE",0,scoped.size(),scoped.size()),new PipelineStage("bm25","COMPLETE",0,scoped.size(),sources.size())),Map.of("faithfulness","NOT_APPLICABLE_EXTRACTIVE"));
    }
    private void checkCurrent(StoreState original,List<Source> sources,DocumentAccess.Actor actor) {
        var current=store.snapshot();
        if(current.revision()!=original.revision()) throw new ApiException(409,"INDEX_CHANGED","Documents changed during the query. Retry to use current sources.");
        for(var source:sources) {
            if(!access.eligible(DocumentStore.get(current,source.documentId()),actor,java.time.Instant.now()))
                throw new ApiException(409,"INDEX_CHANGED","Evidence expired during the query. Retry to use current sources.");
        }
    }
    static void validate(List<Segment> segments,List<Source> sources) {
        if(segments==null || segments.size()>5) throw new ApiException(502,"INVALID_CITATION","Too many or missing excerpts.");
        var evidence=new HashMap<String,Source>(); sources.forEach(s->evidence.put(s.chunkId(),s));
        for(Segment segment:segments) {
            Source source=evidence.get(segment.chunkId());
            if(source==null || segment.quote()==null || segment.quote().isBlank() || !source.excerpt().contains(segment.quote()))
                throw new ApiException(502,"INVALID_CITATION","Model selected an unknown chunk or text outside retrieved evidence.");
        }
    }
    public String mode() { return rag!=null && rag.enabled()?"LOCAL_RAG_GENERATION":provider.mode(); }
    public String retrievalMode() { return rag!=null && rag.enabled()?"HYBRID_BM25_DENSE":"BM25_KEYWORD"; }
    public Map<String,Object> ragConfiguration() { return rag==null?Map.of("enabled",false):rag.configuration(); }
    public Map<String,Object> effectiveConfiguration(int topK) {
        var effective=new LinkedHashMap<String,Object>(configuration.effective(topK));
        if(rag!=null && rag.enabled()) effective.put("rag",rag.configuration());
        return Map.copyOf(effective);
    }
    public RetrievalConfiguration configuration() { return configuration; }
}
