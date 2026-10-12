package org.leon.usermodule.controller;

import lombok.extern.slf4j.Slf4j;
import org.leon.usermodule.config.RabbitMqDemoConfig;
import org.leon.usermodule.mq.UserRegisterProducer;
import org.leon.usermodule.pojo.Result;
import org.leon.usermodule.pojo.mq.UserRegisterMessage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 手动触发入口，配合浏览器或 Apifox 观察消息链路
 */
@Slf4j
@RestController
@RequestMapping("/user/mq")
public class MqDemoController {

    private final UserRegisterProducer producer;

    public MqDemoController(UserRegisterProducer producer) {
        this.producer = producer;
    }

    @GetMapping("/register")
    public Result register(@RequestParam(defaultValue = "leon") String username) {
        UserRegisterMessage message = new UserRegisterMessage(10086L, username, LocalDateTime.now());
        producer.send(message, RabbitMqDemoConfig.EMAIL_ROUTING_KEY);
        producer.send(message, RabbitMqDemoConfig.SMS_ROUTING_KEY);
        return Result.success("已投递 2 条消息，日志里应出现[邮件消费者]和[短信消费者]各一条");
    }

    @GetMapping("/no-binding")
    public Result noBinding(@RequestParam(defaultValue = "ghost") String username) {
        UserRegisterMessage message = new UserRegisterMessage(10087L, username, LocalDateTime.now());
        producer.send(message, "user.delete.phone");
        return Result.success("这条消息没有任何队列绑定，Broker 会直接丢弃，日志里不会有消费者输出");
    }
}
