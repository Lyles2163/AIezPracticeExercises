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

    private QbSubject subject(Integer status) {
        QbSubject s = new QbSubject();
        s.setId(1L);
        s.setStatus(status);
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
        when(mapper.selectById(1L)).thenReturn(subject(0));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireEnabled(mapper, 1L)).errorCode());
        when(mapper.selectById(1L)).thenReturn(subject(null));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireEnabled(mapper, 1L)).errorCode(),
                "status 为 null 的科目按停用处理：列有默认值但别赌");
        when(mapper.selectById(1L)).thenReturn(subject(1));
        assertDoesNotThrow(() -> SubjectGuards.requireEnabled(mapper, 1L));
    }
}
