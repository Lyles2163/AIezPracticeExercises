package org.leon.usermodule.config;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

/**
 * "重试也没用"的异常不参与退避：404 直接进死信，而不是先白等 3 次。
 * 只改监听容器的重试策略，其它异常仍按 spring.rabbitmq.listener.simple.retry 配置重试。
 */
@Configuration
public class LoginRetryConfig {

    @Bean
    public RabbitRetryTemplateCustomizer loginRetryTemplateCustomizer(
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}") int maxAttempts) {
        return (target, retryTemplate) -> retryTemplate.setRetryPolicy(
                new SimpleRetryPolicy(maxAttempts,
                        Map.of(AmqpRejectAndDontRequeueException.class, false), true, true));
    }
}
