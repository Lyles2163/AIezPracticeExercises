package org.leon.usermodule.feign;

import feign.RequestTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FeignInternalTokenConfigTest {

    @DisplayName("拦截器给每个请求加上 X-Internal-Token")
    @Test
    void shouldAddInternalTokenHeader() {
        RequestTemplate template = new RequestTemplate();
        new FeignInternalTokenConfig().internalTokenInterceptor("dev-internal-token").apply(template);

        Collection<String> values = template.headers().get("X-Internal-Token");
        assertTrue(values != null && values.contains("dev-internal-token"));
    }
}
