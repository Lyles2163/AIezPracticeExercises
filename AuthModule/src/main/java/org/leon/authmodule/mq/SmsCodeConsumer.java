package org.leon.authmodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.config.AuthCacheKeys;
import org.leon.authmodule.pojo.mq.SmsExpireEvent;
import org.leon.authmodule.pojo.mq.SmsSendRequest;
import org.leon.authmodule.service.SmsSender;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
public class SmsCodeConsumer {

    private final StringRedisTemplate redisTemplate;
    private final SmsSender smsSender;
    private final SmsCodeProducer producer;

    @Value("${auth.sms.code-ttl-ms:300000}")
    private long codeTtlMs;

    public SmsCodeConsumer(StringRedisTemplate redisTemplate, SmsSender smsSender, SmsCodeProducer producer) {
        this.redisTemplate = redisTemplate;
        this.smsSender = smsSender;
        this.producer = producer;
    }

    @RabbitListener(queues = "#{smsSendQueue.name}")
    public void onSmsSend(SmsSendRequest request) {
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        log.info("[发码消费者] traceId={} phone={} 线程={}",
                request.getTraceId(), request.getPhone(), Thread.currentThread().getName());

        redisTemplate.opsForValue().set(AuthCacheKeys.smsCodeKey(request.getPhone()),
                code, Duration.ofMillis(codeTtlMs));
        smsSender.send(request.getPhone(), code);

        producer.sendExpireDelay(new SmsExpireEvent(request.getPhone(), code, request.getTraceId()));
    }
}
