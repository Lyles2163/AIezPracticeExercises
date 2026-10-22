package org.leon.aimodule.rag.embedding;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.config.RagProperties;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("embedding-it")
class EmbeddingGatewayLiveTest {

    @Test
    @DisplayName("百炼 text-embedding-v3 返回 1024 维，相近句相似度高于无关句")
    void realDashScope() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "需要 DASHSCOPE_API_KEY");

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
                .apiKey(apiKey)
                .build();
        OpenAiEmbeddingModel model = new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model("text-embedding-v3")
                        .dimensions(1024)
                        .build());

        RagProperties props = new RagProperties();
        props.getEmbedding().setDimensions(1024);
        props.getEmbedding().setBatchSize(10);
        props.getEmbedding().setConcurrency(2);
        EmbeddingGateway gateway = new EmbeddingGateway(new SpringAiEmbeddingClient(model), props);

        List<float[]> vectors = gateway.embed(List.of(
                "快速排序的时间复杂度",
                "快排的复杂度是多少",
                "火车相遇问题怎么求相遇时间"));

        assertEquals(3, vectors.size());
        assertEquals(1024, vectors.get(0).length);
        double related = cosine(vectors.get(0), vectors.get(1));
        double unrelated = cosine(vectors.get(0), vectors.get(2));

        System.out.printf("cosine(相近)=%.4f cosine(无关)=%.4f%n", related, unrelated);
        assertTrue(related > 0.7, "相近句相似度应 > 0.7，实际 " + related);
        assertTrue(unrelated < 0.4, "无关句相似度应 < 0.4，实际 " + unrelated);
        gateway.shutdown();
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
