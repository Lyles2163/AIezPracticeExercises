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
    @Test
    void testSendMessage2Queue2() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            String queueName = "work.queue";
            String message = "Hello, RabbitMQ_"+i;
            rabbitTemplate.convertAndSend(queueName, message);
            Thread.sleep(20);
        }
    }




    @Test
    void testSendexchange(){
        String exchangeName = "test.fanout";
        String message = "Hello, test.fanout";
        rabbitTemplate.convertAndSend(exchangeName, "", message);
    }

    @Test
    void testSendexchangeDirect(){
        String exchangeName = "test.direct";
        String message = "Hello, test.direct，这个是红色的消息";
        rabbitTemplate.convertAndSend(exchangeName, "red", message);
    }

    @Test
    void testSendexchangeDirect2(){
        String exchangeName = "test.direct";
        String message = "Hello, test.direct，这个是蓝色的消息";
        rabbitTemplate.convertAndSend(exchangeName, "blue", message);
    }
    @Test
    void testSendexchangeDirect3(){
        String exchangeName = "test.direct";
        String message = "Hello, test.direct，这个是黄色的消息";
        rabbitTemplate.convertAndSend(exchangeName, "yellow", message);
    }

}
