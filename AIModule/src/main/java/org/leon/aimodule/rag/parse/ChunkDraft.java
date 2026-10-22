package org.leon.aimodule.rag.parse;

public record ChunkDraft(int seq, String content, int tokenCount,
                         Integer pageNo, String book, String chapterPath) {
}
