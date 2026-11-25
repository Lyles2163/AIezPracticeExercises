package org.leon.loggingmodule.recorder;

import lombok.extern.slf4j.Slf4j;
import org.leon.loggingmodule.collector.AuditEventCollector;
import org.leon.loggingmodule.collector.SensitiveDataMasker;
import org.leon.loggingmodule.config.AuditProperties;
import org.leon.loggingmodule.model.AiCallEvent;
import org.leon.loggingmodule.model.ApiCallEvent;
import org.leon.loggingmodule.model.BizAuditEvent;
import org.leon.loggingmodule.storage.AsyncAuditStorage;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 轨道 B/C 对外暴露的统一记录入口（设计文档 §4.2 / §7.3）。
 * 所有记录操作均为异步非阻塞且独立于业务事务，任何内部异常均被吸收，绝不影响业务主链路。
 */
@Slf4j
public class AuditRecorder {

    private static final AuditRecorder NOOP = new AuditRecorder(null, null, null, null);

    private final AuditProperties properties;
    private final AsyncAuditStorage storage;
    private final SensitiveDataMasker masker;
    private final AuditEventCollector collector;

    public AuditRecorder(AuditProperties properties,
                         AsyncAuditStorage storage,
                         SensitiveDataMasker masker,
                         AuditEventCollector collector) {
        this.properties = properties;
        this.storage = storage;
        this.masker = masker;
        this.collector = collector;
    }

    /**
     * 无副作用的空实现，方便单测或不启用审计的场景直接构造业务 Service。
     */
    public static AuditRecorder noop() {
        return NOOP;
    }

    /**
     * 轨道 A：记录一次 API 调用。
     */
    public void recordApiCall(ApiCallEvent event) {
        if (event == null || !isActive()) {
            return;
        }
        try {
            storage.enqueue(event);
        } catch (Throwable t) {
            log.warn("[audit] recordApiCall 异常（已忽略）: {}", t.getMessage());
        }
    }

    /**
     * 轨道 B：显式记录一次 AI 接口调用（对话流式/非流式、生图、RAG 检索）。
     */
    public void recordAiCall(AiCallEvent event) {
        if (event == null || !isActive()) {
            return;
        }
        try {
            String traceId = (event.getTraceId() != null && !event.getTraceId().isBlank())
                    ? event.getTraceId()
                    : (collector != null ? collector.currentTraceId() : UUID.randomUUID().toString().replace("-", ""));
            Long userId = event.getUserId() != null
                    ? event.getUserId()
                    : (collector != null ? collector.currentUserId() : null);
            String userName = (event.getUserName() != null && !event.getUserName().isBlank())
                    ? event.getUserName()
                    : (collector != null ? collector.currentUserName() : null);
            String maskedError = masker != null ? masker.maskMessage(event.getErrorMsg(), 255) : event.getErrorMsg();

            AiCallEvent normalized = AiCallEvent.builder()
                    .traceId(SensitiveDataMasker.truncate(traceId, 64))
                    .userId(userId)
                    .userName(SensitiveDataMasker.truncate(userName, 64))
                    .conversationId(event.getConversationId())
                    .messageId(event.getMessageId())
                    .serviceType(SensitiveDataMasker.truncate(event.getServiceType(), 16))
                    .provider(SensitiveDataMasker.truncate(event.getProvider(), 64))
                    .modelKey(SensitiveDataMasker.truncate(event.getModelKey(), 64))
                    .upstreamModel(SensitiveDataMasker.truncate(event.getUpstreamModel(), 128))
                    .promptTokens(event.getPromptTokens())
                    .completionTokens(event.getCompletionTokens())
                    .reasoningTokens(event.getReasoningTokens())
                    .totalTokens(event.resolvedTotalTokens())
                    .costMs(Math.max(0L, event.getCostMs()))
                    .status(SensitiveDataMasker.truncate(event.getStatus(), 16))
                    .errorMsg(maskedError)
                    .createTime(event.getCreateTime() != null ? event.getCreateTime() : LocalDateTime.now())
                    .build();

            storage.enqueue(normalized);
        } catch (Throwable t) {
            log.warn("[audit] recordAiCall 异常（已忽略）: {}", t.getMessage());
        }
    }

    /**
     * 轨道 C：记录一次业务敏感操作。
     */
    public void recordBiz(BizAuditEvent event) {
        if (event == null || !isActive()) {
            return;
        }
        try {
            String maskedError = masker != null ? masker.maskMessage(event.getErrorMsg(), 512) : event.getErrorMsg();
            BizAuditEvent normalized = BizAuditEvent.builder()
                    .traceId(event.getTraceId())
                    .userId(event.getUserId())
                    .userName(event.getUserName())
                    .targetService(event.getTargetService())
                    .bizType(event.getBizType())
                    .bizDesc(event.getBizDesc())
                    .handlerMethod(event.getHandlerMethod())
                    .success(event.isSuccess())
                    .errorMsg(maskedError)
                    .costMs(Math.max(0L, event.getCostMs()))
                    .createTime(event.getCreateTime() != null ? event.getCreateTime() : LocalDateTime.now())
                    .build();
            storage.enqueue(normalized);
        } catch (Throwable t) {
            log.warn("[audit] recordBiz 异常（已忽略）: {}", t.getMessage());
        }
    }

    private boolean isActive() {
        return storage != null && properties != null && properties.isEnabled();
    }
}
