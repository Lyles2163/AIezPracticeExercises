package org.leon.authmodule.pojo.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 进 auth.sms.send.queue 的消息：只带"要发给谁"，码由消费者生成。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsSendRequest {
    private String phone;
    /** 贯穿一次申请的链路号，便于把日志里的生产者/消费者串起来看 */
    private String traceId;
}
