package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 判分口径的唯一定义处（spec 5.3）。表驱动——每一行就是一个口径决定，
 * 改判分规则必须先改这张表。所有 answerJson 都是 qb_question.answer_json 的实测形态。
 */
class AnswerGraderTest {

    private final AnswerGrader grader = new AnswerGrader();
    private static final List<String> ABCD = List.of("A", "B", "C", "D");

    static Stream<Arguments> singleChoiceCases() {
        return Stream.of(
                Arguments.of("\"A\"", "\"A\"", true),
                Arguments.of("\"A\"", "\" a \"", true),
                Arguments.of("\"A\"", "\"B\"", false));
    }

    /** 非法来源：不在选项集内 / 非单选格式 / 空串 —— 全部 40000 不落库 */
    static Stream<Arguments> singleChoiceIllegalCases() {
        return Stream.of(
                Arguments.of("\"E\""),
                Arguments.of("\"Ａ\""),
                Arguments.of("[\"A\"]"),
                Arguments.of("\"\""));
    }

    static Stream<Arguments> multiChoiceCases() {
        return Stream.of(
                Arguments.of("[\"A\",\"C\"]", "[\"A\",\"C\"]", true),
                Arguments.of("[\"A\",\"C\"]", "[\"C\",\"A\"]", true),
                Arguments.of("[\"A\",\"C\"]", "[\"A\"]", false),
                Arguments.of("[\"A\",\"C\"]", "[\"A\",\"A\",\"C\"]", true),
                Arguments.of("[\"A\",\"C\"]", "[\"a\",\"c\"]", true),
                Arguments.of("[\"A\",\"C\"]", "[]", false));
    }

    static Stream<Arguments> judgeCases() {
        return Stream.of(
                Arguments.of("true", "true", true),
                Arguments.of("true", "\"true\"", true),
                Arguments.of("true", "\"TRUE\"", true),
                Arguments.of("true", "false", false));
    }

    static Stream<Arguments> blankCases() {
        return Stream.of(
                Arguments.of("[\"光合作用\"]", "[\"光合作用\"]", true),
                Arguments.of("[\"光合作用\"]", "[\" 光合作用 \"]", true),
                Arguments.of("[\"ABC\"]", "[\"ＡＢＣ\"]", true),
                Arguments.of("[\"ABC\"]", "[\"abc\"]", true),
                Arguments.of("[[\"光合作用\",\"叶绿体\"]]", "[\"叶绿体\"]", true),
                Arguments.of("[\"甲\",\"乙\"]", "[\"甲\"]", false));
    }

    @ParameterizedTest(name = "单选 {0} vs {1} -> {2}")
    @MethodSource("singleChoiceCases")
    void singleChoice(String answerJson, String userAnswerJson, boolean expected) {
        assertEquals(expected, grader.grade(1, answerJson, userAnswerJson, ABCD).correct());
    }

    @ParameterizedTest(name = "单选非法 {0} -> 40000")
    @MethodSource("singleChoiceIllegalCases")
    void singleChoiceRejects(String userAnswerJson) {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(1, "\"A\"", userAnswerJson, ABCD));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    @ParameterizedTest(name = "多选 {0} vs {1} -> {2}")
    @MethodSource("multiChoiceCases")
    void multiChoice(String answerJson, String userAnswerJson, boolean expected) {
        assertEquals(expected, grader.grade(2, answerJson, userAnswerJson, ABCD).correct());
    }

    @Test
    @DisplayName("多选非数组 -> 40000")
    void multiChoiceRejectsNonArray() {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(2, "[\"A\",\"C\"]", "\"A\"", ABCD));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    @ParameterizedTest(name = "判断 {0} vs {1} -> {2}")
    @MethodSource("judgeCases")
    void judge(String answerJson, String userAnswerJson, boolean expected) {
        assertEquals(expected, grader.grade(3, answerJson, userAnswerJson, List.of()).correct());
    }

    static Stream<Arguments> judgeRejectsCases() {
        return Stream.of(Arguments.of("\"yes\""), Arguments.of("1"));
    }

    @ParameterizedTest(name = "判断非法 {0} -> 40000")
    @MethodSource("judgeRejectsCases")
    void judgeRejects(String userAnswerJson) {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(3, "true", userAnswerJson, List.of()));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    @ParameterizedTest(name = "填空 {0} vs {1} -> {2}")
    @MethodSource("blankCases")
    void blank(String answerJson, String userAnswerJson, boolean expected) {
        assertEquals(expected, grader.grade(4, answerJson, userAnswerJson, List.of()).correct());
    }

    static Stream<Arguments> blankRejectsCases() {
        return Stream.of(Arguments.of("\"光合作用\""), Arguments.of("[123]"));
    }

    @ParameterizedTest(name = "填空非法 {0} -> 40000")
    @MethodSource("blankRejectsCases")
    void blankRejects(String userAnswerJson) {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(4, "[\"光合作用\"]", userAnswerJson, List.of()));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    static Stream<Arguments> shortAnswerCases() {
        return Stream.of(
                Arguments.of("\"参考答案：要点一\"", "\"我的作答\""));
    }

    /**
     * 简答不判内容，但答案必须是合法 JSON——user_answer_json 是 MySQL json 列，
     * 放进去非法文本会被驱动层拒绝（段 2 端到端实测：DataIntegrityViolation -> code=0），
     * 所以这条必须在判分器就挡成 40000。
     */
    static Stream<Arguments> shortAnswerRejectsCases() {
        return Stream.of(Arguments.of("我的作答"), Arguments.of("[\"坏 JSON"));
    }

    @ParameterizedTest(name = "简答 {1} -> 不判分等自评")
    @MethodSource("shortAnswerCases")
    void shortAnswer(String answerJson, String userAnswerJson) {
        AnswerGrader.GradeResult r = grader.grade(5, answerJson, userAnswerJson, List.of());
        assertFalse(r.correct(), "简答不能自动判对");
        assertTrue(r.needSelfJudge());
    }

    @ParameterizedTest(name = "简答非法 JSON {0} -> 40000")
    @MethodSource("shortAnswerRejectsCases")
    void shortAnswerRejectsIllegalJson(String userAnswerJson) {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(5, "\"参考答案：要点一\"", userAnswerJson, List.of()));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    static Stream<Arguments> unknownTypeCases() {
        return Stream.of(Arguments.of(0), Arguments.of(9));
    }

    @ParameterizedTest(name = "未知题型 {0} -> 40000")
    @MethodSource("unknownTypeCases")
    void unknownType(int type) {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(type, "\"A\"", "\"A\"", ABCD));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }
}