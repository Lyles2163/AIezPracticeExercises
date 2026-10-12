package org.leon.authmodule.pojo;

import lombok.Data;

/**
 * 申请手机验证码：必须先带一张有效的图形验证码，防止脚本直接刷短信。
 */
@Data
public class SendSmsRequest {

    private String phone;

    private String captchaKey;

    private String captchaCode;
}
