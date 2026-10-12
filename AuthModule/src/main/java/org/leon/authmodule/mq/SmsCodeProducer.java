package org.leon.authmodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.config.CaptchaMqConfig;
import org.leon.authmodule.pojo.mq.SmsExpireEvent;
import org.leon.authmodule.pojo.mq.SmsSendRequest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmsCodeProducer {

    private final RabbitTemplate rabbitTemplate;

    public SmsCodeProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendSmsCode(SmsSendRequest request) {
        log.info("[发码生产者] traceId={} phone={}", request.getTraceId(), request.getPhone());
        rabbitTemplate.convertAndSend(CaptchaMqConfig.SMS_EXCHANGE, CaptchaMqConfig.SMS_SEND_KEY, request);
    }

    public void sendExpireDelay(SmsExpireEvent event) {
        log.info("[过期生产者] traceId={} phone={}", event.getTraceId(), event.getPhone());
        rabbitTemplate.convertAndSend(CaptchaMqConfig.SMS_EXCHANGE, CaptchaMqConfig.SMS_EXPIRE_DELAY_KEY, event);
    }
}
