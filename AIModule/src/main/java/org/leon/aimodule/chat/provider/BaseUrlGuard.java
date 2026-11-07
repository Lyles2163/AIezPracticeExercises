package org.leon.aimodule.chat.provider;

import org.leon.aimodule.chat.exception.ChatRejectException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 用户自填的 baseUrl 会被服务端拿去发请求，所以既是格式校验也是 SSRF 闸门：
 * 只允许 http(s)，拒绝带凭据的 URL，拒绝回环/私网/链路本地地址。
 * 端口不做限制（自建网关常见 8000/11434 这类端口）。
 */
public final class BaseUrlGuard {

    static final String INVALID = "baseUrl 不合法";
    static final String NOT_ALLOWED = "baseUrl 不允许指向本机或内网地址";
    static final String HTTP_FORBIDDEN = "baseUrl 必须使用 https";

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private BaseUrlGuard() {
    }

    /**
     * 归一化：去掉尾部斜杠；路径不以 /v1 结尾时补 /v1。
     * 落库前必须过这里，否则"配 https://api.x.com"与"https://api.x.com/v1"两种写法
     * 会在运行时拼出 /v1/v1/chat/completions 或 /chat/completions 两种地址。
     */
    public static String normalize(String raw, boolean allowHttp) {
        URI uri = parse(raw);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowHttp && "http".equals(scheme))) {
            throw new ChatRejectException("http".equals(scheme) ? HTTP_FORBIDDEN : INVALID);
        }
        if (uri.getUserInfo() != null) {
            throw new ChatRejectException(INVALID);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ChatRejectException(INVALID);
        }
        if (isBlockedHost(host)) {
            throw new ChatRejectException(NOT_ALLOWED);
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.endsWith("/v1")) {
            path = path + "/v1";
        }
        String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        return scheme + "://" + host + port + path;
    }

    public static boolean isBlockedHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || h.endsWith(".localhost") || h.endsWith(".local")
                || h.endsWith(".internal")) {
            return true;
        }
        if (IPV4.matcher(h).matches()) {
            String[] parts = h.split("\\.");
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 0 || a == 10 || a == 127
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 100 && b >= 64 && b <= 127);
        }
        // IPv6 字面量：只放行公网段，其余（::1、fc00::/7、fe80::/10）一律拒绝
        if (h.startsWith("[") || h.contains(":")) {
            String v6 = h.replace("[", "").replace("]", "");
            return v6.equals("::1") || v6.equals("::") || v6.startsWith("fc")
                    || v6.startsWith("fd") || v6.startsWith("fe8") || v6.startsWith("fe9")
                    || v6.startsWith("fea") || v6.startsWith("feb");
        }
        return false;
    }

    private static URI parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new ChatRejectException(INVALID);
        }
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null) {
                throw new ChatRejectException(INVALID);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new ChatRejectException(INVALID);
        }
    }
}
