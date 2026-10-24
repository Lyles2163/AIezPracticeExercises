package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.QuestionCurrentVO;
import org.leon.practicemodule.pojo.vo.QuestionItemVO;
import org.leon.practicemodule.service.QuestionReadService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题库读接口。身份只从 X-User-Name 换（网关注入），query 里的 userId 一概不看。
 * subjectId 是必填 query：缺参数会抛 MissingServletRequestParameterException，
 * 由 PracticeExceptionHandler 统一转 40000。
 */
@RestController
@RequestMapping("/practice/question")
public class PracticeQuestionController {

    private final QuestionReadService questionReadService;
    private final UserIdentityService userIdentityService;

    public PracticeQuestionController(QuestionReadService questionReadService,
                                      UserIdentityService userIdentityService) {
        this.questionReadService = questionReadService;
        this.userIdentityService = userIdentityService;
    }

    @GetMapping("/page")
    public Result<PageVO<QuestionItemVO>> page(@RequestHeader("X-User-Name") String userName,
                                               @RequestParam Long subjectId,
                                               @RequestParam(required = false) Long pageNum,
                                               @RequestParam(required = false) Long pageSize) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(questionReadService.page(userId, subjectId, pageNum, pageSize));
    }

    /**
     * 取题。三种入参组合都是浏览器可直打的 GET（刷完一题点"下一题"就是这条）：
     * ?subjectId=1&mode=1 顺序断点、?subjectId=1&mode=3 智能练习、?questionId=5 指定题。
     */
    @GetMapping("/current")
    public Result<QuestionCurrentVO> current(@RequestHeader("X-User-Name") String userName,
                                             @RequestParam(required = false) Long subjectId,
                                             @RequestParam(required = false) Integer mode,
                                             @RequestParam(required = false) Long questionId) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(questionReadService.current(userId, subjectId, mode, questionId));
    }
}
