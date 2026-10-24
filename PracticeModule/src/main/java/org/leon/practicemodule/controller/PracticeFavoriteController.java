package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.FavoriteToggleReq;
import org.leon.practicemodule.pojo.vo.FavoriteItemVO;
import org.leon.practicemodule.pojo.vo.FavoriteToggleVO;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.service.FavoriteService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 收藏本。toggle 回的是"最终状态"，前端直接绑星号图标，不用再自己取反 */
@RestController
@RequestMapping("/practice/favorite")
public class PracticeFavoriteController {

    private final FavoriteService favoriteService;
    private final UserIdentityService userIdentityService;

    public PracticeFavoriteController(FavoriteService favoriteService,
                                      UserIdentityService userIdentityService) {
        this.favoriteService = favoriteService;
        this.userIdentityService = userIdentityService;
    }

    @GetMapping("/page")
    public Result<PageVO<FavoriteItemVO>> page(@RequestHeader("X-User-Name") String userName,
                                               @RequestParam Long subjectId,
                                               @RequestParam(required = false) Long pageNum,
                                               @RequestParam(required = false) Long pageSize) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(favoriteService.page(userId, subjectId, pageNum, pageSize));
    }

    @PostMapping("/toggle")
    public Result<FavoriteToggleVO> toggle(@RequestHeader("X-User-Name") String userName,
                                           @RequestBody FavoriteToggleReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        FavoriteToggleVO vo = new FavoriteToggleVO();
        vo.setFavorited(favoriteService.toggle(userId, req.getSubjectId(), req.getQuestionId()));
        return Result.success(vo);
    }
}
