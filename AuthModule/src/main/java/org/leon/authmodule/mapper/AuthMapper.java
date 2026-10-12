package org.leon.authmodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leon.authmodule.pojo.Users;

@Mapper
public interface AuthMapper extends BaseMapper<Users> {
    void register(@Param("username") String username,
                  @Param("encodedPassword") String encodedPassword,
                  @Param("phone") String phone);

    Users selectByUsername(String username);

    Users selectByUserId(Long userID);

    void updateLastLogin(@Param("userId") Long userId, @Param("ip") String ip);
}
