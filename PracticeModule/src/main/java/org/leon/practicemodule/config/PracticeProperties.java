package org.leon.practicemodule.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 绑定 application.yaml 的 practice.* 前缀。
 *
 * 选它而非一堆 @Value：类型安全、成组绑定、IDE 能补全 key，且默认值集中在一处。
 *
 * 注意别指望它兜住拼写错误：默认 ignoreUnknownFields=true，yaml 里把
 * cache-ttl-seconds 写成 cache-ttl-second 会被**静默忽略**并落到字段默认值上，
 * 不会启动失败。反而是 @Value("${...}") 在属性缺失时会启动即炸。所以改配置项名
 * 之后要跑一次 Task 1.6 那样的端到端确认，别只看启动成功。
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
