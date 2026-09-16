package org.leon.authmodule.service;

import com.baomidou.mybatisplus.spring.service.IService;
import jakarta.validation.Valid;
import org.leon.authmodule.pojo.RegisterRequest;
import org.leon.authmodule.pojo.Result;

/**
* @author Administrator
* @description 针对表【users_dev(用户核心账号表)】的数据库操作Service
* @createDate 2026-09-10 15:29:46
*/
public interface registerService extends IService<RegisterRequest> {

    Result register(@Valid RegisterRequest request);
}
