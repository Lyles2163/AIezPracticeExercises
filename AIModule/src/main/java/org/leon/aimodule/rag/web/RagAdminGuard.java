package org.leon.aimodule.rag.web;

import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.exception.RagForbiddenException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 临时管理员判定：网关目前只注入 X-User-Name，没有角色。
 * 名单为空时所有管理员操作一律拒绝，避免"默认放行"。
 */
@Component
public class RagAdminGuard {

    private final Set<String> adminNames;

    public RagAdminGuard(RagProperties props) {
        this.adminNames = props.getAdminNames().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void requireAdmin(String userName) {
        if (userName == null || !adminNames.contains(userName)) {
            throw new RagForbiddenException("仅管理员可访问");
        }
    }
}
