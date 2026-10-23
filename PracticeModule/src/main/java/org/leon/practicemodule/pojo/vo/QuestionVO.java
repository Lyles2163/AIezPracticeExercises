package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 答题态唯一的题目出口（spec 5.4 白名单）。
 * 白名单之外一律没有字段——注意这里根本没有 answerJson/analysis/isCorrect，
 * 有人想加就会撞上 PracticeMaskerTest 的守卫。
 */
@Data
public class QuestionVO {

    private Long questionId;
    private Long subjectId;
    private Integer type;
    private String typeName;
    private String title;
    private List<OptionVO> options;
    private Integer difficulty;
    private List<String> tags;
    /** 答题卡序号：段 3 顺序取题/段 5 卷面题号填入 */
    private Integer index;
    /** 卷面分值：段 5 从 structure_json 填入 */
    private BigDecimal score;

    @Data
    public static class OptionVO {
        private String optionKey;
        private String content;
    }
}