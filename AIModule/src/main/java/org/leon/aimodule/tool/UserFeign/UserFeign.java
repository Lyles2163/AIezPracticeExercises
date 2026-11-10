package org.leon.aimodule.tool.UserFeign;

import org.leon.aimodule.tool.dto.WrongQuestionDTO;
import org.leon.commonjwt.pojo.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 调用 PracticeModule 的 Feign 客户端。
 *
 * 走 Nacos 服务发现（name = PracticeModuleServer），所以 URL 不用写死端口；
 * 本地调试 PracticeModuleServer 起在 8102，Nacos 会把名字解析过去。
 *
 * 身份头 {@code X-User-Name} 由调用方传入：AI 工具是按"当前聊天用户"去查他的错题，
 * 不能写死。
 */
@FeignClient(name = "UserModuleServer", path = "/user")
public interface UserFeign {

    @GetMapping("/list")
    Result<List<WrongQuestionDTO>> list(@RequestHeader("X-User-Name") String userName,
                                        @RequestParam(name = "subjectId", required = false) Long subjectId,
                                        @RequestParam(name = "limit", required = false) Integer limit);
}
