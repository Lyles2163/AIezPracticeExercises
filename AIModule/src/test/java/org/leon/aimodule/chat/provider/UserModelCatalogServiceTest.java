package org.leon.aimodule.chat.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.exception.ChatRejectException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserModelCatalogServiceTest {

    private AiUserModelProviderMapper providerMapper;
    private AiUserModelMapper modelMapper;
    private ApiKeyCryptoService crypto;
    private UserModelCatalogService service;

    @BeforeEach
    void setUp() {
        providerMapper = mock(AiUserModelProviderMapper.class);
        modelMapper = mock(AiUserModelMapper.class);
        ProviderProperties props = new ProviderProperties();
        props.setEncryptionKey("0123456789abcdef0123456789abcdef");
        crypto = new ApiKeyCryptoService(props);
        service = new UserModelCatalogService(providerMapper, modelMapper, crypto);
    }

    @Test
    @DisplayName("目录只收启用供应商下的启用模型，key 是 u:{p}:{m}")
    void choicesSkipDisabled() {
        when(providerMapper.selectList(any())).thenReturn(List.of(
                provider(9L, "alice", 1), provider(10L, "alice", 0)));
        when(modelMapper.selectList(any())).thenReturn(List.of(
                model(1L, 9L, "alice", "gpt-4o-mini", "小模型", 1),
                model(2L, 9L, "alice", "gpt-4o", "大模型", 0),
                model(3L, 10L, "alice", "ghost", "停用供应商的模型", 1)));

        List<UserModelCatalogService.Choice> choices = service.choices("alice");

        assertEquals(1, choices.size());
        assertEquals("u:9:1", choices.get(0).selectionKey());
        assertEquals("小模型", choices.get(0).displayName());
    }

    @Test
    @DisplayName("没有供应商时目录为空，不查模型表")
    void choicesEmptyWithoutProviders() {
        when(providerMapper.selectList(any())).thenReturn(List.of());
        assertTrue(service.choices("alice").isEmpty());
    }

    @Test
    @DisplayName("resolve 拿到解密后的 key 与上游模型名，且 toString 不含 key")
    void resolveDecryptsKey() {
        AiUserModelProvider provider = provider(9L, "alice", 1);
        ApiKeyCryptoService.Encrypted enc = crypto.encrypt("sk-real-key");
        provider.setApiKeyCiphertext(enc.ciphertext());
        provider.setApiKeyNonce(enc.nonce());
        when(providerMapper.selectOne(any())).thenReturn(provider);
        when(modelMapper.selectOne(any())).thenReturn(model(1L, 9L, "alice", "gpt-4o-mini", "小模型", 1));

        ProviderRoute route = service.resolve("alice", "u:9:1");

        assertEquals("https://gw.example.com/v1", route.baseUrl());
        assertEquals("sk-real-key", route.apiKey());
        assertEquals("gpt-4o-mini", route.upstreamModel());
        assertFalse(route.supportsTools());
        assertFalse(route.toString().contains("sk-real-key"));
    }

    @Test
    @DisplayName("别人的模型、停用的模型、脏 key 一律按不存在拒绝")
    void resolveRejectsEverythingElse() {
        when(providerMapper.selectOne(any())).thenReturn(null);
        assertEquals("模型不存在",
                assertThrows(ChatRejectException.class, () -> service.resolve("alice", "u:9:1")).getMessage());
        assertEquals("模型不存在",
                assertThrows(ChatRejectException.class, () -> service.resolve("alice", "deepseek-chat")).getMessage());
    }

    @Test
    @DisplayName("生图目录只收 IMAGE 模型，聊天目录不会混进来")
    void imageChoicesOnlyImages() {
        when(providerMapper.selectList(any())).thenReturn(List.of(
                provider(9L, "alice", 1, "CHAT,IMAGE"), provider(10L, "alice", 1, "CHAT")));
        when(modelMapper.selectList(any())).thenReturn(List.of(
                model(1L, 9L, "alice", "gpt-4o-mini", "小模型", "CHAT", 1),
                model(2L, 9L, "alice", "dall-e-3", "画图", "IMAGE", 1),
                model(3L, 10L, "alice", "dall-e-2", "没声明生图能力", "IMAGE", 1)));

        assertEquals(List.of("u:9:2"),
                service.imageChoices("alice").stream().map(UserModelCatalogService.Choice::selectionKey).toList());
        assertEquals(List.of("u:9:1"),
                service.choices("alice").stream().map(UserModelCatalogService.Choice::selectionKey).toList());
    }

    @Test
    @DisplayName("拿生图模型去聊天、拿聊天模型去生图，都按不存在拒绝")
    void resolveIsTypeAware() {
        AiUserModelProvider provider = provider(9L, "alice", 1, "CHAT,IMAGE");
        ApiKeyCryptoService.Encrypted enc = crypto.encrypt("sk-real-key");
        provider.setApiKeyCiphertext(enc.ciphertext());
        provider.setApiKeyNonce(enc.nonce());
        when(providerMapper.selectOne(any())).thenReturn(provider);
        when(modelMapper.selectOne(any())).thenReturn(
                model(1L, 9L, "alice", "gpt-4o-mini", "小模型", "CHAT", 1));

        assertEquals("模型不存在",
                assertThrows(ChatRejectException.class, () -> service.resolveImage("alice", "u:9:1")).getMessage());
        assertEquals("gpt-4o-mini", service.resolve("alice", "u:9:1").upstreamModel());
    }

    private static AiUserModelProvider provider(Long id, String user, int status) {
        return provider(id, user, status, "CHAT");
    }

    private static AiUserModelProvider provider(Long id, String user, int status, String serviceTypes) {
        AiUserModelProvider p = new AiUserModelProvider();
        p.setId(id);
        p.setUserName(user);
        p.setProviderName(user + " 的中转");
        p.setProtocol("OPENAI_COMPAT");
        p.setBaseUrl("https://gw.example.com/v1");
        p.setServiceTypes(serviceTypes);
        p.setApiKeyCiphertext("x");
        p.setApiKeyNonce("y");
        p.setStatus(status);
        return p;
    }

    private static AiUserModel model(Long id, Long providerId, String user, String key,
                                     String display, int status) {
        return model(id, providerId, user, key, display, "CHAT", status);
    }

    private static AiUserModel model(Long id, Long providerId, String user, String key,
                                     String display, String modelType, int status) {
        AiUserModel m = new AiUserModel();
        m.setId(id);
        m.setProviderId(providerId);
        m.setUserName(user);
        m.setModelKey(key);
        m.setDisplayName(display);
        m.setModelType(modelType);
        m.setSupportsThinking(false);
        m.setStatus(status);
        return m;
    }
}
