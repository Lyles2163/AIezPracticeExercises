package org.leon.authmodule.util;

import jakarta.servlet.http.HttpServletRequest;

public final class IpUtil {

    private static final String UNKNOWN = "unknown";
    private static final String IPV6_LOOPBACK = "0:0:0:0:0:0:0:1";

    private IpUtil() {
    }

    public static String getClientIp(HttpServletRequest request) {
        String ip = firstKnown(request.getHeader("X-Forwarded-For"));
        if (ip == null) {
            ip = firstKnown(request.getHeader("X-Real-IP"));
        }
        if (ip == null) {
            ip = request.getRemoteAddr();
        }
        if (IPV6_LOOPBACK.equalsIgnoreCase(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

    private static String firstKnown(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        for (String candidate : headerValue.split(",")) {
            String value = candidate.trim();
            if (!value.isEmpty() && !UNKNOWN.equalsIgnoreCase(value)) {
                return value;
            }
        }
        return null;
    }
}
