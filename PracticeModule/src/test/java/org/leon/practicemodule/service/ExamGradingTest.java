package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.AttemptRecordRow;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 判分内核的两条出口（grade / fromStored）必须给出**同一个形状**——成绩单只有一条装配路径（裁定四）。
 * 所以每个形状断言都写两遍，一遍真判分、一遍从存好的行重建。
 */
class ExamGradingTest {

    private static final AnswerGrader GRADER = new AnswerGrader();

    /** 样例卷 P1 的结构：I(14.00,25min)/A[1(7.00),6(7.00)] + II(16.00,40min)/A[2(10.00)] B[5(6.00)] */
    private static PaperStructure p1() {
        return PaperStructureParser.parse("""
                {"parts":[
                  {"roman":"I","name":"Listening Comprehension","cardLabel":"听力部分","score":14.00,"minutes":25,
                   "sections":[{"label":"Section A","subTitle":"短新闻","direction":"d","audioUrl":"a.mp3",
                     "originalText":"原文","questions":[{"index":1,"questionId":1,"score":7.00},
                                                         {"index":2,"questionId":6,"score":7.00}]}]},
                  {"roman":"II","name":"Reading and Writing","cardLabel":"阅读部分","score":16.00,"minutes":40,
                   "sections":[{"label":"Section A","subTitle":"多项选择","direction":null,"audioUrl":null,
                     "originalText":null,"questions":[{"index":3,"questionId":2,"score":10.00}]},
                    {"label":"Section B","subTitle":"简答题","direction":null,"audioUrl":null,
                     "originalText":null,"questions":[{"index":4,"questionId":5,"score":6.00}]}]}]}
                """);
    }

    /** qid1 单选答案 A、qid6 单选答案 C、qid2 多选 AB、qid5 简答（不判分） */
    private static Map<Long, ExamGrading.Brief> briefs() {
        Map<Long, ExamGrading.Brief> m = new LinkedHashMap<>();
        m.put(1L, new ExamGrading.Brief(1L, 1, "\"A\"", "A 对", List.of("A", "B", "C", "D")));
        m.put(6L, new ExamGrading.Brief(6L, 1, "\"C\"", "C 对", List.of("A", "B", "C")));
        m.put(2L, new ExamGrading.Brief(2L, 2, "[\"A\",\"B\"]", "AB 对", List.of()));
        m.put(5L, new ExamGrading.Brief(5L, 5, "\"参考答案\"", "简答解析", List.of()));
        return Map.copyOf(m);
    }

    @Test
    @DisplayName("全对：score=24.00（简答那 6 分判不了就没有）、correct=3、pending=1")
    void allAnswered() {
        Map<Long, String> answers = new LinkedHashMap<>();
        answers.put(1L, "\"A\"");
        answers.put(6L, "\"C\"");
        answers.put(2L, "[\"A\",\"B\"]");
        answers.put(5L, "\"我的简答\"");

        ExamGrading.PaperGrade g = ExamGrading.grade(p1(), answers, briefs(), GRADER);

        // 7.00 + 7.00 + 10.00 = 24.00：裁定一说明简答题 scoreGot=0，
        // 所以"全对"的卷子里那道简答一分拿不到——score 与 fullScore 差的就是它 6.00
        assertEquals(0, new BigDecimal("24.00").compareTo(g.score()), "score=" + g.score());
        assertEquals(0, new BigDecimal("30.00").compareTo(g.fullScore()));
        assertEquals(3, g.correctCount(), "type=5 不进 correct_count（裁定一）");
        assertEquals(0, g.wrongCount());
        assertEquals(0, g.unansweredCount());
        assertEquals(1, g.pendingCount());
        assertEquals(1.0, g.accuracy().doubleValue(), 1e-9,
                "accuracy=correct/(correct+wrong)=3/3（裁定六：分母不含未答与待自评）");
        assertEquals(4, g.items().size());
        assertEquals(List.of(1, 2, 3, 4), g.items().stream().map(ExamGrading.Item::index).toList());
        assertNull(g.items().get(3).correct(), "待自评题的 isCorrect 必须是 null 而不是 false");
        assertTrue(g.items().get(3).needSelfJudge());
        assertEquals(0, new BigDecimal("6.00").compareTo(g.items().get(3).fullScore()),
                "fullScore 仍是这道题的卷面分值（判不了也不抹掉）");
        assertEquals(0, BigDecimal.ZERO.compareTo(g.items().get(3).scoreGot()),
                "待自评题不计分，scoreGot=0");
    }

    @Test
    @DisplayName("三级汇总：section 与卷级的 scoreGot 只对已判分的题求和")
    void sectionRollup() {
        Map<Long, String> answers = Map.of(1L, "\"B\"", 2L, "[\"A\",\"B\"]", 5L, "\"x\"");
        ExamGrading.PaperGrade g = ExamGrading.grade(p1(), answers, briefs(), GRADER);

        assertEquals(3, g.sections().size(), "三个 section：I/A、II/A、II/B");
        // I/A：qid1 错(0) + qid6 未答 → scoreGot 0，fullScore 14.00，unanswered 1
        ExamGrading.SectionResult listening = g.sections().get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(listening.scoreGot()));
        assertEquals(0, new BigDecimal("14.00").compareTo(listening.fullScore()));
        assertEquals(1, listening.unansweredCount());
        assertEquals(0, listening.correctCount());
        // II/A：多选对 → 10.00
        assertEquals(0, new BigDecimal("10.00").compareTo(g.sections().get(1).scoreGot()));
        // II/B：简答待自评 → 0 分、pending 1、未答 0
        assertEquals(0, BigDecimal.ZERO.compareTo(g.sections().get(2).scoreGot()));
        assertEquals(1, g.sections().get(2).pendingCount());
        assertEquals(0, g.sections().get(2).unansweredCount());

        assertEquals(1, g.correctCount());
        assertEquals(1, g.wrongCount());
        assertEquals(1, g.unansweredCount());
        assertEquals(1, g.pendingCount());
        assertEquals(0, new BigDecimal("10.00").compareTo(g.score()));
        assertEquals(0.50, g.accuracy().doubleValue(), 1e-9, "1/(1+1)=0.50，未答与待自评都不进分母");
    }

    @Test
    @DisplayName("一道没答：score 0、unanswered 4、accuracy 0.0（分母为 0 的保护）")
    void nothingAnswered() {
        ExamGrading.PaperGrade g = ExamGrading.grade(p1(), Map.of(), briefs(), GRADER);
        assertEquals(0, BigDecimal.ZERO.compareTo(g.score()));
        assertEquals(4, g.unansweredCount());
        assertEquals(0, g.correctCount());
        assertEquals(0, g.wrongCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(g.accuracy()),
                "分母 correct+wrong=0 → 0.0，不能 NaN 也不能除零炸");
        assertEquals(4, g.items().size(), "未答的题也要出现在成绩单的 questions 里（截图②要铺满整卷）");
        assertNull(g.items().get(0).userAnswerJson());
    }

    @Test
    @DisplayName("fromStored：按落库的 is_correct（-1/0/1）重建，结果与判分路径同形状")
    void rebuildMatchesGrade() {
        Map<Long, String> answers = new LinkedHashMap<>();
        answers.put(1L, "\"A\"");
        answers.put(6L, "\"C\"");
        answers.put(2L, "[\"A\",\"B\"]");
        answers.put(5L, "\"我的简答\"");
        ExamGrading.PaperGrade graded = ExamGrading.grade(p1(), answers, briefs(), GRADER);

        List<AttemptRecordRow> rows = List.of(
                new AttemptRecordRow(1L, "\"A\"", 1, 300),
                new AttemptRecordRow(6L, "\"C\"", 1, 300),
                new AttemptRecordRow(2L, "[\"A\",\"B\"]", 1, 300),
                new AttemptRecordRow(5L, "\"我的简答\"", -1, 300));
        ExamGrading.PaperGrade rebuilt = ExamGrading.fromStored(p1(), rows, briefs());

        assertEquals(graded.correctCount(), rebuilt.correctCount());
        assertEquals(graded.wrongCount(), rebuilt.wrongCount());
        assertEquals(graded.unansweredCount(), rebuilt.unansweredCount());
        assertEquals(graded.pendingCount(), rebuilt.pendingCount());
        assertEquals(0, graded.score().compareTo(rebuilt.score()));
        assertEquals(graded.items().size(), rebuilt.items().size());
        for (int i = 0; i < graded.items().size(); i++) {
            assertEquals(graded.items().get(i).correct(), rebuilt.items().get(i).correct());
            assertEquals(graded.items().get(i).needSelfJudge(), rebuilt.items().get(i).needSelfJudge());
        }
    }

    @Test
    @DisplayName("题目在卷面上但实体查不到（题被删了）→ 40400，不是 NPE")
    void missingBriefIsNotFound() {
        Map<Long, ExamGrading.Brief> partial = Map.of(1L,
                new ExamGrading.Brief(1L, 1, "\"A\"", "A 对", List.of("A", "B")));
        PracticeException e = assertThrows(PracticeException.class, () -> ExamGrading.grade(
                p1(), Map.of(1L, "\"A\""), partial, GRADER));
        assertEquals(PracticeErrorCode.NOT_FOUND, e.errorCode());
    }
}
