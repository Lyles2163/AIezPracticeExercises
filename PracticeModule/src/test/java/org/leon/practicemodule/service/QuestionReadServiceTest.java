package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserFavoriteMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.mapper.QuestionPageRow;
import org.leon.practicemodule.mapper.QuestionPositionRow;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.QuestionCurrentVO;
import org.leon.practicemodule.pojo.vo.QuestionItemVO;
import org.leon.practicemodule.service.impl.QuestionReadServiceImpl;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("题库读侧：三段式分页与取题（断点/智能练习/自评回捞）")
class QuestionReadServiceTest {

    private static final long USER = 10L;

    private final QbQuestionMapper questionMapper = mock(QbQuestionMapper.class);
    private final QbUserAnswerRecordMapper recordMapper = mock(QbUserAnswerRecordMapper.class);
    private final QbUserFavoriteMapper favoriteMapper = mock(QbUserFavoriteMapper.class);
    private final QbSubjectMapper subjectMapper = mock(QbSubjectMapper.class);
    private final QbUserWrongMapper wrongMapper = mock(QbUserWrongMapper.class);
    private final QbQuestionOptionMapper optionMapper = mock(QbQuestionOptionMapper.class);

    private final QuestionReadService service = newImpl();

    private QuestionReadServiceImpl newImpl() {
        return new QuestionReadServiceImpl(questionMapper, recordMapper, favoriteMapper, subjectMapper,
                wrongMapper, optionMapper, new PracticeMasker());
    }

    private QbSubject subject(int status) {
        QbSubject s = new QbSubject();
        s.setId(1L);
        s.setStatus(status);
        return s;
    }

    private void subjectExists() {
        when(subjectMapper.selectById(1L)).thenReturn(subject(1));
    }

    private QuestionPageRow row(long id, int type) {
        return new QuestionPageRow(id, type, 2, "[\"语法\"]", (int) id);
    }

    private QbQuestion question(long id, int status) {
        QbQuestion q = new QbQuestion();
        q.setId(id);
        q.setSubjectId(1L);
        q.setType(1);
        q.setTitle("题干" + id);
        q.setDifficulty(2);
        q.setStatus(status);
        q.setAnswerJson("\"A\"");
        q.setAnalysis("解析，绝不能外泄");
        q.setTagsJson("[\"语法\"]");
        return q;
    }

    private QbQuestionOption option(String key, int isCorrect) {
        QbQuestionOption o = new QbQuestionOption();
        o.setQuestionId(1L);
        o.setOptionKey(key);
        o.setContent("选项" + key);
        o.setIsCorrect(isCorrect);
        return o;
    }

    // ---------- question/page ----------

    @Test
    @DisplayName("subjectId 为 null 是参数问题 40000；科目不存在 40400；已停用 40900")
    void subjectGuards() {
        // 正常链路下缺参在控制器层就转成 40000 了，服务里的 null 分支是防御性的第二道
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.page(USER, null, null, null)).errorCode());
        when(subjectMapper.selectById(1L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, null, null)).errorCode());
        when(subjectMapper.selectById(1L)).thenReturn(subject(0));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, null, null)).errorCode());
        verifyNoInteractions(questionMapper, recordMapper, favoriteMapper);
    }

    @Test
    @DisplayName("pageSize=101 报 40000，且发生在任何查询之前")
    void pageParamRejectedFirst() {
        subjectExists();
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, 1L, 101L)).errorCode());
        verify(questionMapper, never()).countEnabledBySubject(anyLong());
    }

    @Test
    @DisplayName("total=0：空壳照回，不查分页行")
    void emptySubject() {
        subjectExists();
        when(questionMapper.countEnabledBySubject(1L)).thenReturn(0L);
        PageVO<QuestionItemVO> page = service.page(USER, 1L, 1L, null);
        assertEquals(0L, page.getTotal());
        assertEquals(0L, page.getPages());
        assertTrue(page.getList().isEmpty());
        verify(questionMapper, never()).selectEnabledPage(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(recordMapper, favoriteMapper);
    }

    @Test
    @DisplayName("越界页：total 照回真值，list 空，不查分页行")
    void beyondLastPage() {
        subjectExists();
        when(questionMapper.countEnabledBySubject(1L)).thenReturn(4L);
        PageVO<QuestionItemVO> page = service.page(USER, 1L, 9L, null);
        assertEquals(4L, page.getTotal());
        assertEquals(1L, page.getPages(), "4 题 / 每页 20 = 1 页");
        assertTrue(page.getList().isEmpty());
        verify(questionMapper, never()).selectEnabledPage(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("正常页：index=offset+行位，answerStatus 与 favorited 各一条批量查询补齐")
    void normalPage() {
        subjectExists();
        when(questionMapper.countEnabledBySubject(1L)).thenReturn(4L);
        when(questionMapper.selectEnabledPage(1L, 2, 2)).thenReturn(List.of(row(5, 5), row(2, 1)));
        when(recordMapper.selectLatestByQuestions(eq(USER), any()))
                .thenReturn(List.of(new LatestAnswerRow(22L, 5L, "\"我的作答\"", -1, 1)));
        when(favoriteMapper.selectFavoritedQuestionIds(eq(USER), any())).thenReturn(List.of(2L));

        PageVO<QuestionItemVO> page = service.page(USER, 1L, 2L, 2L);

        assertEquals(4L, page.getTotal());
        assertEquals(2L, page.getPages());
        assertEquals(2L, page.getPageNum());
        assertEquals(2, page.getList().size());
        QuestionItemVO first = page.getList().get(0);
        assertEquals(3, first.getIndex(), "offset 2 起第一行 → 第 3 题");
        assertEquals(5L, first.getQuestionId());
        assertEquals(AnswerStatus.PENDING_SELF_JUDGE, first.getAnswerStatus());
        assertEquals(List.of("语法"), first.getTags());
        assertFalse(first.isFavorited());
        QuestionItemVO second = page.getList().get(1);
        assertEquals(4, second.getIndex());
        assertEquals(AnswerStatus.UNANSWERED, second.getAnswerStatus(), "没记录就是未作答");
        assertTrue(second.isFavorited());

        // 三段式：1 次计数 + 1 次分页 + 各 1 次批量补齐，共 4 条 SQL，没有循环查询
        verify(questionMapper, times(1)).countEnabledBySubject(1L);
        verify(questionMapper, times(1)).selectEnabledPage(anyLong(), anyLong(), anyLong());
        verify(recordMapper, times(1)).selectLatestByQuestions(anyLong(), any());
        verify(favoriteMapper, times(1)).selectFavoritedQuestionIds(anyLong(), any());
    }

    @Test
    @DisplayName("计数与取行之间被并发下架：空行集不打 IN ()")
    void concurrentShrinkDoesNotReachIn() {
        subjectExists();
        when(questionMapper.countEnabledBySubject(1L)).thenReturn(4L);
        when(questionMapper.selectEnabledPage(1L, 0, 20)).thenReturn(List.of());
        PageVO<QuestionItemVO> page = service.page(USER, 1L, 1L, 20L);
        assertTrue(page.getList().isEmpty());
        verifyNoInteractions(recordMapper, favoriteMapper);
    }

    @Test
    @DisplayName("answerStatus 四种取值都在列表上映射正确")
    void allStatuses() {
        subjectExists();
        when(questionMapper.countEnabledBySubject(1L)).thenReturn(4L);
        when(questionMapper.selectEnabledPage(1L, 0, 20)).thenReturn(
                List.of(row(1, 1), row(2, 2), row(3, 3), row(4, 4)));
        when(recordMapper.selectLatestByQuestions(eq(USER), any())).thenReturn(List.of(
                new LatestAnswerRow(1L, 1L, "\"A\"", 1, 1),
                new LatestAnswerRow(2L, 2L, "\"[\\\"A\\\"]\"", 0, 1),
                new LatestAnswerRow(3L, 3L, "\"true\"", -1, 1)));
        when(favoriteMapper.selectFavoritedQuestionIds(eq(USER), any())).thenReturn(List.of());

        List<QuestionItemVO> items = service.page(USER, 1L, 1L, 20L).getList();
        assertEquals(AnswerStatus.CORRECT, items.get(0).getAnswerStatus());
        assertEquals(AnswerStatus.WRONG, items.get(1).getAnswerStatus());
        assertEquals(AnswerStatus.PENDING_SELF_JUDGE, items.get(2).getAnswerStatus());
        assertEquals(AnswerStatus.UNANSWERED, items.get(3).getAnswerStatus());
    }

    // ---------- question/current ----------

    @Test
    @DisplayName("current：参数二选一，都给或都不给都是 40000，且零查询")
    void currentParamExclusive() {
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.current(USER, null, null, null)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.current(USER, 1L, 1, 1L)).errorCode());
        verifyNoInteractions(questionMapper, recordMapper, favoriteMapper, subjectMapper,
                wrongMapper, optionMapper);
    }

    @Test
    @DisplayName("current：mode 只认 1 与 3；2（考试）在逐题取题里是 40000")
    void currentModeRejected() {
        subjectExists();
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.current(USER, 1L, 2, null)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.current(USER, 1L, null, null)).errorCode());
        verify(questionMapper, never()).selectFirstUnansweredQuestionId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("current：指定题不存在 40400，已下架 40900")
    void currentQuestionGuards() {
        when(questionMapper.selectById(9L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.current(USER, null, null, 9L)).errorCode());
        when(questionMapper.selectById(9L)).thenReturn(question(9, 0));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.current(USER, null, null, 9L)).errorCode());
    }

    @Test
    @DisplayName("current：指定题走脱敏出口，index 由位置子查询给，answerStatus 与上次作答同步带回")
    void currentByQuestionId() {
        when(questionMapper.selectById(1L)).thenReturn(question(1, 1));
        when(optionMapper.selectDisplayOptions(1L)).thenReturn(List.of(option("A", 1), option("B", 0)));
        when(questionMapper.selectPositions(any())).thenReturn(List.of(new QuestionPositionRow(1L, 2L)));
        when(recordMapper.selectLatestByQuestions(eq(USER), any()))
                .thenReturn(List.of(new LatestAnswerRow(77L, 1L, "\"C\"", 0, 1)));

        QuestionCurrentVO vo = service.current(USER, null, null, 1L);

        assertFalse(vo.isFinished());
        assertEquals(1L, vo.getQuestion().getQuestionId());
        assertEquals("题干1", vo.getQuestion().getTitle());
        assertEquals("单选题", vo.getQuestion().getTypeName());
        assertEquals(2, vo.getQuestion().getOptions().size());
        assertEquals("A", vo.getQuestion().getOptions().get(0).getOptionKey());
        assertEquals(2, vo.getQuestion().getIndex(), "index 是位置序号 2，不是 sort_order");
        assertEquals(AnswerStatus.WRONG, vo.getAnswerStatus());
        assertEquals("\"C\"", vo.getLastUserAnswerJson());
        assertNull(vo.getPendingRecordId(), "已判过的记录没有自评这回事");
    }

    @Test
    @DisplayName("current：待自评 + 练习记录才回 pendingRecordId；考试的 -1 不回")
    void pendingRecordIdGate() {
        when(questionMapper.selectById(5L)).thenReturn(question(5, 1));
        when(questionMapper.selectPositions(any())).thenReturn(List.of(new QuestionPositionRow(5L, 4L)));
        when(recordMapper.selectLatestByQuestions(eq(USER), any()))
                .thenReturn(List.of(new LatestAnswerRow(22L, 5L, "\"我的作答\"", -1, 1)));

        QuestionCurrentVO practice = service.current(USER, null, null, 5L);
        assertEquals(AnswerStatus.PENDING_SELF_JUDGE, practice.getAnswerStatus());
        assertEquals(22L, practice.getPendingRecordId(), "刷新页面后能继续自评，全靠这个 id");

        when(recordMapper.selectLatestByQuestions(eq(USER), any()))
                .thenReturn(List.of(new LatestAnswerRow(23L, 5L, "\"我的作答\"", -1, 2)));
        QuestionCurrentVO exam = service.current(USER, null, null, 5L);
        assertEquals(AnswerStatus.PENDING_SELF_JUDGE, exam.getAnswerStatus());
        assertNull(exam.getPendingRecordId(), "mode=2 走 self-judge 必然 40000，不能回按钮");
    }

    @Test
    @DisplayName("current mode=1：走 NOT EXISTS 断点，绝不查错题表")
    void sequentialBreakpoint() {
        subjectExists();
        when(questionMapper.selectFirstUnansweredQuestionId(1L, USER)).thenReturn(2L);
        when(questionMapper.selectById(2L)).thenReturn(question(2, 1));
        when(questionMapper.selectPositions(any())).thenReturn(List.of(new QuestionPositionRow(2L, 3L)));

        QuestionCurrentVO vo = service.current(USER, 1L, 1, null);

        assertEquals(2L, vo.getQuestion().getQuestionId());
        assertEquals(3, vo.getQuestion().getIndex());
        assertEquals(AnswerStatus.UNANSWERED, vo.getAnswerStatus());
        verify(wrongMapper, never()).selectSmartCandidateQuestionId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("current mode=3：未掌握错题优先，有候选就不看断点")
    void smartPrefersWrongBook() {
        subjectExists();
        when(wrongMapper.selectSmartCandidateQuestionId(USER, 1L)).thenReturn(1L);
        when(questionMapper.selectById(1L)).thenReturn(question(1, 1));
        when(questionMapper.selectPositions(any())).thenReturn(List.of(new QuestionPositionRow(1L, 1L)));

        assertEquals(1L, service.current(USER, 1L, 3, null).getQuestion().getQuestionId());
        verify(questionMapper, never()).selectFirstUnansweredQuestionId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("current mode=3：没有未掌握错题时回落到顺序断点")
    void smartFallsBackToBreakpoint() {
        subjectExists();
        when(wrongMapper.selectSmartCandidateQuestionId(USER, 1L)).thenReturn(null);
        when(questionMapper.selectFirstUnansweredQuestionId(1L, USER)).thenReturn(6L);
        when(questionMapper.selectById(6L)).thenReturn(question(6, 1));

        assertEquals(6L, service.current(USER, 1L, 3, null).getQuestion().getQuestionId());
    }

    @Test
    @DisplayName("current：刷完了回 finished=true，question 为 null，且一个脱敏器都不走")
    void finished() {
        subjectExists();
        when(questionMapper.selectFirstUnansweredQuestionId(1L, USER)).thenReturn(null);

        QuestionCurrentVO vo = service.current(USER, 1L, 1, null);

        assertTrue(vo.isFinished());
        assertNull(vo.getQuestion());
        assertNull(vo.getAnswerStatus());
        assertNull(vo.getPendingRecordId());
        verifyNoInteractions(optionMapper);
        verify(questionMapper, never()).selectById(any());
    }
}
