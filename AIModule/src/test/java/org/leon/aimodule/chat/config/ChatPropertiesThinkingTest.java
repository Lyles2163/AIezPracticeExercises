package org.leon.aimodule.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.service.EffortResolver;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatPropertiesThinkingTest {

    private static ChatProperties props(boolean thinkingEnabled) {
        ChatProperties p = new ChatProperties();
        ChatProperties.Model chat = new ChatProperties.Model();
        chat.setKey("deepseek-chat");
        chat.setName("deepseek-chat");
        ChatProperties.Model reasoner = new ChatProperties.Model();
        reasoner.setKey("deepseek-reasoner");
        reasoner.setName("deepseek-reasoner");
        reasoner.setSupportsThinking(true);
        p.setModels(new ArrayList<>(List.of(chat, reasoner)));
        p.setEfforts(new ArrayList<>(List.of(effort("Medium"), effort("Max"))));
        p.setThinkingEnabled(thinkingEnabled);
        return p;
    }

    private static ChatProperties.Effort effort(String key) {
        ChatProperties.Effort e = new ChatProperties.Effort();
        e.setKey(key);
        e.setTemperature(1.0);
        e.setMaxTokens(4096);
        return e;
    }

    @Test
    @DisplayName("默认值：思考线开着，上限 120 字符 / 12 步，上游空闲 60 秒")
    void defaults() {
        ChatProperties p = new ChatProperties();
        assertTrue(p.isThinkingEnabled());
        assertEquals(120, p.getThinkingMaxStepChars());
        assertEquals(12, p.getThinkingMaxSteps());
        assertEquals(60, p.getUpstreamIdleTimeoutSeconds());
    }

    @Test
    @DisplayName("thinkingSupported 是全局开关与模型能力的与")
    void thinkingSupportedIsAnd() {
        ChatProperties on = props(true);
        assertTrue(on.thinkingSupported(true));
        assertFalse(on.thinkingSupported(false));
        ChatProperties off = props(false);
        assertFalse(off.thinkingSupported(true), "全局关掉后，reasoner 也不能上报支持思考");
    }

    @Test
    @DisplayName("ModelParams 的 supportsThinking 走同一口径：全局关掉后 meta 也必须报 false")
    void resolverUsesTheSameGate() {
        assertFalse(new EffortResolver(props(false))
                .resolve("deepseek-reasoner", "Medium").supportsThinking());
        assertTrue(new EffortResolver(props(true))
                .resolve("deepseek-reasoner", "Medium").supportsThinking());
    }
}
