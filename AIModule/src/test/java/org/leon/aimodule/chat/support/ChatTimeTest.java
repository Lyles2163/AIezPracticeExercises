package org.leon.aimodule.chat.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTimeTest {

    private static final String ELLIPSIS = "…";

    private static int cpCount(String s) {
        return s.codePointCount(0, s.length());
    }

    private static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("超预算时省略号占一个名额：结果恰好 maxCp 个码点并以省略号结尾")
    void truncatesToExactlyBudgetIncludingEllipsis() {
        String result = ChatTime.truncate("abcdefgh", 5);
        assertEquals("abcd…", result);
        assertEquals(5, cpCount(result));
        assertTrue(result.endsWith(ELLIPSIS));
    }

    @Test
    @DisplayName("恰好等于预算的文本原样返回，不追加省略号")
    void keepsTextAtExactBudget() {
        String text = "abcde";
        assertEquals(text, ChatTime.truncate(text, 5));
        assertFalse(ChatTime.truncate(text, 5).endsWith(ELLIPSIS));
    }

    @Test
    @DisplayName("短于预算的文本原样返回")
    void keepsShorterText() {
        assertEquals("abc", ChatTime.truncate("abc", 10));
    }

    @Test
    @DisplayName("换行与回车压成空格，结果去除首尾空白")
    void flattensLineBreaksAndTrims() {
        assertEquals("第一行 第二行 第三行",
                ChatTime.truncate("  第一行\n第二行\r第三行  ", 100));
    }

    @Test
    @DisplayName("emoji 按码点截断，不产生半个代理对")
    void neverSplitsSurrogatePair() {
        String result = ChatTime.truncate("😀😀😀", 2);
        assertEquals("😀…", result);
        assertEquals(2, cpCount(result));
        assertFalse(hasUnpairedSurrogate(result));
    }

    @Test
    @DisplayName("null 返回空串")
    void nullBecomesEmptyString() {
        assertEquals("", ChatTime.truncate(null, 10));
    }

    @Test
    @DisplayName("maxCp 为 1 时只返回一个省略号")
    void budgetOfOneYieldsOnlyEllipsis() {
        assertEquals(ELLIPSIS, ChatTime.truncate("abc", 1));
        assertEquals(ELLIPSIS, ChatTime.truncate("😀😀", 1));
        assertEquals(1, cpCount(ChatTime.truncate("abc", 1)));
    }

    @Test
    @DisplayName("maxCp 非正时不返回任何字符")
    void nonPositiveBudgetYieldsEmptyString() {
        assertEquals("", ChatTime.truncate("abc", 0));
        assertEquals("", ChatTime.truncate("abc", -3));
    }

    @Test
    @DisplayName("120 码点预算下超长标题截断为 120 个码点，不会撑破 varchar(120)")
    void longTitleFitsVarchar120Budget() {
        String title = "标".repeat(130);
        String result = ChatTime.truncate(title, 120);
        assertEquals(120, cpCount(result), "省略号占用预算，120 字预算含省略号本身");
        assertTrue(result.endsWith(ELLIPSIS));
        assertEquals("标".repeat(119) + ELLIPSIS, result);
    }

    @Test
    @DisplayName("恰好 120 码点的标题原样返回")
    void exact120CodePointTitleUnchanged() {
        String title = "标".repeat(120);
        assertEquals(title, ChatTime.truncate(title, 120));
    }

    @Test
    @DisplayName("millis 按 Asia/Shanghai 换算为 epoch 毫秒，null 保持 null")
    void millisUsesShanghaiZone() {
        assertNull(ChatTime.millis(null));
        // 2026-01-01 00:00 Asia/Shanghai == 2025-12-31T16:00:00Z
        assertEquals(1767196800000L, ChatTime.millis(LocalDateTime.of(2026, 1, 1, 0, 0)));
    }
}
