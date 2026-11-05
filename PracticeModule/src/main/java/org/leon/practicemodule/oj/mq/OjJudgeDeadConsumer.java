package org.leon.practicemodule.oj.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.practicemodule.oj.config.OjJudgeMqConfig;
import org.leon.practicemodule.oj.mapper.OjSubmissionMapper;
import org.leon.practicemodule.oj.pojo.mq.OjJudgeEvent;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 死信兜底：重试耗尽仍失败的任务，在这里落一个终态。
 * 少了这一步，被判题记录会永远停在 RUNNING，前端一直转圈。
 */
@Slf4j
@Component
public class OjJudgeDeadConsumer {

    private final OjSubmissionMapper submissionMapper;

    public OjJudgeDeadConsumer(OjSubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    @RabbitListener(queues = OjJudgeMqConfig.JUDGE_DEAD_QUEUE)
    public void onDead(OjJudgeEvent event) {
        Long submissionId = event.getSubmissionId();
        log.error("[OJ判题死信] submissionId={} 重试耗尽，标记 SE", submissionId);
        submissionMapper.markFinished(submissionId, "SE", 0, 0, 0,
                "系统错误：判题服务暂时不可用，请稍后重试");
    }
}
