package org.leon.authmodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.config.CaptchaMqConfig;
import org.leon.authmodule.pojo.mq.SmsExpireEvent;
import org.leon.authmodule.pojo.mq.SmsSendRequest;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SmsCodeProducerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @DisplayName("发码消息投到 auth.sms.exchange 的 sms.send 路由键")
    @Test
    void sendSmsCodeUsesSendRoutingKey() {
        SmsSendRequest payload = new SmsSendRequest("13631155285", "trace-1");

        new SmsCodeProducer(rabbitTemplate).sendSmsCode(payload);

        verify(rabbitTemplate).convertAndSend(
                CaptchaMqConfig.SMS_EXCHANGE, CaptchaMqConfig.SMS_SEND_KEY, payload);
    }

    @DisplayName("过期消息投到同一交换机的 sms.expire 路由键（它绑定到延迟队列）")
    @Test
    void sendExpireDelayUsesExpireRoutingKey() {
        SmsExpireEvent event = new SmsExpireEvent("13800001234", "123456", "trace-1");

        new SmsCodeProducer(rabbitTemplate).sendExpireDelay(event);

        verify(rabbitTemplate).convertAndSend(
                CaptchaMqConfig.SMS_EXCHANGE, CaptchaMqConfig.SMS_EXPIRE_DELAY_KEY, event);
    }
}
