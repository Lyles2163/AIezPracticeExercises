package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.pojo.QbSubject;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("科目校验：null 40000 / 不存在 40400 / 停用 40900")
class SubjectGuardsTest {

    private final QbSubjectMapper mapper = mock(QbSubjectMapper.class);

    private QbSubject subject(Integer status, Long ownerId) {
        QbSubject s = new QbSubject();
        s.setId(1L);
        s.setStatus(status);
        s.setOwnerId(ownerId);
        return s;
    }

    @Test
    @DisplayName("四种失败与一种通过")
    void cases() {
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireEnabled(mapper, null)).errorCode());
        when(mapper.selectById(1L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireEnabled(mapper, 1L)).errorCode());
        when(mapper.selectById(1L)).thenReturn(subject(0, 1L));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireEnabled(mapper, 1L)).errorCode());
        when(mapper.selectById(1L)).thenReturn(subject(null, 1L));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireEnabled(mapper, 1L)).errorCode(),
                "status 为 null 的科目按停用处理：列有默认值但别赌");
        when(mapper.selectById(1L)).thenReturn(subject(1, 1L));
        assertDoesNotThrow(() -> SubjectGuards.requireEnabled(mapper, 1L));
    }

    @Test
    @DisplayName("requireVisible：公共与本人科目放行，他人个人科目 40300")
    void visibility() {
        when(mapper.selectById(1L)).thenReturn(subject(1, 1L));
        assertDoesNotThrow(() -> SubjectGuards.requireVisible(mapper, 10L, 1L));

        when(mapper.selectById(2L)).thenReturn(subject(1, 10L));
        assertDoesNotThrow(() -> SubjectGuards.requireVisible(mapper, 10L, 2L));

        when(mapper.selectById(3L)).thenReturn(subject(1, 99L));
        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireVisible(mapper, 10L, 3L)).errorCode());
    }

    @Test
    @DisplayName("requireOwnedPersonal：仅本人个人科目放行，公共科目与他人的科目均 40300")
    void ownedPersonal() {
        when(mapper.selectById(1L)).thenReturn(subject(1, 1L));
        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireOwnedPersonal(mapper, 1L, 1L)).errorCode(),
                "owner_id=1 是公共题库，不能走个人科目写路径");

        when(mapper.selectById(2L)).thenReturn(subject(0, 10L));
        assertDoesNotThrow(() -> SubjectGuards.requireOwnedPersonal(mapper, 10L, 2L),
                "停用中的个人科目仍允许本人编辑/启用/删除");

        when(mapper.selectById(3L)).thenReturn(subject(1, 99L));
        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireOwnedPersonal(mapper, 10L, 3L)).errorCode());
    }
}
