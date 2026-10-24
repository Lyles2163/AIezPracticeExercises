package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.WrongItemVO;

public interface WrongBookService {

    /** status：null 全部、1 未掌握、2 已掌握 */
    PageVO<WrongItemVO> page(Long userId, Long subjectId, Integer status, Long pageNum, Long pageSize);

    /** 置为已掌握。幂等：重复调用一律 true */
    boolean master(Long userId, Long questionId);
}
