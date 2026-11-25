package org.leon.loggingmodule.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 轨道 B：AI 接口调用记录，对应表 {@code sys_ai_call_log}。
 */
@Value
@Builder
public class AiCallEvent implements AuditEvent {

    public static final String SERVICE_CHAT = "CHAT";
    public static final String SERVICE_IMAGE = "IMAGE";
    public static final String SERVICE_RAG = "RAG";

    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_TRUNCATED = "truncated";

    String traceId;
    Long userId;
    String userName;
    Long conversationId;
    Long messageId;
    String serviceType;
    String provider;
    String modelKey;
    String upstreamModel;
    Integer promptTokens;
    Integer completionTokens;
    Integer reasoningTokens;
    Integer totalTokens;
    long costMs;
    String status;
    String errorMsg;
    LocalDateTime createTime;

    /**
     * 计算总 token 数：优先使用显式 totalTokens，否则 prompt + completion（不累加 reasoning）。
     */
    public Integer resolvedTotalTokens() {
        if (totalTokens != null) {
            return totalTokens;
        }
        if (promptTokens == null && completionTokens == null) {
            return null;
        }
        return (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
    }
}
