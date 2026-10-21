package org.leon.usermodule.feign;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 所有 Feign 调用统一带上内部令牌，业务代码不用关心。 */
@Configuration
public class FeignInternalTokenConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(
            @Value("${auth.internal.token}") String internalToken) {
        return template -> template.header("X-Internal-Token", internalToken);
    }
}
