package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.WrongMasterReq;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.WrongItemVO;
import org.leon.practicemodule.service.UserIdentityService;
import org.leon.practicemodule.service.WrongBookService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 错题本。GET 供浏览器直打与前端分页，POST 只改掌握状态 */
@RestController
@RequestMapping("/practice/wrong")
public class PracticeWrongController {

    private final WrongBookService wrongBookService;
    private final UserIdentityService userIdentityService;

    public PracticeWrongController(WrongBookService wrongBookService,
                                   UserIdentityService userIdentityService) {
        this.wrongBookService = wrongBookService;
        this.userIdentityService = userIdentityService;
    }

    @GetMapping("/page")
    public Result<PageVO<WrongItemVO>> page(@RequestHeader("X-User-Name") String userName,
                                            @RequestParam Long subjectId,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) Long pageNum,
                                            @RequestParam(required = false) Long pageSize) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(wrongBookService.page(userId, subjectId, status, pageNum, pageSize));
    }

    @PostMapping("/master")
    public Result<Boolean> master(@RequestHeader("X-User-Name") String userName,
                                  @RequestBody WrongMasterReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(wrongBookService.master(userId, req.getQuestionId()));
    }
}
