package org.leon.authmodule.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 7 Step 8 的自动化版本：拓扑声明对不对，只认 Broker 自己的说法，不认"启动没报错"。
 * 所以先用 RabbitAdmin 强制声明，再直接查 15672 的管理 HTTP API。
 * <p>
 * 依赖本机 RabbitMQ（guest/guest 只允许 localhost 连）；Broker 没起时这个测试会红，
 * 那是正确 signal —— 说明这条链路根本没被验证过。
 */
@SpringBootTest
class CaptchaMqTopologyTest {

    private static final String MGMT = "http://127.0.0.1:15672/api";
    private static final String VHOST = "%2F";

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void declareTopology() {
        rabbitAdmin.initialize();
    }

    private JsonNode mgmtGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(MGMT + path))
                .header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "管理接口 " + path + " 返回 " + response.body());
        return objectMapper.readTree(response.body());
    }

    @Test
    @DisplayName("auth.sms.exchange 上有两条绑定：sms.send 与 sms.expire")
    void smsExchangeIsBoundToBothQueues() throws Exception {
        JsonNode bindings = mgmtGet("/exchanges/" + VHOST + "/auth.sms.exchange/bindings/source");

        String routingKeys = "";
        for (JsonNode b : bindings) {
            routingKeys = routingKeys + b.path("routing_key").asText() + "->" + b.path("destination").asText() + " ";
        }
        assertTrue(routingKeys.contains("sms.send->auth.sms.send.queue"),
                "缺发码绑定，实际: " + routingKeys);
        assertTrue(routingKeys.contains("sms.expire->auth.sms.expire.delay.queue"),
                "缺过期延迟绑定（spec §3.2 那个坑），实际: " + routingKeys);
        assertEquals(2, bindings.size(), "绑定数应为 2，实际: " + routingKeys);
    }

    @Test
    @DisplayName("延迟队列带 ttl 与死信参数，且没有任何消费者")
    void delayQueueHasTtlAndDlxAndNoConsumer() throws Exception {
        JsonNode queue = mgmtGet("/queues/" + VHOST + "/auth.sms.expire.delay.queue");
        JsonNode args = queue.path("arguments");

        assertEquals(300000, args.path("x-message-ttl").asInt(), "TTL 必须等于 auth.sms.code-ttl-ms");
        assertEquals("auth.sms.expire.dlx", args.path("x-dead-letter-exchange").asText());
        assertEquals("sms.expired", args.path("x-dead-letter-routing-key").asText());
        assertEquals(0, queue.path("consumer_details").size(),
                "延迟队列一旦被消费者盯着，消息就永远不会超时变死信");
    }

    @Test
    @DisplayName("死信交换机把 sms.expired 投给过期队列")
    void dlxBindsExpiredQueue() throws Exception {
        JsonNode bindings = mgmtGet("/exchanges/" + VHOST + "/auth.sms.expire.dlx/bindings/source");

        assertEquals(1, bindings.size());
        assertEquals("sms.expired", bindings.get(0).path("routing_key").asText());
        assertEquals("auth.sms.expired.queue", bindings.get(0).path("destination").asText());
    }
}
