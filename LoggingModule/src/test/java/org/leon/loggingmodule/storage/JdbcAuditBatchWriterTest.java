package org.leon.loggingmodule.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leon.loggingmodule.config.AuditProperties;
import org.leon.loggingmodule.model.AiCallEvent;
import org.leon.loggingmodule.model.ApiCallEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAuditBatchWriterTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcAuditBatchWriter writer;

    @BeforeEach
    void setUp() {
        String dbName = "audit_test_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
                CREATE TABLE sys_api_call_log (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  trace_id VARCHAR(64) NOT NULL DEFAULT '',
                  span_id VARCHAR(64),
                  user_id BIGINT,
                  user_name VARCHAR(64),
                  source_service VARCHAR(64) NOT NULL,
                  target_service VARCHAR(64) NOT NULL,
                  http_method VARCHAR(10) NOT NULL,
                  request_uri VARCHAR(512) NOT NULL,
                  handler_method VARCHAR(256),
                  remote_addr VARCHAR(64),
                  user_agent VARCHAR(256),
                  request_size INT,
                  response_size INT,
                  status_code SMALLINT NOT NULL,
                  error_code VARCHAR(32),
                  error_msg VARCHAR(512),
                  cost_ms INT NOT NULL,
                  create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                )
                """);

        jdbc.execute("""
                CREATE TABLE sys_ai_call_log (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  trace_id VARCHAR(64) NOT NULL DEFAULT '',
                  user_id BIGINT,
                  user_name VARCHAR(64),
                  conversation_id BIGINT,
                  message_id BIGINT,
                  service_type VARCHAR(16) NOT NULL,
                  provider VARCHAR(64),
                  model_key VARCHAR(64),
                  upstream_model VARCHAR(128),
                  prompt_tokens INT,
                  completion_tokens INT,
                  reasoning_tokens INT,
                  total_tokens INT,
                  cost_ms INT NOT NULL,
                  status VARCHAR(16) NOT NULL,
                  error_msg VARCHAR(255),
                  create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                )
                """);

        jdbc.execute("""
                CREATE TABLE sys_ai_usage_log (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  user_name VARCHAR(64) NOT NULL,
                  service_type VARCHAR(16) NOT NULL,
                  model_key VARCHAR(64) NOT NULL,
                  prompt_tokens INT NOT NULL DEFAULT 0,
                  completion_tokens INT NOT NULL DEFAULT 0,
                  total_tokens INT NOT NULL DEFAULT 0,
                  stat_date DATE NOT NULL,
                  create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  CONSTRAINT uk_user_model_date UNIQUE (user_id, model_key, stat_date)
                )
                """);

        this.writer = new JdbcAuditBatchWriter(ds, new AuditProperties());
    }

    @Test
    void writeApiCallsAndAiCalls_persistsRowsAndUsageRollup() {
        writer.writeApiCalls(List.of(ApiCallEvent.builder()
                .traceId("trace-100")
                .spanId("span-1")
                .userId(7L)
                .userName("leon")
                .sourceService("gateway")
                .targetService("UserModuleServer")
                .httpMethod("GET")
                .requestUri("/user/profile")
                .handlerMethod("UserController#profile")
                .remoteAddr("10.0.0.1")
                .statusCode(200)
                .costMs(25)
                .createTime(LocalDateTime.now())
                .build()));

        Map<String, Object> apiRow = jdbc.queryForMap("SELECT * FROM sys_api_call_log");
        assertThat(apiRow.get("TRACE_ID")).isEqualTo("trace-100");
        assertThat(((Number) apiRow.get("USER_ID")).longValue()).isEqualTo(7L);
        assertThat(apiRow.get("USER_NAME")).isEqualTo("leon");
        assertThat(((Number) apiRow.get("STATUS_CODE")).intValue()).isEqualTo(200);

        writer.writeAiCalls(List.of(AiCallEvent.builder()
                .traceId("trace-ai-1")
                .userId(7L)
                .userName("leon")
                .conversationId(10L)
                .messageId(20L)
                .serviceType(AiCallEvent.SERVICE_CHAT)
                .provider("deepseek")
                .modelKey("deepseek-reasoner")
                .upstreamModel("deepseek-reasoner")
                .promptTokens(120)
                .completionTokens(80)
                .reasoningTokens(30) // completion 的拆分，不应累加进 total_tokens
                .costMs(850)
                .status(AiCallEvent.STATUS_DONE)
                .createTime(LocalDateTime.now())
                .build()));

        Map<String, Object> aiRow = jdbc.queryForMap("SELECT * FROM sys_ai_call_log");
        assertThat(aiRow.get("MODEL_KEY")).isEqualTo("deepseek-reasoner");
        assertThat(((Number) aiRow.get("PROMPT_TOKENS")).intValue()).isEqualTo(120);
        assertThat(((Number) aiRow.get("COMPLETION_TOKENS")).intValue()).isEqualTo(80);
        assertThat(((Number) aiRow.get("REASONING_TOKENS")).intValue()).isEqualTo(30);
        assertThat(((Number) aiRow.get("TOTAL_TOKENS")).intValue()).isEqualTo(200);

        Map<String, Object> usageRow = jdbc.queryForMap("SELECT * FROM sys_ai_usage_log");
        assertThat(((Number) usageRow.get("TOTAL_TOKENS")).intValue()).isEqualTo(200);
    }

    @Test
    void auditWrite_survivesOuterBusinessTransactionRollback() {
        TransactionTemplate outerTx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        outerTx.executeWithoutResult(status -> {
            writer.writeApiCalls(List.of(ApiCallEvent.builder()
                    .traceId("trace-rollback-test")
                    .sourceService("gateway")
                    .targetService("PracticeModuleServer")
                    .httpMethod("POST")
                    .requestUri("/practice/submit")
                    .statusCode(500)
                    .costMs(42)
                    .createTime(LocalDateTime.now())
                    .build()));
            status.setRollbackOnly();
        });

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_api_call_log WHERE trace_id = 'trace-rollback-test'", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
