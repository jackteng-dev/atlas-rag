package dev.knowledge;

import java.util.List;
import static dev.knowledge.Models.*;

public class OfflineExcerptProvider implements ExcerptProvider {
    public String mode() { return "OFFLINE_EXTRACTIVE"; }
    public ProviderResult select(String question,List<Source> evidence) {
        return new ProviderResult(evidence.stream().map(s->new Segment(s.chunkId(),s.excerpt())).toList(),null,null);
    }
}
