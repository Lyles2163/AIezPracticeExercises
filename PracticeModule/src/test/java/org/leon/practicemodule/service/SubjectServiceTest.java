package org.leon.practicemodule.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserSubjectStatMapper;
import org.leon.practicemodule.mapper.StatCountRow;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.QbUserSubjectStat;
import org.leon.practicemodule.pojo.vo.SubjectItemVO;
import org.leon.practicemodule.service.impl.SubjectServiceImpl;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubjectServiceTest {

    @Mock
    QbSubjectMapper subjectMapper;
    @Mock
    QbUserSubjectStatMapper statMapper;

    private SubjectServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SubjectServiceImpl(subjectMapper, statMapper);
    }

    private QbSubject subject(long id, String name, String key) {
        QbSubject s = new QbSubject();
        s.setId(id);
        s.setName(name);
        s.setSubjectKey(key);
        s.setSortOrder((int) id);
        s.setStatus(1);
        return s;
    }

    @Test
    @DisplayName("有科目但没有统计时，我的进度全为 0 而不是 null")
    void worksWithoutStat() {
        when(subjectMapper.selectEnabledSubjects()).thenReturn(List.of(subject(1, "语文", "chinese")));
        when(statMapper.selectByUserAndSubjects(eq(1L), anyCollection())).thenReturn(List.of());
        when(subjectMapper.countEnabledQuestionRows(anyCollection())).thenReturn(List.of());

        List<SubjectItemVO> list = service.listSubjects(1L);

        assertEquals(1, list.size());
        assertEquals(0, list.get(0).getQuestionCount());
        assertEquals(0, list.get(0).getMyTotal());
        assertEquals(0.0, list.get(0).getMyAccuracy());
        assertNull(list.get(0).getLastAnswerAt());
    }

    @Test
    @DisplayName("正确率按 2 位小数返回；时间转成 epoch 毫秒")
    void accuracyRoundedAndTimeIsMillis() {
        when(subjectMapper.selectEnabledSubjects()).thenReturn(List.of(subject(1, "语文", "chinese")));
        QbUserSubjectStat stat = new QbUserSubjectStat();
        stat.setSubjectId(1L);
        stat.setTotalCount(3);
        stat.setCorrectCount(2);
        stat.setWrongCount(1);
        stat.setLastAnswerAt(new Date(1_700_000_000_000L));
        when(statMapper.selectByUserAndSubjects(eq(1L), anyCollection())).thenReturn(List.of(stat));
        when(subjectMapper.countEnabledQuestionRows(anyCollection()))
                .thenReturn(List.of(new StatCountRow(1L, 10)));

        SubjectItemVO vo = service.listSubjects(1L).get(0);

        assertEquals(10, vo.getQuestionCount());
        assertEquals(3, vo.getMyTotal());
        assertEquals(2, vo.getMyCorrect());
        assertEquals(0.67, vo.getMyAccuracy());
        assertEquals(1_700_000_000_000L, vo.getLastAnswerAt());
    }

    @Test
    @DisplayName("固定三次查询，不在科目循环里查库（N+1 守卫）")
    void noPerSubjectQuery() {
        when(subjectMapper.selectEnabledSubjects()).thenReturn(
                List.of(subject(1, "语文", "chinese"), subject(2, "数学", "math")));
        when(statMapper.selectByUserAndSubjects(anyLong(), anyCollection())).thenReturn(List.of());
        when(subjectMapper.countEnabledQuestionRows(anyCollection())).thenReturn(List.of());

        service.listSubjects(1L);

        verify(subjectMapper, times(1)).selectEnabledSubjects();
        verify(statMapper, times(1)).selectByUserAndSubjects(anyLong(), anyCollection());
        verify(subjectMapper, times(1)).countEnabledQuestionRows(anyCollection());
        verifyNoMoreInteractions(subjectMapper, statMapper);
    }

    @Test
    @DisplayName("没有启用科目时不查统计也不查题数")
    void noSubjectsShortCircuits() {
        when(subjectMapper.selectEnabledSubjects()).thenReturn(List.of());

        assertTrue(service.listSubjects(1L).isEmpty());
        verifyNoInteractions(statMapper);
        verify(subjectMapper, never()).countEnabledQuestionRows(anyCollection());
    }
}
