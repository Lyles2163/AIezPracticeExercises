package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * answered_json 是 {"<questionId>":<整份 JSON 答案>}。
 * 这个类型的边界只有两处值得测：key 的往返（数字↔字符串）与 value 的严格度（Jackson 宽、MySQL 严）。
 */
class ExamAnswersTest {

    @Test
    @DisplayName("NULL 与空串都当没答过，不炸")
    void decodesEmpty() {
        assertEquals(Map.of(), ExamAnswers.decode(null));
        assertEquals(Map.of(), ExamAnswers.decode(""));
        assertEquals(Map.of(), ExamAnswers.decode("  "));
        assertEquals(0, ExamAnswers.countOf(null));
    }

    @Test
    @DisplayName("编解码往返：key 是十进制题号字符串，value 保持 JSON 结构")
    void roundTrip() {
        Map<Long, String> answers = new LinkedHashMap<>();
        answers.put(1L, "\"A\"");
        answers.put(4L, "[\"光合作用\"]");
        // 简答题的 value 也是**整份 JSON**（带引号的字符串字面量），不是裸文本：
        // 裸文本连 MySQL 的 json 列都进不去（段 2 实测过的同一件事）
        answers.put(5L, "\"我的简答原文\"");

        String json = ExamAnswers.encode(answers);
        Map<Long, String> back = ExamAnswers.decode(json);

        assertEquals(3, back.size());
        assertEquals("\"A\"", back.get(1L));
        assertEquals("[\"光合作用\"]", back.get(4L));
        assertEquals("\"我的简答原文\"", back.get(5L));
    }

    @Test
    @DisplayName("MySQL 规范化形态照单收下（冒号后带空格），countOf 与段 4 的 answeredJsonKeyCount 同结果")
    void decodesNormalizedForm() {
        String normalized = "{ \"1\" : \"A\", \"6\" : \"B\" }";
        assertEquals(2, ExamAnswers.decode(normalized).size());
        assertEquals(2, ExamAnswers.countOf(normalized));
        assertEquals(2, PaperCards.answeredJsonKeyCount(normalized),
                "两处必须是同一个口径，否则卡片的已答数与答题页的对不上");
    }

    @Test
    @DisplayName("非对象、脏数据一律 40900，不当成空 map 悄悄放过")
    void rejectsDirtyData() {
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> ExamAnswers.decode("[1,2]")).errorCode());
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> ExamAnswers.decode("{\"1\":")).errorCode());
    }

    @Test
    @DisplayName("裁定五：value 必须是整份单值 JSON——尾随内容与 Infinity 都报 40000")
    void rejectsValuesMysqlWouldRefuse() {
        Map<Long, String> tail = Map.of(1L, "\"A\" \"B\"");
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> ExamAnswers.requireValuesValid(tail)).errorCode());
        Map<Long, String> inf = Map.of(1L, "1e999");
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> ExamAnswers.requireValuesValid(inf)).errorCode());
        // 空 value 会被 json 列拒（不是合法 JSON），同样 40000
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> ExamAnswers.requireValuesValid(Map.of(1L, "  "))).errorCode());
        assertDoesNotThrow(() -> ExamAnswers.requireValuesValid(Map.of(
                1L, "\"A\"", 2L, "[\"A\",\"B\"]", 3L, "true", 4L, "[\"\"]", 5L, "\"我的作答\"")));
    }

    @Test
    @DisplayName("encode 拒绝非正题号；空 map 编成 {}")
    void encodeGuardsKeys() {
        assertEquals("{}", ExamAnswers.encode(Map.of()));
        PracticeException e = assertThrows(PracticeException.class,
                () -> ExamAnswers.encode(Map.of(0L, "\"B\"")));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode(), "0 不是合法题号");
        assertThrows(PracticeException.class, () -> ExamAnswers.encode(Map.of(-1L, "\"B\"")));
    }
}
