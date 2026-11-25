package org.leon.loggingmodule.config;

import org.leon.loggingmodule.aspect.ApiCallAuditAspect;
import org.leon.loggingmodule.aspect.BizLogAspect;
import org.leon.loggingmodule.collector.AuditEventCollector;
import org.leon.loggingmodule.collector.SensitiveDataMasker;
import org.leon.loggingmodule.recorder.AuditRecorder;
import org.leon.loggingmodule.storage.AsyncAuditStorage;
import org.leon.loggingmodule.storage.AuditEventSink;
import org.leon.loggingmodule.storage.AuditMetrics;
import org.leon.loggingmodule.storage.JdbcAuditBatchWriter;
import org.leon.loggingmodule.storage.LoggingFallbackSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * LoggingModule 自动装配：
 * 其他业务微服务（UserModule / PracticeModule / AIModule / AuthModule）引入 LoggingModule 依赖后，
 * 自动注册轨道 A（Controller AOP）、轨道 B（AuditRecorder）、轨道 C（@LogBiz）与异步批量落库链路。
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(AuditProperties.class)
@ConditionalOnProperty(prefix = "logging.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LoggingAuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SensitiveDataMasker sensitiveDataMasker(AuditProperties properties) {
        return new SensitiveDataMasker(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditMetrics auditMetrics() {
        return new AuditMetrics();
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditEventSink auditEventSink(ObjectProvider<DataSource> dataSourceProvider,
                                         AuditProperties properties) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource != null) {
            return new JdbcAuditBatchWriter(dataSource, properties);
        }
        return new LoggingFallbackSink();
    }

    @Bean
    @ConditionalOnMissingBean
    public AsyncAuditStorage asyncAuditStorage(AuditProperties properties,
                                               AuditEventSink sink,
                                               AuditMetrics metrics) {
        return new AsyncAuditStorage(properties, sink, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditEventCollector auditEventCollector(SensitiveDataMasker masker,
                                                   @Value("${spring.application.name:unknown}") String appName) {
        return new AuditEventCollector(masker, appName);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditRecorder auditRecorder(AuditProperties properties,
                                       AsyncAuditStorage storage,
                                       SensitiveDataMasker masker,
                                       AuditEventCollector collector) {
        return new AuditRecorder(properties, storage, masker, collector);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiCallAuditAspect apiCallAuditAspect(AuditProperties properties,
                                                 AuditEventCollector collector,
                                                 AuditRecorder recorder) {
        return new ApiCallAuditAspect(properties, collector, recorder);
    }

    @Bean
    @ConditionalOnMissingBean
    public BizLogAspect bizLogAspect(AuditProperties properties,
                                     AuditEventCollector collector,
                                     AuditRecorder recorder) {
        return new BizLogAspect(properties, collector, recorder);
    }
}
