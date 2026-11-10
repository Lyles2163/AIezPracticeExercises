package org.leon.usermodule.mcp_server;

import org.leon.usermodule.pojo.Users;
import org.leon.usermodule.service.UsersService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UserTool {
    @Autowired
    UsersService usersService;

    @Tool(description = "根据用户id查询本人信息")
    public List<Users> getUserInfo(@ToolParam(description = "用户id，格式如1") Integer userId) {
        return usersService.getUserInfo(userId);
    }
}
