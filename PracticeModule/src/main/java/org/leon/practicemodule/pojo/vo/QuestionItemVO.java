package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.util.List;

/**
 * 题目列表的一行（spec 5.2）。刻意没有 title：答题卡是一屏数字格子，
 * 少一个字段就少一条可能把题干带出去的通道。
 */
@Data
public class QuestionItemVO {

    private Integer index;
    private Long questionId;
    private Integer type;
    private Integer difficulty;
    private List<String> tags;
    /** 0未作答 1最近答对 2最近答错 3待自评（段 3 裁定一） */
    private Integer answerStatus;
    private boolean favorited;
}
