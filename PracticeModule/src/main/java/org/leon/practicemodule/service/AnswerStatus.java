package org.leon.practicemodule.service;

import org.leon.practicemodule.mapper.LatestAnswerRow;

/**
 * answerStatus 口径（spec 5.2 的 0/1/2 + 段 3 裁定一新增 3）。
 *
 * 三态映射是段 2 的 -1 哨兵逼出来的：is_correct=-1 既不是"对"也不是"错"。
 * 并入 2 会让简答题答一次就在答题卡上显示"错"（与"自评时才统计"矛盾）；
 * 并入 0 会让断点反复推同一题，而且刷新页面后用户永远拿不回 recordId。
 */
public final class AnswerStatus {

    public static final int UNANSWERED = 0;
    public static final int CORRECT = 1;
    public static final int WRONG = 2;
    public static final int PENDING_SELF_JUDGE = 3;

    private AnswerStatus() {
    }

    public static int of(LatestAnswerRow row) {
        if (row == null) {
            return UNANSWERED;
        }
        Integer isCorrect = row.isCorrect();
        if (isCorrect == null) {
            // 列是 NOT NULL，真出现说明 SQL 少选了列；宁可显示未作答，也不让取题接口 500
            return UNANSWERED;
        }
        if (isCorrect == -1) {
            return PENDING_SELF_JUDGE;
        }
        return isCorrect == 1 ? CORRECT : WRONG;
    }

    /** 待自评 + 练习类作答，才允许把 recordId 交给前端去点"自评" */
    public static boolean selfJudgeable(LatestAnswerRow row) {
        if (of(row) != PENDING_SELF_JUDGE) {
            return false;
        }
        Integer mode = row.answerMode();
        return mode != null && (mode == 1 || mode == 3);
    }
}
