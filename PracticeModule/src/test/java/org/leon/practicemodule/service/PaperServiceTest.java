package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.AttemptRecordCountRow;
import org.leon.practicemodule.mapper.AttemptSubmitCountRow;
import org.leon.practicemodule.mapper.LatestAttemptRow;
import org.leon.practicemodule.mapper.PaperCardRow;
import org.leon.practicemodule.mapper.QbExamAttemptMapper;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.PaperCardVO;
import org.leon.practicemodule.pojo.vo.PaperDetailVO;
import org.leon.practicemodule.service.impl.PaperServiceImpl;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * paper/page 的装配、零 N+1、过滤下推与前置校验。
 *
 * <p>mock 是四个 mapper，被测的是真 {@code PaperServiceImpl} + 真 {@code PaperCards}
 * + 真 {@code PaperAttempts} + 真 {@code PracticePages}/{@code SubjectGuards}——
 * 分段装配里最容易漂移的"三个进度字段合流"这一步必须在被测侧。
 */
@DisplayName("paper/page：三段式聚合、零 N+1、过滤与校验")
class PaperServiceTest {

    private static final long USER = 10L;

    private final QbPaperMapper paperMapper = mock(QbPaperMapper.class);
    private final QbSubjectMapper subjectMapper = mock(QbSubjectMapper.class);
    private final QbExamAttemptMapper attemptMapper = mock(QbExamAttemptMapper.class);
    private final QbUserAnswerRecordMapper recordMapper = mock(QbUserAnswerRecordMapper.class);

    private PaperServiceImpl newService() {
        QbSubject subject = new QbSubject();
        subject.setId(1L);
        subject.setStatus(1);
        lenient().when(subjectMapper.selectById(1L)).thenReturn(subject);
        return new PaperServiceImpl(paperMapper, subjectMapper, attemptMapper, recordMapper);
    }

    private static PaperCardRow row(long paperId, int questionCount) {
        return new PaperCardRow(paperId, 1L, "语文", "2020年07月四级真题第1套",
                2020, 7, 1, 65, new BigDecimal("30.00"), questionCount);
    }

    @Test
    @DisplayName("一页一张卷：卡片铺满字段，徽章与进度按裁定三")
    void singleCardIsFullyAssembled() {
        PaperServiceImpl service = newService();
        when(paperMapper.countForList(eq(1L), isNull(), isNull(), isNull(), eq(USER))).thenReturn(1L);
        when(paperMapper.selectPage(eq(1L), isNull(), isNull(), isNull(), eq(USER), eq(0L), eq(20L)))
                .thenReturn(List.of(row(7L, 4)));
        when(attemptMapper.countSubmitted(eq(USER), any()))
                .thenReturn(List.of(new AttemptSubmitCountRow(7L, 2L)));
        when(attemptMapper.selectLatestByPapers(eq(USER), any()))
                .thenReturn(List.of(new LatestAttemptRow(7L, 99L, 2, null)));
        when(recordMapper.countByAttempts(eq(USER), any())).thenReturn(List.of());

        PageVO<PaperCardVO> page = service.page(USER, 1L, null, null, null, null, null);

        assertEquals(1L, page.getTotal());
        assertEquals(1L, page.getPages());
        PaperCardVO card = page.getList().get(0);
        assertEquals(7L, card.getPaperId());
        assertEquals("语文", card.getSubjectName());
        assertEquals(List.of("2020年", "第一套", "7月", "65分钟"), card.getBadges());
        assertEquals(0, new BigDecimal("30.00").compareTo(card.getTotalScore()));
        assertEquals(4, card.getQuestionCount());
        assertEquals(2L, card.getSubmitCount());
        assertEquals(0, card.getAnsweredCount(), "终态 attempt 但展开的记录行是 0 条（本段库里还没写过考试行）");
        assertEquals(0.0, card.getProgressPercent(), 1e-9);
        assertEquals(99L, card.getLatestAttemptId());
        assertEquals(2, card.getLatestAttemptStatus());
    }

    @Test
    @DisplayName("最新是草稿：已答数取 answered_json 的 key 数，且不占已提交次数")
    void draftLatestUsesAnsweredJson() {
        PaperServiceImpl service = newService();
        when(paperMapper.countForList(anyLong(), any(), any(), any(), anyLong())).thenReturn(1L);
        when(paperMapper.selectPage(anyLong(), any(), any(), any(), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(row(7L, 4)));
        when(attemptMapper.countSubmitted(anyLong(), any())).thenReturn(List.of());
        when(attemptMapper.selectLatestByPapers(anyLong(), any())).thenReturn(
                List.of(new LatestAttemptRow(7L, 100L, 1, "{\"1\":\"A\",\"6\":\"B\"}")));

        PaperCardVO card = service.page(USER, 1L, null, null, null, 1L, 20L).getList().get(0);

        assertEquals(0L, card.getSubmitCount(), "草稿不占已提交次数");
        assertEquals(2, card.getAnsweredCount());
        assertEquals(50.0, card.getProgressPercent(), 1e-9);
        assertEquals(100L, card.getLatestAttemptId());
        assertEquals(1, card.getLatestAttemptStatus());
        verify(recordMapper, never()).countByAttempts(anyLong(), any());
    }

    @Test
    @DisplayName("零 N+1：一页三张卡片只发 4 条 SQL，且都在循环外")
    void noPerRowQueries() {
        PaperServiceImpl service = newService();
        when(paperMapper.countForList(anyLong(), any(), any(), any(), anyLong())).thenReturn(3L);
        when(paperMapper.selectPage(anyLong(), any(), any(), any(), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(row(1L, 4), row(2L, 2), row(3L, 3)));
        when(attemptMapper.countSubmitted(anyLong(), any())).thenReturn(List.of());
        when(attemptMapper.selectLatestByPapers(anyLong(), any())).thenReturn(List.of());

        PageVO<PaperCardVO> page = service.page(USER, 1L, null, null, null, 1L, 20L);

        assertEquals(3, page.getList().size());
        verify(paperMapper, times(1)).countForList(anyLong(), any(), any(), any(), anyLong());
        verify(paperMapper, times(1)).selectPage(anyLong(), any(), any(), any(), anyLong(), anyLong(), anyLong());
        verify(attemptMapper, times(1)).countSubmitted(anyLong(), any());
        verify(attemptMapper, times(1)).selectLatestByPapers(anyLong(), any());
        verifyNoInteractions(recordMapper);
    }

    @Test
    @DisplayName("total=0：空壳照回，一条分页 SQL 都不发")
    void emptySubjectShortCircuits() {
        PaperServiceImpl service = newService();
        when(paperMapper.countForList(anyLong(), any(), any(), any(), anyLong())).thenReturn(0L);

        PageVO<PaperCardVO> page = service.page(USER, 1L, 2030, null, null, 1L, 20L);

        assertEquals(0L, page.getTotal());
        assertEquals(0L, page.getPages());
        assertTrue(page.getList().isEmpty());
        verify(paperMapper, never()).selectPage(anyLong(), any(), any(), any(), anyLong(), anyLong(), anyLong());
        verifyNoInteractions(attemptMapper, recordMapper);
    }

    @Test
    @DisplayName("计数与取行之间被并发禁用：total 照回真值，list 空，不打聚合 SQL")
    void concurrentDisableLeavesEmptyPage() {
        PaperServiceImpl service = newService();
        when(paperMapper.countForList(anyLong(), any(), any(), any(), anyLong())).thenReturn(2L);
        when(paperMapper.selectPage(anyLong(), any(), any(), any(), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of());

        PageVO<PaperCardVO> page = service.page(USER, 1L, null, null, null, 1L, 20L);

        assertEquals(2L, page.getTotal(), "total 是真值，不能被空页抹成 0");
        assertTrue(page.getList().isEmpty());
        verifyNoInteractions(attemptMapper, recordMapper);
    }

    @Test
    @DisplayName("过滤条件原样下推给两个 SQL：count 与 page 不一致就会算错 pages")
    void filtersGoToBothQueries() {
        PaperServiceImpl service = newService();
        when(paperMapper.countForList(1L, 2020, 7, 1, USER)).thenReturn(1L);
        when(paperMapper.selectPage(1L, 2020, 7, 1, USER, 0L, 20L)).thenReturn(List.of(row(7L, 4)));
        when(attemptMapper.countSubmitted(anyLong(), any())).thenReturn(List.of());
        when(attemptMapper.selectLatestByPapers(anyLong(), any())).thenReturn(List.of());

        service.page(USER, 1L, 2020, 7, 1, 1L, 20L);

        verify(paperMapper).countForList(1L, 2020, 7, 1, USER);
        verify(paperMapper).selectPage(1L, 2020, 7, 1, USER, 0L, 20L);
    }

    @Test
    @DisplayName("科目校验在试卷 SQL 之前：不存在 40400、停用 40900")
    void subjectGuardRunsFirst() {
        PaperServiceImpl service = newService();
        QbSubject disabled = new QbSubject();
        disabled.setId(0L);
        disabled.setStatus(0);
        when(subjectMapper.selectById(0L)).thenReturn(disabled);

        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.page(USER, 999L, null, null, null, null, null)).errorCode());
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.page(USER, 0L, null, null, null, null, null)).errorCode());
        verifyNoInteractions(paperMapper, attemptMapper, recordMapper);
    }

    @Test
    @DisplayName("pageSize=101 报 40000，且在任何试卷 SQL 之前")
    void pageSizeRejected() {
        PaperServiceImpl service = newService();
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, null, null, null, 1L, 101L)).errorCode());
        verifyNoInteractions(paperMapper, attemptMapper, recordMapper);
    }

    /** P1 的真实形态：两个 part、三个 section、四道题（分值 7/7/10/6，总 30.00，时长 25+40=65） */
    private static QbPaper seededPaper(long id, int status, long ownerId) {
        QbPaper p = new QbPaper();
        p.setId(id);
        p.setSubjectId(1L);
        p.setOwnerId(ownerId);
        p.setTitle("2020年07月大学英语四级真题第1套（样例）");
        p.setExamYear(2020);
        p.setExamMonth(7);
        p.setSetNo(1);
        p.setDurationMinutes(65);
        p.setTotalScore(new BigDecimal("30.00"));
        p.setQuestionCount(4);
        p.setDescription("样例卷：4 道题铺满两个部分");
        p.setStatus(status);
        p.setStructureJson("""
                {"parts":[{"roman":"I","name":"Listening Comprehension","cardLabel":"听力部分",
                  "score":14.00,"minutes":25,"sections":[{"label":"Section A","subTitle":"短新闻",
                  "direction":"Questions 1 and 2 are based on the news report you have just heard.",
                  "audioUrl":"https://cdn.ezpractice.example/2020-07-set1-part1.mp3",
                  "originalText":"News Report One（样例原文，考试中不可见）",
                  "questions":[{"index":1,"questionId":1,"score":7.00},{"index":2,"questionId":6,"score":7.00}]}]},
                 {"roman":"II","name":"Reading and Writing","cardLabel":"阅读部分",
                  "score":16.00,"minutes":40,"sections":[{"label":"Section A","subTitle":"多项选择",
                  "direction":null,"audioUrl":null,"originalText":null,
                  "questions":[{"index":3,"questionId":2,"score":10.00}]},
                  {"label":"Section B","subTitle":"简答题","direction":null,"audioUrl":null,"originalText":null,
                  "questions":[{"index":4,"questionId":5,"score":6.00}]}]}]}
                """);
        return p;
    }

    @Test
    @DisplayName("详情铺满三层骨架：两个 part、三个 section、四道题，题号 1..4")
    void detailReturnsSkeleton() {
        PaperServiceImpl service = newService();
        when(paperMapper.selectById(8L)).thenReturn(seededPaper(8L, 1, 1L));
        when(attemptMapper.countSubmitted(eq(USER), any())).thenReturn(List.of());
        when(attemptMapper.selectLatestByPapers(eq(USER), any())).thenReturn(List.of());

        PaperDetailVO vo = service.detail(USER, 8L);

        assertEquals(8L, vo.getPaperId());
        assertEquals(List.of("2020年", "第一套", "7月", "65分钟"), vo.getBadges());
        assertEquals(65, vo.getDurationMinutes());
        assertEquals(0, new BigDecimal("30.00").compareTo(vo.getTotalScore()));
        assertEquals(4, vo.getQuestionCount());
        assertEquals("样例卷：4 道题铺满两个部分", vo.getDescription());
        assertEquals(2, vo.getParts().size());
        assertEquals("I", vo.getParts().get(0).getRoman());
        assertEquals(0, new BigDecimal("14.00").compareTo(vo.getParts().get(0).getScore()));
        assertEquals(25L, vo.getParts().get(0).getMinutes());
        assertEquals(1, vo.getParts().get(0).getSections().size());
        assertEquals(2, vo.getParts().get(1).getSections().size());
        assertEquals(1, vo.getParts().get(0).getSections().get(0).getQuestions().get(0).getIndex());
        assertEquals(3, vo.getParts().get(1).getSections().get(0).getQuestions().get(0).getIndex());
        assertEquals(4, vo.getParts().get(1).getSections().get(1).getQuestions().get(0).getIndex());
        assertEquals(0L, vo.getSubmitCount());
        assertEquals(0.0, vo.getProgressPercent(), 1e-9);
        assertNull(vo.getLatestAttemptId());
    }

    @Test
    @DisplayName("裁定二：没有终态 attempt 时 originalText 恒 null，audioUrl 照给")
    void originalTextHiddenBeforeSubmit() {
        PaperServiceImpl service = newService();
        when(paperMapper.selectById(8L)).thenReturn(seededPaper(8L, 1, 1L));
        when(attemptMapper.countSubmitted(eq(USER), any())).thenReturn(List.of());
        when(attemptMapper.selectLatestByPapers(eq(USER), any())).thenReturn(
                List.of(new LatestAttemptRow(8L, 100L, 1, "{\"1\":\"A\"}")));

        PaperDetailVO vo = service.detail(USER, 8L);

        PaperDetailVO.SectionVO first = vo.getParts().get(0).getSections().get(0);
        assertNull(first.getOriginalText(), "草稿态必须看不见听力原文——原文即答案");
        assertEquals("https://cdn.ezpractice.example/2020-07-set1-part1.mp3", first.getAudioUrl());
        assertEquals("Questions 1 and 2 are based on the news report you have just heard.",
                first.getDirection(), "direction 是题目指令不是答案，不受闸门约束");
        assertEquals(0L, vo.getSubmitCount());
        assertEquals(1, vo.getAnsweredCount(), "草稿的已答数来自 answered_json 的 key 数");
        assertEquals(25.0, vo.getProgressPercent(), 1e-9, "1/4 = 25.00");
        assertEquals(100L, vo.getLatestAttemptId());
        assertEquals(1, vo.getLatestAttemptStatus());
    }

    @Test
    @DisplayName("裁定二：交过卷（submitCount>0）之后 originalText 才可见")
    void originalTextVisibleAfterSubmit() {
        PaperServiceImpl service = newService();
        when(paperMapper.selectById(8L)).thenReturn(seededPaper(8L, 1, 1L));
        when(attemptMapper.countSubmitted(eq(USER), any()))
                .thenReturn(List.of(new AttemptSubmitCountRow(8L, 1L)));
        when(attemptMapper.selectLatestByPapers(eq(USER), any()))
                .thenReturn(List.of(new LatestAttemptRow(8L, 101L, 2, null)));
        when(recordMapper.countByAttempts(eq(USER), any()))
                .thenReturn(List.of(new AttemptRecordCountRow(101L, 4L)));

        PaperDetailVO vo = service.detail(USER, 8L);

        assertEquals("News Report One（样例原文，考试中不可见）",
                vo.getParts().get(0).getSections().get(0).getOriginalText());
        assertEquals(1L, vo.getSubmitCount());
        assertEquals(4, vo.getAnsweredCount());
        assertEquals(100.0, vo.getProgressPercent(), 1e-9);
        verify(recordMapper, times(1)).countByAttempts(eq(USER), any());
    }

    @Test
    @DisplayName("详情三种失败路径：paperId 缺失 40000、不存在 40400、已下架 40900，都不打聚合 SQL")
    void detailErrorCodes() {
        PaperServiceImpl service = newService();
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.detail(USER, null)).errorCode());
        when(paperMapper.selectById(999L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.detail(USER, 999L)).errorCode());
        when(paperMapper.selectById(11L)).thenReturn(seededPaper(11L, 0, 1L));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.detail(USER, 11L)).errorCode());
        verifyNoInteractions(attemptMapper, recordMapper);
    }

    @Test
    @DisplayName("他人私有卷 40300：owner_id 既不是 1 也不是当前用户")
    void privatePaperOfAnotherUserIsForbidden() {
        PaperServiceImpl service = newService();
        when(paperMapper.selectById(12L)).thenReturn(seededPaper(12L, 1, 999004L));
        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> service.detail(USER, 12L)).errorCode());
        verifyNoInteractions(attemptMapper, recordMapper);
    }

    @Test
    @DisplayName("本人私有卷可以看：owner_id 等于当前用户就放行")
    void ownPrivatePaperIsVisible() {
        PaperServiceImpl service = newService();
        when(paperMapper.selectById(13L)).thenReturn(seededPaper(13L, 1, USER));
        when(attemptMapper.countSubmitted(eq(USER), any())).thenReturn(List.of());
        when(attemptMapper.selectLatestByPapers(eq(USER), any())).thenReturn(List.of());

        assertEquals(13L, service.detail(USER, 13L).getPaperId());
    }
}
