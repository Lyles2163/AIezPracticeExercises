package org.leon.loggingmodule.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 轨道 C：业务敏感操作审计事件（由 {@code @LogBiz} 或显式调用产生）。
 */
@Value
@Builder
public class BizAuditEvent implements AuditEvent {

    String traceId;
    Long userId;
    String userName;
    String targetService;
    String bizType;
    String bizDesc;
    String handlerMethod;
    boolean success;
    String errorMsg;
    long costMs;
    LocalDateTime createTime;
}
