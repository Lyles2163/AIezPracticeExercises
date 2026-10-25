package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §6.4 的三处入口共用这一句判据，所以边界必须钉死：宽限期内不算过期，
 * 过完宽限才算。start 用它决定"归档还是复用"，draft 用它拒绝，submit 用它决定终态是 2 还是 3。
 */
class ExamDeadlineTest {

    private static final long D = 1_000_000_000_000L;

    @Test
    @DisplayName("deadline 未到 → 未过期")
    void notExpiredBeforeDeadline() {
        assertFalse(ExamDeadline.expired(new Date(D), D - 1L, 60L));
        assertFalse(ExamDeadline.expired(new Date(D), D, 60L), "正好到点：now > deadline+grace 才算过期");
    }

    @Test
    @DisplayName("宽限期内不算过期，宽限之外算（判据是 > 不是 >=）")
    void graceBoundary() {
        assertFalse(ExamDeadline.expired(new Date(D), D + 59_999L, 60L));
        assertFalse(ExamDeadline.expired(new Date(D), D + 60_000L, 60L),
                "grace=60 的语义是「再加 60 秒」，等于 60 秒那一刻仍未过期");
        assertTrue(ExamDeadline.expired(new Date(D), D + 60_001L, 60L));
    }

    @Test
    @DisplayName("grace=0 退化为严格到点")
    void zeroGrace() {
        assertTrue(ExamDeadline.expired(new Date(D), D + 1L, 0L));
        assertFalse(ExamDeadline.expired(new Date(D), D, 0L));
    }

    @Test
    @DisplayName("deadline 为 NULL 不算过期：列是 NOT NULL，走到这里说明数据不对，但不许让读接口 500")
    void nullDeadlineIsNotExpired() {
        assertFalse(ExamDeadline.expired(null, Long.MAX_VALUE, 0L));
    }
}
