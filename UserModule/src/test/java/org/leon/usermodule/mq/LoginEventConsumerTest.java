package org.leon.usermodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.usermodule.feign.AuthModuleClient;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.leon.usermodule.pojo.vo.ProcessedLogin;
import org.leon.usermodule.pojo.vo.UserProfileVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginEventConsumerTest {

    private static LoginEvent event() {
        return new LoginEvent(10L, "test001", "trace-1",
                "203.0.113.7", 1_700_000_000_000L, "abcdef123456");
    }

    @DisplayName("收到事件后反查资料并写进环形队列")
    @Test
    void shouldEnrichWithFeignProfile() {
        RecentLoginStore store = new RecentLoginStore();
        AuthModuleClient client = mock(AuthModuleClient.class);
        when(client.getUserProfile(10L)).thenReturn(
                new UserProfileVO(10L, "test001", "2", 1, "13631155285", null, "203.0.113.7"));

        new LoginEventConsumer(store, client).onLoginEvent(event());

        verify(client).getUserProfile(10L);
        List<ProcessedLogin> recent = store.recent();
        assertEquals(1, recent.size());
        assertEquals("2", recent.get(0).getProfile().getRoleId());
        assertEquals("13631155285", recent.get(0).getProfile().getPhone());
    }
}
