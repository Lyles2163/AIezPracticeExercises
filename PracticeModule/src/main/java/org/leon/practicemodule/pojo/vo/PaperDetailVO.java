package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 卷面骨架（spec 5.6：{@code paper/detail} 不含题干与选项）。三层嵌套与 structure_json 一一对应，
 * 前端用它渲染 Part/Section 标题、分数与时长徽章、"显示原文"按钮与右侧答题卡网格。
 *
 * <p>{@code originalText} 受段 4 裁定二的闸门：该用户该卷没有**终态** attempt 时恒为 null。
 * 这不是脱敏而是业务规则——听力原文即答案，考试中点开"显示原文"就该是空的。
 * 因此它不在 {@code PracticeMasker.FORBIDDEN_IN_ANSWER_STATE} 清单里，
 * 由 {@code PaperServiceTest} 与 {@code PaperSqlDbIT} 单独盯。
 */
@Data
public class PaperDetailVO {

    private Long paperId;
    private String title;
    private List<String> badges;
    private Integer durationMinutes;
    private BigDecimal totalScore;
    private Integer questionCount;
    private String description;

    private long submitCount;
    private int answeredCount;
    private double progressPercent;

    /** 最新一次作答（草稿也算）——"继续考试"的入口 */
    private Long latestAttemptId;
    private Integer latestAttemptStatus;

    private List<PartVO> parts;

    @Data
    public static class PartVO {
        private String roman;
        private String name;
        private String cardLabel;
        private BigDecimal score;
        private long minutes;
        private List<SectionVO> sections;
    }

    @Data
    public static class SectionVO {
        private String label;
        private String subTitle;
        private String direction;
        /** 音频不受原文闸门影响：spec 5.6 明写"audioUrl 正常返回" */
        private String audioUrl;
        private String originalText;
        private List<QuestionRefVO> questions;
    }

    /**
     * 卷面的一题：只有题号、引用与分值。
     * 刻意**不含 type**——题型由段 5 的 {@code exam/question} 给，
     * 骨架里塞题型就要多查一次 qb_question，而这道题的正文本来就不该在这里出现。
     */
    @Data
    public static class QuestionRefVO {
        private Integer index;
        private Long questionId;
        private BigDecimal score;
    }
}
