package org.leon.usermodule.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 登录事件的消费端拓扑：1 交换机 + 主队列 + 死信交换机 + 死信队列。
 * 交换机名与路由键必须与 AuthModule 的 LoginMqConfig 一字不差，
 * 跨服务没有编译期保护，改名会让消息静默进黑洞。
 */
@Configuration
public class LoginEventQueueConfig {

    public static final String LOGIN_EXCHANGE = "auth.login.exchange";
    public static final String LOGIN_SUCCESS_KEY = "user.login.success";
    public static final String LOGIN_EVENT_QUEUE = "user.login.event.queue";
    public static final String LOGIN_DLX = "user.login.event.dlx";
    public static final String LOGIN_DEAD_KEY = "user.login.dead";
    public static final String LOGIN_DEAD_QUEUE = "user.login.event.dead.queue";

    @Bean
    public TopicExchange loginExchange() {
        return new TopicExchange(LOGIN_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange loginDlx() {
        return new DirectExchange(LOGIN_DLX, true, false);
    }

    @Bean
    public Queue loginEventQueue() {
        return QueueBuilder.durable(LOGIN_EVENT_QUEUE)
                .deadLetterExchange(LOGIN_DLX)
                .deadLetterRoutingKey(LOGIN_DEAD_KEY)
                .build();
    }

    @Bean
    public Queue loginDeadQueue() {
        return QueueBuilder.durable(LOGIN_DEAD_QUEUE).build();
    }

    @Bean
    public Binding loginEventBinding() {
        return BindingBuilder.bind(loginEventQueue()).to(loginExchange()).with(LOGIN_SUCCESS_KEY);
    }

    /** 缺这条绑定，消息能进死信交换机但没有队列接，死信链路静默失效 */
    @Bean
    public Binding loginDeadBinding() {
        return BindingBuilder.bind(loginDeadQueue()).to(loginDlx()).with(LOGIN_DEAD_KEY);
    }
}
