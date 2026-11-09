package dev.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import static dev.knowledge.Models.*;

@Component
public class MarkdownChunker {
    public static final int MAX_CHARS = 800;
    private final int maxChars;
    private final SemanticBoundaryMerger semantic;
    public MarkdownChunker() { this(RetrievalConfiguration.baseline()); }
    public MarkdownChunker(RetrievalConfiguration configuration) { this.maxChars=configuration.chunkMaxChars();this.semantic=null; }
    @org.springframework.beans.factory.annotation.Autowired
    public MarkdownChunker(RetrievalConfiguration configuration,
            @org.springframework.beans.factory.annotation.Value("${knowledge.chunk-strategy:structural}") String strategy,
            @org.springframework.beans.factory.annotation.Value("${knowledge.rag.enabled:false}") boolean modelsAllowed,
            @org.springframework.beans.factory.annotation.Value("${knowledge.rag.embedding-url:http://127.0.0.1:18112}") String url,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.maxChars=configuration.chunkMaxChars();
        if(!Set.of("structural","semantic").contains(strategy)) throw new IllegalArgumentException("Unknown chunk strategy");
        if(strategy.equals("semantic") && !modelsAllowed) throw new IllegalArgumentException("Semantic chunking requires explicit local RAG opt-in");
        semantic=strategy.equals("semantic")?new SemanticBoundaryMerger(new LlamaRagModels(url,url,url,mapper),maxChars):null;
    }
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*\\s*$");
    public static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public List<Chunk> split(String documentId, String markdown) {
        var chunks = new ArrayList<Chunk>();
        var occurrences = new HashMap<String,Integer>();
        String[] lines = markdown.replace("\r\n", "\n").replace('\r','\n').split("\n", -1);
        String heading = "Introduction";
        StringBuilder block = new StringBuilder();
        int start=1, end=1;
        boolean fence=false;
        for (int i=0;i<lines.length;i++) {
            String line=lines[i];
            var match=HEADING.matcher(line);
            if (!fence && match.matches()) {
                emit(chunks, occurrences, documentId, heading, block, start, end);
                heading=match.group(1);
                continue;
            }
            if (!fence && line.isBlank()) {
                emit(chunks, occurrences, documentId, heading, block, start, end);
                continue;
            }
            if (line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~")) fence=!fence;
            // Even oversized individual lines are split. Line numbers still point at the original source.
            for (int offset=0; offset<line.length() || (offset==0 && line.isEmpty());) {
                int capacity=maxChars-block.length()-(block.isEmpty()?0:1);
                if (capacity<=0) { emit(chunks, occurrences, documentId, heading, block, start, end); continue; }
                int take=Math.min(capacity, line.length()-offset);
                if (take>0 && offset+take<line.length() && Character.isHighSurrogate(line.charAt(offset+take-1))
                        && Character.isLowSurrogate(line.charAt(offset+take))) take--;
                if(take==0 && offset<line.length()) { emit(chunks, occurrences, documentId, heading, block, start, end); continue; }
                if (block.isEmpty()) start=i+1; else block.append('\n');
                block.append(line, offset, offset+take); end=i+1; offset+=take;
                if (block.length()>=maxChars) emit(chunks, occurrences, documentId, heading, block, start, end);
                if (line.isEmpty()) break;
            }
        }
        emit(chunks, occurrences, documentId, heading, block, start, end);
        if (chunks.isEmpty()) throw new ApiException(400,"EMPTY_DOCUMENT","Markdown must contain body text, not just headings.");
        return semantic==null?List.copyOf(chunks):semantic.merge(documentId,List.copyOf(chunks),markdown);
    }
    private void emit(List<Chunk> chunks, Map<String,Integer> occurrences, String doc, String heading,
                      StringBuilder block, int start, int end) {
        if (!block.toString().isBlank()) {
            String body=block.toString();
            String fingerprint=hash(doc+"\n"+heading+"\n"+body);
            int occurrence=occurrences.merge(fingerprint,1,Integer::sum);
            chunks.add(new Chunk(doc+"-"+fingerprint.substring(0,16)+"-"+occurrence,heading,body,start,end));
        }
        block.setLength(0);
    }
}
