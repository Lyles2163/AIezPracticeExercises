package org.leon.loggingmodule.storage;

import lombok.extern.slf4j.Slf4j;
import org.leon.loggingmodule.config.AuditProperties;
import org.leon.loggingmodule.model.AiCallEvent;
import org.leon.loggingmodule.model.ApiCallEvent;
import org.leon.loggingmodule.model.BizAuditEvent;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 基于独立 {@link DataSourceTransactionManager} + {@code PROPAGATION_REQUIRES_NEW} 的批量写入器（设计文档 §8.3）。
 * 审计落库与业务事务完全解耦：业务回滚不撤销审计记录，审计落库失败也不影响业务事务。
 */
@Slf4j
public class JdbcAuditBatchWriter implements AuditEventSink {

    private static final String INSERT_API_CALL_SQL = """
            INSERT INTO sys_api_call_log (
                trace_id, span_id, user_id, user_name, source_service, target_service,
                http_method, request_uri, handler_method, remote_addr, user_agent,
                request_size, response_size, status_code, error_code, error_msg,
                cost_ms, create_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_AI_CALL_SQL = """
            INSERT INTO sys_ai_call_log (
                trace_id, user_id, user_name, conversation_id, message_id,
                service_type, provider, model_key, upstream_model,
                prompt_tokens, completion_tokens, reasoning_tokens, total_tokens,
                cost_ms, status, error_msg, create_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPSERT_AI_USAGE_SQL = """
            INSERT INTO sys_ai_usage_log (
                user_id, user_name, service_type, model_key,
                prompt_tokens, completion_tokens, total_tokens, stat_date, create_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                prompt_tokens = prompt_tokens + VALUES(prompt_tokens),
                completion_tokens = completion_tokens + VALUES(completion_tokens),
                total_tokens = total_tokens + VALUES(total_tokens)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate requiresNewTx;
    private final AuditProperties properties;

    public JdbcAuditBatchWriter(DataSource dataSource, AuditProperties properties) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
    }

    @Override
    public void writeApiCalls(List<ApiCallEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        requiresNewTx.executeWithoutResult(status ->
                jdbcTemplate.batchUpdate(INSERT_API_CALL_SQL, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ApiCallEvent e = events.get(i);
                        ps.setString(1, nonNull(e.getTraceId()));
                        setNullableString(ps, 2, e.getSpanId());
                        setNullableLong(ps, 3, e.getUserId());
                        setNullableString(ps, 4, e.getUserName());
                        ps.setString(5, nonNullOrDefault(e.getSourceService(), "unknown"));
                        ps.setString(6, nonNullOrDefault(e.getTargetService(), "unknown"));
                        ps.setString(7, nonNullOrDefault(e.getHttpMethod(), "UNKNOWN"));
                        ps.setString(8, nonNull(e.getRequestUri()));
                        setNullableString(ps, 9, e.getHandlerMethod());
                        setNullableString(ps, 10, e.getRemoteAddr());
                        setNullableString(ps, 11, e.getUserAgent());
                        setNullableInt(ps, 12, e.getRequestSize());
                        setNullableInt(ps, 13, e.getResponseSize());
                        ps.setInt(14, e.getStatusCode());
                        setNullableString(ps, 15, e.getErrorCode());
                        setNullableString(ps, 16, e.getErrorMsg());
                        ps.setLong(17, Math.max(0L, e.getCostMs()));
                        ps.setTimestamp(18, toTimestamp(e.getCreateTime()));
                    }

                    @Override
                    public int getBatchSize() {
                        return events.size();
                    }
                }));
    }

    @Override
    public void writeAiCalls(List<AiCallEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        requiresNewTx.executeWithoutResult(status -> {
            jdbcTemplate.batchUpdate(INSERT_AI_CALL_SQL, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    AiCallEvent e = events.get(i);
                    ps.setString(1, nonNull(e.getTraceId()));
                    setNullableLong(ps, 2, e.getUserId());
                    setNullableString(ps, 3, e.getUserName());
                    setNullableLong(ps, 4, e.getConversationId());
                    setNullableLong(ps, 5, e.getMessageId());
                    ps.setString(6, nonNullOrDefault(e.getServiceType(), AiCallEvent.SERVICE_CHAT));
                    setNullableString(ps, 7, e.getProvider());
                    setNullableString(ps, 8, e.getModelKey());
                    setNullableString(ps, 9, e.getUpstreamModel());
                    setNullableInt(ps, 10, e.getPromptTokens());
                    setNullableInt(ps, 11, e.getCompletionTokens());
                    setNullableInt(ps, 12, e.getReasoningTokens());
                    setNullableInt(ps, 13, e.resolvedTotalTokens());
                    ps.setLong(14, Math.max(0L, e.getCostMs()));
                    ps.setString(15, nonNullOrDefault(e.getStatus(), AiCallEvent.STATUS_DONE));
                    setNullableString(ps, 16, e.getErrorMsg());
                    ps.setTimestamp(17, toTimestamp(e.getCreateTime()));
                }

                @Override
                public int getBatchSize() {
                    return events.size();
                }
            });

            if (properties.isEnableUsageRollup()) {
                List<AiCallEvent> rollupCandidates = events.stream()
                        .filter(e -> e.getUserId() != null
                                && e.getUserName() != null
                                && e.getModelKey() != null
                                && e.resolvedTotalTokens() != null
                                && e.resolvedTotalTokens() > 0)
                        .toList();
                if (!rollupCandidates.isEmpty()) {
                    try {
                        jdbcTemplate.batchUpdate(UPSERT_AI_USAGE_SQL, new BatchPreparedStatementSetter() {
                            @Override
                            public void setValues(PreparedStatement ps, int i) throws SQLException {
                                AiCallEvent e = rollupCandidates.get(i);
                                LocalDateTime ts = e.getCreateTime() != null ? e.getCreateTime() : LocalDateTime.now();
                                ps.setLong(1, e.getUserId());
                                ps.setString(2, e.getUserName());
                                ps.setString(3, nonNullOrDefault(e.getServiceType(), AiCallEvent.SERVICE_CHAT));
                                ps.setString(4, e.getModelKey());
                                ps.setInt(5, e.getPromptTokens() == null ? 0 : e.getPromptTokens());
                                ps.setInt(6, e.getCompletionTokens() == null ? 0 : e.getCompletionTokens());
                                ps.setInt(7, e.resolvedTotalTokens());
                                ps.setDate(8, Date.valueOf(LocalDate.from(ts)));
                                ps.setTimestamp(9, Timestamp.valueOf(ts));
                            }

                            @Override
                            public int getBatchSize() {
                                return rollupCandidates.size();
                            }
                        });
                    } catch (Exception ex) {
                        // 扩展汇总表异常不影响主表 sys_ai_call_log 落库
                        log.warn("[audit] sys_ai_usage_log 汇总写入失败（已忽略，不影响主表）: {}", ex.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void writeBizAudits(List<BizAuditEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (BizAuditEvent e : events) {
            log.info("[audit-biz] traceId={} user={}({}) service={} type={} desc={} method={} success={} costMs={} err={}",
                    e.getTraceId(), e.getUserName(), e.getUserId(), e.getTargetService(),
                    e.getBizType(), e.getBizDesc(), e.getHandlerMethod(),
                    e.isSuccess(), e.getCostMs(), e.getErrorMsg());
        }
    }

    private static void setNullableString(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.VARCHAR);
        } else {
            ps.setString(idx, value);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.BIGINT);
        } else {
            ps.setLong(idx, value);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.INTEGER);
        } else {
            ps.setInt(idx, value);
        }
    }

    private static Timestamp toTimestamp(LocalDateTime time) {
        return Timestamp.valueOf(time != null ? time : LocalDateTime.now());
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static String nonNullOrDefault(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }
}
