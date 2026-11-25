package org.leon.loggingmodule.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.leon.loggingmodule.annotation.LogBiz;
import org.leon.loggingmodule.collector.AuditEventCollector;
import org.leon.loggingmodule.config.AuditProperties;
import org.leon.loggingmodule.model.BizAuditEvent;
import org.leon.loggingmodule.recorder.AuditRecorder;

import java.time.LocalDateTime;

/**
 * 轨道 C：业务敏感操作切面（设计文档 §6.3），拦截标注了 {@link LogBiz} 的方法。
 */
@Slf4j
@Aspect
public class BizLogAspect {

    private final AuditProperties properties;
    private final AuditEventCollector collector;
    private final AuditRecorder recorder;

    public BizLogAspect(AuditProperties properties,
                        AuditEventCollector collector,
                        AuditRecorder recorder) {
        this.properties = properties;
        this.collector = collector;
        this.recorder = recorder;
    }

    @Around("@annotation(logBiz)")
    public Object aroundBiz(ProceedingJoinPoint joinPoint, LogBiz logBiz) throws Throwable {
        if (!properties.isEnabled()) {
            return joinPoint.proceed();
        }
        long startNanos = System.nanoTime();
        Throwable thrown = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable ex) {
            thrown = ex;
            throw ex;
        } finally {
            recordSafely(joinPoint, logBiz, thrown, startNanos);
        }
    }

    private void recordSafely(ProceedingJoinPoint joinPoint, LogBiz logBiz, Throwable thrown, long startNanos) {
        try {
            long costMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String handlerMethod = joinPoint.getSignature().getDeclaringTypeName()
                    + "#" + joinPoint.getSignature().getName();
            BizAuditEvent event = BizAuditEvent.builder()
                    .traceId(collector.currentTraceId())
                    .userId(collector.currentUserId())
                    .userName(collector.currentUserName())
                    .targetService(collector.getApplicationName())
                    .bizType(logBiz.type())
                    .bizDesc(logBiz.value())
                    .handlerMethod(handlerMethod)
                    .success(thrown == null)
                    .errorMsg(thrown == null ? null : thrown.getMessage())
                    .costMs(Math.max(0L, costMs))
                    .createTime(LocalDateTime.now())
                    .build();
            recorder.recordBiz(event);
        } catch (Throwable t) {
            log.warn("[audit] BizLogAspect 记录失败（已忽略）: {}", t.getMessage());
        }
    }
}
