package org.leon.usermodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.usermodule.config.LoginEventQueueConfig;
import org.leon.usermodule.feign.AuthModuleClient;
import org.leon.usermodule.pojo.vo.UserProfileVO;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * 关键断言：消息的 __TypeId__ 指向 ForeignLoginEvent（本服务里另一个类），
 * 消费端仍能按字段名反序列化成 LoginEvent —— 证明 INFERRED 生效。
 * Feign 用 @MockBean 替身，本测试只验证 MQ 与反序列化，不要求 AuthModule 在线。
 */
@SpringBootTest(properties = "seata.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginEventFlowE2ETest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RecentLoginStore store;

    @MockBean
    private AuthModuleClient authModuleClient;

    @Test
    @DisplayName("异构类型头也能被消费端识别，且完成 Feign 反查")
    void shouldConsumeForeignTypedMessageAndEnrich() throws Exception {
        when(authModuleClient.getUserProfile(10L)).thenReturn(
                new UserProfileVO(10L, "test001", "2", 1, "13631155285", null, "127.0.0.1"));

        rabbitTemplate.convertAndSend(LoginEventQueueConfig.LOGIN_EXCHANGE,
                LoginEventQueueConfig.LOGIN_SUCCESS_KEY,
                new ForeignLoginEvent(10L, "test001", "e2e-1", "127.0.0.1",
                        1_700_000_000_000L, "abcdef123456"));

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && store.recent().isEmpty()) {
            TimeUnit.MILLISECONDS.sleep(200);
        }

        assertEquals(1, store.recent().size(), "10 秒内没消费到，检查类型头或队列绑定");
        assertNotNull(store.recent().get(0).getProfile());
        assertEquals("e2e-1", store.recent().get(0).getEvent().getTraceId());
    }
}
