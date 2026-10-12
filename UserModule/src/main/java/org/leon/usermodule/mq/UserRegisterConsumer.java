package org.leon.usermodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.usermodule.config.RabbitMqDemoConfig;
import org.leon.usermodule.pojo.mq.UserRegisterMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 消费端：两个队列各自独立监听，模拟发邮件和发短信两个后续动作
 * 内部的两条 BlockingQueue 只是把"异步收到的消息"记录下来，方便单元测试断言
 */
@Slf4j
@Component
public class UserRegisterConsumer {

    private final BlockingQueue<UserRegisterMessage> emailReceived = new LinkedBlockingQueue<>();

    private final BlockingQueue<UserRegisterMessage> smsReceived = new LinkedBlockingQueue<>();

    @RabbitListener(queues = RabbitMqDemoConfig.EMAIL_QUEUE)
    public void onEmailQueue(UserRegisterMessage message) {
        log.info("[邮件消费者] 收到消息，准备给 {} 发欢迎邮件，注册时间={}", message.getUsername(), message.getRegisterTime());
        emailReceived.offer(message);
    }

    @RabbitListener(queues = RabbitMqDemoConfig.SMS_QUEUE)
    public void onSmsQueue(UserRegisterMessage message) {
        log.info("[短信消费者] 收到消息，准备给 {} 发通知短信", message.getUsername());
        smsReceived.offer(message);
    }

    public UserRegisterMessage pollEmailMessage(long timeout, TimeUnit unit) throws InterruptedException {
        return emailReceived.poll(timeout, unit);
    }

    public UserRegisterMessage pollSmsMessage(long timeout, TimeUnit unit) throws InterruptedException {
        return smsReceived.poll(timeout, unit);
    }
}
