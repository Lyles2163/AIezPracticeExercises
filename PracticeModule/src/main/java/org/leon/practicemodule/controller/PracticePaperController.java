package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.PaperCardVO;
import org.leon.practicemodule.pojo.vo.PaperDetailVO;
import org.leon.practicemodule.service.PaperService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 试卷读接口。{@code /practice/**} 已在 Nacos 的 gateway.yaml 里路由到
 * PracticeModuleServer，且不在 JwtAuthGlobalFilter 白名单内——所以必须带 token 走 8100，
 * 网关从 JWT 里注入 X-User-Name，前端不传它。
 */
@RestController
@RequestMapping("/practice/paper")
public class PracticePaperController {

    private final PaperService paperService;
    private final UserIdentityService userIdentityService;

    public PracticePaperController(PaperService paperService, UserIdentityService userIdentityService) {
        this.paperService = paperService;
        this.userIdentityService = userIdentityService;
    }

    /** 真题套卷卡片页（参考截图①）。subjectId 必填，三个徽章字段可选过滤。 */
    @GetMapping("/page")
    public Result<PageVO<PaperCardVO>> page(@RequestHeader("X-User-Name") String userName,
                                            @RequestParam Long subjectId,
                                            @RequestParam(required = false) Integer examYear,
                                            @RequestParam(required = false) Integer examMonth,
                                            @RequestParam(required = false) Integer setNo,
                                            @RequestParam(required = false) Long pageNum,
                                            @RequestParam(required = false) Long pageSize) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(paperService.page(userId, subjectId, examYear, examMonth, setNo, pageNum, pageSize));
    }

    /**
     * 卷面骨架（参考截图②的左侧与答题卡）。paperId 必填，缺失由 Spring 绑定层转 40000，
     * 不在这里兜——口径与 {@code question/current} 的 subjectId 一致。
     */
    @GetMapping("/detail")
    public Result<PaperDetailVO> detail(@RequestHeader("X-User-Name") String userName,
                                        @RequestParam Long paperId) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(paperService.detail(userId, paperId));
    }
}
