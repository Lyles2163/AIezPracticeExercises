package org.leon.authmodule.pojo.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 登录成功事件：只带"谁在何时从哪登录"，角色/手机号由消费端反查。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginSuccessEvent {
    private Long userId;
    private String username;
    /** 贯穿生产/消费的链路号 */
    private String traceId;
    private String loginIp;
    /** epoch millis，避免跨服务的日期类型绑定 */
    private long loginAt;
    /** SHA-256(token) 的前 12 位，仅用于日志关联，绝不传 token 本体 */
    private String tokenFingerprint;
}
