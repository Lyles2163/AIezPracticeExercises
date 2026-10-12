package org.leon.usermodule.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ demo 拓扑：一个 Topic 交换机 + 两个队列
 * 应用启动时由 Spring AMQP 自动在 Broker 上声明（幂等，重复启动不会报错）
 */
@Configuration
public class RabbitMqDemoConfig {

    public static final String USER_EXCHANGE = "demo.user.exchange";

    public static final String EMAIL_QUEUE = "demo.user.email.queue";

    public static final String SMS_QUEUE = "demo.user.sms.queue";

    /** 邮件队列用精确匹配的路由键 */
    public static final String EMAIL_ROUTING_KEY = "user.create.email";

    /** 短信队列用通配符绑定：user.任意一段.sms 都能命中，这里靠 user.create.sms 命中 */
    public static final String SMS_BINDING_PATTERN = "user.*.sms";

    public static final String SMS_ROUTING_KEY = "user.create.sms";

    @Bean
    public TopicExchange userExchange() {
        return new TopicExchange(USER_EXCHANGE, true, false);
    }

    @Bean
    public Queue emailQueue() {
        return new Queue(EMAIL_QUEUE, true);
    }

    @Bean
    public Queue smsQueue() {
        return new Queue(SMS_QUEUE, true);
    }

    @Bean
    public Binding emailBinding(Queue emailQueue, TopicExchange userExchange) {
        return BindingBuilder.bind(emailQueue).to(userExchange).with(EMAIL_ROUTING_KEY);
    }

    @Bean
    public Binding smsBinding(Queue smsQueue, TopicExchange userExchange) {
        return BindingBuilder.bind(smsQueue).to(userExchange).with(SMS_BINDING_PATTERN);
    }

    /**
     * 用 Spring Boot 已配置好的 ObjectMapper 做 JSON 序列化，
     * 替代默认的 JDK 序列化，这样管理界面上看到的消息体是可读的 JSON
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
