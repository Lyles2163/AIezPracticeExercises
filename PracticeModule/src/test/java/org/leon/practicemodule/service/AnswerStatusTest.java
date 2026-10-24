package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leon.practicemodule.mapper.LatestAnswerRow;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("answerStatus 三态映射与可自评判据（裁定一/三）")
class AnswerStatusTest {

    private LatestAnswerRow row(Integer isCorrect, Integer mode) {
        return new LatestAnswerRow(7L, 1L, "\"A\"", isCorrect, mode);
    }

    @ParameterizedTest(name = "is_correct={0} -> answerStatus={1}")
    @CsvSource({
            "1, 1",      // 答对
            "0, 2",      // 答错
            "-1, 3",     // 待自评：既不是对也不是错
    })
    void mapping(int isCorrect, int expected) {
        assertEquals(expected, AnswerStatus.of(row(isCorrect, 1)));
    }

    @Test
    @DisplayName("无记录 / is_correct 为 null 都算未作答，不抛 NPE")
    void unanswered() {
        assertEquals(AnswerStatus.UNANSWERED, AnswerStatus.of(null));
        assertEquals(AnswerStatus.UNANSWERED, AnswerStatus.of(row(null, 1)));
    }

    @Test
    @DisplayName("只有练习类的待自评可自评：mode=2 的考试简答不给 recordId")
    void selfJudgeGate() {
        assertTrue(AnswerStatus.selfJudgeable(row(-1, 1)));
        assertTrue(AnswerStatus.selfJudgeable(row(-1, 3)));
        assertFalse(AnswerStatus.selfJudgeable(row(-1, 2)),
                "段 2 的闸门只收 mode 1/3，回 id 就是回一个必然 40000 的按钮");
        assertFalse(AnswerStatus.selfJudgeable(row(-1, null)));
        assertFalse(AnswerStatus.selfJudgeable(row(1, 1)), "已判过的没有自评这回事");
        assertFalse(AnswerStatus.selfJudgeable(null));
    }

    @Test
    @DisplayName("四个常量就是 API 的取值范围，写死防止将来随手加第五个态")
    void constants() {
        assertEquals(0, AnswerStatus.UNANSWERED);
        assertEquals(1, AnswerStatus.CORRECT);
        assertEquals(2, AnswerStatus.WRONG);
        assertEquals(3, AnswerStatus.PENDING_SELF_JUDGE);
    }
}
