package org.leon.practicemodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.pojo.AnswerSubmitReq;
import org.leon.practicemodule.pojo.SelfJudgeReq;
import org.leon.practicemodule.pojo.vo.AnswerSubmitVO;
import org.leon.practicemodule.pojo.vo.SelfJudgeVO;
import org.leon.practicemodule.pojo.vo.StatDeltaVO;
import org.leon.practicemodule.service.AnswerService;
import org.leon.practicemodule.service.UserIdentityService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PracticeAnswerControllerTest {

    private final AnswerService answerService = mock(AnswerService.class);
    private final UserIdentityService identity = mock(UserIdentityService.class);
    private final PracticeAnswerController controller =
            new PracticeAnswerController(answerService, identity);

    @Test
    @DisplayName("submit：身份换取后交给 Service，结果包 Result")
    void submitWrapsInResult() {
        when(identity.resolveUserId("alice")).thenReturn(2L);
        AnswerSubmitVO vo = new AnswerSubmitVO();
        vo.setRecordId(7L);
        when(answerService.submit(eq(2L), any())).thenReturn(vo);

        Result<AnswerSubmitVO> r = controller.submit("alice", new AnswerSubmitReq());

        assertEquals(1, r.getCode());
        assertEquals(7L, r.getData().getRecordId());
    }

    @Test
    @DisplayName("self-judge：身份换取后交给 Service，statDelta 包进 Result")
    void selfJudgeWrapsInResult() {
        when(identity.resolveUserId("alice")).thenReturn(2L);
        StatDeltaVO delta = new StatDeltaVO();
        delta.setTotal(5);
        SelfJudgeVO vo = new SelfJudgeVO();
        vo.setStatDelta(delta);
        when(answerService.selfJudge(eq(2L), any())).thenReturn(vo);

        Result<SelfJudgeVO> r = controller.selfJudge("alice", new SelfJudgeReq());

        assertEquals(1, r.getCode());
        assertEquals(5, r.getData().getStatDelta().getTotal());
    }

    @Test
    @DisplayName("身份解析失败向上抛给 advice，控制器不吞")
    void propagatesIdentityFailure() {
        when(identity.resolveUserId("ghost"))
                .thenThrow(new PracticeException(PracticeErrorCode.IDENTITY_MISSING, "用户身份不存在，请重新登录"));

        PracticeException e = assertThrows(PracticeException.class,
                () -> controller.submit("ghost", new AnswerSubmitReq()));
        assertEquals(PracticeErrorCode.IDENTITY_MISSING, e.errorCode());
        verify(answerService, never()).submit(anyLong(), any());
    }
}