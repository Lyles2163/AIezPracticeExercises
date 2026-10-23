package org.leon.aimodule.rag.search;

import java.util.List;

public record KnowledgeSearchResponse(int took, boolean empty, List<KnowledgeSearchItem> items) {
    public KnowledgeSearchResponse {
        items = List.copyOf(items);
    }
}
