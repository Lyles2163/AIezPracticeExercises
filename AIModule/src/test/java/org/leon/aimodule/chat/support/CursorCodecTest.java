package org.leon.aimodule.chat.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.exception.ChatRejectException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorCodecTest {

    @Test
    @DisplayName("编解码往返保持毫秒与 id")
    void roundTrips() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 23, 9, 8, 7, 123_000_000);
        String cursor = CursorCodec.encode(t, 4242L);
        assertTrue(cursor.matches("[A-Za-z0-9_-]+"), "必须是 URL 安全的 base64，能直接放 query: " + cursor);
        CursorCodec.Cursor back = CursorCodec.decode(cursor);
        assertEquals(t, back.updateTime());
        assertEquals(4242L, back.id());
    }

    @Test
    @DisplayName("空游标表示第一页")
    void blankMeansFirstPage() {
        assertNull(CursorCodec.decode(null));
        assertNull(CursorCodec.decode(""));
        assertNull(CursorCodec.decode("   "));
    }

    @Test
    @DisplayName("伪造或截断的游标翻成业务拒绝，而不是 NumberFormatException")
    void rejectsGarbage() {
        for (String bad : new String[]{
                // 字符不在 base64url 字母表内，解码阶段就抛 IllegalArgumentException
                "!!!", "abc:def", ":",
                // 合法 base64url，但解出 "hello" / "123"，没有冒号 → sep == -1
                "aGVsbG8", "MTIz",
                // 合法 base64url，解出 "123:"，冒号在末尾
                "MTIzOg==",
                // 合法 base64url，解出 "abc:def"：冒号在中间，Long.parseLong("abc") 抛 NumberFormatException
                "YWJjOmRlZg",
                // 合法 base64url，解出 ":123"：冒号在首位（sep == 0），走 sep <= 0 分支
                "OjEyMw"
        }) {
            ChatRejectException e = assertThrows(ChatRejectException.class, () -> CursorCodec.decode(bad),
                    "应当拒绝: " + bad);
            assertEquals("cursor 无效", e.getMessage());
        }
    }

    @Test
    @DisplayName("负数时间戳与超长数字不崩")
    void survivesExtremeNumbers() {
        CursorCodec.Cursor c = CursorCodec.decode(
                java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString((-9999999999999L + ":" + Long.MAX_VALUE).getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(Long.MAX_VALUE, c.id());
        assertEquals(-9999999999999L,
                c.updateTime().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
    }
}
