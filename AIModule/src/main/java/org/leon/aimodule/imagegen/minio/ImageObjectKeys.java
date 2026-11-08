package org.leon.aimodule.imagegen.minio;

import org.leon.aimodule.imagegen.ImageContentTypes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 对象键：{清洗后的用户名}/{yyyyMMdd}/{uuid}.{ext}。
 * 用户名来自网关注入的 X-User-Name，可能含中文/空格等 Object Key 不接受的字符，
 * 统一清洗；越权判断用同一个清洗函数，保证"能写进去就能取回来"。
 */
public final class ImageObjectKeys {

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private ImageObjectKeys() {
    }

    public static String build(String userName, String contentType) {
        return build(userName, contentType, LocalDate.now(), UUID.randomUUID().toString().replace("-", ""));
    }

    static String build(String userName, String contentType, LocalDate date, String uuid) {
        return sanitizeUser(userName) + "/" + DAY.format(date) + "/" + uuid
                + "." + ImageContentTypes.extension(contentType);
    }

    /** 必须等于「清洗后用户名的第一段」才算自己的图；顺带挡掉 .. 之类的路径穿越。 */
    public static boolean ownedBy(String objectKey, String userName) {
        if (objectKey == null || objectKey.isBlank() || userName == null || userName.isBlank()) {
            return false;
        }
        String key = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        if (key.contains("..") || key.contains("\\")) {
            return false;
        }
        int slash = key.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        return key.substring(0, slash).equals(sanitizeUser(userName));
    }

    /**
     * 清洗后可能撞名（"张"和"李"都会洗成 "_"），那样两个用户就能读到对方的图。
     * 所以只要清洗结果和原名不一致，就补一段原名的摘要后缀。
     */
    public static String sanitizeUser(String userName) {
        String raw = userName == null ? "" : userName.trim();
        if (raw.isEmpty()) {
            return "_anonymous";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String clean = sb.toString().toLowerCase(Locale.ROOT);
        if (clean.equals(raw.toLowerCase(Locale.ROOT))) {
            return clean;
        }
        return clean + "-" + digest(raw);
    }

    private static String digest(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然带 SHA-256；真走到这里说明 JRE 被人动过，直接拒绝比冒险撞名好
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
