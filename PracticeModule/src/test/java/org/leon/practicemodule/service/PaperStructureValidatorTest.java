package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * spec 4.3 的四条写入校验。四条都各有一条反例（spec 9.1.7 的要求），
 * 另外钉住两处容易做错的细节：规则 3 必须 compareTo（scale 不等不是数值不等）、
 * 规则 3 的容差必须是 0。
 */
@DisplayName("structure_json 的四条写入校验")
class PaperStructureValidatorTest {

    /** Part I（25 分钟 / 14.00 分）两题各 7.00，qid 1 与 6 都属科目 1 */
    private static final String TWO_QUESTIONS = "{\"parts\":[{"
            + "\"roman\":\"I\",\"name\":\"Listening\",\"cardLabel\":\"听力\","
            + "\"score\":14.00,\"minutes\":25,\"sections\":[{"
            + "\"label\":\"A\",\"subTitle\":\"短新闻\",\"direction\":null,\"audioUrl\":null,\"originalText\":null,"
            + "\"questions\":[{\"index\":1,\"questionId\":1,\"score\":7.00},"
            + "{\"index\":2,\"questionId\":6,\"score\":7.00}]}]}]}";

    private static Map<Long, PaperStructureValidator.QuestionBrief> subjectOne() {
        Map<Long, PaperStructureValidator.QuestionBrief> m = new HashMap<>();
        m.put(1L, new PaperStructureValidator.QuestionBrief(1L, 1L, 1));
        m.put(6L, new PaperStructureValidator.QuestionBrief(6L, 1L, 1));
        return m;
    }

    private static void validate(String json, int questionCount, String totalScore,
                                 int durationMinutes,
                                 Map<Long, PaperStructureValidator.QuestionBrief> questions) {
        PaperStructureValidator.validate(PaperStructureParser.parse(json), questionCount,
                new BigDecimal(totalScore), durationMinutes, 1L, questions);
    }

    @Test
    @DisplayName("正例：四条全过")
    void validPasses() {
        assertDoesNotThrow(() -> validate(TWO_QUESTIONS, 2, "14.00", 25, subjectOne()));
    }

    @Test
    @DisplayName("规则 1 反例：index 从 2 起（题号空洞 = 答题卡出现无主格子）")
    void indexMustStartAtOne() {
        String json = TWO_QUESTIONS.replace("\"index\":1,", "\"index\":2,");
        PracticeException e = assertThrows(PracticeException.class,
                () -> validate(json, 2, "14.00", 25, subjectOne()));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        assertTrue(e.getMessage().contains("index"), e.getMessage());
    }

    @Test
    @DisplayName("规则 1 反例：index 跳成 1,3（跨 part 必须全局连续）")
    void indexMustBeContiguous() {
        String json = TWO_QUESTIONS.replace("\"index\":2,", "\"index\":3,");
        PracticeException e = assertThrows(PracticeException.class,
                () -> validate(json, 2, "14.00", 25, subjectOne()));
        assertTrue(e.getMessage().contains("连续"), e.getMessage());
    }

    @Test
    @DisplayName("规则 1 反例：index 重复 1,1（连续判据顺带把重复也拦掉）")
    void indexMustNotRepeat() {
        String json = TWO_QUESTIONS.replace("\"index\":2,", "\"index\":1,");
        assertThrows(PracticeException.class, () -> validate(json, 2, "14.00", 25, subjectOne()));
    }

    @Test
    @DisplayName("规则 1 反例：question_count 与实际题数不符（冗余列就是用来被校验的）")
    void questionCountMustMatch() {
        PracticeException e = assertThrows(PracticeException.class,
                () -> validate(TWO_QUESTIONS, 3, "14.00", 25, subjectOne()));
        assertTrue(e.getMessage().contains("题目总数"), e.getMessage());
    }

    @Test
    @DisplayName("规则 2 反例：qid 6 其实属科目 2（越科引用会让错题本与统计串科）")
    void questionMustBelongToSubject() {
        Map<Long, PaperStructureValidator.QuestionBrief> qs = subjectOne();
        qs.put(6L, new PaperStructureValidator.QuestionBrief(6L, 2L, 1));
        PracticeException e = assertThrows(PracticeException.class,
                () -> validate(TWO_QUESTIONS, 2, "14.00", 25, qs));
        assertTrue(e.getMessage().contains("科目"), e.getMessage());
    }

    @Test
    @DisplayName("规则 2 反例：题目已下架（status=0）")
    void questionMustBeEnabled() {
        Map<Long, PaperStructureValidator.QuestionBrief> qs = subjectOne();
        qs.put(1L, new PaperStructureValidator.QuestionBrief(1L, 1L, 0));
        PracticeException e = assertThrows(PracticeException.class,
                () -> validate(TWO_QUESTIONS, 2, "14.00", 25, qs));
        assertTrue(e.getMessage().contains("下架"), e.getMessage());
    }

    @Test
    @DisplayName("规则 2 反例：questionId 根本不存在")
    void questionMustExist() {
        Map<Long, PaperStructureValidator.QuestionBrief> qs = subjectOne();
        qs.remove(6L);
        PracticeException e = assertThrows(PracticeException.class,
                () -> validate(TWO_QUESTIONS, 2, "14.00", 25, qs));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }

    @Test
    @DisplayName("规则 3 反例：题目分值之和 14.00 != total_score 15.00")
    void scoreSumMustEqualTotal() {
        PracticeException e = assertThrows(PracticeException.class,
                () -> validate(TWO_QUESTIONS, 2, "15.00", 25, subjectOne()));
        assertTrue(e.getMessage().contains("分值"), e.getMessage());
    }

    @Test
    @DisplayName("规则 3 容差必须是 0：7.005 + 7.00 != 14.00")
    void scoreSumToleranceIsZero() {
        String json = TWO_QUESTIONS.replace("\"score\":7.00}", "\"score\":7.005}");
        PracticeException e = assertThrows(PracticeException.class,
                () -> validate(json, 2, "14.00", 25, subjectOne()));
        assertTrue(e.getMessage().contains("分值"), e.getMessage());
    }

    @Test
    @DisplayName("规则 3 用 compareTo：total_score 写 14.0 而题目和是 14.00 必须通过")
    void scaleDifferenceIsNotMismatch() {
        assertDoesNotThrow(() -> validate(TWO_QUESTIONS, 2, "14.0", 25, subjectOne()));
    }

    @Test
    @DisplayName("规则 4 反例：部分时长之和 30 > 考试时长 25")
    void minutesMustFitDuration() {
        String json = TWO_QUESTIONS.replace("\"minutes\":25", "\"minutes\":30");
        PracticeException e = assertThrows(PracticeException.class,
                () -> validate(json, 2, "14.00", 25, subjectOne()));
        assertTrue(e.getMessage().contains("时长"), e.getMessage());
    }

    @Test
    @DisplayName("规则 4 允许小于总时长（留收卷余量），等于也允许")
    void minutesSmallerOrEqualPasses() {
        assertDoesNotThrow(() -> validate(TWO_QUESTIONS, 2, "14.00", 40, subjectOne()));
        assertDoesNotThrow(() -> validate(TWO_QUESTIONS, 2, "14.00", 25, subjectOne()));
    }

    @Test
    @DisplayName("两个 part 的题号跨 part 连续：part2 从 3 起才合法")
    void indexContinuesAcrossParts() {
        String twoParts = "{\"parts\":["
                + "{\"roman\":\"I\",\"name\":\"a\",\"cardLabel\":\"b\",\"score\":7.00,\"minutes\":10,"
                + "\"sections\":[{\"label\":\"A\",\"subTitle\":\"s\",\"direction\":null,\"audioUrl\":null,"
                + "\"originalText\":null,\"questions\":[{\"index\":1,\"questionId\":1,\"score\":7.00}]}]},"
                + "{\"roman\":\"II\",\"name\":\"c\",\"cardLabel\":\"d\",\"score\":7.00,\"minutes\":10,"
                + "\"sections\":[{\"label\":\"A\",\"subTitle\":\"s\",\"direction\":null,\"audioUrl\":null,"
                + "\"originalText\":null,\"questions\":[{\"index\":2,\"questionId\":6,\"score\":7.00}]}]}]}";
        assertDoesNotThrow(() -> validate(twoParts, 2, "14.00", 20, subjectOne()));

        String broken = twoParts.replace("\"index\":2,\"questionId\":6", "\"index\":1,\"questionId\":6");
        assertThrows(PracticeException.class, () -> validate(broken, 2, "14.00", 20, subjectOne()));
    }
}
