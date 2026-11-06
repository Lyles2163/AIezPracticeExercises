package org.leon.aimodule.tool.PracticeFeign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 给所有 Feign 请求自动加上内部令牌头 {@code X-Internal-Token}。
 *
 * AIModule 调 PracticeModule 是服务间直连，不经过网关，
 * 所以需要自己带令牌，PracticeModule 的 InternalTokenFilter 才会放行。
 *
 * 注意：{@code X-User-Name} / {@code X-User-Id} 由具体的 Feign 方法参数传入，
 * 这个拦截器只负责加令牌，不碰用户身份头。
 */
@Component
public class PracticeFeignInterceptor implements RequestInterceptor {

    @Value("${practice.internal-token:ez-practice-internal-token-2026}")
    private String internalToken;

    @Override
    public void apply(RequestTemplate template) {
        template.header("X-Internal-Token", internalToken);
    }
}
