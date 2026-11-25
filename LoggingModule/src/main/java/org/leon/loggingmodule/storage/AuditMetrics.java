package org.leon.loggingmodule.storage;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计模块运维计数器（设计文档 §9）：
 * - logging.audit.events.received
 * - logging.audit.events.dropped
 * - logging.audit.events.persisted
 * - logging.audit.events.failed
 */
public class AuditMetrics {

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong persisted = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public void incReceived() {
        received.incrementAndGet();
    }

    public void incDropped() {
        dropped.incrementAndGet();
    }

    public void addPersisted(long count) {
        persisted.addAndGet(count);
    }

    public void addFailed(long count) {
        failed.addAndGet(count);
    }

    public long getReceived() {
        return received.get();
    }

    public long getDropped() {
        return dropped.get();
    }

    public long getPersisted() {
        return persisted.get();
    }

    public long getFailed() {
        return failed.get();
    }

    public String summary() {
        return "received=" + received.get()
                + ", dropped=" + dropped.get()
                + ", persisted=" + persisted.get()
                + ", failed=" + failed.get();
    }
}
