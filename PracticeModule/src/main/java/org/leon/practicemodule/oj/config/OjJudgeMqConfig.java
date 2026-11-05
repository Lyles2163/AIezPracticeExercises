package org.leon.practicemodule.oj.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OJ 判题队列拓扑：1 主交换机 + 主队列 + 死信交换机 + 死信队列 + 2 条绑定。
 *
 * 生产者与消费者同在 PracticeModule 内，但仍把交换机/路由键写成常量，
 * 与项目里 LoginMqConfig / LoginEventQueueConfig 保持一致的显式契约风格 ——
 * 以后把消费者拆成独立判题服务时，这些常量就是跨服务的唯一约定。
 */
@Configuration
public class OjJudgeMqConfig {

    public static final String JUDGE_EXCHANGE = "oj.judge.exchange";
    public static final String JUDGE_SUBMIT_KEY = "oj.judge.submit";
    public static final String JUDGE_QUEUE = "oj.judge.queue";

    public static final String JUDGE_DLX = "oj.judge.dlx";
    public static final String JUDGE_DEAD_KEY = "oj.judge.dead";
    public static final String JUDGE_DEAD_QUEUE = "oj.judge.dead.queue";

    @Bean
    public TopicExchange ojJudgeExchange() {
        return new TopicExchange(JUDGE_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange ojJudgeDlx() {
        return new DirectExchange(JUDGE_DLX, true, false);
    }

    @Bean
    public Queue ojJudgeQueue() {
        return QueueBuilder.durable(JUDGE_QUEUE)
                .deadLetterExchange(JUDGE_DLX)
                .deadLetterRoutingKey(JUDGE_DEAD_KEY)
                .build();
    }

    @Bean
    public Queue ojJudgeDeadQueue() {
        return QueueBuilder.durable(JUDGE_DEAD_QUEUE).build();
    }

    @Bean
    public Binding ojJudgeBinding() {
        return BindingBuilder.bind(ojJudgeQueue()).to(ojJudgeExchange()).with(JUDGE_SUBMIT_KEY);
    }

    /** 缺这条绑定，死信能进交换机但没有队列接，死信链路静默失效 */
    @Bean
    public Binding ojJudgeDeadBinding() {
        return BindingBuilder.bind(ojJudgeDeadQueue()).to(ojJudgeDlx()).with(JUDGE_DEAD_KEY);
    }

    /** JSON 序列化：管理界面能直接读到人类可读的消息体 */
    @Bean
    public MessageConverter ojJsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
