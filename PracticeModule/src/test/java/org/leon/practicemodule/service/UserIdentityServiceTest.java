package org.leon.practicemodule.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.practicemodule.config.PracticeProperties;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.UsersReadMapper;
import org.leon.practicemodule.service.impl.UserIdentityServiceImpl;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserIdentityServiceTest {

    @Mock UsersReadMapper usersReadMapper;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private PracticeProperties props;
    private UserIdentityServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new PracticeProperties();
        props.getIdentity().setCacheTtlSeconds(1800L);
        service = new UserIdentityServiceImpl(usersReadMapper, redis, props);
    }

    @Test
    @DisplayName("缓存命中就不查库")
    void cacheHitSkipsDb() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("qb:uid:alice")).thenReturn("2");

        assertEquals(2L, service.resolveUserId("alice"));
        verifyNoInteractions(usersReadMapper);
    }

    @Test
    @DisplayName("缓存未命中则查库并回填，TTL 取配置")
    void cacheMissLoadsAndCaches() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("qb:uid:bob")).thenReturn(null);
        when(usersReadMapper.selectIdByUsername("bob")).thenReturn(3L);

        assertEquals(3L, service.resolveUserId("bob"));
        verify(valueOps).set(eq("qb:uid:bob"), eq("3"), eq(Duration.ofSeconds(1800)));
    }

    @Test
    @DisplayName("用户名查不到 → 40100，且不写缓存（防止把不存在的人缓存住）")
    void unknownUserThrowsIdentityMissing() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(usersReadMapper.selectIdByUsername("ghost")).thenReturn(null);

        PracticeException e = assertThrows(PracticeException.class,
                () -> service.resolveUserId("ghost"));
        assertEquals(org.leon.practicemodule.exception.PracticeErrorCode.IDENTITY_MISSING,
                e.errorCode());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("空用户名直接 40100，不碰 Redis 也不查库")
    void blankUserNameThrows() {
        assertThrows(PracticeException.class, () -> service.resolveUserId(null));
        assertThrows(PracticeException.class, () -> service.resolveUserId("  "));
        verifyNoInteractions(usersReadMapper, redis);
    }

    @Test
    @DisplayName("Redis 抖动不能阻断取题：降级查库")
    void redisFailureDegradesToDb() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("connection refused"));
        when(usersReadMapper.selectIdByUsername("alice")).thenReturn(2L);

        assertEquals(2L, service.resolveUserId("alice"));
    }
}
