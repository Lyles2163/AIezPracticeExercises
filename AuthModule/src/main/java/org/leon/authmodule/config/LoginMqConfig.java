package org.leon.authmodule.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 登录事件的交换机与路由键。队列由消费方 UserModule 声明。
 * 交换机和路由键是跨服务隐式契约，没有编译期保护：改名必须两边同步，
 * 否则消息进黑洞且不报错。
 */
@Configuration
public class LoginMqConfig {

    public static final String LOGIN_EXCHANGE = "auth.login.exchange";
    public static final String LOGIN_SUCCESS_KEY = "user.login.success";
    /** 死信路由键，必须与消费端 LoginEventQueueConfig.LOGIN_DEAD_KEY 完全一致 */
    public static final String LOGIN_DEAD_KEY = "user.login.dead";

    @Bean
    public TopicExchange loginExchange() {
        return new TopicExchange(LOGIN_EXCHANGE, true, false);
    }
}
