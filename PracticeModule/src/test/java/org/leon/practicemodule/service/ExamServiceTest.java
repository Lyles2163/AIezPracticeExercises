package org.leon.practicemodule.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.config.PracticeProperties;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbExamAttemptMapper;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.mapper.QuestionOptionKeyRow;
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.vo.ExamDraftVO;
import org.leon.practicemodule.pojo.vo.ExamQuestionVO;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;
import org.leon.practicemodule.pojo.vo.ExamStartVO;
import org.leon.practicemodule.service.impl.ExamServiceImpl;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * start 的七个分支：无草稿新建、复用草稿、forceNew 归档再新建、过期草稿归档再新建、撞唯一键重读、
 * 四道可见性门、卷面坏了不建 attempt。
 *
 * <p>每个分支都要有"另一条路径没被走到"的反证（verify 的 times/never），
 * 否则"复用草稿"其实每次都新建、测试也照样绿。
 */
class ExamServiceTest {

    private static final long USER = 10L;
    private static final long PAPER = 8L;

    private QbPaperMapper paperMapper;
    private QbExamAttemptMapper attemptMapper;
    private QbUserAnswerRecordMapper recordMapper;
    private QbQuestionMapper questionMapper;
    private QbQuestionOptionMapper optionMapper;
    private QbUserWrongMapper wrongMapper;
    private ExamBriefs briefs;
    private ExamFinalizer finalizer;
    private ExamServiceImpl service;

    @BeforeEach
    void setUp() {
        paperMapper = mock(QbPaperMapper.class);
        attemptMapper = mock(QbExamAttemptMapper.class);
        recordMapper = mock(QbUserAnswerRecordMapper.class);
        questionMapper = mock(QbQuestionMapper.class);
        optionMapper = mock(QbQuestionOptionMapper.class);
        wrongMapper = mock(QbUserWrongMapper.class);
        briefs = new ExamBriefs(questionMapper, optionMapper);
        finalizer = new ExamFinalizer(attemptMapper, recordMapper, wrongMapper, briefs, new AnswerGrader());
        service = new ExamServiceImpl(paperMapper, attemptMapper, recordMapper,
                questionMapper, optionMapper, briefs, finalizer,
                new PracticeProperties(), new PracticeMasker());
        when(paperMapper.selectById(PAPER)).thenReturn(ExamFixtures.paperP1());
        stubQuestions();
    }

    /** 判分原料：qid 1/6/2/5 的 type 与 answer_json、type=1 的选项键 */
    private void stubQuestions() {
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.question(6L, 1, "\"C\""),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]"), ExamFixtures.question(5L, 5, "\"参考答案\"")));
        when(optionMapper.selectOptionKeysByQuestions(any())).thenReturn(List.of(
                new QuestionOptionKeyRow(1L, "A"), new QuestionOptionKeyRow(1L, "B"),
                new QuestionOptionKeyRow(6L, "A"), new QuestionOptionKeyRow(6L, "C")));
    }

    /** 本类所有用例共用的一条草稿：attemptId 固定 500，只有 seq_no 与草稿内容随用例变 */
    private static QbExamAttempt draft(int seqNo, String answeredJson) {
        QbExamAttempt attempt = ExamFixtures.draftAttempt(USER, PAPER, answeredJson);
        attempt.setSeqNo(seqNo);
        return attempt;
    }

    /** 归档/提交之后库里那条行：status 由调用方给（2 已提交 / 3 超时归档），id 与草稿一致 */
    private static QbExamAttempt terminal(QbExamAttempt from, int status) {
        QbExamAttempt attempt = ExamFixtures.draftAttempt(from.getUserId(), from.getPaperId(), null);
        attempt.setStatus(status);
        attempt.setSubmitAt(new Date());
        attempt.setDurationUsedMs(60_000);
        attempt.setScore(new BigDecimal("7.00"));
        attempt.setCorrectCount(1);
        attempt.setWrongCount(0);
        attempt.setUnansweredCount(3);
        return attempt;
    }

    /** exam/question 那道题的原料：type=1 的 qid 1 与它的四个选项 */
    private void stubQuestionOneForRead() {
        QbQuestionOption a = new QbQuestionOption();
        a.setOptionKey("A");
        a.setContent("选项甲");
        a.setIsCorrect(1);                       // 实体里带真值，遮罩器必须不复制它
        QbQuestionOption b = new QbQuestionOption();
        b.setOptionKey("B");
        b.setContent("选项乙");
        b.setIsCorrect(0);
        when(questionMapper.selectById(1L)).thenReturn(ExamFixtures.question(1L, 1, "\"A\""));
        when(optionMapper.selectDisplayOptions(1L)).thenReturn(List.of(a, b));
    }

    @Test
    @DisplayName("取题：脱敏单题 + 卷面 index 与 score + 这道题的草稿答案")
    void questionIsMaskedAndCarriesScore() {
        stubQuestionOneForRead();
        QbExamAttempt existing = draft(1, "{\"1\":\"A\"}");
        when(attemptMapper.selectById(500L)).thenReturn(existing);

        ExamQuestionVO vo = service.question(USER, 500L, 1L);

        assertEquals(1, vo.getIndex());
        assertEquals(0, new BigDecimal("7.00").compareTo(vo.getScore()));
        assertEquals("单选题", vo.getQuestion().getTypeName(), "题型名由 PracticeMasker 给（既有口径）");
        assertEquals("\"A\"", vo.getUserAnswerJson(), "草稿答案回给用户自己的数据，允许外泄");
        assertEquals(2, vo.getQuestion().getOptions().size());
        assertEquals("选项甲", vo.getQuestion().getOptions().get(0).getContent());
        // 这里**不写** "assertNull(option.getIsCorrect())"：QuestionVO.OptionVO 根本没有这个字段
        // （段 2 定死的类型），写了编译不过，而"选项不带真值"由响应字节守卫与 PracticeMaskerTest 各钉一遍。
    }

    @Test
    @DisplayName("越权：B 用户拿 A 的 attemptId → 40300，且不查题目")
    void otherUsersAttemptIsForbidden() {
        QbExamAttempt mine = draft(1, null);
        mine.setUserId(999L);
        when(attemptMapper.selectById(501L)).thenReturn(mine);

        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> service.question(USER, 501L, 1L)).errorCode());
        verifyNoInteractions(questionMapper);
    }

    @Test
    @DisplayName("题不在卷面上 → 40000（不是 40400：题存在，但不属于这张卷子）")
    void questionNotOnPaper() {
        QbExamAttempt existing = draft(1, null);
        when(attemptMapper.selectById(500L)).thenReturn(existing);
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.question(USER, 500L, 99L)).errorCode());
    }

    @Test
    @DisplayName("已提交的 attempt 不能再取题 → 41002")
    void terminalAttemptRejectsQuestion() {
        QbExamAttempt done = draft(1, null);
        done.setStatus(2);
        when(attemptMapper.selectById(502L)).thenReturn(done);
        assertEquals(PracticeErrorCode.EXAM_SUBMITTED, assertThrows(PracticeException.class,
                () -> service.question(USER, 502L, 1L)).errorCode());
    }

    @Test
    @DisplayName("过期但仍是草稿：读题不拒（到点只禁写草稿，读无副作用）")
    void expiredDraftCanStillRead() {
        stubQuestionOneForRead();
        QbExamAttempt stale = draft(1, null);
        stale.setDeadlineAt(new Date(System.currentTimeMillis() - 10_000L));
        when(attemptMapper.selectById(503L)).thenReturn(stale);

        ExamQuestionVO vo = service.question(USER, 503L, 1L);

        assertEquals(1, vo.getIndex(), "过期了也该能翻题目看已存的草稿，否则用户连自己要交什么都看不见");
    }

    @Test
    @DisplayName("简答/填空没有选项：不发选项查询")
    void nonChoiceTypeSkipsOptions() {
        QbExamAttempt existing = draft(1, "{\"5\":\"我的作答\"}");
        when(attemptMapper.selectById(500L)).thenReturn(existing);
        when(questionMapper.selectById(5L)).thenReturn(ExamFixtures.question(5L, 5, "\"参考答案\""));

        ExamQuestionVO vo = service.question(USER, 500L, 5L);

        assertEquals(4, vo.getIndex());
        assertEquals(0, new BigDecimal("6.00").compareTo(vo.getScore()));
        assertTrue(vo.getQuestion().getOptions().isEmpty());
        verify(optionMapper, never()).selectDisplayOptions(any());
        assertEquals("\"我的作答\"", vo.getUserAnswerJson());
    }

    @Test
    @DisplayName("attempt 不存在 → 40400；questionId 缺失 → 40000")
    void notFoundAndBadParam() {
        when(attemptMapper.selectById(777L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.question(USER, 777L, 1L)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.question(USER, 500L, null)).errorCode());
    }

    @Test
    @DisplayName("没有草稿：新建 seq_no=1，deadline = now + 65 分钟，answeredMap 空")
    void createsFirstAttempt() {
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER)).thenReturn(null);
        when(attemptMapper.selectMaxSeqNo(USER, PAPER)).thenReturn(0);

        ExamStartVO vo = service.start(USER, PAPER, false);

        assertEquals(1, vo.getSeqNo());
        assertEquals(0, vo.getAnsweredCount());
        assertEquals(4, vo.getQuestionCount());
        assertEquals(0.0, vo.getProgressPercent(), 1e-9);
        assertTrue(vo.getAnsweredMap().isEmpty());
        assertEquals(65, vo.getDurationMinutes());
        assertEquals(2, vo.getStructure().size(), "卷面骨架两层 part 都要在（答题卡与 Part 标题用）");
        assertEquals(25L, vo.getStructure().get(0).getMinutes());
        assertNull(vo.getStructure().get(0).getSections().get(0).getOriginalText(),
                "考试中不许看见听力原文（裁定二在 start 的响应里同样成立）");
        assertEquals(65 * 60_000L, vo.getDeadlineAt() - vo.getServerNow(),
                "deadline 必须由服务端算，前端只信 deadlineAt-serverNow");
        assertTrue(vo.getRemainingMs() > 0);
        verify(attemptMapper, times(1)).insert(any(QbExamAttempt.class));
    }

    @Test
    @DisplayName("已有未过期草稿：复用同一个 attemptId，一次都不 insert（9.3 的刷新不丢草稿）")
    void reusesActiveDraft() {
        QbExamAttempt existing = draft(1, "{\"1\":\"A\",\"2\":\"[\\\"A\\\",\\\"B\\\"]\"}");
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER)).thenReturn(existing);

        ExamStartVO vo = service.start(USER, PAPER, false);

        assertEquals(existing.getId(), vo.getAttemptId());
        assertEquals(1, vo.getSeqNo());
        assertEquals(2, vo.getAnsweredCount());
        assertEquals(50.0, vo.getProgressPercent(), 1e-9);
        assertEquals("\"A\"", vo.getAnsweredMap().get("1"),
                "key 是十进制题号字符串（裁定五），前端按 questionId 查");
        verify(attemptMapper, never()).insert(any(QbExamAttempt.class));
        // 复用草稿这条路径绝不能把任何行推成终态，否则"刷新页面"会悄悄交卷
        verify(attemptMapper, never()).finalizeIfDraft(any());
    }

    @Test
    @DisplayName("过期草稿（非 forceNew）：先归档判分再新建，seq_no 递增")
    void expiredDraftIsArchivedThenNewOneCreated() {
        QbExamAttempt stale = draft(1, "{\"1\":\"A\"}");
        stale.setDeadlineAt(new Date(System.currentTimeMillis() - 120_000L));   // 过期 2 分钟 > grace 60
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER)).thenReturn(stale);
        when(attemptMapper.selectMaxSeqNo(USER, PAPER)).thenReturn(1);
        when(attemptMapper.finalizeIfDraft(any())).thenReturn(1);
        when(attemptMapper.selectById(stale.getId())).thenReturn(terminal(stale, 3));

        ExamStartVO vo = service.start(USER, PAPER, false);

        assertEquals(2, vo.getSeqNo(), "归档旧草稿后新建，seq_no 必须是 2");
        assertNotEquals(stale.getId(), vo.getAttemptId());
        verify(attemptMapper, times(1)).finalizeIfDraft(any());
        verify(recordMapper, times(1)).insert(any(QbUserAnswerRecord.class));   // 归档即判分：qid1 那道已答的题展开成 1 行（裁定三）
        verify(attemptMapper, times(1)).insert(any(QbExamAttempt.class));
    }

    @Test
    @DisplayName("forceNew：未过期的草稿也归档为 3，然后新建")
    void forceNewArchivesEvenWhenActive() {
        QbExamAttempt existing = draft(1, "{\"1\":\"A\"}");
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER)).thenReturn(existing);
        when(attemptMapper.selectMaxSeqNo(USER, PAPER)).thenReturn(1);
        when(attemptMapper.finalizeIfDraft(any())).thenReturn(1);
        when(attemptMapper.selectById(existing.getId())).thenReturn(terminal(existing, 3));

        ExamStartVO vo = service.start(USER, PAPER, true);

        assertEquals(2, vo.getSeqNo());
        verify(attemptMapper, times(1)).finalizeIfDraft(any());
    }

    @Test
    @DisplayName("撞唯一键（并发双击）：重读草稿并返回它，不报\"服务器繁忙\"（裁定二）")
    void duplicateKeyReturnsTheOtherRequestDraft() {
        QbExamAttempt other = draft(1, null);
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER))
                .thenReturn(null)      // 第一次查：没有
                .thenReturn(other);    // 撞键后重读：别人建好了
        when(attemptMapper.selectMaxSeqNo(USER, PAPER)).thenReturn(0);
        doThrow(new DuplicateKeyException("uk_attempt_user_paper_seq")).when(attemptMapper).insert(any(QbExamAttempt.class));

        ExamStartVO vo = service.start(USER, PAPER, false);

        assertEquals(other.getId(), vo.getAttemptId(), "第二次点击必须拿到第一次建的那次，而不是报错");
    }

    @Test
    @DisplayName("下架卷 40900、不存在 40400、他人私有卷 40300、paperId null 40000——四道门来自 PaperLoad")
    void guardsAreSharedWithPaperDetail() {
        when(paperMapper.selectById(999L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.start(USER, 999L, false)).errorCode());

        QbPaper disabled = ExamFixtures.paperP1();
        disabled.setStatus(0);
        when(paperMapper.selectById(11L)).thenReturn(disabled);
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.start(USER, 11L, false)).errorCode());

        QbPaper foreign = ExamFixtures.paperP1();
        foreign.setOwnerId(999L);
        when(paperMapper.selectById(12L)).thenReturn(foreign);
        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> service.start(USER, 12L, false)).errorCode());

        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.start(USER, null, false)).errorCode());
        verify(attemptMapper, never()).insert(any(QbExamAttempt.class));
    }

    @Test
    @DisplayName("卷面结构坏了（人手改过 structure_json）：40000 且不建 attempt")
    void corruptStructureRejectsBeforeInsert() {
        QbPaper broken = ExamFixtures.paperP1();
        broken.setStructureJson("{\"parts\":[]}");
        when(paperMapper.selectById(PAPER)).thenReturn(broken);

        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.start(USER, PAPER, false)).errorCode());
        verify(attemptMapper, never()).insert(any(QbExamAttempt.class));
    }

    @Test
    @DisplayName("卷面里的题被下架：开考是 40000（校验规则 2），不是判分路径的 40900")
    void disabledQuestionBlocksStart() {
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.disabled(6L, 1),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]"), ExamFixtures.question(5L, 5, "\"参考答案\"")));
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER)).thenReturn(null);

        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.start(USER, PAPER, false)).errorCode(),
                "开考时卷面必须完整——这道门由 PaperStructureValidator 守，错误码是 40000");
        verify(attemptMapper, never()).insert(any(QbExamAttempt.class));
    }

    @Test
    @DisplayName("撞唯一键但对方已回滚：40900，不退回去再建一次（避免二次撞键）")
    void duplicateKeyWithRolledBackRivalConflicts() {
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER)).thenReturn(null);
        when(attemptMapper.selectMaxSeqNo(USER, PAPER)).thenReturn(0);
        doThrow(new DuplicateKeyException("uk_attempt_user_paper_seq")).when(attemptMapper).insert(any(QbExamAttempt.class));

        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.start(USER, PAPER, false)).errorCode());
        verify(attemptMapper, times(1)).insert(any(QbExamAttempt.class));
    }

    @Test
    @DisplayName("过期判定的宽限期：只过期 30 秒 < grace=60，草稿照原样复用")
    void withinGraceReusesDraft() {
        QbExamAttempt justLate = draft(1, "{\"1\":\"A\"}");
        justLate.setDeadlineAt(new Date(System.currentTimeMillis() - 30_000L));
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER)).thenReturn(justLate);

        ExamStartVO vo = service.start(USER, PAPER, false);

        assertEquals(justLate.getId(), vo.getAttemptId(), "宽限期内不算过期，绝不能偷偷归档重来");
        verify(attemptMapper, never()).finalizeIfDraft(any());
        verify(attemptMapper, never()).insert(any(QbExamAttempt.class));
    }

    @Test
    @DisplayName("start 不发选项键那条 SQL：它只要卷面骨架与题目归属，不判分")
    void startDoesNotLoadOptionKeys() {
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER)).thenReturn(null);
        when(attemptMapper.selectMaxSeqNo(USER, PAPER)).thenReturn(0);

        service.start(USER, PAPER, false);

        verify(questionMapper, times(1)).selectBatchIds(any());
        verify(optionMapper, never()).selectOptionKeysByQuestions(any());
    }

    @Test
    @DisplayName("存草稿：整包覆盖 answered_json，返回已答数与进度")
    void draftOverwritesWholeMap() {
        QbExamAttempt draft = draft(1, "{\"1\":\"A\"}");
        when(attemptMapper.selectById(500L)).thenReturn(draft);
        when(attemptMapper.updateById(any(QbExamAttempt.class))).thenReturn(1);

        ExamDraftVO vo = service.draft(USER, 500L, Map.of("2", "[\"A\",\"B\"]", "5", "\"我的简答\""));

        assertEquals(2, vo.getAnsweredCount(), "整包覆盖：旧的 qid1 不在了，新 map 有 2 个 key");
        assertEquals(50.0, vo.getProgressPercent(), 1e-9);
        ArgumentCaptor<QbExamAttempt> cap = ArgumentCaptor.forClass(QbExamAttempt.class);
        verify(attemptMapper).updateById(cap.capture());
        assertEquals(500L, cap.getValue().getId());
        Map<Long, String> saved = ExamAnswers.decode(cap.getValue().getAnsweredJson());
        assertEquals(2, saved.size());
        assertEquals("[\"A\",\"B\"]", saved.get(2L));
        assertNull(saved.get(1L), "整包覆盖的语义就是\"这次没带的等于删掉\"");
    }

    @Test
    @DisplayName("卷面之外的题号 → 40000，且不写库（草稿里不许长出这张卷子没有的答案）")
    void draftRejectsQuestionsOffPaper() {
        QbExamAttempt draft = draft(1, null);
        when(attemptMapper.selectById(500L)).thenReturn(draft);
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.draft(USER, 500L, Map.of("99", "\"A\""))).errorCode());
        verify(attemptMapper, never()).updateById(any(QbExamAttempt.class));
    }

    @Test
    @DisplayName("非法 value（尾随内容 / Infinity / 空串）→ 40000，且不写库")
    void draftRejectsMysqlUnsafeValues() {
        QbExamAttempt draft = draft(1, null);
        when(attemptMapper.selectById(500L)).thenReturn(draft);
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.draft(USER, 500L, Map.of("1", "\"A\" \"B\""))).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.draft(USER, 500L, Map.of("1", "1e999"))).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.draft(USER, 500L, Map.of("1", "  "))).errorCode());
        verify(attemptMapper, never()).updateById(any(QbExamAttempt.class));
    }

    @Test
    @DisplayName("非法题号（非数字 / 0 / 负数）→ 40000，且不写库")
    void draftRejectsBadQuestionIdKeys() {
        QbExamAttempt draft = draft(1, null);
        when(attemptMapper.selectById(500L)).thenReturn(draft);
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.draft(USER, 500L, Map.of("abc", "\"A\""))).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.draft(USER, 500L, Map.of("0", "\"A\""))).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.draft(USER, 500L, Map.of("-3", "\"A\""))).errorCode());
        // "1" 与 "01" 都指向题 1：合并会静默丢掉一份答案，只能拒
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.draft(USER, 500L, Map.of("1", "\"A\"", "01", "\"B\""))).errorCode());
        verify(attemptMapper, never()).updateById(any(QbExamAttempt.class));
    }

    @Test
    @DisplayName("已提交 → 41002；越权 → 40300；不存在 → 40400")
    void draftErrorCodes() {
        QbExamAttempt submitted = draft(1, null);
        submitted.setStatus(2);
        when(attemptMapper.selectById(502L)).thenReturn(submitted);
        assertEquals(PracticeErrorCode.EXAM_SUBMITTED, assertThrows(PracticeException.class,
                () -> service.draft(USER, 502L, Map.of("1", "\"A\""))).errorCode());

        QbExamAttempt foreign = draft(1, null);
        foreign.setUserId(999L);
        when(attemptMapper.selectById(504L)).thenReturn(foreign);
        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> service.draft(USER, 504L, Map.of("1", "\"A\""))).errorCode());

        when(attemptMapper.selectById(777L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.draft(USER, 777L, Map.of("1", "\"A\""))).errorCode());
        verify(attemptMapper, never()).updateById(any(QbExamAttempt.class));
    }

    @Test
    @DisplayName("超时 → 41001，且**不写** answered_json（§6.4：draft 拒绝，submit 才收）")
    void expiredDraftIsRejected() {
        QbExamAttempt stale = draft(1, null);
        stale.setDeadlineAt(new Date(System.currentTimeMillis() - 120_000L));
        when(attemptMapper.selectById(503L)).thenReturn(stale);

        assertEquals(PracticeErrorCode.EXAM_EXPIRED, assertThrows(PracticeException.class,
                () -> service.draft(USER, 503L, Map.of("1", "\"A\""))).errorCode());
        verify(attemptMapper, never()).updateById(any(QbExamAttempt.class));
    }

    @Test
    @DisplayName("answers 为 null 当作空 map：清空草稿并写回 {}（\"删光了再保存\"也是保存）")
    void nullBecomesEmptyDraft() {
        QbExamAttempt draft = draft(1, "{\"1\":\"A\"}");
        when(attemptMapper.selectById(500L)).thenReturn(draft);
        when(attemptMapper.updateById(any(QbExamAttempt.class))).thenReturn(1);

        ExamDraftVO vo = service.draft(USER, 500L, null);

        assertEquals(0, vo.getAnsweredCount());
        assertEquals(0.0, vo.getProgressPercent(), 1e-9);
        ArgumentCaptor<QbExamAttempt> cap = ArgumentCaptor.forClass(QbExamAttempt.class);
        verify(attemptMapper).updateById(cap.capture());
        assertEquals("{}", cap.getValue().getAnsweredJson());
    }

    @Test
    @DisplayName("存草稿只碰 answered_json：patch 里不能出现 status / deadline_at")
    void draftPatchCarriesOnlyAnsweredJson() {
        QbExamAttempt draft = draft(1, null);
        when(attemptMapper.selectById(500L)).thenReturn(draft);
        when(attemptMapper.updateById(any(QbExamAttempt.class))).thenReturn(1);

        service.draft(USER, 500L, Map.of("1", "\"A\""));

        ArgumentCaptor<QbExamAttempt> cap = ArgumentCaptor.forClass(QbExamAttempt.class);
        verify(attemptMapper).updateById(cap.capture());
        QbExamAttempt patch = cap.getValue();
        assertEquals(500L, patch.getId());
        assertNotNull(patch.getAnsweredJson());
        assertNull(patch.getStatus(), "patch 带上 status，MP 的非空字段更新就会把状态改掉");
        assertNull(patch.getDeadlineAt());
        assertNull(patch.getScore());
    }
}
