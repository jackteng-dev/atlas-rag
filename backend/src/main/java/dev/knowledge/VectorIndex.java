package dev.knowledge;

import java.util.*;
import static dev.knowledge.Models.*;

public interface VectorIndex {
    record Point(Document document,Chunk chunk,List<Double> vector) {}
    record Match(String documentId,int version,String chunkId,double score) {}
    void replace(List<Point> points);
    List<Match> query(List<Double> vector,List<Document> authorizedDocuments,int limit);
}
