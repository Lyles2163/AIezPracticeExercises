package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/**
 * 错题本的一行（spec 5.2）。lastWrongAt 是 epoch 毫秒（spec 5.1）。
 * 只有 titleBrief 与"我自己上次答了什么"，没有任何答案真值（spec 5.4）。
 */
@Data
public class WrongItemVO {

    private Long questionId;
    private Integer index;
    private Integer type;
    private String titleBrief;
    private Integer wrongCount;
    private Long lastWrongAt;
    /** 1未掌握 2已掌握 */
    private Integer status;
    private String lastUserAnswerJson;
}
