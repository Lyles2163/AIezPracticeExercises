package org.leon.authmodule.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.mq.LoginEventPublisher;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.Users;
import org.leon.authmodule.pojo.mq.LoginSuccessEvent;
import org.leon.authmodule.util.IpUtil;
import org.leon.authmodule.util.TokenFingerprint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 仅用于演示：[DEMO] 前缀，生产环境必须删除。绕开图形验证码直接发事件。 */
@Slf4j
@RestController
@RequestMapping("/auth/demo")
public class AuthDemoController {

    private final AuthMapper authMapper;
    private final LoginEventPublisher publisher;

    public AuthDemoController(AuthMapper authMapper, LoginEventPublisher publisher) {
        this.authMapper = authMapper;
        this.publisher = publisher;
    }

    @GetMapping("/login-event")
    public Result triggerLoginEvent(@RequestParam(defaultValue = "1") Long userId,
                                    HttpServletRequest request) {
        String clientIp = IpUtil.getClientIp(request);
        Users users = authMapper.selectByUserId(userId);
        if (users == null) {
            log.warn("[DEMO] 用户不存在，不发布事件。userId={}", userId);
            return Result.error("用户不存在，未发布事件");
        }

        String traceId = UUID.randomUUID().toString();
        publisher.publish(new LoginSuccessEvent(
                users.getId(), users.getUsername(), traceId, clientIp,
                System.currentTimeMillis(), TokenFingerprint.of("demo-token-" + userId)));
        log.info("[DEMO] 已发布登录事件 traceId={} userId={}", traceId, userId);
        return Result.success(traceId);
    }
}
