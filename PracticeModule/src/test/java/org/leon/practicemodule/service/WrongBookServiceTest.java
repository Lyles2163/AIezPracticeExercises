package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.mapper.QuestionPositionRow;
import org.leon.practicemodule.mapper.WrongPageRow;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.QbUserWrong;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.WrongItemVO;
import org.leon.practicemodule.service.impl.WrongBookServiceImpl;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("错题本：分页补齐与置已掌握的幂等")
class WrongBookServiceTest {

    private static final long USER = 10L;

    private final QbUserWrongMapper wrongMapper = mock(QbUserWrongMapper.class);
    private final QbQuestionMapper questionMapper = mock(QbQuestionMapper.class);
    private final QbUserAnswerRecordMapper recordMapper = mock(QbUserAnswerRecordMapper.class);
    private final QbSubjectMapper subjectMapper = mock(QbSubjectMapper.class);

    private final WrongBookService service =
            new WrongBookServiceImpl(wrongMapper, questionMapper, recordMapper, subjectMapper);

    private void subjectExists() {
        QbSubject s = new QbSubject();
        s.setId(1L);
        s.setStatus(1);
        when(subjectMapper.selectById(1L)).thenReturn(s);
    }

    private WrongPageRow row(long qid, int wrongCount, int status) {
        // 标题刻意 21 字：截断分支必须在真数据形态上走一次（实测题干最长 21 码点）
        return new WrongPageRow(qid, wrongCount, new Date(1_700_000_000_000L + qid), status,
                1, "题".repeat(21));
    }

    @Test
    @DisplayName("status 只认 null/1/2，其它值 40000 且零查询")
    void statusFilterValidated() {
        subjectExists();
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, 3, null, null)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, 0, null, null)).errorCode());
        verifyNoInteractions(wrongMapper, questionMapper, recordMapper);
    }

    @Test
    @DisplayName("科目校验在分页之前")
    void subjectGuardFirst() {
        when(subjectMapper.selectById(9L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.page(USER, 9L, null, null, null)).errorCode());
        verifyNoInteractions(wrongMapper);
    }

    @Test
    @DisplayName("total=0 与越界页都只查一次计数，不查分页行")
    void emptyPages() {
        subjectExists();
        when(wrongMapper.countWrong(eq(USER), eq(1L), isNull())).thenReturn(0L);
        PageVO<WrongItemVO> page = service.page(USER, 1L, null, 1L, 20L);
        assertEquals(0L, page.getTotal());
        assertTrue(page.getList().isEmpty());

        when(wrongMapper.countWrong(eq(USER), eq(1L), eq(1))).thenReturn(3L);
        PageVO<WrongItemVO> beyond = service.page(USER, 1L, 1, 5L, 20L);
        assertEquals(3L, beyond.getTotal());
        assertTrue(beyond.getList().isEmpty());
        verify(wrongMapper, times(2)).countWrong(anyLong(), anyLong(), any());
        verify(wrongMapper, never()).selectWrongPage(anyLong(), anyLong(), any(), anyLong(), anyLong());
        verifyNoInteractions(questionMapper, recordMapper);
    }

    @Test
    @DisplayName("一行错题：index 来自批量位置、titleBrief 已截断、lastWrongAt 是 epoch 毫秒")
    void normalPage() {
        subjectExists();
        when(wrongMapper.countWrong(eq(USER), eq(1L), isNull())).thenReturn(2L);
        when(wrongMapper.selectWrongPage(eq(USER), eq(1L), isNull(), eq(0L), eq(20L)))
                .thenReturn(List.of(row(1, 3, 1), row(6, 1, 1)));
        when(questionMapper.selectPositions(any())).thenReturn(
                List.of(new QuestionPositionRow(1L, 1L), new QuestionPositionRow(6L, 2L)));
        when(recordMapper.selectLatestByQuestions(eq(USER), any()))
                .thenReturn(List.of(new LatestAnswerRow(50L, 1L, "\"C\"", 0, 1)));

        PageVO<WrongItemVO> page = service.page(USER, 1L, null, 1L, 20L);

        WrongItemVO first = page.getList().get(0);
        assertEquals(1L, first.getQuestionId());
        assertEquals(1, first.getIndex());
        assertEquals(3, first.getWrongCount());
        assertEquals(1, first.getStatus());
        assertEquals(20, first.getTitleBrief().length(), "21 字标题必须被截成 20");
        assertEquals("…", first.getTitleBrief().substring(19));
        assertEquals(1_700_000_000_001L, first.getLastWrongAt());
        assertEquals("\"C\"", first.getLastUserAnswerJson());

        WrongItemVO second = page.getList().get(1);
        assertEquals(2, second.getIndex());
        assertNull(second.getLastUserAnswerJson(), "qid 6 没有记录，补齐不能编造");

        verify(questionMapper, times(1)).selectPositions(any());
        verify(recordMapper, times(1)).selectLatestByQuestions(anyLong(), any());
    }

    @Test
    @DisplayName("题目在计数后被下架：位置缺行，index 回 null 而不是崩")
    void missingPositionRowTolerated() {
        subjectExists();
        when(wrongMapper.countWrong(eq(USER), eq(1L), isNull())).thenReturn(1L);
        when(wrongMapper.selectWrongPage(eq(USER), eq(1L), isNull(), eq(0L), eq(20L)))
                .thenReturn(List.of(row(1, 1, 1)));
        when(questionMapper.selectPositions(any())).thenReturn(List.of());

        assertNull(service.page(USER, 1L, null, 1L, 20L).getList().get(0).getIndex());
    }

    @Test
    @DisplayName("master：条件更新命中 1 行就回 true，不再回读")
    void masterHits() {
        when(wrongMapper.masterIfUnmastered(USER, 1L)).thenReturn(1);
        assertTrue(service.master(USER, 1L));
        verify(wrongMapper, never()).selectByUserAndQuestion(anyLong(), anyLong());
    }

    @Test
    @DisplayName("master：0 行时区分已掌握（幂等 true）、无记录（40400）与脏状态（40000）")
    void masterMisses() {
        when(wrongMapper.masterIfUnmastered(USER, 1L)).thenReturn(0);
        QbUserWrong mastered = new QbUserWrong();
        mastered.setStatus(2);
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(mastered);
        assertTrue(service.master(USER, 1L), "第二次点已掌握必须还是 true");

        when(wrongMapper.selectByUserAndQuestion(USER, 2L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.master(USER, 2L)).errorCode());

        when(wrongMapper.selectByUserAndQuestion(USER, 3L)).thenReturn(new QbUserWrong());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.master(USER, 3L)).errorCode(),
                "行在但 status 既不是 1 也不是 2：报 40000，不假装成功");
    }

    @Test
    @DisplayName("master：questionId 缺失 40000，零写库")
    void masterParam() {
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.master(USER, null)).errorCode());
        verifyNoInteractions(wrongMapper);
    }
}
