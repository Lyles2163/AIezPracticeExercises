package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 某张卷子的一次作答（{@code exam/attempt/page} 的一行）。
 *
 * <p>计数与分数<b>全部可空</b>：{@code status=1} 的草稿行没有成绩快照，
 * 给 0 会让用户以为"考了 0 分"，而真相是"还没交"。
 *
 * <p>{@code pendingSelfJudgeCount} 不是库里的列（{@code qb_exam_attempt} 只有 17 列），
 * 由服务层用 {@code question_count - correct - wrong - unanswered} 减出来；
 * 三个快照任一为 null（草稿）时这一格也是 null。
 */
@Data
public class ExamAttemptItemVO {

    private Long attemptId;
    private Integer seqNo;
    /** 1 进行中 / 2 已提交 / 3 超时或放弃自动归档 */
    private Integer status;
    private BigDecimal score;
    /** epoch 毫秒；草稿为 null */
    private Long submitAt;
    private Integer durationUsedMs;
    private Integer correctCount;
    private Integer wrongCount;
    private Integer unansweredCount;
    private Integer pendingSelfJudgeCount;
}
