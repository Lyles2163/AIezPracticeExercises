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

    @RabbitListener(queues = "work.queue")
    public void listen2(String message) {
//        log.info("消费者2收到了消息: {}", message);
        System.out.println("消费者2收到了消息: " + message);
    }
    @RabbitListener(queues = "work.queue")
    public void listen3(String message) {
//        log.info("消费者3收到了消息: {}", message);
        System.out.println("消费者3收到了消息: " + message);
    }
}
