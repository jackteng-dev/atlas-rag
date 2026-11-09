package dev.knowledge;

import java.util.*;
import static dev.knowledge.Models.*;

/** Groups adjacent paragraphs only within one heading using real embedding similarity and a strict size cap. */
public final class SemanticBoundaryMerger {
    private final RagModels models;
    private final int maxChars;
    public SemanticBoundaryMerger(RagModels models,int maxChars) { this.models=models;this.maxChars=maxChars; }
    public List<Chunk> merge(String documentId,List<Chunk> chunks,String markdown) {
        if(chunks.size()<2) return chunks;
        var vectors=models.embed(chunks.stream().map(c->c.heading()+"\n"+c.text()).toList());
        if(vectors.size()!=chunks.size()) throw new ApiException(502,"MODEL_INVALID_OUTPUT","Semantic chunking requires one vector per paragraph.");
        var groups=new ArrayList<Chunk>();var occurrences=new HashMap<String,Integer>();
        String[] lines=markdown.replace("\r\n","\n").replace('\r','\n').split("\n",-1);
        Chunk current=chunks.getFirst();
        for(int i=1;i<chunks.size();i++) {
            var next=chunks.get(i);
            String combined=String.join("\n",Arrays.copyOfRange(lines,current.lineStart()-1,next.lineEnd()));
            if(current.heading().equals(next.heading()) && next.lineStart()>current.lineEnd()
                    && String.join("\n",Arrays.copyOfRange(lines,current.lineStart()-1,current.lineEnd())).equals(current.text())
                    && String.join("\n",Arrays.copyOfRange(lines,next.lineStart()-1,next.lineEnd())).equals(next.text())
                    && Arrays.stream(lines,current.lineEnd(),next.lineStart()-1).allMatch(String::isBlank)
                    && combined.length()<=maxChars && cosine(vectors.get(i-1),vectors.get(i))>=0.72) {
                current=new Chunk("",current.heading(),combined,current.lineStart(),next.lineEnd());
            } else { groups.add(current);current=next; }
        }
        groups.add(current);
        var result=new ArrayList<Chunk>();
        for(var group:groups) {
            String hash=MarkdownChunker.hash(documentId+"\n"+group.heading()+"\n"+group.text());
            int occurrence=occurrences.merge(hash,1,Integer::sum);
            result.add(new Chunk(documentId+"-"+hash.substring(0,16)+"-"+occurrence,group.heading(),group.text(),group.lineStart(),group.lineEnd()));
        }
        return List.copyOf(result);
    }
    static double cosine(List<Double> a,List<Double> b) {
        if(a.isEmpty() || a.size()!=b.size()) throw new ApiException(502,"MODEL_INVALID_OUTPUT","Inconsistent semantic chunking vectors.");
        double dot=0,na=0,nb=0;
        for(int i=0;i<a.size();i++) {
            if(!Double.isFinite(a.get(i)) || !Double.isFinite(b.get(i))) throw new ApiException(502,"MODEL_INVALID_OUTPUT","Non-finite chunking vector.");
            dot+=a.get(i)*b.get(i);na+=a.get(i)*a.get(i);nb+=b.get(i)*b.get(i);
        }
        if(na==0 || nb==0) throw new ApiException(502,"MODEL_INVALID_OUTPUT","Zero chunking vector.");
        return dot/Math.sqrt(na*nb);
    }
}
