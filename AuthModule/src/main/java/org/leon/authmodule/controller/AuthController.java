package org.leon.authmodule.controller;

import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.pojo.RegisterRequest;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.service.AuthService;
import org.leon.commonjwt.config.JwtConfig;
import org.leon.commonjwt.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {
    @Autowired
    private JwtUtil jwtUtil; // 自动注入 CommonJwt 模块的工具类
    
    @Autowired
    private AuthService AuthService;
    
    

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
    @GetMapping("/testLogin2")
    public String testLogin2() {
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

        return "登录成功！你的 Token 是：\n" + token2;
    }
    @GetMapping("testGetClaimsFromToke")
    public Claims testGetClaimsFromToke(){
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
        return  result;
    }

    @PostMapping("/login")
    public Result login(@Valid @RequestBody RegisterRequest request){
        return AuthService.login(request);
    }

    @PostMapping("/register")
    public Result register(@Valid @RequestBody RegisterRequest request) {
//         ========== 1. 验证码校验（TODO: 对接 Redis 中的验证码） ==========
        // if (!captchaService.verify(request.getCaptchaKey(), request.getCaptchaCode())) {
        //     return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "验证码错误"))

        // ========== 3. 调用 Service 层保存用户（TODO） ==========
       return AuthService.register(request);

    }






}