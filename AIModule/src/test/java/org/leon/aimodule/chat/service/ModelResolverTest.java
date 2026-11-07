package org.leon.aimodule.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.provider.ProviderRoute;
import org.leon.aimodule.chat.provider.UserModelCatalogService;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelResolverTest {

    private ChatProperties props;
    private UserModelCatalogService catalog;
    private ModelResolver resolver;

    @BeforeEach
    void setUp() {
        props = new ChatProperties();
        props.setDefaultModel("deepseek-chat");
        ChatProperties.Model m = new ChatProperties.Model();
        m.setKey("deepseek-chat");
        m.setName("deepseek-chat");
        ChatProperties.Model reasoner = new ChatProperties.Model();
        reasoner.setKey("deepseek-reasoner");
        reasoner.setName("deepseek-reasoner");
        reasoner.setSupportsThinking(true);
        props.setModels(new ArrayList<>(List.of(m, reasoner)));
        List<ChatProperties.Effort> efforts = new ArrayList<>();
        ChatProperties.Effort medium = new ChatProperties.Effort();
        medium.setKey("Medium");
        medium.setTemperature(1.0);
        medium.setMaxTokens(4096);
        efforts.add(medium);
        props.setEfforts(efforts);
        catalog = mock(UserModelCatalogService.class);
        resolver = new ModelResolver(props, new EffortResolver(props), catalog);
    }

    @Test
    @DisplayName("内置模型不产生 route")
    void builtinHasNoRoute() {
        ModelResolver.Resolved r = resolver.resolve("alice", "deepseek-chat", "Medium");
        assertEquals("deepseek-chat", r.params().modelKey());
        assertNull(r.route());
    }

    @Test
    @DisplayName("个人模型带 route，params 里的 key 是选择器 key")
    void personalCarriesRoute() {
        when(catalog.resolve(any(), any())).thenReturn(new ProviderRoute(9L, 1L, "中转",
                "小模型", "https://gw.example.com/v1", "sk-x", "gpt-4o-mini", true));
        ModelResolver.Resolved r = resolver.resolve("alice", "u:9:1", "Medium");
        assertEquals("u:9:1", r.params().modelKey());
        assertEquals("gpt-4o-mini", r.route().upstreamModel());
        assertEquals(1.0, r.params().temperature());
        // 模型能力与全局开关相与后为 true
        assertTrue(r.params().supportsThinking());
    }

    @Test
    @DisplayName("用户显式关闭思考时，个人模型也必须关闭")
    void personalRespectsUserThinkingOff() {
        when(catalog.resolve(any(), any())).thenReturn(new ProviderRoute(9L, 1L, "中转",
                "小模型", "https://gw.example.com/v1", "sk-x", "gpt-4o-mini", true));
        ModelResolver.Resolved r = resolver.resolve("alice", "u:9:1", "Medium", false);
        assertTrue(!r.params().supportsThinking());
    }

    @Test
    @DisplayName("个人模型不存在时把拒绝原样抛出，不回落默认模型")
    void personalRejectPropagates() {
        when(catalog.resolve(any(), any())).thenThrow(new ChatRejectException("模型不存在"));
        assertEquals("模型不存在", assertThrows(ChatRejectException.class,
                () -> resolver.resolve("alice", "u:9:99", "Medium")).getMessage());
    }

    @Test
    @DisplayName("档位未知一律拒绝，两条链口径一致")
    void unknownEffortRejected() {
        when(catalog.resolve(any(), any())).thenReturn(new ProviderRoute(9L, 1L, "中转",
                "小模型", "https://gw.example.com/v1", "sk-x", "m", false));
        assertThrows(ChatRejectException.class, () -> resolver.resolve("alice", "u:9:1", "Ultra"));
        assertThrows(ChatRejectException.class, () -> resolver.resolve("alice", "deepseek-chat", "Ultra"));
    }

    @Test
    @DisplayName("模型为空时回落配置默认模型")
    void blankFallsBackToDefault() {
        ModelResolver.Resolved r = resolver.resolve("alice", null, null);
        assertEquals("deepseek-chat", r.params().modelKey());
    }
}
