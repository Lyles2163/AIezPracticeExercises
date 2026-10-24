package org.leon.aimodule.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ChatStreamExecutorConfig {

    /**
     * SynchronousQueue + max 上限：长连接的容量上限必须等于并发 emitter 数。
     * 排队等 30 秒才出首字比直接拒绝更糟，所以不建队列，满了就抛
     * RejectedExecutionException，由 controller 翻成"服务繁忙"。
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor chatStreamExecutor(ChatProperties props) {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(props.getStreamCoreThreads(), props.getStreamMaxThreads(),
                60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "chat-stream-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService chatHeartbeatExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "chat-ping");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
