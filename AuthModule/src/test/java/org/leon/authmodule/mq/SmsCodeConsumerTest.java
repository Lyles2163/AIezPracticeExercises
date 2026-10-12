package org.leon.authmodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.pojo.mq.SmsExpireEvent;
import org.leon.authmodule.pojo.mq.SmsSendRequest;
import org.leon.authmodule.service.SmsSender;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmsCodeConsumerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SmsSender smsSender;

    @Mock
    private SmsCodeProducer producer;

    private SmsCodeConsumer newConsumer() {
        SmsCodeConsumer consumer = new SmsCodeConsumer(redisTemplate, smsSender, producer);
        ReflectionTestUtils.setField(consumer, "codeTtlMs", 300000L);
        return consumer;
    }

    @DisplayName("收到发码消息：写 Redis 的 key 是 auth:sms:code:{phone}、TTL 等于 code-ttl-ms、码为 6 位数字，并模拟发送 + 投过期消息")
    @Test
    void onSmsSendStoresCodeSendsAndSchedulesExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        newConsumer().onSmsSend(new SmsSendRequest("13800001234", "trace-9"));

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("auth:sms:code:13800001234"), code.capture(), eq(Duration.ofMillis(300000)));
        assertEquals(6, code.getValue().length());
        assertTrue(code.getValue().chars().allMatch(Character::isDigit));

        verify(smsSender).send("13800001234", code.getValue());

        ArgumentCaptor<SmsExpireEvent> event = ArgumentCaptor.forClass(SmsExpireEvent.class);
        verify(producer).sendExpireDelay(event.capture());
        assertEquals("13800001234", event.getValue().getPhone());
        assertEquals(code.getValue(), event.getValue().getCode(), "过期事件必须带同一个码，消费者2 靠它做幂等比对");
        assertEquals("trace-9", event.getValue().getTraceId());
    }

    /**
     * 队列名必须走 SpEL 引用 Bean，不能写死常量：常量绑的是"不带 queueSuffix"的名字，
     * 测试环境声明的是 -test 队列，两边各抢一半消息，症状是偶发红。
     * 这条断言就是防止有人把它改回 CaptchaMqConfig.SMS_SEND_QUEUE。
     */
    @DisplayName("监听注解用 SpEL 解析队列 Bean，队列名跟着 queueSuffix 走")
    @Test
    void listenerResolvesQueueThroughBeanNotConstant() throws NoSuchMethodException {
        RabbitListener listener = SmsCodeConsumer.class
                .getMethod("onSmsSend", SmsSendRequest.class)
                .getAnnotation(RabbitListener.class);

        assertTrue(listener != null && listener.queues().length == 1,
                "onSmsSend 必须只监听一个队列");
        assertEquals("#{smsSendQueue.name}", listener.queues()[0],
                "队列名必须是 SpEL Bean 引用，硬编码常量会忽略 queueSuffix");
    }
}
