package org.leon.authmodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.config.LoginMqConfig;
import org.leon.authmodule.pojo.mq.LoginSuccessEvent;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoginEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @DisplayName("登录事件投到 auth.login.exchange 的 user.login.success 路由键")
    @Test
    void publishUsesLoginExchangeAndSuccessKey() {
        LoginSuccessEvent event = new LoginSuccessEvent(
                10L, "test001", "trace-1", "203.0.113.7", 1_700_000_000_000L, "abcdef123456");

        new LoginEventPublisher(rabbitTemplate).publish(event);

        verify(rabbitTemplate).convertAndSend(
                LoginMqConfig.LOGIN_EXCHANGE, LoginMqConfig.LOGIN_SUCCESS_KEY, event);
    }
}
