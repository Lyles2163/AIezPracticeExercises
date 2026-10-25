package org.leon.practicemodule.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.pojo.ExamDraftReq;
import org.leon.practicemodule.pojo.ExamStartReq;
import org.leon.practicemodule.pojo.vo.ExamDraftVO;
import org.leon.practicemodule.pojo.vo.ExamQuestionVO;
import org.leon.practicemodule.pojo.vo.ExamStartVO;
import org.leon.practicemodule.service.ExamService;
import org.leon.practicemodule.service.UserIdentityService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 控制器只做两件事：X-User-Name 换 user_id、把 body 原样交给服务层。
 * 所以这里测的就是"有没有多兜一层"——空 body 必须带着 null 落到服务层那条 40000，
 * 而不是在控制器里变成一个看不见的默认值。
 */
class PracticeExamControllerTest {

    private ExamService examService;
    private UserIdentityService identity;
    private PracticeExamController controller;

    @BeforeEach
    void setUp() {
        examService = mock(ExamService.class);
        identity = mock(UserIdentityService.class);
        controller = new PracticeExamController(examService, identity);
    }

    @Test
    @DisplayName("start：body 只有 paperId 与 forceNew，userId 由 X-User-Name 换")
    void startPassesThrough() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        ExamStartVO vo = new ExamStartVO();
        vo.setAttemptId(900L);
        when(examService.start(10L, 8L, true)).thenReturn(vo);
        ExamStartReq req = new ExamStartReq();
        req.setPaperId(8L);
        req.setForceNew(true);

        Result<ExamStartVO> r = controller.start("alice", req);

        assertEquals(1, r.getCode());
        assertEquals(900L, r.getData().getAttemptId());
        verify(examService).start(10L, 8L, true);
    }

    @Test
    @DisplayName("空 body：paperId 原样给 null，交给 Service 报 40000（控制器不兜）")
    void emptyBodyGoesToService() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(examService.start(10L, null, null)).thenThrow(
                new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法"));
        assertThrows(PracticeException.class, () -> controller.start("alice", null));
        verify(examService).start(10L, null, null);
    }

    @Test
    @DisplayName("question：两个 query 参数原样透传，attemptId 不参与身份判断")
    void questionPassesThrough() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        ExamQuestionVO vo = new ExamQuestionVO();
        vo.setQuestionId(1L);
        when(examService.question(10L, 500L, 1L)).thenReturn(vo);

        Result<ExamQuestionVO> r = controller.question("alice", 500L, 1L);

        assertEquals(1, r.getCode());
        assertEquals(1L, r.getData().getQuestionId());
        verify(examService).question(10L, 500L, 1L);
    }

    @Test
    @DisplayName("draft：attemptId 与 answers 原样透传，进度由服务层算")
    void draftPassesThrough() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        ExamDraftVO vo = new ExamDraftVO();
        vo.setAnsweredCount(2);
        Map<String, String> answers = Map.of("1", "\"A\"", "2", "[\"A\",\"B\"]");
        when(examService.draft(10L, 500L, answers)).thenReturn(vo);
        ExamDraftReq req = new ExamDraftReq();
        req.setAttemptId(500L);
        req.setAnswers(answers);

        Result<ExamDraftVO> r = controller.draft("alice", req);

        assertEquals(1, r.getCode());
        assertEquals(2, r.getData().getAnsweredCount());
        verify(examService).draft(10L, 500L, answers);
    }

    @Test
    @DisplayName("draft 的空 body：attemptId null 进服务层（40000 在那儿报），answers null 不兜成空 map")
    void draftEmptyBodyGoesToService() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(examService.draft(eq(10L), isNull(), isNull())).thenThrow(
                new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法"));

        assertThrows(PracticeException.class, () -> controller.draft("alice", null));
        verify(examService).draft(10L, null, null);
    }
}
