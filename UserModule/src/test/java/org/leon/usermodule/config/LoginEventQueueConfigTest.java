package org.leon.usermodule.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginEventQueueConfigTest {

    private final LoginEventQueueConfig config = new LoginEventQueueConfig();

    @DisplayName("主队列持久化且挂了死信交换机与独立路由键")
    @Test
    void mainQueueShouldDeclareDeadLetterArguments() {
        Queue queue = config.loginEventQueue();

        assertEquals("user.login.event.queue", queue.getName());
        assertTrue(queue.isDurable());
        assertEquals("user.login.event.dlx",
                queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals("user.login.dead",
                queue.getArguments().get("x-dead-letter-routing-key"));
    }

    @DisplayName("死信队列独立存在，不与主队列同名")
    @Test
    void deadQueueShouldBeSeparate() {
        Queue dead = config.loginDeadQueue();

        assertEquals("user.login.event.dead.queue", dead.getName());
        assertTrue(dead.isDurable());
    }

    @DisplayName("交换机名与路由键必须与生产端 AuthModule 完全一致")
    @Test
    void contractMustMatchProducer() {
        assertEquals("auth.login.exchange", LoginEventQueueConfig.LOGIN_EXCHANGE);
        assertEquals("user.login.success", LoginEventQueueConfig.LOGIN_SUCCESS_KEY);
        assertEquals("user.login.dead", LoginEventQueueConfig.LOGIN_DEAD_KEY);
    }
}
