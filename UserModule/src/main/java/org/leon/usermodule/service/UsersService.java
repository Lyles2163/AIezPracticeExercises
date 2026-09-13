package org.leon.usermodule.service;

import org.leon.usermodule.pojo.Users;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;

/**
* @author Administrator
* @description 针对表【users_dev(用户核心账号表)】的数据库操作Service
* @createDate 2026-09-10 15:29:46
*/
public interface UsersService extends IService<Users> {

    List<Users> selectAll();

    void deleteById(Integer id);
}
