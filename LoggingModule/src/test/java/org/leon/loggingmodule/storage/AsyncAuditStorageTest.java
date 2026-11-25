package org.leon.loggingmodule.storage;

import org.junit.jupiter.api.Test;
import org.leon.loggingmodule.config.AuditProperties;
import org.leon.loggingmodule.model.AiCallEvent;
import org.leon.loggingmodule.model.ApiCallEvent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AsyncAuditStorageTest {

    @Test
    void enqueueAndFlush_persistsBatchesAndTracksMetrics() {
        AuditProperties props = new AuditProperties();
        props.setQueueCapacity(100);
        props.setBatchSize(10);
        props.setFlushIntervalSeconds(60);

        List<ApiCallEvent> capturedApi = Collections.synchronizedList(new ArrayList<>());
        List<AiCallEvent> capturedAi = Collections.synchronizedList(new ArrayList<>());
        AuditEventSink sink = new AuditEventSink() {
            @Override
            public void writeApiCalls(List<ApiCallEvent> events) {
                capturedApi.addAll(events);
            }

            @Override
            public void writeAiCalls(List<AiCallEvent> events) {
                capturedAi.addAll(events);
            }
        };
        AuditMetrics metrics = new AuditMetrics();
        AsyncAuditStorage storage = new AsyncAuditStorage(props, sink, metrics);
        try {
            storage.enqueue(sampleApiEvent("t1"));
            storage.enqueue(sampleApiEvent("t2"));
            storage.enqueue(sampleAiEvent("t3"));
            storage.flushNow();

            assertThat(capturedApi).hasSize(2);
            assertThat(capturedAi).hasSize(1);
            assertThat(metrics.getReceived()).isEqualTo(3);
            assertThat(metrics.getPersisted()).isEqualTo(3);
            assertThat(metrics.getDropped()).isZero();
            assertThat(metrics.getFailed()).isZero();
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void queueFull_dropsNewEventsWithoutBlocking() {
        AuditProperties props = new AuditProperties();
        props.setQueueCapacity(2);
        props.setBatchSize(100);
        props.setFlushIntervalSeconds(60);

        AuditMetrics metrics = new AuditMetrics();
        AuditEventSink noopSink = new LoggingFallbackSink();
        AsyncAuditStorage storage = new AsyncAuditStorage(props, noopSink, metrics);
        try {
            assertThat(storage.enqueue(sampleApiEvent("t1"))).isTrue();
            assertThat(storage.enqueue(sampleApiEvent("t2"))).isTrue();
            assertThat(storage.enqueue(sampleApiEvent("t3"))).isFalse();

            assertThat(metrics.getReceived()).isEqualTo(3);
            assertThat(metrics.getDropped()).isEqualTo(1);
        } finally {
            storage.shutdown();
        }
    }

    @Test
    void sinkFailure_degradesGracefullyAndCountsFailed() {
        AuditProperties props = new AuditProperties();
        props.setQueueCapacity(50);
        props.setBatchSize(10);
        props.setFlushIntervalSeconds(60);

        AuditEventSink failingSink = new AuditEventSink() {
            @Override
            public void writeApiCalls(List<ApiCallEvent> events) {
                throw new RuntimeException("DB connection refused");
            }

            @Override
            public void writeAiCalls(List<AiCallEvent> events) {
                throw new RuntimeException("DB connection refused");
            }
        };
        AuditMetrics metrics = new AuditMetrics();
        AsyncAuditStorage storage = new AsyncAuditStorage(props, failingSink, metrics);
        try {
            storage.enqueue(sampleApiEvent("t1"));
            storage.enqueue(sampleAiEvent("t2"));
            assertThatCode(storage::flushNow).doesNotThrowAnyException();

            assertThat(metrics.getReceived()).isEqualTo(2);
            assertThat(metrics.getPersisted()).isZero();
            assertThat(metrics.getFailed()).isEqualTo(2);
        } finally {
            storage.shutdown();
        }
    }

    private static ApiCallEvent sampleApiEvent(String traceId) {
        return ApiCallEvent.builder()
                .traceId(traceId)
                .sourceService("gateway")
                .targetService("UserModuleServer")
                .httpMethod("GET")
                .requestUri("/user/profile")
                .statusCode(200)
                .costMs(12)
                .createTime(LocalDateTime.now())
                .build();
    }

    private static AiCallEvent sampleAiEvent(String traceId) {
        return AiCallEvent.builder()
                .traceId(traceId)
                .userId(1L)
                .userName("leon")
                .serviceType(AiCallEvent.SERVICE_CHAT)
                .modelKey("deepseek-chat")
                .promptTokens(100)
                .completionTokens(50)
                .costMs(300)
                .status(AiCallEvent.STATUS_DONE)
                .createTime(LocalDateTime.now())
                .build();
    }
}
