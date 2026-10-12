package org.leon.authmodule.pojo;

import lombok.Data;

/**
 * 注册请求：手机验证码取代原先形同虚设的 captchaCode 字段。
 */
@Data
public class RegisterRequest {

    private String username;

    private String password;

    private String phone;

    private String smsCode;
}
