package org.leon.aimodule.chat.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * "测试连接"：用一个极便宜的请求探活。
 * 聊天模型打 /chat/completions（不列 /models：不少兼容端点不实现它，而 /chat/completions 一定实现）；
 * 生图 / RAG 模型只发 GET /models —— 对生图端点发一次真生成是花钱的，
 * 对 embedding 端点发 /chat/completions 则必然 400。
 * 失败原因只回短文案 + 上游错误体前 200 字符，绝不回请求头或 key。
 */
@Slf4j
@Component
public class ProviderProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderProperties properties;

    public ProviderProbe(ProviderProperties properties) {
        this.properties = properties;
    }

    public Result probe(String baseUrl, String apiKey, String model) {
        long start = System.nanoTime();
        try {
            HttpRequest request = builder(properties.getProbeTimeoutSeconds(), baseUrl + "/chat/completions", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(chatBody(model), StandardCharsets.UTF_8))
                    .build();
            return send(request, start, baseUrl);
        } catch (Exception e) {
            return failure(start, baseUrl, e);
        }
    }

    /** 生图 / RAG 供应商的探活：只列模型，不产生生成费用。 */
    public Result probeModels(String baseUrl, String apiKey) {
        long start = System.nanoTime();
        try {
            HttpRequest request = builder(properties.getProbeTimeoutSeconds(), baseUrl + "/models", apiKey)
                    .GET()
                    .build();
            return send(request, start, baseUrl);
        } catch (Exception e) {
            return failure(start, baseUrl, e);
        }
    }

    static HttpRequest.Builder builder(int timeoutSeconds, String url, String apiKey) {
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json");
    }

    private Result send(HttpRequest request, long start, String baseUrl) throws Exception {
        Duration timeout = Duration.ofSeconds(Math.max(1, properties.getProbeTimeoutSeconds()));
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long ms = elapsed(start);
        if (response.statusCode() / 100 == 2) {
            return new Result(true, ms, "连接成功");
        }
        return new Result(false, ms, "上游返回 " + response.statusCode() + ": " + truncate(response.body()));
    }

    private Result failure(long start, String baseUrl, Exception e) {
        log.warn("[provider] 探测失败 baseUrl={} err={}", baseUrl, e.getClass().getSimpleName());
        return new Result(false, elapsed(start), "连接失败: " + e.getClass().getSimpleName());
    }

    private static String chatBody(String model) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", 1,
                "stream", false));
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    public record Result(boolean ok, long latencyMs, String message) {
    }
}
