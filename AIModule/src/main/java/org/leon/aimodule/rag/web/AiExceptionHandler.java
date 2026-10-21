package org.leon.aimodule.rag.web;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.transport.TransportException;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.rag.exception.RagException;
import org.leon.commonjwt.pojo.Result;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice(basePackages = "org.leon.aimodule.rag")
public class AiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Result.error("参数校验失败: " + msg);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public Result<Void> handleMissingHeader(MissingRequestHeaderException e) {
        return Result.error("缺少请求头: " + e.getHeaderName());
    }

    @ExceptionHandler(RagException.class)
    public Result<Void> handleRag(RagException e) {
        log.warn("[RAG] 业务异常: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler({ElasticsearchException.class, TransportException.class, IOException.class})
    public Result<Void> handleEs(Exception e) {
        log.warn("[RAG] 检索服务不可用: {}", e.getMessage());
        return Result.error("检索服务暂时不可用");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("[RAG] 未预期异常", e);
        return Result.error("服务内部错误");
    }
}
