package org.leon.authmodule.pojo;

import lombok.Data;

/**
 * 登录请求：账号密码 + 图形验证码。
 * 校验在 Service 层显式做，不依赖 @Valid（项目未引入 validation starter）。
 */
@Data
public class LoginRequest {

    private String username;

    private String password;

    private String captchaKey;

    private String captchaCode;
}
