package org.leon.authmodule.service;

import lombok.AllArgsConstructor;
import lombok.Data;

public interface CaptchaService {

    /**
     * 生成一张图形验证码，答案写 Redis（TTL 120s）。
     */
    CaptchaImage generate();

    /**
     * 校验并消费：无论成败都删除 Redis 中的答案，保证同一张图只能用一次。
     *
     * @return true 表示验证码正确且此前未使用
     */
    boolean verifyAndConsume(String captchaKey, String code);

    @Data
    @AllArgsConstructor
    class CaptchaImage {
        private String captchaKey;

        /** 形如 data:image/png;base64,iVBORw0...，前端可直接绑到 img 的 src */
        private String imageBase64;
    }
}
