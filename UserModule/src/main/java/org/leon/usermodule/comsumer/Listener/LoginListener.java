package org.leon.usermodule.comsumer.Listener;

import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class LoginListener {

//    private final AuthRedisService authRedisService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "login.user.queue", durable = "true"),
            exchange = @Exchange(value = "login.topic", durable = "true", type = ExchangeTypes.TOPIC),
            key = "test_key"
    ))
    public void LoginGetUser(Long userId) {
        //登录返回用户信息，进行后续处理
    }
}
