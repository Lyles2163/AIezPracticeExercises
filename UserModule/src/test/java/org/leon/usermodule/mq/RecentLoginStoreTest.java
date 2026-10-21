package org.leon.usermodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.leon.usermodule.pojo.vo.ProcessedLogin;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecentLoginStoreTest {

    private static ProcessedLogin item(long userId) {
        LoginEvent event = new LoginEvent(userId, "u" + userId, "trace-" + userId,
                "127.0.0.1", 1_700_000_000_000L, "abcdef123456");
        return new ProcessedLogin(event, 1_700_000_000_001L);
    }

    @DisplayName("最新的一条排在最前")
    @Test
    void newestFirst() {
        RecentLoginStore store = new RecentLoginStore();
        store.add(item(1L));
        store.add(item(2L));

        List<ProcessedLogin> recent = store.recent();
        assertEquals(2L, recent.get(0).getEvent().getUserId());
    }

    @DisplayName("超过容量淘汰最旧的")
    @Test
    void evictsOldestBeyondCapacity() {
        RecentLoginStore store = new RecentLoginStore();
        for (long i = 1; i <= 25; i++) {
            store.add(item(i));
        }

        List<ProcessedLogin> recent = store.recent();
        assertEquals(20, recent.size());
        assertEquals(25L, recent.get(0).getEvent().getUserId());
        assertEquals(6L, recent.get(19).getEvent().getUserId());
    }
}
