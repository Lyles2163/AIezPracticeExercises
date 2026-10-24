package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 假上游用 JDK 自带的 HttpServer：不引 MockWebServer，也不打真接口花钱。
 * 帧内容是本机实测录下的 DeepSeek 响应（含 data: 后那个空格与 system_fingerprint）。
 */
class OpenAiStreamChatModelGatewayTest {

    private HttpServer server;
    private ChatProperties props;
    private OpenAiStreamChatModelGateway gateway;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        props = new ChatProperties();
        props.setUpstreamIdleTimeoutSeconds(2);
        props.setApiKey("sk-test-for-fake-upstream");
        gateway = new OpenAiStreamChatModelGateway(props, new ObjectMapper());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 把 body 拆成不定长的小块写出去，专门用来验证半帧拼接。 */
    private void serve(String body, int status, int chunkSize) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] all = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(status, status == 200 ? 0 : all.length);
            if (status != 200) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(all);
                }
                return;
            }
            try (OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < all.length; i += chunkSize) {
                    os.write(all, i, Math.min(chunkSize, all.length - i));
                    os.flush();
                }
            }
        });
        server.start();
        gateway.setBaseUrlForTest("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private List<ChatModelGateway.ChatChunk> collect() {
        return gateway.stream(List.of(new CtxMessage("user", "hi")),
                new ModelParams("deepseek-reasoner", 1.0, 4096, true)).collectList().block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("请求体与头：stream、stream_options.include_usage、Bearer 都在")
    void requestShape() throws Exception {
        serve("data: [DONE]\n\n", 200, 64);
        collect();
        assertTrue(lastBody.get().contains("\"stream\":true"), lastBody.get());
        assertTrue(lastBody.get().contains("\"stream_options\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"include_usage\":true"), lastBody.get());
        assertTrue(lastBody.get().contains("\"model\":\"deepseek-reasoner\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"max_tokens\":4096"), lastBody.get());
        assertEquals("Bearer " + props.getApiKey(), lastAuth.get());
    }

    @Test
    @DisplayName("真实录制的帧序列：63 reasoning + 26 content + 尾帧，半帧拼接也不丢")
    void streamsRecordedFramesWithTornChunks() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 63; i++) {
            body.append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":null,")
                    .append("\"reasoning_content\":\"r").append(i).append("\"},\"finish_reason\":null}]}\n\n");
        }
        for (int i = 0; i < 26; i++) {
            body.append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"c").append(i)
                    .append("\"},\"finish_reason\":null}]}\n\n");
        }
        body.append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},")
                .append("\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":90,")
                .append("\"completion_tokens_details\":{\"reasoning_tokens\":63}}}\n\n");
        body.append("data: [DONE]\n\n");
        serve(body.toString(), 200, 7);   // 7 字节一片，必然把帧劈开

        List<ChatModelGateway.ChatChunk> chunks = collect();
        assertEquals(90, chunks.size(), "63 reasoning + 26 content + 1 尾帧");
        assertEquals("r0", chunks.get(0).reasoning());
        assertEquals("c0", chunks.get(63).text());
        ChatModelGateway.ChatChunk last = chunks.get(89);
        assertEquals("stop", last.finishReason());
        assertEquals(63, last.reasoningTokens());
    }

    @Test
    @DisplayName("上游 401 → UpstreamStatusException(401)，供编排层判不 retryable")
    void unauthorizedMapsToStatusException() throws Exception {
        serve("{\"error\":{\"message\":\"Authenticated Failed\"}}", 401, 64);
        UpstreamStatusException use = cause(UpstreamStatusException.class);
        assertEquals(401, use.status());
        assertTrue(use.getMessage().contains("Authenticated"), use.getMessage());
    }

    @Test
    @DisplayName("上游 503 → 同一个异常类型，status 带着走")
    void serverErrorKeepsStatus() throws Exception {
        serve("{\"error\":{\"message\":\"Too Many Requests\"}}", 503, 64);
        assertEquals(503, cause(UpstreamStatusException.class).status());
    }

    @Test
    @DisplayName("上游只出一帧后卡住 → 空闲超时抛 TimeoutException")
    void idleTimeoutFires() {
        props.setUpstreamIdleTimeoutSeconds(1);
        streamStalledUpstream();
        assertInstanceOf(TimeoutException.class, cause(TimeoutException.class));
    }

    /**
     * block() 会把 checked 异常包进 ReactiveException，所以断言要顺着 cause 链找，
     * 不能 assertThrows(TimeoutException.class, ...) —— 那会红得让人误判成实现错了。
     */
    private <T> T cause(Class<T> type) {
        try {
            collect();
            throw new AssertionError("期望抛错，但流正常结束");
        } catch (Throwable t) {
            for (Throwable cur = t; cur != null; cur = cur.getCause()) {
                if (type.isInstance(cur)) {
                    return type.cast(cur);
                }
            }
            throw new AssertionError("异常链里没有 " + type.getSimpleName() + "：" + t, t);
        }
    }

    private void streamStalledUpstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", ex -> {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}]}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    Thread.sleep(5000);
                } catch (Exception ignored) {
                }
            });
            server.start();
            gateway.setBaseUrlForTest("http://127.0.0.1:" + server.getAddress().getPort());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
