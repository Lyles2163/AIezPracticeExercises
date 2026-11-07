# 用户自建 AI 供应商（后端）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在设置页添加自己的 OpenAI 兼容供应商与 API Key，存进个人库，并在聊天页切换使用。

**Architecture:** 两张表（`ai_user_model_provider` / `ai_user_model`）已按设计建好并核对过列名；新增 `org.leon.aimodule.chat.provider` 包做 CRUD、AES-GCM 密钥加密、baseUrl 安全校验与连通性探测；聊天链在 `prepare` 阶段把选择器 key（`u:{providerId}:{modelId}`）解析成 `ProviderRoute`（用户自己的 baseUrl + 解密后的 key + 上游模型名），随 `PreparedTurn` 进入流式调用，内置模型仍走服务端配置。

**Tech Stack:** Spring Boot 3.2 + MyBatis-Plus 3.5.17 + WebClient（流式）+ JDK HttpClient（探测）+ JUnit 5 + Mockito

**已就绪（本计划之前已提交到工作区）：**

- 表：`ai_user_model_provider`（id/user_name/provider_name/protocol/base_url/api_key_ciphertext/api_key_nonce/api_key_fingerprint/status/create_time/update_time）、`ai_user_model`（id/provider_id/user_name/model_key/display_name/supports_thinking/status/create_time/update_time），已用 jshell+JDBC 从 information_schema 逐列核对。
- `AIModule/src/main/java/org/leon/aimodule/chat/provider/` 下已有：`AiUserModelProvider`、`AiUserModel`、两个 `BaseMapper`、`ApiKeyCryptoService`、`BaseUrlGuard`、`ProviderProperties`、`ProviderRequests`、`ProviderViews`、`ProviderRoute`、`ProviderService`。
- 测试：`AIModule/src/test/java/org/leon/aimodule/chat/provider/ApiKeyCryptoServiceTest`。

**Maven 命令（PowerShell，仓库根目录执行）：**

```powershell
$MVN = "C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd"
& $MVN -pl AIModule -am -DskipTests compile
& $MVN -pl AIModule -am test -Dtest=ProviderServiceTest
```

---

## Task 1: 打通配置与加密依赖

**Files:**
- Modify: `AIModule/src/main/resources/application.yaml`（`chat:` 段尾追加 `provider:` 子树）

- [ ] **Step 1: 在 `application.yaml` 的 `chat:` 下追加**

```yaml
  # 用户自建供应商。主密钥只从环境变量/Nacos 注入（变量名 EZ_PROVIDER_ENCRYPTION_KEY），
  # 留空时加解密直接失败、该功能整体不可用，绝不退化成明文存储。
  provider:
    allow-http: false
    encryption-key: ${EZ_PROVIDER_ENCRYPTION_KEY:}
    probe-timeout-seconds: 15
    max-providers-per-user: 10
    max-models-per-provider: 30
```

- [ ] **Step 2: 本地密钥文件补一行（`config/local.properties`，已 gitignore）**

```properties
EZ_PROVIDER_ENCRYPTION_KEY=换成你自己的32位以上随机串
```

- [ ] **Step 3: 编译**

Run: `& $MVN -pl AIModule -am -DskipTests compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: 提交（用户确认后再执行）**

```bash
git add AIModule/src/main/resources/application.yaml
git commit -m "chore: 增加用户自建供应商配置项"
```

---

## Task 2: BaseUrlGuard 单测补齐

**Files:**
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/provider/BaseUrlGuardTest.java`

- [ ] **Step 1: 写测试**

```java
package org.leon.aimodule.chat.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.exception.ChatRejectException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaseUrlGuardTest {

    @Test
    @DisplayName("没有 /v1 时补上，尾斜杠被去掉")
    void normalizesToV1() {
        assertEquals("https://api.example.com/v1", BaseUrlGuard.normalize("https://api.example.com/", false));
        assertEquals("https://api.example.com/v1", BaseUrlGuard.normalize("https://api.example.com", false));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                BaseUrlGuard.normalize("https://dashscope.aliyuncs.com/compatible-mode/v1/", false));
    }

    @Test
    @DisplayName("端口保留")
    void keepsPort() {
        assertEquals("https://gw.example.com:8443/v1",
                BaseUrlGuard.normalize("https://gw.example.com:8443", false));
    }

    @Test
    @DisplayName("http 默认拒绝，开关打开才放行非内网地址")
    void httpRequiresFlag() {
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> BaseUrlGuard.normalize("http://api.example.com", false));
        assertEquals(BaseUrlGuard.HTTP_FORBIDDEN, e.getMessage());
        assertEquals("http://api.example.com/v1",
                BaseUrlGuard.normalize("http://api.example.com", true));
    }

    @Test
    @DisplayName("本机与内网地址一律拒绝")
    void blocksPrivateTargets() {
        for (String url : new String[]{
                "https://localhost/v1", "https://127.0.0.1/v1", "https://10.0.0.8/v1",
                "https://192.168.1.2/v1", "https://172.20.0.1/v1", "https://169.254.169.254/latest",
                "https://100.64.0.1/v1", "https://[::1]/v1", "https://svc.internal/v1",
                "https://box.local/v1"}) {
            assertEquals(BaseUrlGuard.NOT_ALLOWED,
                    assertThrows(ChatRejectException.class, () -> BaseUrlGuard.normalize(url, false)).getMessage(),
                    url);
        }
    }

    @Test
    @DisplayName("带凭据或非 http(s) 的一律不合法")
    void rejectsMalformed() {
        assertEquals(BaseUrlGuard.INVALID,
                assertThrows(ChatRejectException.class,
                        () -> BaseUrlGuard.normalize("https://user:pass@api.example.com", false)).getMessage());
        assertEquals(BaseUrlGuard.INVALID,
                assertThrows(ChatRejectException.class,
                        () -> BaseUrlGuard.normalize("file:///etc/passwd", false)).getMessage());
        assertEquals(BaseUrlGuard.INVALID,
                assertThrows(ChatRejectException.class, () -> BaseUrlGuard.normalize("  ", false)).getMessage());
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `& $MVN -pl AIModule -am test -Dtest=BaseUrlGuardTest`
Expected: PASS（5 个用例）。若 `https://[::1]/v1` 在 JDK 里解析成 host `[::1]`，`isBlockedHost` 已按带括号的形式处理。

---

## Task 3: 模型选择器 key 编解码

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/provider/ModelSelection.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/provider/ModelSelectionTest.java`

- [ ] **Step 1: 写测试**

```java
package org.leon.aimodule.chat.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSelectionTest {

    @Test
    @DisplayName("内置模型 key 不是个人模型")
    void builtinIsNotPersonal() {
        assertFalse(ModelSelection.isPersonal("deepseek-chat"));
        assertFalse(ModelSelection.isPersonal(null));
        assertNull(ModelSelection.parse("deepseek-chat"));
    }

    @Test
    @DisplayName("个人模型 key 往返一致，且长度不超 ai_conversation.model_key 的 64")
    void roundTrip() {
        String key = ModelSelection.key(12L, 34L);
        assertEquals("u:12:34", key);
        assertTrue(key.length() <= 64);
        assertTrue(ModelSelection.isPersonal(key));
        assertEquals(12L, ModelSelection.parse(key).providerId());
        assertEquals(34L, ModelSelection.parse(key).modelId());
    }

    @Test
    @DisplayName("脏 key 解析为 null 而不是抛异常")
    void malformedParsesToNull() {
        assertNull(ModelSelection.parse("u:"));
        assertNull(ModelSelection.parse("u:1"));
        assertNull(ModelSelection.parse("u:a:b"));
        assertNull(ModelSelection.parse("u:1:2:3"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `& $MVN -pl AIModule -am test -Dtest=ModelSelectionTest`
Expected: 编译失败，`ModelSelection` 不存在

- [ ] **Step 3: 实现**

```java
package org.leon.aimodule.chat.provider;

/**
 * 模型选择器 key。个人模型的 key 是 u:{providerId}:{modelId}，
 * 内置模型的 key 是配置里的 vendor/model 名，两者不会撞。
 * 这个 key 会落进 ai_conversation.model_key / ai_message.model_key（VARCHAR(64)），
 * 所以格式必须紧凑、可逆，且绝不包含 apiKey。
 */
public final class ModelSelection {

    static final String PREFIX = "u:";

    private ModelSelection() {
    }

    public static String key(Long providerId, Long modelId) {
        return PREFIX + providerId + ":" + modelId;
    }

    public static boolean isPersonal(String key) {
        return key != null && key.trim().startsWith(PREFIX);
    }

    public static Ids parse(String key) {
        if (!isPersonal(key)) {
            return null;
        }
        String[] parts = key.trim().substring(PREFIX.length()).split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new Ids(Long.valueOf(parts[0]), Long.valueOf(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record Ids(Long providerId, Long modelId) {
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `& $MVN -pl AIModule -am test -Dtest=ModelSelectionTest`
Expected: PASS

---

## Task 4: ProviderService 增删改查 + 归属校验

**Files:**
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/provider/ProviderService.java`（已存在，补 `ProviderProbe` 依赖与 `testConnection`）
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/provider/ProviderProbe.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/provider/ProviderServiceTest.java`

- [ ] **Step 1: 先写探测组件**

```java
package org.leon.aimodule.chat.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * "测试连接"：用一个 max_tokens=1 的非流式请求探活。
 * 不列 /models：不少兼容端点不实现它，但 /chat/completions 一定实现。
 * 失败原因只回短文案 + 上游错误体前 200 字符，绝不回请求头。
 */
@Slf4j
@Component
public class ProviderProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderProperties properties;

    public ProviderProbe(ProviderProperties properties) {
        this.properties = properties;
    }

    public Result probe(String baseUrl, String apiKey, String model) {
        long start = System.nanoTime();
        try {
            Duration timeout = Duration.ofSeconds(properties.getProbeTimeoutSeconds());
            HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
            String body = MAPPER.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 1,
                    "stream", false));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long ms = elapsed(start);
            if (response.statusCode() / 100 == 2) {
                return new Result(true, ms, "连接成功");
            }
            return new Result(false, ms, "上游返回 " + response.statusCode() + ": " + truncate(response.body()));
        } catch (Exception e) {
            log.warn("[provider] 探测失败 baseUrl={} err={}", baseUrl, e.getClass().getSimpleName());
            return new Result(false, elapsed(start), "连接失败: " + e.getClass().getSimpleName());
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    public record Result(boolean ok, long latencyMs, String message) {
    }
}
```

- [ ] **Step 2: 给 `ProviderService` 加探测入口**

构造函数追加第 5 个参数 `ProviderProbe probe` 并赋值给字段；新增方法：

```java
    public ProviderProbe.Result testConnection(String userName, Long providerId) {
        AiUserModelProvider provider = ownedProvider(userName, providerId);
        List<AiUserModel> enabled = modelsOf(userName, providerId).stream()
                .filter(m -> m.getStatus() != null && m.getStatus() == 1)
                .toList();
        if (enabled.isEmpty()) {
            throw new ChatRejectException("请先添加至少一个模型");
        }
        String apiKey = crypto.decrypt(new ApiKeyCryptoService.Encrypted(
                provider.getApiKeyCiphertext(), provider.getApiKeyNonce()));
        return probe.probe(provider.getBaseUrl(), apiKey, enabled.get(0).getModelKey());
    }
```

- [ ] **Step 3: 给 `ProviderViews` 补测试要用的访问器**

无需改动，`Provider`/`Model` 已是 record，测试可直接读组件。

- [ ] **Step 4: 写 ProviderService 测试**

```java
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderServiceTest {

    private AiUserModelProviderMapper providerMapper;
    private AiUserModelMapper modelMapper;
    private ProviderService service;

    @BeforeEach
    void setUp() {
        providerMapper = mock(AiUserModelProviderMapper.class);
        modelMapper = mock(AiUserModelMapper.class);
        ProviderProperties props = new ProviderProperties();
        props.setEncryptionKey("0123456789abcdef0123456789abcdef");
        service = new ProviderService(providerMapper, modelMapper,
                new ApiKeyCryptoService(props), props, mock(ProviderProbe.class));
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
                "我的中转", "https://api.example.com/", "sk-abcdefghijkl",
                List.of(new ProviderRequests.ModelInput("gpt-4o-mini", "小模型", false))));

        ArgumentCaptor<AiUserModelProvider> cap = ArgumentCaptor.forClass(AiUserModelProvider.class);
        verify(providerMapper).insert(cap.capture());
        AiUserModelProvider saved = cap.getValue();
        assertEquals("alice", saved.getUserName());
        assertEquals("https://api.example.com/v1", saved.getBaseUrl());
        assertEquals("OPENAI_COMPAT", saved.getProtocol());
        assertFalse(saved.getApiKeyCiphertext().contains("sk-abcdefghijkl"));
        assertEquals(64, saved.getApiKeyFingerprint().length());
        assertEquals("sk-a...ijkl", vo.maskedKey());
        assertTrue(vo.configured());
        verify(modelMapper).insert(any(AiUserModel.class));
    }

    @Test
    @DisplayName("供应商数量到上限直接拒绝，不落库")
    void createRespectsQuota() {
        when(providerMapper.selectCount(any())).thenReturn(10L);
        assertThrows(ChatRejectException.class, () -> service.create("alice",
                new ProviderRequests.CreateProvider("p", "https://api.example.com", "k",
                        List.of(new ProviderRequests.ModelInput("m", "m", false)))));
        verify(providerMapper, never()).insert(any(AiUserModelProvider.class));
    }

    @Test
    @DisplayName("跨用户操作一律当作不存在")
    void ownershipIsEnforced() {
        when(providerMapper.selectOne(any())).thenReturn(null);
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> service.update("alice", 9L,
                        new ProviderRequests.UpdateProvider("p", "https://api.example.com", null, 1)));
        assertEquals("供应商不存在", e.getMessage());
        assertThrows(ChatRejectException.class, () -> service.delete("alice", 9L));
        assertThrows(ChatRejectException.class,
                () -> service.addModel("alice", 9L, new ProviderRequests.ModelInput("m", "m", false)));
        verify(providerMapper, never()).updateById(any(AiUserModelProvider.class));
    }

    @Test
    @DisplayName("更新时不传 apiKey 就保留原密文")
    void updateKeepsKeyWhenBlank() {
        AiUserModelProvider existing = new AiUserModelProvider();
        existing.setId(3L);
        existing.setUserName("alice");
        existing.setBaseUrl("https://api.example.com/v1");
        existing.setApiKeyCiphertext("cipher");
        existing.setApiKeyNonce("nonce");
        existing.setApiKeyFingerprint("fp");
        existing.setStatus(1);
        when(providerMapper.selectOne(any())).thenReturn(existing);
        when(modelMapper.selectList(any())).thenReturn(List.of());

        service.update("alice", 3L,
                new ProviderRequests.UpdateProvider("新名字", "https://api2.example.com", "  ", 0));

        ArgumentCaptor<AiUserModelProvider> cap = ArgumentCaptor.forClass(AiUserModelProvider.class);
        verify(providerMapper).updateById(cap.capture());
        assertEquals("cipher", cap.getValue().getApiKeyCiphertext());
        assertEquals("https://api2.example.com/v1", cap.getValue().getBaseUrl());
        assertEquals(0, cap.getValue().getStatus());
    }

    @Test
    @DisplayName("删除是软删：供应商与它的模型都置 0")
    void deleteIsSoft() {
        AiUserModelProvider existing = new AiUserModelProvider();
        existing.setId(3L);
        existing.setUserName("alice");
        existing.setStatus(1);
        when(providerMapper.selectOne(any())).thenReturn(existing);

        service.delete("alice", 3L);

        ArgumentCaptor<AiUserModelProvider> cap = ArgumentCaptor.forClass(AiUserModelProvider.class);
        verify(providerMapper).updateById(cap.capture());
        assertEquals(0, cap.getValue().getStatus());
        verify(modelMapper).update(any(AiUserModel.class), any());
    }

    @Test
    @DisplayName("停用的供应商不能再加模型")
    void disabledProviderRejectsModel() {
        AiUserModelProvider existing = new AiUserModelProvider();
        existing.setId(3L);
        existing.setUserName("alice");
        existing.setStatus(0);
        when(providerMapper.selectOne(any())).thenReturn(existing);
        assertThrows(ChatRejectException.class,
                () -> service.addModel("alice", 3L, new ProviderRequests.ModelInput("m", "m", false)));
        verify(modelMapper, never()).insert(any(AiUserModel.class));
    }

    @Test
    @DisplayName("测试连接：先用第一个启用模型探活")
    void testConnectionUsesFirstEnabledModel() {
        AiUserModelProvider existing = new AiUserModelProvider();
        existing.setId(3L);
        existing.setUserName("alice");
        existing.setStatus(1);
        existing.setBaseUrl("https://api.example.com/v1");
        ProviderProperties props = new ProviderProperties();
        props.setEncryptionKey("0123456789abcdef0123456789abcdef");
        ApiKeyCryptoService crypto = new ApiKeyCryptoService(props);
        ApiKeyCryptoService.Encrypted enc = crypto.encrypt("sk-test-key-1234");
        existing.setApiKeyCiphertext(enc.ciphertext());
        existing.setApiKeyNonce(enc.nonce());
        when(providerMapper.selectOne(any())).thenReturn(existing);

        AiUserModel enabled = new AiUserModel();
        enabled.setId(1L);
        enabled.setProviderId(3L);
        enabled.setUserName("alice");
        enabled.setModelKey("gpt-4o-mini");
        enabled.setStatus(1);
        AiUserModel disabled = new AiUserModel();
        disabled.setId(2L);
        disabled.setProviderId(3L);
        disabled.setUserName("alice");
        disabled.setModelKey("other");
        disabled.setStatus(0);
        when(modelMapper.selectList(any())).thenReturn(List.of(disabled, enabled));

        ProviderProbe probe = mock(ProviderProbe.class);
        when(probe.probe(any(), any(), any())).thenReturn(new ProviderProbe.Result(true, 12L, "连接成功"));
        ProviderService withProbe = new ProviderService(providerMapper, modelMapper, crypto, props, probe);

        ProviderProbe.Result result = withProbe.testConnection("alice", 3L);

        assertTrue(result.ok());
        verify(probe).probe("https://api.example.com/v1", "sk-test-key-1234", "gpt-4o-mini");
    }

    @Test
    @DisplayName("测试连接：没有启用模型时直接拒绝")
    void testConnectionRequiresModel() {
        AiUserModelProvider existing = new AiUserModelProvider();
        existing.setId(3L);
        existing.setUserName("alice");
        existing.setStatus(1);
        when(providerMapper.selectOne(any())).thenReturn(existing);
        when(modelMapper.selectList(any())).thenReturn(List.of());
        ProviderProbe probe = mock(ProviderProbe.class);
        ProviderProperties props = new ProviderProperties();
        props.setEncryptionKey("0123456789abcdef0123456789abcdef");
        ProviderService withProbe = new ProviderService(providerMapper, modelMapper,
                new ApiKeyCryptoService(props), props, probe);
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> withProbe.testConnection("alice", 3L));
        assertEquals("请先添加至少一个模型", e.getMessage());
        verify(probe, never()).probe(any(), any(), any());
        verify(providerMapper, never()).selectById(anyLong());
    }
}
```

- [ ] **Step 5: 跑测试**

Run: `& $MVN -pl AIModule -am test -Dtest=ProviderServiceTest`
Expected: PASS（8 个用例）

---

## Task 5: 个人模型目录与运行期解析

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/provider/UserModelCatalogService.java`
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/provider/ProviderRoute.java`（补 `supportsThinking`）
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/provider/UserModelCatalogServiceTest.java`

- [ ] **Step 1: `ProviderRoute` 换成下面这版**

```java
package org.leon.aimodule.chat.provider;

/**
 * 本轮请求实际要打的上游：用户自己的 baseUrl + 解密后的 key + 上游模型标识。
 * 只在 prepare 阶段构造、活在一轮流里，绝不进日志/事件/响应体，toString 因此隐去 key。
 *
 * supportsTools 固定 false：个人供应商是否支持 OpenAI 工具协议无法预判，
 * 贸然带 tools 会让不支持的端点整轮报错，所以第一期个人模型一律不带工具。
 */
public record ProviderRoute(Long providerId, Long modelId, String providerName, String displayName,
                            String baseUrl, String apiKey, String upstreamModel, boolean supportsThinking) {

    public boolean supportsTools() {
        return false;
    }

    @Override
    public String toString() {
        return "ProviderRoute[providerId=" + providerId + ", modelId=" + modelId
                + ", baseUrl=" + baseUrl + ", upstreamModel=" + upstreamModel + "]";
    }
}
```

- [ ] **Step 2: 写目录服务测试**

```java
package org.leon.aimodule.chat.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.exception.ChatRejectException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        AiUserModelProvider provider = provider(9L, "alice", 1);
        AiUserModelProvider disabled = provider(10L, "alice", 0);
        when(providerMapper.selectList(any())).thenReturn(List.of(provider, disabled));
        when(modelMapper.selectList(any())).thenReturn(List.of(
                model(1L, 9L, "alice", "gpt-4o-mini", "小模型", 1),
                model(2L, 9L, "alice", "gpt-4o", "大模型", 0),
                model(3L, 10L, "alice", "ghost", "停用供应商的模型", 1)));

        List<UserModelCatalogService.Choice> choices = service.choices("alice");

        assertEquals(1, choices.size());
        assertEquals("u:9:1", choices.get(0).selectionKey());
        assertEquals("小模型", choices.get(0).displayName());
        assertEquals("alice 的中转", choices.get(0).providerName());
    }

    @Test
    @DisplayName("resolve 拿到解密后的 key 与上游模型名")
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
        assertEquals(false, route.supportsTools());
        assertTrue(!route.toString().contains("sk-real-key"));
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

    private static AiUserModelProvider provider(Long id, String user, int status) {
        AiUserModelProvider p = new AiUserModelProvider();
        p.setId(id);
        p.setUserName(user);
        p.setProviderName(user + " 的中转");
        p.setProtocol("OPENAI_COMPAT");
        p.setBaseUrl("https://gw.example.com/v1");
        p.setApiKeyCiphertext("x");
        p.setApiKeyNonce("y");
        p.setStatus(status);
        return p;
    }

    private static AiUserModel model(Long id, Long providerId, String user, String key,
                                     String display, int status) {
        AiUserModel m = new AiUserModel();
        m.setId(id);
        m.setProviderId(providerId);
        m.setUserName(user);
        m.setModelKey(key);
        m.setDisplayName(display);
        m.setSupportsThinking(false);
        m.setStatus(status);
        return m;
    }
}
```

- [ ] **Step 3: 实现 `UserModelCatalogService`**

测试里删掉那行 `AiUserModelProvider.EncryptedKeys unused = null;`（写测试时不要留占位），最终实现：

```java
package org.leon.aimodule.chat.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人模型目录：列出当前用户可选的模型，并把选择器 key 解析成可直发的上游路由。
 * 归属校验在这里做唯一一次：provider 与 model 都必须属于请求头里的 X-User-Name 且状态为启用，
 * 前端传什么 id 都不构成授权。
 */
@Service
public class UserModelCatalogService {

    private final AiUserModelProviderMapper providerMapper;
    private final AiUserModelMapper modelMapper;
    private final ApiKeyCryptoService crypto;

    public UserModelCatalogService(AiUserModelProviderMapper providerMapper, AiUserModelMapper modelMapper,
                                   ApiKeyCryptoService crypto) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.crypto = crypto;
    }

    public record Choice(Long providerId, Long modelId, String providerName, String modelKey,
                         String displayName, boolean supportsThinking) {
        public String selectionKey() {
            return ModelSelection.key(providerId, modelId);
        }
    }

    public List<Choice> choices(String userName) {
        List<AiUserModelProvider> providers = providerMapper.selectList(new LambdaQueryWrapper<AiUserModelProvider>()
                .eq(AiUserModelProvider::getUserName, userName)
                .eq(AiUserModelProvider::getStatus, 1));
        if (providers.isEmpty()) {
            return List.of();
        }
        Map<Long, AiUserModelProvider> byId = new LinkedHashMap<>();
        for (AiUserModelProvider p : providers) {
            byId.put(p.getId(), p);
        }
        List<Choice> out = new ArrayList<>();
        for (AiUserModel model : modelMapper.selectList(new LambdaQueryWrapper<AiUserModel>()
                .eq(AiUserModel::getUserName, userName)
                .eq(AiUserModel::getStatus, 1)
                .orderByAsc(AiUserModel::getProviderId)
                .orderByAsc(AiUserModel::getId))) {
            AiUserModelProvider provider = byId.get(model.getProviderId());
            if (provider == null) {
                continue;
            }
            out.add(new Choice(provider.getId(), model.getId(), provider.getProviderName(),
                    model.getModelKey(), model.getDisplayName(),
                    Boolean.TRUE.equals(model.getSupportsThinking())));
        }
        return out;
    }

    public ProviderRoute resolve(String userName, String selectionKey) {
        ModelSelection.Ids ids = ModelSelection.parse(selectionKey);
        if (ids == null) {
            throw new ChatRejectException("模型不存在");
        }
        AiUserModelProvider provider = providerMapper.selectOne(new LambdaQueryWrapper<AiUserModelProvider>()
                .eq(AiUserModelProvider::getId, ids.providerId())
                .eq(AiUserModelProvider::getUserName, userName)
                .eq(AiUserModelProvider::getStatus, 1));
        AiUserModel model = provider == null ? null
                : modelMapper.selectOne(new LambdaQueryWrapper<AiUserModel>()
                .eq(AiUserModel::getId, ids.modelId())
                .eq(AiUserModel::getProviderId, provider.getId())
                .eq(AiUserModel::getUserName, userName)
                .eq(AiUserModel::getStatus, 1));
        if (model == null) {
            throw new ChatRejectException("模型不存在");
        }
        String apiKey;
        try {
            apiKey = crypto.decrypt(new ApiKeyCryptoService.Encrypted(
                    provider.getApiKeyCiphertext(), provider.getApiKeyNonce()));
        } catch (RuntimeException e) {
            throw new ChatRejectException("供应商密钥不可用，请重新填写 API Key");
        }
        return new ProviderRoute(provider.getId(), model.getId(), provider.getProviderName(),
                model.getDisplayName(), provider.getBaseUrl(), apiKey, model.getModelKey(),
                Boolean.TRUE.equals(model.getSupportsThinking()));
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `& $MVN -pl AIModule -am test -Dtest=UserModelCatalogServiceTest`
Expected: PASS（3 个用例）

---

## Task 6: 供应商 HTTP 接口

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/provider/ProviderController.java`

- [ ] **Step 1: 写控制器**

```java
package org.leon.aimodule.chat.provider;

import jakarta.validation.Valid;
import org.leon.commonjwt.pojo.Result;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户自建供应商的设置页接口。归属一律取网关注入的 X-User-Name，
 * 请求体里不接受 userName/providerId 之外的任何身份字段。
 * 异常由 chat 包的 ChatExceptionHandler 统一转成 Result(code=0)。
 */
@RestController
@RequestMapping("/ai/chat/providers")
@Validated
public class ProviderController {

    private final ProviderService service;

    public ProviderController(ProviderService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<ProviderViews.Provider>> list(@RequestHeader("X-User-Name") String userName) {
        return Result.success(service.list(userName));
    }

    @PostMapping
    public Result<ProviderViews.Provider> create(@RequestHeader("X-User-Name") String userName,
                                                 @RequestBody @Valid ProviderRequests.CreateProvider req) {
        return Result.success(service.create(userName, req));
    }

    @PutMapping("/{providerId}")
    public Result<ProviderViews.Provider> update(@RequestHeader("X-User-Name") String userName,
                                                 @PathVariable Long providerId,
                                                 @RequestBody @Valid ProviderRequests.UpdateProvider req) {
        return Result.success(service.update(userName, providerId, req));
    }

    @DeleteMapping("/{providerId}")
    public Result<Void> delete(@RequestHeader("X-User-Name") String userName, @PathVariable Long providerId) {
        service.delete(userName, providerId);
        return Result.success();
    }

    @PostMapping("/{providerId}/models")
    public Result<ProviderViews.Model> addModel(@RequestHeader("X-User-Name") String userName,
                                                @PathVariable Long providerId,
                                                @RequestBody @Valid ProviderRequests.ModelInput req) {
        return Result.success(service.addModel(userName, providerId, req));
    }

    @PutMapping("/{providerId}/models/{modelId}")
    public Result<ProviderViews.Model> updateModel(@RequestHeader("X-User-Name") String userName,
                                                   @PathVariable Long providerId, @PathVariable Long modelId,
                                                   @RequestBody @Valid ProviderRequests.UpdateModel req) {
        return Result.success(service.updateModel(userName, providerId, modelId, req));
    }

    @DeleteMapping("/{providerId}/models/{modelId}")
    public Result<Void> deleteModel(@RequestHeader("X-User-Name") String userName,
                                    @PathVariable Long providerId, @PathVariable Long modelId) {
        service.deleteModel(userName, providerId, modelId);
        return Result.success();
    }

    @PostMapping("/{providerId}/test")
    public Result<ProviderProbe.Result> test(@RequestHeader("X-User-Name") String userName,
                                             @PathVariable Long providerId) {
        return Result.success(service.testConnection(userName, providerId));
    }
}
```

- [ ] **Step 2: 编译**

Run: `& $MVN -pl AIModule -am -DskipTests compile`
Expected: `BUILD SUCCESS`

---

## Task 7: 聊天链接入 ProviderRoute

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/service/ModelResolver.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/service/ModelResolverTest.java`
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/service/EffortResolver.java`（加 `resolvePersonal`）
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatModelGateway.java`（新增 5 参 default 方法）
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/stream/OpenAiStreamChatModelGateway.java`（抽出 `execute`，按 route 覆盖 baseUrl/key/model）
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/stream/PreparedTurn.java`（追加 `route`）
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatStreamService.java`（用 `ModelResolver`、按 route 关工具、路由透传）
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/service/ConversationService.java`（`catalog(userName)` 合并个人模型、`create` 走 `ModelResolver`）
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/web/dto/ModelVo.java`（补 providerName/custom）
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/web/ChatConversationController.java`（`/models` 读可选 header）
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/service/ModelResolverTest.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/service/ConversationServiceTest.java`（改构造）

- [ ] **Step 1: `EffortResolver` 追加个人模型入口**

```java
    /** 个人模型的参数：温度/上限仍取档位，thinking 能力取用户模型标记（再与全局开关相与）。 */
    public ModelParams resolvePersonal(String selectionKey, String effort, boolean supportsThinking) {
        String ek = (effort == null || effort.isBlank()) ? props.getDefaultEffort() : effort.trim();
        ChatProperties.Effort level = props.effort(ek);
        if (level == null) {
            throw new ChatRejectException("不支持的档位: " + ek);
        }
        return new ModelParams(selectionKey, level.getTemperature(), level.getMaxTokens(),
                props.thinkingSupported(supportsThinking));
    }
```

- [ ] **Step 2: 写 `ModelResolverTest`（先失败）**

```java
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelResolverTest {

    private ChatProperties props;
    private UserModelCatalogService catalog;
    private ModelResolver resolver;

    @BeforeEach
    void setUp() {
        props = new ChatProperties();
        ChatProperties.Model m = new ChatProperties.Model();
        m.setKey("deepseek-chat");
        m.setName("deepseek-chat");
        props.setModels(new ArrayList<>(List.of(m)));
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
        when(catalog.resolve(anyString(), anyString())).thenReturn(new ProviderRoute(9L, 1L, "中转",
                "小模型", "https://gw.example.com/v1", "sk-x", "gpt-4o-mini", false));
        ModelResolver.Resolved r = resolver.resolve("alice", "u:9:1", "Medium");
        assertEquals("u:9:1", r.params().modelKey());
        assertEquals("gpt-4o-mini", r.route().upstreamModel());
        assertEquals(1.0, r.params().temperature());
    }

    @Test
    @DisplayName("个人模型不存在时把拒绝原样抛出，不回落默认模型")
    void personalRejectPropagates() {
        when(catalog.resolve(any(), any())).thenThrow(new ChatRejectException("模型不存在"));
        assertEquals("模型不存在", assertThrows(ChatRejectException.class,
                () -> resolver.resolve("alice", "u:9:99", "Medium")).getMessage());
    }

    @Test
    @DisplayName("档位未知一律拒绝，内置与个人两条链口径一致")
    void unknownEffortRejected() {
        when(catalog.resolve(any(), any())).thenReturn(new ProviderRoute(9L, 1L, "中转",
                "小模型", "https://gw.example.com/v1", "sk-x", "m", false));
        assertThrows(ChatRejectException.class, () -> resolver.resolve("alice", "u:9:1", "Ultra"));
        assertThrows(ChatRejectException.class, () -> resolver.resolve("alice", "deepseek-chat", "Ultra"));
    }

    @Test
    @DisplayName("模型为空时回落配置默认模型")
    void blankFallsBackToDefault() {
        props.setDefaultModel("deepseek-chat");
        ModelResolver.Resolved r = resolver.resolve("alice", null, null);
        assertEquals("deepseek-chat", r.params().modelKey());
    }
}
```

- [ ] **Step 3: 实现 `ModelResolver`**

```java
package org.leon.aimodule.chat.service;

import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.provider.ModelSelection;
import org.leon.aimodule.chat.provider.ProviderRoute;
import org.leon.aimodule.chat.provider.UserModelCatalogService;
import org.springframework.stereotype.Service;

/**
 * (userName, modelKey, effort) → 调用参数 + 上游路由。
 * 两条链在这里合流：内置模型只出 ModelParams（route 为 null，走服务端 apiKey/baseUrl），
 * 个人模型额外带一条 ProviderRoute，绝不让 route 从请求体里来。
 */
@Service
public class ModelResolver {

    private final ChatProperties props;
    private final EffortResolver efforts;
    private final UserModelCatalogService userModels;

    public ModelResolver(ChatProperties props, EffortResolver efforts, UserModelCatalogService userModels) {
        this.props = props;
        this.efforts = efforts;
        this.userModels = userModels;
    }

    public record Resolved(ModelParams params, ProviderRoute route) {
    }

    public Resolved resolve(String userName, String modelKey, String effort) {
        String mk = (modelKey == null || modelKey.isBlank()) ? props.getDefaultModel() : modelKey.trim();
        if (ModelSelection.isPersonal(mk)) {
            ProviderRoute route = userModels.resolve(userName, mk);
            return new Resolved(efforts.resolvePersonal(mk, effort, route.supportsThinking()), route);
        }
        return new Resolved(efforts.resolve(mk, effort), null);
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `& $MVN -pl AIModule -am test -Dtest=ModelResolverTest`
Expected: PASS

- [ ] **Step 5: `ChatModelGateway` 加 5 参 default 方法**

```java
    /**
     * 带用户自建路由的一轮。默认实现忽略 route（老实现/测试替身不受影响），
     * 真正会切 baseUrl/key 的是 OpenAiStreamChatModelGateway。
     */
    default Flux<ChatChunk> stream(List<CtxMessage> messages, ModelParams params, String systemPromptOverride,
                                   List<ChatTool> tools, ProviderRoute route) {
        return stream(messages, params, systemPromptOverride, tools);
    }
```

import：`org.leon.aimodule.chat.provider.ProviderRoute`。

- [ ] **Step 6: `OpenAiStreamChatModelGateway` 按 route 发请求**

把现有 `stream(...)` 方法体整体搬进私有 `execute(...)`，签名：

```java
    private Flux<ChatModelGateway.ChatChunk> execute(List<CtxMessage> messages, ModelParams params,
                                                     String systemPromptOverride, List<ChatTool> tools,
                                                     String baseUrl, String path, String apiKey,
                                                     String wireModel) {
```

`body.put("model", wireModel)`、`webClient.mutate().baseUrl(baseUrl).build().post().uri(path)`、
`.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)`，其余逻辑不变。

两个入口：

```java
    @Override
    public Flux<ChatModelGateway.ChatChunk> stream(List<CtxMessage> messages, ModelParams params,
                                                   String systemPromptOverride, List<ChatTool> tools) {
        String base = testBaseUrl == null ? props.getBaseUrl() : testBaseUrl;
        return execute(messages, params, systemPromptOverride, tools,
                base, "/v1/chat/completions", props.getApiKey(), params.modelKey());
    }

    @Override
    public Flux<ChatModelGateway.ChatChunk> stream(List<CtxMessage> messages, ModelParams params,
                                                   String systemPromptOverride, List<ChatTool> tools,
                                                   ProviderRoute route) {
        if (route == null) {
            return stream(messages, params, systemPromptOverride, tools);
        }
        String base = testBaseUrl == null ? route.baseUrl() : testBaseUrl;
        return execute(messages, params, systemPromptOverride,
                route.supportsTools() ? tools : null,
                base, "/chat/completions", route.apiKey(), route.upstreamModel());
    }
```

- [ ] **Step 7: `PreparedTurn` 追加 route**

```java
public record PreparedTurn(Long conversationId, Long userMessageId, Long assistantMessageId,
                           List<CtxMessage> messages, ModelParams params, String userName, String effortName,
                           boolean useRag, RagContextService.RagOptions ragOptions,
                           String systemPromptOverride,
                           /** 个人供应商路由；内置模型为 null。 */
                           ProviderRoute route) {

    public PreparedTurn(Long conversationId, Long userMessageId, Long assistantMessageId,
                        List<CtxMessage> messages, ModelParams params, String userName, String effortName,
                        boolean useRag, RagContextService.RagOptions ragOptions) {
        this(conversationId, userMessageId, assistantMessageId, messages, params, userName, effortName,
                useRag, ragOptions, null, null);
    }

    public PreparedTurn(Long conversationId, Long userMessageId, Long assistantMessageId,
                        List<CtxMessage> messages, ModelParams params, String userName, String effortName,
                        boolean useRag, RagContextService.RagOptions ragOptions, String systemPromptOverride) {
        this(conversationId, userMessageId, assistantMessageId, messages, params, userName, effortName,
                useRag, ragOptions, systemPromptOverride, null);
    }
}
```

- [ ] **Step 8: `ChatStreamService` 三处改动**

1) 字段/构造：把 `EffortResolver resolver` 换成 `ModelResolver modelResolver`（构造参数位不变，仍是第 4 个）。

2) `prepare` 里：

```java
        ModelResolver.Resolved resolved = modelResolver.resolve(userName, modelKey, effortName);
        ModelParams params = resolved.params();
```

3) `prepare` 收尾：

```java
        return new PreparedTurn(cid, userMessageId, assistantId, messages, params, userName, effortName,
                cmd.useRag(), cmd.toRagOptions(), cmd.systemPromptOverride(), resolved.route());
```

4) `run` 里工具闸门：

```java
        boolean toolsAllowed = prepared.route() == null || prepared.route().supportsTools();
        List<ChatTool> tools = toolRegistry.isEmpty() || !toolsAllowed ? null : toolRegistry.all();
```

5) `streamLoop` 的网关调用：

```java
        Disposable disposable = gateway.stream(context, params, systemPromptOverride,
                        lastRound ? null : tools, prepared.route())
                .subscribe(
```

- [ ] **Step 9: `ModelVo` 补字段（保持 4 参构造可用）**

```java
package org.leon.aimodule.chat.web.dto;

/** 形状对齐前端 PromptBarModel = {key, name, tag?}，supportsThinking 供思考线开关。 */
public record ModelVo(String key, String name, String tag, boolean supportsThinking,
                      /** 个人模型所属供应商显示名；内置模型为 null。 */
                      String providerName,
                      /** true 表示用户自建供应商下的模型。 */
                      boolean custom) {

    public ModelVo(String key, String name, String tag, boolean supportsThinking) {
        this(key, name, tag, supportsThinking, null, false);
    }
}
```

- [ ] **Step 10: `ConversationService` 目录合并与创建**

构造参数第 2 位由 `EffortResolver` 换成 `ModelResolver`（命名 `resolver` 不变），新增 `UserModelCatalogService userModels` 第 5 参。

```java
    public ModelCatalogVo catalog(String userName) {
        List<ModelVo> models = new ArrayList<>(props.getModels().stream()
                .map(m -> new ModelVo(m.getKey(), m.getName(), m.getTag(),
                        props.thinkingSupported(m.isSupportsThinking())))
                .toList());
        if (userName != null && !userName.isBlank()) {
            for (UserModelCatalogService.Choice c : userModels.choices(userName)) {
                models.add(new ModelVo(c.selectionKey(), c.displayName(), c.providerName(),
                        props.thinkingSupported(c.supportsThinking()), c.providerName(), true));
            }
        }
        return new ModelCatalogVo(props.getDefaultModel(), props.getDefaultEffort(),
                props.getEfforts().stream().map(ChatProperties.Effort::getKey).toList(), models);
    }

    public ModelCatalogVo catalog() {
        return catalog(null);
    }
```

`create` 里：

```java
        ModelResolver.Resolved resolved = resolver.resolve(userName,
                req == null ? null : req.modelKey(), req == null ? null : req.effort());
        ModelParams params = resolved.params();
```

- [ ] **Step 11: `/models` 读可选用户**

```java
    @GetMapping("/models")
    public Result<ModelCatalogVo> models(
            @RequestHeader(value = "X-User-Name", required = false) String userName) {
        return Result.success(service.catalog(userName));
    }
```

- [ ] **Step 12: 修既有测试的构造**

`ConversationServiceTest.newService()`：

```java
        service = new ConversationService(props,
                new ModelResolver(props, new EffortResolver(props), mock(UserModelCatalogService.class)),
                conversationMapper, messageMapper, mock(UserModelCatalogService.class));
```

`ChatStreamServiceTest.newService(...)`：把 `new EffortResolver(props)` 换成
`new ModelResolver(props, new EffortResolver(props), mock(UserModelCatalogService.class))`。

- [ ] **Step 13: 编译 + 全量单测**

Run: `& $MVN -pl AIModule -am test`
Expected: 全部 PASS（含既有 100+ 用例；`db-it/es-it` 组按 pom 默认排除）

---

## Task 8: 网关路由的端到端验证

**Files:**
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/stream/OpenAiStreamChatModelGatewayRouteTest.java`

- [ ] **Step 1: 写测试（用本机 HttpServer 当假上游）**

```java
package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.provider.ProviderRoute;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiStreamChatModelGatewayRouteTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("带 route 时用用户的 baseUrl、Bearer 与上游模型名，且路径只拼一次 /chat/completions")
    void routeOverridesUpstream() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        ChatProperties props = new ChatProperties();
        OpenAiStreamChatModelGateway gateway = new OpenAiStreamChatModelGateway(props, new ObjectMapper());
        ProviderRoute route = new ProviderRoute(1L, 2L, "中转", "小模型", base,
                "sk-user-key", "gpt-4o-mini", false);

        List<ChatModelGateway.ChatChunk> chunks = gateway.stream(
                        List.of(new CtxMessage("user", "你好")),
                        new ModelParams("u:1:2", 1.0, 64, false), null, null, route)
                .collectList().block();

        assertEquals("Bearer sk-user-key", auth.get());
        assertEquals("/v1/chat/completions", path.get());
        assertTrue(body.get().contains("\"model\":\"gpt-4o-mini\""));
        assertTrue(chunks != null && chunks.stream().anyMatch(c -> "hi".equals(c.text())));
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `& $MVN -pl AIModule -am test -Dtest=OpenAiStreamChatModelGatewayRouteTest`
Expected: PASS（本机 HttpServer，不需外网）

---

## Task 9: 手工验收（对着真实后端）

- [ ] **Step 1: 起服务**

Run: `& $MVN -pl AIModule spring-boot:run`（需 Nacos/MySQL 已起；缺 `EZ_PROVIDER_ENCRYPTION_KEY` 时保存会明确报错，这是设计行为）

- [ ] **Step 2: 逐条 curl（PowerShell，`$H` 里换真实网关签发的用户）**

```powershell
$H = @{ "X-User-Name" = "alice"; "Content-Type" = "application/json" }

# 新增供应商（key 只进不出）
Invoke-RestMethod -Method Post -Uri http://localhost:8105/ai/chat/providers -Headers $H -Body (@{
  providerName = "我的中转"; baseUrl = "https://api.example.com"; apiKey = "sk-xxxxxxxxxxxx"
  models = @(@{ modelKey = "gpt-4o-mini"; displayName = "小模型"; supportsThinking = $false })
} | ConvertTo-Json -Depth 5)

# 列表：只应看到 maskedKey
Invoke-RestMethod -Uri http://localhost:8105/ai/chat/providers -Headers $H

# 测试连接
Invoke-RestMethod -Method Post -Uri http://localhost:8105/ai/chat/providers/1/test -Headers $H

# 模型目录：应同时有内置项与 custom=true 的 u:1:1
Invoke-RestMethod -Uri http://localhost:8105/ai/chat/models -Headers $H

# 用个人模型建会话并发一条
Invoke-RestMethod -Method Post -Uri http://localhost:8105/ai/chat/conversations -Headers $H -Body (@{
  title = "个人模型试跑"; modelKey = "u:1:1"; effort = "Medium" } | ConvertTo-Json)
```

- [ ] **Step 3: 断言点**

- 列表响应里没有明文字段 `apiKey`；
- 用别的用户 header 访问 `/providers/1` 返回 `供应商不存在`；
- 会话 SSE 的 `meta` 事件里 `modelKey` 是 `u:1:1`，正文由用户供应商产出；
- 停用该模型后再发消息，返回 `模型不存在` 而不是回落到默认模型。

---

## Task 10: 前端联动的接口契约（交给下一阶段）

前端设置页需要的字段与行为，后端已按此实现：

- `GET /ai/chat/providers` → `data[] = {id, providerName, protocol, baseUrl, configured, maskedKey, status, models[]}`
- `models[] = {id, modelKey, displayName, supportsThinking, status}`
- 新增/更新供应商：`providerName`、`baseUrl`、`apiKey`（更新时留空=不改）、`status`
- 模型目录 `GET /ai/chat/models`：`models[] = {key, name, tag, supportsThinking, providerName, custom}`，聊天请求把 `key` 原样回传即可
- 错误统一 `{code: 0, msg: "中文提示", data: null}`，HTTP 200

---

## 自检结论

- 覆盖：表（已完成）、加密、URL 安全、CRUD、归属校验、探测、目录合并、聊天路由、工具闸门、验收全部有对应任务。
- 无占位：每个待写文件都给了完整代码；改动点给了精确落点与替换代码。
- 命名一致：`ModelResolver.Resolved(params, route)`、`ProviderRoute(providerId, modelId, providerName, displayName, baseUrl, apiKey, upstreamModel, supportsThinking)`、`UserModelCatalogService.Choice.selectionKey()`、`u:{providerId}:{modelId}` 全计划统一。
- 已知取舍：个人模型第一期不带工具（`supportsTools()` 恒 false）；删除为软删，历史会话里的 `u:p:m` 仍可展示但再次发送会得到 `模型不存在`。

  
