package org.leon.aimodule.rag.search;

/** cosine 只有 kNN 腿召回的命中才有值；ES 9.5 不把 dense_vector 留在 _source，纯 BM25 命中算不出余弦。 */
public record KnowledgeSearchItem(long chunkId, long docId, int rank, Double cosine,
                                  String title, String book, String chapterPath,
                                  Integer pageNo, String content) {
}
