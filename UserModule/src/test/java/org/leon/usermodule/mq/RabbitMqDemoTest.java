package org.leon.usermodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.leon.usermodule.config.RabbitMqDemoConfig;
import org.leon.usermodule.pojo.mq.UserRegisterMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 运行前提：本机 RabbitMQ 已启动（默认 127.0.0.1:5672，guest/guest）
 */
@Slf4j
@SpringBootTest
class RabbitMqDemoTest {

    @Autowired
    private UserRegisterProducer producer;

    @Autowired
    private UserRegisterConsumer consumer;

    @Test
    void registerMessageIsConsumedByBothQueues() throws InterruptedException {
        UserRegisterMessage message = new UserRegisterMessage(1001L, "demoUser", LocalDateTime.now());

        producer.send(message, RabbitMqDemoConfig.EMAIL_ROUTING_KEY);
        UserRegisterMessage emailResult = consumer.pollEmailMessage(5, TimeUnit.SECONDS);
        assertNotNull(emailResult, "5 秒内邮件消费者没收到：检查 RabbitMQ 是否启动、路由键是否匹配");
        assertEquals("demoUser", emailResult.getUsername());
        assertEquals(message.getRegisterTime().withNano(0), emailResult.getRegisterTime().withNano(0));

        producer.send(message, RabbitMqDemoConfig.SMS_ROUTING_KEY);
        assertNotNull(consumer.pollSmsMessage(5, TimeUnit.SECONDS), "短信消费者应靠 user.*.sms 通配符命中");
    }

    @Test
    void messageWithUnboundRoutingKeyIsDropped() throws InterruptedException {
        producer.send(new UserRegisterMessage(1002L, "ghostUser", LocalDateTime.now()), "user.delete.phone");

        assertNull(consumer.pollEmailMessage(2, TimeUnit.SECONDS), "user.delete.phone 不该命中邮件队列的绑定");
        assertNull(consumer.pollSmsMessage(2, TimeUnit.SECONDS), "user.delete.phone 也不该命中 user.*.sms");
    }
}
