package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("分页入参归一（裁定六）")
class PracticePagesTest {

    @Test
    @DisplayName("null 走默认：pageNum=1、pageSize=20、offset=0")
    void defaults() {
        PracticePages.PageParam p = PracticePages.of(null, null);
        assertEquals(1L, p.pageNum());
        assertEquals(20L, p.pageSize());
        assertEquals(0L, p.offset());
    }

    @Test
    @DisplayName("offset 由页码与页长算出")
    void offset() {
        assertEquals(40L, PracticePages.of(3L, 20L).offset());
        assertEquals(1L, PracticePages.of(2L, 1L).offset());
    }

    @Test
    @DisplayName("pageSize=100 合法，101 报 40000——不静默夹取")
    void sizeBoundary() {
        assertEquals(100L, PracticePages.of(1L, 100L).pageSize());
        PracticeException e = assertThrows(PracticeException.class,
                () -> PracticePages.of(1L, 101L));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    @Test
    @DisplayName("0 与负数的页码/页长都报 40000")
    void nonPositiveRejected() {
        assertThrows(PracticeException.class, () -> PracticePages.of(0L, 20L));
        assertThrows(PracticeException.class, () -> PracticePages.of(-1L, 20L));
        assertThrows(PracticeException.class, () -> PracticePages.of(1L, 0L));
        assertThrows(PracticeException.class, () -> PracticePages.of(1L, -5L));
    }

    @Test
    @DisplayName("天文数字的 pageNum 报 40000 而不是溢出成负 offset")
    void overflowGuarded() {
        // (Long.MAX_VALUE-1)*100 会溢出为负数，负数进 LIMIT 是库错 -> code=0（在说谎）
        assertEquals(PracticeErrorCode.BAD_REQUEST,
                assertThrows(PracticeException.class,
                        () -> PracticePages.of(Long.MAX_VALUE, 100L)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST,
                assertThrows(PracticeException.class,
                        () -> PracticePages.of(PracticePages.MAX_PAGE_NUM + 1, 100L)).errorCode());
        assertTrue(PracticePages.of(PracticePages.MAX_PAGE_NUM, 100L).offset() > 0,
                "上限内必须给出正 offset");
    }
}
