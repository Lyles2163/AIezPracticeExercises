package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.ExamDraftVO;
import org.leon.practicemodule.pojo.vo.ExamQuestionVO;
import org.leon.practicemodule.pojo.vo.ExamStartVO;

import java.util.Map;

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

    /**
     * 答题态取题：只给脱敏正文 + 卷面序号与分值 + 用户自己已存的草稿答案。
     * 终态 attempt 上取题是 41002（成绩单走 {@code attempt/detail}）。
     */
    ExamQuestionVO question(Long userId, Long attemptId, Long questionId);

    /**
     * 存草稿：<b>整包覆盖</b> {@code answered_json}（这次没带的题号等于删掉）。
     * 写路径的三条闸门按此顺序：归属 → 终态 41002 → 超时 41001。
     * 超时只拒绝写入、<b>不归档</b>——§6.4 说到点之后只有 submit 能收这次作答。
     */
    ExamDraftVO draft(Long userId, Long attemptId, Map<String, String> answers);
}
