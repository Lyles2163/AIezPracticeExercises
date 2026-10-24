package org.leon.practicemodule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.vo.QuestionVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 脱敏守卫（spec 5.4/9.1.2）：答题态响应按 F12 不该看到答案。
 * 本类建立"禁止子串清单"，段 3 的取题接口、段 5 的 exam/question 直接扩这份清单。
 */
class PracticeMaskerTest {

    private final PracticeMasker masker = new PracticeMasker();

    private QbQuestion question(int type) {
        QbQuestion q = new QbQuestion();
        q.setId(1L);
        q.setSubjectId(2L);
        q.setType(type);
        q.setTitle("题干");
        q.setDifficulty(2);
        q.setStatus(1);
        q.setAnswerJson("\"A\"");
        q.setAnalysis("这是解析，绝不能出现在答题态");
        q.setTagsJson("[\"语法\",\"时态\"]");
        return q;
    }

    /** 重载：允许 null 题型（实体字段是 Integer，防御路径要能测） */
    private QbQuestion question(Integer type) {
        QbQuestion q = question(1);
        q.setType(type);
        return q;
    }

    private QbQuestionOption option(String key, String content, int isCorrect) {
        QbQuestionOption o = new QbQuestionOption();
        o.setQuestionId(1L);
        o.setOptionKey(key);
        o.setContent(content);
        o.setIsCorrect(isCorrect);
        return o;
    }

    @Test
    @DisplayName("五种题型都有中文名")
    void allFiveTypesNamed() {
        assertEquals("单选题", masker.toQuestionVO(question(1), List.of()).getTypeName());
        assertEquals("多选题", masker.toQuestionVO(question(2), List.of()).getTypeName());
        assertEquals("判断题", masker.toQuestionVO(question(3), List.of()).getTypeName());
        assertEquals("填空题", masker.toQuestionVO(question(4), List.of()).getTypeName());
        assertEquals("简答题", masker.toQuestionVO(question(5), List.of()).getTypeName());
    }

    @Test
    @DisplayName("未知题型与 null 题型不炸，落成未知题型")
    void unknownTypeFallback() {
        assertEquals("未知题型", masker.toQuestionVO(question(9), List.of()).getTypeName());
        assertEquals("未知题型", masker.toQuestionVO(question((Integer) null), List.of()).getTypeName());
    }

    @Test
    @DisplayName("tags_json 合法/为空/坏 JSON 三种都不连累取题")
    void tagsParsing() {
        assertEquals(List.of("语法", "时态"), masker.toQuestionVO(question(1), List.of()).getTags());

        QbQuestion blank = question(1);
        blank.setTagsJson("  ");
        assertEquals(List.of(), masker.toQuestionVO(blank, List.of()).getTags());

        QbQuestion broken = question(1);
        broken.setTagsJson("{不是数组");
        assertEquals(List.of(), masker.toQuestionVO(broken, List.of()).getTags());
    }

    @Test
    @DisplayName("选项只复制标号与内容；无选项返回空列表而不是 null")
    void optionsCopied() {
        QuestionVO vo = masker.toQuestionVO(question(1),
                List.of(option("A", "选项甲", 1), option("B", "选项乙", 0)));
        assertEquals(2, vo.getOptions().size());
        assertEquals("A", vo.getOptions().get(0).getOptionKey());
        assertEquals("选项甲", vo.getOptions().get(0).getContent());
        assertEquals(List.of(), masker.toQuestionVO(question(3), List.of()).getOptions());
    }

    @Test
    @DisplayName("脱敏守卫：序列化后不得出现任何答案字段")
    void serializedQuestionNeverLeaksAnswers() throws Exception {
        QbQuestion q = question(1);
        // 值级哨兵：光查字段名，"把 analysis 改名成 remark 但照抄内容"照样能过守卫
        q.setAnswerJson("\"ANSWER_SENTINEL\"");
        q.setAnalysis("ANALYSIS_SENTINEL");
        QuestionVO vo = masker.toQuestionVO(q,
                List.of(option("A", "选项甲", 1), option("B", "选项乙", 0)));
        String json = new ObjectMapper().writeValueAsString(vo);

        // 正向锚点：扫描目标为空串时，下面的负向断言会全部空转
        assertTrue(json.contains("\"title\""), "扫描目标为空，负向断言无意义");
        assertTrue(json.contains("选项甲"));
        for (String forbidden : PracticeMasker.FORBIDDEN_IN_ANSWER_STATE) {
            assertFalse(json.contains(forbidden), "答题态泄漏了 " + forbidden + "：" + json);
        }
        assertFalse(json.contains("ANSWER_SENTINEL"), "答案内容被换了字段名仍外泄：" + json);
        assertFalse(json.contains("ANALYSIS_SENTINEL"), "解析内容被换了字段名仍外泄：" + json);
    }

    @Test
    @DisplayName("守卫非空转证明：直接序列化实体确实带着这些字段")
    void scannerWouldCatchRawEntity() throws Exception {
        String json = new ObjectMapper().writeValueAsString(question(1));
        assertTrue(json.contains("answerJson") && json.contains("analysis"),
                "实体序列化都不含这些键，说明扫描串写错了，守卫是一条永远绿的空断言");
    }

    @Test
    @DisplayName("titleBrief：null 原样、空白折叠、20 字符内不动、超长截成 20")
    void titleBriefCases() {
        assertNull(PracticeMasker.titleBrief(null));
        assertEquals("a b", PracticeMasker.titleBrief("  a \t\n b  "));
        String twenty = "一二三四五六七八九十一二三四五六七八九十";   // 20 字（两个"一…十"）
        assertEquals(twenty, PracticeMasker.titleBrief(twenty), "刚好 20 字不该被砍");
        String twentyOne = twenty + "三";
        String brief = PracticeMasker.titleBrief(twentyOne);
        assertEquals(20, brief.length(), "截断后总长仍是 20，含省略号");
        assertEquals("…", brief.substring(19));
        assertEquals(twenty.substring(0, 19), brief.substring(0, 19));
    }

    @Test
    @DisplayName("titleBrief：真库两条题干做锚点——qid 4 刚好 20 字不动，qid 6 的 21 字被截")
    void titleBriefHitsRealData() {
        // 实测（2026-10-24，seg3/TitleProbe.java）：七道题干长度 10~21 码点，无换行
        String qid4 = "植物通过____作用将光能转化为化学能。";
        String qid6 = "这是用户 alice 的私有题，正确的是？";
        assertEquals(20, qid4.codePointCount(0, qid4.length()), "锚点数据变了，本用例要重取样本");
        assertEquals(21, qid6.codePointCount(0, qid6.length()), "锚点数据变了，本用例要重取样本");
        assertEquals(qid4, PracticeMasker.titleBrief(qid4), "20 字的题干原样回，不砍不该砍的");
        String brief = PracticeMasker.titleBrief(qid6);
        assertEquals(20, brief.length());
        assertEquals(qid6.substring(0, 19), brief.substring(0, 19));
        assertEquals("…", brief.substring(19));
    }

    @Test
    @DisplayName("parseTags 转 public static 后行为不变：合法/空白/坏 JSON")
    void parseTagsStillSafe() {
        assertEquals(List.of("语法", "时态"), PracticeMasker.parseTags("[\"语法\",\"时态\"]"));
        assertEquals(List.of(), PracticeMasker.parseTags(null));
        assertEquals(List.of(), PracticeMasker.parseTags("[]"));
        assertEquals(List.of(), PracticeMasker.parseTags("{坏"));
    }
}