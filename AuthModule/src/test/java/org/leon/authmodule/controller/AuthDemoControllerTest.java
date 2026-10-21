package org.leon.authmodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.mq.LoginEventPublisher;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.Users;
import org.leon.authmodule.pojo.mq.LoginSuccessEvent;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthDemoControllerTest {

    private static MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }

    @DisplayName("按 userId 查库并发布事件，返回 traceId")
    @Test
    void shouldPublishEventForExistingUser() {
        AuthMapper authMapper = mock(AuthMapper.class);
        LoginEventPublisher publisher = mock(LoginEventPublisher.class);
        Users users = new Users();
        users.setId(1L);
        users.setUsername("leon");
        users.setRoleId("1");
        users.setLast_login_ip("127.0.0.1");
        when(authMapper.selectByUserId(1L)).thenReturn(users);

        Result result = new AuthDemoController(authMapper, publisher)
                .triggerLoginEvent(1L, requestFrom("203.0.113.7"));

        assertEquals(1, result.getCode());
        assertNotNull(result.getData());
        ArgumentCaptor<LoginSuccessEvent> captor = ArgumentCaptor.forClass(LoginSuccessEvent.class);
        verify(publisher).publish(captor.capture());
        assertEquals(1L, captor.getValue().getUserId());
        assertEquals("203.0.113.7", captor.getValue().getLoginIp());
    }

    @DisplayName("用户不存在时不发布事件")
    @Test
    void shouldNotPublishForMissingUser() {
        AuthMapper authMapper = mock(AuthMapper.class);
        LoginEventPublisher publisher = mock(LoginEventPublisher.class);
        when(authMapper.selectByUserId(999999L)).thenReturn(null);

        Result result = new AuthDemoController(authMapper, publisher)
                .triggerLoginEvent(999999L, requestFrom("203.0.113.7"));

        assertEquals(0, result.getCode());
        verify(publisher, org.mockito.Mockito.never()).publish(any());
    }
}
