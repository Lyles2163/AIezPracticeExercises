package org.leon.aimodule.tool.dto;

import lombok.Data;

import java.util.List;

/**
 * 与 PracticeModule 的 WrongQuestionVO 一一对应。
 * 字段含义见 PracticeModule 那边的类注释；这里不重复，保持同步靠字段名一致。
 */
@Data
public class WrongQuestionDTO {

    private Long questionId;
    private Long subjectId;
    private String subjectName;
    private Integer type;
    private Integer difficulty;
    private String title;
    private List<Option> options;
    private List<String> tags;
    private Integer wrongCount;
    private Long lastWrongAt;
    private Integer status;
    private String lastUserAnswerJson;
    private String answerJson;
    private String analysis;

    @Data
    public static class Option {
        private String optionKey;
        private String content;
    }
}
