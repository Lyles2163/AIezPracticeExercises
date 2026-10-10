package org.leon.authmodule.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IpUtilTest {

    @DisplayName("多级代理时取 X-Forwarded-For 中最前面的客户端地址")
    @Test
    void shouldReturnFirstEntryOfXForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1, 10.0.0.2");
        request.setRemoteAddr("127.0.0.1");

        assertEquals("203.0.113.7", IpUtil.getClientIp(request));
    }

    @DisplayName("X-Forwarded-For 中的 unknown 占位值被跳过")
    @Test
    void shouldSkipUnknownPlaceholderInForwardedChain() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "unknown, 203.0.113.9");

        assertEquals("203.0.113.9", IpUtil.getClientIp(request));
    }

    @DisplayName("没有 X-Forwarded-For 时回退到 X-Real-IP")
    @Test
    void shouldFallBackToXRealIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "198.51.100.4");
        request.setRemoteAddr("127.0.0.1");

        assertEquals("198.51.100.4", IpUtil.getClientIp(request));
    }

    @DisplayName("转发头只有空白值时不算有效来源")
    @Test
    void shouldIgnoreBlankForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "  ");
        request.setRemoteAddr("192.168.1.10");

        assertEquals("192.168.1.10", IpUtil.getClientIp(request));
    }

    @DisplayName("无任何转发头时使用容器给出的远端地址")
    @Test
    void shouldFallBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.20");

        assertEquals("192.168.1.20", IpUtil.getClientIp(request));
    }

    @DisplayName("IPv6 回环地址归一为 127.0.0.1")
    @Test
    void shouldNormalizeIpv6Loopback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("0:0:0:0:0:0:0:1");

        assertEquals("127.0.0.1", IpUtil.getClientIp(request));
    }

    @DisplayName("非回环的 IPv6 地址原样保留")
    @Test
    void shouldKeepRealIpv6Address() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "2001:db8::1");

        assertEquals("2001:db8::1", IpUtil.getClientIp(request));
    }
}
