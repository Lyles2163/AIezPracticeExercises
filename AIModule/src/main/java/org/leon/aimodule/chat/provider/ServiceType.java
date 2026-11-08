package org.leon.aimodule.chat.provider;

import org.leon.aimodule.chat.exception.ChatRejectException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 供应商级别的服务能力。一家供应商可以同时提供聊天与生图（OpenAI、百炼都是这样），
 * 所以这里用集合而不是单值；落库是一列 CSV，读回来再拆。
 */
public enum ServiceType {
    CHAT, IMAGE, RAG;

    /** 老数据 / 老前端不传时的兜底能力，升级过程零迁移风险。 */
    public static final ServiceType DEFAULT = CHAT;

    public boolean covers(ModelType modelType) {
        return modelType != null && modelType.serviceType() == this;
    }

    /**
     * CSV → 集合。未知值直接丢弃而不是抛错：脏数据不该让整个设置页打不开。
     * 顺序固定按枚举声明序（CHAT,IMAGE,RAG），落库结果可比对、可断言。
     */
    public static List<ServiceType> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of(DEFAULT);
        }
        Set<ServiceType> picked = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            ServiceType type = parse(part);
            if (type != null) {
                picked.add(type);
            }
        }
        return picked.isEmpty() ? List.of(DEFAULT) : ordered(picked);
    }

    public static List<ServiceType> parseList(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of(DEFAULT);
        }
        Set<ServiceType> picked = new LinkedHashSet<>();
        for (String part : raw) {
            ServiceType type = parse(part);
            if (type != null) {
                picked.add(type);
            }
        }
        return picked.isEmpty() ? List.of(DEFAULT) : ordered(picked);
    }

    /** 严格版：请求体里写了不认识的取值就拒绝，别静默改成 CHAT。 */
    public static List<ServiceType> parseStrict(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of(DEFAULT);
        }
        Set<ServiceType> picked = new LinkedHashSet<>();
        for (String part : raw) {
            ServiceType type = parse(part);
            if (type == null) {
                throw new ChatRejectException("未知的服务能力: " + part);
            }
            picked.add(type);
        }
        return ordered(picked);
    }

    public static String toCsv(Collection<ServiceType> types) {
        List<ServiceType> ordered = types == null ? List.of(DEFAULT) : ordered(new LinkedHashSet<>(types));
        StringBuilder sb = new StringBuilder();
        for (ServiceType type : ordered) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(type.name());
        }
        return sb.toString();
    }

    public static ServiceType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<ServiceType> ordered(Collection<ServiceType> types) {
        List<ServiceType> out = new ArrayList<>();
        for (ServiceType type : values()) {
            if (types.contains(type)) {
                out.add(type);
            }
        }
        return out;
    }
}
