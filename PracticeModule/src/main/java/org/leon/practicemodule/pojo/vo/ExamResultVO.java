package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 成绩单（spec 5.6）：{@code exam/submit} 的返回，也是 {@code exam/attempt/detail} 的返回——同一个类型，
 * 所以"刷新页面不丢解析"不是功能而是结果。
 *
 * <p>这份响应<b>允许带真值</b>（{@code correctAnswerJson}/{@code analysis}/{@code isCorrect}），
 * 因为它只在交卷后出现（spec 5.4）。字节扫描的白名单里必须有它，否则守卫会把自己的成绩单判成泄漏。
 *
 * <p>{@code pendingSelfJudgeCount} 是段 5 加的第 1 个字段：考试里的简答题判不了分
 * （裁定一），既不是对也不是错更不是未答，四个计数里必须有一个地方放它，否则
 * {@code correct+wrong+unanswered != questionCount} 这个用户一眼能看出来的等式会破。
 */
@Data
public class ExamResultVO {

    private Long attemptId;
    private Integer seqNo;
    private Integer status;
    private Long paperId;
    private String title;

    private BigDecimal score;
    private BigDecimal fullScore;
    private Integer correctCount;
    private Integer wrongCount;
    private Integer unansweredCount;
    private Integer pendingSelfJudgeCount;
    private BigDecimal accuracy;

    private Integer durationUsedMs;
    /** epoch 毫秒（段 3 口径）；草稿态为 null */
    private Long submitAt;

    private List<SectionVO> sections;
    private List<QuestionVO> questions;

    @Data
    public static class SectionVO {
        private String name;
        private BigDecimal scoreGot;
        private BigDecimal fullScore;
        private Integer correctCount;
        private Integer pendingCount;
        private Integer unansweredCount;
    }

    /**
     * 成绩单里的一题：卷面骨架 + 用户答案 + 真值 + 解析。
     * {@code isCorrect} 用包装 Boolean——<b>null 就是"待自评"</b>（裁定一），不是 false。
     */
    @Data
    public static class QuestionVO {
        private Integer index;
        private Long questionId;
        private Integer type;
        private String userAnswerJson;
        private String correctAnswerJson;
        private Boolean isCorrect;
        private Boolean needSelfJudge;
        private BigDecimal scoreGot;
        private BigDecimal fullScore;
        private String analysis;
    }
}
