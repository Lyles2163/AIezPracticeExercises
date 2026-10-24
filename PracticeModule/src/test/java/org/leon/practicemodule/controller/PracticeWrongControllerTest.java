package org.leon.practicemodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.pojo.WrongMasterReq;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.WrongItemVO;
import org.leon.practicemodule.service.UserIdentityService;
import org.leon.practicemodule.service.WrongBookService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PracticeWrongControllerTest {

    private final WrongBookService wrongBookService = mock(WrongBookService.class);
    private final UserIdentityService identity = mock(UserIdentityService.class);
    private final PracticeWrongController controller =
            new PracticeWrongController(wrongBookService, identity);

    @Test
    @DisplayName("page：status 缺省透传 null")
    void pagePassesThrough() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(wrongBookService.page(eq(10L), eq(1L), isNull(), any(), any()))
                .thenReturn(PageVO.of(List.of(), 0L, 1L, 20L));

        Result<PageVO<WrongItemVO>> r = controller.page("alice", 1L, null, null, null);

        assertEquals(1, r.getCode());
        verify(wrongBookService).page(10L, 1L, null, null, null);
    }

    @Test
    @DisplayName("master：只取 questionId，返回裸 boolean")
    void masterReturnsFlag() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(wrongBookService.master(10L, 3L)).thenReturn(true);
        WrongMasterReq req = new WrongMasterReq();
        req.setQuestionId(3L);

        Result<Boolean> r = controller.master("alice", req);

        assertEquals(1, r.getCode());
        assertTrue(r.getData());
    }

    @Test
    @DisplayName("请求体是空对象：questionId null 交给 Service 判 40000，控制器不兜")
    void masterEmptyBody() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(wrongBookService.master(10L, null)).thenThrow(
                new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法"));

        assertEquals(PracticeErrorCode.BAD_REQUEST,
                assertThrows(PracticeException.class,
                        () -> controller.master("alice", new WrongMasterReq())).errorCode());
    }
}
