package org.leon.practicemodule.pojo;

import lombok.Data;

/** 简答自评请求体。recordId 来自 submit 的响应；isCorrect 必填（true=对 / false=错） */
@Data
public class SelfJudgeReq {
    private Long subjectId;
    private Long questionId;
    private Long recordId;
    private Boolean isCorrect;
}