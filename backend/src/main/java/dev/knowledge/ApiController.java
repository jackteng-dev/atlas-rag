package dev.knowledge;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static dev.knowledge.Models.*;

@RestController @RequestMapping("/api")
public class ApiController {
    private final DocumentStore store;
    private final AnswerService answers;
    private final DocumentAccess access;
    public ApiController(DocumentStore store,AnswerService answers,DocumentAccess access) { this.store=store; this.answers=answers;this.access=access; }
    @GetMapping("/status") public Map<String,Object> status() {
        var snapshot=store.snapshot();
        var actor=access.current();
        var eligible=snapshot.documents().stream().filter(d->access.eligible(d,actor,java.time.Instant.now())).toList();
        return Map.ofEntries(Map.entry("status","READY"),Map.entry("mode",answers.mode()),Map.entry("retrieval",answers.retrievalMode()),Map.entry("embedding",answers.mode().equals("LOCAL_RAG_GENERATION")?"BGE_M3":"NONE"),
                Map.entry("indexRevision",snapshot.revision()),Map.entry("documentCount",eligible.size()),Map.entry("chunkCount",eligible.stream().mapToInt(d->d.chunks().size()).sum()),
                Map.entry("coverageThreshold",answers.configuration().coverageThreshold()),Map.entry("chunkMaxChars",answers.configuration().chunkMaxChars()),
                Map.entry("configuration",answers.effectiveConfiguration(answers.configuration().topK())),Map.entry("rag",answers.ragConfiguration()),
                Map.entry("authentication",access.authenticatedMode()?"BASIC_SERVER_ACCOUNTS":"PUBLIC_DEMO"),Map.entry("actor",actor.name()),Map.entry("roles",actor.roles().stream().sorted().toList()),
                Map.entry("departments",actor.departments().stream().sorted().toList()));
    }
    @GetMapping("/documents") public List<DocumentSummary> list() {
        return store.snapshot().documents().stream().filter(d->access.readable(d,access.current())).map(d->new DocumentSummary(d.id(),d.title(),d.version(),d.contentHash(),d.updatedAt(),d.chunks().size(),d.metadata())).toList();
    }
    @GetMapping("/documents/{id}") public Document document(@PathVariable String id) { var doc=store.get(id);access.requireRead(doc,false);return doc; }
    @PostMapping("/documents") public ResponseEntity<Document> create(@Valid @RequestBody ImportRequest request) {
        access.requireImport(request.metadata()==null?Metadata.publicDocument():request.metadata());
        return ResponseEntity.status(201).body(store.create(request));
    }
    @PutMapping("/documents/{id}") public Document update(@PathVariable String id,@Valid @RequestBody UpdateRequest request) {
        var doc=store.get(id);access.requireManage(doc);access.requireImport(request.metadata()==null?doc.metadata():request.metadata());return store.update(id,request);
    }
    @DeleteMapping("/documents/{id}") public ResponseEntity<Void> delete(@PathVariable String id,@RequestParam int expectedVersion) {
        access.requireManage(store.get(id));store.delete(id,expectedVersion); return ResponseEntity.noContent().build();
    }
    @GetMapping("/documents/{id}/chunks/{chunkId}") public Source source(@PathVariable String id,@PathVariable String chunkId,@RequestParam int version) {
        var doc=store.get(id);
        access.requireRead(doc,true);
        if(doc.version()!=version) throw new ApiException(409,"STALE_CITATION","Source version changed. Run the question again.");
        var chunk=doc.chunks().stream().filter(c->c.id().equals(chunkId)).findFirst().orElseThrow(()->new ApiException(404,"CHUNK_NOT_FOUND","Chunk is absent from the current version."));
        return new Source(chunk.id(),doc.id(),doc.title(),doc.version(),chunk.heading(),chunk.text(),chunk.lineStart(),chunk.lineEnd(),0,
                "/api/documents/"+doc.id()+"/chunks/"+chunk.id()+"?version="+doc.version());
    }
    @PostMapping("/questions") public Answer ask(@Valid @RequestBody AskRequest request) { return answers.ask(request); }
}
