package org.leon.practicemodule.oj.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.practicemodule.oj.config.OjJudgeMqConfig;
import org.leon.practicemodule.oj.pojo.mq.OjJudgeEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OjJudgePublisher {

    private final RabbitTemplate rabbitTemplate;

    public OjJudgePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(Long submissionId) {
        OjJudgeEvent event = new OjJudgeEvent();
        event.setSubmissionId(submissionId);
        log.info("[OJ判题生产者] submissionId={}", submissionId);
        rabbitTemplate.convertAndSend(
                OjJudgeMqConfig.JUDGE_EXCHANGE, OjJudgeMqConfig.JUDGE_SUBMIT_KEY, event);
    }
}
