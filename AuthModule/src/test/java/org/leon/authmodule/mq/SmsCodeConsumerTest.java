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

/**
 * SmsCodeConsumer 单元测试（纯 Mockito，不启动 Spring 容器）。
 * <p>
 * 验证两个核心契约：
 * 1. 收到发码消息后，写 Redis、模拟短信发送、投递延迟过期事件的完整链路。
 * 2. @RabbitListener 的队列名必须通过 SpEL 引用 Bean，确保测试/生产环境切换 queueSuffix 时不出错。
 */
@ExtendWith(MockitoExtension.class)
class SmsCodeConsumerTest {

    /** 模拟 Redis，验证验证码写入的 key/value/TTL */
    @Mock
    private StringRedisTemplate redisTemplate;

    /** Redis String 类型操作子接口，由 redisTemplate.opsForValue() 返回 */
    @Mock
    private ValueOperations<String, String> valueOperations;

    /** 短信发送抽象（项目中为 Mock 实现），验证是否被正确调用 */
    @Mock
    private SmsSender smsSender;

    /** 消息生产者，验证过期事件是否被按时投递到延迟队列 */
    @Mock
    private SmsCodeProducer producer;

    /**
     * 构建被测对象并注入 codeTtlMs（本测试设为 300 秒）。
     * 使用 ReflectionTestUtils 绕过 @Value 注解，避免加载 Spring 上下文。
     */
    private SmsCodeConsumer newConsumer() {
        SmsCodeConsumer consumer = new SmsCodeConsumer(redisTemplate, smsSender, producer);
        ReflectionTestUtils.setField(consumer, "codeTtlMs", 300000L);
        return consumer;
    }

    /**
     * 正向流程验证：onSmsSend 收到请求后应依次完成三件事——
     * ① 以 auth:sms:code:{phone} 为 key、6位随机数字为 value、codeTtlMs 为 TTL 写入 Redis；
     * ② 调用 SmsSender 模拟发送短信；
     * ③ 通过 Producer 投递一条包含相同验证码的延迟过期事件（消费者2 靠 code 做幂等比对）。
     */
    @DisplayName("收到发码消息：写 Redis 的 key 是 auth:sms:code:{phone}、TTL 等于 code-ttl-ms、码为 6 位数字，并模拟发送 + 投过期消息")
    @Test
    void onSmsSendStoresCodeSendsAndSchedulesExpiry() {
        // 准备：让 redisTemplate.opsForValue() 返回 mock 的 ValueOperations
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // 执行：触发消费者方法，传入测试手机号和链路追踪 ID
        newConsumer().onSmsSend(new SmsSendRequest("13800001234", "trace-9"));

        // 断言 ①：Redis 写入 — key 格式、value 为 6 位纯数字、TTL 匹配配置
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("auth:sms:code:13800001234"), code.capture(), eq(Duration.ofMillis(300000)));
        assertEquals(6, code.getValue().length());
        assertTrue(code.getValue().chars().allMatch(Character::isDigit));

        // 断言 ②：短信发送使用了刚生成的验证码
        verify(smsSender).send("13800001234", code.getValue());

        // 断言 ③：过期事件携带同一验证码和 traceId，保证下游幂等校验可通过
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
     * <p>
     * 实现方式：通过反射读取 @RabbitListener 注解的 queues 属性，断言值为 "#{smsSendQueue.name}"，
     * 这样无论 queueSuffix 如何切换，运行时都由 Spring 容器动态解析 Bean 名。
     */
    @DisplayName("监听注解用 SpEL 解析队列 Bean，队列名跟着 queueSuffix 走")
    @Test
    void listenerResolvesQueueThroughBeanNotConstant() throws NoSuchMethodException {
        // 反射获取 onSmsSend 方法上的 @RabbitListener 注解
        RabbitListener listener = SmsCodeConsumer.class
                .getMethod("onSmsSend", SmsSendRequest.class)
                .getAnnotation(RabbitListener.class);

        // 确保注解存在且只声明了一个队列
        assertTrue(listener != null && listener.queues().length == 1,
                "onSmsSend 必须只监听一个队列");
        // 核心断言：队列名必须是 SpEL 表达式，而非硬编码字符串常量
        assertEquals("#{smsSendQueue.name}", listener.queues()[0],
                "队列名必须是 SpEL Bean 引用，硬编码常量会忽略 queueSuffix");
    }
}
