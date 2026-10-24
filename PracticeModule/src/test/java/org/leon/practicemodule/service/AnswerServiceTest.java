package org.leon.practicemodule.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserSubjectStatMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.pojo.AnswerSubmitReq;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;
import org.leon.practicemodule.pojo.QbUserSubjectStat;
import org.leon.practicemodule.pojo.QbUserWrong;
import org.leon.practicemodule.pojo.SelfJudgeReq;
import org.leon.practicemodule.pojo.vo.AnswerSubmitVO;
import org.leon.practicemodule.pojo.vo.SelfJudgeVO;
import org.leon.practicemodule.service.impl.AnswerServiceImpl;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 作答落库的规则锁：7.1（只累加 1/3）、7.3（掌握回退）、-1 待自评哨兵、
 * 判分格式错不落库。判分器用真实实例——本类不测判分算法，但让判分走真链路。
 */
class AnswerServiceTest {

    private static final long USER = 10L;

    private final QbQuestionMapper questionMapper = mock(QbQuestionMapper.class);
    private final QbQuestionOptionMapper optionMapper = mock(QbQuestionOptionMapper.class);
    private final QbUserAnswerRecordMapper recordMapper = mock(QbUserAnswerRecordMapper.class);
    private final QbUserWrongMapper wrongMapper = mock(QbUserWrongMapper.class);
    private final QbUserSubjectStatMapper statMapper = mock(QbUserSubjectStatMapper.class);
    private final AnswerServiceImpl service = new AnswerServiceImpl(
            questionMapper, optionMapper, recordMapper, wrongMapper, statMapper, new AnswerGrader());

    @BeforeEach
    void stubInsertBackfillsId() {
        when(recordMapper.insert(any(QbUserAnswerRecord.class))).thenAnswer(inv -> {
            ((QbUserAnswerRecord) inv.getArgument(0)).setId(123L);
            return 1;
        });
    }

    private QbQuestion question(int type, String answerJson) {
        QbQuestion q = new QbQuestion();
        q.setId(1L);
        q.setSubjectId(1L);
        q.setType(type);
        q.setStatus(1);
        q.setAnswerJson(answerJson);
        q.setAnalysis("解析");
        return q;
    }

    private AnswerSubmitReq req(Integer mode) {
        AnswerSubmitReq r = new AnswerSubmitReq();
        r.setSubjectId(1L);
        r.setQuestionId(1L);
        r.setUserAnswerJson("\"A\"");
        r.setDurationMs(3000);
        r.setMode(mode);
        return r;
    }

    private void stubStat(Integer total, Integer correct, Integer wrong) {
        QbUserSubjectStat stat = new QbUserSubjectStat();
        stat.setTotalCount(total);
        stat.setCorrectCount(correct);
        stat.setWrongCount(wrong);
        stat.setLastAnswerAt(new Date());
        when(statMapper.selectByUserAndSubject(USER, 1L)).thenReturn(stat);
    }

    @Test
    @DisplayName("答对：写记录 + 掌握置位 + 统计累加 + 响应是更新后的累计快照")
    void correctAnswerWritesRecordAndAccumulatesStat() {
        when(questionMapper.selectById(1L)).thenReturn(question(1, "\"A\""));
        when(optionMapper.selectOptionKeys(1L)).thenReturn(List.of("A", "B", "C", "D"));
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(null);
        stubStat(1, 1, 0);

        AnswerSubmitVO vo = service.submit(USER, req(1));

        assertEquals(123L, vo.getRecordId());
        assertTrue(vo.getIsCorrect());
        assertFalse(vo.isNeedSelfJudge());
        assertEquals("解析", vo.getAnalysis());
        assertEquals(0, vo.getWrongCount());
        assertFalse(vo.isWrongMastered());
        assertEquals(1, vo.getStatDelta().getTotal());
        assertEquals(1.0, vo.getStatDelta().getAccuracy());

        ArgumentCaptor<QbUserAnswerRecord> captor = ArgumentCaptor.forClass(QbUserAnswerRecord.class);
        verify(recordMapper).insert(captor.capture());
        QbUserAnswerRecord written = captor.getValue();
        assertEquals(USER, written.getUserId());
        assertEquals(1L, written.getSubjectId());
        assertEquals(1L, written.getQuestionId());
        assertEquals(1, written.getIsCorrect());
        assertEquals(1, written.getAnswerMode());
        assertEquals(3000, written.getDurationMs());

        verify(wrongMapper).masterIfUnmastered(USER, 1L);
        verify(wrongMapper, never()).upsertWrong(anyLong(), anyLong(), anyLong());
        verify(statMapper).upsertAccumulate(USER, 1L, 1, 0);
    }

    @Test
    @DisplayName("答错：错题本 upsert，统计进错列")
    void wrongAnswerIncrementsWrongBook() {
        when(questionMapper.selectById(1L)).thenReturn(question(1, "\"A\""));
        when(optionMapper.selectOptionKeys(1L)).thenReturn(List.of("A", "B", "C", "D"));
        QbUserWrong row = new QbUserWrong();
        row.setWrongCount(1);
        row.setStatus(1);
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(row);
        stubStat(1, 0, 1);
        AnswerSubmitReq r = req(1);
        r.setUserAnswerJson("\"B\"");

        AnswerSubmitVO vo = service.submit(USER, r);

        assertFalse(vo.getIsCorrect());
        assertEquals(1, vo.getWrongCount());
        assertFalse(vo.isWrongMastered());
        assertEquals(1, vo.getStatDelta().getWrong());
        verify(wrongMapper).upsertWrong(USER, 1L, 1L);
        verify(wrongMapper, never()).masterIfUnmastered(anyLong(), anyLong());
        verify(statMapper).upsertAccumulate(USER, 1L, 0, 1);
    }

    @Test
    @DisplayName("答对未掌握错题 -> 置已掌握并在响应里体现")
    void correctAnswerOnUnmasteredWrongRowMastersIt() {
        when(questionMapper.selectById(1L)).thenReturn(question(1, "\"A\""));
        when(optionMapper.selectOptionKeys(1L)).thenReturn(List.of("A", "B", "C", "D"));
        QbUserWrong row = new QbUserWrong();
        row.setWrongCount(2);
        row.setStatus(2);
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(row);
        stubStat(3, 2, 1);

        AnswerSubmitVO vo = service.submit(USER, req(1));

        assertEquals(2, vo.getWrongCount());
        assertTrue(vo.isWrongMastered());
        verify(wrongMapper).masterIfUnmastered(USER, 1L);
    }

    @Test
    @DisplayName("简答：写 -1 待自评哨兵，不碰错题本与统计")
    void shortAnswerWritesPendingSentinelAndSkipsLinkage() {
        when(questionMapper.selectById(1L)).thenReturn(question(5, "\"参考答案：要点一\""));
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(null);
        when(statMapper.selectByUserAndSubject(USER, 1L)).thenReturn(null);

        AnswerSubmitVO vo = service.submit(USER, req(1));

        assertTrue(vo.isNeedSelfJudge());
        assertNull(vo.getIsCorrect(), "待自评不能给前端假的 false");
        assertEquals(0, vo.getStatDelta().getTotal());

        ArgumentCaptor<QbUserAnswerRecord> captor = ArgumentCaptor.forClass(QbUserAnswerRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(-1, captor.getValue().getIsCorrect(), "待自评哨兵必须是 -1，不是 0");

        verify(wrongMapper, never()).upsertWrong(anyLong(), anyLong(), anyLong());
        verify(wrongMapper, never()).masterIfUnmastered(anyLong(), anyLong());
        verify(statMapper, never()).upsertAccumulate(anyLong(), anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("mode=3 智能练习同样累加统计（spec 7.1 的 IN (1,3)）")
    void smartPracticeModeAlsoAccumulatesStat() {
        when(questionMapper.selectById(1L)).thenReturn(question(1, "\"A\""));
        when(optionMapper.selectOptionKeys(1L)).thenReturn(List.of("A", "B", "C", "D"));
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(null);
        stubStat(1, 1, 0);

        service.submit(USER, req(3));

        verify(statMapper).upsertAccumulate(USER, 1L, 1, 0);
    }

    @Test
    @DisplayName("题目不存在 40400，不写任何行")
    void missingQuestionIs40400() {
        when(questionMapper.selectById(999L)).thenReturn(null);
        AnswerSubmitReq r = req(1);
        r.setQuestionId(999L);

        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, r));
        assertEquals(PracticeErrorCode.NOT_FOUND, e.errorCode());
        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
    }

    @Test
    @DisplayName("题目已下架 40900")
    void disabledQuestionIs40900() {
        QbQuestion q = question(1, "\"A\"");
        q.setStatus(0);
        when(questionMapper.selectById(1L)).thenReturn(q);

        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, req(1)));
        assertEquals(PracticeErrorCode.CONFLICT, e.errorCode());
        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
    }

    @Test
    @DisplayName("题目不属于该科目 40000")
    void subjectMismatchIs40000() {
        QbQuestion q = question(1, "\"A\"");
        q.setSubjectId(2L);
        when(questionMapper.selectById(1L)).thenReturn(q);

        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, req(1)));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    @Test
    @DisplayName("mode=2 是考试，答题接口拒绝（考试不吃科目统计，spec 7.1）")
    void examModeRejectedWith40000() {
        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, req(2)));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        verify(questionMapper, never()).selectById(any());
        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
        verifyNoInteractions(wrongMapper, statMapper);
    }

    @Test
    @DisplayName("空答案 40000，不查库")
    void blankAnswerRejectedWith40000() {
        AnswerSubmitReq r = req(1);
        r.setUserAnswerJson("   ");
        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, r));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        verifyNoInteractions(questionMapper);
    }

    @Test
    @DisplayName("负用时 40000")
    void negativeDurationRejectedWith40000() {
        AnswerSubmitReq r = req(1);
        r.setDurationMs(-1);
        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, r));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    private QbUserAnswerRecord pendingRecord() {
        QbUserAnswerRecord record = new QbUserAnswerRecord();
        record.setId(55L);
        record.setUserId(USER);
        record.setSubjectId(1L);
        record.setQuestionId(5L);
        record.setIsCorrect(-1);
        record.setAnswerMode(1);
        return record;
    }

    private SelfJudgeReq judgeReq(boolean correct) {
        SelfJudgeReq r = new SelfJudgeReq();
        r.setSubjectId(1L);
        r.setQuestionId(5L);
        r.setRecordId(55L);
        r.setIsCorrect(correct);
        return r;
    }

    @Test
    @DisplayName("自评成功：条件更新生效后统计与错题本才联动")
    void selfJudgeSuccessAccumulatesStat() {
        when(recordMapper.selectById(55L)).thenReturn(pendingRecord());
        when(questionMapper.selectById(5L)).thenReturn(question(5, "\"参考答案\""));
        when(recordMapper.judgeIfPending(55L, USER, 1)).thenReturn(1);
        when(wrongMapper.selectByUserAndQuestion(USER, 5L)).thenReturn(null);
        stubStat(1, 1, 0);

        SelfJudgeVO vo = service.selfJudge(USER, judgeReq(true));

        assertEquals(1, vo.getStatDelta().getTotal());
        verify(statMapper).upsertAccumulate(USER, 1L, 1, 0);
        verify(wrongMapper).masterIfUnmastered(USER, 5L);
        verify(wrongMapper, never()).upsertWrong(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("自评第二次：条件更新 0 行 -> 40900，统计一次都不重复累加")
    void secondSelfJudgeIsConflict40900() {
        when(recordMapper.selectById(55L)).thenReturn(pendingRecord());
        when(questionMapper.selectById(5L)).thenReturn(question(5, "\"参考答案\""));
        when(recordMapper.judgeIfPending(55L, USER, 1)).thenReturn(0);

        PracticeException e = assertThrows(PracticeException.class,
                () -> service.selfJudge(USER, judgeReq(true)));
        assertEquals(PracticeErrorCode.CONFLICT, e.errorCode());
        verify(statMapper, never()).upsertAccumulate(anyLong(), anyLong(), anyInt(), anyInt());
        verify(wrongMapper, never()).upsertWrong(anyLong(), anyLong(), anyLong());
        verify(wrongMapper, never()).masterIfUnmastered(anyLong(), anyLong());
    }

    @Test
    @DisplayName("自评判错：错题本按答错规则 upsert")
    void selfJudgeWrongUpsertsWrongBook() {
        when(recordMapper.selectById(55L)).thenReturn(pendingRecord());
        when(questionMapper.selectById(5L)).thenReturn(question(5, "\"参考答案\""));
        when(recordMapper.judgeIfPending(55L, USER, 0)).thenReturn(1);
        QbUserWrong row = new QbUserWrong();
        row.setWrongCount(1);
        row.setStatus(1);
        when(wrongMapper.selectByUserAndQuestion(USER, 5L)).thenReturn(row);
        stubStat(1, 0, 1);

        SelfJudgeVO vo = service.selfJudge(USER, judgeReq(false));

        assertEquals(1, vo.getStatDelta().getWrong());
        verify(wrongMapper).upsertWrong(USER, 1L, 5L);
        verify(statMapper).upsertAccumulate(USER, 1L, 0, 1);
    }

    @Test
    @DisplayName("自评别人的记录 40300，且不执行条件更新")
    void selfJudgeOthersRecordIs40300() {
        QbUserAnswerRecord record = pendingRecord();
        record.setUserId(99L);
        when(recordMapper.selectById(55L)).thenReturn(record);

        PracticeException e = assertThrows(PracticeException.class,
                () -> service.selfJudge(USER, judgeReq(true)));
        assertEquals(PracticeErrorCode.FORBIDDEN, e.errorCode());
        verify(recordMapper, never()).judgeIfPending(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("非简答题自评 40000")
    void selfJudgeNonShortAnswerIs40000() {
        when(recordMapper.selectById(55L)).thenReturn(pendingRecord());
        when(questionMapper.selectById(5L)).thenReturn(question(1, "\"A\""));

        PracticeException e = assertThrows(PracticeException.class,
                () -> service.selfJudge(USER, judgeReq(true)));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        verify(recordMapper, never()).judgeIfPending(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("记录不存在 40400")
    void selfJudgeMissingRecordIs40400() {
        when(recordMapper.selectById(55L)).thenReturn(null);

        PracticeException e = assertThrows(PracticeException.class,
                () -> service.selfJudge(USER, judgeReq(true)));
        assertEquals(PracticeErrorCode.NOT_FOUND, e.errorCode());
    }

    @Test
    @DisplayName("自评参数缺失 40000，不查库")
    void selfJudgeMissingParamIs40000() {
        SelfJudgeReq r = judgeReq(true);
        r.setRecordId(null);
        PracticeException e = assertThrows(PracticeException.class, () -> service.selfJudge(USER, r));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        verifyNoInteractions(questionMapper);
    }

    @Test
    @DisplayName("多值 JSON（首值合法带尾随）：40000 且不写库——曾经落到 json 列报 code=0")
    void multiValueAnswerWritesNothing() {
        when(questionMapper.selectById(1L)).thenReturn(question(1, "\"A\""));
        when(optionMapper.selectOptionKeys(1L)).thenReturn(List.of("A", "B", "C", "D"));
        AnswerSubmitReq r = req(1);
        r.setUserAnswerJson("\"A\" \"B\"");

        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, r));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
        verifyNoInteractions(wrongMapper, statMapper);
    }

    @Test
    @DisplayName("选项集校验真的接上了：集外字母 40000 且不落库")
    void outOfSetLetterIsRejectedBeforeInsert() {
        when(questionMapper.selectById(1L)).thenReturn(question(1, "\"A\""));
        when(optionMapper.selectOptionKeys(1L)).thenReturn(List.of("A", "B"));
        AnswerSubmitReq r = req(1);
        r.setUserAnswerJson("\"D\"");

        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, r));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
    }

    @Test
    @DisplayName("非选择题不查选项表")
    void nonChoiceTypeNeverLoadsOptions() {
        when(questionMapper.selectById(1L)).thenReturn(question(3, "true"));
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(null);
        stubStat(1, 1, 0);
        AnswerSubmitReq r = req(1);
        r.setUserAnswerJson("true");

        service.submit(USER, r);

        verifyNoInteractions(optionMapper);
    }

    @Test
    @DisplayName("考试记录（mode=2）不支持自评——7.1 的口径不靠段 5 自觉")
    void selfJudgeOnExamRecordIs40000() {
        QbUserAnswerRecord record = pendingRecord();
        record.setAnswerMode(2);
        when(recordMapper.selectById(55L)).thenReturn(record);
        when(questionMapper.selectById(5L)).thenReturn(question(5, "\"参考答案\""));

        PracticeException e = assertThrows(PracticeException.class,
                () -> service.selfJudge(USER, judgeReq(true)));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        verify(recordMapper, never()).judgeIfPending(anyLong(), anyLong(), anyInt());
        verifyNoInteractions(statMapper);
    }

    @Test
    @DisplayName("答案格式与题型不符：判分器 40000 直接上抛，一行不写")
    void illegalAnswerFormatWritesNothing() {
        when(questionMapper.selectById(1L)).thenReturn(question(2, "[\"A\",\"C\"]"));
        // 多选传了字符串 "A"
        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, req(1)));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
        verifyNoInteractions(wrongMapper, statMapper);
    }
}