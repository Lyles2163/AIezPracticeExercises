package org.leon.authmodule.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * /auth/internal/** 不走 JWT（它是服务间调用），改校验共享令牌。
 * 端口是直接对进程外开放的，permitAll 之外必须加这层。
 */
@Component
public class InternalApiAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Token";

    @Value("${auth.internal.token:}")
    private String internalToken;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/auth/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (internalToken == null || internalToken.isBlank() || !matches(provided, internalToken)) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"code\":401,\"msg\":\"内部接口令牌无效\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String provided, String expected) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
