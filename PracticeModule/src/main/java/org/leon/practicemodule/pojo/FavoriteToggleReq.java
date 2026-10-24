package org.leon.practicemodule.pojo;

import lombok.Data;

/**
 * 收藏开关。subjectId 只用来核对归属；收藏行里的 subject_id 取题目自己的，
 * 不给前端传错的机会（与段 2 的 submit 同一口径）。
 */
@Data
public class FavoriteToggleReq {

    private Long subjectId;
    private Long questionId;
}
