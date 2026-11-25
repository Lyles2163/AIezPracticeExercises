package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/** 科目卡片：题库入口页的一格 */
@Data
public class SubjectItemVO {
    private Long id;
    private String name;
    private String subjectKey;
    private String icon;
    private String description;
    /** 所属用户ID（1=公共题库，其他=个人题库） */
    private Long ownerId;
    /** 是否为个人科目（ownerId != 1） */
    private boolean personal;
    private long questionCount;
    private long myTotal;
    private long myCorrect;
    /** 0~1，两位小数；未答题时 0 */
    private double myAccuracy;
    /** epoch 毫秒，未答题时 null */
    private Long lastAnswerAt;
}
