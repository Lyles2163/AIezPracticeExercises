package org.leon.usermodule.comsumer.Listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MqListener {
    @RabbitListener(queues = "hello.queue2")
    public void listen(String message) {
        log.info("消费者收到了消息: {}", message);
    }
}
