package org.leon.practicemodule.mapper;

import java.math.BigDecimal;
import java.util.Date;

/**
 * attempt/page 的一行。<b>score/correctCount/… 在 status=1（草稿）时可为 NULL</b>，所以全用包装类型。
 *
 * <p>这里面没有"待自评数"：{@code qb_exam_attempt} 只有 17 列（段 4 实测），库里没这一列，
 * 选它就直接 {@code Unknown column}。待自评数由服务层用
 * {@code question_count - correct - wrong - unanswered} 减出来（Task 5.9），
 * join {@code qb_paper} 反而多一张表，而减法不会漂。
 */
public record AttemptPageRow(Long attemptId, Integer seqNo, Integer status, BigDecimal score,
                             Date submitAt, Integer durationUsedMs,
                             Integer correctCount, Integer wrongCount, Integer unansweredCount) {
}
