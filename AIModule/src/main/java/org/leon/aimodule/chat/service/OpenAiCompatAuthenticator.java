package org.leon.aimodule.chat.service;

import org.leon.aimodule.chat.config.ChatProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 桌宠这条链的服务键校验。
 *
 * 这条路由在网关白名单里（不校验 JWT），所以凭据与用户归属只能在这里定：
 * 必须始终成立 —— 没配 api-key 或没配 user-name 就是「路由关闭」，绝不因为「没配」而放行。
 */
@Component
public class OpenAiCompatAuthenticator {

    private static final String BEARER = "Bearer ";

    public enum Decision {
        /** 通过。 */
        OK,
        /** 服务端没配键或没配归属账号：503，路由关闭。 */
        DISABLED,
        /** 键缺失或不匹配：401。 */
        REJECTED
    }

    private final ChatProperties props;

    public OpenAiCompatAuthenticator(ChatProperties props) {
        this.props = props;
    }

    public Decision authorize(String authorizationHeader) {
        ChatProperties.OpenAiCompat cfg = props.getOpenaiCompat();
        if (isBlank(cfg.getApiKey()) || isBlank(cfg.getUserName())) {
            return Decision.DISABLED;
        }
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER)) {
            return Decision.REJECTED;
        }
        byte[] expected = cfg.getApiKey().getBytes(StandardCharsets.UTF_8);
        byte[] actual = authorizationHeader.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8);
        // 常量时间比对：这条链的凭据是唯一防线，不能靠提前返回省那点开销。
        return MessageDigest.isEqual(expected, actual) ? Decision.OK : Decision.REJECTED;
    }

    /** 校验通过后才可调用：桌宠的轮次要落在谁的会话列表里。 */
    public String userName() {
        return props.getOpenaiCompat().getUserName().trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
