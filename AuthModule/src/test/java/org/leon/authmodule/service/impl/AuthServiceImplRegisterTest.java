package org.leon.authmodule.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.pojo.RegisterRequest;
import org.leon.authmodule.pojo.Result;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 6：注册链路要能把 phone 写进 users_dev，并且能分辨两个唯一索引谁冲突了。
 * 用 Mockito 而不是真库：这一层只关心"参数传对没有、异常回话对不对"。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplRegisterTest {

    @Mock
    private AuthMapper authMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(passwordEncoder);
        ReflectionTestUtils.setField(authService, "AuthMapper", authMapper);
    }

    private RegisterRequest request() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("phonetest01");
        request.setPassword("password123");
        request.setPhone("13800001234");
        return request;
    }

    /** 造一条和 MySQL 1062 一样的异常链，验证服务是从 mostSpecificCause 里认索引名的 */
    private DuplicateKeyException duplicateOn(String indexName) {
        return new DuplicateKeyException(
                "Statement execute failed; SQL [INSERT INTO users_dev ...]",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry 'phonetest01' for key 'users_dev." + indexName + "'"));
    }

    @Test
    @DisplayName("注册成功：phone 作为第三个参数写库")
    void shouldWritePhoneWhenRegisterSucceeds() {
        when(passwordEncoder.encode("password123")).thenReturn("$2a$10$encoded");

        Result result = authService.register(request());

        verify(authMapper).register("phonetest01", "$2a$10$encoded", "13800001234");
        assertEquals(1, result.getCode());
        assertEquals("注册成功", result.getData());
    }

    @Test
    @DisplayName("撞 uk_users_username：回话是用户名已存在，不能说手机号")
    void shouldReportUsernameTakenWhenUsernameIndexViolated() {
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encoded");
        doThrow(duplicateOn("uk_users_username"))
                .when(authMapper).register(anyString(), anyString(), anyString());

        Result result = authService.register(request());

        assertEquals(0, result.getCode());
        assertEquals("用户名已存在", result.getMsg());
    }

    @Test
    @DisplayName("撞 uk_users_dev_phone：回话是该手机号已注册")
    void shouldReportPhoneTakenWhenPhoneIndexViolated() {
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$encoded");
        doThrow(duplicateOn("uk_users_dev_phone"))
                .when(authMapper).register(anyString(), anyString(), anyString());

        Result result = authService.register(request());

        assertEquals(0, result.getCode());
        assertEquals("该手机号已注册", result.getMsg());
    }
}
