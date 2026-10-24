package org.leon.practicemodule.mapper;

/**
 * selectPositions 的投影行：posNo 就是 API 里的 index（段 3 裁定四）。
 * 列名刻意用 pos_no，不叫 index 也不叫 position——前者是 MySQL 关键词，后者是函数名。
 */
public record QuestionPositionRow(Long questionId, long posNo) {
}
