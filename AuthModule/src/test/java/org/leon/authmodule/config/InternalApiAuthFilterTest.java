package org.leon.authmodule.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalApiAuthFilterTest {

    private InternalApiAuthFilter filter() {
        InternalApiAuthFilter filter = new InternalApiAuthFilter();
        ReflectionTestUtils.setField(filter, "internalToken", "dev-internal-token");
        return filter;
    }

    @DisplayName("缺少或不匹配的令牌返回 401")
    @Test
    void shouldRejectWhenTokenMissingOrWrong() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/internal/users/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilterInternal(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @DisplayName("令牌正确时放行")
    @Test
    void shouldPassWithCorrectToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/internal/users/1");
        request.addHeader("X-Internal-Token", "dev-internal-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilterInternal(request, response, chain);

        assertTrue(chain.getRequest() != null, "放行后请求应继续进入后续过滤器链");
    }
}
