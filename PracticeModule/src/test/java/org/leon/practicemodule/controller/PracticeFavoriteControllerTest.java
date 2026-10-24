package org.leon.practicemodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.pojo.FavoriteToggleReq;
import org.leon.practicemodule.pojo.vo.FavoriteItemVO;
import org.leon.practicemodule.pojo.vo.FavoriteToggleVO;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.service.FavoriteService;
import org.leon.practicemodule.service.UserIdentityService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PracticeFavoriteControllerTest {

    private final FavoriteService favoriteService = mock(FavoriteService.class);
    private final UserIdentityService identity = mock(UserIdentityService.class);
    private final PracticeFavoriteController controller =
            new PracticeFavoriteController(favoriteService, identity);

    private FavoriteToggleReq req(Long subjectId, Long questionId) {
        FavoriteToggleReq r = new FavoriteToggleReq();
        r.setSubjectId(subjectId);
        r.setQuestionId(questionId);
        return r;
    }

    @Test
    @DisplayName("page：身份换取后透传")
    void pagePassesThrough() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(favoriteService.page(eq(10L), eq(1L), any(), any()))
                .thenReturn(PageVO.of(List.of(), 0L, 1L, 20L));

        Result<PageVO<FavoriteItemVO>> r = controller.page("alice", 1L, null, null);

        assertEquals(1, r.getCode());
        verify(favoriteService).page(10L, 1L, null, null);
    }

    @Test
    @DisplayName("toggle：Service 的布尔包成 favorited 字段")
    void toggleWrapsFlag() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(favoriteService.toggle(10L, 1L, 2L)).thenReturn(true);

        Result<FavoriteToggleVO> r = controller.toggle("alice", req(1L, 2L));

        assertEquals(1, r.getCode());
        assertTrue(r.getData().isFavorited());
    }

    @Test
    @DisplayName("toggle：Service 报 40900 时控制器不吞")
    void togglePropagates() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(favoriteService.toggle(10L, 1L, 2L)).thenThrow(
                new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架"));

        assertEquals(PracticeErrorCode.CONFLICT,
                assertThrows(PracticeException.class, () -> controller.toggle("alice", req(1L, 2L))).errorCode());
    }
}
