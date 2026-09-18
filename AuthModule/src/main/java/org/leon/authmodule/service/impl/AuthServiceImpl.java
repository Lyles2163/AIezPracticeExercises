package org.leon.authmodule.service.impl;


import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import io.jsonwebtoken.Claims;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.pojo.RegisterRequest;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.Users;
import org.leon.authmodule.service.AuthService;
import org.leon.commonjwt.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
* @author Administrator
* @description 针对表【users_dev(用户核心账号表)】的数据库操作Service实现
* @createDate 2026-09-10 15:29:46
*/
@Service
public class AuthServiceImpl extends ServiceImpl<AuthMapper, RegisterRequest>
    implements AuthService {
    @Autowired
    private AuthMapper AuthMapper;
    @Autowired
    private JwtUtil jwtUtil;

    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }


    @Override
    public Result register(RegisterRequest request) {
        //         ========== 1. 验证码校验（TODO: 对接 Redis 中的验证码） ==========
        // if (!captchaService.verify(request.getCaptchaKey(), request.getCaptchaCode())) {
        //     return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "验证码错误"));
        // }

        // ========== 2. 密码 BCrypt 加密（绝不明文存储！） ==========
        String encodedPassword = passwordEncoder.encode(request.getPassword());

        AuthMapper.register(request.getUsername(),encodedPassword);

        // userService.register(request.getUsername(), encodedPassword);
        // ⚠️ Service 层需捕获 DuplicateKeyException 并返回"用户名已存在"

//        log.info("新用户注册成功: {}", request.getUsername()); // ⚠️ 日志绝不打印密码！
        return Result.success("注册成功");
    }

    @Override
    public Result login(RegisterRequest request) {

            Users users= AuthMapper.selectByUsername(request.getUsername());
            if (users==null){
                //防止用户恶意试探用户名
                return Result.error("用户名或密码错误");
            }

            //将用户的密码加密后与数据库中加密的密码做对比
            boolean isPassword=passwordEncoder.matches(request.getPassword(),users.getPassword_hash());
             if(!isPassword){
                 return Result.error("密码错误");
             }


        String token= jwtUtil.generateToken2(users.getId(), users.getUsername(),users.getRoleId());
        Map<String,Object> data=new HashMap<>();
        data.put("token",token);
        data.put("userName",users.getUsername());
        Claims result=jwtUtil.getClaimsFromToken(token);
        data.put("iat",result.getIssuedAt());
        data.put("exp",result.getExpiration());
        return Result.success(data);
    }
}




