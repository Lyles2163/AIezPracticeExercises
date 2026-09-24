package org.leon.authmodule.service;

import lombok.RequiredArgsConstructor;
import org.leon.authmodule.pojo.Users;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthRedisService {

    /** key 前缀：auth:login:token:{userId} */
    private static final String TOKEN_KEY_PREFIX = "auth:login:token:";
    /** key 前缀：auth:login:user:{userId} */
    private static final String USER_KEY_PREFIX  = "auth:login:user:";

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 保存登录信息
     * @param userId     用户ID
     * @param token      JWT
     * @param user       用户信息（会自动脱敏，不存密码哈希）
     * @param ttlMillis  过期时间（毫秒，与 JWT 保持一致）
     */
    public void saveLoginInfo(Long userId, String token, Users user, long ttlMillis) {
        Users safeUser = new Users();
        BeanUtils.copyProperties(user, safeUser);
        safeUser.setPassword_hash(null);

        String tokenKey = TOKEN_KEY_PREFIX + userId;
        String userKey  = USER_KEY_PREFIX  + userId;

        redisTemplate.opsForValue().set(tokenKey, token,    ttlMillis, TimeUnit.MILLISECONDS);
        redisTemplate.opsForValue().set(userKey,  safeUser, ttlMillis, TimeUnit.MILLISECONDS);
    }

    public String getToken(Long userId) {
        Object v = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + userId);
        return v == null ? null : v.toString();
    }

    public Users getUser(Long userId) {
        Object v = redisTemplate.opsForValue().get(USER_KEY_PREFIX + userId);
        return v == null ? null : (Users) v;
    }

    /** 校验 Redis 中的 token 是否与传入的一致（可用作 JWT 白名单/登出校验） */
    public boolean isTokenValid(Long userId, String token) {
        String cached = getToken(userId);
        return cached != null && cached.equals(token);
    }

    /** 登出：删除 token 和用户信息 */
    public void deleteLoginInfo(Long userId) {
        redisTemplate.delete(TOKEN_KEY_PREFIX + userId);
        redisTemplate.delete(USER_KEY_PREFIX  + userId);
    }

    /** 续期（可选，滑动过期场景） */
    public void refreshExpire(Long userId, long ttlMillis) {
        redisTemplate.expire(TOKEN_KEY_PREFIX + userId, ttlMillis, TimeUnit.MILLISECONDS);
        redisTemplate.expire(USER_KEY_PREFIX  + userId, ttlMillis, TimeUnit.MILLISECONDS);
    }
}