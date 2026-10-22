package org.leon.aimodule.rag.embedding;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.exception.EmbeddingException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全项目唯一的向量出口。百炼单次最多 20 条，超过直接 400；
 * batchSize=10 是硬性分批，不是性能优化。
 */
@Slf4j
@Component
public class EmbeddingGateway {

    private static final int MAX_ATTEMPTS = 3;

    private final EmbeddingClient client;
    private final RagProperties props;
    private final ExecutorService executor;
    private final AtomicLong callCounter = new AtomicLong();

    public EmbeddingGateway(EmbeddingClient client, RagProperties props) {
        this.client = client;
        this.props = props;
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, props.getEmbedding().getConcurrency()),
                r -> {
                    Thread t = new Thread(r, "rag-embedding");
                    t.setDaemon(true);
                    return t;
                });
    }

    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int batchSize = Math.max(1, props.getEmbedding().getBatchSize());
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < texts.size(); from += batchSize) {
            batches.add(texts.subList(from, Math.min(from + batchSize, texts.size())));
        }

        List<CompletableFuture<List<float[]>>> futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(() -> embedBatch(batch), executor))
                .toList();

        List<float[]> vectors = new ArrayList<>(texts.size());
        try {
            for (CompletableFuture<List<float[]>> future : futures) {
                vectors.addAll(future.join());
            }
        } catch (CompletionException e) {
            futures.forEach(f -> f.cancel(true));
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof EmbeddingException embeddingException) {
                throw embeddingException;
            }
            throw new EmbeddingException("embedding 调用失败", cause);
        }
        return vectors;
    }

    /** 阶段 4b 的幂等测试用它断言“重复消息没有产生新的 embedding 调用” */
    public long callCount() {
        return callCounter.get();
    }

    private List<float[]> embedBatch(List<String> batch) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                callCounter.incrementAndGet();
                List<float[]> vectors = client.embed(batch);
                assertShape(batch, vectors);
                return vectors;
            } catch (EmbeddingException e) {
                // 维度/条数不符是契约错误，重试不会变好，立即失败
                throw e;
            } catch (RuntimeException e) {
                last = e;
                log.warn("[RAG] embedding 第 {}/{} 次失败，batchSize={}: {}",
                        attempt, MAX_ATTEMPTS, batch.size(), e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(500L << (attempt - 1));
                }
            }
        }
        throw new EmbeddingException("embedding 调用失败，已重试 " + MAX_ATTEMPTS + " 次", last);
    }

    private void assertShape(List<String> batch, List<float[]> vectors) {
        int dims = props.getEmbedding().getDimensions();
        if (vectors == null || vectors.size() != batch.size()) {
            throw new EmbeddingException("embedding 返回条数不符: 期望 " + batch.size()
                    + "，实际 " + (vectors == null ? 0 : vectors.size()));
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length != dims) {
                throw new EmbeddingException("embedding 维度不符: 期望 " + dims
                        + "，实际 " + (vector == null ? 0 : vector.length));
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("embedding 重试被中断", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
