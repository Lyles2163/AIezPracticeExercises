package org.leon.authmodule.publisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
@SpringBootTest
public class SpringAmqpTest {
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void testSendMessage2Queue(){
        String queueName = "hello.queue2";
        String message = "Hello, RabbitMQ!1016666";
        rabbitTemplate.convertAndSend(queueName, message);
    }
}
