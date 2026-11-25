package org.leon.loggingmodule.model;

/**
 * 审计事件顶层标记接口，便于统一进入异步有界队列。
 */
public sealed interface AuditEvent permits ApiCallEvent, AiCallEvent, BizAuditEvent {
}
