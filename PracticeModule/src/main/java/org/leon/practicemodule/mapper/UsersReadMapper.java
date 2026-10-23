package org.leon.practicemodule.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 只读访问 users_dev。本模块不拥有用户数据，唯一需要的能力是把
 * 网关注入的 username 换成 user_id，因此不建实体、不引入更新删除。
 */
@Mapper
public interface UsersReadMapper {

    @Select("SELECT id FROM users_dev WHERE username = #{username}")
    Long selectIdByUsername(@Param("username") String username);
}
