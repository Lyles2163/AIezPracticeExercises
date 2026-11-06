package org.leon.practicemodule.mapper;

import java.util.Date;

/**
 * selectWrongDetails 的投影行。给 AI 用，所以带 answerJson/analysis/tagsJson 真值；
 * 题干 title 保持原文，不做折叠截断（AI 要看到完整题目）。
 */
public record WrongDetailRow(Long questionId, Long subjectId, Integer wrongCount, Date lastWrongAt,
                             Integer status, Integer type, Integer difficulty,
                             String title, String answerJson, String analysis, String tagsJson) {
}
