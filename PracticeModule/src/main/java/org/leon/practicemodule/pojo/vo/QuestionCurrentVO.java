package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/**
 * 取题响应（spec 5.2 的"脱敏单题"）。
 * finished=true 时 question 为 null（本科目已刷完），其余字段也全为 null——
 * 前端据此显示"这一科已经刷完，要不要去错题本"。
 *
 * 为什么嵌套而不是平铺：平铺要把三个字段塞进 QuestionVO，而那个 VO 同时被段 5 的
 * exam/question 用（那里没有 answerStatus 也没有 finished）；且 PracticeMasker 必须
 * 是唯一的复制器——多一个复制器就多一处泄漏点。index 留在 question.index（段 2 就预留了）。
 */
@Data
public class QuestionCurrentVO {

    private boolean finished;
    /** 唯一出口 PracticeMasker 的产物；答题态的所有题目字段都在它身上 */
    private QuestionVO question;
    /** 0未作答 1最近答对 2最近答错 3待自评（裁定一） */
    private Integer answerStatus;
    /** 我自己上次答了什么（spec 5.4 明确允许：不是真值） */
    private String lastUserAnswerJson;
    /** 仅"待自评 + 练习类作答"才非空（裁定三）：回一个注定 40000 的 recordId 比不回更糟 */
    private Long pendingRecordId;
}
