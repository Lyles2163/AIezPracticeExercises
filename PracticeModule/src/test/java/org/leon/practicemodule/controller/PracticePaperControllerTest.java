package org.leon.practicemodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.PaperCardVO;
import org.leon.practicemodule.pojo.vo.PaperDetailVO;
import org.leon.practicemodule.service.PaperService;
import org.leon.practicemodule.service.UserIdentityService;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 控制器的职责只有两件：X-User-Name 换 userId、把参数原样透传给 Service。
 * 参数缺失/类型错的 40000 由 Spring 绑定层 + PracticeExceptionHandler 负责，
 * 那部分在段 1 的 handler 测试里已钉过，这里不重复（口径与段 3 的三个控制器测试一致）。
 */
class PracticePaperControllerTest {

    private final PaperService paperService = mock(PaperService.class);
    private final UserIdentityService identity = mock(UserIdentityService.class);
    private final PracticePaperController controller = new PracticePaperController(paperService, identity);

    @Test
    @DisplayName("page：先把 X-User-Name 换成 userId，再原样透传四个过滤参数")
    void pagePassesThrough() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(paperService.page(eq(10L), eq(1L), eq(2020), isNull(), eq(1), eq(1L), eq(20L)))
                .thenReturn(PageVO.of(List.of(), 0L, 1L, 20L));

        Result<PageVO<PaperCardVO>> r = controller.page("alice", 1L, 2020, null, 1, 1L, 20L);

        assertEquals(1, r.getCode());
        verify(paperService).page(10L, 1L, 2020, null, 1, 1L, 20L);
    }

    @Test
    @DisplayName("page：全缺省时 pageNum/pageSize 是 null，交给 PracticePages 定默认值")
    void pageDefaultsStayInService() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(paperService.page(eq(10L), eq(2L), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(PageVO.of(List.of(), 0L, 1L, 20L));

        controller.page("alice", 2L, null, null, null, null, null);

        verify(paperService).page(10L, 2L, null, null, null, null, null);
    }

    @Test
    @DisplayName("身份解析失败：控制器不兜，异常照给 handler（40100）")
    void identityFailurePropagates() {
        when(identity.resolveUserId("ghost")).thenThrow(
                new PracticeException(PracticeErrorCode.IDENTITY_MISSING, "用户身份不存在，请重新登录"));

        PracticeException e = assertThrows(PracticeException.class,
                () -> controller.page("ghost", 1L, null, null, null, null, null));
        assertEquals(PracticeErrorCode.IDENTITY_MISSING, e.errorCode());
        verifyNoInteractions(paperService);
    }

    @Test
    @DisplayName("卡片字段由 Service 装配，控制器不加工：badges 原样出网")
    void cardIsPassedThroughUntouched() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        PaperCardVO card = new PaperCardVO();
        card.setPaperId(8L);
        card.setTitle("2020年07月大学英语四级真题第1套（样例）");
        card.setBadges(List.of("2020年", "第一套", "7月", "65分钟"));
        card.setTotalScore(new BigDecimal("30.00"));
        card.setQuestionCount(4);
        when(paperService.page(eq(10L), eq(1L), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(PageVO.of(List.of(card), 1L, 1L, 20L));

        PaperCardVO out = controller.page("alice", 1L, null, null, null, null, null)
                .getData().getList().get(0);

        assertEquals(List.of("2020年", "第一套", "7月", "65分钟"), out.getBadges());
        assertEquals(1L, controller.page("alice", 1L, null, null, null, null, null).getData().getTotal());
    }

    @Test
    @DisplayName("detail：只把 paperId 与 userId 交给 Service，骨架原样出网")
    void detailPassesThrough() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        PaperDetailVO vo = new PaperDetailVO();
        vo.setPaperId(7L);
        PaperDetailVO.PartVO part = new PaperDetailVO.PartVO();
        part.setRoman("I");
        PaperDetailVO.SectionVO section = new PaperDetailVO.SectionVO();
        section.setLabel("Section A");
        PaperDetailVO.QuestionRefVO ref = new PaperDetailVO.QuestionRefVO();
        ref.setIndex(1);
        ref.setQuestionId(1L);
        ref.setScore(new BigDecimal("7.00"));
        section.setQuestions(List.of(ref));
        part.setSections(List.of(section));
        vo.setParts(List.of(part));
        when(paperService.detail(10L, 7L)).thenReturn(vo);

        Result<PaperDetailVO> r = controller.detail("alice", 7L);

        assertEquals(1, r.getCode());
        assertEquals(7L, r.getData().getPaperId());
        assertEquals(1, r.getData().getParts().get(0).getSections().get(0).getQuestions().get(0).getIndex());
        verify(paperService).detail(10L, 7L);
    }

    @Test
    @DisplayName("detail 的 40400/40900/40300 由 Service 判定，控制器不兜也不换码")
    void detailErrorPropagates() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(paperService.detail(10L, 999L))
                .thenThrow(new PracticeException(PracticeErrorCode.NOT_FOUND, "试卷不存在"));

        PracticeException e = assertThrows(PracticeException.class, () -> controller.detail("alice", 999L));
        assertEquals(PracticeErrorCode.NOT_FOUND, e.errorCode());
    }
}
