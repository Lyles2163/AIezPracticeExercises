package org.leon.commonjwt.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.leon.commonjwt.config.JwtConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 核心工具类
 */
@Component
public class JwtUtil {

    @Autowired
    private JwtConfig jwtConfig;

    /**
     * 生成 JWT Token
     * @param userId 用户ID (放入 claims 中)
     * @param username 用户名
     * @return 生成的 Token 字符串
     */
    public String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        return createToken(claims);
    }

    /**
     * 内部创建 Token 的通用方法
     */
    private String createToken(Map<String, Object> claims) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtConfig.getExpiration());

        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                // 使用 HMAC-SHA256 算法和密钥签名
                .signWith(getSignInKey())
                .compact();
    }

    /**
     * 解析 Token 并获取 Claims
     * @param token 待解析的 Token 字符串
     * @return Claims 对象 (包含 userId, username 等)
     */
    public Claims getClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith((SecretKey) getSignInKey()) // ✅ 0.12.x 校验密钥的新方法
                .build()
                .parseSignedClaims(token) // ✅ 解析 SignedClaims
                .getPayload(); // ✅ 获取 Payload (即 Claims)
    }

    /**
     * 根据 Token 获取用户 ID
     */
    public Long getUserIdFromToken(String token) {
        return getClaimsFromToken(token).get("userId", Long.class);
    }

    /**
     * 验证 Token 是否过期
     */
    public Boolean isTokenExpired(String token) {
        try {
            Claims claims = getClaimsFromToken(token);
            return claims.getExpiration().before(new Date());
        } catch (Exception e) {
            // 解析失败或过期，都认为无效
            return true;
        }
    }

    /**
     * 获取签名密钥
     */
    private Key getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtConfig.getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }
}