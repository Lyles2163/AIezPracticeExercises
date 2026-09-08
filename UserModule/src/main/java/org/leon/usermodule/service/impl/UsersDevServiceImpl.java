package org.leon.usermodule.service.impl;


import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.leon.usermodule.pojo.UsersDev;
import org.leon.usermodule.service.UsersDevService;
import org.leon.usermodule.mapper.UsersDevMapper;
import org.springframework.stereotype.Service;

/**
* @author Administrator
* @description 针对表【users_dev(用户核心账号表)】的数据库操作Service实现
* @createDate 2026-09-08 16:16:32
*/
@Service
public class UsersDevServiceImpl extends ServiceImpl<UsersDevMapper, UsersDev>
    implements UsersDevService {

}




