package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbQuestion;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 段 5 测试夹具：样例卷 P1 的真实结构 + 它的判分原料 + 三种实体工厂。
 *
 * <p>必须是 {@code public}：{@code AnswerStateResponseGuardTest} 与
 * {@code PracticeExamControllerTest} 在 {@code controller} 包，包私有类够不到。
 * 结构、briefs 与答案 map 在五个测试类里复用，只在这里定义一次。
 */
public final class ExamFixtures {

    /** P1 的 structure_json 原文：{@link #p1Structure()} 与 {@link #paperP1()} 共用这一份 */
    public static final String P1_JSON = """
            {"parts":[
              {"roman":"I","name":"Listening Comprehension","cardLabel":"听力部分","score":14.00,"minutes":25,
               "sections":[{"label":"Section A","subTitle":"短新闻","direction":"d","audioUrl":"a.mp3",
                 "originalText":"News Report One（样例原文）","questions":[
                   {"index":1,"questionId":1,"score":7.00},{"index":2,"questionId":6,"score":7.00}]}]},
              {"roman":"II","name":"Reading and Writing","cardLabel":"阅读部分","score":16.00,"minutes":40,
               "sections":[{"label":"Section A","subTitle":"多项选择","direction":null,"audioUrl":null,
                 "originalText":null,"questions":[{"index":3,"questionId":2,"score":10.00}]},
                {"label":"Section B","subTitle":"简答题","direction":null,"audioUrl":null,
                 "originalText":null,"questions":[{"index":4,"questionId":5,"score":6.00}]}]}]}
            """;

    public static final long PAPER_ID = 8L;
    public static final long ATTEMPT_ID = 500L;

    private ExamFixtures() {
    }

    /** 与库里 qb_paper id=8 的 structure_json 同形态（两个 part、三个 section、四道题 1/6/2/5） */
    public static PaperStructure p1Structure() {
        return PaperStructureParser.parse(P1_JSON);
    }

    public static Map<Long, ExamGrading.Brief> briefs() {
        return Map.of(
                1L, new ExamGrading.Brief(1L, 1, "\"A\"", "A 对", List.of("A", "B", "C", "D")),
                6L, new ExamGrading.Brief(6L, 1, "\"C\"", "C 对", List.of("A", "B", "C")),
                2L, new ExamGrading.Brief(2L, 2, "[\"A\",\"B\"]", "AB 对", List.of()),
                5L, new ExamGrading.Brief(5L, 5, "\"参考答案\"", "简答解析", List.of()));
    }

    public static Map<Long, String> allRight() {
        return Map.of(1L, "\"A\"", 6L, "\"C\"", 2L, "[\"A\",\"B\"]", 5L, "\"我的简答\"");
    }

    /** 启用态（status=1）的题目实体，只填判分与卷面校验要用的列 */
    public static QbQuestion question(long id, int type, String answerJson) {
        return question(id, type, answerJson, 1);
    }

    /** 下架题（status=0）：{@code requireEnabled} 分支的反例 */
    public static QbQuestion disabled(long id, int type) {
        return question(id, type, "\"x\"", 0);
    }

    private static QbQuestion question(long id, int type, String answerJson, int status) {
        QbQuestion q = new QbQuestion();
        q.setId(id);
        q.setSubjectId(1L);
        q.setType(type);
        q.setAnswerJson(answerJson);
        q.setAnalysis("解析");
        q.setStatus(status);
        return q;
    }

    /** 样例卷 P1 的 qb_paper 行（id=8、科目 1、公共卷、4 题 30 分 65 分钟） */
    public static QbPaper paperP1() {
        QbPaper p = new QbPaper();
        p.setId(PAPER_ID);
        p.setSubjectId(1L);
        p.setOwnerId(1L);
        p.setTitle("2020年07月大学英语四级真题第1套（样例）");
        p.setDurationMinutes(65);
        p.setTotalScore(new BigDecimal("30.00"));
        p.setQuestionCount(4);
        p.setStatus(1);
        p.setStructureJson(P1_JSON);
        return p;
    }

    /**
     * 一条<b>进行中</b>（status=1）的草稿，attemptId 固定 500，deadline 设在将来。
     * 需要"过期""已提交"的用例自己改 {@code deadlineAt}/{@code status}——正因为默认值是"正常在考"，
     * 每条用例只需说明它偏离了哪一项，读测试时才不用倒推夹具。
     */
    public static QbExamAttempt draftAttempt(long userId, long paperId, String answeredJson) {
        long now = System.currentTimeMillis();
        QbExamAttempt a = new QbExamAttempt();
        a.setId(ATTEMPT_ID);
        a.setUserId(userId);
        a.setPaperId(paperId);
        a.setSubjectId(1L);
        a.setSeqNo(1);
        a.setStatus(1);
        a.setStartedAt(new Date(now - 60_000L));
        a.setDeadlineAt(new Date(now + 65 * 60_000L));
        a.setAnsweredJson(answeredJson);
        return a;
    }

    /** 只含一道题的最小卷面：配合"该题不是单选"的桩，用来验"全卷没有单选题就不发第二条 SQL" */
    public static PaperStructure oneQuestionStructure() {
        return PaperStructureParser.parse("""
                {"parts":[{"roman":"I","name":"n","cardLabel":"c","score":1.00,"minutes":1,
                 "sections":[{"label":"A","subTitle":"s","direction":null,"audioUrl":null,
                   "originalText":null,"questions":[{"index":1,"questionId":1,"score":1.00}]}]}]}
                """);
    }
}
