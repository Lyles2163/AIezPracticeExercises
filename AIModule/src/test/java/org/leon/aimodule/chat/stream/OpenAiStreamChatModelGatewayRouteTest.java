package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.provider.ProviderRoute;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiStreamChatModelGatewayRouteTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("带 route 时用用户的 baseUrl、Bearer 与上游模型名，路径只拼一次 /chat/completions")
    void routeOverridesUpstream() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        ChatProperties props = new ChatProperties();
        OpenAiStreamChatModelGateway gateway = new OpenAiStreamChatModelGateway(props, new ObjectMapper());
        ProviderRoute route = new ProviderRoute(1L, 2L, "中转", "小模型", base,
                "sk-user-key", "gpt-4o-mini", false);

        List<ChatModelGateway.ChatChunk> chunks = gateway.stream(
                        List.of(new CtxMessage("user", "你好")),
                        new ModelParams("u:1:2", 1.0, 64, false), null, null, route)
                .collectList().block();

        assertEquals("Bearer sk-user-key", auth.get());
        assertEquals("/v1/chat/completions", path.get());
        assertTrue(body.get().contains("\"model\":\"gpt-4o-mini\""));
        assertTrue(chunks != null && chunks.stream().anyMatch(c -> "hi".equals(c.text())));
    }
}
