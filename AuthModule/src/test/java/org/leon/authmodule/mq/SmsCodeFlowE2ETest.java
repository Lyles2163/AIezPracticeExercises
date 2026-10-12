package org.leon.authmodule.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.authmodule.config.AuthCacheKeys;
import org.leon.authmodule.config.CaptchaMqConfig;
import org.leon.authmodule.pojo.mq.SmsSendRequest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 8 Step 6 的自动化版本：往 Broker 真投一条 sms.send，看它有没有被消费者接走。
 * <p>
 * 计划里这一步是"去管理界面手动 Publish 然后盯控制台"，这里换成断言，理由是控制台日志
 * 不能当验收证据。三条硬证据：
 * 1. Redis 里出现了 auth:sms:code:{phone}，值是 6 位数字（说明消费者真的跑了）；
 * 2. 写这个 key 的不是测试线程——测试线程只发了一条消息，从没碰过 Redis，
 *    这一条就是"异步"本身；
 * 3. 延迟队列的 Ready 数 +1，且它 consumer=0（过期消息正在躺 TTL，没人能提前吃掉）。
 */
@SpringBootTest
class SmsCodeFlowE2ETest {

    private static final String PHONE = "13800005678";
    private static final String MGMT = "http://127.0.0.1:15672/api";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(AuthCacheKeys.smsCodeKey(PHONE));
    }

    private long delayQueueReady() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(MGMT + "/queues/%2F/" + CaptchaMqConfig.SMS_EXPIRE_DELAY_QUEUE))
                .header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8)))
                .GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode queue = objectMapper.readTree(response.body());
        return queue.path("messages_ready").asLong();
    }

    /** 轮询等异步链路跑完；返回 null 表示超时 */
    private String awaitValueOperations(String key, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                return value;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        return null;
    }

    @Test
    @DisplayName("投一条 sms.send：消费者异步生成码写进 Redis，并把过期事件停进无消费者的延迟队列")
    void publishTriggersAsyncConsumerAndParksExpiry() throws Exception {
        redisTemplate.delete(AuthCacheKeys.smsCodeKey(PHONE));
        long readyBefore = delayQueueReady();

        rabbitTemplate.convertAndSend(CaptchaMqConfig.SMS_EXCHANGE, CaptchaMqConfig.SMS_SEND_KEY,
                new SmsSendRequest(PHONE, "e2e-1"));

        String code = awaitValueOperations(AuthCacheKeys.smsCodeKey(PHONE), 10_000);
        assertNotNull(code, "10 秒内 Redis 没出现验证码，说明消费者没接到消息");
        assertEquals(6, code.length(), "必须是 6 位数字码，实际: " + code);
        assertTrue(code.chars().allMatch(Character::isDigit), "码里混进了非数字: " + code);

        Long ttl = redisTemplate.getExpire(AuthCacheKeys.smsCodeKey(PHONE), TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 300, "Redis TTL 应跟 auth.sms.code-ttl-ms 一致（300 秒内），实际: " + ttl);

        long readyAfter = -1;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            readyAfter = delayQueueReady();
            if (readyAfter >= readyBefore + 1) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        assertTrue(readyAfter >= readyBefore + 1,
                "延迟队列没收到过期事件（ready " + readyBefore + " -> " + readyAfter + "），说明 sms.expire 那条绑定或生产者没跑通");
    }
}
