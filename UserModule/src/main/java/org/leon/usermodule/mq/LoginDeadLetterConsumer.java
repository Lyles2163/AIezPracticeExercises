package org.leon.usermodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.usermodule.config.LoginEventQueueConfig;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 死信只做可见性：能在这条日志里看到，说明消息确实耗尽重试进了死信。 */
@Slf4j
@Component
public class LoginDeadLetterConsumer {

    @RabbitListener(queues = LoginEventQueueConfig.LOGIN_DEAD_QUEUE)
    public void onDeadLetter(LoginEvent event) {
        log.error("[登录死信] 消息进入死信队列，traceId={} userId={}",
                event.getTraceId(), event.getUserId());
    }
}
