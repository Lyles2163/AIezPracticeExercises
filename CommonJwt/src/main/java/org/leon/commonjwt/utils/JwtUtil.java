package org.leon.commonjwt.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.leon.commonjwt.config.JwtConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class JwtUtil {

    @Autowired
    private JwtConfig jwtConfig;

    //  统一使用这一个密钥实例，删除 getSignInKey() 方法
    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        String secret = jwtConfig.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT Secret 未配置 (leon.jwt.secret)");
        }
        // ⬅️ 统一使用 UTF-8 编码派生密钥（与 generateToken 保持一致）
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            log.warn("⚠️ JWT 密钥长度仅 {} 字节，建议至少 32 字节(256位)以保证安全性", keyBytes.length);
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("✅ JWT 工具类初始化成功，过期时间：{}ms", jwtConfig.getExpiration());
    }

    // ==================== Token 生成 ====================

    public String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        return createToken(claims);
    }

    public String generateToken2(Long userId, String username, String userRole) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("userRole", userRole);
        return createToken(claims);
    }

    private String createToken(Map<String, Object> claims) {
        Date now = new Date();
        return Jwts.builder()
                .claims(claims)           // ⬅️ 0.12.x 推荐用 claims() 替代 setClaims()
                .issuedAt(now)            // ⬅️ 0.12.x 推荐用 issuedAt() 替代 setIssuedAt()
                .expiration(new Date(now.getTime() + jwtConfig.getExpiration()))
                .signWith(secretKey)      // ⬅️ 自动选择最优 HMAC 算法，无需手动指定
                .compact();
    }

    // ==================== Token 解析与验证 ====================

    /**
     * 统一解析入口（内部复用，避免重复解析）
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)    // ⬅️ 自动校验算法+签名，无需 requireAlgorithm
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT 验证失败: Token 已过期");
        } catch (MalformedJwtException e) {
            log.warn("JWT 验证失败: Token 格式错误");
        } catch (SecurityException e) {
            log.warn("JWT 验证失败: 签名无效");
        } catch (Exception e) {
            log.warn("JWT 验证失败: {}", e.getMessage());
        }
        return false;
    }

    public Claims getClaimsFromToken(String token) {
        return parseClaims(token); // ⬅️ 复用统一解析方法，不再单独创建 parser
    }

    public Long getUserIdFromToken(String token) {
        return getClaimsFromToken(token).get("userId", Long.class);
    }

    public String getUsernameFromToken(String token) {
        return getClaimsFromToken(token).get("username", String.class);
    }

    public Boolean isTokenExpired(String token) {
        try {
            return parseClaims(token).getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        } catch (Exception e) {
            return true;
        }
    }
}