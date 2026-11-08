package org.leon.aimodule.chat.provider;

import org.leon.aimodule.chat.exception.ChatRejectException;

import java.util.Locale;

/**
 * 模型级别的能力。EMBEDDING / RERANK 归在 RAG 服务下，运行时不参与任何链路，
 * 目前只用于登记与列表展示，等 RAG 自定义模型落地再接线。
 */
public enum ModelType {
    CHAT, IMAGE, EMBEDDING, RERANK;

    public static final ModelType DEFAULT = CHAT;

    public ServiceType serviceType() {
        return switch (this) {
            case CHAT -> ServiceType.CHAT;
            case IMAGE -> ServiceType.IMAGE;
            case EMBEDDING, RERANK -> ServiceType.RAG;
        };
    }

    public boolean isChat() {
        return this == CHAT;
    }

    /**
     * 宽松解析：null / 空白 / 历史脏值一律当 CHAT。
     * 读库路径必须用这个——老数据没有 model_type，抛错会让整个模型目录打不开。
     */
    public static ModelType of(String raw) {
        ModelType type = parse(raw);
        return type == null ? DEFAULT : type;
    }

    /** 严格解析：请求体写了不认识的取值就拒绝，别把一个生图模型静默存成聊天模型。 */
    public static ModelType parseStrict(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        ModelType type = parse(raw);
        if (type == null) {
            throw new ChatRejectException("未知的模型类型: " + raw);
        }
        return type;
    }

    private static ModelType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
