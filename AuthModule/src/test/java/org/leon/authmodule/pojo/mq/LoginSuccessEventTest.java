package org.leon.authmodule.pojo.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginSuccessEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @DisplayName("事件能被 Jackson 往返序列化，字段不丢")
    @Test
    void shouldRoundTripThroughJackson() throws Exception {
        LoginSuccessEvent event = new LoginSuccessEvent(
                10L, "test001", "trace-1", "203.0.113.7", 1_700_000_000_000L, "abcdef123456");

        String json = objectMapper.writeValueAsString(event);
        LoginSuccessEvent back = objectMapper.readValue(json, LoginSuccessEvent.class);

        assertEquals(10L, back.getUserId());
        assertEquals("test001", back.getUsername());
        assertEquals("trace-1", back.getTraceId());
        assertEquals("203.0.113.7", back.getLoginIp());
        assertEquals(1_700_000_000_000L, back.getLoginAt());
        assertEquals("abcdef123456", back.getTokenFingerprint());
    }

    @DisplayName("消息体里不能出现密码或 token 字段")
    @Test
    void shouldNotLeakSecrets() throws Exception {
        String json = objectMapper.writeValueAsString(new LoginSuccessEvent(
                10L, "test001", "trace-1", "203.0.113.7", 1_700_000_000_000L, "abcdef123456"));

        assertFalse(json.contains("password"));
        assertFalse(json.contains("\"token\""));
        assertTrue(json.contains("tokenFingerprint"));
    }
}
