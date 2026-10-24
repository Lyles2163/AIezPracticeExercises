package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.FavoritePageRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserFavoriteMapper;
import org.leon.practicemodule.mapper.QuestionPositionRow;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.vo.FavoriteItemVO;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.service.impl.FavoriteServiceImpl;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("收藏本：分页与 toggle 的删除优先三步走")
class FavoriteServiceTest {

    private static final long USER = 10L;

    private final QbUserFavoriteMapper favoriteMapper = mock(QbUserFavoriteMapper.class);
    private final QbQuestionMapper questionMapper = mock(QbQuestionMapper.class);
    private final QbSubjectMapper subjectMapper = mock(QbSubjectMapper.class);

    private final FavoriteService service =
            new FavoriteServiceImpl(favoriteMapper, questionMapper, subjectMapper);

    private void subjectExists() {
        QbSubject s = new QbSubject();
        s.setId(1L);
        s.setStatus(1);
        when(subjectMapper.selectById(1L)).thenReturn(s);
    }

    private QbQuestion question(int status) {
        QbQuestion q = new QbQuestion();
        q.setId(1L);
        q.setSubjectId(1L);
        q.setType(1);
        q.setTitle("题".repeat(21));
        q.setStatus(status);
        return q;
    }

    @Test
    @DisplayName("page：科目不存在 40400；pageSize=0 40000；都在计数之前")
    void pageGuards() {
        when(subjectMapper.selectById(9L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.page(USER, 9L, null, null)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, 1L, 0L)).errorCode());
        verify(favoriteMapper, never()).countFavorites(anyLong(), anyLong());
    }

    @Test
    @DisplayName("page：total=0 与越界页都不查分页行")
    void emptyPages() {
        subjectExists();
        when(favoriteMapper.countFavorites(USER, 1L)).thenReturn(0L);
        PageVO<FavoriteItemVO> page = service.page(USER, 1L, 1L, 20L);
        assertTrue(page.getList().isEmpty());
        verify(favoriteMapper, never()).selectFavoritePage(anyLong(), anyLong(), anyLong(), anyLong());

        when(favoriteMapper.countFavorites(USER, 1L)).thenReturn(3L);
        assertTrue(service.page(USER, 1L, 4L, 20L).getList().isEmpty());
        verifyNoInteractions(questionMapper);
    }

    @Test
    @DisplayName("page：index 来自位置批量、titleBrief 截断、createdAt 是 epoch 毫秒")
    void normalPage() {
        subjectExists();
        when(favoriteMapper.countFavorites(USER, 1L)).thenReturn(2L);
        when(favoriteMapper.selectFavoritePage(eq(USER), eq(1L), eq(0L), eq(20L))).thenReturn(
                List.of(new FavoritePageRow(1L, new Date(1_700_000_000_000L), 1, "题".repeat(21)),
                        new FavoritePageRow(6L, new Date(1_600_000_000_000L), 1, "短标题")));
        when(questionMapper.selectPositions(any())).thenReturn(
                List.of(new QuestionPositionRow(1L, 1L), new QuestionPositionRow(6L, 2L)));

        PageVO<FavoriteItemVO> page = service.page(USER, 1L, 1L, 20L);

        assertEquals(2L, page.getTotal());
        assertEquals(1L, page.getPages());
        FavoriteItemVO first = page.getList().get(0);
        assertEquals(1L, first.getQuestionId());
        assertEquals(1, first.getIndex());
        assertEquals(20, first.getTitleBrief().length(), "21 字必须截成 20");
        assertEquals("…", first.getTitleBrief().substring(19));
        assertEquals(1_700_000_000_000L, first.getCreatedAt());
        assertEquals("短标题", page.getList().get(1).getTitleBrief());
        assertEquals(1_600_000_000_000L, page.getList().get(1).getCreatedAt());
        verify(questionMapper, times(1)).selectPositions(any());
    }

    @Test
    @DisplayName("toggle：先删；删掉了就回 false，且不查题目状态（裁定五）")
    void removeDoesNotCheckQuestionStatus() {
        when(favoriteMapper.deleteByUserAndQuestion(USER, 1L)).thenReturn(1);

        assertFalse(service.toggle(USER, 1L, 1L));
        verifyNoInteractions(questionMapper, subjectMapper);
        verify(favoriteMapper, never()).insertIgnoreDuplicate(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("toggle：新增分支的题目存在/启用/归属三道校验都在插入之前")
    void addChecksBeforeInsert() {
        when(favoriteMapper.deleteByUserAndQuestion(USER, 1L)).thenReturn(0);
        when(questionMapper.selectById(1L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.toggle(USER, 1L, 1L)).errorCode());

        when(questionMapper.selectById(1L)).thenReturn(question(0));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.toggle(USER, 1L, 1L)).errorCode());

        QbQuestion other = question(1);
        other.setSubjectId(2L);
        when(questionMapper.selectById(1L)).thenReturn(other);
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.toggle(USER, 1L, 1L)).errorCode());

        verify(favoriteMapper, never()).insertIgnoreDuplicate(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("toggle：插入用题目自己的 subjectId，回读存在才报 true")
    void addThenReadBack() {
        when(favoriteMapper.deleteByUserAndQuestion(USER, 1L)).thenReturn(0);
        when(questionMapper.selectById(1L)).thenReturn(question(1));
        when(favoriteMapper.countByUserAndQuestion(USER, 1L)).thenReturn(1L);

        assertTrue(service.toggle(USER, 1L, 1L));
        verify(favoriteMapper).insertIgnoreDuplicate(USER, 1L, 1L);
    }

    @Test
    @DisplayName("toggle：并发下刚插完就被别人删掉，回 false 而不是硬编 true")
    void addRacedToZero() {
        when(favoriteMapper.deleteByUserAndQuestion(USER, 1L)).thenReturn(0);
        when(questionMapper.selectById(1L)).thenReturn(question(1));
        when(favoriteMapper.countByUserAndQuestion(USER, 1L)).thenReturn(0L);

        assertFalse(service.toggle(USER, 1L, 1L), "如实汇报：这一次点击没落下收藏");
    }

    @Test
    @DisplayName("toggle：参数缺失 40000，零写库")
    void paramRequired() {
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.toggle(USER, null, 1L)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.toggle(USER, 1L, null)).errorCode());
        verifyNoInteractions(favoriteMapper, questionMapper, subjectMapper);
    }
}
