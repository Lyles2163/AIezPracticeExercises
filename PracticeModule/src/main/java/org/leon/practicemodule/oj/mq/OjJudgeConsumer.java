package org.leon.practicemodule.oj.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.practicemodule.oj.config.OjJudgeMqConfig;
import org.leon.practicemodule.oj.mapper.OjProblemMapper;
import org.leon.practicemodule.oj.mapper.OjSubmissionMapper;
import org.leon.practicemodule.oj.mapper.OjTestcaseMapper;
import org.leon.practicemodule.oj.pojo.OjProblem;
import org.leon.practicemodule.oj.pojo.OjSubmission;
import org.leon.practicemodule.oj.pojo.OjTestcase;
import org.leon.practicemodule.oj.pojo.mq.OjJudgeEvent;
import org.leon.practicemodule.oj.sandbox.JudgeLanguage;
import org.leon.practicemodule.oj.service.OjJudger;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class OjJudgeConsumer {

    /** 终态：说明已经判完了，重复消息直接忽略 */
    private static final Set<String> TERMINAL =
            Set.of("AC", "WA", "TLE", "MLE", "CE", "RE", "SE");

    private final OjSubmissionMapper submissionMapper;
    private final OjProblemMapper problemMapper;
    private final OjTestcaseMapper testcaseMapper;
    private final OjJudger judger;

    public OjJudgeConsumer(OjSubmissionMapper submissionMapper,
                           OjProblemMapper problemMapper,
                           OjTestcaseMapper testcaseMapper,
                           OjJudger judger) {
        this.submissionMapper = submissionMapper;
        this.problemMapper = problemMapper;
        this.testcaseMapper = testcaseMapper;
        this.judger = judger;
    }

    @RabbitListener(queues = OjJudgeMqConfig.JUDGE_QUEUE)
    public void onJudgeRequest(OjJudgeEvent event) {
        Long submissionId = event.getSubmissionId();
        OjSubmission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            // 重试一百次也不会凭空出现这条记录，直接进死信，不占用重试
            throw new AmqpRejectAndDontRequeueException("提交记录不存在: " + submissionId);
        }
        if (TERMINAL.contains(submission.getStatus())) {
            log.info("[OJ判题消费者] submissionId={} 已是终态 {}，忽略重复消息",
                    submissionId, submission.getStatus());
            return;
        }

        // PENDING（首次）或 RUNNING（上次判到一半，比如服务重启）都继续判。
        // 判题是幂等的：重判一遍结果相同，所以不设"只允许 PENDING→RUNNING"的硬闸门。
        submissionMapper.markRunning(submissionId);
        try {
            OjProblem problem = problemMapper.selectById(submission.getProblemId());
            if (problem == null) {
                submissionMapper.markFinished(submissionId, "SE", 0, 0, 0, "题目已不存在");
                return;
            }
            List<OjTestcase> testcases = testcaseMapper.selectByProblemId(submission.getProblemId());
            JudgeLanguage language = JudgeLanguage.of(submission.getLanguage());

            OjJudger.Verdict verdict = judger.judge(problem, testcases,
                    submission.getSourceCode(), language);
            submissionMapper.markFinished(submissionId, verdict.status(), verdict.passed(),
                    verdict.total(), verdict.timeUsedMs(), verdict.errorMsg());
            log.info("[OJ判题消费者] submissionId={} verdict={} {}/{}",
                    submissionId, verdict.status(), verdict.passed(), verdict.total());
        } catch (RuntimeException e) {
            // Docker 未安装、镜像没拉、容器异常等基础设施问题：抛回 MQ 按配置重试，
            // 重试耗尽后进死信队列，由 OjJudgeDeadConsumer 落终态。
            log.error("[OJ判题消费者] submissionId={} 判题异常，交给 MQ 重试", submissionId, e);
            throw e;
        }
    }
}
