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

    /** 上游 HTTP 直连参数。值由 yaml 从 spring.ai.openai.* 引过来，key 只有一个真相源。 */
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";

    private boolean thinkingEnabled = true;
    private int thinkingMaxStepChars = 120;
    private int thinkingMaxSteps = 12;
    private int upstreamIdleTimeoutSeconds = 60;

    private OpenAiCompat openaiCompat = new OpenAiCompat();

    /**
     * 桌宠（dsh-pet 独立模式）那条 OpenAI 兼容链的参数。
     * 这条链不经登录 JWT：网关按完整路径放行，凭据由 AIModule 自己校验，
     * 所以 apiKey 留空的语义必须是「路由关闭」，绝不能当成「没配就放行」。
     */
    @Data
    public static class OpenAiCompat {
        /** 服务键，与请求里的 Bearer 做常量时间比对。 */
        private String apiKey = "";
        /** 桌宠的轮次落在哪个账号名下——网页端会话列表里看到的就是这个用户。 */
        private String userName = "";
        /** 固定会话的标题。显式给标题才躲得开 renameIfDefault（它只改「新对话」）。 */
        private String conversationTitle = "桌宠对话";
        /** 这条链的档位；留空回落 chat.default-effort。 */
        private String effort = "";
    }

    /**
     * 能力上报的唯一口径。上一期留过"报 true 却永远发不出内容"的空头旗子，
     * 所以 #1、meta、以及要不要喂 splitter 都必须问这里，不许各算各的。
     */
    public boolean thinkingSupported(boolean modelFlag) {
        return thinkingEnabled && modelFlag;
    }

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
