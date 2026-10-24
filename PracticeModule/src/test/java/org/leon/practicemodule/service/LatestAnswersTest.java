package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QuestionPositionRow;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@DisplayName("批量补齐：空集合绝不进 IN ()")
class LatestAnswersTest {

    private final QbUserAnswerRecordMapper recordMapper = mock(QbUserAnswerRecordMapper.class);
    private final QbQuestionMapper questionMapper = mock(QbQuestionMapper.class);

    @Test
    @DisplayName("ids 为空/null：返回空 Map 且一次库都不访")
    void emptyIdsShortCircuit() {
        // 空 IN 会生成 `WHERE question_id IN ()`，MySQL 直接语法错 -> code=0（在说谎）
        assertTrue(LatestAnswers.byQuestion(recordMapper, 1L, List.of()).isEmpty());
        assertTrue(LatestAnswers.byQuestion(recordMapper, 1L, null).isEmpty());
        assertTrue(LatestAnswers.positions(questionMapper, List.of()).isEmpty());
        assertTrue(LatestAnswers.positions(questionMapper, null).isEmpty());
        verifyNoInteractions(recordMapper, questionMapper);
    }

    @Test
    @DisplayName("有值：一次查询拿全，按 questionId 建索引")
    void buildsMaps() {
        when(recordMapper.selectLatestByQuestions(anyLong(), any()))
                .thenReturn(List.of(new LatestAnswerRow(9L, 2L, "\"A\"", 1, 1)));
        when(questionMapper.selectPositions(any()))
                .thenReturn(List.of(new QuestionPositionRow(2L, 3L)));

        assertEquals(1, LatestAnswers.byQuestion(recordMapper, 1L, List.of(2L, 3L)).size());
        assertEquals(3L, LatestAnswers.positions(questionMapper, List.of(2L)).get(2L));
        verify(recordMapper, times(1)).selectLatestByQuestions(anyLong(), any());
        verify(questionMapper, times(1)).selectPositions(any());
    }
}
