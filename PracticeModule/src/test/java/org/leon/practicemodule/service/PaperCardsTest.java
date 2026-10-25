package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 卡片上的两类纯计算：徽章文案与进度数字（段 4 裁定三）。
 *
 * 徽章为什么由后端拼：spec 5.6 明写"前端直接渲染卡片，不拼字符串"。
 * 交给前端拼就会出现"第一套 / 第1套 / Set 1"三套中文序数，而那是产品文案不是数据。
 */
@DisplayName("卡片徽章与进度口径")
class PaperCardsTest {

    @Test
    @DisplayName("四个徽章齐全，顺序固定为 年/套/月/时长")
    void allBadgesInSpecOrder() {
        assertEquals(List.of("2020年", "第一套", "7月", "65分钟"),
                PaperCards.badges(2020, 7, 1, 65));
    }

    @Test
    @DisplayName("缺哪个徽章就跳过哪个，不留空串也不留 null 元素")
    void nullFieldsAreSkipped() {
        assertEquals(List.of("120分钟"), PaperCards.badges(null, null, null, 120));
        assertEquals(List.of("2021年", "12月"), PaperCards.badges(2021, 12, null, null));
        assertTrue(PaperCards.badges(null, null, null, null).isEmpty());
    }

    @Test
    @DisplayName("月份是数字（截图就是 7月）；套次中文序数表覆盖到十，超出用阿拉伯数字兜住")
    void setNoFallbackBeyondTen() {
        assertEquals(List.of("第十套"), PaperCards.badges(null, null, 10, null));
        assertEquals(List.of("第一套"), PaperCards.badges(null, null, 1, null));
        assertEquals(List.of("第11套"), PaperCards.badges(null, null, 11, null));
        assertEquals(List.of("12月"), PaperCards.badges(null, 12, null, null));
    }

    @Test
    @DisplayName("progressPercent：2/4=50.00，口径与 accuracy 一致（2 位 HALF_UP），除零回 0.0")
    void progressPercentRounding() {
        assertEquals(50.0, PaperCards.progressPercent(2, 4), 1e-9);
        assertEquals(33.33, PaperCards.progressPercent(1, 3), 1e-9);
        assertEquals(66.67, PaperCards.progressPercent(2, 3), 1e-9);
        assertEquals(75.0, PaperCards.progressPercent(3, 4), 1e-9);
        assertEquals(0.0, PaperCards.progressPercent(0, 0), 1e-9);
        assertEquals(0.0, PaperCards.progressPercent(3, 0), 1e-9);
        assertEquals(100.0, PaperCards.progressPercent(4, 4), 1e-9);
    }

    @Test
    @DisplayName("草稿的已答数是 map 的 key 数；空/缺失/脏数据都回 0 而不是炸")
    void draftAnsweredCount() {
        assertEquals(2, PaperCards.answeredJsonKeyCount("{\"1\":\"A\",\"6\":\"B\"}"));
        assertEquals(1, PaperCards.answeredJsonKeyCount("{\"1\":null}"), "值为 null 也算答过这题的键");
        assertEquals(0, PaperCards.answeredJsonKeyCount("{}"));
        assertEquals(0, PaperCards.answeredJsonKeyCount(null));
        assertEquals(0, PaperCards.answeredJsonKeyCount(""));
        assertEquals(0, PaperCards.answeredJsonKeyCount("这不是 JSON"));
        assertEquals(0, PaperCards.answeredJsonKeyCount("[1,2,3]"), "不是对象形态的草稿按 0，不当数组数");
    }
}
