package org.leon.commonjwt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性
 * 其他服务的 application.yaml 中需要配置 leon.jwt 开头的属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "leon.jwt")
public class JwtConfig {
    /**
     * JWT 签名的密钥 (生产环境一定要足够长且复杂，建议 256 位以上)
     */
    private String secret = "LeonSecretKeyForJWTInEzPracticeExercises2026IsVeryLongAndComplexNow";

    /**
     * Token 过期时间 (单位：毫秒)，默认 2 小时
     */
    private long expiration = 7200000;
}