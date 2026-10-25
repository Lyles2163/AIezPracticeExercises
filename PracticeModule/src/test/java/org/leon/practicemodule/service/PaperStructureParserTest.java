package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 卷面结构解析（spec 4.3）。这里盯的是两件事：
 * ① 合法结构必须三层都拆得出来、可空字段保持 null；
 * ② 缺字段/类型错必须报 40000 且消息带**定位**——
 *    因为 structure_json 是人手写的组卷数据，"哪里错了"比"错了"有用得多。
 */
@DisplayName("卷面结构解析：字段缺失必须点到位置")
class PaperStructureParserTest {

    /** 最小合法结构：1 个 part、1 个 section、2 题 */
    private static final String OK = "{\"parts\":[{"
            + "\"roman\":\"I\",\"name\":\"Listening Comprehension\",\"cardLabel\":\"听力部分\","
            + "\"score\":14.00,\"minutes\":25,\"sections\":[{"
            + "\"label\":\"Section A\",\"subTitle\":\"短新闻\","
            + "\"direction\":\"Questions 1 and 2 are based on the news report.\","
            + "\"audioUrl\":\"https://cdn/x.mp3\",\"originalText\":\"News Report One\","
            + "\"questions\":[{\"index\":1,\"questionId\":1,\"score\":7.00},"
            + "{\"index\":2,\"questionId\":6,\"score\":7.00}]}]}]}";

    @Test
    @DisplayName("合法结构：三层都拆出来，可空字段保持 null，非空的按值给")
    void parsesValidStructure() {
        PaperStructure s = PaperStructureParser.parse(OK);
        assertEquals(1, s.parts().size());
        PaperStructure.Part part = s.parts().get(0);
        assertEquals("I", part.roman());
        assertEquals("听力部分", part.cardLabel());
        assertEquals(0, new BigDecimal("14.00").compareTo(part.score()));
        assertEquals(25L, part.minutes());
        assertEquals(1, part.sections().size());

        PaperStructure.Section section = part.sections().get(0);
        assertEquals("Section A", section.label());
        assertEquals("短新闻", section.subTitle());
        assertEquals("https://cdn/x.mp3", section.audioUrl());
        assertEquals("News Report One", section.originalText());

        assertEquals(List.of(1L, 6L), s.questionsInOrder().stream()
                .map(PaperStructure.QuestionRef::questionId).toList());
        assertEquals(List.of(1, 2), s.questionsInOrder().stream()
                .map(PaperStructure.QuestionRef::index).toList());
        assertEquals(0, new BigDecimal("14.00").compareTo(s.sumQuestionScore()));
        assertEquals(25L, s.sumPartMinutes());
    }

    @Test
    @DisplayName("三个可空字段写 null 是合法的（选词填空类 section 没有音频与原文）")
    void nullableFieldsAcceptNull() {
        String json = OK.replace("\"audioUrl\":\"https://cdn/x.mp3\"", "\"audioUrl\":null")
                .replace("\"originalText\":\"News Report One\"", "\"originalText\":null")
                .replace("\"direction\":\"Questions 1 and 2 are based on the news report.\"", "\"direction\":null");
        PaperStructure.Section section = PaperStructureParser.parse(json).parts().get(0).sections().get(0);
        assertNull(section.direction());
        assertNull(section.audioUrl());
        assertNull(section.originalText());
    }

    @Test
    @DisplayName("可空字段整个缺键也合法（与显式 null 同义）")
    void missingNullableKeysAccept() {
        String json = OK.replace("\"originalText\":\"News Report One\",", "");
        assertNull(PaperStructureParser.parse(json).parts().get(0).sections().get(0).originalText());
    }

    @Test
    @DisplayName("整体不是合法 JSON：40000，消息点明不是合法 JSON")
    void rejectsGarbage() {
        PracticeException e = assertThrows(PracticeException.class,
                () -> PaperStructureParser.parse("{\"parts\":[{\"roman\":"));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        assertTrue(e.getMessage().contains("不是合法 JSON"), e.getMessage());
    }

    @Test
    @DisplayName("parts 缺失、不是数组、或空数组：都报 parts（空卷面没有意义）")
    void rejectsMissingOrEmptyParts() {
        PracticeException missing = assertThrows(PracticeException.class,
                () -> PaperStructureParser.parse("{}"));
        assertTrue(missing.getMessage().contains("parts"), missing.getMessage());

        assertTrue(assertThrows(PracticeException.class,
                () -> PaperStructureParser.parse("{\"parts\":[]}")).getMessage().contains("parts"));

        assertTrue(assertThrows(PracticeException.class,
                () -> PaperStructureParser.parse("{\"parts\":3}")).getMessage().contains("parts"));

        assertTrue(assertThrows(PracticeException.class,
                () -> PaperStructureParser.parse("[]")).getMessage().contains("对象"));
    }

    @Test
    @DisplayName("题缺 score：消息必须带定位 parts[0].sections[0].questions[0]")
    void missingScoreCarriesLocation() {
        String json = OK.replace("{\"index\":1,\"questionId\":1,\"score\":7.00}", "{\"index\":1,\"questionId\":1}");
        PracticeException e = assertThrows(PracticeException.class, () -> PaperStructureParser.parse(json));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        assertTrue(e.getMessage().contains("parts[0].sections[0].questions[0]"),
                "必须定位到第几个 part / section / 题，实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("score"), e.getMessage());
    }

    @Test
    @DisplayName("第二题缺 questionId：定位是 questions[1]，不是 questions[0]")
    void secondQuestionLocation() {
        String json = OK.replace("{\"index\":2,\"questionId\":6,\"score\":7.00}", "{\"index\":2,\"score\":7.00}");
        PracticeException e = assertThrows(PracticeException.class, () -> PaperStructureParser.parse(json));
        assertTrue(e.getMessage().contains("parts[0].sections[0].questions[1]"), e.getMessage());
        assertTrue(e.getMessage().contains("questionId"), e.getMessage());
    }

    @Test
    @DisplayName("数值字段给字符串（minutes 写成 \"25\"）：拦，且消息含 minutes")
    void rejectsWrongValueType() {
        String json = OK.replace("\"minutes\":25", "\"minutes\":\"25\"");
        PracticeException e = assertThrows(PracticeException.class, () -> PaperStructureParser.parse(json));
        assertTrue(e.getMessage().contains("minutes"), e.getMessage());
    }

    @Test
    @DisplayName("index 给小数（1.5）：拦，且消息含 index 而不是 score")
    void rejectsNonIntegerIndex() {
        String json = OK.replace("\"index\":1,", "\"index\":1.5,");
        PracticeException e = assertThrows(PracticeException.class, () -> PaperStructureParser.parse(json));
        assertTrue(e.getMessage().contains("index"), e.getMessage());
    }

    @Test
    @DisplayName("section 不带题 / part 不带 section：都拦（答题卡会渲染不出任何东西）")
    void rejectsEmptySectionsOrQuestions() {
        PracticeException noQuestions = assertThrows(PracticeException.class,
                () -> PaperStructureParser.parse(OK.replace(
                        "\"questions\":[{\"index\":1,\"questionId\":1,\"score\":7.00},"
                                + "{\"index\":2,\"questionId\":6,\"score\":7.00}]", "\"questions\":[]")));
        assertTrue(noQuestions.getMessage().contains("questions"), noQuestions.getMessage());

        PracticeException noSections = assertThrows(PracticeException.class,
                () -> PaperStructureParser.parse("{\"parts\":[{\"roman\":\"I\",\"name\":\"n\","
                        + "\"cardLabel\":\"c\",\"score\":1.00,\"minutes\":1,\"sections\":[]}]}"));
        assertTrue(noSections.getMessage().contains("sections"), noSections.getMessage());
    }

    @Test
    @DisplayName("文本字段给空串或纯空白：按缺失拦下（roman 是空的答题卡没标题）")
    void rejectsBlankTextFields() {
        String json = OK.replace("\"roman\":\"I\"", "\"roman\":\"  \"");
        PracticeException e = assertThrows(PracticeException.class, () -> PaperStructureParser.parse(json));
        assertTrue(e.getMessage().contains("roman"), e.getMessage());
    }
}
