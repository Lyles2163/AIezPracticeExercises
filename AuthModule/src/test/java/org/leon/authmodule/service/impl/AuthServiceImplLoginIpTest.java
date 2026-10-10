package org.leon.authmodule.service.impl;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.pojo.RegisterRequest;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.Users;
import org.leon.authmodule.service.AuthRedisService;
import org.leon.commonjwt.utils.JwtUtil;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplLoginIpTest {

    private static final String CLIENT_IP = "203.0.113.7";

    @Mock
    private AuthMapper authMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private AuthRedisService authRedisService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(passwordEncoder);
        ReflectionTestUtils.setField(authService, "AuthMapper", authMapper);
        ReflectionTestUtils.setField(authService, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(authService, "authRedisService", authRedisService);
        ReflectionTestUtils.setField(authService, "jwtExpiration", 7200000L);
    }

    private RegisterRequest request() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("test001");
        request.setPassword("password123");
        return request;
    }

    private Users existingUser() {
        Users users = new Users();
        users.setId(10L);
        users.setUsername("test001");
        users.setPassword_hash("$2a$10$hash");
        users.setRoleId("1");
        return users;
    }

    private Claims issuedClaims() {
        Claims claims = mock(Claims.class);
        when(claims.getIssuedAt()).thenReturn(new Date(1_700_000_000_000L));
        when(claims.getExpiration()).thenReturn(new Date(1_700_007_200_000L));
        return claims;
    }

    private void stubSuccessfulCredentials(Claims claims) {
        when(authMapper.selectByUsername("test001")).thenReturn(existingUser());
        when(passwordEncoder.matches("password123", "$2a$10$hash")).thenReturn(true);
        when(jwtUtil.generateToken2(10L, "test001", "1")).thenReturn("token-value");
        when(jwtUtil.getClaimsFromToken("token-value")).thenReturn(claims);
    }

    @DisplayName("登录成功后记录本次登录的 IP")
    @Test
    void shouldRecordClientIpOnSuccessfulLogin() {
        stubSuccessfulCredentials(issuedClaims());

        Result result = authService.login(request(), CLIENT_IP);

        assertEquals(1, result.getCode());
        verify(authMapper).updateLastLogin(10L, CLIENT_IP);
    }

    @DisplayName("登录信息在签发 JWT 之前更新，避免签发成功但记录失败")
    @Test
    void shouldRecordLoginInfoBeforeIssuingToken() {
        stubSuccessfulCredentials(issuedClaims());

        authService.login(request(), CLIENT_IP);

        InOrder inOrder = inOrder(authMapper, jwtUtil);
        inOrder.verify(authMapper).updateLastLogin(10L, CLIENT_IP);
        inOrder.verify(jwtUtil).generateToken2(10L, "test001", "1");
    }

    @DisplayName("密码错误时不记录登录信息")
    @Test
    void shouldNotRecordLoginInfoWhenPasswordWrong() {
        when(authMapper.selectByUsername("test001")).thenReturn(existingUser());
        when(passwordEncoder.matches("password123", "$2a$10$hash")).thenReturn(false);

        Result result = authService.login(request(), CLIENT_IP);

        assertEquals(0, result.getCode());
        verify(authMapper, never()).updateLastLogin(anyLong(), any());
    }

    @DisplayName("用户不存在时不记录登录信息")
    @Test
    void shouldNotRecordLoginInfoWhenUserMissing() {
        when(authMapper.selectByUsername("test001")).thenReturn(null);

        Result result = authService.login(request(), CLIENT_IP);

        assertEquals(0, result.getCode());
        verify(authMapper, never()).updateLastLogin(anyLong(), any());
    }
}
