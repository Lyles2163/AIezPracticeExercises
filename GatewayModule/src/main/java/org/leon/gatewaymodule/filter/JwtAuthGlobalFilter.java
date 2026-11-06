package org.leon.gatewaymodule.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.leon.commonjwt.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * JWT 全局鉴权过滤器
 * <p>
 * 执行顺序：
 * 1. 提取 Authorization 请求头中的 Bearer Token
 * 2. 验证 Token 有效性（签名 + 过期时间）
 * 3. 将解析出的用户信息写入请求头，传递给下游微服务
 * 4. ⚠️ 清除原始 Authorization 头，防止下游服务绕过网关直接信任客户端传入的 Token
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {



    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 网关→下游 的内部令牌。
     * 下游（PracticeModule 等）校验这个头，只有带对令牌的请求才放行，
     * 防止有人绕过网关直连下游、伪造 X-User-Name。
     * 默认值仅供本地开发，生产环境必须在 Nacos 覆盖。
     */
    @Value("${gateway.internal-token:ez-practice-internal-token-2026}")
    private String internalToken;

    /**
     * 白名单路径（无需鉴权）
     * 可根据实际需求扩展，如 /auth/login, /auth/register 等
     */
    private static final List<String> WHITE_LIST = List.of(
            "/auth/login",
//            "/auth/logout",
            "/auth/register",
            "/auth/captcha",
            "/auth/sms/send",
            // 桌宠（dsh-pet）只会发 Authorization: Bearer <服务键>，发不出 JWT，也没法塞自定义头。
            // 写完整路径而不是 /ai/v1：白名单是 startsWith 匹配，写前缀等于把整段 /ai/v1/** 都放开。
            // 放行后不再注入 X-User-Name、也不剥 Authorization，凭据由 AIModule 那侧自己校验。
            "/ai/v1/chat/completions",
            "/actuator"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        log.info("JWT Filter: actualPath={}, isWhiteListed={}", path, isWhiteListed(path));

        // ========== 1. 白名单放行 ==========
        if (isWhiteListed(path)) {
            return chain.filter(exchange);
        }

        // ========== 2. 提取 Token ==========
        String token = extractToken(request);
        if (!StringUtils.hasText(token)) {
            log.warn("JWT Filter: 缺少 Authorization 头, path={}", path);
            return unauthorizedResponse(exchange, "缺少或无效的授权头");
        }

        // ========== 3. 验证 Token ==========
        if (!jwtUtil.validateToken(token)) {
            log.warn("JWT Filter: Token 无效或已过期, path={}", path);
            return unauthorizedResponse(exchange, "无效或过期令牌");
        }

        // ========== 4. 解析用户信息并转发 ==========
        String username = jwtUtil.getUsernameFromToken(token);
        Long userId = jwtUtil.getUserIdFromToken(token);

        // 构建新请求：
        // - 先删掉客户端可能伪造的 X-User-Name / X-User-Id / X-Internal-Token（防止绕过）
        // - 再注入网关注入的真实用户信息
        // - 带上内部令牌 X-Internal-Token，下游据此判断请求来自网关
        // - 移除原始 Authorization 头，防止 Token 泄露到下游
        ServerHttpRequest modifiedRequest = request.mutate()
                .headers(headers -> {
                    headers.remove("X-User-Name");
                    headers.remove("X-User-Id");
                    headers.remove("X-Internal-Token");
                    headers.remove(HttpHeaders.AUTHORIZATION);
                })
                .header("X-User-Name", username)
                .header("X-User-Id", String.valueOf(userId))
                .header("X-Internal-Token", internalToken)
                .build();

        log.debug("JWT Filter: 鉴权通过, user={}, userId={}, path={}", username, userId, path);
        return chain.filter(exchange.mutate().request(modifiedRequest).build());
    }

    /**
     * 过滤器优先级
     * 数值越小优先级越高，确保在其他业务过滤器之前执行鉴权
     */
    @Override
    public int getOrder() {
        return -100;
    }

    /**
     * 从 Authorization 头中提取 Bearer Token
     *
     * @return 纯 Token 字符串，格式不正确时返回 null
     */
    private String extractToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7); // 去掉 "Bearer " 前缀
        }
        return null;
    }

    /**
     * 判断请求路径是否在白名单中
     */
    private boolean isWhiteListed(String path) {
        return WHITE_LIST.stream().anyMatch(path::startsWith);
    }

    /**
     * 返回 401 未授权响应
     */
    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        byte[] bytes = ("{\"code\":401,\"message\":\"" + message + "\"}").getBytes();
        var buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}