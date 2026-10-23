package org.leon.practicemodule.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 子码是前端分支的唯一依据，且必须与 spec 5.7 的错误矩阵逐项一致。
 * 直接 new handler 调用，不起 MVC——@RestControllerAdvice 的接线由 Task 1.5 的端到端覆盖。
 */
class PracticeExceptionHandlerTest {

    private final PracticeExceptionHandler handler = new PracticeExceptionHandler();

    @Test
    @DisplayName("spec 5.7 的七个子码逐项对齐")
    void codesMatchSpec() {
        assertEquals(40000, PracticeErrorCode.BAD_REQUEST.code());
        assertEquals(40100, PracticeErrorCode.IDENTITY_MISSING.code());
        assertEquals(40300, PracticeErrorCode.FORBIDDEN.code());
        assertEquals(40400, PracticeErrorCode.NOT_FOUND.code());
        assertEquals(40900, PracticeErrorCode.CONFLICT.code());
        assertEquals(41001, PracticeErrorCode.EXAM_EXPIRED.code());
        assertEquals(41002, PracticeErrorCode.EXAM_SUBMITTED.code());
    }

    @Test
    @DisplayName("业务异常转成 HTTP 200 + Result{code:子码}，不是 HTTP 4xx")
    void practiceExceptionBecomesResult() {
        Result<Void> r = handler.handlePracticeException(
                new PracticeException(PracticeErrorCode.EXAM_EXPIRED, "考试已结束，请交卷"));
        assertEquals(41001, r.getCode());
        assertEquals("考试已结束，请交卷", r.getMsg());
        assertNull(r.getData());
    }

    @Test
    @DisplayName("未预期异常一律 0 码 + 不泄漏内部 message")
    void unexpectedExceptionIsGeneric() {
        Result<Void> r = handler.handleUnexpected(new IllegalStateException("jdbc url 内部信息"));
        assertEquals(0, r.getCode());
        assertFalse(r.getMsg().contains("jdbc"), "不得把内部异常信息透给前端");
    }
}
