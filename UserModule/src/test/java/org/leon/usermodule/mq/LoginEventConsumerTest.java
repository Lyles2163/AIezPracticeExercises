package org.leon.usermodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.leon.usermodule.pojo.vo.ProcessedLogin;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoginEventConsumerTest {

    @DisplayName("收到事件后写进环形队列，traceId 原样保留")
    @Test
    void shouldStoreProcessedLogin() {
        RecentLoginStore store = new RecentLoginStore();
        LoginEventConsumer consumer = new LoginEventConsumer(store);

        consumer.onLoginEvent(new LoginEvent(10L, "test001", "trace-1",
                "203.0.113.7", 1_700_000_000_000L, "abcdef123456"));

        List<ProcessedLogin> recent = store.recent();
        assertEquals(1, recent.size());
        assertEquals("trace-1", recent.get(0).getEvent().getTraceId());
    }
}
