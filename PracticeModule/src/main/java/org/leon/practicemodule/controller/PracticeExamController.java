package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.ExamDraftReq;
import org.leon.practicemodule.pojo.ExamStartReq;
import org.leon.practicemodule.pojo.ExamSubmitReq;
import org.leon.practicemodule.pojo.vo.ExamAttemptItemVO;
import org.leon.practicemodule.pojo.vo.ExamDraftVO;
import org.leon.practicemodule.pojo.vo.ExamQuestionVO;
import org.leon.practicemodule.pojo.vo.ExamResultVO;
import org.leon.practicemodule.pojo.vo.ExamStartVO;
import org.leon.practicemodule.pojo.vo.PageVO;
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

    /** 存草稿：整包覆盖。前端每 30 秒自动存一次与"保存草稿"按钮都打这里。 */
    @PostMapping("/draft")
    public Result<ExamDraftVO> draft(@RequestHeader("X-User-Name") String userName,
                                     @RequestBody(required = false) ExamDraftReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        ExamDraftReq body = req == null ? new ExamDraftReq() : req;
        return Result.success(examService.draft(userId, body.getAttemptId(), body.getAnswers()));
    }

    /** 交卷：幂等（连点两次只判一次分）。成绩单同时就是 attempt/detail 的内容。 */
    @PostMapping("/submit")
    public Result<ExamResultVO> submit(@RequestHeader("X-User-Name") String userName,
                                       @RequestBody(required = false) ExamSubmitReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        ExamSubmitReq body = req == null ? new ExamSubmitReq() : req;
        return Result.success(examService.submit(userId, body.getAttemptId()));
    }

    /** 某张卷子的历次作答（卡片上"已提交次数"点进去的那页） */
    @GetMapping("/attempt/page")
    public Result<PageVO<ExamAttemptItemVO>> attemptPage(@RequestHeader("X-User-Name") String userName,
                                                         @RequestParam Long paperId,
                                                         @RequestParam(required = false) Long pageNum,
                                                         @RequestParam(required = false) Long pageSize) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(examService.attemptPage(userId, paperId, pageNum, pageSize));
    }

    /** 成绩单详情：刷新不丢解析（与 submit 同一个 ExamResultVO） */
    @GetMapping("/attempt/detail")
    public Result<ExamResultVO> attemptDetail(@RequestHeader("X-User-Name") String userName,
                                              @RequestParam Long attemptId) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(examService.attemptDetail(userId, attemptId));
    }
}
