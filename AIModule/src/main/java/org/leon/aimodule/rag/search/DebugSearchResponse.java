package org.leon.aimodule.rag.search;

import java.util.List;

public record DebugSearchResponse(int took, List<DebugLegItem> bm25, List<DebugLegItem> knn) {
    public DebugSearchResponse {
        bm25 = List.copyOf(bm25);
        knn = List.copyOf(knn);
    }
}
