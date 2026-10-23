package org.leon.aimodule.rag.search;

public record KnowledgeSearchItem(long chunkId, long docId, int rank, double cosine,
                                  String title, String book, String chapterPath,
                                  Integer pageNo, String content) {
}
