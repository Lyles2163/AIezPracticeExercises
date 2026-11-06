package org.leon.practicemodule.service;

public interface UserIdentityService {

    /**
     * 把网关注入的 X-User-Name 换成分布式主键 user_id。
     *
     * @throws org.leon.practicemodule.exception.PracticeException 用户名为空或库中无此人（40100）
     */
    Long resolveUserId(String userName);

    /**
     * 直接用网关注入的 X-User-Id，省掉查库换 userId 那一步。
     * 只有在确认 X-User-Id 是可信来源（网关已校验 JWT 且剥离了客户端伪造头）时才调这个。
     *
     * @throws org.leon.practicemodule.exception.PracticeException userId 为空（40100）
     */
    Long resolveUserIdFromHeader(Long userId);
}
