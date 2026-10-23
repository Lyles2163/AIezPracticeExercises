package org.leon.aimodule.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * chat: 段的配置绑定。默认值即"不写 yaml 也能跑"的兜底，
 * yaml 里的值会覆盖它们。
 */
@Data
@Component
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

    private String defaultModel = "deepseek-chat";
    private String defaultEffort = "Medium";
    private List<Model> models = new ArrayList<>();
    private List<Effort> efforts = new ArrayList<>();
    private String systemPrompt = "";
    private int contextMaxTokens = 8000;
    private long streamTimeoutMs = 300_000L;
    private int streamCoreThreads = 4;
    private int streamMaxThreads = 16;
    private int heartbeatIntervalSeconds = 15;
    private int staleStreamReapMinutes = 5;

    public Model model(String key) {
        if (key == null) {
            return null;
        }
        return models.stream().filter(m -> key.equals(m.getKey())).findFirst().orElse(null);
    }

    public Effort effort(String key) {
        if (key == null) {
            return null;
        }
        return efforts.stream().filter(e -> key.equals(e.getKey())).findFirst().orElse(null);
    }

    @Data
    public static class Model {
        private String key;
        private String name;
        private String tag = "";
        private boolean supportsThinking = false;
    }

    @Data
    public static class Effort {
        private String key;
        private Double temperature;
        private Integer maxTokens;
    }
}
