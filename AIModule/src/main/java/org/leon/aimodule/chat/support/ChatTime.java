package org.leon.aimodule.chat.support;

import java.time.LocalDateTime;
import java.time.ZoneId;

public final class ChatTime {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private ChatTime() {
    }

    /** 出参时间一律 epoch 毫秒，避免全局 ObjectMapper 被改动。 */
    public static Long millis(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toInstant().toEpochMilli();
    }

    /** 按码点截断，结果不超过 maxCp 个码点（省略号占一个名额），不留半个代理对。 */
    public static String truncate(String text, int maxCp) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.codePointCount(0, flat.length()) <= maxCp) {
            return flat;
        }
        if (maxCp <= 0) {
            return "";
        }
        if (maxCp == 1) {
            return "…";
        }
        int end = flat.offsetByCodePoints(0, maxCp - 1);
        return flat.substring(0, end) + "…";
    }
}
