package org.leon.aimodule.rag.search;

public record DebugLegItem(long chunkId, long docId, int rank, Double score, Double cosine,
                           String title, String content) {
}
