package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.ExamStartVO;

/**
 * 套卷考试的状态机（spec 5.6）。写路径三条（start/draft/submit）、读路径三条
 * （question/attempt 两页）。
 */
public interface ExamService {

    /**
     * 开考或续考。复用草稿的唯一判据是"该用户该卷有 status=1 的行且没过期"，
     * 不是"客户端带了 attemptId"——所以刷新页面天然回到那一次。
     */
    ExamStartVO start(Long userId, Long paperId, Boolean forceNew);
}
