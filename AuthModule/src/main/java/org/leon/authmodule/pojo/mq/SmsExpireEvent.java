package org.leon.authmodule.pojo.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 延迟 5 分钟后到期的过期事件。带 code 是为了让消费者2 做幂等比对。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsExpireEvent {
    private String phone;
    private String code;
    private String traceId;
}
