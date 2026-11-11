package dev.knowledge;

import java.util.*;
import static dev.knowledge.Models.*;

public interface RagModels {
    record Ranked(int index,double score) {}
    record Generation(List<GeneratedStatement> statements,Integer inputTokens,Integer outputTokens) {}
    List<List<Double>> embed(List<String> texts);
    List<Ranked> rerank(String question,List<String> texts);
    Generation generate(String question,List<Source> sources);
    /** quality=true uses the optional larger model; implementations without one fall back to the default. */
    default Generation generate(String question,List<Source> sources,boolean quality) { return generate(question,sources); }
    default boolean hasQuality() { return false; }
}
