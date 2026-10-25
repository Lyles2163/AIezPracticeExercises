package org.leon.practicemodule.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QuestionOptionKeyRow;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 判分原料的取数纪律：一张卷子最多两条 SQL，且这两条都在循环外。
 *
 * <p>{@code times(1)} 是这里的主要工具——"看起来批量、其实在循环里逐题查"这种 N+1
 * 用结果断言永远发现不了（答案是对的），只有条数能钉住。
 */
class ExamBriefsTest {

    private QbQuestionMapper questionMapper;
    private QbQuestionOptionMapper optionMapper;
    private ExamBriefs briefs;

    @BeforeEach
    void setUp() {
        questionMapper = mock(QbQuestionMapper.class);
        optionMapper = mock(QbQuestionOptionMapper.class);
        briefs = new ExamBriefs(questionMapper, optionMapper);
    }

    @Test
    @DisplayName("两次批量：题目一次、选项键一次；没有单选题时不发第二条")
    void loadsInTwoQueries() {
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.question(6L, 1, "\"C\""),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]"), ExamFixtures.question(5L, 5, "\"参考答案\"")));
        when(optionMapper.selectOptionKeysByQuestions(any())).thenReturn(List.of(
                new QuestionOptionKeyRow(1L, "A"), new QuestionOptionKeyRow(1L, "B"),
                new QuestionOptionKeyRow(6L, "A"), new QuestionOptionKeyRow(6L, "C")));

        Map<Long, ExamGrading.Brief> loaded = briefs.load(ExamFixtures.p1Structure(), true);

        assertEquals(4, loaded.size());
        assertEquals(List.of("A", "B"), loaded.get(1L).optionKeys());
        assertEquals(List.of("A", "C"), loaded.get(6L).optionKeys());
        assertEquals(List.of(), loaded.get(2L).optionKeys(), "多选题不需要选项键集合");
        verify(questionMapper, times(1)).selectBatchIds(any());
        verify(optionMapper, times(1)).selectOptionKeysByQuestions(any());

        reset(questionMapper, optionMapper);
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(ExamFixtures.question(1L, 2, "[\"A\"]")));
        briefs.load(ExamFixtures.oneQuestionStructure(), true);
        // 全卷没有单选题就不该发第二条 SQL：
        verifyNoInteractions(optionMapper);
    }

    @Test
    @DisplayName("requireEnabled 的两条分支：缺题 40400、下架 40900；false 时下架放过")
    void enabledGateDiffersByCaller() {
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.disabled(6L, 1),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]"), ExamFixtures.question(5L, 5, "\"参考答案\"")));
        when(optionMapper.selectOptionKeysByQuestions(any())).thenReturn(List.of(
                new QuestionOptionKeyRow(1L, "A"), new QuestionOptionKeyRow(6L, "B")));

        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> briefs.load(ExamFixtures.p1Structure(), true)).errorCode());
        // 成绩单重建路径：题下架了也必须能看到已交卷的成绩，所以同一条数据在 false 下要放过
        assertEquals(4, briefs.load(ExamFixtures.p1Structure(), false).size());

        reset(questionMapper, optionMapper);
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.question(6L, 1, "\"C\""),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]")));
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> briefs.load(ExamFixtures.p1Structure(), true)).errorCode(), "qid 5 查不到 = 题被删了");
    }

    @Test
    @DisplayName("空 IN 挡在前面：卷面零题时一条 SQL 都不发")
    void emptyStructureShortCircuits() {
        assertEquals(Map.of(), briefs.loadQuestions(new PaperStructure(List.of())));
        verifyNoInteractions(questionMapper, optionMapper);
    }
}
