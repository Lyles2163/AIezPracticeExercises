package org.leon.aimodule.imagegen;

import java.util.Locale;

/**
 * 生图返回的是裸字节（b64_json 没有 Content-Type），只能靠魔数猜类型。
 * 猜错不影响显示（浏览器也会嗅探），但落 MinIO 时得有个像样的 Content-Type。
 */
public final class ImageContentTypes {

    static final String DEFAULT = "application/octet-stream";

    private ImageContentTypes() {
    }

    public static String sniff(byte[] data) {
        if (data == null || data.length < 12) {
            return DEFAULT;
        }
        if (startsWith(data, 0x89, 'P', 'N', 'G')) {
            return "image/png";
        }
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(data, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        if (startsWith(data, 'R', 'I', 'F', 'F') && data[8] == 'W' && data[9] == 'E'
                && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        return DEFAULT;
    }

    public static String extension(String contentType) {
        String ct = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        if (semi >= 0) {
            ct = ct.substring(0, semi).trim();
        }
        return switch (ct) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != (prefix[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
