package org.leon.usermodule.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ Demo 拓扑配置类。
 * <p>
 * 声明一个 Topic 交换机（{@code demo.user.exchange}）和两个业务队列：
 * <ul>
 *   <li>{@code demo.user.email.queue} —— 处理用户注册后的邮件通知</li>
 *   <li>{@code demo.user.sms.queue} —— 处理用户注册后的短信通知</li>
 * </ul>
 * 应用启动时由 Spring AMQP 自动在 Broker 上声明上述拓扑（幂等，重复启动不会报错）。
 * </p>
 *
 * @author leon
 * @see org.springframework.amqp.rabbit.core.RabbitTemplate
 */
@Configuration
public class RabbitMqDemoConfig {

    /** Topic 交换机名称，所有用户相关消息通过此交换机路由 */
    public static final String USER_EXCHANGE = "demo.user.exchange";

    /** 邮件通知队列名称，持久化，Broker 重启后不丢失 */
    public static final String EMAIL_QUEUE = "demo.user.email.queue";

    /** 短信通知队列名称，持久化，Broker 重启后不丢失 */
    public static final String SMS_QUEUE = "demo.user.sms.queue";

    /** 邮件队列用精确匹配的路由键 */
    public static final String EMAIL_ROUTING_KEY = "user.create.email";

    /** 短信队列用通配符绑定：user.任意一段.sms 都能命中，这里靠 user.create.sms 命中 */
    public static final String SMS_BINDING_PATTERN = "user.*.sms";

    /** 短信队列生产者发送时使用的路由键，能被通配符 user.*.sms 匹配 */
    public static final String SMS_ROUTING_KEY = "user.create.sms";

    /**
     * 声明 Topic 类型交换机。
     * <p>参数：durable=true（持久化），autoDelete=false（不自动删除）。</p>
     *
     * @return TopicExchange 实例
     */
    @Bean
    public TopicExchange userExchange() {
        return new TopicExchange(USER_EXCHANGE, true, false);
    }

    /**
     * 声明邮件通知队列。
     * <p>参数：durable=true，消息在 Broker 重启后仍然保留。</p>
     *
     * @return Queue 实例
     */
    @Bean
    public Queue emailQueue() {
        return new Queue(EMAIL_QUEUE, true);
    }

    /**
     * 声明短信通知队列。
     * <p>参数：durable=true，消息在 Broker 重启后仍然保留。</p>
     *
     * @return Queue 实例
     */
    @Bean
    public Queue smsQueue() {
        return new Queue(SMS_QUEUE, true);
    }

    /**
     * 将邮件队列绑定到交换机，使用精确路由键 {@code user.create.email}。
     * <p>只有携带该路由键的消息才会被投递到此队列。</p>
     *
     * @param emailQueue   邮件队列 Bean
     * @param userExchange Topic 交换机 Bean
     * @return Binding 实例
     */
    @Bean
    public Binding emailBinding(Queue emailQueue, TopicExchange userExchange) {
        return BindingBuilder.bind(emailQueue).to(userExchange).with(EMAIL_ROUTING_KEY);
    }

    /**
     * 将短信队列绑定到交换机，使用通配符路由模式 {@code user.*.sms}。
     * <p>
     * Topic 交换机中 {@code *} 匹配恰好一个单词，因此
     * {@code user.create.sms}、{@code user.update.sms} 等都能命中此绑定。
     * </p>
     *
     * @param smsQueue     短信队列 Bean
     * @param userExchange Topic 交换机 Bean
     * @return Binding 实例
     */
    @Bean
    public Binding smsBinding(Queue smsQueue, TopicExchange userExchange) {
        return BindingBuilder.bind(smsQueue).to(userExchange).with(SMS_BINDING_PATTERN);
    }

    /**
     * 配置 JSON 消息转换器。
     * <p>
     * 使用 Spring Boot 自动配置的 {@link ObjectMapper} 替代默认的 JDK 序列化，
     * 使 RabbitMQ 管理界面可查看可读的 JSON 消息体，
     * 同时便于跨语言消费者（如 Python、Node.js）解析消息。
     * </p>
     *
     * @param objectMapper Spring Boot 自动注入的 Jackson ObjectMapper
     * @return Jackson2JsonMessageConverter 实例
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        // 生产端的 __TypeId__ 是 org.leon.authmodule.pojo.mq.LoginSuccessEvent，
        // 本服务没有这个类。INFERRED 让监听方法参数类型成为权威，按字段名映射。
        converter.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }
}
