package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.vo.ExamResultVO;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 成绩单是"交卷后一次给全"（spec 5.6），也是刷新后 attempt/detail 的同一份内容，
 * 所以装配必须只有一个函数、且每个字段的来源都能一句话说清。
 */
class ExamResultsTest {

    private static QbExamAttempt attempt() {
        QbExamAttempt a = ExamFixtures.draftAttempt(10L, ExamFixtures.PAPER_ID, null);
        a.setStatus(2);
        a.setSeqNo(2);
        a.setSubmitAt(new Date(1_700_000_000_000L));
        a.setDurationUsedMs(120_000);
        return a;
    }

    @Test
    @DisplayName("成绩单逐题铺满：index/questionId/type/三个答案位/两个分值位都在")
    void resultCarriesEveryQuestion() {
        ExamGrading.PaperGrade grade = ExamGrading.grade(
                ExamFixtures.p1Structure(), ExamFixtures.allRight(), ExamFixtures.briefs(),
                new AnswerGrader());

        ExamResultVO vo = ExamResults.toVO(attempt(), ExamFixtures.paperP1(), grade);

        assertEquals(500L, vo.getAttemptId());
        assertEquals(2, vo.getSeqNo());
        assertEquals(2, vo.getStatus());
        assertEquals(8L, vo.getPaperId());
        assertEquals("2020年07月大学英语四级真题第1套（样例）", vo.getTitle());
        assertEquals(4, vo.getQuestions().size(), "未答的题也必须在（答题卡网格要铺满整卷）");
        assertEquals(1, vo.getQuestions().get(0).getIndex());
        assertEquals(0, new BigDecimal("7.00").compareTo(vo.getQuestions().get(0).getFullScore()));
        assertEquals(3, vo.getCorrectCount());
        assertEquals(1, vo.getPendingSelfJudgeCount(), "裁定一：待自评单独一位，不进 correct/wrong/unanswered");
        assertEquals(0, vo.getUnansweredCount());
        // 简答题那 6.00 判不了分，所以"全对"也只有 24.00；score 与 fullScore 之差就是待自评的分值
        assertEquals(0, new BigDecimal("24.00").compareTo(vo.getScore()), "score=" + vo.getScore());
        assertEquals(0, new BigDecimal("30.00").compareTo(vo.getFullScore()));
        assertEquals(1.0, vo.getAccuracy().doubleValue(), 1e-9);
        assertEquals(120_000, vo.getDurationUsedMs());
        assertEquals(1_700_000_000_000L, vo.getSubmitAt(), "submitAt 是 epoch 毫秒（段 3 的既有口径）");
        assertEquals(3, vo.getSections().size());
        assertEquals("Section A", vo.getSections().get(0).getName());
    }

    @Test
    @DisplayName("status=1 的草稿也能装配：判据在服务层不在这里，这里只搬字段")
    void draftAttemptStillAssembles() {
        QbExamAttempt draft = attempt();
        draft.setStatus(1);
        draft.setSubmitAt(null);
        ExamResultVO vo = ExamResults.toVO(draft, ExamFixtures.paperP1(),
                ExamGrading.grade(ExamFixtures.p1Structure(), ExamFixtures.allRight(),
                        ExamFixtures.briefs(), new AnswerGrader()));
        assertEquals(1, vo.getStatus());
        assertNull(vo.getSubmitAt(), "没交过就没有 submitAt，不能给 0");
    }

    @Test
    @DisplayName("questionCount=0 的脏数据：progressPercent 走 0.0 而不是除零")
    void zeroQuestionCountGuard() {
        assertEquals(0.0, ExamResults.progressPercentOf(0, 0), 1e-9);
        assertEquals(50.0, ExamResults.progressPercentOf(2, 4), 1e-9);
    }
}
