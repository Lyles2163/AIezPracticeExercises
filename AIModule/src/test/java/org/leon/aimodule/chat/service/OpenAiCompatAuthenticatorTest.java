package org.leon.aimodule.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatAuthenticatorTest {

    private static OpenAiCompatAuthenticator authenticator(String apiKey, String userName) {
        ChatProperties props = new ChatProperties();
        props.getOpenaiCompat().setApiKey(apiKey);
        props.getOpenaiCompat().setUserName(userName);
        return new OpenAiCompatAuthenticator(props);
    }

    @Test
    @DisplayName("键与账号都配好、Bearer 相同 → 放行")
    void ok() {
        assertEquals(OpenAiCompatAuthenticator.Decision.OK,
                authenticator("sk-pet-123", "tester").authorize("Bearer sk-pet-123"));
    }

    @Test
    @DisplayName("键不匹配 → 拒绝，不是关闭")
    void wrongKey() {
        assertEquals(OpenAiCompatAuthenticator.Decision.REJECTED,
                authenticator("sk-pet-123", "tester").authorize("Bearer sk-other"));
    }

    @Test
    @DisplayName("没带头 / 不是 Bearer 前缀 → 拒绝")
    void missingOrWrongScheme() {
        OpenAiCompatAuthenticator a = authenticator("sk-pet-123", "tester");
        assertEquals(OpenAiCompatAuthenticator.Decision.REJECTED, a.authorize(null));
        assertEquals(OpenAiCompatAuthenticator.Decision.REJECTED, a.authorize("sk-pet-123"));
        assertEquals(OpenAiCompatAuthenticator.Decision.REJECTED, a.authorize("Basic sk-pet-123"));
    }

    @Test
    @DisplayName("没配服务键 = 路由关闭：即使请求侧看起来完全正确也绝不放行")
    void disabledWithoutKey() {
        assertEquals(OpenAiCompatAuthenticator.Decision.DISABLED,
                authenticator("", "tester").authorize("Bearer "));
        assertEquals(OpenAiCompatAuthenticator.Decision.DISABLED,
                authenticator(null, "tester").authorize("Bearer anything"));
    }

    @Test
    @DisplayName("没配归属账号同样关闭：轮次落不到任何人名下，放过去就是脏数据")
    void disabledWithoutUser() {
        assertEquals(OpenAiCompatAuthenticator.Decision.DISABLED,
                authenticator("sk-pet-123", "  ").authorize("Bearer sk-pet-123"));
    }
}
