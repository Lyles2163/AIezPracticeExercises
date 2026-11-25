package org.leon.loggingmodule.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 审计日志模块配置，对应 Nacos / application.yaml 中的 {@code logging.audit.*}。
 */
@Data
@ConfigurationProperties(prefix = "logging.audit")
public class AuditProperties {

    /** 总开关 */
    private boolean enabled = true;

    /** 不记录的路径前缀/Ant 模式 */
    private List<String> excludePaths = new ArrayList<>(List.of(
            "/actuator/**", "/error", "/swagger-ui/**", "/v3/api-docs/**"));

    /** 采样率 0.0~1.0，1.0 表示全量记录 */
    private double sampleRate = 1.0;

    /** 需要脱敏的字段名列表 */
    private List<String> sensitiveFields = new ArrayList<>(List.of(
            "password", "token", "authorization", "smsCode", "idCard", "phone"));

    /** 异步内存有界队列容量 */
    private int queueCapacity = 10000;

    /** 批量写入条数阈值 */
    private int batchSize = 100;

    /** 定时刷盘间隔（秒） */
    private int flushIntervalSeconds = 5;

    /** API 日志保留天数（预留给归档任务） */
    private int retentionDays = 90;

    /** AI 日志保留天数（预留给归档任务） */
    private int aiRetentionDays = 180;

    /** 是否同时按天汇总写入 sys_ai_usage_log */
    private boolean enableUsageRollup = true;
}
