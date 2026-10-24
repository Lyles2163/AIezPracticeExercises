package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/** 收藏本的一行（spec 5.2）。createdAt 是 epoch 毫秒 */
@Data
public class FavoriteItemVO {

    private Long questionId;
    private Integer index;
    private Integer type;
    private String titleBrief;
    private Long createdAt;
}
