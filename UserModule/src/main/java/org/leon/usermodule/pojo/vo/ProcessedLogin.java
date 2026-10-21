package org.leon.usermodule.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.leon.usermodule.pojo.mq.LoginEvent;

/** 一条"已处理"的登录记录，供 /user/mq/login/recent 观察。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedLogin {
    private LoginEvent event;
    private long consumedAt;
}
