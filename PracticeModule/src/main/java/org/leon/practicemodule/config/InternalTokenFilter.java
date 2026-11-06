package org.leon.practicemodule.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 内部请求来源校验：只放行带对 {@code X-Internal-Token} 的请求。
 *
 * 这个令牌只有网关和通过 Feign 互调的内部服务知道，客户端拿不到。
 * 加这一层是为了防止有人绕过网关直连 PracticeModule（8102）、
 * 伪造 {@code X-User-Name} 去查别人的数据。
 *
 * 本地开发如果嫌麻烦，可以把 practice.internal-token 设为空串，
 * 过滤器就不校验（但生产绝对不能这么干）。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalTokenFilter implements Filter {

    public static final String HEADER_NAME = "X-Internal-Token";

    @Value("${practice.internal-token:ez-practice-internal-token-2026}")
    private String internalToken;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // 配置为空时不校验：方便本地直连调试
        if (internalToken == null || internalToken.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest req = (HttpServletRequest) request;
        String token = req.getHeader(HEADER_NAME);
        if (!internalToken.equals(token)) {
            log.warn("[internal-token] 拒绝无令牌请求: path={} remoteAddr={}",
                    req.getRequestURI(), req.getRemoteAddr());
            HttpServletResponse resp = (HttpServletResponse) response;
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"code\":401,\"message\":\"请通过网关访问\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
