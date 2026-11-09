package dev.knowledge;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import static dev.knowledge.Models.*;

@Component
public class KeywordRetriever {
    private static final Set<String> STOP = Set.of("a","an","the","is","are","was","were","be","been","to","of","for","and","or","in","on","at","with","from","by","how","what","which","why","when","where","do","does","did","can","could","would","should","will","it","its","this","that","these","those","i","my","we","our","you","your","please","tell","me","about");
    private static final Pattern TOKENS = Pattern.compile("[a-z0-9]+|[\\p{IsHan}]+");
    public static List<String> tokens(String text) {
        var result=new ArrayList<String>();
        var matcher=TOKENS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token=matcher.group();
            if (token.codePointAt(0)>127) {
                int[] points=token.codePoints().toArray();
                if (points.length==1) result.add(token);
                else for(int i=0;i<points.length-1;i++) result.add(new String(points,i,2));
            } else if (!STOP.contains(token)) {
                // A small, explicit plural rule, not semantic expansion or general stemming.
                if (token.equals("ids")) token="id";
                else if (token.length()>3 && token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us")) token=token.substring(0,token.length()-1);
                result.add(token);
            }
        }
        return result;
    }
    public record Hit(Document document, Chunk chunk, double score, Set<String> terms) {}
    public record Result(List<Hit> hits, double coverage, List<String> missingTerms) {}
    private record Indexed(Document doc, Chunk chunk, Map<String,Integer> frequencies, int length) {}
    public Result retrieve(StoreState state, String question, List<String> ids, int topK) {
        Set<String> query=new LinkedHashSet<>(tokens(question));
        List<Indexed> index=new ArrayList<>();
        for (Document doc:state.documents()) {
            if (ids!=null && !ids.isEmpty() && !ids.contains(doc.id())) continue;
            for (Chunk chunk:doc.chunks()) {
                // Only body terms count as evidence. A matching title alone cannot substantiate a question.
                List<String> terms=tokens(chunk.text()); var freq=new HashMap<String,Integer>();
                terms.forEach(t->freq.merge(t,1,Integer::sum)); index.add(new Indexed(doc,chunk,freq,Math.max(1,terms.size())));
            }
        }
        Map<String,Integer> df=new HashMap<>();
        index.forEach(c->c.frequencies().keySet().forEach(t->df.merge(t,1,Integer::sum)));
        double average=index.stream().mapToInt(Indexed::length).average().orElse(1);
        List<Hit> hits=new ArrayList<>();
        for (Indexed c:index) {
            double score=0; Set<String> matched=new HashSet<>();
            for (String term:query) {
                int tf=c.frequencies().getOrDefault(term,0); if(tf==0) continue;
                matched.add(term);
                double idf=Math.log(1+(index.size()-df.get(term)+0.5)/(df.get(term)+0.5));
                double k1=RetrievalConfiguration.BM25_K1, b=RetrievalConfiguration.BM25_B;
                score+=idf*(tf*(k1+1))/(tf+k1*(1-b+b*c.length()/average));
            }
            if(score>0) hits.add(new Hit(c.doc(),c.chunk(),score,Set.copyOf(matched)));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(h->h.chunk().id()));
        hits=List.copyOf(hits.subList(0,Math.min(topK,hits.size())));
        Set<String> covered=new HashSet<>(); hits.forEach(h->covered.addAll(h.terms()));
        List<String> missing=query.stream().filter(t->!covered.contains(t)).toList();
        return new Result(hits,query.isEmpty()?0:(double)covered.size()/query.size(),missing);
    }
}
