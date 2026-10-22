package org.leon.aimodule.rag.embedding;

import java.util.List;

public interface EmbeddingClient {
    List<float[]> embed(List<String> texts);
}
