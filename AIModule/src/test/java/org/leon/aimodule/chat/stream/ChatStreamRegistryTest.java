package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamRegistryTest {

    private static ActiveStream stream(long id) {
        SseChannel channel = new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
            }

            @Override
            public void complete() {
            }
        }, new ObjectMapper());
        return new ActiveStream(id, 7L, "alice", channel, new AtomicReference<>(), new StringBuilder("部分内容"),
                new AtomicReference<>("done"), new AtomicReference<>(new int[]{1, 2}));
    }

    @Test
    @DisplayName("注册、查找、摘除与计数")
    void registerFindRemove() {
        ChatStreamRegistry registry = new ChatStreamRegistry();
        registry.register(42L, stream(42L));
        assertEquals(1, registry.size());
        assertTrue(registry.activeIds().contains(42L));
        assertEquals(42L, registry.find(42L).messageId());
        // remove 的返回值是 claim：谁摘掉谁负责收尾那一次 UPDATE
        assertTrue(registry.remove(42L));
        assertNull(registry.find(42L));
        assertEquals(0, registry.size());
        assertFalse(registry.remove(42L), "第二次摘除必须抢不到，收尾才不会写两次");
        assertEquals(0, registry.size());
        assertNull(registry.find(null));
        assertFalse(registry.remove(null));
    }

    @Test
    @DisplayName("同 id 覆盖注册时旧的先被摘掉，不留两条活跃流")
    void reRegisterReplaces() {
        ChatStreamRegistry registry = new ChatStreamRegistry();
        registry.register(1L, stream(1L));
        registry.register(1L, stream(1L));
        assertEquals(1, registry.size());
    }

    @Test
    @DisplayName("activeIds 是快照，遍历期间摘除不抛 ConcurrentModificationException")
    void activeIdsIsSnapshot() {
        ChatStreamRegistry registry = new ChatStreamRegistry();
        registry.register(1L, stream(1L));
        registry.register(2L, stream(2L));
        java.util.Set<Long> ids = registry.activeIds();
        registry.remove(1L);
        assertEquals(2, ids.size());
        assertEquals(1, registry.size());
    }

    @Test
    @DisplayName("buffer 累积与读回；upstream 未挂上时 dispose 不崩")
    void bufferAccumulatesAndDisposeIsSafe() {
        ActiveStream s = stream(9L);
        s.append("a");
        s.append("b");
        assertEquals("部分内容ab", s.accumulated());
        // upstream 还没挂上（注册表在订阅之前登记），此时停流必须什么都不做
        s.disposeUpstream();

        java.util.concurrent.atomic.AtomicBoolean disposed =
                new java.util.concurrent.atomic.AtomicBoolean();
        s.upstream().set(() -> disposed.set(true));
        s.disposeUpstream();
        assertTrue(disposed.get(), "已挂上 Disposable 时必须真的停掉上游");
        // 重复停不炸
        s.disposeUpstream();
    }
}
