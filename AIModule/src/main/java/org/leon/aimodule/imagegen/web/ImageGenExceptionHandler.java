package org.leon.aimodule.imagegen.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.imagegen.exception.ImageGenException;
import org.leon.commonjwt.pojo.Result;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 只覆盖 imagegen 包。不扩宽 chat 那个 advice 的作用范围，两个模块的异常映射互不影响。
 * 约定与全仓一致：业务拒绝 = HTTP 200 + Result(code=0, msg)，msg 是可直接展示的中文。
 * 响应体手写，不走内容协商 —— 取图端点会把 Accept 带成 image/*，协商不出 JSON。
 */
@Slf4j
@RestControllerAdvice(basePackages = "org.leon.aimodule.imagegen")
public class ImageGenExceptionHandler {

    private final ObjectMapper mapper;

    public ImageGenExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler({ImageGenException.class, ChatRejectException.class})
    public void reject(RuntimeException e, HttpServletResponse response) throws IOException {
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

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public void badRequest(Exception e, HttpServletResponse response) throws IOException {
        log.warn("[imagegen] 请求格式错误: {}", e.getClass().getSimpleName());
        write(Result.error("请求体格式错误"), response);
    }

    @ExceptionHandler(Exception.class)
    public void other(Exception e, HttpServletResponse response) throws IOException {
        log.error("[imagegen] 未预期异常", e);
        write(Result.error("服务内部错误"), response);
    }

    private void write(Result<?> result, HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), result);
        response.flushBuffer();
    }
}
