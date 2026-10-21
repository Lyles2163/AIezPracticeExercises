package org.leon.authmodule.controller;

import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.pojo.Users;
import org.leon.authmodule.pojo.vo.UserProfileVO;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供 UserModule 通过 Feign 反查。直接返回 VO，不套 Result：
 * Result.data 声明为 Object，Jackson 会反序列化成 LinkedHashMap。
 */
@RestController
@RequestMapping("/auth/internal")
public class InternalUserController {

    private final AuthMapper authMapper;

    public InternalUserController(AuthMapper authMapper) {
        this.authMapper = authMapper;
    }

    @GetMapping(value = "/users/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserProfileVO> getUserProfile(@PathVariable Long userId) {
        Users users = authMapper.selectByUserId(userId);
        if (users == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new UserProfileVO(
                users.getId(), users.getUsername(), users.getRoleId(), users.getStatus(),
                users.getPhone(), users.getLast_login_at(), users.getLast_login_ip()));
    }
}
