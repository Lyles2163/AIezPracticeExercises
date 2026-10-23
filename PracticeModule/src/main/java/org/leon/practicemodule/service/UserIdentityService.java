package org.leon.practicemodule.service;

public interface UserIdentityService {

    /**
     * 把网关注入的 X-User-Name 换成分布式主键 user_id。
     *
     * @throws org.leon.practicemodule.exception.PracticeException 用户名为空或库中无此人（40100）
     */
    Long resolveUserId(String userName);
}
