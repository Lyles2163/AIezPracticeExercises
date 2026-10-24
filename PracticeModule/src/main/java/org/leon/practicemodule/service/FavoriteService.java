package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.FavoriteItemVO;
import org.leon.practicemodule.pojo.vo.PageVO;

public interface FavoriteService {

    PageVO<FavoriteItemVO> page(Long userId, Long subjectId, Long pageNum, Long pageSize);

    /** 开或关，返回最终状态 */
    boolean toggle(Long userId, Long subjectId, Long questionId);
}
