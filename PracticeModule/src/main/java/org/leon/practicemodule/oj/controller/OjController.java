package org.leon.practicemodule.oj.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.oj.pojo.OjSubmitReq;
import org.leon.practicemodule.oj.pojo.vo.OjProblemItemVO;
import org.leon.practicemodule.oj.pojo.vo.OjProblemVO;
import org.leon.practicemodule.oj.pojo.vo.OjSubmissionVO;
import org.leon.practicemodule.oj.pojo.vo.OjSubmitVO;
import org.leon.practicemodule.oj.service.OjService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OJ 接口。身份只从 X-User-Name 换取，与 PracticeAnswerController 同口径。
 * 提交立刻返回 submissionId，结果由前端轮询 /submission/{id}。
 */
@RestController
@RequestMapping("/practice/oj")
public class OjController {

    private final OjService ojService;
    private final UserIdentityService userIdentityService;

    public OjController(OjService ojService, UserIdentityService userIdentityService) {
        this.ojService = ojService;
        this.userIdentityService = userIdentityService;
    }

    @PostMapping("/submit")
    public Result<OjSubmitVO> submit(@RequestHeader("X-User-Name") String userName,
                                     @RequestBody OjSubmitReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(ojService.submit(userId, req));
    }

    @GetMapping("/submission/{id}")
    public Result<OjSubmissionVO> submission(@RequestHeader("X-User-Name") String userName,
                                             @PathVariable Long id) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(ojService.getSubmission(userId, id));
    }

    @GetMapping("/problems")
    public Result<List<OjProblemItemVO>> problems(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long pageSize) {
        return Result.success(ojService.listProblems(page, pageSize));
    }

    @GetMapping("/problem/{id}")
    public Result<OjProblemVO> problem(@PathVariable Long id) {
        return Result.success(ojService.getProblem(id));
    }
}
