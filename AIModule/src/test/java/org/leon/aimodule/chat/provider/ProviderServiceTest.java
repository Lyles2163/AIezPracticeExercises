package org.leon.aimodule.chat.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderServiceTest {

    private AiUserModelProviderMapper providerMapper;
    private AiUserModelMapper modelMapper;
    private ProviderProperties props;
    private ProviderService service;
    private ProviderProbe probe;

    @BeforeEach
    void setUp() {
        providerMapper = mock(AiUserModelProviderMapper.class);
        modelMapper = mock(AiUserModelMapper.class);
        probe = mock(ProviderProbe.class);
        props = new ProviderProperties();
        props.setEncryptionKey("0123456789abcdef0123456789abcdef");
        service = new ProviderService(providerMapper, modelMapper,
                new ApiKeyCryptoService(props), props, probe);
    }

    @Test
    @DisplayName("新增供应商：baseUrl 归一化、key 只存密文、返回体只给掩码")
    void createEncryptsAndMasks() {
        when(providerMapper.selectCount(any())).thenReturn(0L);
        when(providerMapper.insert(any(AiUserModelProvider.class))).thenAnswer(inv -> {
            inv.getArgument(0, AiUserModelProvider.class).setId(5L);
            return 1;
        });
        when(modelMapper.selectList(any())).thenReturn(List.of());

        ProviderViews.Provider vo = service.create("alice", new ProviderRequests.CreateProvider(
                "我的中转", "https://api.example.com/", "sk-abcdefghijkl", null,
                List.of(new ProviderRequests.ModelInput("gpt-4o-mini", "小模型", false, null))));

        ArgumentCaptor<AiUserModelProvider> cap = ArgumentCaptor.forClass(AiUserModelProvider.class);
        verify(providerMapper).insert(cap.capture());
        AiUserModelProvider saved = cap.getValue();
        assertEquals("alice", saved.getUserName());
        assertEquals("https://api.example.com/v1", saved.getBaseUrl());
        assertEquals("OPENAI_COMPAT", saved.getProtocol());
        assertEquals("CHAT", saved.getServiceTypes());
        assertFalse(saved.getApiKeyCiphertext().contains("sk-abcdefghijkl"));
        assertEquals("sk-a...ijkl", vo.maskedKey());
        assertEquals(List.of("CHAT"), vo.serviceTypes());
        assertTrue(vo.configured());
        verify(modelMapper).insert(any(AiUserModel.class));
    }

    @Test
    @DisplayName("供应商数量到上限直接拒绝，不落库")
    void createRespectsQuota() {
        when(providerMapper.selectCount(any())).thenReturn(10L);
        assertThrows(ChatRejectException.class, () -> service.create("alice",
                new ProviderRequests.CreateProvider("p", "https://api.example.com", "k", null,
                        List.of(new ProviderRequests.ModelInput("m", "m", false, null)))));
        verify(providerMapper, never()).insert(any(AiUserModelProvider.class));
    }

    @Test
    @DisplayName("跨用户操作一律当作不存在")
    void ownershipIsEnforced() {
        when(providerMapper.selectOne(any())).thenReturn(null);
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> service.update("alice", 9L,
                        new ProviderRequests.UpdateProvider("p", "https://api.example.com", null, null, 1)));
        assertEquals("供应商不存在", e.getMessage());
        assertThrows(ChatRejectException.class, () -> service.delete("alice", 9L));
        assertThrows(ChatRejectException.class,
                () -> service.addModel("alice", 9L, new ProviderRequests.ModelInput("m", "m", false, null)));
        verify(providerMapper, never()).updateById(any(AiUserModelProvider.class));
    }

    @Test
    @DisplayName("更新时不传 apiKey 就保留原密文")
    void updateKeepsKeyWhenBlank() {
        AiUserModelProvider existing = provider(3L, "alice", 1);
        existing.setApiKeyCiphertext("cipher");
        existing.setApiKeyNonce("nonce");
        existing.setApiKeyFingerprint("fp");
        when(providerMapper.selectOne(any())).thenReturn(existing);
        when(modelMapper.selectList(any())).thenReturn(List.of());

        service.update("alice", 3L,
                new ProviderRequests.UpdateProvider("新名字", "https://api2.example.com", "  ", null, 0));

        ArgumentCaptor<AiUserModelProvider> cap = ArgumentCaptor.forClass(AiUserModelProvider.class);
        verify(providerMapper).updateById(cap.capture());
        assertEquals("cipher", cap.getValue().getApiKeyCiphertext());
        assertEquals("https://api2.example.com/v1", cap.getValue().getBaseUrl());
        assertEquals(0, cap.getValue().getStatus());
    }

    @Test
    @DisplayName("删除是软删：供应商与它的模型都置 0")
    void deleteIsSoft() {
        when(providerMapper.selectOne(any())).thenReturn(provider(3L, "alice", 1));
        when(modelMapper.selectList(any())).thenReturn(List.of());

        service.delete("alice", 3L);

        ArgumentCaptor<AiUserModelProvider> cap = ArgumentCaptor.forClass(AiUserModelProvider.class);
        verify(providerMapper).updateById(cap.capture());
        assertEquals(0, cap.getValue().getStatus());
        verify(modelMapper).update(any(AiUserModel.class), any());
    }

    @Test
    @DisplayName("停用的供应商不能再加模型")
    void disabledProviderRejectsModel() {
        when(providerMapper.selectOne(any())).thenReturn(provider(3L, "alice", 0));
        assertThrows(ChatRejectException.class,
                () -> service.addModel("alice", 3L, new ProviderRequests.ModelInput("m", "m", false, null)));
        verify(modelMapper, never()).insert(any(AiUserModel.class));
    }

    @Test
    @DisplayName("测试连接：用第一个启用模型与该供应商的 key 探活")
    void testConnectionUsesFirstEnabledModel() {
        AiUserModelProvider existing = provider(3L, "alice", 1);
        AiUserModelProvider saved = existing;
        ApiKeyCryptoService crypto = new ApiKeyCryptoService(props);
        ApiKeyCryptoService.Encrypted enc = crypto.encrypt("sk-test-key-1234");
        saved.setApiKeyCiphertext(enc.ciphertext());
        saved.setApiKeyNonce(enc.nonce());
        when(providerMapper.selectOne(any())).thenReturn(saved);
        when(modelMapper.selectList(any())).thenReturn(List.of(
                model(2L, 3L, "alice", "disabled-model", 0),
                model(1L, 3L, "alice", "gpt-4o-mini", 1)));
        when(probe.probe(any(), any(), any())).thenReturn(new ProviderProbe.Result(true, 12L, "连接成功"));

        ProviderProbe.Result result = service.testConnection("alice", 3L);

        assertTrue(result.ok());
        verify(probe).probe("https://gw.example.com/v1", "sk-test-key-1234", "gpt-4o-mini");
    }

    @Test
    @DisplayName("测试连接：没有启用模型时直接拒绝")
    void testConnectionRequiresModel() {
        when(providerMapper.selectOne(any())).thenReturn(provider(3L, "alice", 1));
        when(modelMapper.selectList(any())).thenReturn(List.of());
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> service.testConnection("alice", 3L));
        assertEquals("请先添加至少一个模型", e.getMessage());
        verify(probe, never()).probe(any(), any(), any());
    }

    @Test
    @DisplayName("生图能力：模型类型不在供应商声明内直接拒绝")
    void modelTypeMustBeDeclared() {
        when(providerMapper.selectOne(any())).thenReturn(provider(3L, "alice", 1));
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> service.addModel("alice", 3L,
                        new ProviderRequests.ModelInput("dall-e-3", "画图", false, "IMAGE")));
        assertEquals("该供应商未声明生图服务", e.getMessage());
        verify(modelMapper, never()).insert(any(AiUserModel.class));
    }

    @Test
    @DisplayName("声明 IMAGE 后可以加生图模型，类型落库成大写")
    void imageModelNeedsImageCapability() {
        when(providerMapper.selectOne(any())).thenReturn(provider(3L, "alice", 1, "CHAT,IMAGE"));
        when(modelMapper.insert(any(AiUserModel.class))).thenAnswer(inv -> 1);

        ProviderViews.Model vo = service.addModel("alice", 3L,
                new ProviderRequests.ModelInput("dall-e-3", "画图", false, "image"));

        assertEquals("IMAGE", vo.modelType());
    }

    @Test
    @DisplayName("取消能力时，已有模型还挂着就拒绝")
    void cannotDropCapabilityStillInUse() {
        when(providerMapper.selectOne(any())).thenReturn(provider(3L, "alice", 1, "CHAT,IMAGE"));
        AiUserModel imageModel = model(7L, 3L, "alice", "dall-e-3", 1);
        imageModel.setModelType("IMAGE");
        when(modelMapper.selectList(any())).thenReturn(List.of(imageModel));

        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> service.update("alice", 3L, new ProviderRequests.UpdateProvider(
                        "p", "https://gw.example.com", null, List.of("CHAT"), 1)));

        assertEquals("该供应商下还有生图模型，不能取消生图能力", e.getMessage());
        verify(providerMapper, never()).updateById(any(AiUserModelProvider.class));
    }

    @Test
    @DisplayName("不传 serviceTypes 时保持原有能力，不会被打回 CHAT")
    void updateKeepsServiceTypesWhenOmitted() {
        AiUserModelProvider existing = provider(3L, "alice", 1, "CHAT,IMAGE");
        existing.setApiKeyCiphertext("cipher");
        existing.setApiKeyNonce("nonce");
        when(providerMapper.selectOne(any())).thenReturn(existing);
        when(modelMapper.selectList(any())).thenReturn(List.of());

        service.update("alice", 3L,
                new ProviderRequests.UpdateProvider("p", "https://gw.example.com", null, null, 1));

        ArgumentCaptor<AiUserModelProvider> cap = ArgumentCaptor.forClass(AiUserModelProvider.class);
        verify(providerMapper).updateById(cap.capture());
        assertEquals("CHAT,IMAGE", cap.getValue().getServiceTypes());
    }

    @Test
    @DisplayName("生图模型探活走 /models，不发一次真生成")
    void imageProviderProbesModelsEndpoint() {
        AiUserModelProvider existing = provider(3L, "alice", 1, "IMAGE");
        ApiKeyCryptoService.Encrypted enc = new ApiKeyCryptoService(props).encrypt("sk-img-key");
        existing.setApiKeyCiphertext(enc.ciphertext());
        existing.setApiKeyNonce(enc.nonce());
        when(providerMapper.selectOne(any())).thenReturn(existing);
        AiUserModel imageModel = model(1L, 3L, "alice", "dall-e-3", 1);
        imageModel.setModelType("IMAGE");
        when(modelMapper.selectList(any())).thenReturn(List.of(imageModel));
        when(probe.probeModels(any(), any())).thenReturn(new ProviderProbe.Result(true, 8L, "连接成功"));

        assertTrue(service.testConnection("alice", 3L).ok());

        verify(probe).probeModels("https://gw.example.com/v1", "sk-img-key");
        verify(probe, never()).probe(any(), any(), any());
    }

    private static AiUserModelProvider provider(Long id, String user, int status) {
        return provider(id, user, status, "CHAT");
    }

    private static AiUserModelProvider provider(Long id, String user, int status, String serviceTypes) {
        AiUserModelProvider p = new AiUserModelProvider();
        p.setId(id);
        p.setUserName(user);
        p.setProviderName("中转");
        p.setProtocol("OPENAI_COMPAT");
        p.setBaseUrl("https://gw.example.com/v1");
        p.setServiceTypes(serviceTypes);
        p.setStatus(status);
        return p;
    }

    private static AiUserModel model(Long id, Long providerId, String user, String key, int status) {
        AiUserModel m = new AiUserModel();
        m.setId(id);
        m.setProviderId(providerId);
        m.setUserName(user);
        m.setModelKey(key);
        m.setDisplayName(key);
        m.setStatus(status);
        return m;
    }
}
