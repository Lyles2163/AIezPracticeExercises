package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.ExamStartReq;
import org.leon.practicemodule.pojo.vo.ExamQuestionVO;
import org.leon.practicemodule.pojo.vo.ExamStartVO;
import org.leon.practicemodule.service.ExamService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 套卷考试接口（spec 5.6）。身份沿用本模块口径：网关注入 {@code X-User-Name}，
 * 这里换成 user_id，控制器自己不读任何身份字段。
 */
@RestController
@RequestMapping("/practice/exam")
public class PracticeExamController {

    private final ExamService examService;
    private final UserIdentityService userIdentityService;

    public PracticeExamController(ExamService examService, UserIdentityService userIdentityService) {
        this.examService = examService;
        this.userIdentityService = userIdentityService;
    }

    /** 开考/续考。{@code forceNew} 缺省即"续做"，前端只有"重新开始"按钮会显式传 true。 */
    @PostMapping("/start")
    public Result<ExamStartVO> start(@RequestHeader("X-User-Name") String userName,
                                     @RequestBody(required = false) ExamStartReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        ExamStartReq body = req == null ? new ExamStartReq() : req;
        return Result.success(examService.start(userId, body.getPaperId(), body.getForceNew()));
    }

    /** 逐题取题：只给脱敏正文 + 卷面序号与分值。终态 attempt 报 41002。 */
    @GetMapping("/question")
    public Result<ExamQuestionVO> question(@RequestHeader("X-User-Name") String userName,
                                          @RequestParam Long attemptId,
                                          @RequestParam Long questionId) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(examService.question(userId, attemptId, questionId));
    }
}
