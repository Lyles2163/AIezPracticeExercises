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
    private long questionCount;
    private long myTotal;
    private long myCorrect;
    /** 0~1，两位小数；未答题时 0 */
    private double myAccuracy;
    /** epoch 毫秒，未答题时 null */
    private Long lastAnswerAt;
}
