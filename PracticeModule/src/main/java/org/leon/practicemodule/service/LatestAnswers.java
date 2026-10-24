package org.leon.practicemodule.service;

import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QuestionPositionRow;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 分页行的批量补齐：一题一行最近作答 + 一题一个位置序号，各一条 SQL，零 N+1。
 *
 * 为什么是静态工具而不是 bean：它无状态、只有两个方法，做成 bean 会让三个服务构造器
 * 各多一个参数，而单测里传 mock 的 mapper 反而更直观。
 * 两个方法都必须先挡空集合：`IN ()` 在 MySQL 是语法错，会被兜底 handler 说成"服务器繁忙"。
 */
public final class LatestAnswers {

    private LatestAnswers() {
    }

    public static Map<Long, LatestAnswerRow> byQuestion(QbUserAnswerRecordMapper mapper,
                                                        Long userId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, LatestAnswerRow> map = new HashMap<>();
        for (LatestAnswerRow row : mapper.selectLatestByQuestions(userId, ids)) {
            map.put(row.questionId(), row);
        }
        return map;
    }

    /** questionId -> 本科目内位置序号（裁定四）。缺行表示该题已下架，调用方按 null 处理 */
    public static Map<Long, Long> positions(QbQuestionMapper mapper, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> map = new HashMap<>();
        for (QuestionPositionRow row : mapper.selectPositions(ids)) {
            map.put(row.questionId(), row.posNo());
        }
        return map;
    }
}
