package org.leon.authmodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.leon.authmodule.pojo.RegisterRequest;

@Mapper
public interface registerMapper extends BaseMapper<RegisterRequest> {
    void register(String username, String encodedPassword);
}
