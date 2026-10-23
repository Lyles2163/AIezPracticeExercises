package org.leon.practicemodule.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RequestHeader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 子码是前端分支的唯一依据，且必须与 spec 5.7 的错误矩阵逐项一致。
 * 直接 new handler 调用，不起 MVC——advice 的接线由 Task 1.6 的端到端覆盖。
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
    @DisplayName("业务异常转成 Result{code:子码}，msg 原样透给前端")
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

    @Test
    @DisplayName("缺 X-User-Name → 40100；缺其他头 → 40000")
    void missingHeaderMapsByIdentity() {
        Result<Void> identityMissing = handler.handleMissingHeader(missingHeader("X-User-Name"));
        assertEquals(PracticeErrorCode.IDENTITY_MISSING.code(), identityMissing.getCode());

        Result<Void> other = handler.handleMissingHeader(missingHeader("X-Trace-Id"));
        assertEquals(PracticeErrorCode.BAD_REQUEST.code(), other.getCode());
    }

    @Test
    @DisplayName("请求体不合法 → 40000，不落进兜底的 code=0")
    void badRequestBodyMapsToBadRequest() {
        Result<Void> r = handler.handleBadRequest(
                new HttpMessageNotReadableException("broken json", (org.springframework.http.HttpInputMessage) null));
        assertEquals(PracticeErrorCode.BAD_REQUEST.code(), r.getCode());
    }

    @Test
    @DisplayName("advice 限定在 practicemodule 包，绝不全局生效")
    void adviceIsPackageScoped() {
        // @RestControllerAdvice 是 @ControllerAdvice 的元注解，而 @ControllerAdvice 没有 @Inherited，
        // 所以 getAnnotation(ControllerAdvice.class) 会返回 null —— 必须用 findMergedAnnotation。
        ControllerAdvice merged = AnnotatedElementUtils.findMergedAnnotation(
                PracticeExceptionHandler.class, ControllerAdvice.class);
        assertNotNull(merged, "@RestControllerAdvice 是元注解，getAnnotation 取不到");
        assertArrayEquals(new String[]{"org.leon.practicemodule"}, merged.basePackages(),
                "一旦变成全局 advice，就会截走 AIModule 的 RagException 处理");
    }

    /** 真实 MethodParameter，避免手工 new 缺参数校验导致 NPE */
    @SuppressWarnings("unused")
    private void headerTarget(@RequestHeader("X-User-Name") String userName) {
    }

    private static MissingRequestHeaderException missingHeader(String headerName) {
        try {
            MethodParameter parameter = new MethodParameter(
                    PracticeExceptionHandlerTest.class.getDeclaredMethod("headerTarget", String.class), 0);
            return new MissingRequestHeaderException(headerName, parameter);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("测试自身的 headerTarget 方法不见了", e);
        }
    }
}
