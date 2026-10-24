package org.leon.aimodule.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.commonjwt.pojo.Result;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 只覆盖 chat 包。不扩宽 rag 包那个 advice 的作用范围：
 * RAG 侧已有测试依赖它当前的映射，合并会把两边异常行为一起改掉。
 * 全部返回 HTTP 200 + Result(code=0)，与仓库既有约定一致。
 *
 * 响应体是手写的，不走内容协商。#7 的 produces 同时声明了
 * text/event-stream 与 application/json，客户端只带
 * {@code Accept: text/event-stream} 时协商不出 JSON，
 * advice 的返回值会被 HttpMediaTypeNotAcceptableException 打回、
 * 最终变成 HTTP 500 空响应体（实测）。"拒绝一定是 JSON Result"
 * 这句契约不该取决于客户端怎么写 Accept。
 */
@Slf4j
@RestControllerAdvice(basePackages = "org.leon.aimodule.chat")
public class ChatExceptionHandler {

    private final ObjectMapper mapper;

    public ChatExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(ChatRejectException.class)
    public void reject(ChatRejectException e, HttpServletResponse response) throws IOException {
        write(Result.error(e.getMessage()), response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public void invalid(MethodArgumentNotValidException e, HttpServletResponse response) throws IOException {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        write(Result.error("参数校验失败: " + msg), response);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public void violated(ConstraintViolationException e, HttpServletResponse response) throws IOException {
        write(Result.error("参数校验失败: " + e.getMessage()), response);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public void missingHeader(MissingRequestHeaderException e, HttpServletResponse response) throws IOException {
        write(Result.error("缺少请求头: " + e.getHeaderName()), response);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public void missingParam(MissingServletRequestParameterException e, HttpServletResponse response)
            throws IOException {
        write(Result.error("缺少参数: " + e.getParameterName()), response);
    }

    /**
     * 客户端把自己的请求体写坏了（坏 JSON、坏编码、?limit=abc、/conversations/abc）。
     * 这类不能让 catch-all 翻成"服务内部错误"并打 ERROR 堆栈：排查方向会被指到服务端，
     * 而真正错的是调用方。只留一行 warn。
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public void badRequest(Exception e, HttpServletResponse response) throws IOException {
        log.warn("[chat] 请求格式错误: {}", rootMessage(e));
        if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            write(Result.error("参数格式错误: " + mismatch.getName()), response);
            return;
        }
        write(Result.error("请求体格式错误"), response);
    }

    @ExceptionHandler(Exception.class)
    public void other(Exception e, HttpServletResponse response) throws IOException {
        log.error("[chat] 未预期异常", e);
        write(Result.error("服务内部错误"), response);
    }

    private void write(Result<?> result, HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            // 已经在推 SSE 帧了，此刻再写 JSON 只会污染流；断流交给 emitter 回调收尾
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), result);
        response.flushBuffer();
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
