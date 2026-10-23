package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.vo.SubjectItemVO;
import org.leon.practicemodule.service.SubjectService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/practice/subject")
public class PracticeSubjectController {

    private final SubjectService subjectService;
    private final UserIdentityService userIdentityService;

    public PracticeSubjectController(SubjectService subjectService,
                                     UserIdentityService userIdentityService) {
        this.subjectService = subjectService;
        this.userIdentityService = userIdentityService;
    }

    /**
     * 题库入口页的科目卡片列表。
     * userId 只从 X-User-Name 换取，绝不接受请求参数——那是越权入口。
     */
    @GetMapping("/list")
    public Result<List<SubjectItemVO>> list(@RequestHeader("X-User-Name") String userName) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(subjectService.listSubjects(userId));
    }
}
