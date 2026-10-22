package org.leon.aimodule.rag.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SpringAiEmbeddingClient implements EmbeddingClient {

    private final EmbeddingModel embeddingModel;

    public SpringAiEmbeddingClient(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embeddingModel.embed(texts);
    }
}
