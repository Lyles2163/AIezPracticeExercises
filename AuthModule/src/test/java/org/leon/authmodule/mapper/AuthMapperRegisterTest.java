package org.leon.authmodule.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.authmodule.pojo.Users;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 6 Step 8 的自动化版本：真的往 users_dev 写一行，确认 phone 落库、
 * 并且唯一索引撞第二次时会变成 Spring 的 DuplicateKeyException（服务层靠这个分辨回话）。
 * <p>
 * 这是本仓库第一个连真库的测试，所以每个用例都在 AfterEach 里把自己插的行删掉，
 * 库里的 11 行存量数据不受影响。
 */
@SpringBootTest
class AuthMapperRegisterTest {

    private static final String USERNAME = "mq_it_phone_20261012";
    private static final String PHONE = "13800001234";

    @Autowired
    private AuthMapper authMapper;

    @AfterEach
    void cleanUp() {
        authMapper.delete(new LambdaQueryWrapper<Users>().eq(Users::getUsername, USERNAME));
    }

    private Users findByName(String username) {
        return authMapper.selectOne(new LambdaQueryWrapper<Users>().eq(Users::getUsername, username));
    }

    @Test
    @DisplayName("register 把 phone 写进 users_dev")
    void shouldPersistPhone() {
        authMapper.register(USERNAME, "$2a$10$itertesthash", PHONE);

        Users saved = findByName(USERNAME);
        assertNotNull(saved, "注册后应能按用户名查回该行");
        assertEquals(PHONE, saved.getPhone());
        assertEquals(USERNAME, saved.getUsername());
        assertNotNull(saved.getId());
    }

    @Test
    @DisplayName("同一手机号换用户名再注册：撞 uk_users_dev_phone，抛 DuplicateKeyException")
    void shouldRejectDuplicatePhone() {
        authMapper.register(USERNAME, "$2a$10$itertesthash", PHONE);

        DuplicateKeyException e = assertThrows(DuplicateKeyException.class,
                () -> authMapper.register(USERNAME + "_2", "$2a$10$itertesthash", PHONE));
        assertTrue(e.getMostSpecificCause().getMessage().contains("uk_users_dev_phone"),
                "异常信息里必须带索引名，服务层才能分辨是手机号冲突");
    }
}
