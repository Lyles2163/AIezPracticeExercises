package org.leon.loggingmodule.storage;

import lombok.extern.slf4j.Slf4j;
import org.leon.loggingmodule.model.AiCallEvent;
import org.leon.loggingmodule.model.ApiCallEvent;
import org.leon.loggingmodule.model.BizAuditEvent;

import java.util.List;

/**
 * 无 DataSource 环境下的兜底 Sink：仅将审计摘要输出到应用日志，不阻断启动与业务。
 */
@Slf4j
public class LoggingFallbackSink implements AuditEventSink {

    @Override
    public void writeApiCalls(List<ApiCallEvent> events) {
        for (ApiCallEvent e : events) {
            log.info("[audit-api-fallback] traceId={} user={}({}) {} {} -> {} costMs={}",
                    e.getTraceId(), e.getUserName(), e.getUserId(),
                    e.getHttpMethod(), e.getRequestUri(), e.getStatusCode(), e.getCostMs());
        }
    }

    @Override
    public void writeAiCalls(List<AiCallEvent> events) {
        for (AiCallEvent e : events) {
            log.info("[audit-ai-fallback] traceId={} user={}({}) type={} model={} tokens={}/{}/{} status={} costMs={}",
                    e.getTraceId(), e.getUserName(), e.getUserId(), e.getServiceType(),
                    e.getModelKey(), e.getPromptTokens(), e.getCompletionTokens(),
                    e.resolvedTotalTokens(), e.getStatus(), e.getCostMs());
        }
    }

    @Override
    public void writeBizAudits(List<BizAuditEvent> events) {
        for (BizAuditEvent e : events) {
            log.info("[audit-biz-fallback] traceId={} user={}({}) type={} desc={} success={} costMs={}",
                    e.getTraceId(), e.getUserName(), e.getUserId(),
                    e.getBizType(), e.getBizDesc(), e.isSuccess(), e.getCostMs());
        }
    }
}
