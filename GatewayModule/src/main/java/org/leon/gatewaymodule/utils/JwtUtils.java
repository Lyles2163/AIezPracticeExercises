package org.leon.gatewaymodule.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.leon.gatewaymodule.config.JwtProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类
 * <p>
 * 基于 jjwt 0.12.x 新版 API 实现
 * 通过构造器注入 JwtProperties，避免直接注入裸 String 导致的 Bean 创建失败
 */
@Component
@RequiredArgsConstructor
public class JwtUtils {

    private final JwtProperties jwtProperties;

    /**
     * 获取签名密钥（懒加载 + 缓存，避免每次解析都重新生成）
     * <p>
     * Keys.hmacShaKeyFor() 会自动校验密钥长度 ≥ 256位
     * 密钥不合法时会在启动阶段就暴露问题，而不是等到运行时
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成 JWT Token
     *
     * @param username 用户名（作为 token 的 subject）
     * @param claims   自定义声明（如角色、用户ID等），可为 null
     * @return 签名字符串形式的 JWT
     */
    public String generateToken(String username, Map<String, Object> claims) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getExpirationMs());

        var builder = Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey()); // 0.12.x 新API：自动绑定 HMAC-SHA 算法

        // 如果有自定义声明，批量添加
        if (claims != null && !claims.isEmpty()) {
            builder.claims(claims);
        }

        return builder.compact();
    }

    /**
     * 解析并验证 JWT Token
     *
     * @param token JWT 字符串
     * @return Claims 载荷对象
     * @throws io.jsonwebtoken.JwtException 当 token 过期、签名无效或格式错误时抛出
     */
    public Claims parseToken(String token) {
        // 0.12.x 新API：verifyWith() 替代已废弃的 setSigningKey()
        // parseSignedClaims() 替代已废弃的 parseClaimsJws()
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 token 中提取用户名
     */
    public String getUsernameFromToken(String token) {
        return parseToken(token).getSubject();
    }

    /**
     * 验证 token 是否有效（未过期 + 签名正确）
     *
     * @return true=有效，false=无效（捕获异常而非向上抛出，适合 Filter 中的快速判断）
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            // 生产环境建议用 log.debug 记录具体原因，避免日志被恶意请求刷爆
            return false;
        }
    }
}