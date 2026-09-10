package org.leon.usermodule.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.leon.usermodule.pojo.Users;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
* @author Administrator
* @description 针对表【users_dev(用户核心账号表)】的数据库操作Mapper
* @createDate 2026-09-10 15:29:46
* @Entity generator.pojo.Users
*/
@Mapper
public interface UsersMapper extends BaseMapper<Users> {

}




