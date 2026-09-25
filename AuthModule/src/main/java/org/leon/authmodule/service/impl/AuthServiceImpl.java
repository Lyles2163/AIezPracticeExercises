package org.leon.authmodule.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import io.jsonwebtoken.Claims;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.pojo.RegisterRequest;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.Users;
import org.leon.authmodule.service.AuthRedisService;
import org.leon.authmodule.service.AuthService;
import org.leon.commonjwt.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class AuthServiceImpl extends ServiceImpl<AuthMapper, RegisterRequest>
        implements AuthService {

    @Autowired
    private AuthMapper AuthMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AuthRedisService authRedisService;

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
        AuthMapper.register(request.getUsername(), encodedPassword);
        return Result.success("注册成功");
    }

    @Override
    public Result login(RegisterRequest request) {
        Users users = AuthMapper.selectByUsername(request.getUsername());
        if (users == null) {
            return Result.error("用户名或密码错误");
        }

        boolean isPassword = passwordEncoder.matches(request.getPassword(), users.getPassword_hash());
        if (!isPassword) {
            return Result.error("密码错误");
        }

        // 1. 生成 JWT
        String token = jwtUtil.generateToken2(users.getId(), users.getUsername(), users.getRoleId());

        // 2. 写入 Redis（token + 用户信息，TTL 与 JWT 一致）
        authRedisService.saveLoginInfo(users.getId(), token, users, jwtExpiration);

        // 3. 返回给前端
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