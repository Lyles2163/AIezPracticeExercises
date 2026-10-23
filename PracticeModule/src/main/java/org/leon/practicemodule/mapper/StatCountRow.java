package org.leon.practicemodule.mapper;

/** countEnabledQuestionRows 的投影行：subject_id + 题数 */
public record StatCountRow(Long subjectId, long cnt) {
}
