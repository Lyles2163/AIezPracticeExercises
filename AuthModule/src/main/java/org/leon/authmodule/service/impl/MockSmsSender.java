package org.leon.authmodule.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.service.SmsSender;
import org.springframework.stereotype.Component;

/**
 * ⚠️ 本机没有短信通道，这里只打日志。
 * 生产必须换实现并去掉日志里的明文码——把验证码写进日志系统等于泄露。
 */
@Slf4j
@Component
public class MockSmsSender implements SmsSender {

    @Override
    public void send(String phone, String code) {
        log.info("【模拟短信】{} 的登录/注册验证码是 {}（本机无短信通道，此为 mock，测试用）", phone, code);
    }
}
