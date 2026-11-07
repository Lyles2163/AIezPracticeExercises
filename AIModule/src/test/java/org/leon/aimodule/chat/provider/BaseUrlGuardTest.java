package org.leon.aimodule.chat.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.exception.ChatRejectException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaseUrlGuardTest {

    @Test
    @DisplayName("没有 /v1 时补上，尾斜杠被去掉")
    void normalizesToV1() {
        assertEquals("https://api.example.com/v1", BaseUrlGuard.normalize("https://api.example.com/", false));
        assertEquals("https://api.example.com/v1", BaseUrlGuard.normalize("https://api.example.com", false));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                BaseUrlGuard.normalize("https://dashscope.aliyuncs.com/compatible-mode/v1/", false));
    }

    @Test
    @DisplayName("端口保留")
    void keepsPort() {
        assertEquals("https://gw.example.com:8443/v1",
                BaseUrlGuard.normalize("https://gw.example.com:8443", false));
    }

    @Test
    @DisplayName("http 默认拒绝，开关打开才放行非内网地址")
    void httpRequiresFlag() {
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> BaseUrlGuard.normalize("http://api.example.com", false));
        assertEquals(BaseUrlGuard.HTTP_FORBIDDEN, e.getMessage());
        assertEquals("http://api.example.com/v1",
                BaseUrlGuard.normalize("http://api.example.com", true));
    }

    @Test
    @DisplayName("本机与内网地址一律拒绝")
    void blocksPrivateTargets() {
        for (String url : new String[]{
                "https://localhost/v1", "https://127.0.0.1/v1", "https://10.0.0.8/v1",
                "https://192.168.1.2/v1", "https://172.20.0.1/v1", "https://169.254.169.254/latest",
                "https://100.64.0.1/v1", "https://[::1]/v1", "https://svc.internal/v1",
                "https://box.local/v1"}) {
            assertEquals(BaseUrlGuard.NOT_ALLOWED,
                    assertThrows(ChatRejectException.class, () -> BaseUrlGuard.normalize(url, false)).getMessage(),
                    url);
        }
    }

    @Test
    @DisplayName("带凭据或非 http(s) 的一律不合法")
    void rejectsMalformed() {
        assertEquals(BaseUrlGuard.INVALID,
                assertThrows(ChatRejectException.class,
                        () -> BaseUrlGuard.normalize("https://user:pass@api.example.com", false)).getMessage());
        assertEquals(BaseUrlGuard.INVALID,
                assertThrows(ChatRejectException.class,
                        () -> BaseUrlGuard.normalize("file:///etc/passwd", false)).getMessage());
        assertEquals(BaseUrlGuard.INVALID,
                assertThrows(ChatRejectException.class, () -> BaseUrlGuard.normalize("  ", false)).getMessage());
    }
}
