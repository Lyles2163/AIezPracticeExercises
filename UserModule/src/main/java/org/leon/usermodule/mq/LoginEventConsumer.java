package org.leon.usermodule.mq;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.leon.usermodule.config.LoginEventQueueConfig;
import org.leon.usermodule.feign.AuthModuleClient;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.leon.usermodule.pojo.vo.ProcessedLogin;
import org.leon.usermodule.pojo.vo.UserProfileVO;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoginEventConsumer {

    private final RecentLoginStore store;
    private final AuthModuleClient authModuleClient;

    public LoginEventConsumer(RecentLoginStore store, AuthModuleClient authModuleClient) {
        this.store = store;
        this.authModuleClient = authModuleClient;
    }

    @RabbitListener(queues = LoginEventQueueConfig.LOGIN_EVENT_QUEUE)
    public void onLoginEvent(LoginEvent event) {
        log.info("[登录事件消费者] traceId={} userId={} 开始反查资料", event.getTraceId(), event.getUserId());

        // 故意不 catch：反查失败必须外抛，交给重试和死信（只有 404 例外）
        UserProfileVO profile;
        try {
            profile = authModuleClient.getUserProfile(event.getUserId());
        } catch (FeignException e) {
            if (e.status() == 404) {
                // 用户不存在：重试一百次也不会出现，直接进死信
                throw new AmqpRejectAndDontRequeueException(
                        "用户不存在，不重试: " + event.getUserId(), e);
            }
            throw e;
        }

        log.info("[登录事件消费者] traceId={} userId={} roleId={} phone={}",
                event.getTraceId(), event.getUserId(), profile.getRoleId(), profile.getPhone());
        store.add(new ProcessedLogin(event, System.currentTimeMillis(), profile));
    }
}
