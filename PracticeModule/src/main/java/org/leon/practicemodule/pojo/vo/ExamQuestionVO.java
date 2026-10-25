package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 考试中逐题取题的响应（spec 5.6 的 {@code exam/question}）。
 *
 * <p>这是<b>答题态</b>响应，所以里面的 {@code question} 必须是脱敏后的 {@link QuestionVO}：
 * 没有 answerJson、没有 analysis、选项没有 isCorrect（{@code PracticeMasker.FORBIDDEN_IN_ANSWER_STATE}）。
 * 用户自己的草稿 {@code userAnswerJson} 是例外——那是他自己写的，回显不算泄漏。
 */
@Data
public class ExamQuestionVO {

    private Long attemptId;
    private Long questionId;
    /** 卷面序号（答题卡上的那个号），来自 structure_json 而不是 qb_question.sort_order */
    private Integer index;
    /** 这道题在本卷的分值，来自 structure_json */
    private BigDecimal score;
    private QuestionVO question;
    private String userAnswerJson;
}
