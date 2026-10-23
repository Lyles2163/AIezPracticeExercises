package org.leon.practicemodule.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 绑定 application.yaml 的 practice.* 前缀。
 * 用 @ConfigurationProperties 而非一堆 @Value：拼错属性名会在启动期暴露而不是静默取默认值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "practice")
public class PracticeProperties {

    private final Identity identity = new Identity();

    @Data
    public static class Identity {
        /** qb:uid:{userName} 的存活秒数 */
        private long cacheTtlSeconds = 1800L;
    }
}
