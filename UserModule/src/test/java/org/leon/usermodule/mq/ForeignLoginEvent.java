package org.leon.usermodule.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 仅用于测试：模拟另一个服务里"字段相同、类名不同"的事件类。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ForeignLoginEvent {
    private Long userId;
    private String username;
    private String traceId;
    private String loginIp;
    private long loginAt;
    private String tokenFingerprint;
}
