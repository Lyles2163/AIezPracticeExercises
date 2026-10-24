package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.QuestionCurrentVO;
import org.leon.practicemodule.pojo.vo.QuestionItemVO;

/** 题库读侧（段 3）。 */
public interface QuestionReadService {

    PageVO<QuestionItemVO> page(Long userId, Long subjectId, Long pageNum, Long pageSize);

    /**
     * 取一道题。二选一：subjectId+mode（1顺序 3智能）走断点/优先题，questionId 取指定题。
     * 两个都给或都不给都是 40000——歧义参数不该由后端猜。
     */
    QuestionCurrentVO current(Long userId, Long subjectId, Integer mode, Long questionId);
}
