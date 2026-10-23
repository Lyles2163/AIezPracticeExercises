package org.leon.aimodule.chat.support;

import org.leon.aimodule.chat.exception.ChatRejectException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;

/**
 * 会话列表游标 = base64url("<updateTime 毫秒>:<id>")，降序翻页。
 * 不签名、不加密：它只是分页位置，越权由 user_name 条件挡住。
 */
public final class CursorCodec {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private CursorCodec() {
    }

    public record Cursor(LocalDateTime updateTime, long id) {
    }

    public static String encode(LocalDateTime updateTime, long id) {
        long millis = updateTime.atZone(ZONE).toInstant().toEpochMilli();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((millis + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ChatRejectException("cursor 无效");
        }
        int sep = raw.lastIndexOf(':');
        if (sep <= 0 || sep == raw.length() - 1) {
            throw new ChatRejectException("cursor 无效");
        }
        try {
            long millis = Long.parseLong(raw.substring(0, sep));
            long id = Long.parseLong(raw.substring(sep + 1));
            return new Cursor(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE), id);
        } catch (NumberFormatException | java.time.DateTimeException e) {
            throw new ChatRejectException("cursor 无效");
        }
    }
}
