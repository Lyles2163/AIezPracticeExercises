package org.leon.practicemodule.mapper;

/**
 * 一次作答展开出的答题行数，即终态 attempt 的已答题数（段 4 裁定三的后半边）。
 * 草稿不走这条：草稿的答案在 answered_json 里，还没展开成行。
 */
public record AttemptRecordCountRow(Long attemptId, long answeredCount) {
}
