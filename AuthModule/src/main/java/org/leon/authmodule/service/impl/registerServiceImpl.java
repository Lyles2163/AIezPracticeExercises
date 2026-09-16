package org.leon.authmodule.service.impl;


import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.leon.authmodule.mapper.registerMapper;
import org.leon.authmodule.pojo.RegisterRequest;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.service.registerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
* @author Administrator
* @description 针对表【users_dev(用户核心账号表)】的数据库操作Service实现
* @createDate 2026-09-10 15:29:46
*/
@Service
public class registerServiceImpl extends ServiceImpl<registerMapper, RegisterRequest>
    implements registerService {
    @Autowired
    private registerMapper registerMapper;

    private final PasswordEncoder passwordEncoder;

    public registerServiceImpl(PasswordEncoder passwordEncoder) {
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

        registerMapper.register(request.getUsername(),encodedPassword);

        // userService.register(request.getUsername(), encodedPassword);
        // ⚠️ Service 层需捕获 DuplicateKeyException 并返回"用户名已存在"

//        log.info("新用户注册成功: {}", request.getUsername()); // ⚠️ 日志绝不打印密码！
        return Result.success("注册成功");
    }
}




