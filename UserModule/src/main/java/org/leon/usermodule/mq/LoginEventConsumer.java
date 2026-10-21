package org.leon.usermodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.usermodule.config.LoginEventQueueConfig;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.leon.usermodule.pojo.vo.ProcessedLogin;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 本任务只落观察队列；Task 10 在这里加 Feign 反查。 */
@Slf4j
@Component
public class LoginEventConsumer {

    private final RecentLoginStore store;

    public LoginEventConsumer(RecentLoginStore store) {
        this.store = store;
    }

    @RabbitListener(queues = LoginEventQueueConfig.LOGIN_EVENT_QUEUE)
    public void onLoginEvent(LoginEvent event) {
        log.info("[登录事件消费者] traceId={} userId={} username={} ip={}",
                event.getTraceId(), event.getUserId(), event.getUsername(), event.getLoginIp());
        store.add(new ProcessedLogin(event, System.currentTimeMillis()));
    }
}
