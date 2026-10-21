package org.leon.usermodule.feign;

import org.leon.usermodule.pojo.vo.UserProfileVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "AuthModuleServer", path = "/auth/internal")
public interface AuthModuleClient {

    /** 用户不存在时抛 FeignException(status=404)，消费端据此走"不重试"分支。 */
    @GetMapping("/users/{userId}")
    UserProfileVO getUserProfile(@PathVariable("userId") Long userId);
}
