package dev.knowledge;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.knowledge.Models.*;

/** Qdrant stores model vectors plus provenance/access metadata, never an authoritative copy of source text. */
public final class QdrantVectorIndex implements VectorIndex {
    private final LocalJsonHttp http;
    private final String collection;
    public QdrantVectorIndex(String origin,String collection,ObjectMapper mapper) {
        if(!collection.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid Qdrant collection");
        this.collection="/collections/"+collection;http=new LocalJsonHttp(origin,mapper);
    }
    public void replace(List<Point> points) {
        if(points.isEmpty()) {
            if(!http.request("GET",collection,null,true).isNull()) http.request("POST",collection+"/points/delete?wait=true",Map.of("filter",Map.of()));
            return;
        }
        int dimensions=points.getFirst().vector().size();
        var current=http.request("GET",collection,null,true);
        if(current.isNull()) http.request("PUT",collection,Map.of("vectors",Map.of("size",dimensions,"distance","Cosine")));
        else if(current.path("result").path("config").path("params").path("vectors").path("size").asInt()!=dimensions)
            throw new ApiException(503,"VECTOR_CONFIGURATION_CHANGED","Vector dimensions changed; use a new Qdrant collection.");
        var ids=new ArrayList<String>();
        for(int start=0;start<points.size();start+=64) {
            var batch=new ArrayList<Map<String,Object>>();
            for(var point:points.subList(start,Math.min(start+64,points.size()))) {
                var doc=point.document();var chunk=point.chunk();var metadata=doc.metadata();
                String id=UUID.nameUUIDFromBytes((doc.id()+":"+doc.version()+":"+chunk.id()).getBytes(StandardCharsets.UTF_8)).toString();ids.add(id);
                var payload=new LinkedHashMap<String,Object>();
                payload.put("documentId",doc.id());payload.put("version",doc.version());payload.put("chunkId",chunk.id());
                payload.put("department",metadata.department());payload.put("owner",metadata.owner());payload.put("allowedRoles",metadata.allowedRoles());
                payload.put("expiresAt",metadata.expiresAt());payload.put("updatedAt",doc.updatedAt());payload.put("tags",metadata.tags());
                batch.add(Map.of("id",id,"vector",point.vector(),"payload",payload));
            }
            http.request("PUT",collection+"/points?wait=true",Map.of("points",batch));
        }
        // Old/deleted versions are purged only after successful insertion. Query filters already exclude them.
        http.request("POST",collection+"/points/delete?wait=true",Map.of("filter",Map.of("must_not",List.of(Map.of("has_id",ids)))));
    }
    public List<Match> query(List<Double> vector,List<Document> authorized,int limit) {
        if(authorized.isEmpty()) return List.of();
        var allowed=authorized.stream().map(d->Map.of("must",List.of(
                Map.of("key","documentId","match",Map.of("value",d.id())),
                Map.of("key","version","match",Map.of("value",d.version()))))).toList();
        // IDs AND current versions are derived after server authorization/expiry checks, not accepted from client claims.
        var response=http.request("POST",collection+"/points/query",Map.of("query",vector,"filter",Map.of("should",allowed),"limit",limit,"with_payload",true));
        var matches=new ArrayList<Match>();
        var points=response.path("result").path("points");
        if(!points.isArray()) throw new ApiException(502,"VECTOR_INVALID_OUTPUT","Qdrant returned an invalid result list.");
        for(var point:points) {
            var p=point.path("payload");double score=point.path("score").asDouble(Double.NaN);
            if(!Double.isFinite(score)) throw new ApiException(502,"VECTOR_INVALID_OUTPUT","Qdrant returned a non-finite score.");
            matches.add(new Match(p.path("documentId").asText(),p.path("version").asInt(),p.path("chunkId").asText(),score));
        }
        return List.copyOf(matches);
    }
}
