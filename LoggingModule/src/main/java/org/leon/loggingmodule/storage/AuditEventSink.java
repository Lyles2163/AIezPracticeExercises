package org.leon.loggingmodule.storage;

import org.leon.loggingmodule.model.AiCallEvent;
import org.leon.loggingmodule.model.ApiCallEvent;
import org.leon.loggingmodule.model.BizAuditEvent;

import java.util.List;

/**
 * 审计事件批量落库接口。默认由 {@link JdbcAuditBatchWriter} 实现；无数据源环境降级为日志打印。
 */
public interface AuditEventSink {

    void writeApiCalls(List<ApiCallEvent> events);

    void writeAiCalls(List<AiCallEvent> events);

    default void writeBizAudits(List<BizAuditEvent> events) {
        // 默认由日志输出，不强绑定独立表
    }
}
