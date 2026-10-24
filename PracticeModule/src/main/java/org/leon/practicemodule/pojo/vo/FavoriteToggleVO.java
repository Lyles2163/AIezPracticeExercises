package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/** toggle 的结果：以最终状态为准，而不是"我猜你想加还是想删" */
@Data
public class FavoriteToggleVO {

    private boolean favorited;
}
