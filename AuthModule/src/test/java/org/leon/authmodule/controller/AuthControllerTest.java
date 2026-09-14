package org.leon.authmodule.controller;

import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class AuthControllerTest {
    @Resource
    private JwtUtil jwtUtil;

    @DisplayName("jwt基础生成测试")
    @Test
    void testLogin() {
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
        System.out.println("生成的jwt为："+token);

    }
    @DisplayName("jwt数据自定义生成测试")
    @Test
    void testLogin2() {


        // 1. 模拟一个用户登录成功，拿到用户ID和用户名
        Long userId = 1001L;
        String username = "testUser";
        String userRole =  "ADMIN";

        // 2. 将用户信息放入 Map，作为 JWT 的载荷 (Payload)
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("userRole", userRole);

        // 3. 调用 CommonJwt 模块的工具类生成 Token
        String token2 = jwtUtil.generateToken2(userId, username,userRole); // JWT
        // 如果你想把 Map 传进去，也可以用：jwtUtil.generateToken(claims);
        System.out.println("自定义生成的jwt为："+token2);
    }
    @DisplayName("jwt数据解析测试")
    @Test
    void testGetClaimsFromToke() {
        // 1. 模拟一个用户登录成功，拿到用户ID和用户名
        Long userId = 1001L;
        String username = "testUser";
        String userRole =  "ADMIN";

        // 2. 将用户信息放入 Map，作为 JWT 的载荷 (Payload)
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("userRole", userRole);

        // 3. 调用 CommonJwt 模块的工具类生成 Token
        String token2 = jwtUtil.generateToken2(userId, username,userRole); // JWT
        // 如果你想把 Map 传进去，也可以用：jwtUtil.generateToken(claims);

        Claims result=jwtUtil.getClaimsFromToken(token2);
        System.out.println("解析jwt的信息为："+result);
    }
}