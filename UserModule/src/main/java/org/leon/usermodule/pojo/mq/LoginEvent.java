package org.leon.usermodule.pojo.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录事件的消费侧视图。类名/包名与生产端不同是刻意的：
 * MQ 传的是数据契约，不是类型契约（转换器已设为 INFERRED，见计划 Task 10）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginEvent {
    private Long userId;
    private String username;
    private String traceId;
    private String loginIp;
    private long loginAt;
    private String tokenFingerprint;
}
