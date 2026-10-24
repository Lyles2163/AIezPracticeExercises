package org.leon.practicemodule.mapper;

import java.math.BigDecimal;

/**
 * selectPage 的投影行：真题套卷卡片的一页。
 *
 * subjectName 已在 SQL 里 join qb_subject 取到——不在 Java 里按科目二次查，
 * 那是 N+1 最常见的起点。structure_json 故意不在这里：卡片不解析卷面结构。
 */
public record PaperCardRow(Long paperId, Long subjectId, String subjectName, String title,
                           Integer examYear, Integer examMonth, Integer setNo,
                           Integer durationMinutes, BigDecimal totalScore,
                           Integer questionCount) {
}
