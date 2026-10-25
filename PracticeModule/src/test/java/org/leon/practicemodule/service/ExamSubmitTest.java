package org.leon.practicemodule.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.leon.practicemodule.config.PracticeProperties;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.AttemptRecordRow;
import org.leon.practicemodule.mapper.QbExamAttemptMapper;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserSubjectStatMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.mapper.QuestionOptionKeyRow;
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;
import org.leon.practicemodule.pojo.vo.ExamResultVO;
import org.leon.practicemodule.service.impl.ExamServiceImpl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 交卷的四条不变式，每条都用<b>调用次数</b>而不是返回值来断：
 * ① 展开的行数 = 已答题数（未答不落行，事实 1）；
 * ② {@code qb_user_subject_stat} 一次都不碰（spec 7.1）；
 * ③ 二次提交零写入（幂等闸门 + 不重复展开）；
 * ④ 待自评的简答落 {@code is_correct=-1}（裁定一）。
 *
 * <p>本类与 {@code ExamServiceTest} 分开，是因为这里要断的是<b>写库次数与统计不变</b>，
 * 夹具与 mock 清单和读路径不同（多一个 {@code statMapper}，且每条都要 verify 次数）。
 */
class ExamSubmitTest {

    private static final long USER = 10L;

    private QbPaperMapper paperMapper;
    private QbExamAttemptMapper attemptMapper;
    private QbUserAnswerRecordMapper recordMapper;
    private QbUserWrongMapper wrongMapper;
    private QbQuestionMapper questionMapper;
    private QbQuestionOptionMapper optionMapper;
    private QbUserSubjectStatMapper statMapper;
    private ExamServiceImpl service;

    @BeforeEach
    void setUp() {
        paperMapper = mock(QbPaperMapper.class);
        attemptMapper = mock(QbExamAttemptMapper.class);
        recordMapper = mock(QbUserAnswerRecordMapper.class);
        wrongMapper = mock(QbUserWrongMapper.class);
        questionMapper = mock(QbQuestionMapper.class);
        optionMapper = mock(QbQuestionOptionMapper.class);
        statMapper = mock(QbUserSubjectStatMapper.class);
        ExamBriefs briefs = new ExamBriefs(questionMapper, optionMapper);
        // statMapper 不是任何类的依赖，它 mock 出来只为了 verifyNoInteractions：
        // 将来有人把统计 mapper 接进交卷路径，这条会红。
        service = new ExamServiceImpl(paperMapper, attemptMapper, recordMapper,
                questionMapper, optionMapper, briefs,
                new ExamFinalizer(attemptMapper, recordMapper, wrongMapper, briefs, new AnswerGrader()),
                new PracticeProperties(), new PracticeMasker());
        when(paperMapper.selectById(8L)).thenReturn(ExamFixtures.paperP1());
        stubQuestions();
    }

    private void stubQuestions() {
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.question(6L, 1, "\"C\""),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]"), ExamFixtures.question(5L, 5, "\"参考答案\"")));
        when(optionMapper.selectOptionKeysByQuestions(any())).thenReturn(List.of(
                new QuestionOptionKeyRow(1L, "A"), new QuestionOptionKeyRow(1L, "B"),
                new QuestionOptionKeyRow(6L, "A"), new QuestionOptionKeyRow(6L, "C")));
    }

    /**
     * {@code finalizeIfDraft} 之后 {@code selectById} 该读到的那一行。
     * 必须另造一个对象：直接改传进来的 {@code draft} 会让"闸门写入的快照"和"内存里的草稿"
     * 变成同一个东西，测试就再也分不清成绩单到底是读库来的还是就地算的。
     */
    private static QbExamAttempt submitted(QbExamAttempt draft) {
        QbExamAttempt done = ExamFixtures.draftAttempt(draft.getUserId(), draft.getPaperId(), null);
        done.setId(draft.getId());
        done.setStatus(2);
        done.setSubmitAt(new Date());
        done.setDurationUsedMs(60_000);
        done.setScore(new BigDecimal("17.00"));
        done.setCorrectCount(2);
        done.setWrongCount(0);
        done.setUnansweredCount(1);
        return done;
    }

    @Test
    @DisplayName("交卷：3 行展开（未答的 qid6 不落行）、错题本联动、统计一次不碰")
    void submitExpandsOnlyAnswered() {
        QbExamAttempt draft = ExamFixtures.draftAttempt(USER, 8L,
                "{\"1\":\"A\",\"2\":[\"A\",\"B\"],\"5\":\"我的简答\"}");
        when(attemptMapper.selectById(500L)).thenReturn(draft, submitted(draft));   // 先读草稿，归档后重读终态
        when(attemptMapper.finalizeIfDraft(any(QbExamAttempt.class))).thenReturn(1);

        ExamResultVO vo = service.submit(USER, 500L);

        ArgumentCaptor<QbUserAnswerRecord> cap = ArgumentCaptor.forClass(QbUserAnswerRecord.class);
        verify(recordMapper, times(3)).insert(cap.capture());
        List<QbUserAnswerRecord> rows = cap.getAllValues();
        assertEquals(1, attemptIdOf(rows), "三行都挂同一个 attempt_id（distinct 只该有 1 个）");
        assertTrue(rows.stream().allMatch(r -> r.getAnswerMode() == 2), "answer_mode=2（考试）");
        assertEquals(1, rows.stream().filter(r -> r.getIsCorrect() == -1).count(),
                "裁定一：qid5 简答落 -1 待自评");
        assertEquals(0, rows.stream().filter(r -> r.getQuestionId() == 6L).count(),
                "未答的题不落行：user_answer_json 是 json NOT NULL");
        assertNotNull(rows.get(0).getDurationMs(),
                "均摊用时必须落库——expandRecords 若把 draft 而不是 terminal 传进去，这里静默变 null");

        verify(wrongMapper, times(2)).masterIfUnmastered(eq(USER), any());
        // spec 7.1：考试作答绝不进科目统计
        verifyNoInteractions(statMapper);
        verify(attemptMapper, times(1)).finalizeIfDraft(any(QbExamAttempt.class));

        assertEquals(3, vo.getCorrectCount() + vo.getWrongCount() + vo.getPendingSelfJudgeCount());
        assertEquals(1, vo.getUnansweredCount(), "qid6 未答：四个计数加起来必须等于卷面题数");
        assertEquals(4, vo.getQuestions().size(), "成绩单要铺满整卷，未答的也在");
        // qid1 对(7.00) + qid2 多选对(10.00) + qid5 待自评(0) = 17.00，qid6 未答(0)
        assertEquals(0, new BigDecimal("17.00").compareTo(vo.getScore()));
    }

    @Test
    @DisplayName("连点两次提交：闸门回 0 行 → 零 insert、零错题本写入，成绩单从库里重建")
    void secondSubmitWritesNothing() {
        QbExamAttempt draft = ExamFixtures.draftAttempt(USER, 8L, "{\"1\":\"A\"}");
        QbExamAttempt submitted = submitted(draft);
        submitted.setScore(new BigDecimal("7.00"));
        submitted.setCorrectCount(1);
        submitted.setUnansweredCount(3);
        // 第一次读到草稿（所以走闸门），第二次读到终态（闸门回 0 之后的重读）
        when(attemptMapper.selectById(500L)).thenReturn(draft, submitted);
        when(attemptMapper.finalizeIfDraft(any(QbExamAttempt.class))).thenReturn(0);      // 闸门关上：别人先交了
        when(recordMapper.selectByAttempt(USER, 500L)).thenReturn(List.of(
                new AttemptRecordRow(1L, "\"A\"", 1, 20_000)));

        ExamResultVO vo = service.submit(USER, 500L);

        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
        verifyNoInteractions(wrongMapper);
        verifyNoInteractions(statMapper);
        assertEquals(2, vo.getStatus());
        assertEquals(1, vo.getCorrectCount(), "重建出来的计数来自库里的行，不是重新判分");
        assertEquals("\"A\"", vo.getQuestions().get(0).getUserAnswerJson());
        assertEquals(4, vo.getQuestions().size());
    }

    @Test
    @DisplayName("已经是终态的 attempt 再提交：零写入、不碰闸门，直接重建成绩单")
    void terminalAttemptSubmitIsReadOnly() {
        QbExamAttempt done = submitted(ExamFixtures.draftAttempt(USER, 8L, null));
        when(attemptMapper.selectById(500L)).thenReturn(done);
        when(recordMapper.selectByAttempt(USER, 500L)).thenReturn(List.of(
                new AttemptRecordRow(1L, "\"A\"", 1, 20_000),
                new AttemptRecordRow(2L, "[\"A\",\"B\"]", 1, 20_000)));

        ExamResultVO vo = service.submit(USER, 500L);

        verify(attemptMapper, never()).finalizeIfDraft(any(QbExamAttempt.class));
        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
        verifyNoInteractions(wrongMapper);
        assertEquals(2, vo.getStatus());
        assertEquals(2, vo.getCorrectCount());
    }

    @Test
    @DisplayName("越权 / 不存在 / 卷面下架题：40300 / 40400 / 40900，且零写入")
    void submitErrorCodes() {
        QbExamAttempt foreign = ExamFixtures.draftAttempt(999L, 8L, null);
        when(attemptMapper.selectById(501L)).thenReturn(foreign);
        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> service.submit(USER, 501L)).errorCode());

        when(attemptMapper.selectById(777L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.submit(USER, 777L)).errorCode());

        QbExamAttempt stale = ExamFixtures.draftAttempt(USER, 8L, "{\"1\":\"A\"}");
        stale.setStartedAt(null);
        when(attemptMapper.selectById(505L)).thenReturn(stale);
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.submit(USER, 505L)).errorCode(), "started_at 为 NULL 无法算用时：数据异常，不进归档");

        QbPaper withDisabled = ExamFixtures.paperP1();
        when(paperMapper.selectById(8L)).thenReturn(withDisabled);
        when(attemptMapper.selectById(502L)).thenReturn(ExamFixtures.draftAttempt(USER, 8L, "{\"1\":\"A\"}"));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.disabled(6L, 1),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]"), ExamFixtures.question(5L, 5, "\"x\"")));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.submit(USER, 502L)).errorCode());
        verify(attemptMapper, never()).finalizeIfDraft(any(QbExamAttempt.class));
        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
    }

    @Test
    @DisplayName("过期交卷：终态是 3 不是 2，且仍给成绩（§6.4）")
    void expiredSubmitArchivesAsThree() {
        QbExamAttempt stale = ExamFixtures.draftAttempt(USER, 8L, "{\"1\":\"A\"}");
        stale.setStartedAt(new Date(System.currentTimeMillis() - 70 * 60_000L));
        stale.setDeadlineAt(new Date(System.currentTimeMillis() - 120_000L));
        // 夹具的 id 恒为 500，归档后 finalizer 按 draft.getId() 重读，所以两条 stub 都挂在 500 上
        when(attemptMapper.selectById(500L)).thenReturn(stale, submitted(stale));
        when(attemptMapper.finalizeIfDraft(any(QbExamAttempt.class))).thenReturn(1);

        ExamResultVO vo = service.submit(USER, 500L);

        ArgumentCaptor<QbExamAttempt> cap = ArgumentCaptor.forClass(QbExamAttempt.class);
        verify(attemptMapper).finalizeIfDraft(cap.capture());
        assertEquals(3, cap.getValue().getStatus(), "超时提交仍判分，但状态是 3");
        assertEquals(0, new BigDecimal("7.00").compareTo(cap.getValue().getScore()));
    }

    @Test
    @DisplayName("交卷的取数次数：题目一次批量、选项键一次批量，展开三行 = 三条 INSERT，零 N+1")
    void submitGradingIsBatchNotPerQuestion() {
        QbExamAttempt draft = ExamFixtures.draftAttempt(USER, 8L,
                "{\"1\":\"A\",\"2\":[\"A\",\"B\"],\"5\":\"我的简答\"}");
        when(attemptMapper.selectById(500L)).thenReturn(draft, submitted(draft));
        when(attemptMapper.finalizeIfDraft(any(QbExamAttempt.class))).thenReturn(1);
        // setUp 里的 stubQuestions() 自己就算一次调用，清点后才能断"恰好 1 次"
        clearInvocations(questionMapper, optionMapper);

        service.submit(USER, 500L);

        verify(questionMapper, times(1)).selectBatchIds(any());
        verify(optionMapper, times(1)).selectOptionKeysByQuestions(any());
        verify(questionMapper, never()).selectById(any());
    }

    private static long attemptIdOf(List<QbUserAnswerRecord> rows) {
        return rows.stream().map(QbUserAnswerRecord::getAttemptId).distinct().count();
    }
}
