package org.leon.practicemodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.pojo.vo.SubjectItemVO;
import org.leon.practicemodule.service.SubjectService;
import org.leon.practicemodule.service.UserIdentityService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PracticeSubjectControllerTest {

    private final SubjectService subjectService = mock(SubjectService.class);
    private final UserIdentityService identity = mock(UserIdentityService.class);
    private final PracticeSubjectController controller =
            new PracticeSubjectController(subjectService, identity);

    @Test
    @DisplayName("返回 Result 包裹，code=1")
    void wrapsInResult() {
        when(identity.resolveUserId("admin")).thenReturn(1L);
        SubjectItemVO vo = new SubjectItemVO();
        vo.setId(1L);
        when(subjectService.listSubjects(1L)).thenReturn(List.of(vo));

        Result<List<SubjectItemVO>> r = controller.list("admin");

        assertEquals(1, r.getCode());
        assertEquals(1, r.getData().size());
        assertEquals(1L, r.getData().get(0).getId());
    }

    @Test
    @DisplayName("身份解析失败时异常向上抛给 advice，控制器不吞")
    void propagatesIdentityFailure() {
        when(identity.resolveUserId(eq("ghost")))
                .thenThrow(new PracticeException(PracticeErrorCode.IDENTITY_MISSING, "用户身份不存在，请重新登录"));

        PracticeException e = assertThrows(PracticeException.class, () -> controller.list("ghost"));
        assertEquals(PracticeErrorCode.IDENTITY_MISSING, e.errorCode());
        verify(subjectService, never()).listSubjects(anyLong());
    }
}
