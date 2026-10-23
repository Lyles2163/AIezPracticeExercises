package org.leon.practicemodule.exception;

import lombok.extern.slf4j.Slf4j;
import org.leon.commonjwt.pojo.Result;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 按包作用域收口，不扩 AIModule 的 AiExceptionHandler.basePackages（它的映射有测试依赖）。
 * 一律 HTTP 200 + Result{code:子码}，与仓库现有约定一致。
 */
@Slf4j
@RestControllerAdvice(basePackages = "org.leon.practicemodule")
public class PracticeExceptionHandler {

    @ExceptionHandler(PracticeException.class)
    public Result<Void> handlePracticeException(PracticeException e) {
        log.warn("业务异常 code={} msg={}", e.errorCode().code(), e.getMessage());
        return Result.error(e.errorCode().code(), e.getMessage());
    }

    /**
     * 缺 X-User-Name 只可能是绕过了网关（正常链路下网关必然注入该头），
     * 语义上是身份缺失而非服务器故障，故回 40100 让前端跳登录。
     * 本模块目前唯一的 @RequestHeader 就是它；将来若有别的必填头，须按头名分流。
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public Result<Void> handleMissingHeader(MissingRequestHeaderException e) {
        if ("X-User-Name".equals(e.getHeaderName())) {
            log.warn("缺少身份请求头，疑似绕过网关直连");
            return Result.error(PracticeErrorCode.IDENTITY_MISSING.code(), "用户身份不存在，请重新登录");
        }
        log.warn("缺少请求头 {}", e.getHeaderName());
        return Result.error(PracticeErrorCode.BAD_REQUEST.code(), "请求参数不合法");
    }

    /** 请求体不是合法 JSON、或参数类型不匹配 —— 都是客户端错误，不是 5xx */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public Result<Void> handleBadRequest(Exception e) {
        log.warn("请求不合法: {}", e.getMessage());
        return Result.error(PracticeErrorCode.BAD_REQUEST.code(), "请求参数不合法");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return Result.error(0, "服务器繁忙，请稍后重试");
    }
}
