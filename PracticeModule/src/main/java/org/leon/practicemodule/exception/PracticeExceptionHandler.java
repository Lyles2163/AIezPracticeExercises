package org.leon.practicemodule.exception;

import lombok.extern.slf4j.Slf4j;
import org.leon.commonjwt.pojo.Result;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return Result.error(0, "服务器繁忙，请稍后重试");
    }
}
