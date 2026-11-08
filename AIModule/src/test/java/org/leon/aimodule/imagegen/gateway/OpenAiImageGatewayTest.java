package org.leon.aimodule.imagegen.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.imagegen.exception.ImageGenException;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiImageGatewayTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5};

    private HttpServer server;
    private final OpenAiImageGateway gateway = new OpenAiImageGateway();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("b64_json 直接解码，类型靠魔数嗅探")
    void decodesBase64Payload() throws Exception {
        String body = "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG) + "\"}]}";
        String baseUrl = serve(200, body);

        ImageGateway.Response response = gateway.generate(command(baseUrl));

        assertEquals(1, response.images().size());
        assertArrayEquals(PNG, response.images().get(0).data());
        assertEquals("image/png", response.images().get(0).contentType());
    }

    @Test
    @DisplayName("非 2xx 只回状态码 + 上游错误体前 200 字符，不带 key")
    void mapsUpstreamError() throws Exception {
        String baseUrl = serve(401, "{\"error\":\"bad key\"}");

        ImageGenException e = assertThrows(ImageGenException.class,
                () -> gateway.generate(command(baseUrl)));

        assertTrue(e.getMessage().startsWith("生图上游返回 401"));
        assertTrue(!e.getMessage().contains("sk-secret"));
    }

    @Test
    @DisplayName("回退 url 时拦内网地址，别把服务端当跳板")
    void rejectsInternalImageUrl() throws Exception {
        String baseUrl = serve(200,
                "{\"data\":[{\"url\":\"http://127.0.0.1:9999/a.png\"}]}");

        ImageGenException e = assertThrows(ImageGenException.class,
                () -> gateway.generate(command(baseUrl)));

        assertEquals("上游返回的图片地址不可用", e.getMessage());
    }

    @Test
    @DisplayName("上游返回空 data 直接报错，不返回空列表让上层以为成功")
    void emptyDataFails() throws Exception {
        String baseUrl = serve(200, "{\"data\":[]}");

        assertEquals("上游没有返回图片",
                assertThrows(ImageGenException.class, () -> gateway.generate(command(baseUrl))).getMessage());
    }

    private ImageGateway.Command command(String baseUrl) {
        return new ImageGateway.Command(baseUrl, "sk-secret", "dall-e-3", "一只猫", "1024x1024", 1, 10);
    }

    private String serve(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/images/generations", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
