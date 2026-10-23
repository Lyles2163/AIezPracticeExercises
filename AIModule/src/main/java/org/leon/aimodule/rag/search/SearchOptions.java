package org.leon.aimodule.rag.search;

import java.util.List;

public record SearchOptions(String ownerName, List<String> scopes, Long subjectId, int topK) {
    public SearchOptions {
        scopes = List.copyOf(scopes);
    }
}
