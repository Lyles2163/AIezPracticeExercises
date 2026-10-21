package org.leon.authmodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.config.LoginMqConfig;
import org.leon.authmodule.pojo.mq.LoginSuccessEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoginEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public LoginEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(LoginSuccessEvent event) {
        log.info("[登录事件生产者] traceId={} userId={}", event.getTraceId(), event.getUserId());
        rabbitTemplate.convertAndSend(
                LoginMqConfig.LOGIN_EXCHANGE, LoginMqConfig.LOGIN_SUCCESS_KEY, event);
    }
}
