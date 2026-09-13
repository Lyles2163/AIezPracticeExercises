package org.leon.authmodule.controller;

import jakarta.annotation.Resource;
import org.leon.commonjwt.config.JwtConfig;
import org.leon.commonjwt.utils.JwtUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private JwtUtil jwtUtil; // 自动注入 CommonJwt 模块的工具类

    @Resource
    private JwtConfig jwtConfig;

    @GetMapping("/testLogin")
    public String testLogin() {
        // 1. 模拟一个用户登录成功，拿到用户ID和用户名
        Long userId = 1001L;
        String username = "testUser";

        // 2. 将用户信息放入 Map，作为 JWT 的载荷 (Payload)
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);

        // 3. 调用 CommonJwt 模块的工具类生成 Token
        String token = jwtUtil.generateToken(userId, username); // 使用你写的默认方法
        // 如果你想把 Map 传进去，也可以用：jwtUtil.generateToken(claims);

        return "登录成功！你的 Token 是：\n" + token;
    }
}