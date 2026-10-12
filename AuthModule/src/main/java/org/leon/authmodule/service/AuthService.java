package org.leon.authmodule.service;

import com.baomidou.mybatisplus.spring.service.IService;
import org.leon.authmodule.pojo.LoginRequest;
import org.leon.authmodule.pojo.RegisterRequest;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.Users;

/**
* @author Administrator
* @description 针对表【users_dev(用户核心账号表)】的数据库操作Service
* @createDate 2026-09-10 15:29:46
*/
public interface AuthService extends IService<Users> {

    Result register(RegisterRequest request);

    Result login(LoginRequest request, String clientIp);


    Result logout(String token);
}
