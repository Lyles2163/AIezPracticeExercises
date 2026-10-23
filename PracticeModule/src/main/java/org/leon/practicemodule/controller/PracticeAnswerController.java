package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.AnswerSubmitReq;
import org.leon.practicemodule.pojo.SelfJudgeReq;
import org.leon.practicemodule.pojo.vo.AnswerSubmitVO;
import org.leon.practicemodule.pojo.vo.SelfJudgeVO;
import org.leon.practicemodule.service.AnswerService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 作答接口。身份只从 X-User-Name 换取——请求体/参数里的任何 userId 都不看。
 */
@RestController
@RequestMapping("/practice/answer")
public class PracticeAnswerController {

    private final AnswerService answerService;
    private final UserIdentityService userIdentityService;

    public PracticeAnswerController(AnswerService answerService,
                                    UserIdentityService userIdentityService) {
        this.answerService = answerService;
        this.userIdentityService = userIdentityService;
    }

    @PostMapping("/submit")
    public Result<AnswerSubmitVO> submit(@RequestHeader("X-User-Name") String userName,
                                         @RequestBody AnswerSubmitReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(answerService.submit(userId, req));
    }

    @PostMapping("/self-judge")
    public Result<SelfJudgeVO> selfJudge(@RequestHeader("X-User-Name") String userName,
                                         @RequestBody SelfJudgeReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(answerService.selfJudge(userId, req));
    }
}