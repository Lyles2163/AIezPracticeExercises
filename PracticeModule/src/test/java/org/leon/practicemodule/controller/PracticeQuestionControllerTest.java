package org.leon.practicemodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.QuestionCurrentVO;
import org.leon.practicemodule.pojo.vo.QuestionItemVO;
import org.leon.practicemodule.service.QuestionReadService;
import org.leon.practicemodule.service.UserIdentityService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PracticeQuestionControllerTest {

    private final QuestionReadService readService = mock(QuestionReadService.class);
    private final UserIdentityService identity = mock(UserIdentityService.class);
    private final PracticeQuestionController controller =
            new PracticeQuestionController(readService, identity);

    @Test
    @DisplayName("page：身份换取后交给 Service，结果包 Result")
    void pageWrapsInResult() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(readService.page(eq(10L), eq(1L), any(), any()))
                .thenReturn(PageVO.of(List.of(), 0L, 1L, 20L));

        Result<PageVO<QuestionItemVO>> r = controller.page("alice", 1L, null, null);

        assertEquals(1, r.getCode());
        assertEquals(0L, r.getData().getTotal());
    }

    @Test
    @DisplayName("current：三种入参原样透传给 Service")
    void currentPassesThrough() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        QuestionCurrentVO vo = new QuestionCurrentVO();
        vo.setFinished(true);
        when(readService.current(eq(10L), eq(1L), eq(1), isNull())).thenReturn(vo);

        Result<QuestionCurrentVO> r = controller.current("alice", 1L, 1, null);

        assertEquals(1, r.getCode());
        assertTrue(r.getData().isFinished());
        verify(readService).current(10L, 1L, 1, null);
    }

    @Test
    @DisplayName("身份解析失败向上抛给 advice，控制器不吞")
    void propagatesIdentityFailure() {
        when(identity.resolveUserId("ghost")).thenThrow(
                new PracticeException(PracticeErrorCode.IDENTITY_MISSING, "用户身份不存在，请重新登录"));

        assertEquals(PracticeErrorCode.IDENTITY_MISSING,
                assertThrows(PracticeException.class, () -> controller.page("ghost", 1L, null, null)).errorCode());
        verify(readService, never()).page(anyLong(), any(), any(), any());
    }
}
