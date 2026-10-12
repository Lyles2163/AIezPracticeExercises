package org.leon.authmodule.service;

/** 短信通道抽象。接真通道 = 再加一个实现类，不改消费者。 */
public interface SmsSender {
    void send(String phone, String code);
}
