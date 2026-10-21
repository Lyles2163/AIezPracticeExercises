package org.leon.authmodule.service.impl;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.mq.LoginEventPublisher;
import org.leon.authmodule.pojo.LoginRequest;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.Users;
import org.leon.authmodule.pojo.mq.LoginSuccessEvent;
import org.leon.authmodule.service.AuthRedisService;
import org.leon.authmodule.service.CaptchaService;
import org.leon.commonjwt.utils.JwtUtil;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplLoginEventTest {

    @Mock private AuthMapper authMapper;
    @Mock private JwtUtil jwtUtil;
    @Mock private AuthRedisService authRedisService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CaptchaService captchaService;
    @Mock private LoginEventPublisher loginEventPublisher;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(passwordEncoder);
        ReflectionTestUtils.setField(authService, "AuthMapper", authMapper);
        ReflectionTestUtils.setField(authService, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(authService, "authRedisService", authRedisService);
        ReflectionTestUtils.setField(authService, "captchaService", captchaService);
        ReflectionTestUtils.setField(authService, "loginEventPublisher", loginEventPublisher);
        ReflectionTestUtils.setField(authService, "jwtExpiration", 7200000L);
    }

    private void stubSuccessfulLogin() {
        Users users = new Users();
        users.setId(10L);
        users.setUsername("test001");
        users.setPassword_hash("$2a$10$hash");
        users.setRoleId("1");

        Claims claims = mock(Claims.class);
        when(claims.getIssuedAt()).thenReturn(new Date(1_700_000_000_000L));
        when(claims.getExpiration()).thenReturn(new Date(1_700_007_200_000L));

        when(captchaService.verifyAndConsume(any(), any())).thenReturn(true);
        when(authMapper.selectByUsername("test001")).thenReturn(users);
        when(passwordEncoder.matches("password123", "$2a$10$hash")).thenReturn(true);
        when(jwtUtil.generateToken2(10L, "test001", "1")).thenReturn("token-value");
        when(jwtUtil.getClaimsFromToken("token-value")).thenReturn(claims);
    }

    private LoginRequest newRequest() {
        LoginRequest request = new LoginRequest();
        request.setUsername("test001");
        request.setPassword("password123");
        return request;
    }

    @DisplayName("登录成功发布事件：带 userId/IP/traceId，且只带 token 指纹")
    @Test
    void shouldPublishLoginEventWithFingerprint() {
        stubSuccessfulLogin();

        authService.login(newRequest(), "203.0.113.7");

        ArgumentCaptor<LoginSuccessEvent> captor = ArgumentCaptor.forClass(LoginSuccessEvent.class);
        verify(loginEventPublisher).publish(captor.capture());
        LoginSuccessEvent event = captor.getValue();
        assertEquals(10L, event.getUserId());
        assertEquals("test001", event.getUsername());
        assertEquals("203.0.113.7", event.getLoginIp());
        assertNotNull(event.getTraceId());
        assertEquals(12, event.getTokenFingerprint().length());
        assertNotNull(event.getLoginAt());
    }

    @DisplayName("MQ 发布失败时登录仍然成功，且照常返回 token")
    @Test
    void shouldStillSucceedWhenPublishFails() {
        stubSuccessfulLogin();
        doThrow(new RuntimeException("broker down"))
                .when(loginEventPublisher).publish(any());

        Result result = authService.login(newRequest(), "203.0.113.7");

        assertEquals(1, result.getCode());
    }
}
