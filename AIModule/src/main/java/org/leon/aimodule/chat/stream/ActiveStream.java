package org.leon.aimodule.chat.stream;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 一条进行中的流。buffer 只在内存里累积，收尾时一次性落库——
 * 每 token 写一次会把一次回答放大成上千次行锁更新。
 *
 * upstream 用 AtomicReference 而不是直接持有 Disposable：注册表必须在订阅上游
 * 之前就登记（否则秒回的流会在登记前就已完成，留下一条永远停不下来的僵尸条目），
 * 而 Disposable 只有 subscribe() 返回后才拿得到。
 */
public record ActiveStream(Long messageId, Long conversationId, String userName, SseChannel channel,
                           AtomicReference<reactor.core.Disposable> upstream, StringBuilder buffer,
                           AtomicReference<String> finishReason, AtomicReference<int[]> usage) {

    public String accumulated() {
        synchronized (buffer) {
            return buffer.toString();
        }
    }

    public void append(String text) {
        synchronized (buffer) {
            buffer.append(text);
        }
    }

    public void disposeUpstream() {
        reactor.core.Disposable d = upstream.get();
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }
}
