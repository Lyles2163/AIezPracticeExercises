package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/**
 * 判分响应——答案真值允许出现的两处之一（另一处是段 5 交卷后的成绩单，spec 5.4）。
 */
@Data
public class AnswerSubmitVO {
    private Long recordId;
    /** 简答待自评时为 null——不给前端一个假的 false */
    private Boolean isCorrect;
    private String correctAnswerJson;
    private String analysis;
    private boolean needSelfJudge;
    private StatDeltaVO statDelta;
    private Integer wrongCount;
    private boolean wrongMastered;
}