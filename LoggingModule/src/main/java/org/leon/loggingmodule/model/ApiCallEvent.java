package org.leon.loggingmodule.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 轨道 A：API 接口使用记录，对应表 {@code sys_api_call_log}。
 */
@Value
@Builder
public class ApiCallEvent implements AuditEvent {

    String traceId;
    String spanId;
    Long userId;
    String userName;
    String sourceService;
    String targetService;
    String httpMethod;
    String requestUri;
    String handlerMethod;
    String remoteAddr;
    String userAgent;
    Integer requestSize;
    Integer responseSize;
    int statusCode;
    String errorCode;
    String errorMsg;
    long costMs;
    LocalDateTime createTime;
}
