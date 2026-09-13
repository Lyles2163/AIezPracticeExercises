package org.leon.gatewaymodule.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性类
 * <p>
 * 自动绑定 application.yaml 中 jwt.* 前缀的配置项
 * 使用 @ConfigurationProperties 比 @Value 更安全、更易维护
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * JWT 签名密钥（Base64 编码格式）
     * <p>
     * ⚠️ 生产环境必须通过环境变量 JWT_SECRET 注入，不要硬编码
     * 密钥长度要求：Base64 解码后 ≥ 32字节（256位），否则 jjwt 会抛 WeakKeyException
     * 生成安全密钥命令：openssl rand -base64 32
     */
    private String secret;

    /**
     * Token 过期时间（毫秒）
     * <p>
     * 默认值 43200000 = 12小时
     * 可通过环境变量 JWT_EXPIRATION_MS 覆盖
     */
    private long expirationMs = 43200000L;
}