package org.leon.practicemodule.mapper;

/**
 * selectLatestByQuestions 的投影行：每题"最近一次作答"的原始形态。
 * 刻意把 isCorrect / answerMode 原样带出来，answerStatus 的三态映射交给 AnswerStatus——
 * 映射规则是 Java 逻辑，必须能在不连库的单测里逐个值验。
 */
public record LatestAnswerRow(Long recordId, Long questionId, String userAnswerJson,
                              Integer isCorrect, Integer answerMode) {
}
