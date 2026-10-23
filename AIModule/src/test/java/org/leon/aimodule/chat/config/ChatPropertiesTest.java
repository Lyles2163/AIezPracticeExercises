package org.leon.aimodule.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatPropertiesTest {

    @EnableConfigurationProperties(ChatProperties.class)
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class)
            .withPropertyValues(
                    "chat.default-model=deepseek-chat",
                    "chat.default-effort=Medium",
                    "chat.models[0].key=deepseek-chat",
                    "chat.models[0].name=deepseek-chat",
                    "chat.models[0].tag=Fast",
                    "chat.models[0].supports-thinking=false",
                    "chat.models[1].key=deepseek-reasoner",
                    "chat.models[1].name=deepseek-reasoner",
                    "chat.models[1].tag=Reasoning",
                    "chat.models[1].supports-thinking=true",
                    "chat.efforts[0].key=Low",
                    "chat.efforts[0].temperature=1.3",
                    "chat.efforts[0].max-tokens=4096",
                    "chat.efforts[1].key=Medium",
                    "chat.efforts[1].temperature=1.0",
                    "chat.efforts[1].max-tokens=4096");

    @Test
    @DisplayName("chat.models / chat.efforts 列表能按 key 查到，未知 key 返回 null 而不是抛异常")
    void bindsLists() {
        runner.run(ctx -> {
            ChatProperties p = ctx.getBean(ChatProperties.class);
            assertNotNull(p);
            assertEquals(2, p.getModels().size());
            assertTrue(p.model("deepseek-reasoner").isSupportsThinking());
            assertFalse(p.model("deepseek-chat").isSupportsThinking());
            assertEquals("Fast", p.model("deepseek-chat").getTag());
            assertEquals(4096, p.effort("Low").getMaxTokens());
            assertEquals(1.3, p.effort("Low").getTemperature());
            assertNull(p.model("gpt-5.5"));
            assertNull(p.effort("Ultra"));
        });
    }

    @Test
    @DisplayName("流式与预算相关配置有可用默认值")
    void hasDefaults() {
        runner.run(ctx -> {
            ChatProperties p = ctx.getBean(ChatProperties.class);
            assertEquals(8000, p.getContextMaxTokens());
            assertEquals(300_000L, p.getStreamTimeoutMs());
            assertEquals(4, p.getStreamCoreThreads());
            assertEquals(16, p.getStreamMaxThreads());
            assertEquals(15, p.getHeartbeatIntervalSeconds());
            assertEquals(5, p.getStaleStreamReapMinutes());
        });
    }

    @Test
    @DisplayName("五个档位顺序与前端 PromptBar 的 DEFAULT_EFFORTS 逐字一致")
    void effortKeysMatchFrontend() {
        runner.withPropertyValues(
                "chat.efforts[2].key=High",
                "chat.efforts[3].key=Extra",
                "chat.efforts[4].key=Max",
                "chat.efforts[2].temperature=0.7",
                "chat.efforts[2].max-tokens=8192",
                "chat.efforts[3].temperature=0.4",
                "chat.efforts[3].max-tokens=8192",
                "chat.efforts[4].temperature=0.2",
                "chat.efforts[4].max-tokens=16384")
                .run(ctx -> {
                    ChatProperties p = ctx.getBean(ChatProperties.class);
                    assertEquals(List.of("Low", "Medium", "High", "Extra", "Max"),
                            p.getEfforts().stream().map(ChatProperties.Effort::getKey).toList());
                });
    }

    @Test
    @DisplayName("application.yaml 里的 chat: 段能原样绑出五档，防止 yaml 与前端数组漂移")
    void bindsFromApplicationYaml() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
                .forEach(env.getPropertySources()::addLast);
        ChatProperties p = Binder.get(env).bind("chat", ChatProperties.class).get();

        assertEquals(List.of("Low", "Medium", "High", "Extra", "Max"),
                p.getEfforts().stream().map(ChatProperties.Effort::getKey).toList());
        assertEquals(List.of(1.3, 1.0, 0.7, 0.4, 0.2),
                p.getEfforts().stream().map(ChatProperties.Effort::getTemperature).toList());
        assertEquals(List.of(4096, 4096, 8192, 8192, 16384),
                p.getEfforts().stream().map(ChatProperties.Effort::getMaxTokens).toList());
        assertEquals(List.of("deepseek-chat", "deepseek-reasoner"),
                p.getModels().stream().map(ChatProperties.Model::getKey).toList());
        assertEquals(List.of("Fast", "Reasoning"),
                p.getModels().stream().map(ChatProperties.Model::getTag).toList());
        assertFalse(p.model("deepseek-chat").isSupportsThinking());
        assertTrue(p.model("deepseek-reasoner").isSupportsThinking());
        assertEquals("deepseek-chat", p.getDefaultModel());
        assertEquals("Medium", p.getDefaultEffort());
    }
}
