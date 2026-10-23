package org.leon.practicemodule.pojo;

import lombok.Data;

/** 逐题作答请求体。校验在 Service 层显式做——项目未引 validation starter，@Valid 是空转的 */
@Data
public class AnswerSubmitReq {
    private Long subjectId;
    private Long questionId;
    /** 用户答案 JSON，形态与题型对应：单选 "A"、多选 ["A","C"]、判断 true、填空 ["x"]、简答 文本 */
    private String userAnswerJson;
    private Integer durationMs;
    /** 1刷题 3智能练习；2 是考试，走 exam/submit，不从这里进 */
    private Integer mode;
}