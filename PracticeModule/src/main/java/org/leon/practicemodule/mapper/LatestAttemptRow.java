package org.leon.practicemodule.mapper;

/**
 * 一批试卷各自的最新一次作答（MAX(id)，草稿也算最新——"续考"要的就是它）。
 *
 * 用 id 而不是时间排序：qb_user_answer_record 实测同秒并发写入无法靠 created_at 区分，
 * 本表同构，于是沿用 QbUserAnswerRecordMapper.selectLatestByQuestions 的同一口径。
 *
 * answeredJson 只在 status=1（草稿）时是原料：草稿的已答数是它的 map key 数；
 * 终态的已答数走 qb_user_answer_record 的行数（AttemptRecordCountRow）。
 */
public record LatestAttemptRow(Long paperId, Long attemptId, Integer status, String answeredJson) {
}
