package org.leon.practicemodule.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.exception.PracticeExceptionHandler;
import org.leon.practicemodule.mapper.AttemptRecordCountRow;
import org.leon.practicemodule.mapper.AttemptSubmitCountRow;
import org.leon.practicemodule.mapper.FavoritePageRow;
import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.LatestAttemptRow;
import org.leon.practicemodule.mapper.PaperCardRow;
import org.leon.practicemodule.mapper.QbExamAttemptMapper;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserFavoriteMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.mapper.QuestionPageRow;
import org.leon.practicemodule.mapper.QuestionPositionRow;
import org.leon.practicemodule.mapper.WrongPageRow;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.config.PracticeProperties;
import org.leon.practicemodule.service.AnswerGrader;
import org.leon.practicemodule.service.ExamBriefs;
import org.leon.practicemodule.service.ExamFinalizer;
import org.leon.practicemodule.service.ExamService;
import org.leon.practicemodule.service.FavoriteService;
import org.leon.practicemodule.service.PaperService;
import org.leon.practicemodule.service.PracticeMasker;
import org.leon.practicemodule.service.QuestionReadService;
import org.leon.practicemodule.service.UserIdentityService;
import org.leon.practicemodule.service.WrongBookService;
import org.leon.practicemodule.service.ExamFixtures;
import org.leon.practicemodule.service.impl.ExamServiceImpl;
import org.leon.practicemodule.service.impl.FavoriteServiceImpl;
import org.leon.practicemodule.service.impl.PaperServiceImpl;
import org.leon.practicemodule.service.impl.QuestionReadServiceImpl;
import org.leon.practicemodule.service.impl.WrongBookServiceImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 脱敏守卫跑在**真实响应字节**上（spec 9.1.2 的段 3 升级；段 2 只跑到 VO 的进程内序列化）。
 *
 * 组装：真控制器 + 真服务 + 真 PracticeMasker + mock 的 mapper。
 * 为什么不用 @SpringBootTest：那会去拉 Nacos/Redis/Seata；standalone MockMvc 已经把
 * "控制器 → advice → Jackson → 字节"这一段全走一遍，守卫要的就是这一段。
 *
 * 读响应用 getContentAsByteArray + UTF-8 手工解码：MockHttpServletResponse 的默认字符集
 * 是 ISO-8859-1，直接 getContentAsString() 会把中文读成问号，正向锚点就会假失败。
 */
@DisplayName("答题态响应体不含任何答案字段")
class AnswerStateResponseGuardTest {

    private static final String ANSWER_SENTINEL = "ANSWER_SENTINEL_GUARD";
    private static final String ANALYSIS_SENTINEL = "ANALYSIS_SENTINEL_GUARD";

    private QbQuestionMapper questionMapper;
    private QbUserAnswerRecordMapper recordMapper;
    private QbUserFavoriteMapper favoriteMapper;
    private QbSubjectMapper subjectMapper;
    private QbUserWrongMapper wrongMapper;
    private QbQuestionOptionMapper optionMapper;
    private QbPaperMapper paperMapper;
    private QbExamAttemptMapper attemptMapper;
    private MockMvc mockMvc;

    private static String body(MvcResult result) throws Exception {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @BeforeEach
    void wireRealServicesOverMockMappers() {
        questionMapper = mock(QbQuestionMapper.class);
        recordMapper = mock(QbUserAnswerRecordMapper.class);
        favoriteMapper = mock(QbUserFavoriteMapper.class);
        subjectMapper = mock(QbSubjectMapper.class);
        wrongMapper = mock(QbUserWrongMapper.class);
        optionMapper = mock(QbQuestionOptionMapper.class);
        paperMapper = mock(QbPaperMapper.class);
        attemptMapper = mock(QbExamAttemptMapper.class);
        UserIdentityService identity = mock(UserIdentityService.class);
        when(identity.resolveUserId("alice")).thenReturn(10L);

        when(subjectMapper.selectById(1L)).thenReturn(subject(1));

        QuestionReadService readService = new QuestionReadServiceImpl(questionMapper, recordMapper,
                favoriteMapper, subjectMapper, wrongMapper, optionMapper, new PracticeMasker());
        WrongBookService wrongBookService =
                new WrongBookServiceImpl(wrongMapper, questionMapper, recordMapper, subjectMapper);
        FavoriteService favoriteService =
                new FavoriteServiceImpl(favoriteMapper, questionMapper, subjectMapper);
        // paper 侧不接 masker：卷面骨架里根本没有题目字段，守卫要盯的是 structure_json 里的
        // 原文/答案会不会随实体或内嵌 record 一起出网（spec 9.1.2 把这两个接口列进同一份清单）。
        PaperService paperService = new PaperServiceImpl(paperMapper, subjectMapper,
                attemptMapper, recordMapper);
        // 段 5 的 exam/question：真服务 + 真遮罩器 + mock mapper，与上面四个接口同一套装配方式。
        // masker 必须是真的 new：脱敏口径本身就是被测对象，mock 掉它这条守卫就是空转。
        ExamBriefs examBriefs = new ExamBriefs(questionMapper, optionMapper);
        ExamService examService = new ExamServiceImpl(paperMapper, attemptMapper, recordMapper,
                questionMapper, optionMapper, examBriefs,
                new ExamFinalizer(attemptMapper, recordMapper, wrongMapper, examBriefs, new AnswerGrader()),
                new PracticeProperties(), new PracticeMasker());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PracticeQuestionController(readService, identity),
                        new PracticeWrongController(wrongBookService, identity),
                        new PracticeFavoriteController(favoriteService, identity),
                        new PracticePaperController(paperService, identity),
                        new PracticeExamController(examService, identity))
                .setControllerAdvice(new PracticeExceptionHandler())
                .build();
    }

    private static QbQuestionOption option(String key, String content, int isCorrect) {
        QbQuestionOption o = new QbQuestionOption();
        o.setOptionKey(key);
        o.setContent(content);
        o.setIsCorrect(isCorrect);
        return o;
    }

    /** 造一道"带真值"的题：脱敏器写错时这些哨兵就会出现在字节里 */
    private static QbQuestion rawQuestion(long id) {
        QbQuestion q = new QbQuestion();
        q.setId(id);
        q.setSubjectId(1L);
        q.setType(1);
        q.setTitle("题干甲");
        q.setDifficulty(2);
        q.setStatus(1);
        q.setAnswerJson("\"" + ANSWER_SENTINEL + "\"");
        q.setAnalysis(ANALYSIS_SENTINEL);
        q.setTagsJson("[\"语法\"]");
        return q;
    }

    private static QbSubject subject(int status) {
        QbSubject s = new QbSubject();
        s.setId(1L);
        s.setStatus(status);
        return s;
    }

    /** 每个答题态接口都跑这一套。清单只有一份，来自 PracticeMasker */
    private void assertNoAnswerFields(String responseBody, String anchor) {
        assertTrue(responseBody.contains(anchor),
                "响应体里没有本接口自己的字段 [" + anchor + "]，说明没打到真接口，负向断言是空转：" + responseBody);
        for (String forbidden : PracticeMasker.FORBIDDEN_IN_ANSWER_STATE) {
            assertFalse(responseBody.contains(forbidden), "响应体泄漏了 " + forbidden + "：" + responseBody);
        }
        assertFalse(responseBody.contains(ANSWER_SENTINEL), "答案内容换了字段名照样外泄：" + responseBody);
        assertFalse(responseBody.contains(ANALYSIS_SENTINEL), "解析内容换了字段名照样外泄：" + responseBody);
    }

    private MvcResult get(String path) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .header("X-User-Name", "alice"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
    }

    @Test
    @DisplayName("question/page 的响应体干净")
    void questionPageResponseIsClean() throws Exception {
        when(questionMapper.countEnabledBySubject(1L)).thenReturn(1L);
        when(questionMapper.selectEnabledPage(eq(1L), anyLong(), anyLong()))
                .thenReturn(List.of(new QuestionPageRow(1L, 1, 2, "[\"语法\"]", 1)));

        String json = body(get("/practice/question/page?subjectId=1"));

        assertNoAnswerFields(json, "\"favorited\"");
        assertTrue(json.contains("\"answerStatus\":0"), "未作答的题该显式给 0：" + json);
        assertTrue(json.contains("\"index\":1"), "答题卡序号没进响应：" + json);
    }

    @Test
    @DisplayName("question/current 的响应体：选项不带 isCorrect，题干不带答案与解析")
    void questionCurrentResponseIsClean() throws Exception {
        when(questionMapper.selectById(1L)).thenReturn(rawQuestion(1L));
        when(optionMapper.selectDisplayOptions(1L)).thenReturn(List.of(
                option("A", "选项甲", 1), option("B", "选项乙", 0)));
        when(questionMapper.selectPositions(any()))
                .thenReturn(List.of(new QuestionPositionRow(1L, 1L)));
        when(recordMapper.selectLatestByQuestions(eq(10L), any()))
                .thenReturn(List.of(new LatestAnswerRow(22L, 1L, "\"我的上次作答\"", -1, 1)));

        String json = body(get("/practice/question/current?questionId=1"));

        assertNoAnswerFields(json, "\"题干甲\"");
        assertTrue(json.contains("\"index\":1"), "index 没填进响应：" + json);
        assertTrue(json.contains("\"pendingRecordId\":22"),
                "待自评的 recordId 没带回，自评入口会不可达：" + json);
        assertTrue(json.contains("我的上次作答"), "lastUserAnswerJson 是允许的用户自身数据：" + json);
    }

    /**
     * 段 5 的 exam/question（spec 9.1.3：这条是答题态清单里唯一还没被守卫覆盖的 GET）。
     * 用带真值的实体喂它（{@link #rawQuestion} 的 answerJson/analysis 都是哨兵），
     * 一旦遮罩漏字段，值哨兵就会在字节里现形。
     */
    @Test
    @DisplayName("exam/question 的响应体干净：选项不带 isCorrect，题干不带答案与解析")
    void examQuestionResponseIsClean() throws Exception {
        when(paperMapper.selectById(8L)).thenReturn(ExamFixtures.paperP1());
        when(attemptMapper.selectById(500L))
                .thenReturn(ExamFixtures.draftAttempt(10L, 8L, "{\"1\":\"A\"}"));
        when(questionMapper.selectById(1L)).thenReturn(rawQuestion(1L));
        when(optionMapper.selectDisplayOptions(1L)).thenReturn(List.of(
                option("A", "选项甲", 1), option("B", "选项乙", 0)));

        String json = body(get("/practice/exam/question?attemptId=500&questionId=1"));

        assertNoAnswerFields(json, "\"选项甲\"");
        assertTrue(json.contains("\"index\":1"), "卷面序号没进响应：" + json);
        // 实测：structure_json 里的 7.00 被 Jackson 读成 DoubleNode，decimalValue() 回的是 scale=1 的
        // BigDecimal("7.0")，所以出网是 "score":7.0 而不是 7.00。两种形态都接受，Task 5.10 端到端按真应用核。
        assertTrue(json.contains("\"score\":7.00") || json.contains("\"score\":7.0"),
                "这道题在卷面上的分值必须给前端（答题页要显示 7 分）：" + json);
        assertTrue(json.contains("\"userAnswerJson\":\"\\\"A\\\"\""),
                "自己的草稿答案必须回显：" + json);
    }

    @Test
    @DisplayName("favorite/page 的响应体干净，且 createdAt 是 epoch 毫秒")    void favoritePageResponseIsClean() throws Exception {
        when(favoriteMapper.countFavorites(10L, 1L)).thenReturn(1L);
        when(favoriteMapper.selectFavoritePage(eq(10L), eq(1L), anyLong(), anyLong()))
                .thenReturn(List.of(new FavoritePageRow(1L, new Date(1_700_000_000_000L), 1, "题干甲")));
        when(questionMapper.selectPositions(any())).thenReturn(List.of(new QuestionPositionRow(1L, 1L)));

        String json = body(get("/practice/favorite/page?subjectId=1"));

        assertNoAnswerFields(json, "\"titleBrief\"");
        assertTrue(json.contains("题干甲"), "列表要能认出是哪道题：" + json);
        assertTrue(json.contains("1700000000000"), "createdAt 必须是 epoch 毫秒：" + json);
    }

    @Test
    @DisplayName("wrong/page 的响应体干净，且 titleBrief 真的铺进了响应")
    void wrongPageResponseIsClean() throws Exception {
        when(wrongMapper.countWrong(eq(10L), eq(1L), isNull())).thenReturn(1L);
        when(wrongMapper.selectWrongPage(eq(10L), eq(1L), isNull(), anyLong(), anyLong()))
                .thenReturn(List.of(new WrongPageRow(1L, 2, new Date(1_700_000_000_000L), 1, 1,
                        "题干甲")));
        when(questionMapper.selectPositions(any()))
                .thenReturn(List.of(new QuestionPositionRow(1L, 1L)));

        String json = body(get("/practice/wrong/page?subjectId=1"));

        assertNoAnswerFields(json, "\"titleBrief\"");
        // titleBrief 的原料是 title，而 title 本来就可外泄，所以这里刻意不把哨兵塞进题干：
        // 那会变成"谁在题干里写了'ANALYSIS_SENTINEL'这几个字谁就违规"的假阳性。
        // 真值列的防线由字段名清单 + question/current 那条选项哨兵守着。
        assertTrue(json.contains("题干甲"), "列表要能认出是哪道题：" + json);
    }

    @Test
    @DisplayName("paper/page 与 paper/detail 的响应体干净（spec 9.1.2 的 paper 侧升级）")
    void paperResponsesAreClean() throws Exception {
        when(paperMapper.countForList(eq(1L), isNull(), isNull(), isNull(), eq(10L))).thenReturn(1L);
        when(paperMapper.selectPage(eq(1L), isNull(), isNull(), isNull(), eq(10L), eq(0L), eq(20L)))
                .thenReturn(List.of(new PaperCardRow(7L, 1L, "语文", "题干甲样例卷",
                        2020, 7, 1, 65, new BigDecimal("30.00"), 1)));
        when(attemptMapper.countSubmitted(eq(10L), any())).thenReturn(List.of());
        when(attemptMapper.selectLatestByPapers(eq(10L), any())).thenReturn(List.of());

        String pageJson = body(get("/practice/paper/page?subjectId=1"));
        assertNoAnswerFields(pageJson, "\"badges\"");

        // detail：交过卷才有原文，这里给出终态 attempt，让 originalText 真的出现在字节里——
        // 用它证明"值哨兵不炸"：原文里写的不是答案字段，闸门放行是设计而非漏洞。
        when(paperMapper.selectById(7L)).thenReturn(submittedPaper());
        when(attemptMapper.countSubmitted(eq(10L), any()))
                .thenReturn(List.of(new AttemptSubmitCountRow(7L, 1L)));
        when(attemptMapper.selectLatestByPapers(eq(10L), any()))
                .thenReturn(List.of(new LatestAttemptRow(7L, 3L, 2, null)));
        when(recordMapper.countByAttempts(eq(10L), any()))
                .thenReturn(List.of(new AttemptRecordCountRow(3L, 1L)));

        String detailJson = body(get("/practice/paper/detail?paperId=7"));
        assertNoAnswerFields(detailJson, "\"originalText\"");
        assertTrue(detailJson.contains("样例原文"), "originalText 在交卷后必须真的出现：" + detailJson);
        assertTrue(detailJson.contains("\"submitCount\":1"), detailJson);
    }

    /** 骨架只有一道题的样例卷：structure_json 里塞的是原文，不是答案 */
    private static QbPaper submittedPaper() {
        QbPaper paper = new QbPaper();
        paper.setId(7L);
        paper.setSubjectId(1L);
        paper.setOwnerId(1L);
        paper.setTitle("题干甲样例卷");
        paper.setExamYear(2020);
        paper.setExamMonth(7);
        paper.setSetNo(1);
        paper.setDurationMinutes(65);
        paper.setTotalScore(new BigDecimal("30.00"));
        paper.setQuestionCount(1);
        paper.setStatus(1);
        paper.setStructureJson("""
                {"parts":[{"roman":"I","name":"L","cardLabel":"听力","score":7.00,"minutes":65,
                 "sections":[{"label":"A","subTitle":"短新闻","direction":"d",
                 "audioUrl":"https://cdn/a.mp3","originalText":"样例原文",
                 "questions":[{"index":1,"questionId":1,"score":7.00}]}]}]}
                """);
        return paper;
    }

    @Test
    @DisplayName("缺 subjectId → 40000，而不是兜底的服务器繁忙")
    void missingParamIsClientError() throws Exception {
        String json = body(get("/practice/question/page"));
        assertTrue(json.contains("\"code\":40000"), "缺参应转 40000：" + json);
    }

    @Test
    @DisplayName("非空转对照：真泄漏时这套扫描必须红")
    void guardCatchesRawEntityResponse() throws Exception {
        MockMvc leaky = MockMvcBuilders.standaloneSetup(new LeakController())
                .setControllerAdvice(new PracticeExceptionHandler()).build();
        String json = new String(leaky.perform(MockMvcRequestBuilders.get("/leak"))
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

        // 这套断言若对"直接把实体吐出去"的响应都无感，它就是永远绿的空转
        assertTrue(json.contains("answerJson"), "扫描抓不到 answerJson，守卫无效：" + json);
        assertTrue(json.contains("analysis"), "扫描抓不到 analysis，守卫无效：" + json);
        assertTrue(json.contains(ANALYSIS_SENTINEL), "哨兵没出现在泄漏响应里：" + json);
    }

    /** 只用于对照实验的"错误示范"：把实体当响应直接吐出去 */
    @RestController
    static class LeakController {
        @GetMapping("/leak")
        public Result<QbQuestion> leak() {
            QbQuestion q = new QbQuestion();
            q.setId(1L);
            q.setSubjectId(1L);
            q.setType(1);
            q.setTitle("题干");
            q.setStatus(1);
            q.setAnswerJson("\"" + ANSWER_SENTINEL + "\"");
            q.setAnalysis(ANALYSIS_SENTINEL);
            return Result.success(q);
        }
    }
}
