package org.leon.usermodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.usermodule.config.RabbitMqDemoConfig;
import org.leon.usermodule.pojo.mq.UserRegisterMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 发送端：只管把消息丢给交换机，不关心有几个消费者
 */
@Slf4j
@Component
public class UserRegisterProducer {

    private final RabbitTemplate rabbitTemplate;

    public UserRegisterProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void send(UserRegisterMessage message, String routingKey) {
        log.info("[生产者] 准备发送 routingKey={} 消息={}", routingKey, message);
        rabbitTemplate.convertAndSend(RabbitMqDemoConfig.USER_EXCHANGE, routingKey, message);
    }
}
