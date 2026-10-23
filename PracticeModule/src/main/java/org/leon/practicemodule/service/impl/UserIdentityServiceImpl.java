package org.leon.practicemodule.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.leon.practicemodule.config.PracticeProperties;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.UsersReadMapper;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 本模块唯一的身份入口。
 *
 * 为什么不靠 token 里的 userId：网关只往下游注入 X-User-Name，且 Authorization 头被网关
 * 删掉了，下游拿不到 token。改网关注入 X-User-Id 是更彻底的方案，但会动 GatewayModule
 * 与 CommonJwt，本期把代价收在这一个类里——将来换方案只改 resolveUserId 的方法体。
 *
 * Redis 只用于这一件事（roadmap 7.2）。缓存故障时降级查库，不阻断取题。
 */
@Slf4j
@Service
public class UserIdentityServiceImpl implements UserIdentityService {

    private static final String CACHE_PREFIX = "qb:uid:";

    private final UsersReadMapper usersReadMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final PracticeProperties props;

    public UserIdentityServiceImpl(UsersReadMapper usersReadMapper,
                                   StringRedisTemplate stringRedisTemplate,
                                   PracticeProperties props) {
        this.usersReadMapper = usersReadMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.props = props;
    }

    @Override
    public Long resolveUserId(String userName) {
        if (!StringUtils.hasText(userName)) {
            throw new PracticeException(PracticeErrorCode.IDENTITY_MISSING, "用户身份不存在，请重新登录");
        }
        String key = CACHE_PREFIX + userName;
        Long cached = readCache(key, userName);
        if (cached != null) {
            return cached;
        }

        Long userId = usersReadMapper.selectIdByUsername(userName);
        if (userId == null) {
            log.warn("X-User-Name 在 users_dev 中不存在: {}", userName);
            throw new PracticeException(PracticeErrorCode.IDENTITY_MISSING, "用户身份不存在，请重新登录");
        }
        writeCache(key, userId, userName);
        return userId;
    }

    private Long readCache(String key, String userName) {
        try {
            String v = stringRedisTemplate.opsForValue().get(key);
            return v == null ? null : Long.valueOf(v);
        } catch (Exception e) {
            log.warn("身份缓存读取失败，降级查库 user={} err={}", userName, e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, Long userId, String userName) {
        try {
            stringRedisTemplate.opsForValue().set(key, String.valueOf(userId),
                    Duration.ofSeconds(props.getIdentity().getCacheTtlSeconds()));
        } catch (Exception e) {
            log.warn("身份缓存写入失败 user={} err={}", userName, e.getMessage());
        }
    }
}
