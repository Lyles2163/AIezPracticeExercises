package org.leon.usermodule.service.impl;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.leon.usermodule.pojo.Users;
import org.leon.usermodule.service.UsersService;
import org.leon.usermodule.mapper.UsersMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
* @author Administrator
* @description 针对表【users_dev(用户核心账号表)】的数据库操作Service实现
* @createDate 2026-09-10 15:29:46
*/
@Service
public class UsersServiceImpl extends ServiceImpl<UsersMapper, Users>
    implements UsersService {
    @Autowired
    private UsersMapper usersMapper;

    @Override
    public List<Users> selectAll() {
        return usersMapper.selectList(null);
    }

    @Override
    public void deleteById(Integer id) {
        usersMapper.deleteById(id);
    }
}




