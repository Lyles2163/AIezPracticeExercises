package org.leon.authmodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.leon.authmodule.pojo.RegisterRequest;
import org.leon.authmodule.pojo.Users;

@Mapper
public interface AuthMapper extends BaseMapper<RegisterRequest> {
    void register(String username, String encodedPassword);

    Users selectByUsername(String username);

    Users selectByUserId(Long userID);
}
