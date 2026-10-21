package org.leon.authmodule.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.mq.LoginEventPublisher;
import org.leon.authmodule.pojo.LoginRequest;
import org.leon.authmodule.pojo.RegisterRequest;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.Users;
import org.leon.authmodule.pojo.mq.LoginSuccessEvent;
import org.leon.authmodule.service.AuthRedisService;
import org.leon.authmodule.service.AuthService;
import org.leon.authmodule.service.CaptchaService;
import org.leon.authmodule.util.TokenFingerprint;
import org.leon.commonjwt.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AuthServiceImpl extends ServiceImpl<AuthMapper, Users>
        implements AuthService {

    @Autowired
    private AuthMapper AuthMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AuthRedisService authRedisService;

    @Autowired
    private CaptchaService captchaService;

    @Autowired
    private LoginEventPublisher loginEventPublisher;

    /** 直接读配置，避免依赖 JwtConfig 的具体 API */
    @Value("${leon.jwt.expiration:7200000}")
    private long jwtExpiration;

    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Result register(RegisterRequest request) {
        String encodedPassword = passwordEncoder.encode(request.getPassword());
        try {
            AuthMapper.register(request.getUsername(), encodedPassword, request.getPhone());
        } catch (DuplicateKeyException e) {
            // users_dev 上有两个唯一索引（Task 6 Step 1 实测：uk_users_username、uk_users_dev_phone），
            // 只按"手机号已注册"回话会把重名用户误报成手机号问题。
            String violated = e.getMostSpecificCause().getMessage();
            if (violated != null && violated.contains("uk_users_username")) {
                log.info("[注册] 用户名冲突 username={}", request.getUsername());
                return Result.error("用户名已存在");
            }
            log.info("[注册] 手机号冲突 username={} phone={}", request.getUsername(), request.getPhone());
            return Result.error("该手机号已注册");
        }
        return Result.success("注册成功");
    }

    @Override
    public Result login(LoginRequest request, String clientIp) {
        // 图形码放在最前面：一次登录尝试必须先过人机校验，才轮到查库比对密码。
        // verifyAndConsume 无论成败都会作废这张图，所以撞库者每次都要重新取一张。
        if (!captchaService.verifyAndConsume(request.getCaptchaKey(), request.getCaptchaCode())) {
            return Result.error("图形验证码错误或已失效");
        }

        Users users = AuthMapper.selectByUsername(request.getUsername());
        if (users == null) {
            return Result.error("用户名或密码错误");
        }

        boolean isPassword = passwordEncoder.matches(request.getPassword(), users.getPassword_hash());
        if (!isPassword) {
            return Result.error("密码错误");
        }

        // 1. 记录本次登录信息，失败则不签发 token，避免出现"已登录但无记录"
        AuthMapper.updateLastLogin(users.getId(), clientIp);

        // 2. 生成 JWT
        String token = jwtUtil.generateToken2(users.getId(), users.getUsername(), users.getRoleId());

        // 3. 写入 Redis（token + 用户信息，TTL 与 JWT 一致）
        authRedisService.saveLoginInfo(users.getId(), token, users, jwtExpiration);

        // 5. 发布登录事件。MQ 是"尽力而为"：Broker 不可用绝不能把登录搞挂。
        String traceId = UUID.randomUUID().toString();
        try {
            loginEventPublisher.publish(new LoginSuccessEvent(
                    users.getId(), users.getUsername(), traceId, clientIp,
                    System.currentTimeMillis(), TokenFingerprint.of(token)));
        } catch (Exception e) {
            log.error("[登录事件] 发布失败，登录继续。userId={} traceId={}", users.getId(), traceId, e);
        }

        // 6. 返回给前端
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userName", users.getUsername());
        Claims claims = jwtUtil.getClaimsFromToken(token);
        data.put("iat", claims.getIssuedAt());
        data.put("exp", claims.getExpiration());
        return Result.success(data);
    }

    @Override
    public Result logout(String token) {
        //判断redis中是否存在登录用户的token；
        if (token==null || token.isBlank()){
            return  Result.error("未携带token");
        }
        Long userId ;
        try {
            Claims claims=jwtUtil.getClaimsFromToken(token);
            userId =Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return Result.success("已退出");
        }
        //只删除当前的这个token的对应的登录信息
        //防止：用户在新设备登录后，旧设备调用logout 将新会话踢掉；
        if (authRedisService.isTokenValid(userId, token)){
            authRedisService.deleteLoginInfo(userId);
        }

        return Result.success("退出成功");
    }


}