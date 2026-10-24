package org.leon.aimodule.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamExecutorConfigTest {

    @Test
    @DisplayName("线程池满时立刻拒绝，不排队")
    void rejectsInsteadOfQueuing() throws Exception {
        ChatProperties props = new ChatProperties();
        props.setStreamCoreThreads(1);
        props.setStreamMaxThreads(1);
        ThreadPoolExecutor executor = new ChatStreamExecutorConfig().chatStreamExecutor(props);
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try {
                    hold.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {
            }));
            assertEquals(0, executor.getQueue().size(), "满了也不许把任务留在队列里");
        } finally {
            hold.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("心跳线程池是守护线程且取消即移除")
    void heartbeatExecutorIsDaemonAndCleansUp() throws Exception {
        java.util.concurrent.ScheduledExecutorService executor =
                new ChatStreamExecutorConfig().chatHeartbeatExecutor();
        try {
            java.util.concurrent.CountDownLatch onWorker = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Thread> seen = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.ScheduledFuture<?> future = executor.schedule(() -> {
                seen.set(Thread.currentThread());
                onWorker.countDown();
            }, 10, TimeUnit.MILLISECONDS);
            assertTrue(onWorker.await(5, TimeUnit.SECONDS));
            assertTrue(seen.get().isDaemon(), "心跳线程必须守护，否则 JVM 退不掉");
            future.cancel(false);
            assertTrue(executor instanceof java.util.concurrent.ScheduledThreadPoolExecutor spe
                    && spe.getRemoveOnCancelPolicy(), "取消的任务必须立刻出队");
        } finally {
            executor.shutdownNow();
        }
    }
}
