package org.leon.authmodule.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 手机验证码的 MQ 拓扑：2 交换机 + 3 队列 + 3 绑定（spec §3.2）。
 * 延迟队列故意不配 @RabbitListener —— 有消费者消息就永远不会超时变死信。
 */
@Configuration
public class CaptchaMqConfig {

    public static final String SMS_EXCHANGE = "auth.sms.exchange";
    public static final String SMS_EXPIRE_DLX = "auth.sms.expire.dlx";

    public static final String SMS_SEND_KEY = "sms.send";
    public static final String SMS_EXPIRE_DELAY_KEY = "sms.expire";
    public static final String SMS_EXPIRED_KEY = "sms.expired";

    public static final String SMS_SEND_QUEUE = "auth.sms.send.queue";
    public static final String SMS_EXPIRE_DELAY_QUEUE = "auth.sms.expire.delay.queue";
    public static final String SMS_EXPIRED_QUEUE = "auth.sms.expired.queue";

    @Value("${auth.sms.code-ttl-ms:300000}")
    private long codeTtlMs;

    @Value("${auth.sms.queue-suffix:}")
    private String queueSuffix;

    @Bean
    public TopicExchange smsExchange() {
        return new TopicExchange(SMS_EXCHANGE, true, false);
    }

    /** 死信交换机：接收从延迟队列超时出来的消息 */
    @Bean
    public DirectExchange smsExpireDlx() {
        return new DirectExchange(SMS_EXPIRE_DLX, true, false);
    }

    @Bean
    public Queue smsSendQueue() {
        return QueueBuilder.durable(SMS_SEND_QUEUE + queueSuffix).build();
    }

    @Bean
    public Queue smsExpireDelayQueue() {
        return QueueBuilder.durable(SMS_EXPIRE_DELAY_QUEUE + queueSuffix)
                .ttl((int) codeTtlMs)
                .deadLetterExchange(SMS_EXPIRE_DLX)
                .deadLetterRoutingKey(SMS_EXPIRED_KEY)
                .build();
    }

    @Bean
    public Queue smsExpiredQueue() {
        return QueueBuilder.durable(SMS_EXPIRED_QUEUE + queueSuffix).build();
    }

    @Bean
    public Binding smsSendBinding() {
        return BindingBuilder.bind(smsSendQueue()).to(smsExchange()).with(SMS_SEND_KEY);
    }

    /** ★ 缺这条绑定，延迟队列永远是空的，过期链路静默失效（spec §3.2 自检记录的就是这个坑） */
    @Bean
    public Binding smsExpireDelayBinding() {
        return BindingBuilder.bind(smsExpireDelayQueue()).to(smsExchange()).with(SMS_EXPIRE_DELAY_KEY);
    }

    @Bean
    public Binding smsExpiredBinding() {
        return BindingBuilder.bind(smsExpiredQueue()).to(smsExpireDlx()).with(SMS_EXPIRED_KEY);
    }

    /** JSON 序列化：管理界面上能直接读到人类可读的消息体 */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
