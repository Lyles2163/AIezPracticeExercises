package org.leon.authmodule.service.impl;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.captcha.generator.RandomGenerator;
import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.config.AuthCacheKeys;
import org.leon.authmodule.service.CaptchaService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
public class CaptchaServiceImpl implements CaptchaService {

    private static final String DIGITS_ONLY = "0123456789";
    private static final int CODE_LENGTH = 6;
    private static final int IMAGE_WIDTH = 160;
    private static final int IMAGE_HEIGHT = 60;
    private static final int LINE_COUNT = 20;
    private static final Duration CAPTCHA_TTL = Duration.ofSeconds(120);

    private final StringRedisTemplate redisTemplate;

    public CaptchaServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public CaptchaImage generate() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(IMAGE_WIDTH, IMAGE_HEIGHT, CODE_LENGTH, LINE_COUNT);
        // createLineCaptcha 内部已用默认生成器（含字母）生过一次码；
        // setGenerator 只替换字段不会重新生成，所以必须再 createCode() 一次，
        // 否则图片上的字符与 getCode() 不一致。
        captcha.setGenerator(new RandomGenerator(DIGITS_ONLY, CODE_LENGTH));
        captcha.createCode();

        String code = captcha.getCode();
        String captchaKey = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(AuthCacheKeys.captchaKey(captchaKey), code, CAPTCHA_TTL);

        log.info("[图形码] 生成 captchaKey={} 答案={}（开发日志，上生产必须去掉明文输出）", captchaKey, code);
        return new CaptchaImage(captchaKey, captcha.getImageBase64Data());
    }

    @Override
    public boolean verifyAndConsume(String captchaKey, String code) {
        if (captchaKey == null || captchaKey.isBlank() || code == null || code.isBlank()) {
            return false;
        }

        String redisKey = AuthCacheKeys.captchaKey(captchaKey);
        String expected = redisTemplate.opsForValue().get(redisKey);
        if (expected == null) {
            return false;
        }

        // 一次性：无论命中与否都作废这张图，否则同一张图可以被无限次试错
        redisTemplate.delete(redisKey);
        return expected.equals(code.trim());
    }
}
