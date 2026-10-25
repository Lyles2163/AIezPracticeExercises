package org.leon.practicemodule.mapper;

/** 一批题的选项键（交卷时一次取齐，判单选题答案是否在集合内）。不带 is_correct——那是死数据（spec 5.5）。 */
public record QuestionOptionKeyRow(long questionId, String optionKey) {
}
