package org.leon.loggingmodule.aspect;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.leon.loggingmodule.annotation.LogIgnore;
import org.leon.loggingmodule.collector.AuditEventCollector;
import org.leon.loggingmodule.config.AuditProperties;
import org.leon.loggingmodule.model.ApiCallEvent;
import org.leon.loggingmodule.recorder.AuditRecorder;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.AntPathMatcher;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 轨道 A：Controller 层边界自动记录切面（设计文档 §6.1）。
 * 拦截 {@code org.leon..*} 包下所有带 Spring Web 映射注解的 Controller 方法，
 * 支持总开关、{@link LogIgnore} 逃生舱、排除路径前缀与采样率配置。
 */
@Slf4j
@Aspect
public class ApiCallAuditAspect {

    private final AuditProperties properties;
    private final AuditEventCollector collector;
    private final AuditRecorder recorder;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ApiCallAuditAspect(AuditProperties properties,
                              AuditEventCollector collector,
                              AuditRecorder recorder) {
        this.properties = properties;
        this.collector = collector;
        this.recorder = recorder;
    }

    @Around("within(org.leon..*) && ("
            + "@within(org.springframework.web.bind.annotation.RestController) || "
            + "@within(org.springframework.stereotype.Controller)) && ("
            + "@annotation(org.springframework.web.bind.annotation.RequestMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.GetMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.PostMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.PutMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.DeleteMapping) || "
            + "@annotation(org.springframework.web.bind.annotation.PatchMapping))")
    public Object aroundController(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!properties.isEnabled() || shouldIgnore(joinPoint)) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = AuditEventCollector.currentRequest();
        if (request != null && isExcludedPath(request.getRequestURI())) {
            return joinPoint.proceed();
        }
        if (!shouldSample()) {
            return joinPoint.proceed();
        }

        long startNanos = System.nanoTime();
        Object result = null;
        Throwable thrown = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable ex) {
            thrown = ex;
            throw ex;
        } finally {
            recordSafely(joinPoint, request, AuditEventCollector.currentResponse(), result, thrown, startNanos);
        }
    }

    private void recordSafely(ProceedingJoinPoint joinPoint,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              Object result,
                              Throwable thrown,
                              long startNanos) {
        try {
            long costMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String handlerMethod = joinPoint.getSignature().getDeclaringTypeName()
                    + "#" + joinPoint.getSignature().getName();
            ApiCallEvent event = collector.collectApiCall(request, response, handlerMethod, result, thrown, costMs);
            recorder.recordApiCall(event);
        } catch (Throwable t) {
            log.warn("[audit] ApiCallAuditAspect 记录失败（已忽略，不影响业务）: {}", t.getMessage());
        }
    }

    private boolean shouldIgnore(ProceedingJoinPoint joinPoint) {
        try {
            if (joinPoint.getSignature() instanceof MethodSignature ms) {
                Method method = ms.getMethod();
                if (AnnotatedElementUtils.hasAnnotation(method, LogIgnore.class)) {
                    return true;
                }
                Class<?> targetClass = joinPoint.getTarget() != null
                        ? joinPoint.getTarget().getClass()
                        : ms.getDeclaringType();
                return AnnotatedElementUtils.hasAnnotation(targetClass, LogIgnore.class);
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    boolean isExcludedPath(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        List<String> excludes = properties.getExcludePaths();
        if (excludes == null || excludes.isEmpty()) {
            return false;
        }
        for (String raw : excludes) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            for (String pattern : raw.split(",")) {
                String p = pattern.trim();
                if (!p.isEmpty() && (pathMatcher.match(p, uri) || uri.startsWith(p))) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean shouldSample() {
        double rate = properties.getSampleRate();
        if (rate >= 1.0) {
            return true;
        }
        if (rate <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < rate;
    }
}
