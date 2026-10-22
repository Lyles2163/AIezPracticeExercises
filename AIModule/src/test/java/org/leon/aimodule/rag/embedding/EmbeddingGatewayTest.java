package org.leon.aimodule.rag.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.exception.EmbeddingException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingGatewayTest {

    private RagProperties props(int batchSize) {
        RagProperties props = new RagProperties();
        props.getEmbedding().setBatchSize(batchSize);
        props.getEmbedding().setConcurrency(2);
        props.getEmbedding().setDimensions(4);
        return props;
    }

    private float[] vec() {
        return new float[]{0.1f, 0.2f, 0.3f, 0.4f};
    }

    @Test
    @DisplayName("21 条文本切成 10/10/1 三批，顺序保持")
    void batchesAtMostTen() {
        AtomicInteger batches = new AtomicInteger();
        EmbeddingClient fake = texts -> {
            batches.incrementAndGet();
            List<float[]> out = new ArrayList<>();
            for (String text : texts) {
                float[] v = vec();
                v[0] = Integer.parseInt(text.replace("t", "")) / 100f;
                out.add(v);
            }
            return out;
        };
        EmbeddingGateway gateway = new EmbeddingGateway(fake, props(10));

        List<String> texts = IntStream.range(0, 21).mapToObj(i -> "t" + i).toList();
        List<float[]> vectors = gateway.embed(texts);

        assertEquals(21, vectors.size());
        assertEquals(3, batches.get());
        assertEquals(3, gateway.callCount());
        assertEquals(0.00f, vectors.get(0)[0], 0.0001f);
        assertEquals(0.20f, vectors.get(20)[0], 0.0001f);
        gateway.shutdown();
    }

    @Test
    @DisplayName("维度不符立即失败")
    void rejectsWrongDimensions() {
        EmbeddingClient fake = texts -> List.of(new float[]{1f, 2f});
        EmbeddingGateway gateway = new EmbeddingGateway(fake, props(10));

        EmbeddingException e = assertThrows(EmbeddingException.class,
                () -> gateway.embed(List.of("a")));
        assertTrue(e.getMessage().contains("维度"));
        gateway.shutdown();
    }

    @Test
    @DisplayName("首次失败后重试成功")
    void retriesTransientFailure() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingClient fake = texts -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("网络抖动");
            }
            return List.of(vec());
        };
        EmbeddingGateway gateway = new EmbeddingGateway(fake, props(10));

        List<float[]> vectors = gateway.embed(List.of("a"));

        assertEquals(1, vectors.size());
        assertEquals(2, calls.get());
        gateway.shutdown();
    }

    @Test
    @DisplayName("空输入不发请求")
    void emptyInput() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingClient fake = texts -> {
            calls.incrementAndGet();
            return List.of();
        };
        EmbeddingGateway gateway = new EmbeddingGateway(fake, props(10));

        assertTrue(gateway.embed(List.of()).isEmpty());
        assertEquals(0, calls.get());
        gateway.shutdown();
    }
}
