package org.leon.aimodule.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.exception.ChatRejectException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffortResolverTest {

    private static ChatProperties props() {
        ChatProperties p = new ChatProperties();
        ChatProperties.Model chat = new ChatProperties.Model();
        chat.setKey("deepseek-chat");
        chat.setName("deepseek-chat");
        ChatProperties.Model reasoner = new ChatProperties.Model();
        reasoner.setKey("deepseek-reasoner");
        reasoner.setName("deepseek-reasoner");
        reasoner.setSupportsThinking(true);
        p.setModels(new ArrayList<>(List.of(chat, reasoner)));

        String[] keys = {"Low", "Medium", "High", "Extra", "Max"};
        double[] temps = {1.3, 1.0, 0.7, 0.4, 0.2};
        int[] maxTokens = {4096, 4096, 8192, 8192, 16384};
        List<ChatProperties.Effort> efforts = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            ChatProperties.Effort e = new ChatProperties.Effort();
            e.setKey(keys[i]);
            e.setTemperature(temps[i]);
            e.setMaxTokens(maxTokens[i]);
            efforts.add(e);
        }
        p.setEfforts(efforts);
        return p;
    }

    private final EffortResolver resolver = new EffortResolver(props());

    @Test
    @DisplayName("两参数都缺省时落到配置的默认模型与默认档位")
    void fallsBackToDefaults() {
        ModelParams params = resolver.resolve(null, "  ");
        assertEquals("deepseek-chat", params.modelKey());
        assertEquals(1.0, params.temperature());
        assertEquals(4096, params.maxTokens());
    }

    @Test
    @DisplayName("五档解析出的参数两两不同，effort 不改变模型")
    void effortsDifferAndNeverSwitchModel() {
        List<ModelParams> all = new ArrayList<>();
        for (String key : List.of("Low", "Medium", "High", "Extra", "Max")) {
            ModelParams p = resolver.resolve("deepseek-chat", key);
            assertEquals("deepseek-chat", p.modelKey(), "档位不得换模型: " + key);
            all.add(p);
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                assertTrue(all.get(i).temperature().equals(all.get(j).temperature()) == false
                                || all.get(i).maxTokens().equals(all.get(j).maxTokens()) == false,
                        "档位 " + i + " 与 " + j + " 参数完全相同，effort 成了装饰");
            }
        }
    }

    @Test
    @DisplayName("supportsThinking 来自模型而不是档位")
    void thinkingFollowsModelNotEffort() {
        assertTrue(resolver.resolve("deepseek-reasoner", "Low").supportsThinking());
        assertEquals(false, resolver.resolve("deepseek-chat", "Max").supportsThinking());
    }

    @Test
    @DisplayName("未知模型或未知档位一律拒绝，消息里带上非法值")
    void rejectsUnknownKeys() {
        ChatRejectException e1 = assertThrows(ChatRejectException.class,
                () -> resolver.resolve("gpt-5.5", "Medium"));
        assertTrue(e1.getMessage().contains("gpt-5.5"));
        ChatRejectException e2 = assertThrows(ChatRejectException.class,
                () -> resolver.resolve("deepseek-chat", "Ultra"));
        assertTrue(e2.getMessage().contains("Ultra"));
    }
}
