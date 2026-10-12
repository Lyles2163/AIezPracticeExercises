package org.leon.usermodule.pojo.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户注册后广播出去的通知消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRegisterMessage {

    private Long userId;

    private String username;

    private LocalDateTime registerTime;
}
