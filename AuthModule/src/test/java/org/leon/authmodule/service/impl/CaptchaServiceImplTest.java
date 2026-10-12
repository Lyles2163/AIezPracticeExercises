package org.leon.authmodule.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.service.CaptchaService;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaptchaServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CaptchaServiceImpl newService() {
        return new CaptchaServiceImpl(redisTemplate);
    }

    @DisplayName("生成后 Redis 里写入 auth:captcha:{key}，TTL 120 秒，返回带 data: 前缀的 base64")
    @Test
    void generateStoresAnswerWithTwoMinuteTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CaptchaService.CaptchaImage image = newService().generate();

        assertNotNull(image.getCaptchaKey());
        assertEquals(32, image.getCaptchaKey().length(), "captchaKey 应为 32 位无横线 UUID");
        assertTrue(image.getImageBase64().startsWith("data:image/png;base64,"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(key.capture(), value.capture(), eq(Duration.ofSeconds(120)));
        assertEquals("auth:captcha:" + image.getCaptchaKey(), key.getValue());
        assertEquals(6, value.getValue().length());
        assertTrue(value.getValue().chars().allMatch(Character::isDigit),
                "图形码必须是 6 位纯数字，否则前端 CodeSlots 的数字输入框输不进去");
    }

    @DisplayName("校验成功后立即删除 key（一次性）")
    @Test
    void verifyDeletesKeyEvenOnSuccess() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:captcha:k1")).thenReturn("123456");

        assertTrue(newService().verifyAndConsume("k1", "123456"));

        verify(redisTemplate).delete("auth:captcha:k1");
    }

    @DisplayName("校验失败也要删除 key：不给同一张图反复试的机会")
    @Test
    void verifyDeletesKeyEvenOnWrongCode() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:captcha:k1")).thenReturn("123456");

        assertFalse(newService().verifyAndConsume("k1", "000000"));

        verify(redisTemplate).delete("auth:captcha:k1");
    }

    @DisplayName("key 不存在（已过期）时返回 false 且不抛异常")
    @Test
    void missingKeyIsTreatedAsExpired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertFalse(newService().verifyAndConsume("k1", "123456"));

        verify(redisTemplate, never()).delete(anyString());
    }

    @DisplayName("空白入参直接判失败，不查 Redis")
    @Test
    void blankInputShortCircuits() {
        assertFalse(newService().verifyAndConsume("", "123456"));
        assertFalse(newService().verifyAndConsume("k1", null));
        verify(redisTemplate, times(0)).opsForValue();
    }
}
