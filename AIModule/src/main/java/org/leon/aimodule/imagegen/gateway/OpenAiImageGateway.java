package org.leon.aimodule.imagegen.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.chat.provider.BaseUrlGuard;
import org.leon.aimodule.imagegen.ImageContentTypes;
import org.leon.aimodule.imagegen.exception.ImageGenException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容生图：POST {baseUrl}/images/generations，response_format 固定要 b64_json，
 * 拿不到就直接下载 data[].url —— 但下载前要拦内网地址，否则上游返回一个
 * http://169.254.169.254/... 就能把服务端当跳板。
 */
@Slf4j
@Component
public class OpenAiImageGateway implements ImageGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Response generate(Command command) {
        long start = System.nanoTime();
        try {
            byte[] body = MAPPER.writeValueAsBytes(requestBody(command));
            Duration timeout = Duration.ofSeconds(Math.max(5, command.timeoutSeconds()));
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(command.baseUrl() + "/images/generations"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + command.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(new String(body, StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            long ms = elapsed(start);
            if (response.statusCode() / 100 != 2) {
                String text = new String(response.body(), StandardCharsets.UTF_8);
                throw new ImageGenException("生图上游返回 " + response.statusCode() + ": " + truncate(text));
            }
            List<GeneratedImage> images = parse(response.body(), command.timeoutSeconds());
            if (images.isEmpty()) {
                throw new ImageGenException("上游没有返回图片");
            }
            return new Response(images, ms);
        } catch (ImageGenException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageGenException("生图请求被中断", e);
        } catch (Exception e) {
            log.warn("[imagegen] 生图请求失败 upstream={} err={}", command.upstreamModel(), e.toString());
            throw new ImageGenException("生图请求失败: " + e.getClass().getSimpleName(), e);
        }
    }

    private static Map<String, Object> requestBody(Command command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", command.upstreamModel());
        body.put("prompt", command.prompt());
        body.put("n", command.n());
        body.put("response_format", "b64_json");
        if (command.size() != null && !command.size().isBlank()) {
            body.put("size", command.size().trim());
        }
        return body;
    }

    private List<GeneratedImage> parse(byte[] payload, int timeoutSeconds) throws IOException, InterruptedException {
        JsonNode root = MAPPER.readTree(payload);
        JsonNode data = root.path("data");
        List<GeneratedImage> out = new ArrayList<>();
        for (JsonNode item : data) {
            String b64 = text(item, "b64_json");
            if (!b64.isBlank()) {
                byte[] bytes = Base64.getDecoder().decode(b64);
                out.add(new GeneratedImage(bytes, ImageContentTypes.sniff(bytes)));
                continue;
            }
            String url = text(item, "url");
            if (!url.isBlank()) {
                byte[] bytes = download(url, timeoutSeconds);
                out.add(new GeneratedImage(bytes, ImageContentTypes.sniff(bytes)));
            }
        }
        return out;
    }

    private byte[] download(String url, int timeoutSeconds) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ImageGenException("上游返回的图片地址不可用");
        }
        String host = uri.getHost();
        if (host == null || BaseUrlGuard.isBlockedHost(host)) {
            throw new ImageGenException("上游返回的图片地址不可用");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new ImageGenException("下载上游图片失败: " + response.statusCode());
        }
        return response.body();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
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
}
