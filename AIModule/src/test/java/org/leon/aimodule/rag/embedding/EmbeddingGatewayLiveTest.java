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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("embedding-it")
class EmbeddingGatewayLiveTest {

    @Test
    @DisplayName("百炼 text-embedding-v3 返回 1024 维，相近句高于 0.7、无关句低于 0.55 且两者差值大于 0.2")
    void realDashScope() {
        String apiKey = secret("DASHSCOPE_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "需要 DASHSCOPE_API_KEY：系统环境变量或 config/local.properties 二选一");

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

        System.out.printf("cosine(相近)=%.4f cosine(无关)=%.4f 差值=%.4f%n",
                related, unrelated, related - unrelated);
        assertTrue(related > 0.7, "相近句相似度应 > 0.7，实际 " + related);
        // 0.55 与生产配置 rag.search.knn-similarity 的截断值一致
        assertTrue(unrelated < 0.55, "无关句相似度应 < 0.55，实际 " + unrelated);
        assertTrue(related - unrelated > 0.2,
                "相近句与无关句相似度差值应 > 0.2，实际 " + (related - unrelated));
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

    // 与 Spring Boot 的取值顺序保持一致：同名系统环境变量优先，其次才是 gitignore 掉的本地密钥文件
    private static String secret(String name) {
        String fromEnv = System.getenv(name);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        List<Path> candidates = List.of(Path.of("config", "local.properties"),
                Path.of("..", "config", "local.properties"));
        for (Path candidate : candidates) {
            Properties props = new Properties();
            try (BufferedReader reader = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException | UncheckedIOException e) {
                continue;
            }
            String value = props.getProperty(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
