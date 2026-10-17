package org.leon.usermodule.comsumer.Listener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
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
    @RabbitListener(queues = "test.direct.queue1")
    public void listen4(String message) {
//        log.info("消费者1收到了test.direct.queue1消息: {}", message);
        System.out.println("消费者1收到了test.direct.queue1消息: " + message);
    }
    @RabbitListener(queues = "test.direct.queue2")
    public void listen5(String message) {
//        log.info("消费者2收到test.direct.queue2了消息: {}", message);
        System.out.println("消费者2收到test.direct.queue2了消息: " + message);
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name ="direct.queue1",durable = "true"),
            exchange = @Exchange(name = "test.direct.exchange",type = ExchangeTypes.DIRECT),
            key = {"a", "b"}
    ))
    public void listen6(String message) {
//        log.info("消费者1收到了test.direct.queue1消息: {}", message);
        System.out.println("消费者1收到了test.direct.queue1消息: " + message);
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name ="direct.queue2",durable = "true"),
            exchange = @Exchange(name = "test.direct.exchange",type = ExchangeTypes.DIRECT),
            key = {"b", "c"}
    ))
    public void listen7(String message) {
//        log.info("消费者1收到了test.direct.queue1消息: {}", message);
        System.out.println("消费者2收到了test.direct.queue2消息: " + message);
    }

}
