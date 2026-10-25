package org.leon.practicemodule.service;

import java.util.Date;

/**
 * 超时判据（spec 6.4）。整段只有一句 {@code now > deadline + grace}，
 * 但它被 start / draft / submit 三处共用——写三遍就会漂成三种语义（一处 >= 一处加 grace 一处不加），
 * 于是"卡着点交卷成功、下次 start 却把同一份草稿判成过期"这类鬼故事。所以收成静态方法。
 */
public final class ExamDeadline {

    private ExamDeadline() {
    }

    /**
     * @param deadlineAt   attempt 的截止时间；NULL 视为未过期（列是 NOT NULL，NULL 只可能是脏数据，
     *                     读接口不该因此炸）
     * @param nowMillis    服务端当前毫秒（不信前端时钟，spec 6.3 的倒计时只是显示用）
     * @param graceSeconds practice.exam.grace-seconds
     */
    public static boolean expired(Date deadlineAt, long nowMillis, long graceSeconds) {
        if (deadlineAt == null) {
            return false;
        }
        return nowMillis > deadlineAt.getTime() + graceSeconds * 1_000L;
    }
}
