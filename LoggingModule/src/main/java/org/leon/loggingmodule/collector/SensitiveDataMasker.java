package org.leon.loggingmodule.collector;

import org.leon.loggingmodule.config.AuditProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感字段与文本脱敏器（设计文档 §6.5）：
 * - URI 查询参数与键值对中的敏感字段（如 password/token/authorization/smsCode/idCard/phone）统一脱敏
 * - 手机号保留前三后四（138****1234）
 * - 邮箱保留前缀首字符（l***@example.com）
 * - 身份证号保留前四后四
 * - 中文姓名保留姓（张*）
 */
public class SensitiveDataMasker {

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("([a-zA-Z0-9._%+-])([a-zA-Z0-9._%+-]*)(@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<![0-9A-Za-z])(\\d{4})\\d{10}(\\d{3}[0-9Xx])(?![0-9A-Za-z])");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._\\-]+");

    private final AuditProperties properties;

    public SensitiveDataMasker(AuditProperties properties) {
        this.properties = properties;
    }

    /**
     * 对包含查询串的 URI 做敏感参数脱敏并截断到指定长度。
     */
    public String maskUri(String uri, String queryString, int maxLen) {
        if (uri == null) {
            return "";
        }
        String result;
        if (queryString == null || queryString.isBlank()) {
            result = maskText(uri);
        } else {
            StringBuilder sb = new StringBuilder(uri).append('?');
            String[] pairs = queryString.split("&");
            Set<String> sensitive = sensitiveFieldSet();
            for (int i = 0; i < pairs.length; i++) {
                if (i > 0) {
                    sb.append('&');
                }
                String pair = pairs[i];
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String key = pair.substring(0, eq);
                    String val = pair.substring(eq + 1);
                    if (isSensitiveKey(key, sensitive)) {
                        sb.append(key).append("=***");
                    } else {
                        sb.append(key).append('=').append(maskText(val));
                    }
                } else {
                    sb.append(maskText(pair));
                }
            }
            result = sb.toString();
        }
        return truncate(result, maxLen);
    }

    /**
     * 对异常消息或普通文本做脱敏与长度截断。
     */
    public String maskMessage(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        String masked = maskSensitiveKeyValues(text);
        masked = maskText(masked);
        return truncate(masked, maxLen);
    }

    /**
     * 脱敏手机号/邮箱/身份证/Bearer Token。
     */
    public String maskText(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = BEARER_PATTERN.matcher(input).replaceAll("$1***");
        out = ID_CARD_PATTERN.matcher(out).replaceAll("$1**********$2");
        out = PHONE_PATTERN.matcher(out).replaceAll("$1****$3");
        out = EMAIL_PATTERN.matcher(out).replaceAll("$1***$3");
        return out;
    }

    /**
     * 按策略脱敏姓名：保留第一个字符，其余用 * 替代。
     */
    public String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() == 1) {
            return "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    private String maskSensitiveKeyValues(String text) {
        Set<String> sensitive = sensitiveFieldSet();
        if (sensitive.isEmpty()) {
            return text;
        }
        String out = text;
        for (String field : sensitive) {
            Pattern kvPattern = Pattern.compile(
                    "(?i)(\"?" + Pattern.quote(field) + "\"?\\s*[:=]\\s*\"?)([^\"\\s,&}]+)(\"?)");
            Matcher m = kvPattern.matcher(out);
            out = m.replaceAll("$1***$3");
        }
        return out;
    }

    private Set<String> sensitiveFieldSet() {
        List<String> fields = properties.getSensitiveFields();
        Set<String> set = new HashSet<>();
        if (fields != null) {
            for (String f : fields) {
                if (f != null && !f.isBlank()) {
                    for (String part : f.split(",")) {
                        String trimmed = part.trim().toLowerCase(Locale.ROOT);
                        if (!trimmed.isEmpty()) {
                            set.add(trimmed);
                        }
                    }
                }
            }
        }
        return set;
    }

    private boolean isSensitiveKey(String key, Set<String> sensitive) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String s : sensitive) {
            if (lower.equals(s) || lower.contains(s)) {
                return true;
            }
        }
        return false;
    }

    public static String truncate(String s, int maxLen) {
        if (s == null || maxLen <= 0 || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen);
    }
}
