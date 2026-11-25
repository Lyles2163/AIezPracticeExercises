package org.leon.loggingmodule.storage;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.leon.loggingmodule.config.AuditProperties;
import org.leon.loggingmodule.model.AiCallEvent;
import org.leon.loggingmodule.model.ApiCallEvent;
import org.leon.loggingmodule.model.AuditEvent;
import org.leon.loggingmodule.model.BizAuditEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步批量落库引擎（设计文档 §8.1 / §8.2）：
 * 1. 有界内存队列，入队非阻塞（offer < 1ms），队满按策略丢弃新事件并累加 dropped 计数；
 * 2. 达到 batchSize 或每 flushIntervalSeconds 触发一次批量刷盘；
 * 3. 数据库不可用时仅计 failed 计数并打印内部告警日志，绝不向业务线程抛异常。
 */
@Slf4j
public class AsyncAuditStorage {

    private final BlockingQueue<AuditEvent> queue;
    private final AuditEventSink sink;
    private final AuditMetrics metrics;
    private final int batchSize;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    public AsyncAuditStorage(AuditProperties properties, AuditEventSink sink, AuditMetrics metrics) {
        int capacity = Math.max(1, properties.getQueueCapacity());
        this.batchSize = Math.max(1, properties.getBatchSize());
        int flushInterval = Math.max(1, properties.getFlushIntervalSeconds());
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.sink = sink;
        this.metrics = metrics;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-log-flusher");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleWithFixedDelay(this::flushSafely, flushInterval, flushInterval, TimeUnit.SECONDS);
    }

    /**
     * 非阻塞入队（目标 < 1ms）。
     */
    public boolean enqueue(AuditEvent event) {
        if (event == null) {
            return false;
        }
        metrics.incReceived();
        boolean offered = queue.offer(event);
        if (!offered) {
            metrics.incDropped();
            log.warn("[audit] 审计队列已满，丢弃新事件 ({})", metrics.summary());
            return false;
        }
        if (queue.size() >= batchSize && flushScheduled.compareAndSet(false, true)) {
            try {
                scheduler.execute(() -> {
                    try {
                        flushSafely();
                    } finally {
                        flushScheduled.set(false);
                    }
                });
            } catch (Exception e) {
                flushScheduled.set(false);
            }
        }
        return true;
    }

    /**
     * 将当前队列中的事件按 batchSize 分批刷盘。可在测试中显式调用。
     */
    public void flushNow() {
        List<AuditEvent> batch = new ArrayList<>(batchSize);
        while (queue.drainTo(batch, batchSize) > 0) {
            dispatchBatch(batch);
            batch.clear();
        }
    }

    private void flushSafely() {
        try {
            flushNow();
        } catch (Throwable t) {
            log.warn("[audit] 审计刷盘线程异常（已降级忽略）: {}", t.getMessage());
        }
    }

    private void dispatchBatch(List<AuditEvent> batch) {
        List<ApiCallEvent> apiEvents = new ArrayList<>();
        List<AiCallEvent> aiEvents = new ArrayList<>();
        List<BizAuditEvent> bizEvents = new ArrayList<>();
        for (AuditEvent event : batch) {
            if (event instanceof ApiCallEvent api) {
                apiEvents.add(api);
            } else if (event instanceof AiCallEvent ai) {
                aiEvents.add(ai);
            } else if (event instanceof BizAuditEvent biz) {
                bizEvents.add(biz);
            }
        }
        persistGroup(apiEvents, sink::writeApiCalls, "sys_api_call_log");
        persistGroup(aiEvents, sink::writeAiCalls, "sys_ai_call_log");
        persistGroup(bizEvents, sink::writeBizAudits, "biz_audit");
    }

    private <T> void persistGroup(List<T> events, java.util.function.Consumer<List<T>> writer, String label) {
        if (events.isEmpty()) {
            return;
        }
        try {
            writer.accept(events);
            metrics.addPersisted(events.size());
        } catch (Throwable t) {
            metrics.addFailed(events.size());
            log.warn("[audit] 批量写入 {} 失败（共 {} 条，已降级丢弃，{}）: {}",
                    label, events.size(), metrics.summary(), t.getMessage());
        }
    }

    public int pendingSize() {
        return queue.size();
    }

    @PreDestroy
    public void shutdown() {
        try {
            flushSafely();
        } finally {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }
    }
}
