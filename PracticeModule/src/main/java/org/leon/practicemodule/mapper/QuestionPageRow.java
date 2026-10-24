package org.leon.practicemodule.mapper;

/**
 * selectEnabledPage 的投影行：题目列表的一页。
 * tagsJson 保持原始串——解析只在 PracticeMasker 一处，别在 SQL 与 VO 里各拆一遍。
 */
public record QuestionPageRow(Long questionId, Integer type, Integer difficulty,
                              String tagsJson, Integer sortOrder) {
}
