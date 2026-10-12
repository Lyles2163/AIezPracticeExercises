package org.leon.authmodule.config;

/**
 * 认证相关 Redis key 的统一入口。
 * 命名风格对齐 AuthRedisService 已有的 auth: 前缀，便于在 redis-cli 里按前缀排查。
 */
public final class AuthCacheKeys {

    private static final String CAPTCHA_PREFIX = "auth:captcha:";
    private static final String SMS_CODE_PREFIX = "auth:sms:code:";
    private static final String SMS_LIMIT_PREFIX = "auth:sms:limit:";

    private AuthCacheKeys() {
    }

    public static String captchaKey(String captchaKey) {
        return CAPTCHA_PREFIX + captchaKey;
    }

    public static String smsCodeKey(String phone) {
        return SMS_CODE_PREFIX + phone;
    }

    public static String smsLimitKey(String phone) {
        return SMS_LIMIT_PREFIX + phone;
    }
}
