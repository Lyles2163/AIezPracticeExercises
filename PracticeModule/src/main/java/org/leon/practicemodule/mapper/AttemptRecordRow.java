package org.leon.practicemodule.mapper;

/**
 * 一次考试展开出的答题行（重建成绩单用）。
 * 不带 record id：前端在成绩单页不需要按行操作，{@code self-judge} 也不接受 mode=2 的行（裁定一）。
 */
public record AttemptRecordRow(Long questionId, String userAnswerJson,
                               Integer isCorrect, Integer durationMs) {
}
