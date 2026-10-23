# 仿 ChatGPT 对话子系统（AIModule）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `AIModule` 里实现一个仿 ChatGPT 的对话后端——2 张表、8 个接口、SSE 流式问答、服务端组装多轮上下文、重新生成与停止生成，不含 RAG、不含前端。

**Architecture:** 三层单向依赖：`ChatStreamController` 只懂 HTTP/SSE，`ChatStreamService` 只懂编排（幂等、并发检查、上下文、三段短事务落库），`ChatModelGateway` 把 Spring AI 封在接口后面。编排层通过 `Consumer<ChatEvent>` 输出事件，完全不 import `SseEmitter`，因此核心逻辑可在无 Spring、无上游的环境下单测。

**Tech Stack:** Java 21 · Spring Boot 3.x（`spring-boot-starter-web`，servlet 栈）· Spring AI 1.0.0-M7 + DeepSeek · MyBatis-Plus 3.5.17 · MySQL 9.1.0 · jtokkit 1.1.0 · JUnit 5 + Mockito + AssertJ

**Spec:** `docs/superpowers/specs/2026-10-23-ai-chat-conversation-design.md` —— 本计划的每一条决定都出自该文件，执行时必须同时读它。

## Global Constraints

逐条抄自 spec，每个任务的隐含要求都包含本节：

- Java 21（root pom `<java.version>21</java.version>`）。构建用 wrapper maven，`mvn` 不在 PATH：
  ```bash
  export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
  MVN="/c/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
  ```
- **不新增任何 Maven 依赖。** `mybatis-plus-spring-boot3-starter 3.5.17`、`mybatis-plus-spring`、`mysql-connector-j`、`jtokkit`、`spring-boot-starter-validation`、`CommonJwt` 均已在 `AIModule/pom.xml`。
- 新代码一律放 `org.leon.aimodule.chat` 包下（异常 advice 按包切作用域，放错包等于没有异常处理）。
- 返回体只用 `org.leon.commonjwt.pojo.Result`，`code=1` 成功、`code=0` 失败。**不返回 HTTP 4xx/5xx**（与 `AiExceptionHandler` 现状一致，spec 6.3）。
- 用户身份只从 `@RequestHeader("X-User-Name")` 取，禁止解析 `Authorization`。
- 分页只用游标，禁 offset 深翻；`limit` 必须先 clamp。
- 出参时间一律 epoch 毫秒 `Long`，用 `ChatTime.millis(...)` 显式转换，**不注册全局 Jackson 定制器**（不动全局 `ObjectMapper`，RAG 出参零影响；这是对 spec 第五节时间约定的一处实现方式修正，见 Task 3）。
- 禁止 `@GlobalTransactional`，只用 `@Transactional`（spec 5.5）。
- **流式期间数据库零写入**：一条消息整个生成过程 = INSERT 1 次 + UPDATE 1 次，与 token 数无关（spec 5.5 不变式，Task 10 有计数断言测试）。
- 表名 `ai_` 前缀，`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`，主键 `BIGINT UNSIGNED AUTO_INCREMENT` + `IdType.AUTO`，时间列 `DATETIME(3)`，不建物理外键。
- `effort` 只映射服务端参数（temperature / maxTokens），**不映射模型**（spec 第一节、4.7）。
- `#1` 返回的 `efforts` 数组必须与前端 `PromptBar.vue:128` 的 `['Low','Medium','High','Extra','Max']` 逐字一致；model 项形状对齐 `PromptBarModel = {key, name, tag?}`（spec 4.7）。
- 集成测试用**已有**标签机制：`@Tag("db-it")`。`AIModule/pom.xml:209` 的 `<surefire.excludedGroups>es-it,embedding-it,db-it,mq-it</surefire.excludedGroups>` 默认排除，显式跑时加 `-Dgroups=db-it -Dsurefire.excludedGroups=`。
- **五个关卡必须停下问用户**（spec 第十节）：G1 在主库执行 DDL（Task 2）、G2 建测试 schema（Task 3）、G3 提供/创建 BCrypt 测试账号（Task 12）、G4 改 Nacos `gateway.yaml`（Task 12 条件触发）、G5 改 `AIModule/application.yaml`（Task 1）。
- 工作区当前有**不属于本计划**的未提交改动：`AIModule/.../rag/chunk/ChunkPlanner.java`、`TokenCounter.java`。每次 `git add` 只加本任务明确列出的文件，禁止 `git add -A` / `git add .`。

## 文件结构

```
AIModule/src/main/java/org/leon/aimodule/chat/
  config/ChatProperties.java              配置绑定 + 按 key 查 model/effort
  config/ChatStreamExecutorConfig.java    流式线程池 bean（Task 9）
  exception/ChatRejectException.java      业务拒绝 → advice → Result(code=0)
  entity/AiConversation.java  entity/AiMessage.java
  mapper/AiConversationMapper.java  mapper/AiMessageMapper.java
  support/CursorCodec.java                (updateTime,id) ↔ base64url
  support/ChatTime.java                   LocalDateTime ↔ epoch ms
  service/ModelParams.java                record(modelKey, temperature, maxTokens, supportsThinking)
  service/EffortResolver.java             (modelKey, effort) → ModelParams
  service/ConversationService.java        #1–#5 + 归属校验
  service/MessageQueryService.java        #6
  service/ContextAssembler.java           token 预算截断
  repo/ChatTurnRepository.java            把两个 mapper 包成可 mock 的写入口
  stream/ChatEvent.java                   sealed interface + 5 个 record
  stream/ChatModelGateway.java            接口 + ChatChunk record
  stream/SpringAiChatModelGateway.java    ChatClient 实现
  stream/SseChannel.java                  发帧封装（synchronized）
  stream/ChatStreamRegistry.java          messageId → 活跃流
  stream/ChatStreamService.java           编排：prepare / subscribeAsync / stop
  web/dto/*.java                          请求与 VO（record）
  web/ChatConversationController.java     #1–#6
  web/ChatStreamController.java           #7、#8
  web/ChatExceptionHandler.java           @RestControllerAdvice(basePackages="org.leon.aimodule.chat")
db/migration/2026-10-23-ai-chat-conversation.sql
AIModule/src/test/java/org/leon/aimodule/chat/...   （镜像主包结构）
```

拆分原则：`ChatStreamService` 不碰 `SseEmitter`（靠 `SseChannel` 接口化），`ChatTurnRepository` 存在的全部理由是"让写次数可断言"——所有数据库写集中在一个可 mock 的类上。

---

### Task 1: 配置绑定与 effort 解析（关卡 G5）

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/config/ChatProperties.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/exception/ChatRejectException.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/service/ModelParams.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/service/EffortResolver.java`
- Modify: `AIModule/src/main/resources/application.yaml`（在 `rag:` 段之后加 `chat:` 段）
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/config/ChatPropertiesTest.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/service/EffortResolverTest.java`

**Interfaces:**
- Consumes: 无（本任务是起点）
- Produces:
  - `ChatProperties`：`getDefaultModel():String`、`getDefaultEffort():String`、`getModels():List<ChatProperties.Model>`、`getEfforts():List<ChatProperties.Effort>`、`model(String):ChatProperties.Model|null`、`effort(String):ChatProperties.Effort|null`、`getSystemPrompt():String`、`getContextMaxTokens():int`、`getStreamTimeoutMs():long`、`getStreamCoreThreads():int`、`getStreamMaxThreads():int`、`getHeartbeatIntervalSeconds():int`、`getStaleStreamReapMinutes():int`
  - `ChatProperties.Model`：`getKey()/getName()/getTag():String`、`isSupportsThinking():boolean`
  - `ChatProperties.Effort`：`getKey():String`、`getTemperature():Double`、`getMaxTokens():Integer`
  - `record ModelParams(String modelKey, Double temperature, Integer maxTokens, boolean supportsThinking)`
  - `EffortResolver.resolve(String modelKey, String effort): ModelParams`（两参数允许传 null，走默认值；非法抛 `ChatRejectException`）
  - `ChatRejectException extends RuntimeException`，`new ChatRejectException(String message)`

- [ ] **Step 1: 写失败测试 `ChatPropertiesTest`**

`AIModule/src/test/java/org/leon/aimodule/chat/config/ChatPropertiesTest.java`：

```java
package org.leon.aimodule.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
MVN="/c/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
cd /f/AAAJAVA/code/ezPracticeExercises
"$MVN" -o -pl AIModule -am test -Dtest=ChatPropertiesTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译错误 `cannot find symbol: class ChatProperties`。

- [ ] **Step 3: 写 `ChatProperties`**

```java
package org.leon.aimodule.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2 的命令。Expected: `Tests run: 2, Failures: 0`。

- [ ] **Step 5: 写失败测试 `EffortResolverTest`**

```java
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
```

- [ ] **Step 6: 跑测试确认失败**

```bash
"$MVN" -o -pl AIModule -am test -Dtest=EffortResolverTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译错误 `cannot find symbol: class EffortResolver`。

- [ ] **Step 7: 写 `ChatRejectException`、`ModelParams`、`EffortResolver`**

`exception/ChatRejectException.java`：

```java
package org.leon.aimodule.chat.exception;

/**
 * 业务拒绝。advice 统一翻成 HTTP 200 + Result(code=0)，
 * 与仓库既有约定一致，不引入 4xx。
 */
public class ChatRejectException extends RuntimeException {

    public ChatRejectException(String message) {
        super(message);
    }
}
```

`service/ModelParams.java`：

```java
package org.leon.aimodule.chat.service;

public record ModelParams(String modelKey, Double temperature, Integer maxTokens, boolean supportsThinking) {
}
```

`service/EffortResolver.java`：

```java
package org.leon.aimodule.chat.service;

import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.springframework.stereotype.Service;

/**
 * (modelKey, effort) → 上游调用参数。
 * effort 只改 temperature/maxTokens，绝不换模型：前端两个控件独立，
 * 换模型会让模型下拉变成误导。
 */
@Service
public class EffortResolver {

    private final ChatProperties props;

    public EffortResolver(ChatProperties props) {
        this.props = props;
    }

    public ModelParams resolve(String modelKey, String effort) {
        String mk = (modelKey == null || modelKey.isBlank()) ? props.getDefaultModel() : modelKey.trim();
        String ek = (effort == null || effort.isBlank()) ? props.getDefaultEffort() : effort.trim();

        ChatProperties.Model model = props.model(mk);
        if (model == null) {
            throw new ChatRejectException("不支持的模型: " + mk);
        }
        ChatProperties.Effort level = props.effort(ek);
        if (level == null) {
            throw new ChatRejectException("不支持的档位: " + ek);
        }
        return new ModelParams(model.getKey(), level.getTemperature(), level.getMaxTokens(),
                model.isSupportsThinking());
    }
}
```

- [ ] **Step 8: 跑两个测试确认全绿**

```bash
"$MVN" -o -pl AIModule -am test -Dtest='ChatPropertiesTest,EffortResolverTest' \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `Tests run: 6, Failures: 0`。

- [ ] **Step 9: 关卡 G5 —— 请用户批准改 `application.yaml`**

向用户展示要追加的内容并等一句"同意"。不允许跳过。

- [ ] **Step 10: 追加 `chat:` 配置段**

在 `AIModule/src/main/resources/application.yaml` 的 `rag:` 段（`dev-seed` 那两行）之后、`mybatis:` 之前插入：

```yaml
chat:
  default-model: deepseek-chat
  default-effort: Medium
  models:
    - key: deepseek-chat
      name: deepseek-chat
      tag: Fast
      supports-thinking: false
    - key: deepseek-reasoner
      name: deepseek-reasoner
      tag: Reasoning
      supports-thinking: true
  efforts:
    - { key: Low,    temperature: 1.3, max-tokens: 4096 }
    - { key: Medium, temperature: 1.0, max-tokens: 4096 }
    - { key: High,   temperature: 0.7, max-tokens: 8192 }
    - { key: Extra,  temperature: 0.4, max-tokens: 8192 }
    - { key: Max,    temperature: 0.2, max-tokens: 16384 }
  system-prompt: ""
  context-max-tokens: 8000
  stream-timeout-ms: 300000
  stream-core-threads: 4
  stream-max-threads: 16
  heartbeat-interval-seconds: 15
  stale-stream-reap-minutes: 5
```

同时把 `spring.ai.openai.chat.options` 段补一行（`stream-usage` 不开则 `usage` 事件永不出现）：

```yaml
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
          stream-usage: true
```

- [ ] **Step 11: 用 ApplicationContextRunner 复验 yaml 里的档位数组**

在 `ChatPropertiesTest` 追加一条测试，直接读 yaml 的字符串数组形状，锁死"与前端 `DEFAULT_EFFORTS` 逐字一致"：

```java
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
                    assertEquals(java.util.List.of("Low", "Medium", "High", "Extra", "Max"),
                            p.getEfforts().stream().map(ChatProperties.Effort::getKey).toList());
                });
    }
```

Run: `"$MVN" -o -pl AIModule -am test -Dtest=ChatPropertiesTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 3, Failures: 0`。

- [ ] **Step 12: 提交**

```bash
cd /f/AAAJAVA/code/ezPracticeExercises
git add AIModule/src/main/java/org/leon/aimodule/chat/config/ChatProperties.java \
        AIModule/src/main/java/org/leon/aimodule/chat/exception/ChatRejectException.java \
        AIModule/src/main/java/org/leon/aimodule/chat/service/ModelParams.java \
        AIModule/src/main/java/org/leon/aimodule/chat/service/EffortResolver.java \
        AIModule/src/main/resources/application.yaml \
        AIModule/src/test/java/org/leon/aimodule/chat/config/ChatPropertiesTest.java \
        AIModule/src/test/java/org/leon/aimodule/chat/service/EffortResolverTest.java
git commit -m "新增对话模块配置绑定与 effort 参数解析：档位只改温度与上限，不换模型"
```

---

### Task 2: 建表 DDL（关卡 G1）

**Files:**
- Create: `db/migration/2026-10-23-ai-chat-conversation.sql`

**Interfaces:**
- Consumes: Task 1 的 `ChatProperties.defaultModel` / `defaultEffort`（DDL 的列默认值必须与之一致）
- Produces: 主库 `ezpracticeexercises` 里的 `ai_conversation`、`ai_message` 两张表

- [ ] **Step 1: 写 schema 文件**

`db/migration/2026-10-23-ai-chat-conversation.sql`（逐字照抄 spec 3.2，含 `effort` 两列）：

```sql
-- 仿 ChatGPT 对话子系统：2 张表。无物理外键，一致性在应用层。
CREATE TABLE ai_conversation (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_name       VARCHAR(64)  NOT NULL COMMENT '网关注入的 X-User-Name，源自 JWT 的 username claim',
  title           VARCHAR(120) NOT NULL DEFAULT '新对话',
  model_key       VARCHAR(64)  NOT NULL DEFAULT 'deepseek-chat',
  effort          VARCHAR(16)  NOT NULL DEFAULT 'Medium' COMMENT 'Low/Medium/High/Extra/Max，只映射服务端参数',
  last_message_id BIGINT UNSIGNED NULL,
  last_preview    VARCHAR(160) NOT NULL DEFAULT '' COMMENT '列表预览，写入时截断好',
  message_count   INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'user + 当前生效 assistant 条数',
  status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0软删',
  create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_list (user_name, status, update_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 会话';

CREATE TABLE ai_message (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  conversation_id     BIGINT UNSIGNED NOT NULL,
  role                VARCHAR(16)  NOT NULL COMMENT 'user / assistant',
  content             MEDIUMTEXT   NOT NULL COMMENT 'Markdown 源文',
  status              VARCHAR(16)  NOT NULL DEFAULT 'done' COMMENT 'streaming/done/failed/truncated/superseded',
  stop_reason         VARCHAR(24)  NULL COMMENT 'done/truncated 时的收尾原因',
  model_key           VARCHAR(64)  NULL COMMENT 'assistant 实际使用的上游模型',
  effort              VARCHAR(16)  NULL COMMENT '本条回答生效时的档位',
  prompt_tokens       INT UNSIGNED NULL,
  completion_tokens   INT UNSIGNED NULL,
  reply_to_message_id BIGINT UNSIGNED NULL COMMENT 'assistant 指向被回答的那条 user 消息',
  client_msg_id       VARCHAR(40)  NULL COMMENT '前端生成的幂等键',
  error_msg           VARCHAR(255) NULL,
  create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_conv_client (conversation_id, client_msg_id),
  KEY idx_conv_page (conversation_id, id),
  KEY idx_reply_to (reply_to_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 消息';
```

- [ ] **Step 2: 关卡 G1 —— 明确请求用户批准在主库执行**

告知：目标库 `ezpracticeexercises`（本地，root 空密码），执行的是 2 条 `CREATE TABLE`，不改动任何既有表。等用户回"同意"再继续。

- [ ] **Step 3: 用 jshell + JDBC 执行**

```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
cd /f/AAAJAVA/code/ezPracticeExercises
cat > /tmp/run_chat_ddl.jsh <<'EOF'
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
String raw = Files.readString(Path.of("F:/AAAJAVA/code/ezPracticeExercises/db/migration/2026-10-23-ai-chat-conversation.sql"));
String sql = Arrays.stream(raw.split("\n")).filter(l -> !l.trim().startsWith("--")).collect(Collectors.joining("\n"));
Connection c = DriverManager.getConnection("jdbc:mysql://localhost:3306/ezpracticeexercises?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true", "root", "");
int n = 0;
for (String stmt : sql.split(";")) {
  String s = stmt.trim();
  if (s.isEmpty()) continue;
  c.createStatement().execute(s);
  n++;
  System.out.println("OK [" + n + "]: " + s.substring(0, Math.min(46, s.length())).replace('\n', ' '));
}
c.close();
/exit
EOF
"$JAVA_HOME/bin/jshell" --class-path "C:/Users/Administrator/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar" -q /tmp/run_chat_ddl.jsh
```
Expected 输出：
```
OK [1]: CREATE TABLE ai_conversation (   id
BIGINT
OK [2]: CREATE TABLE ai_message (   id
BIGINT
```

- [ ] **Step 4: 验证表结构与索引**

```bash
cat > /tmp/verify_chat_ddl.jsh <<'EOF'
import java.sql.*;
Connection c = DriverManager.getConnection("jdbc:mysql://localhost:3306/ezpracticeexercises?useSSL=false&serverTimezone=Asia/Shanghai","root","");
ResultSet r = c.createStatement().executeQuery("SHOW TABLES LIKE 'ai_%'");
while (r.next()) System.out.println("table=" + r.getString(1));
r = c.createStatement().executeQuery("SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) cols, NON_UNIQUE " +
  "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='ezpracticeexercises' AND TABLE_NAME LIKE 'ai_%' " +
  "GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE ORDER BY TABLE_NAME, INDEX_NAME");
while (r.next()) System.out.println(r.getString(1) + " | " + r.getString(2) + " | " + r.getString(3) + " | nonUnique=" + r.getString(4));
r.close(); c.close();
/exit
EOF
"$JAVA_HOME/bin/jshell" --class-path "C:/Users/Administrator/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar" -q /tmp/verify_chat_ddl.jsh
```
Expected：两张表 + 索引恰好是
```
ai_conversation | PRIMARY | id | nonUnique=0
ai_conversation | idx_user_list | user_name,status,update_time,id | nonUnique=1
ai_message | PRIMARY | id | nonUnique=0
ai_message | idx_conv_page | conversation_id,id | nonUnique=1
ai_message | idx_reply_to | reply_to_message_id | nonUnique=1
ai_message | uk_conv_client | conversation_id,client_msg_id | nonUnique=0
```
若少了 `uk_conv_client` 的 `nonUnique=0`，说明唯一键没建成——停下修 DDL，不要往下走。

- [ ] **Step 5: 提交**

```bash
git add db/migration/2026-10-23-ai-chat-conversation.sql
git commit -m "新增对话子系统两张表 DDL：会话冗余预览、消息带回答归属与幂等唯一键"
```

---

### Task 3: 实体、Mapper 与真库读写（关卡 G2）

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/entity/AiConversation.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/entity/AiMessage.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/mapper/AiConversationMapper.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/mapper/AiMessageMapper.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/support/ChatTime.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/mapper/ChatMapperDbIT.java`

**Interfaces:**
- Consumes: Task 2 的两张表
- Produces:
  - `AiConversation` getter/setter：`id:Long`、`userName:String`、`title:String`、`modelKey:String`、`effort:String`、`lastMessageId:Long`、`lastPreview:String`、`messageCount:Long`、`status:Integer`、`createTime:LocalDateTime`、`updateTime:LocalDateTime`
  - `AiMessage` getter/setter：`id:Long`、`conversationId:Long`、`role:String`、`content:String`、`status:String`、`stopReason:String`、`modelKey:String`、`effort:String`、`promptTokens:Integer`、`completionTokens:Integer`、`replyToMessageId:Long`、`clientMsgId:String`、`errorMsg:String`、`createTime:LocalDateTime`、`updateTime:LocalDateTime`
  - `AiConversationMapper extends BaseMapper<AiConversation>`：`int touchOnNewMessage(Long id, Long lastMessageId, String lastPreview, int delta)`、`int renameIfDefault(Long id, String title)`
  - `AiMessageMapper extends BaseMapper<AiMessage>`：`int supersedePrevious(Long replyTo)`、`int finalizeAssistant(Long id, String content, String status, String stopReason, Integer promptTokens, Integer completionTokens)`、`int markFailed(Long id, String errorMsg)`、`int reapStaleStreaming(Long conversationId, int minutes, String errorMsg)`、`int countLiveStreaming(Long conversationId, int minutes)`
  - `ChatTime.millis(LocalDateTime):Long`、`ChatTime.truncate(String,int):String`
- 状态字面量在 `AiMessage` 上以 `public static final String` 常量的形式给出，其他类禁止手写字符串。

- [ ] **Step 1: 关卡 G2 —— 请求用户批准建测试 schema**

告知：新建 `ezpracticeexercises_chat_it`（与主库同实例、独立 schema），只给集成测试用，跑完不 drop（下次 truncate），不碰 `ezpracticeexercises`。等"同意"。

- [ ] **Step 2: 建 schema 并套用 DDL**

```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
cat > /tmp/make_chat_it_schema.jsh <<'EOF'
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
Connection admin = DriverManager.getConnection("jdbc:mysql://localhost:3306?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true","root","");
admin.createStatement().execute("CREATE DATABASE IF NOT EXISTS ezpracticeexercises_chat_it DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");
System.out.println("schema ready");
String raw = Files.readString(Path.of("F:/AAAJAVA/code/ezPracticeExercises/db/migration/2026-10-23-ai-chat-conversation.sql"));
String sql = Arrays.stream(raw.split("\n")).filter(l -> !l.trim().startsWith("--")).collect(Collectors.joining("\n"));
admin.createStatement().execute("USE ezpracticeexercises_chat_it");
int n = 0;
for (String stmt : sql.split(";")) {
  String s = stmt.trim();
  if (s.isEmpty()) continue;
  admin.createStatement().execute(s);
  n++;
}
System.out.println("statements=" + n);
admin.close();
/exit
EOF
"$JAVA_HOME/bin/jshell" --class-path "C:/Users/Administrator/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar" -q /tmp/make_chat_it_schema.jsh
```
Expected: `schema ready` + `statements=2`。

- [ ] **Step 2b: 在 `AIModule/pom.xml` 的 `surefire.excludedGroups` 里确认含 `db-it`**

Run: `grep -n "surefire.excludedGroups" AIModule/pom.xml`
Expected: 已存在 `<surefire.excludedGroups>es-it,embedding-it,db-it,mq-it</surefire.excludedGroups>`（实测在 `AIModule/pom.xml:209`）。**不需要改 pom**；若该行不含 `db-it` 才补，补了要在提交信息里写明。

- [ ] **Step 3: 写实体**

`entity/AiConversation.java`：

```java
package org.leon.aimodule.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_conversation")
public class AiConversation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String userName;
    private String title;
    private String modelKey;
    private String effort;
    private Long lastMessageId;
    private String lastPreview;
    private Long messageCount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

`entity/AiMessage.java`：

```java
package org.leon.aimodule.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_message")
public class AiMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public static final String ST_STREAMING = "streaming";
    public static final String ST_DONE = "done";
    public static final String ST_FAILED = "failed";
    public static final String ST_TRUNCATED = "truncated";
    public static final String ST_SUPERSEDED = "superseded";

    public static final String STOP_DONE = "done";
    public static final String STOP_STOPPED = "stopped";
    public static final String STOP_LENGTH = "length";
    public static final String STOP_UPSTREAM = "upstream";
    public static final String STOP_CLIENT_DISCONNECT = "client_disconnect";
    public static final String STOP_TIMEOUT = "timeout";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private String role;
    private String content;
    private String status;
    private String stopReason;
    private String modelKey;
    private String effort;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long replyToMessageId;
    private String clientMsgId;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

- [ ] **Step 4: 写 `ChatTime`**

```java
package org.leon.aimodule.chat.support;

import java.time.LocalDateTime;
import java.time.ZoneId;

public final class ChatTime {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private ChatTime() {
    }

    /** 出参时间一律 epoch 毫秒，避免全局 ObjectMapper 被改动。 */
    public static Long millis(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toInstant().toEpochMilli();
    }

    /** 按码点截断，不留半个代理对。 */
    public static String truncate(String text, int maxCp) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.codePointCount(0, flat.length()) <= maxCp) {
            return flat;
        }
        int end = flat.offsetByCodePoints(0, maxCp);
        return flat.substring(0, end) + "…";
    }
}
```

- [ ] **Step 5: 写两个 Mapper**

`mapper/AiConversationMapper.java`：

```java
package org.leon.aimodule.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.leon.aimodule.chat.entity.AiConversation;

@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {

    @Update("UPDATE ai_conversation SET last_message_id = #{lastMessageId}, last_preview = #{lastPreview}, "
            + "message_count = message_count + #{delta}, update_time = NOW(3) WHERE id = #{id}")
    int touchOnNewMessage(@Param("id") Long id, @Param("lastMessageId") Long lastMessageId,
                          @Param("lastPreview") String lastPreview, @Param("delta") int delta);

    @Update("UPDATE ai_conversation SET title = #{title}, update_time = NOW(3) "
            + "WHERE id = #{id} AND title = '新对话'")
    int renameIfDefault(@Param("id") Long id, @Param("title") String title);
}
```

`mapper/AiMessageMapper.java`：

```java
package org.leon.aimodule.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.leon.aimodule.chat.entity.AiMessage;

@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    @Update("UPDATE ai_message SET status = 'superseded', update_time = NOW(3) "
            + "WHERE reply_to_message_id = #{replyTo} AND role = 'assistant' AND status <> 'superseded'")
    int supersedePrevious(@Param("replyTo") Long replyTo);

    @Update("UPDATE ai_message SET content = #{content}, status = #{status}, stop_reason = #{stopReason}, "
            + "prompt_tokens = #{promptTokens}, completion_tokens = #{completionTokens}, update_time = NOW(3) "
            + "WHERE id = #{id}")
    int finalizeAssistant(@Param("id") Long id, @Param("content") String content,
                          @Param("status") String status, @Param("stopReason") String stopReason,
                          @Param("promptTokens") Integer promptTokens,
                          @Param("completionTokens") Integer completionTokens);

    @Update("UPDATE ai_message SET status = 'failed', error_msg = #{errorMsg}, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 'streaming'")
    int markFailed(@Param("id") Long id, @Param("errorMsg") String errorMsg);

    @Update("UPDATE ai_message SET status = 'failed', error_msg = #{errorMsg}, update_time = NOW(3) "
            + "WHERE conversation_id = #{conversationId} AND role = 'assistant' AND status = 'streaming' "
            + "AND create_time < DATE_SUB(NOW(3), INTERVAL #{minutes} MINUTE)")
    int reapStaleStreaming(@Param("conversationId") Long conversationId, @Param("minutes") int minutes,
                           @Param("errorMsg") String errorMsg);

    @Select("SELECT COUNT(1) FROM ai_message WHERE conversation_id = #{conversationId} "
            + "AND role = 'assistant' AND status = 'streaming' "
            + "AND create_time >= DATE_SUB(NOW(3), INTERVAL #{minutes} MINUTE)")
    int countLiveStreaming(@Param("conversationId") Long conversationId, @Param("minutes") int minutes);
}
```

`import org.apache.ibatis.annotations.Select;` 与 `@Select` 是配套的。实测纠正（Task 3 执行时验证）：计数查询若误标 `@Update`，MyBatis 走 `PreparedStatementHandler.update()` → `getUpdateCount()`，JDBC 对"结果是 ResultSet"定义返回 **-1**，所以**不会抛异常，而是静默返回 -1**——比抛异常更危险，因为 Task 10 的并发判断会变成 `-1 > 0` 恒假、流永远认为没有并发。`ChatMapperDbIT` 里 `assertEquals(1, mapper.countLiveStreaming(...))` 是唯一能抓住它的断言。

- [ ] **Step 6: 写真库集成测试**

`AIModule/src/test/java/org/leon/aimodule/chat/mapper/ChatMapperDbIT.java`。手工搭 `SqlSessionFactory`，不启 Spring 上下文——避免被 Nacos 配置导入与 Seata 拖住，测的仍是真 MySQL 与真 Mapper SQL：

```java
package org.leon.aimodule.chat.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.entity.AiMessage;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("db-it")
class ChatMapperDbIT {

    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl("jdbc:mysql://localhost:3306/ezpracticeexercises_chat_it"
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        ds.setUser("root");
        ds.setPassword("");
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setEnvironment(new Environment("db-it", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(AiConversationMapper.class);
        cfg.addMapper(AiMessageMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
    }

    @BeforeEach
    void clean() {
        try (SqlSession s = factory.openSession(true)) {
            s.getConnection().createStatement().execute("DELETE FROM ai_message");
            s.getConnection().createStatement().execute("DELETE FROM ai_conversation");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Long newConversation(SqlSession s, String userName) {
        AiConversation c = new AiConversation();
        c.setUserName(userName);
        s.getMapper(AiConversationMapper.class).insert(c);
        assertNotNull(c.getId(), "自增主键必须回填到实体");
        return c.getId();
    }

    private Long newMessage(SqlSession s, Long cid, String role, String clientMsgId) {
        AiMessage m = new AiMessage();
        m.setConversationId(cid);
        m.setRole(role);
        m.setContent("x");
        m.setClientMsgId(clientMsgId);
        s.getMapper(AiMessageMapper.class).insert(m);
        return m.getId();
    }

    @Test
    @DisplayName("NOT NULL 列由 DDL 默认值补齐，DB 托管时间非空")
    void appliesDbDefaults() {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiConversation c = s.getMapper(AiConversationMapper.class).selectById(cid);
            assertEquals("新对话", c.getTitle());
            assertEquals("deepseek-chat", c.getModelKey());
            assertEquals("Medium", c.getEffort());
            assertEquals(0L, c.getMessageCount());
            assertEquals(1, c.getStatus());
            assertNotNull(c.getCreateTime());
            assertNotNull(c.getUpdateTime());
        }
    }

    @Test
    @DisplayName("assistant 行不填 client_msg_id，同会话多条不撞唯一键")
    void nullClientMsgIdDoesNotCollide() {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiMessageMapper mapper = s.getMapper(AiMessageMapper.class);
            for (int i = 0; i < 3; i++) {
                AiMessage m = new AiMessage();
                m.setConversationId(cid);
                m.setRole(AiMessage.ROLE_ASSISTANT);
                m.setContent("answer " + i);
                m.setStatus(AiMessage.ST_DONE);
                mapper.insert(m);
                assertNotNull(m.getId());
            }
            assertEquals(3, mapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiMessage>()
                            .eq(AiMessage::getConversationId, cid)));
        }
    }

    @Test
    @DisplayName("同一 user 消息下的旧 assistant 被 supersede，新的保持 done")
    void supersedeOnlyTargetsSameReplyTo() {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiMessageMapper mapper = s.getMapper(AiMessageMapper.class);
            Long user1 = newMessage(s, cid, AiMessage.ROLE_USER, "u1");
            Long user2 = newMessage(s, cid, AiMessage.ROLE_USER, "u2");

            AiMessage old = new AiMessage();
            old.setConversationId(cid);
            old.setRole(AiMessage.ROLE_ASSISTANT);
            old.setContent("v1");
            old.setStatus(AiMessage.ST_DONE);
            old.setReplyToMessageId(user1);
            mapper.insert(old);

            AiMessage other = new AiMessage();
            other.setConversationId(cid);
            other.setRole(AiMessage.ROLE_ASSISTANT);
            other.setContent("for u2");
            other.setStatus(AiMessage.ST_DONE);
            other.setReplyToMessageId(user2);
            mapper.insert(other);

            assertEquals(1, mapper.supersedePrevious(user1));
            assertEquals(AiMessage.ST_SUPERSEDED, mapper.selectById(old.getId()).getStatus());
            assertEquals(AiMessage.ST_DONE, mapper.selectById(other.getId()).getStatus(), "不能越界改别的回答");
        }
    }

    @Test
    @DisplayName("只回收超时的 streaming 行，未超时与已完成的不动")
    void reapOnlyStaleStreaming() {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiMessageMapper mapper = s.getMapper(AiMessageMapper.class);

            AiMessage live = new AiMessage();
            live.setConversationId(cid);
            live.setRole(AiMessage.ROLE_ASSISTANT);
            live.setContent("");
            live.setStatus(AiMessage.ST_STREAMING);
            mapper.insert(live);

            AiMessage stale = new AiMessage();
            stale.setConversationId(cid);
            stale.setRole(AiMessage.ROLE_ASSISTANT);
            stale.setContent("");
            stale.setStatus(AiMessage.ST_STREAMING);
            mapper.insert(stale);
            s.getConnection().createStatement().execute(
                    "UPDATE ai_message SET create_time = DATE_SUB(NOW(3), INTERVAL 30 MINUTE) WHERE id = " + stale.getId());

            assertEquals(1, mapper.countLiveStreaming(cid, 5), "只有未超时的算活跃");
            assertEquals(1, mapper.reapStaleStreaming(cid, 5, "生成中断"));
            assertEquals(AiMessage.ST_FAILED, mapper.selectById(stale.getId()).getStatus());
            assertEquals("生成中断", mapper.selectById(stale.getId()).getErrorMsg());
            assertEquals(AiMessage.ST_STREAMING, mapper.selectById(live.getId()).getStatus());
        }
    }

    @Test
    @DisplayName("touchOnNewMessage 累加计数，renameIfDefault 只改默认标题")
    void touchAndConditionalRename() {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiConversationMapper cm = s.getMapper(AiConversationMapper.class);
            AiMessageMapper mm = s.getMapper(AiMessageMapper.class);
            Long mid = newMessage(s, cid, AiMessage.ROLE_USER, "u1");

            assertEquals(1, cm.touchOnNewMessage(cid, mid, "把这段改成 STAR", 1));
            AiConversation c = cm.selectById(cid);
            assertEquals(1L, c.getMessageCount());
            assertEquals(mid, c.getLastMessageId());
            assertEquals("把这段改成 STAR", c.getLastPreview());

            assertEquals(1, cm.renameIfDefault(cid, "把这段改成"));
            assertEquals("把这段改成", cm.selectById(cid).getTitle());
            assertEquals(0, cm.renameIfDefault(cid, "不该覆盖"), "已改名的会话不再被自动改名");
            assertEquals("把这段改成", cm.selectById(cid).getTitle());

            cm.touchOnNewMessage(cid, mid, "x", -1);
            assertEquals(0L, cm.selectById(cid).getMessageCount());
            assertNull(cm.selectById(999999L), "不存在的会话返回 null");
            List<AiConversation> all = cm.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiConversation>()
                            .eq(AiConversation::getUserName, "alice"));
            assertEquals(1, all.size());
        }
    }

    @Test
    @DisplayName("finalizeAssistant 一次写完正文与用量")
    void finalizeWritesEverythingOnce() {
        try (SqlSession s = factory.openSession(true)) {
            Long cid = newConversation(s, "alice");
            AiMessageMapper mm = s.getMapper(AiMessageMapper.class);
            AiMessage m = new AiMessage();
            m.setConversationId(cid);
            m.setRole(AiMessage.ROLE_ASSISTANT);
            m.setContent("");
            m.setStatus(AiMessage.ST_STREAMING);
            mm.insert(m);

            LocalDateTime before = mm.selectById(m.getId()).getUpdateTime();
            assertEquals(1, mm.finalizeAssistant(m.getId(), "## 正文", AiMessage.ST_DONE,
                    AiMessage.STOP_DONE, 812, 1640));
            AiMessage done = mm.selectById(m.getId());
            assertEquals("## 正文", done.getContent());
            assertEquals(AiMessage.ST_DONE, done.getStatus());
            assertEquals(812, done.getPromptTokens());
            assertEquals(1640, done.getCompletionTokens());
            assertTrue(!done.getUpdateTime().isBefore(before));
        }
    }
}
```

- [ ] **Step 7: 跑集成测试**

```bash
cd /f/AAAJAVA/code/ezPracticeExercises
"$MVN" -o -pl AIModule -am test -Dtest=ChatMapperDbIT -Dgroups=db-it -Dsurefire.excludedGroups= \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `Tests run: 6, Failures: 0, Errors: 0`。若 `countLiveStreaming` 报 `Unsupported operation`，回到 Step 5 把 `@Update` 改成 `@Select`。

- [ ] **Step 8: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/chat/entity/ \
        AIModule/src/main/java/org/leon/aimodule/chat/mapper/ \
        AIModule/src/main/java/org/leon/aimodule/chat/support/ChatTime.java \
        AIModule/src/test/java/org/leon/aimodule/chat/mapper/ChatMapperDbIT.java
git commit -m "新增对话实体与 Mapper：supersede、超时回收、条件改名等写路径以真库集成测试锁定"
```

---

### Task 4: 游标编解码

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/support/CursorCodec.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/support/CursorCodecTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `CursorCodec.encode(LocalDateTime updateTime, long id):String`、`CursorCodec.decode(String):CursorCodec.Cursor|null`、`record Cursor(LocalDateTime updateTime, long id)`。非法输入抛 `ChatRejectException("cursor 无效")`，**绝不抛 `IllegalArgumentException`/`NumberFormatException`**。

- [ ] **Step 1: 写失败测试**

```java
package org.leon.aimodule.chat.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.exception.ChatRejectException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorCodecTest {

    @Test
    @DisplayName("编解码往返保持毫秒与 id")
    void roundTrips() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 23, 9, 8, 7, 123_000_000);
        String cursor = CursorCodec.encode(t, 4242L);
        assertTrue(cursor.matches("[A-Za-z0-9_-]+"), "必须是 URL 安全的 base64，能直接放 query: " + cursor);
        CursorCodec.Cursor back = CursorCodec.decode(cursor);
        assertEquals(t, back.updateTime());
        assertEquals(4242L, back.id());
    }

    @Test
    @DisplayName("空游标表示第一页")
    void blankMeansFirstPage() {
        assertNull(CursorCodec.decode(null));
        assertNull(CursorCodec.decode(""));
        assertNull(CursorCodec.decode("   "));
    }

    @Test
    @DisplayName("伪造或截断的游标翻成业务拒绝，而不是 NumberFormatException")
    void rejectsGarbage() {
        for (String bad : new String[]{"!!!", "aGVsbG8", "MTIz", "abc:def", "MTIzOg==", ":"}) {
            ChatRejectException e = assertThrows(ChatRejectException.class, CursorCodec.decode(bad),
                    "应当拒绝: " + bad);
            assertEquals("cursor 无效", e.getMessage());
        }
    }

    @Test
    @DisplayName("负数时间戳与超长数字不崩")
    void survivesExtremeNumbers() {
        CursorCodec.Cursor c = CursorCodec.decode(
                java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString((-9999999999999L + ":" + Long.MAX_VALUE).getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(Long.MAX_VALUE, c.id());
        assertEquals(-9999999999999L,
                c.updateTime().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
"$MVN" -o -pl AIModule -am test -Dtest=CursorCodecTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译错误 `cannot find symbol: class CursorCodec`。

- [ ] **Step 3: 实现**

```java
package org.leon.aimodule.chat.support;

import org.leon.aimodule.chat.exception.ChatRejectException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;

/**
 * 会话列表游标 = base64url("<updateTime 毫秒>:<id>")，降序翻页。
 * 不签名、不加密：它只是分页位置，越权由 user_name 条件挡住。
 */
public final class CursorCodec {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private CursorCodec() {
    }

    public record Cursor(LocalDateTime updateTime, long id) {
    }

    public static String encode(LocalDateTime updateTime, long id) {
        long millis = updateTime.atZone(ZONE).toInstant().toEpochMilli();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((millis + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ChatRejectException("cursor 无效");
        }
        int sep = raw.lastIndexOf(':');
        if (sep <= 0 || sep == raw.length() - 1) {
            throw new ChatRejectException("cursor 无效");
        }
        try {
            long millis = Long.parseLong(raw.substring(0, sep));
            long id = Long.parseLong(raw.substring(sep + 1));
            return new Cursor(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE), id);
        } catch (NumberFormatException | java.time.DateTimeException e) {
            throw new ChatRejectException("cursor 无效");
        }
    }
}
```

> `aGVsbG8` 解出 `hello`（无冒号 → `sep=-1`）、`MTIz` 解出 `123`（无冒号）、`abc:def` 的 `def` 不是数字、`MTIzOg==` 解出 `123:`（冒号在末尾）——四条都走 `ChatRejectException`。`LocalDateTime.ofInstant` 对极端负值会抛 `DateTimeException`，所以它也必须在 catch 里。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: `Tests run: 4, Failures: 0`。

- [ ] **Step 5: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/chat/support/CursorCodec.java \
        AIModule/src/test/java/org/leon/aimodule/chat/support/CursorCodecTest.java
git commit -m "新增会话游标编解码：伪造输入统一翻成业务拒绝"
```

---

### Task 5: 控制面接口 #1–#5

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/web/dto/ModelVo.java`、`ModelCatalogVo.java`、`CreateConversationRequest.java`、`RenameConversationRequest.java`、`ConversationVo.java`、`ConversationPageVo.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/service/ConversationService.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/web/ChatConversationController.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/web/ChatExceptionHandler.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/service/ConversationServiceTest.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/web/ChatConversationControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `ChatProperties`/`EffortResolver`、Task 3 `AiConversationMapper`/`AiConversation`/`ChatTime`、Task 4 `CursorCodec`
- Produces:
  - `record ModelVo(String key, String name, String tag, boolean supportsThinking)`
  - `record ModelCatalogVo(String defaultModel, String defaultEffort, List<String> efforts, List<ModelVo> models)`
  - `record ConversationVo(Long id, String title, String modelKey, String effort, Long messageCount, String lastPreview, Long updateTime)`
  - `record ConversationPageVo(List<ConversationVo> items, String nextCursor)`
  - `record CreateConversationRequest(String title, String modelKey, String effort)`
  - `record RenameConversationRequest(String title)`（`@NotBlank @Size(min=1,max=120)`）
  - `ConversationService.catalog():ModelCatalogVo`
  - `ConversationService.create(String userName, CreateConversationRequest req):ConversationVo`
  - `ConversationService.list(String userName, String cursor, int limit):ConversationPageVo`
  - `ConversationService.rename(String userName, Long id, String title):void`
  - `ConversationService.softDelete(String userName, Long id):void`
  - `ConversationService.requireOwned(String userName, Long id):AiConversation`（后续任务复用；不存在/非本人/已软删 → `ChatRejectException("会话不存在")`）
  - `ConversationService.clampLimit(int limit, int max):int`

- [ ] **Step 1: 写失败测试 `ConversationServiceTest`**

```java
package org.leon.aimodule.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.mapper.AiConversationMapper;
import org.leon.aimodule.chat.mapper.AiMessageMapper;
import org.leon.aimodule.chat.web.dto.ConversationPageVo;
import org.leon.aimodule.chat.web.dto.ConversationVo;
import org.leon.aimodule.chat.web.dto.CreateConversationRequest;
import org.leon.aimodule.chat.web.dto.ModelCatalogVo;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    AiConversationMapper conversationMapper;
    @Mock
    AiMessageMapper messageMapper;

    private ChatProperties props;
    private ConversationService service;

    private ConversationService newService() {
        props = new ChatProperties();
        ChatProperties.Model m = new ChatProperties.Model();
        m.setKey("deepseek-chat");
        m.setName("deepseek-chat");
        m.setTag("Fast");
        ChatProperties.Model r = new ChatProperties.Model();
        r.setKey("deepseek-reasoner");
        r.setName("deepseek-reasoner");
        r.setSupportsThinking(true);
        props.setModels(new ArrayList<>(List.of(m, r)));
        List<ChatProperties.Effort> efforts = new ArrayList<>();
        for (String k : List.of("Low", "Medium", "High", "Extra", "Max")) {
            ChatProperties.Effort e = new ChatProperties.Effort();
            e.setKey(k);
            e.setTemperature(1.0);
            e.setMaxTokens(4096);
            efforts.add(e);
        }
        props.setEfforts(efforts);
        service = new ConversationService(props, new EffortResolver(props), conversationMapper, messageMapper);
        return service;
    }

    @Test
    @DisplayName("#1 目录：efforts 是字符串数组，model 项带 tag 与 supportsThinking")
    void catalogMatchesPromptBarProps() {
        newService();
        ModelCatalogVo vo = service.catalog();
        assertEquals("deepseek-chat", vo.defaultModel());
        assertEquals("Medium", vo.defaultEffort());
        assertEquals(List.of("Low", "Medium", "High", "Extra", "Max"), vo.efforts());
        assertEquals(2, vo.models().size());
        assertEquals("Fast", vo.models().get(0).tag());
        assertTrue(vo.models().get(1).supportsThinking());
        assertTrue(!vo.models().get(0).supportsThinking());
    }

    @Test
    @DisplayName("创建会话：标题缺省存“新对话”，模型与档位缺省落配置默认值")
    void createAppliesDefaults() {
        newService();
        when(conversationMapper.insert(any(AiConversation.class))).thenAnswer(inv -> {
            inv.getArgument(0, AiConversation.class).setId(18L);
            return 1;
        });
        ConversationVo vo = service.create("alice", new CreateConversationRequest(null, null, null));
        ArgumentCaptor<AiConversation> cap = ArgumentCaptor.forClass(AiConversation.class);
        verify(conversationMapper).insert(cap.capture());
        assertEquals("alice", cap.getValue().getUserName());
        assertEquals("新对话", cap.getValue().getTitle());
        assertEquals("deepseek-chat", cap.getValue().getModelKey());
        assertEquals("Medium", cap.getValue().getEffort());
        assertEquals(18L, vo.id());
        assertEquals("新对话", vo.title());
    }

    @Test
    @DisplayName("创建会话：未知模型或未知档位被拒，且不落库")
    void createRejectsUnknownKeys() {
        newService();
        assertThrows(ChatRejectException.class,
                () -> service.create("alice", new CreateConversationRequest("t", "gpt-6", "Medium")));
        assertThrows(ChatRejectException.class,
                () -> service.create("alice", new CreateConversationRequest("t", "deepseek-chat", "Ultra")));
        verify(conversationMapper, never()).insert(any(AiConversation.class));
    }

    @Test
    @DisplayName("标题超 120 字符直接截断而不是报错")
    void createTruncatesLongTitle() {
        newService();
        when(conversationMapper.insert(any(AiConversation.class))).thenAnswer(inv -> {
            inv.getArgument(0, AiConversation.class).setId(19L);
            return 1;
        });
        ConversationVo vo = service.create("alice",
                new CreateConversationRequest("长".repeat(200), null, null));
        assertEquals(120, vo.title().length());
    }

    @Test
    @DisplayName("归属校验：非本人或已软删都返回“会话不存在”同一文案")
    void ownershipMessagesAreIdentical() {
        newService();
        AiConversation other = conversation(1L, "bob", 1);
        when(conversationMapper.selectById(1L)).thenReturn(other);
        ChatRejectException e1 = assertThrows(ChatRejectException.class,
                () -> service.requireOwned("alice", 1L));
        assertEquals("会话不存在", e1.getMessage());

        when(conversationMapper.selectById(2L)).thenReturn(conversation(2L, "alice", 0));
        ChatRejectException e2 = assertThrows(ChatRejectException.class,
                () -> service.requireOwned("alice", 2L));
        assertEquals("会话不存在", e2.getMessage());

        when(conversationMapper.selectById(3L)).thenReturn(null);
        assertEquals("会话不存在",
                assertThrows(ChatRejectException.class, () -> service.requireOwned("alice", 3L)).getMessage());
    }

    @Test
    @DisplayName("软删只置 status=0，不物理删除")
    void softDeleteUpdatesStatus() {
        newService();
        when(conversationMapper.selectById(1L)).thenReturn(conversation(1L, "alice", 1));
        service.softDelete("alice", 1L);
        ArgumentCaptor<AiConversation> cap = ArgumentCaptor.forClass(AiConversation.class);
        verify(conversationMapper).updateById(cap.capture());
        assertEquals(0, cap.getValue().getStatus());
        assertEquals(1L, cap.getValue().getId());
    }

    @Test
    @DisplayName("limit 越界被夹住，max 生效")
    void clampsLimit() {
        newService();
        assertEquals(1, service.clampLimit(0, 50));
        assertEquals(20, service.clampLimit(20, 50));
        assertEquals(50, service.clampLimit(9999, 50));
    }

    @Test
    @DisplayName("列表多取一条判断是否有下一页，并给出 nextCursor")
    void listDetectsNextPage() {
        newService();
        List<AiConversation> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(conversation((long) (10 - i), "alice", 1));
        }
        when(conversationMapper.selectList(any())).thenReturn(rows);
        ConversationPageVo page = service.list("alice", null, 2);
        assertEquals(2, page.items().size());
        assertTrue(page.nextCursor() != null && !page.nextCursor().isBlank());
    }

    @Test
    @DisplayName("列表不足一页时 nextCursor 为 null")
    void listLastPageHasNoCursor() {
        newService();
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation(9L, "alice", 1)));
        ConversationPageVo page = service.list("alice", null, 20);
        assertEquals(1, page.items().size());
        assertNull(page.nextCursor());
    }

    private static AiConversation conversation(Long id, String user, int status) {
        AiConversation c = new AiConversation();
        c.setId(id);
        c.setUserName(user);
        c.setTitle("t" + id);
        c.setModelKey("deepseek-chat");
        c.setEffort("Medium");
        c.setMessageCount(2L);
        c.setLastPreview("预览");
        c.setStatus(status);
        c.setUpdateTime(LocalDateTime.of(2026, 10, 23, 9, 0, 0, (int) (id * 1_000_000)));
        return c;
    }
}
```

> `@InjectMocks` 与 `newService()` 二者取其一即可，本测试用 `newService()` 手工构造，**删掉 `@InjectMocks` 注解**以免 Mockito 先注入一个 `props=null` 的实例。落地时把 `@InjectMocks ConversationService service;` 改成普通字段 `private ConversationService service;`。

- [ ] **Step 2: 跑测试确认失败**

```bash
"$MVN" -o -pl AIModule -am test -Dtest=ConversationServiceTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译错误（`ConversationService` 等类不存在）。

- [ ] **Step 3: 写 6 个 DTO**

```java
// web/dto/ModelVo.java
package org.leon.aimodule.chat.web.dto;

/** 形状对齐前端 PromptBarModel = {key, name, tag?}，supportsThinking 供思考线开关。 */
public record ModelVo(String key, String name, String tag, boolean supportsThinking) {
}
```

```java
// web/dto/ModelCatalogVo.java
package org.leon.aimodule.chat.web.dto;

import java.util.List;

public record ModelCatalogVo(String defaultModel, String defaultEffort,
                             List<String> efforts, List<ModelVo> models) {
}
```

```java
// web/dto/CreateConversationRequest.java
package org.leon.aimodule.chat.web.dto;

import jakarta.validation.constraints.Size;

public record CreateConversationRequest(
        @Size(max = 120, message = "标题最长 120 字符") String title,
        String modelKey,
        String effort) {
}
```

```java
// web/dto/RenameConversationRequest.java
package org.leon.aimodule.chat.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameConversationRequest(
        @NotBlank(message = "标题不能为空") @Size(max = 120, message = "标题最长 120 字符") String title) {
}
```

```java
// web/dto/ConversationVo.java
package org.leon.aimodule.chat.web.dto;

public record ConversationVo(Long id, String title, String modelKey, String effort,
                             Long messageCount, String lastPreview, Long updateTime) {
}
```

```java
// web/dto/ConversationPageVo.java
package org.leon.aimodule.chat.web.dto;

import java.util.List;

public record ConversationPageVo(List<ConversationVo> items, String nextCursor) {
}
```

- [ ] **Step 4: 写 `ConversationService`**

```java
package org.leon.aimodule.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.mapper.AiConversationMapper;
import org.leon.aimodule.chat.mapper.AiMessageMapper;
import org.leon.aimodule.chat.support.ChatTime;
import org.leon.aimodule.chat.support.CursorCodec;
import org.leon.aimodule.chat.web.dto.ConversationPageVo;
import org.leon.aimodule.chat.web.dto.ConversationVo;
import org.leon.aimodule.chat.web.dto.CreateConversationRequest;
import org.leon.aimodule.chat.web.dto.ModelCatalogVo;
import org.leon.aimodule.chat.web.dto.ModelVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConversationService {

    static final String DEFAULT_TITLE = "新对话";
    static final int PREVIEW_CP = 80;

    private final ChatProperties props;
    private final EffortResolver resolver;
    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;

    public ConversationService(ChatProperties props, EffortResolver resolver,
                              AiConversationMapper conversationMapper, AiMessageMapper messageMapper) {
        this.props = props;
        this.resolver = resolver;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    public ModelCatalogVo catalog() {
        return new ModelCatalogVo(props.getDefaultModel(), props.getDefaultEffort(),
                props.getEfforts().stream().map(ChatProperties.Effort::getKey).toList(),
                props.getModels().stream()
                        .map(m -> new ModelVo(m.getKey(), m.getName(), m.getTag(), m.isSupportsThinking()))
                        .toList());
    }

    @Transactional
    public ConversationVo create(String userName, CreateConversationRequest req) {
        ModelParams params = resolver.resolve(req == null ? null : req.modelKey(),
                req == null ? null : req.effort());
        AiConversation c = new AiConversation();
        c.setUserName(userName);
        c.setTitle(defaultTitle(req == null ? null : req.title()));
        c.setModelKey(params.modelKey());
        c.setEffort(effortOf(req));
        conversationMapper.insert(c);
        return toVo(c);
    }

    public ConversationPageVo list(String userName, String cursor, int limit) {
        int size = clampLimit(limit, 50);
        CursorCodec.Cursor c = CursorCodec.decode(cursor);
        LambdaQueryWrapper<AiConversation> q = new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserName, userName)
                .eq(AiConversation::getStatus, 1);
        if (c != null) {
            q.and(w -> w.lt(AiConversation::getUpdateTime, c.updateTime())
                    .or(x -> x.eq(AiConversation::getUpdateTime, c.updateTime())
                            .lt(AiConversation::getId, c.id())));
        }
        q.orderByDesc(AiConversation::getUpdateTime).orderByDesc(AiConversation::getId)
                .last("LIMIT " + (size + 1));
        List<AiConversation> rows = conversationMapper.selectList(q);
        boolean more = rows.size() > size;
        List<AiConversation> page = more ? rows.subList(0, size) : rows;
        List<ConversationVo> items = page.stream().map(this::toVo).toList();
        String next = (more && !page.isEmpty())
                ? CursorCodec.encode(page.get(page.size() - 1).getUpdateTime(),
                        page.get(page.size() - 1).getId())
                : null;
        return new ConversationPageVo(items, next);
    }

    @Transactional
    public void rename(String userName, Long id, String title) {
        requireOwned(userName, id);
        AiConversation upd = new AiConversation();
        upd.setId(id);
        upd.setTitle(ChatTime.truncate(title, 120));
        conversationMapper.updateById(upd);
    }

    @Transactional
    public void softDelete(String userName, Long id) {
        requireOwned(userName, id);
        AiConversation upd = new AiConversation();
        upd.setId(id);
        upd.setStatus(0);
        conversationMapper.updateById(upd);
    }

    public AiConversation requireOwned(String userName, Long id) {
        AiConversation c = id == null ? null : conversationMapper.selectById(id);
        if (c == null || !c.getUserName().equals(userName) || c.getStatus() == null || c.getStatus() != 1) {
            throw new ChatRejectException("会话不存在");
        }
        return c;
    }

    public int clampLimit(int limit, int max) {
        return Math.min(Math.max(limit, 1), max);
    }

    private String defaultTitle(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_TITLE;
        }
        return ChatTime.truncate(title, 120);
    }

    private String effortOf(CreateConversationRequest req) {
        String ek = req == null || req.effort() == null || req.effort().isBlank()
                ? props.getDefaultEffort() : req.effort().trim();
        return ek;
    }

    ConversationVo toVo(AiConversation c) {
        return new ConversationVo(c.getId(), c.getTitle(), c.getModelKey(), c.getEffort(),
                c.getMessageCount(), c.getLastPreview(), ChatTime.millis(c.getUpdateTime()));
    }
}
```

> `create()` 里 `resolver.resolve(...)` 负责校验两个 key，`effortOf` 只做默认值回填——校验与取值分开的代价是多一次 `props.effort()` 查找，换来的是 `ModelParams` 不带 effort 也不会丢档位值。`DEFAULT_TITLE` 与 Mapper 里 `renameIfDefault` 的 `'新对话'` 字面量必须一致，Task 10 会有一条测试同时断言两处。

- [ ] **Step 5: 写 `ChatExceptionHandler`**

```java
package org.leon.aimodule.chat.web;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.commonjwt.pojo.Result;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 只覆盖 chat 包。不扩宽 rag 包那个 advice 的作用范围：
 * RAG 侧已有测试依赖它当前的映射，合并会把两边异常行为一起改掉。
 * 全部返回 HTTP 200 + Result(code=0)，与仓库既有约定一致。
 */
@Slf4j
@RestControllerAdvice(basePackages = "org.leon.aimodule.chat")
public class ChatExceptionHandler {

    @ExceptionHandler(ChatRejectException.class)
    public Result<Void> reject(ChatRejectException e) {
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Result.error("参数校验失败: " + msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> violated(ConstraintViolationException e) {
        return Result.error("参数校验失败: " + e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public Result<Void> missingHeader(MissingRequestHeaderException e) {
        return Result.error("缺少请求头: " + e.getHeaderName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> missingParam(MissingServletRequestParameterException e) {
        return Result.error("缺少参数: " + e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> other(Exception e) {
        log.error("[chat] 未预期异常", e);
        return Result.error("服务内部错误");
    }
}
```

- [ ] **Step 6: 写 `ChatConversationController`**

```java
package org.leon.aimodule.chat.web;

import jakarta.validation.Valid;
import org.leon.aimodule.chat.service.ConversationService;
import org.leon.aimodule.chat.web.dto.ConversationPageVo;
import org.leon.aimodule.chat.web.dto.ConversationVo;
import org.leon.aimodule.chat.web.dto.CreateConversationRequest;
import org.leon.aimodule.chat.web.dto.ModelCatalogVo;
import org.leon.aimodule.chat.web.dto.RenameConversationRequest;
import org.leon.commonjwt.pojo.Result;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/chat")
@Validated
public class ChatConversationController {

    private final ConversationService service;

    public ChatConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping("/models")
    public Result<ModelCatalogVo> models() {
        return Result.success(service.catalog());
    }

    @PostMapping("/conversations")
    public Result<ConversationVo> create(@RequestHeader("X-User-Name") String userName,
                                        @RequestBody(required = false) @Valid CreateConversationRequest req) {
        return Result.success(service.create(userName, req));
    }

    @GetMapping("/conversations")
    public Result<ConversationPageVo> list(@RequestHeader("X-User-Name") String userName,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "20") int limit) {
        return Result.success(service.list(userName, cursor, limit));
    }

    @PatchMapping("/conversations/{id}")
    public Result<Void> rename(@RequestHeader("X-User-Name") String userName,
                              @PathVariable Long id,
                              @RequestBody @Valid RenameConversationRequest req) {
        service.rename(userName, id, req.title());
        return Result.success();
    }

    @DeleteMapping("/conversations/{id}")
    public Result<Void> delete(@RequestHeader("X-User-Name") String userName, @PathVariable Long id) {
        service.softDelete(userName, id);
        return Result.success();
    }
}
```

- [ ] **Step 7: 写失败测试 `ChatConversationControllerTest`（MockMvc standalone，不启 Spring）**

```java
package org.leon.aimodule.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.mapper.AiConversationMapper;
import org.leon.aimodule.chat.mapper.AiMessageMapper;
import org.leon.aimodule.chat.service.ConversationService;
import org.leon.aimodule.chat.service.EffortResolver;
import org.mockito.ArgumentMatchers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatConversationControllerTest {

    private final ObjectMapper json = new ObjectMapper();
    private AiConversationMapper conversationMapper;
    private ConversationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ChatProperties props = new ChatProperties();
        ChatProperties.Model m = new ChatProperties.Model();
        m.setKey("deepseek-chat");
        m.setName("deepseek-chat");
        m.setTag("Fast");
        props.setModels(new ArrayList<>(List.of(m)));
        List<ChatProperties.Effort> efforts = new ArrayList<>();
        for (String k : List.of("Low", "Medium", "High", "Extra", "Max")) {
            ChatProperties.Effort e = new ChatProperties.Effort();
            e.setKey(k);
            e.setTemperature(1.0);
            e.setMaxTokens(4096);
            efforts.add(e);
        }
        props.setEfforts(efforts);
        conversationMapper = mock(AiConversationMapper.class);
        service = new ConversationService(props, new EffortResolver(props), conversationMapper,
                mock(AiMessageMapper.class));
        mvc = MockMvcBuilders.standaloneSetup(new ChatConversationController(service))
                .setControllerAdvice(new ChatExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("#1 models 接口返回 efforts 字符串数组与带 tag 的 models")
    void modelsShape() throws Exception {
        mvc.perform(get("/ai/chat/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.defaultModel").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.efforts", org.hamcrest.Matchers.contains(
                        "Low", "Medium", "High", "Extra", "Max")))
                .andExpect(jsonPath("$.data.models[0].key").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.models[0].tag").value("Fast"))
                .andExpect(jsonPath("$.data.models[0].supportsThinking").value(false));
    }

    @Test
    @DisplayName("缺 X-User-Name 返回 200 + code=0，不建会话")
    void missingHeaderIsRejected() throws Exception {
        mvc.perform(post("/ai/chat/conversations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("缺少请求头: X-User-Name"));
    }

    @Test
    @DisplayName("创建成功返回 data.id 与 data.title，时间是 epoch 毫秒数字")
    void createOk() throws Exception {
        when(conversationMapper.insert(ArgumentMatchers.any())).thenAnswer(inv -> {
            inv.getArgument(0, org.leon.aimodule.chat.entity.AiConversation.class).setId(18L);
            return 1;
        });
        mvc.perform(post("/ai/chat/conversations").header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new org.leon.aimodule.chat.web.dto.CreateConversationRequest(
                                        "帮我改简历", "deepseek-chat", "High"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.id").value(18))
                .andExpect(jsonPath("$.data.title").value("帮我改简历"))
                .andExpect(jsonPath("$.data.effort").value("High"))
                .andExpect(jsonPath("$.data.updateTime", Matchers.notNullValue()))
                .andExpect(jsonPath("$.data.updateTime", org.hamcrest.Matchers.instanceOf(Number.class)));
    }

    @Test
    @DisplayName("未知档位：code=0 且 msg 含档位名")
    void unknownEffort() throws Exception {
        mvc.perform(post("/ai/chat/conversations").header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effort\":\"Ultra\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("Ultra")));
    }

    @Test
    @DisplayName("重命名标题为空：走参数校验分支")
    void renameBlankTitle() throws Exception {
        mvc.perform(patch("/ai/chat/conversations/1").header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("title")));
    }

    @Test
    @DisplayName("删除不存在的会话：统一“会话不存在”")
    void deleteOthers() throws Exception {
        when(conversationMapper.selectById(1L)).thenReturn(null);
        mvc.perform(delete("/ai/chat/conversations/1").header("X-User-Name", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("会话不存在"));
    }
}
```

> `service` 是真实对象、只有两个 mapper 是 mock——这样测的是"控制器 + advice + 校验 + 真实 service 分支"，mock 边界压在数据库那一层。`deleteOthers` 用 `selectById` 返回 `null` 触发归属拒绝分支，不要去 stub 真实对象。
> 顶部 import 需要 `org.hamcrest.Matchers`，以及 `java.time.LocalDateTime`（`conversation(...)` 用不到就去掉未用 import）。

- [ ] **Step 8: 跑测试确认通过**

```bash
"$MVN" -o -pl AIModule -am test \
  -Dtest='ConversationServiceTest,ChatConversationControllerTest' \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `Tests run: 15, Failures: 0`（数量以实际为准，关键是 0 失败）。

- [ ] **Step 9: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/chat/web/dto/ \
        AIModule/src/main/java/org/leon/aimodule/chat/service/ConversationService.java \
        AIModule/src/main/java/org/leon/aimodule/chat/web/ChatConversationController.java \
        AIModule/src/main/java/org/leon/aimodule/chat/web/ChatExceptionHandler.java \
        AIModule/src/test/java/org/leon/aimodule/chat/service/ConversationServiceTest.java \
        AIModule/src/test/java/org/leon/aimodule/chat/web/ChatConversationControllerTest.java
git commit -m "新增对话控制面：模型目录、会话增删改查与游标列表，异常按包隔离在 chat advice"
```

---

### Task 6: 历史消息接口 #6

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/web/dto/ChatUsageVo.java`、`MessageVo.java`、`MessagePageVo.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/service/MessageQueryService.java`
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/web/ChatConversationController.java`（加 `#6` 方法 + 构造参数）
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/service/MessageQueryServiceTest.java`

**Interfaces:**
- Consumes: Task 3 `AiMessageMapper`/`AiMessage`、Task 5 `ConversationService.requireOwned`/`clampLimit`
- Produces:
  - `record ChatUsageVo(Integer promptTokens, Integer completionTokens)`
  - `record MessageVo(Long id, Long conversationId, String role, String content, String status, String modelKey, String effort, String stopReason, ChatUsageVo usage, Long createTime)`
  - `record MessagePageVo(boolean hasMore, List<MessageVo> items)`
  - `MessageQueryService.page(String userName, Long conversationId, Long beforeId, int limit):MessagePageVo`

- [ ] **Step 1: 写失败测试**

```java
package org.leon.aimodule.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.mapper.AiConversationMapper;
import org.leon.aimodule.chat.mapper.AiMessageMapper;
import org.leon.aimodule.chat.web.dto.MessagePageVo;
import org.leon.aimodule.chat.web.dto.MessageVo;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageQueryServiceTest {

    private AiMessageMapper messageMapper;
    private ConversationService conversations;
    private MessageQueryService service;

    @BeforeEach
    void setUp() {
        messageMapper = mock(AiMessageMapper.class);
        AiConversationMapper conversationMapper = mock(AiConversationMapper.class);
        ChatProperties props = new ChatProperties();
        conversations = new ConversationService(props, new EffortResolver(props), conversationMapper, messageMapper);
        AiConversation mine = new AiConversation();
        mine.setId(7L);
        mine.setUserName("alice");
        mine.setStatus(1);
        mine.setUpdateTime(LocalDateTime.now());
        when(conversationMapper.selectById(7L)).thenReturn(mine);
        service = new MessageQueryService(messageMapper, conversations);
    }

    private static AiMessage msg(long id, String role, String status, Integer pt, Integer ct) {
        AiMessage m = new AiMessage();
        m.setId(id);
        m.setConversationId(7L);
        m.setRole(role);
        m.setContent("c" + id);
        m.setStatus(status);
        m.setModelKey(AiMessage.ROLE_ASSISTANT.equals(role) ? "deepseek-chat" : null);
        m.setEffort(AiMessage.ROLE_ASSISTANT.equals(role) ? "High" : null);
        m.setPromptTokens(pt);
        m.setCompletionTokens(ct);
        m.setCreateTime(LocalDateTime.of(2026, 10, 23, 9, 0, 0).plusSeconds(id));
        return m;
    }

    @Test
    @DisplayName("Mapper 按 id 降序取，返回给前端时翻成升序")
    void returnsAscending() {
        when(messageMapper.selectList(any())).thenReturn(
                List.of(msg(3, AiMessage.ROLE_USER, AiMessage.ST_DONE, null, null),
                        msg(2, AiMessage.ROLE_ASSISTANT, AiMessage.ST_DONE, 10, 20),
                        msg(1, AiMessage.ROLE_USER, AiMessage.ST_DONE, null, null)));
        MessagePageVo page = service.page("alice", 7L, null, 30);
        assertEquals(List.of(1L, 2L, 3L), page.items().stream().map(MessageVo::id).toList());
        assertTrue(!page.hasMore());
    }

    @Test
    @DisplayName("多取一条用于判 hasMore，丢弃的那条不进结果")
    void detectsMorePages() {
        when(messageMapper.selectList(any())).thenReturn(
                List.of(msg(5, "user", "done", null, null), msg(4, "assistant", "done", 1, 1),
                        msg(3, "user", "done", null, null)));
        MessagePageVo page = service.page("alice", 7L, null, 2);
        assertTrue(page.hasMore());
        assertEquals(2, page.items().size());
        assertEquals(List.of(4L, 5L), page.items().stream().map(MessageVo::id).toList());
    }

    @Test
    @DisplayName("usage 只在两个 token 都缺时返回 null，避免前端渲染空对象")
    void usageOnlyWhenPresent() {
        when(messageMapper.selectList(any())).thenReturn(
                List.of(msg(2, "assistant", "done", null, null), msg(1, "assistant", "done", 812, 1640)));
        MessagePageVo page = service.page("alice", 7L, null, 30);
        assertNull(page.items().get(0).usage());
        assertEquals(812, page.items().get(1).usage().promptTokens());
        assertEquals(1640, page.items().get(1).usage().completionTokens());
    }

    @Test
    @DisplayName("createTime 是 epoch 毫秒数字，不是 ISO 字符串")
    void timeIsEpochMillis() {
        when(messageMapper.selectList(any())).thenReturn(List.of(msg(1, "user", "done", null, null)));
        MessagePageVo page = service.page("alice", 7L, null, 30);
        assertTrue(page.items().get(0).createTime() > 1_700_000_000_000L);
    }

    @Test
    @DisplayName("查他人会话：与不存在同一文案，不泄露存在性")
    void rejectsOtherUsers() {
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> service.page("bob", 7L, null, 30));
        assertEquals("会话不存在", e.getMessage());
        assertThrows(ChatRejectException.class, () -> service.page("alice", 999L, null, 30));
    }

    @Test
    @DisplayName("limit 被夹到 1..100")
    void clampsLimit() {
        when(messageMapper.selectList(any())).thenReturn(List.of());
        assertEquals(0, service.page("alice", 7L, null, 9999).items().size());
        org.mockito.Mockito.verify(messageMapper, org.mockito.Mockito.atLeastOnce()).selectList(any());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
"$MVN" -o -pl AIModule -am test -Dtest=MessageQueryServiceTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译错误（`MessageQueryService` 不存在）。

- [ ] **Step 3: 写 DTO 与 Service**

```java
// web/dto/ChatUsageVo.java
package org.leon.aimodule.chat.web.dto;

public record ChatUsageVo(Integer promptTokens, Integer completionTokens) {
}
```

```java
// web/dto/MessageVo.java
package org.leon.aimodule.chat.web.dto;

public record MessageVo(Long id, Long conversationId, String role, String content, String status,
                        String modelKey, String effort, String stopReason, ChatUsageVo usage, Long createTime) {
}
```

```java
// web/dto/MessagePageVo.java
package org.leon.aimodule.chat.web.dto;

import java.util.List;

public record MessagePageVo(boolean hasMore, List<MessageVo> items) {
}
```

```java
package org.leon.aimodule.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.mapper.AiMessageMapper;
import org.leon.aimodule.chat.support.ChatTime;
import org.leon.aimodule.chat.web.dto.ChatUsageVo;
import org.leon.aimodule.chat.web.dto.MessagePageVo;
import org.leon.aimodule.chat.web.dto.MessageVo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class MessageQueryService {

    private static final int MAX_PAGE = 100;

    private final AiMessageMapper messageMapper;
    private final ConversationService conversations;

    public MessageQueryService(AiMessageMapper messageMapper, ConversationService conversations) {
        this.messageMapper = messageMapper;
        this.conversations = conversations;
    }

    /**
     * superseded 不进对话流：重新生成后旧回答只在库里留档，不在 UI 出现（spec 4.2 #6）。
     * streaming 行会返回，前端据此显示"正在生成"。
     */
    public MessagePageVo page(String userName, Long conversationId, Long beforeId, int limit) {
        conversations.requireOwned(userName, conversationId);
        int size = conversations.clampLimit(limit, MAX_PAGE);
        LambdaQueryWrapper<AiMessage> q = new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId)
                .ne(AiMessage::getStatus, AiMessage.ST_SUPERSEDED);
        if (beforeId != null) {
            q.lt(AiMessage::getId, beforeId);
        }
        q.orderByDesc(AiMessage::getId).last("LIMIT " + (size + 1));
        List<AiMessage> rows = messageMapper.selectList(q);
        boolean hasMore = rows.size() > size;
        List<MessageVo> items = new ArrayList<>(
                (hasMore ? rows.subList(0, size) : rows).stream().map(this::toVo).toList());
        Collections.reverse(items);
        return new MessagePageVo(hasMore, items);
    }

    private MessageVo toVo(AiMessage m) {
        ChatUsageVo usage = m.getPromptTokens() == null && m.getCompletionTokens() == null
                ? null : new ChatUsageVo(m.getPromptTokens(), m.getCompletionTokens());
        return new MessageVo(m.getId(), m.getConversationId(), m.getRole(), m.getContent(), m.getStatus(),
                m.getModelKey(), m.getEffort(), m.getStopReason(), usage, ChatTime.millis(m.getCreateTime()));
    }
}
```

- [ ] **Step 4: 给控制器加 #6**

`ChatConversationController` 构造改成两个 service，并加方法：

```java
    private final ConversationService service;
    private final MessageQueryService messageQuery;

    public ChatConversationController(ConversationService service, MessageQueryService messageQuery) {
        this.service = service;
        this.messageQuery = messageQuery;
    }

    @GetMapping("/conversations/{id}/messages")
    public Result<MessagePageVo> messages(@RequestHeader("X-User-Name") String userName,
                                         @PathVariable Long id,
                                         @RequestParam(required = false) Long beforeId,
                                         @RequestParam(defaultValue = "30") int limit) {
        return Result.success(messageQuery.page(userName, id, beforeId, limit));
    }
```
import `org.leon.aimodule.chat.service.MessageQueryService`、`org.leon.aimodule.chat.web.dto.MessagePageVo`。Task 5 的 `ChatConversationControllerTest.setUp()` 里 `new ChatConversationController(service)` 要同步改成 `new ChatConversationController(service, new MessageQueryService(messageMapper, service))`（`messageMapper` 用已有 mock）。

- [ ] **Step 5: 跑测试（新 + 受影响）**

```bash
"$MVN" -o -pl AIModule -am test \
  -Dtest='MessageQueryServiceTest,ChatConversationControllerTest,ConversationServiceTest' \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全绿。

- [ ] **Step 6: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/chat/web/dto/ \
        AIModule/src/main/java/org/leon/aimodule/chat/service/MessageQueryService.java \
        AIModule/src/main/java/org/leon/aimodule/chat/web/ChatConversationController.java \
        AIModule/src/test/java/org/leon/aimodule/chat/service/MessageQueryServiceTest.java \
        AIModule/src/test/java/org/leon/aimodule/chat/web/ChatConversationControllerTest.java
git commit -m "新增历史消息接口：游标向前翻页、superseded 不进流、时间为 epoch 毫秒"
```

---

### Task 7: 上下文组装

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/service/CtxMessage.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/service/ContextAssembler.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/service/ContextAssemblerTest.java`

**Interfaces:**
- Consumes: `org.leon.aimodule.rag.chunk.TokenCounter`（现成，`int count(String)`）、Task 1 `ChatProperties.getContextMaxTokens`、Task 3 `AiMessage`
- Produces:
  - `record CtxMessage(String role, String content)`
  - `ContextAssembler.assemble(List<AiMessage> priorAsc, String latestUserContent):List<CtxMessage>`
  - `ContextAssembler.totalTokens(List<CtxMessage>):int`（供日志与测试）
  - 抛 `ChatRejectException("消息过长，超出上下文预算")` 当单条最新消息就超预算

- [ ] **Step 1: 写失败测试**

```java
package org.leon.aimodule.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.rag.chunk.TokenCounter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextAssemblerTest {

    private ContextAssembler assembler(int budget) {
        ChatProperties props = new ChatProperties();
        props.setContextMaxTokens(budget);
        return new ContextAssembler(new TokenCounter(), props);
    }

    private static AiMessage msg(String role, String content) {
        AiMessage m = new AiMessage();
        m.setRole(role);
        m.setContent(content);
        m.setStatus(AiMessage.ST_DONE);
        return m;
    }

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    @Test
    @DisplayName("历史为空时只有最新一条用户消息")
    void onlyLatest() {
        List<CtxMessage> out = assembler(8000).assemble(List.of(), "你好");
        assertEquals(1, out.size());
        assertEquals(AiMessage.ROLE_USER, out.get(0).role());
        assertEquals("你好", out.get(0).content());
    }

    @Test
    @DisplayName("预算够时按原顺序全带上")
    void keepsAllWhenFits() {
        List<AiMessage> prior = List.of(msg("user", "q1"), msg("assistant", "a1"), msg("user", "q2"));
        List<CtxMessage> out = assembler(8000).assemble(prior, "q3");
        assertEquals(List.of("user", "assistant", "user", "user"),
                out.stream().map(CtxMessage::role).toList());
    }

    @Test
    @DisplayName("预算不够时从最老一侧丢，最新消息永不丢")
    void dropsOldestFirst() {
        List<AiMessage> prior = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            prior.add(msg("user", repeat("上下文片段。", 40)));
            prior.add(msg("assistant", repeat("回答内容。", 40)));
        }
        List<CtxMessage> out = assembler(200).assemble(prior, "最新问题");
        assertTrue(out.size() < prior.size(), "必须发生截断");
        assertEquals("最新问题", out.get(out.size() - 1).content());
        assertTrue(assembler(200).totalTokens(out) <= 200);
    }

    @Test
    @DisplayName("截断后首条不能是 assistant，否则模型以为对话是自己起的头")
    void neverStartsWithAssistant() {
        List<AiMessage> prior = List.of(
                msg("user", repeat("很长的背景。", 60)),
                msg("assistant", repeat("回答。", 60)),
                msg("user", "短问题"),
                msg("assistant", "短回答"));
        List<CtxMessage> out = assembler(120).assemble(prior, "再来一次");
        assertTrue(!out.isEmpty());
        assertEquals(AiMessage.ROLE_USER, out.get(0).role(),
                "实际序列: " + out.stream().map(CtxMessage::role).toList());
    }

    @Test
    @DisplayName("连续多条 assistant 开头也要一并丢掉")
    void dropsConsecutiveAssistantHead() {
        List<AiMessage> prior = List.of(
                msg("user", repeat("超长。", 80)),
                msg("assistant", "a1"),
                msg("assistant", "a2"),
                msg("user", "u3"));
        List<CtxMessage> out = assembler(60).assemble(prior, "q");
        assertTrue(out.stream().noneMatch(m -> m.role().equals("assistant")) || out.get(0).role().equals("user"));
    }

    @Test
    @DisplayName("单条最新消息就超预算：拒绝而不是静默截断")
    void rejectsOversizedLatest() {
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> assembler(10).assemble(List.of(), repeat("这个词很长很长很长。", 50)));
        assertEquals("消息过长，超出上下文预算", e.getMessage());
    }

    @Test
    @DisplayName("null 历史与 null 内容不崩")
    void toleratesNulls() {
        List<AiMessage> prior = new ArrayList<>();
        AiMessage blank = msg("user", "   ");
        prior.add(blank);
        List<CtxMessage> out = assembler(8000).assemble(prior, "hi");
        assertEquals(2, out.size());
        assertTrue(assembler(8000).totalTokens(out) >= 1);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
"$MVN" -o -pl AIModule -am test -Dtest=ContextAssemblerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译错误（`ContextAssembler` 不存在）。

- [ ] **Step 3: 实现**

```java
// service/CtxMessage.java
package org.leon.aimodule.chat.service;

public record CtxMessage(String role, String content) {
}
```

```java
package org.leon.aimodule.chat.service;

import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.rag.chunk.TokenCounter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端组装多轮上下文：从最新往回按 token 预算累加，再翻正。
 * 计数用 jtokkit cl100k_base，与 DeepSeek 真实分词不一致（中文侧通常偏多），
 * 所以 context-max-tokens 是保守上限而不是精确值。
 */
@Service
public class ContextAssembler {

    private final TokenCounter tokenCounter;
    private final ChatProperties props;

    public ContextAssembler(TokenCounter tokenCounter, ChatProperties props) {
        this.tokenCounter = tokenCounter;
        this.props = props;
    }

    public List<CtxMessage> assemble(List<AiMessage> priorAsc, String latestUserContent) {
        int budget = props.getContextMaxTokens();
        String latest = latestUserContent == null ? "" : latestUserContent;
        int used = tokenCounter.count(latest);
        if (used > budget) {
            throw new ChatRejectException("消息过长，超出上下文预算");
        }
        List<CtxMessage> picked = new ArrayList<>();
        List<AiMessage> prior = priorAsc == null ? List.of() : priorAsc;
        for (int i = prior.size() - 1; i >= 0; i--) {
            String content = prior.get(i).getContent();
            int cost = tokenCounter.count(content);
            if (used + cost > budget) {
                break;
            }
            used += cost;
            picked.add(0, new CtxMessage(prior.get(i).getRole(), content));
        }
        // 截断可能把某一对问答撕开，留下半截 assistant 开头。
        while (!picked.isEmpty() && AiMessage.ROLE_ASSISTANT.equals(picked.get(0).role())) {
            picked.remove(0);
        }
        picked.add(new CtxMessage(AiMessage.ROLE_USER, latest));
        return picked;
    }

    public int totalTokens(List<CtxMessage> messages) {
        int sum = 0;
        for (CtxMessage m : messages) {
            sum += tokenCounter.count(m.content());
        }
        return sum;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: `Tests run: 7, Failures: 0`。若 `neverStartsWithAssistant` 失败，说明截断循环写成了 `if` 而不是 `while`。

- [ ] **Step 5: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/chat/service/CtxMessage.java \
        AIModule/src/main/java/org/leon/aimodule/chat/service/ContextAssembler.java \
        AIModule/src/test/java/org/leon/aimodule/chat/service/ContextAssemblerTest.java
git commit -m "新增上下文组装：token 预算从新到旧截断且不撕开问答对"
```

---

### Task 8: 事件模型与上游网关抽象

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatEvent.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatModelGateway.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/SpringAiChatModelGateway.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/stream/SpringAiChatModelGatewayTest.java`

**Interfaces:**
- Consumes: Task 1 `ChatProperties`（systemPrompt）、Task 7 `CtxMessage`、`ModelParams`
- Produces:
  - `sealed interface ChatEvent` permits `Meta, Delta, TokenUsage, Done, ErrorEvt`
    - `record Meta(Long conversationId, Long messageId, String modelKey, String effort, boolean supportsThinking)`
    - `record Delta(String text)`
    - `record TokenUsage(Integer promptTokens, Integer completionTokens)`
    - `record Done(String finishReason)`
    - `record ErrorEvt(String errorCode, String msg, boolean retryable)`
  - `interface ChatModelGateway { Flux<ChatChunk> stream(List<CtxMessage> messages, ModelParams params); }`
  - `record ChatChunk(String text, String finishReason, Integer promptTokens, Integer completionTokens)`（四字段均可为 null）
  - `SpringAiChatModelGateway.toChunk(ChatResponse):ChatChunk|null`（package-private static，供单测直接打）

- [ ] **Step 1: 写失败测试**

```java
package org.leon.aimodule.chat.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpringAiChatModelGatewayTest {

    @Test
    @DisplayName("普通正文 chunk 只带 text")
    void textChunk() {
        ChatResponse resp = new ChatResponse(List.of(new Generation(new AssistantMessage("## 经"))));
        ChatModelGateway.ChatChunk chunk = SpringAiChatModelGateway.toChunk(resp);
        assertEquals("## 经", chunk.text());
        assertNull(chunk.finishReason());
        assertNull(chunk.promptTokens());
    }

    @Test
    @DisplayName("空正文的 role-only chunk 归一为 null text，不产生空 delta")
    void emptyTextBecomesNull() {
        ChatResponse resp = new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
        assertNull(SpringAiChatModelGateway.toChunk(resp), "什么都没有的 chunk 应当整帧丢弃");
    }

    @Test
    @DisplayName("最后一个 chunk 只有 choices 空数组 + usage，必须留住 usage")
    void usageOnlyChunk() {
        ChatResponse resp = new ChatResponse(List.of(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(812, 1640)).build());
        ChatModelGateway.ChatChunk chunk = SpringAiChatModelGateway.toChunk(resp);
        assertNull(chunk.text());
        assertEquals(812, chunk.promptTokens());
        assertEquals(1640, chunk.completionTokens());
    }

    @Test
    @DisplayName("finishReason 与 text 同帧到达时两者都保留")
    void keepsFinishReason() {
        Generation g = new Generation(new AssistantMessage("结束"),
                ChatResponseMetadata.builder().build() == null ? null
                        : org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                        .finishReason("stop").build());
        ChatModelGateway.ChatChunk chunk = SpringAiChatModelGateway.toChunk(new ChatResponse(List.of(g)));
        assertEquals("结束", chunk.text());
        assertEquals("stop", chunk.finishReason());
    }

    @Test
    @DisplayName("null 响应与 null result 都不崩")
    void nullSafe() {
        assertNull(SpringAiChatModelGateway.toChunk(null));
        assertNull(SpringAiChatModelGateway.toChunk(new ChatResponse(List.of())));
    }
}
```

> `keepsFinishReason` 里那行 `ChatResponseMetadata.builder().build() == null ? null : ...` 是无意义的噪音，落地时直接写：
> ```java
>         Generation g = new Generation(new AssistantMessage("结束"),
>                 org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
>                         .finishReason("stop").build());
> ```
> 先跑 `"$JAVA_HOME/bin/javap" -cp "$M2/org/springframework/ai/spring-ai-model/1.0.0-M7/spring-ai-model-1.0.0-M7.jar" 'org.springframework.ai.chat.metadata.ChatGenerationMetadata$Builder'` 确认 `finishReason(String)` 存在，再照此写。

- [ ] **Step 2: 跑测试确认失败**

```bash
"$MVN" -o -pl AIModule -am test -Dtest=SpringAiChatModelGatewayTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译错误（`ChatEvent`/`ChatModelGateway` 不存在）。

- [ ] **Step 3: 写事件与接口**

```java
package org.leon.aimodule.chat.stream;

/**
 * 编排层唯一的输出语言。心跳 ping 不在这里：它由 SseChannel 的定时器直接发，
 * 不经编排层，否则每个业务事件都要被迫携带它。
 */
public sealed interface ChatEvent {

    record Meta(Long conversationId, Long messageId, String modelKey, String effort,
                boolean supportsThinking) implements ChatEvent {
    }

    record Delta(String text) implements ChatEvent {
    }

    record TokenUsage(Integer promptTokens, Integer completionTokens) implements ChatEvent {
    }

    /** finishReason: done / stopped / length / upstream */
    record Done(String finishReason) implements ChatEvent {
    }

    record ErrorEvt(String errorCode, String msg, boolean retryable) implements ChatEvent {
    }
}
```

```java
package org.leon.aimodule.chat.stream;

import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ChatModelGateway {

    Flux<ChatChunk> stream(List<CtxMessage> messages, ModelParams params);

    /** 上游一帧的归一化视图，四个字段都可能为 null。 */
    record ChatChunk(String text, String finishReason, Integer promptTokens, Integer completionTokens) {
    }
}
```

- [ ] **Step 4: 写 Spring AI 实现**

```java
package org.leon.aimodule.chat.stream;

import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
public class SpringAiChatModelGateway implements ChatModelGateway {

    private final ChatClient chatClient;
    private final ChatProperties props;

    public SpringAiChatModelGateway(ChatClient.Builder builder, ChatProperties props) {
        this.chatClient = builder.build();
        this.props = props;
    }

    @Override
    public Flux<ChatChunk> stream(List<CtxMessage> messages, ModelParams params) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(params.modelKey());
        if (params.temperature() != null) {
            options.temperature(params.temperature());
        }
        if (params.maxTokens() != null) {
            options.maxTokens(params.maxTokens());
        }
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .messages(messages.stream().map(SpringAiChatModelGateway::toMessage).toList())
                .options(options.build());
        if (!props.getSystemPrompt().isBlank()) {
            spec = spec.system(props.getSystemPrompt());
        }
        return spec.stream().chatResponse().mapNotNull(SpringAiChatModelGateway::toChunk);
    }

    static Message toMessage(CtxMessage m) {
        return AiMessage.ROLE_ASSISTANT.equals(m.role())
                ? new AssistantMessage(m.content()) : new UserMessage(m.content());
    }

    /**
     * 上游一帧 → 归一化 chunk。全空时返回 null 让 mapNotNull 丢掉，
     * 但 usage-only 的最后一帧必须留住——DeepSeek 的 usage 就挂在那一帧上。
     */
    static ChatChunk toChunk(ChatResponse resp) {
        if (resp == null) {
            return null;
        }
        String text = null;
        String finish = null;
        Generation gen = resp.getResult();
        if (gen != null && gen.getOutput() != null) {
            String raw = gen.getOutput().getText();
            text = (raw == null || raw.isEmpty()) ? null : raw;
            if (gen.getMetadata() != null) {
                String fr = gen.getMetadata().getFinishReason();
                finish = (fr == null || fr.isBlank()) ? null : fr;
            }
        }
        ChatResponseMetadata md = resp.getMetadata();
        Usage usage = md == null ? null : md.getUsage();
        Integer pt = usage == null ? null : usage.getPromptTokens();
        Integer ct = usage == null ? null : usage.getCompletionTokens();
        if (text == null && finish == null && pt == null && ct == null) {
            return null;
        }
        return new ChatChunk(text, finish, pt, ct);
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: 同 Step 2。Expected: `Tests run: 5, Failures: 0`。

- [ ] **Step 6: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/chat/stream/ \
        AIModule/src/test/java/org/leon/aimodule/chat/stream/
git commit -m "新增事件模型与上游网关抽象：usage-only 尾帧不被丢弃"
```

---

### Task 9: SSE 通道、注册表与线程池

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/SseChannel.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatStreamRegistry.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/config/ChatStreamExecutorConfig.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/stream/SseChannelTest.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/stream/ChatStreamRegistryTest.java`

**Interfaces:**
- Consumes: Task 8 `ChatEvent`
- Produces:
  - `SseChannel(SseChannel.Sink sink, ObjectMapper mapper)`；`void emit(ChatEvent)`、`void ping()`、`void close()`、`boolean isClosed()`
  - `interface SseChannel.Sink { void send(String event, String json) throws Exception; void complete(); }`
  - `ChatStreamRegistry`：`void register(Long messageId, ActiveStream s)`、`ActiveStream find(Long messageId)`、`void remove(Long messageId)`、`int size()`、`java.util.Set<Long> activeIds()`
  - `record ActiveStream(Long messageId, Long conversationId, String userName, SseChannel channel, java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> upstream, StringBuilder buffer, java.util.concurrent.atomic.AtomicReference<String> finishReason, java.util.concurrent.atomic.AtomicReference<int[]> usage)`，另有 `String accumulated()`、`void append(String)`
  - bean `ThreadPoolExecutor chatStreamExecutor`、`ScheduledExecutorService chatHeartbeatExecutor`

- [ ] **Step 1: 写失败测试 `SseChannelTest`**

```java
package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseChannelTest {

    private final List<String> frames = new CopyOnWriteArrayList<>();
    private final SseChannel.Sink sink = new SseChannel.Sink() {
        @Override
        public void send(String event, String json) {
            frames.add("event:" + event + "\ndata:" + json + "\n\n");
        }

        @Override
        public void complete() {
            frames.add("complete\n");
        }
    };
    private final SseChannel channel = new SseChannel(sink, new ObjectMapper());

    @Test
    @DisplayName("五类事件各自的名字与 JSON 形状")
    void emitsAllEventKinds() {
        channel.emit(new ChatEvent.Meta(7L, 42L, "deepseek-chat", "High", false));
        channel.emit(new ChatEvent.Delta("## 经"));
        channel.emit(new ChatEvent.TokenUsage(812, 1640));
        channel.emit(new ChatEvent.ErrorEvt("UPSTREAM_MODEL_ERROR", "模型服务暂时不可用", true));
        channel.emit(new ChatEvent.Done("done"));
        assertEquals(5, frames.size());
        assertTrue(frames.get(0).startsWith("event:meta\ndata:{"));
        assertTrue(frames.get(0).contains("\"conversationId\":7"));
        assertTrue(frames.get(0).contains("\"supportsThinking\":false"));
        assertTrue(frames.get(1).contains("event:delta"));
        assertTrue(frames.get(2).contains("\"completionTokens\":1640"));
        assertTrue(frames.get(3).contains("\"retryable\":true"));
        assertTrue(frames.get(4).contains("event:done"));
    }

    @Test
    @DisplayName("正文里的换行必须被转义，绝不能提前造出空行断帧")
    void escapesNewlines() {
        channel.emit(new ChatEvent.Delta("第一行\n\n第二行"));
        String frame = frames.get(0);
        assertEquals(1, frame.split("\n\n", -1).length - 2, "整帧只允许结尾一个空行: " + frame);
        assertTrue(frame.contains("\\n\\n"), "换行必须转义成 \\n 字面量: " + frame);
    }

    @Test
    @DisplayName("心跳帧独立于业务事件")
    void pingIsOwnFrame() {
        channel.ping();
        assertEquals("event:ping\ndata:{\"t\":100}\n\n", frames.get(0).replaceAll("\\d{2,}", "100"));
    }

    @Test
    @DisplayName("close 之后再发不产生新帧，且只 complete 一次")
    void closeIsIdempotent() {
        channel.close();
        channel.close();
        channel.emit(new ChatEvent.Delta("晚了"));
        assertEquals(1, frames.stream().filter(f -> f.equals("complete\n")).count());
        assertEquals(1, frames.size());
        assertTrue(channel.isClosed());
    }

    @Test
    @DisplayName("sink 抛异常时标记关闭并把异常透出，由调用方落库")
    void surfacesSinkFailure() {
        SseChannel broken = new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
                throw new IllegalStateException("断开的管道");
            }

            @Override
            public void complete() {
            }
        }, new ObjectMapper());
        assertThrows(RuntimeException.class, () -> broken.emit(new ChatEvent.Delta("x")));
        assertTrue(broken.isClosed());
    }

    @Test
    @DisplayName("两个线程并发发帧不会交错出半帧")
    void concurrentEmitsStayAtomic() throws Exception {
        List<String> raw = new CopyOnWriteArrayList<>();
        SseChannel concurrent = new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
                raw.add("event:" + event + "\ndata:" + json + "\n\n");
            }

            @Override
            public void complete() {
            }
        }, new ObjectMapper());
        int threads = 4;
        int each = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool.execute(() -> {
                start.countDown();
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < each; i++) {
                    concurrent.emit(new ChatEvent.Delta("t" + id + "-i" + i));
                    if (i % 20 == 0) {
                        concurrent.ping();
                    }
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(threads * (each + each / 20 + 1), raw.size());
        for (String frame : raw) {
            assertTrue(frame.startsWith("event:") && frame.endsWith("\n\n")
                    && frame.split("\n\n", -1).length == 3, "坏帧: " + frame);
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
"$MVN" -o -pl AIModule -am test -Dtest=SseChannelTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译错误（`SseChannel` 不存在）。

- [ ] **Step 3: 实现 `SseChannel`**

```java
package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 发帧的唯一入口。SseEmitter.send 不是幂等追加：心跳由定时器线程触发、
 * delta 由 Reactor IO 线程触发，两者并发写同一个 response 会交错出坏帧，
 * 所以 send/complete 全部串在一把锁上。
 */
public class SseChannel {

    public interface Sink {
        void send(String event, String json) throws Exception;

        void complete();
    }

    private final Sink sink;
    private final ObjectMapper mapper;
    private final Object lock = new Object();
    private volatile boolean closed;

    public SseChannel(Sink sink, ObjectMapper mapper) {
        this.sink = sink;
        this.mapper = mapper;
    }

    public boolean isClosed() {
        return closed;
    }

    public void emit(ChatEvent event) {
        String name;
        Object payload;
        if (event instanceof ChatEvent.Meta e) {
            name = "meta";
            payload = e;
        } else if (event instanceof ChatEvent.Delta e) {
            name = "delta";
            payload = e;
        } else if (event instanceof ChatEvent.TokenUsage e) {
            name = "usage";
            payload = e;
        } else if (event instanceof ChatEvent.Done e) {
            name = "done";
            payload = e;
        } else if (event instanceof ChatEvent.ErrorEvt e) {
            name = "error";
            payload = e;
        } else {
            return;
        }
        send(name, payload);
    }

    public void ping() {
        send("ping", new Ping(System.currentTimeMillis()));
    }

    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            sink.complete();
        }
    }

    private void send(String name, Object payload) {
        String json;
        try {
            // 必须走 Jackson：正文里的 \n 与 \n\n 手拼出去会直接切断帧。
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败: " + name, e);
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            try {
                sink.send(name, json);
            } catch (Exception e) {
                closed = true;
                throw new ClientGoneException(e);
            }
        }
    }

    private record Ping(long t) {
    }

    /** 客户端断连或写失败，编排层据此落 truncated。 */
    public static class ClientGoneException extends RuntimeException {
        public ClientGoneException(Throwable cause) {
            super("SSE 写出失败", cause);
        }
    }
}
```

> `surfacesSinkFailure` 断言 `isClosed()` 为真——`send` 失败时置 `closed=true` 再抛，编排层只需 catch `ClientGoneException`。

- [ ] **Step 4: 写失败测试 `ChatStreamRegistryTest`**

```java
package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamRegistryTest {

    private static ActiveStream stream(long id) {
        SseChannel channel = new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
            }

            @Override
            public void complete() {
            }
        }, new ObjectMapper());
        return new ActiveStream(id, 7L, "alice", channel, new AtomicReference<>(), new StringBuilder("部分内容"),
                new AtomicReference<>("done"), new AtomicReference<>(new int[]{1, 2}));
    }

    @Test
    @DisplayName("注册、查找、摘除与计数")
    void registerFindRemove() {
        ChatStreamRegistry registry = new ChatStreamRegistry();
        registry.register(42L, stream(42L));
        assertEquals(1, registry.size());
        assertTrue(registry.activeIds().contains(42L));
        assertEquals(42L, registry.find(42L).messageId());
        registry.remove(42L);
        assertNull(registry.find(42L));
        assertEquals(0, registry.size());
        registry.remove(42L);
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("同 id 覆盖注册时旧的先被摘掉，不留两条活跃流")
    void reRegisterReplaces() {
        ChatStreamRegistry registry = new ChatStreamRegistry();
        registry.register(1L, stream(1L));
        registry.register(1L, stream(1L));
        assertEquals(1, registry.size());
    }
}
```

- [ ] **Step 5: 实现 `ActiveStream` 与 `ChatStreamRegistry`**

```java
package org.leon.aimodule.chat.stream;

import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 一条进行中的流。buffer 只在内存里累积，收尾时一次性落库——
 * 每 token 写一次会把一次回答放大成上千次行锁更新。
 *
 * upstream 用 AtomicReference 而不是直接持有 Disposable：注册表必须在订阅上游
 * 之前就登记（否则秒回的流会在登记前就已完成，留下一条永远停不下来的僵尸条目），
 * 而 Disposable 只有 subscribe() 返回后才拿得到。
 */
public record ActiveStream(Long messageId, Long conversationId, String userName, SseChannel channel,
                           AtomicReference<Disposable> upstream, StringBuilder buffer,
                           AtomicReference<String> finishReason, AtomicReference<int[]> usage) {

    public String accumulated() {
        synchronized (buffer) {
            return buffer.toString();
        }
    }

    public void append(String text) {
        synchronized (buffer) {
            buffer.append(text);
        }
    }

    public void disposeUpstream() {
        Disposable d = upstream.get();
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }
}
```

```java
package org.leon.aimodule.chat.stream;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内活跃流表。多实例部署时 stop 可能打到别的实例，
 * 那时走 ChatStreamService 的孤儿分支（状态能收敛，内容会缺）。
 */
@Component
public class ChatStreamRegistry {

    private final Map<Long, ActiveStream> active = new ConcurrentHashMap<>();

    public void register(Long messageId, ActiveStream stream) {
        active.put(messageId, stream);
    }

    public ActiveStream find(Long messageId) {
        return messageId == null ? null : active.get(messageId);
    }

    public void remove(Long messageId) {
        if (messageId != null) {
            active.remove(messageId);
        }
    }

    public int size() {
        return active.size();
    }

    public Set<Long> activeIds() {
        return Set.copyOf(active.keySet());
    }
}
```

> `ActiveStream` 是 record，`upstream` 是 `AtomicReference<Disposable>`，测试里传 `new AtomicReference<>()` 即可，不需要造假的 `Disposable` 实现。

- [ ] **Step 6: 实现线程池配置**

```java
package org.leon.aimodule.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ChatStreamExecutorConfig {

    /**
     * SynchronousQueue + max 上限：长连接的容量上限必须等于并发 emitter 数。
     * 排队等 30 秒才出首字比直接拒绝更糟，所以队列长度固定为 0，满了就抛
     * RejectedExecutionException，由 controller 翻成"服务繁忙"。
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor chatStreamExecutor(ChatProperties props) {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(props.getStreamCoreThreads(), props.getStreamMaxThreads(),
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>() /* 见下方说明 */,
                r -> {
                    Thread t = new Thread(r, "chat-stream-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService chatHeartbeatExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "chat-ping");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
```

**上面这段有个必须改掉的错误**：注释要的是"队列长度为 0、不排队"，但 `new LinkedBlockingQueue<>()` 是**无界队列**——有了它 `max` 线程数永远用不上，拒绝也不会发生。正确写法是 `new SynchronousQueue<>()`：

```java
        return new ThreadPoolExecutor(props.getStreamCoreThreads(), props.getStreamMaxThreads(),
                60L, TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(),
                threadFactory, new ThreadPoolExecutor.AbortPolicy());
```
并把 `import java.util.concurrent.LinkedBlockingQueue;` 换成 `import java.util.concurrent.SynchronousQueue;`。

- [ ] **Step 7: 加一条锁死"不排队"的测试**

追加到 `SseChannelTest` 所在目录的新文件 `ChatStreamExecutorConfigTest.java`：

```java
package org.leon.aimodule.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamExecutorConfigTest {

    @Test
    @DisplayName("线程池满时立刻拒绝，不排队")
    void rejectsInsteadOfQueuing() throws Exception {
        ChatProperties props = new ChatProperties();
        props.setStreamCoreThreads(1);
        props.setStreamMaxThreads(1);
        ThreadPoolExecutor executor = new ChatStreamExecutorConfig().chatStreamExecutor(props);
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try {
                    hold.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> executor.execute(() -> {
            }));
            hold.countDown();
        } finally {
            hold.countDown();
            executor.shutdownNow();
        }
    }
}
```

- [ ] **Step 8: 跑三个测试确认通过**

```bash
"$MVN" -o -pl AIModule -am test \
  -Dtest='SseChannelTest,ChatStreamRegistryTest,ChatStreamExecutorConfigTest' \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全绿。`concurrentEmitsStayAtomic` 若报坏帧，说明 `send` 里 `writeValueAsString` 被放到了锁外面 yet 又复用了非线程安全的 mapper —— Jackson 的 `ObjectMapper` 写路径线程安全，问题一定在锁的范围内。

- [ ] **Step 9: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/chat/stream/SseChannel.java \
        AIModule/src/main/java/org/leon/aimodule/chat/stream/ActiveStream.java \
        AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatStreamRegistry.java \
        AIModule/src/main/java/org/leon/aimodule/chat/config/ChatStreamExecutorConfig.java \
        AIModule/src/test/java/org/leon/aimodule/chat/stream/ \
        AIModule/src/test/java/org/leon/aimodule/chat/config/ChatStreamExecutorConfigTest.java
git commit -m "新增 SSE 通道与活跃流表：发帧串行化、线程池满即拒不排队"
```

---

### Task 10: 编排层（三段短事务、幂等、并发拒绝）

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/repo/ChatTurnRepository.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/StreamCommand.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/PreparedTurn.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatStreamService.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/stream/ChatStreamServiceTest.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/stream/ChatStreamWriteCountTest.java`

**Interfaces:**
- Consumes: Task 3 两个 mapper、Task 5 `ConversationService.requireOwned`、Task 7 `ContextAssembler`、Task 8 `ChatModelGateway`/`ChatEvent`、Task 9 `SseChannel`/`ActiveStream`/`ChatStreamRegistry`/`chatStreamExecutor`
- Produces:
  - `record StreamCommand(Long conversationId, String content, String modelKey, String effort, String clientMsgId, boolean regenerate)`
  - `record PreparedTurn(Long conversationId, Long userMessageId, Long assistantMessageId, java.util.List<CtxMessage> messages, ModelParams params, String userName, String effortName)`
  - `ChatTurnRepository`：`Long insertUser(Long cid, String content, String clientMsgId)`、`Long insertAssistant(Long cid, Long replyTo, String modelKey, String effort, String assistantClientMsgId)`、`AiMessage findByClientMsgId(Long cid, String clientMsgId)`、`List<AiMessage> activeHistory(Long cid, Long beforeIdExclusive)`、`void supersede(Long replyTo)`、`void touch(Long cid, Long lastMessageId, String preview, int delta)`、`int renameIfDefault(Long cid, String title)`、`int reapStale(Long cid, int minutes)`、`int countLive(Long cid, int minutes)`、`void finalizeAssistant(Long id, String content, String status, String stopReason, Integer pt, Integer ct)`、`void markFailed(Long id, String errorMsg)`
  - `ChatStreamService.prepare(StreamCommand cmd, String userName):PreparedTurn`（同步，含 T1；拒绝时抛 `ChatRejectException`）
  - `ChatStreamService.subscribeAsync(PreparedTurn prepared, SseChannel channel):ActiveStream`
  - `ChatStreamService.stop(Long messageId, String userName):void`

- [ ] **Step 1: 写 `ChatTurnRepository`（先写实现，它是被 mock 的边界）**

```java
package org.leon.aimodule.chat.repo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.mapper.AiConversationMapper;
import org.leon.aimodule.chat.mapper.AiMessageMapper;
import org.leon.aimodule.chat.support.ChatTime;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 对话写路径的唯一入口。存在的理由只有一个：让"一次回答写库几次"可以在
 * 一个 mock 对象上被数出来（见 ChatStreamWriteCountTest）。
 */
@Repository
public class ChatTurnRepository {

    public static final int PREVIEW_CP = 80;

    private final AiMessageMapper messageMapper;
    private final AiConversationMapper conversationMapper;

    public ChatTurnRepository(AiMessageMapper messageMapper, AiConversationMapper conversationMapper) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
    }

    @Transactional
    public Long insertUser(Long cid, String content, String clientMsgId) {
        AiMessage m = new AiMessage();
        m.setConversationId(cid);
        m.setRole(AiMessage.ROLE_USER);
        m.setContent(content);
        m.setStatus(AiMessage.ST_DONE);
        m.setClientMsgId(clientMsgId);
        messageMapper.insert(m);
        return m.getId();
    }

    @Transactional
    public Long insertAssistant(Long cid, Long replyTo, String modelKey, String effort, String assistantClientMsgId) {
        AiMessage m = new AiMessage();
        m.setConversationId(cid);
        m.setRole(AiMessage.ROLE_ASSISTANT);
        m.setContent("");
        m.setStatus(AiMessage.ST_STREAMING);
        m.setReplyToMessageId(replyTo);
        m.setModelKey(modelKey);
        m.setEffort(effort);
        m.setClientMsgId(assistantClientMsgId);
        messageMapper.insert(m);
        return m.getId();
    }

    public AiMessage findByClientMsgId(Long cid, String clientMsgId) {
        if (clientMsgId == null || clientMsgId.isBlank()) {
            return null;
        }
        return messageMapper.selectOne(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, cid)
                .eq(AiMessage::getClientMsgId, clientMsgId)
                .last("LIMIT 1"));
    }

    /** 生效中的历史，升序，不含本轮这条新用户消息。 */
    public List<AiMessage> activeHistory(Long cid, Long beforeIdExclusive) {
        LambdaQueryWrapper<AiMessage> q = new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, cid)
                .in(AiMessage::getStatus, AiMessage.ST_DONE, AiMessage.ST_TRUNCATED);
        if (beforeIdExclusive != null) {
            q.lt(AiMessage::getId, beforeIdExclusive);
        }
        return messageMapper.selectList(q.orderByAsc(AiMessage::getId));
    }

    public void supersede(Long replyTo) {
        messageMapper.supersedePrevious(replyTo);
    }

    public void touch(Long cid, Long lastMessageId, String preview, int delta) {
        conversationMapper.touchOnNewMessage(cid, lastMessageId, ChatTime.truncate(preview, PREVIEW_CP), delta);
    }

    public int renameIfDefault(Long cid, String title) {
        return conversationMapper.renameIfDefault(cid, ChatTime.truncate(title, 24));
    }

    public int reapStale(Long cid, int minutes) {
        return messageMapper.reapStaleStreaming(cid, minutes, "生成中断（进程重启或超时）");
    }

    public int countLive(Long cid, int minutes) {
        return messageMapper.countLiveStreaming(cid, minutes);
    }

    @Transactional
    public void finalizeAssistant(Long id, String content, String status, String stopReason,
                                  Integer promptTokens, Integer completionTokens) {
        messageMapper.finalizeAssistant(id, content, status, stopReason, promptTokens, completionTokens);
    }

    public void markFailed(Long id, String errorMsg) {
        messageMapper.markFailed(id, ChatTime.truncate(errorMsg, 250));
    }
}
```

> `markFailed` 用 `ChatTime.truncate` 会把换行替换成空格并加省略号，`error_msg` 列只有 255 字符——够用，但**别把整段堆栈塞进去**。

- [ ] **Step 2: 写失败测试 `ChatStreamServiceTest`**

```java
package org.leon.aimodule.chat.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.repo.ChatTurnRepository;
import org.leon.aimodule.chat.service.ContextAssembler;
import org.leon.aimodule.chat.service.ConversationService;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.EffortResolver;
import org.leon.aimodule.chat.service.ModelParams;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatStreamServiceTest {

    private ChatTurnRepository repo;
    private ConversationService conversations;
    private ChatModelGateway gateway;
    private ChatStreamRegistry registry;
    private ChatProperties props;
    private ChatStreamService service;
    private final List<ChatEvent> events = new ArrayList<>();

    private SseChannel channel() {
        return new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
                // 事件序列由 ChatStreamService 侧的 recorder 断言，这里只吃掉帧
            }

            @Override
            public void complete() {
            }
        }, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @BeforeEach
    void setUp() {
        props = new ChatProperties();
        ChatProperties.Model m = new ChatProperties.Model();
        m.setKey("deepseek-chat");
        m.setName("deepseek-chat");
        props.setModels(new ArrayList<>(List.of(m)));
        List<ChatProperties.Effort> efforts = new ArrayList<>();
        for (String k : List.of("Low", "Medium", "High", "Extra", "Max")) {
            ChatProperties.Effort e = new ChatProperties.Effort();
            e.setKey(k);
            e.setTemperature(1.0);
            e.setMaxTokens(4096);
            efforts.add(e);
        }
        props.setEfforts(efforts);

        repo = mock(ChatTurnRepository.class);
        gateway = mock(ChatModelGateway.class);
        registry = new ChatStreamRegistry();
        AiMessage owned = new AiMessage();
        conversations = mock(ConversationService.class);
        org.leon.aimodule.chat.entity.AiConversation c = new org.leon.aimodule.chat.entity.AiConversation();
        c.setId(7L);
        c.setUserName("alice");
        c.setStatus(1);
        c.setTitle("新对话");
        when(conversations.requireOwned(anyString(), anyLong())).thenReturn(c);
        when(repo.insertUser(anyLong(), anyString(), any())).thenReturn(41L);
        when(repo.insertAssistant(anyLong(), anyLong(), anyString(), anyString(), any())).thenReturn(42L);
        when(repo.activeHistory(anyLong(), anyLong())).thenReturn(List.of());

        service = new ChatStreamService(repo, conversations,
                new ContextAssembler(new org.leon.aimodule.rag.chunk.TokenCounter(), props),
                new EffortResolver(props), gateway, registry, props,
                Runnable::run);   // 直接执行，测试确定性
        // 事件收集：把 channel 换成 recorder 版本
    }

    private ChatModelGateway.ChatChunk text(String t) {
        return new ChatModelGateway.ChatChunk(t, null, null, null);
    }

    private ChatModelGateway.ChatChunk finish() {
        return new ChatModelGateway.ChatChunk(null, "stop", 812, 1640);
    }

    private PreparedTurn prepareNormal() {
        return service.prepare(new StreamCommand(7L, "把这段改成 STAR", "deepseek-chat", "High", "cnd1", false),
                "alice");
    }

    @Test
    @DisplayName("T1 写序：插用户消息、插占位、touch 两次、自动改名")
    void prepareWritesBeforeStream() {
        PreparedTurn prepared = prepareNormal();
        assertEquals(41L, prepared.userMessageId());
        assertEquals(42L, prepared.assistantMessageId());
        verify(repo).insertUser(7L, "把这段改成 STAR", "cnd1");
        verify(repo).insertAssistant(eq(7L), eq(41L), eq("deepseek-chat"), eq("High"), eq("cnd1:a"));
        verify(repo).touch(eq(7L), eq(41L), anyString(), eq(1));
        verify(repo).touch(eq(7L), eq(42L), anyString(), eq(1));
        verify(repo).renameIfDefault(7L, "把这段改成 STAR");
        assertEquals(List.of("user"), prepared.messages().stream().map(CtxMessage::role).toList());
    }

    @Test
    @DisplayName("建流前先回收超时流，再判并发")
    void reapsThenRejectsConcurrent() {
        when(repo.countLive(7L, 5)).thenReturn(1);
        ChatRejectException e = assertThrows(ChatRejectException.class, this::prepareNormal);
        assertEquals("上一条回答仍在生成", e.getMessage());
        verify(repo).reapStale(7L, 5);
        verify(repo, never()).insertUser(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("会话不存在时不写任何行")
    void ownershipCheckedFirst() {
        doThrow(new ChatRejectException("会话不存在")).when(conversations).requireOwned("bob", 7L);
        assertThrows(ChatRejectException.class,
                () -> service.prepare(new StreamCommand(7L, "hi", null, null, "x", false), "bob"));
        verify(repo, never()).insertUser(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("重试命中同一 clientMsgId：不新建用户消息，旧的标 superseded")
    void retryBecomesRegenerate() {
        AiMessage existing = new AiMessage();
        existing.setId(41L);
        existing.setConversationId(7L);
        existing.setRole(AiMessage.ROLE_USER);
        existing.setContent("原问题");
        existing.setStatus(AiMessage.ST_DONE);
        when(repo.findByClientMsgId(7L, "cnd1")).thenReturn(existing);

        PreparedTurn prepared = service.prepare(
                new StreamCommand(7L, "重复提交的旧文本", "deepseek-chat", "High", "cnd1", false), "alice");

        verify(repo, never()).insertUser(anyLong(), anyString(), any());
        assertEquals(41L, prepared.userMessageId());
        assertEquals("原问题", prepared.messages().get(prepared.messages().size() - 1).content());
        verify(repo).supersede(41L);
    }

    @Test
    @DisplayName("regenerate 不带 content，取会话最后一条用户消息")
    void regenerateUsesLastUserMessage() {
        AiMessage last = new AiMessage();
        last.setId(41L);
        last.setRole(AiMessage.ROLE_USER);
        last.setContent("重跑这个问题");
        last.setStatus(AiMessage.ST_DONE);
        when(repo.activeHistory(7L, null)).thenReturn(List.of(last));

        PreparedTurn prepared = service.prepare(
                new StreamCommand(7L, null, null, null, null, true), "alice");
        assertEquals(41L, prepared.userMessageId());
        verify(repo).supersede(41L);
        verify(repo, never()).insertUser(anyLong(), anyString(), any());
        verify(repo, never()).renameIfDefault(anyLong(), anyString());
    }

    @Test
    @DisplayName("regenerate 但会话里还没有用户消息：拒绝")
    void regenerateWithoutHistoryRejected() {
        when(repo.activeHistory(7L, null)).thenReturn(List.of());
        ChatRejectException e = assertThrows(ChatRejectException.class,
                () -> service.prepare(new StreamCommand(7L, null, null, null, null, true), "alice"));
        assertEquals("没有可重新生成的问题", e.getMessage());
    }

    @Test
    @DisplayName("事件序列 meta → delta* → usage → done")
    void emitsHappyPath() {
        when(gateway.stream(any(), any())).thenReturn(Flux.just(text("## "), text("经验"), finish()));
        PreparedTurn prepared = prepareNormal();
        events.clear();
        service.subscribeAsync(prepared, recorderChannel()).accumulated();
        assertEquals(List.of("meta", "delta", "delta", "usage", "done"),
                events.stream().map(e -> e.getClass().getSimpleName()).toList()
                        .stream().map(s -> switch (s) {
                            case "Meta" -> "meta";
                            case "Delta" -> "delta";
                            case "TokenUsage" -> "usage";
                            case "Done" -> "done";
                            default -> "other";
                        }).toList());
        assertEquals("## 经验", ((ChatEvent.Delta) events.get(1)).text()
                + ((ChatEvent.Delta) events.get(2)).text());
        ChatEvent.Meta meta = (ChatEvent.Meta) events.get(0);
        assertEquals(7L, meta.conversationId());
        assertEquals(42L, meta.messageId());
        assertEquals("High", meta.effort());
        ChatEvent.Done done = (ChatEvent.Done) events.get(4);
        assertEquals("done", done.finishReason());
        verify(repo).finalizeAssistant(42L, "## 经验", AiMessage.ST_DONE, AiMessage.STOP_DONE, 812, 1640);
    }

    @Test
    @DisplayName("上游报错：已产出内容落 failed，发 error 再发 done{upstream}")
    void upstreamFailureKeepsPartialContent() {
        when(gateway.stream(any(), any())).thenReturn(
                Flux.concat(Flux.just(text("前半"), text("后半")), Flux.error(new IllegalStateException("429"))));
        PreparedTurn prepared = prepareNormal();
        events.clear();
        service.subscribeAsync(prepared, recorderChannel());
        List<String> kinds = events.stream().map(e -> e.getClass().getSimpleName()).toList();
        assertEquals(List.of("Meta", "Delta", "Delta", "ErrorEvt", "Done"), kinds);
        ChatEvent.ErrorEvt err = (ChatEvent.ErrorEvt) events.get(3);
        assertEquals("UPSTREAM_MODEL_ERROR", err.errorCode());
        assertTrue(err.retryable());
        assertEquals("upstream", ((ChatEvent.Done) events.get(4)).finishReason());
        verify(repo).finalizeAssistant(42L, "前半后半", AiMessage.ST_FAILED, AiMessage.STOP_UPSTREAM, null, null);
        verify(repo).markFailed(42L, err.msg());
    }

    @Test
    @DisplayName("finishReason=length 时状态仍是 done，收尾原因可区分")
    void lengthFinishReason() {
        when(gateway.stream(any(), any())).thenReturn(Flux.just(
                text("正文"), new ChatModelGateway.ChatChunk(null, "length", 10, 20)));
        service.subscribeAsync(prepareNormal(), recorderChannel());
        verify(repo).finalizeAssistant(42L, "正文", AiMessage.ST_DONE, AiMessage.STOP_LENGTH, 10, 20);
        assertEquals("length", ((ChatEvent.Done) events.get(events.size() - 1)).finishReason());
    }

    @Test
    @DisplayName("客户端断连：落 truncated、上游订阅被取消")
    void clientGoneTruncates() {
        when(gateway.stream(any(), any())).thenReturn(Flux.just(text("已产出")));
        SseChannel broken = new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
                throw new IllegalStateException("断开的管道");
            }

            @Override
            public void complete() {
            }
        }, new com.fasterxml.jackson.databind.ObjectMapper());
        PreparedTurn prepared = prepareNormal();
        assertThrows(SseChannel.ClientGoneException.class,
                () -> service.subscribeAsync(prepared, broken));
        verify(repo).finalizeAssistant(eq(42L), anyString(), eq(AiMessage.ST_TRUNCATED),
                eq(AiMessage.STOP_CLIENT_DISCONNECT), any(), any());
        assertEquals(0, registry.size(), "收尾必须从注册表摘除");
    }

    @Test
    @DisplayName("stop：取消上游并把已累积内容落 truncated/stopped")
    void stopKeepsPartialContent() {
        when(gateway.stream(any(), any())).thenReturn(Flux.never());
        service.subscribeAsync(prepareNormal(), recorderChannel());
        assertEquals(1, registry.size());
        service.stop(42L, "alice");
        verify(repo).finalizeAssistant(eq(42L), anyString(), eq(AiMessage.ST_TRUNCATED),
                eq(AiMessage.STOP_STOPPED), any(), any());
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("stop 不属于自己的消息：拒绝")
    void stopChecksOwnership() {
        when(gateway.stream(any(), any())).thenReturn(Flux.never());
        service.subscribeAsync(prepareNormal(), recorderChannel());
        ChatRejectException e = assertThrows(ChatRejectException.class, () -> service.stop(42L, "bob"));
        assertEquals("会话不存在", e.getMessage());
    }

    @Test
    @DisplayName("stop 一个不在注册表的消息：按 DB 状态收敛，不抛异常")
    void stopOrphanIsIdempotent() {
        AiMessage row = new AiMessage();
        row.setId(99L);
        row.setConversationId(7L);
        row.setRole(AiMessage.ROLE_ASSISTANT);
        row.setStatus(AiMessage.ST_STREAMING);
        row.setContent("库里已有的部分");
        when(repo.findById(99L)).thenReturn(row);
        service.stop(99L, "alice");
        verify(repo).finalizeAssistant(99L, "库里已有的部分", AiMessage.ST_TRUNCATED,
                AiMessage.STOP_STOPPED, null, null);
    }

    @Test
    @DisplayName("stop 一个已 done 的消息：什么都不写")
    void stopDoneMessageNoop() {
        AiMessage row = new AiMessage();
        row.setId(98L);
        row.setConversationId(7L);
        row.setStatus(AiMessage.ST_DONE);
        when(repo.findById(98L)).thenReturn(row);
        service.stop(98L, "alice");
        verify(repo, never()).finalizeAssistant(anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("线程池满：不建流，且 T1 已写的行被回滚不了就标 failed")
    void executorRejectionMarksFailed() {
        when(gateway.stream(any(), any())).thenReturn(Flux.never());
        PreparedTurn prepared = prepareNormal();
        ChatStreamService rejecting = new ChatStreamService(repo, conversations,
                new ContextAssembler(new org.leon.aimodule.rag.chunk.TokenCounter(), props),
                new EffortResolver(props), gateway, registry, props,
                command -> {
                    throw new RejectedExecutionException("full");
                });
        assertThrows(ChatRejectException.class, () -> rejecting.subscribeAsync(prepared, recorderChannel()));
        verify(repo).markFailed(eq(42L), anyString());
    }

    /** 把事件塞进 events 的 channel。 */
    private SseChannel recorderChannel() {
        return new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) {
                switch (event) {
                    case "meta" -> events.add(new ChatEvent.Meta(7L, 42L, "deepseek-chat", "High", false));
                    case "delta" -> events.add(new ChatEvent.Delta(readText(json)));
                    case "usage" -> events.add(new ChatEvent.TokenUsage(812, 1640));
                    case "done" -> events.add(new ChatEvent.Done(readFinish(json)));
                    case "error" -> events.add(new ChatEvent.ErrorEvt("UPSTREAM_MODEL_ERROR",
                            "模型服务暂时不可用", true));
                    default -> {
                    }
                }
            }

            @Override
            public void complete() {
            }

            private String readText(String json) {
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("text").asText();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            private String readFinish(String json) {
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json)
                            .get("finishReason").asText();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }, new com.fasterxml.jackson.databind.ObjectMapper());
    }
}
```

> `recorderChannel` 从 JSON 反解事件，是因为 `SseChannel` 的对外契约就是"发帧"。这让测试同时覆盖了事件语义与序列化，代价是断言里要自己解析 JSON。**不要**为了让测试好写而给 `SseChannel` 加一个"事件回调"测试钩子。

- [ ] **Step 3: 跑测试确认失败**

```bash
"$MVN" -o -pl AIModule -am test -Dtest=ChatStreamServiceTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译错误（`ChatStreamService`、`StreamCommand`、`PreparedTurn` 不存在）。

- [ ] **Step 4: 写 `StreamCommand`、`PreparedTurn`**

```java
// stream/StreamCommand.java
package org.leon.aimodule.chat.stream;

public record StreamCommand(Long conversationId, String content, String modelKey, String effort,
                           String clientMsgId, boolean regenerate) {
}
```

```java
// stream/PreparedTurn.java
package org.leon.aimodule.chat.stream;

import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;

import java.util.List;

public record PreparedTurn(Long conversationId, Long userMessageId, Long assistantMessageId,
                           List<CtxMessage> messages, ModelParams params, String userName, String effortName) {
}
```

- [ ] **Step 5: 写 `ChatStreamService`**

```java
package org.leon.aimodule.chat.stream;

import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.entity.AiConversation;
import org.leon.aimodule.chat.entity.AiMessage;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.repo.ChatTurnRepository;
import org.leon.aimodule.chat.service.ContextAssembler;
import org.leon.aimodule.chat.service.ConversationService;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.EffortResolver;
import org.leon.aimodule.chat.service.ModelParams;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class ChatStreamService {

    static final String BUSY = "上一条回答仍在生成";
    static final String NOT_FOUND = "会话不存在";

    private final ChatTurnRepository repo;
    private final ConversationService conversations;
    private final ContextAssembler assembler;
    private final EffortResolver resolver;
    private final ChatModelGateway gateway;
    private final ChatStreamRegistry registry;
    private final ChatProperties props;
    private final Executor streamExecutor;

    public ChatStreamService(ChatTurnRepository repo, ConversationService conversations,
                            ContextAssembler assembler, EffortResolver resolver, ChatModelGateway gateway,
                            ChatStreamRegistry registry, ChatProperties props,
                            @org.springframework.beans.factory.annotation.Qualifier("chatStreamExecutor")
                            Executor streamExecutor) {
        this.repo = repo;
        this.conversations = conversations;
        this.assembler = assembler;
        this.resolver = resolver;
        this.gateway = gateway;
        this.registry = registry;
        this.props = props;
        this.streamExecutor = streamExecutor;
    }

    /** T1：建流之前的全部数据库动作。任何拒绝都在这里抛出。 */
    public PreparedTurn prepare(StreamCommand cmd, String userName) {
        AiConversation conversation = conversations.requireOwned(userName, cmd.conversationId());
        Long cid = conversation.getId();
        int stale = props.getStaleStreamReapMinutes();
        repo.reapStale(cid, stale);
        if (repo.countLive(cid, stale) > 0) {
            throw new ChatRejectException(BUSY);
        }
        ModelParams params = resolver.resolve(
                cmd.modelKey() == null ? conversation.getModelKey() : cmd.modelKey(),
                cmd.effort() == null ? conversation.getEffort() : cmd.effort());

        Long userMessageId;
        String question;
        boolean regenerate = cmd.regenerate();
        AiMessage existing = repo.findByClientMsgId(cid, cmd.clientMsgId());
        if (existing != null) {
            userMessageId = existing.getId();
            question = existing.getContent();
            regenerate = true;
        } else if (regenerate) {
            List<AiMessage> history = repo.activeHistory(cid, null);
            AiMessage last = null;
            for (AiMessage m : history) {
                if (AiMessage.ROLE_USER.equals(m.getRole())) {
                    last = m;
                }
            }
            if (last == null) {
                throw new ChatRejectException("没有可重新生成的问题");
            }
            userMessageId = last.getId();
            question = last.getContent();
        } else {
            if (cmd.content() == null || cmd.content().isBlank()) {
                throw new ChatRejectException("content 不能为空");
            }
            question = cmd.content();
            userMessageId = repo.insertUser(cid, question, cmd.clientMsgId());
            repo.touch(cid, userMessageId, question, 1);
        }
        if (regenerate) {
            repo.supersede(userMessageId);
        }
        String effortName = effortName(cmd, conversation);
        String assistantClientMsgId = cmd.clientMsgId() == null || cmd.clientMsgId().isBlank()
                ? null : cmd.clientMsgId().trim() + ":a";
        Long assistantId = repo.insertAssistant(cid, userMessageId, params.modelKey(),
                effortName, assistantClientMsgId);
        repo.touch(cid, assistantId, question, regenerate ? 0 : 1);
        if (!regenerate) {
            repo.renameIfDefault(cid, question);
        }
        List<AiMessage> prior = repo.activeHistory(cid, userMessageId);
        List<CtxMessage> messages = assembler.assemble(prior, question);
        return new PreparedTurn(cid, userMessageId, assistantId, messages, params, userName, effortName);
    }

    /** 订阅上游并把事件推给 channel。executor 已限流，这里只跑一次。 */
    public ActiveStream subscribeAsync(PreparedTurn prepared, SseChannel channel) {
        ActiveStream stream = new ActiveStream(prepared.assistantMessageId(), prepared.conversationId(),
                prepared.userName(), channel, new AtomicReference<>(), new StringBuilder(),
                new AtomicReference<>(AiMessage.STOP_DONE), new AtomicReference<>(new int[]{-1, -1}));
        // 必须先登记再提交任务：秒回的流会在任务跑完时把自己从注册表里摘掉，
        // 若登记放在订阅之后，那条已完成的流会被重新塞回注册表成为僵尸。
        registry.register(stream.messageId(), stream);
        try {
            streamExecutor.execute(() -> run(stream, prepared, channel));
        } catch (RejectedExecutionException e) {
            registry.remove(stream.messageId());
            repo.markFailed(prepared.assistantMessageId(), "服务繁忙，未开始生成");
            channel.close();
            throw new ChatRejectException("服务繁忙，请稍后重试");
        }
        return stream;
    }

    private void run(ActiveStream stream, PreparedTurn prepared, SseChannel channel) {
        ModelParams params = prepared.params();
        try {
            channel.emit(new ChatEvent.Meta(prepared.conversationId(), stream.messageId(),
                    params.modelKey(), prepared.effortName(), params.supportsThinking()));
        } catch (SseChannel.ClientGoneException gone) {
            finishTruncated(stream, AiMessage.STOP_CLIENT_DISCONNECT);
            return;
        }
        Disposable disposable = gateway.stream(prepared.messages(), params).subscribe(
                chunk -> onChunk(stream, chunk),
                error -> onError(stream, prepared, channel, error),
                () -> onComplete(stream, prepared, channel));
        stream.upstream().set(disposable);
    }

    private void onChunk(ActiveStream stream, ChatModelGateway.ChatChunk chunk) {
        if (chunk.text() != null) {
            stream.append(chunk.text());
            try {
                stream.channel().emit(new ChatEvent.Delta(chunk.text()));
            } catch (SseChannel.ClientGoneException gone) {
                disposeQuietly(stream);
                finishTruncated(stream, AiMessage.STOP_CLIENT_DISCONNECT);
            }
        }
        if (chunk.finishReason() != null) {
            stream.finishReason().set(chunk.finishReason());
        }
        if (chunk.promptTokens() != null || chunk.completionTokens() != null) {
            stream.usage().set(new int[]{
                    chunk.promptTokens() == null ? -1 : chunk.promptTokens(),
                    chunk.completionTokens() == null ? -1 : chunk.completionTokens()});
        }
    }

    private void onComplete(ActiveStream stream, PreparedTurn prepared, SseChannel channel) {
        int[] usage = stream.usage().get();
        if (usage[0] >= 0 || usage[1] >= 0) {
            emitQuietly(channel, new ChatEvent.TokenUsage(usage[0] < 0 ? null : usage[0],
                    usage[1] < 0 ? null : usage[1]));
        }
        String reason = mapFinishReason(stream.finishReason().get());
        repo.finalizeAssistant(stream.messageId(), stream.accumulated(), AiMessage.ST_DONE, reason,
                usage[0] < 0 ? null : usage[0], usage[1] < 0 ? null : usage[1]);
        emitQuietly(channel, new ChatEvent.Done(reason));
        channel.close();
        registry.remove(stream.messageId());
    }

    private void onError(ActiveStream stream, PreparedTurn prepared, SseChannel channel, Throwable error) {
        String msg = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        log.warn("[chat] 上游失败 messageId={} err={}", stream.messageId(), msg);
        repo.finalizeAssistant(stream.messageId(), stream.accumulated(), AiMessage.ST_FAILED,
                AiMessage.STOP_UPSTREAM, null, null);
        repo.markFailed(stream.messageId(), msg);
        emitQuietly(channel, new ChatEvent.ErrorEvt("UPSTREAM_MODEL_ERROR", "模型服务暂时不可用", true));
        emitQuietly(channel, new ChatEvent.Done(AiMessage.STOP_UPSTREAM));
        channel.close();
        registry.remove(stream.messageId());
    }

    public void stop(Long messageId, String userName) {
        ActiveStream stream = registry.find(messageId);
        if (stream != null) {
            if (!stream.userName().equals(userName)) {
                throw new ChatRejectException(NOT_FOUND);
            }
            disposeQuietly(stream);
            finishTruncated(stream, AiMessage.STOP_STOPPED);
            return;
        }
        AiMessage row = repo.findById(messageId);
        if (row == null) {
            throw new ChatRejectException(NOT_FOUND);
        }
        conversations.requireOwned(userName, row.getConversationId());
        if (!AiMessage.ST_STREAMING.equals(row.getStatus())) {
            return;
        }
        repo.finalizeAssistant(row.getId(), row.getContent() == null ? "" : row.getContent(),
                AiMessage.ST_TRUNCATED, AiMessage.STOP_STOPPED, null, null);
    }

    private void finishTruncated(ActiveStream stream, String stopReason) {
        repo.finalizeAssistant(stream.messageId(), stream.accumulated(), AiMessage.ST_TRUNCATED,
                stopReason, null, null);
        stream.channel().close();
        registry.remove(stream.messageId());
    }

    private void emitQuietly(SseChannel channel, ChatEvent event) {
        try {
            channel.emit(event);
        } catch (SseChannel.ClientGoneException ignored) {
            // 客户端已经不在了，落库已完成，无需再处理
        }
    }

    private void disposeQuietly(ActiveStream stream) {
        stream.disposeUpstream();
    }

    private static String mapFinishReason(String upstream) {
        if (upstream == null) {
            return AiMessage.STOP_DONE;
        }
        return switch (upstream) {
            case "length" -> AiMessage.STOP_LENGTH;
            default -> AiMessage.STOP_DONE;
        };
    }

    private String effortName(StreamCommand cmd, AiConversation conversation) {
        return cmd.effort() == null || cmd.effort().isBlank() ? conversation.getEffort() : cmd.effort().trim();
    }
}
```

**上面这份实现有三处必须在写代码时一并改掉，否则 Step 6 过不了**（都是接口对不齐，不是思路问题）：

1. **档位有两个真相源**。`resolver.resolve(...)` 在 `cmd.effort()` 为空时回落到 `chat.default-effort`，而 `effortName(cmd, conversation)` 回落到 `ai_conversation.effort`。两者不一致时会出现"按 Medium 的参数生成、按 High 落库"。改法：`prepare()` 里先算一次 `String effortName = effortName(cmd, conversation);`，再 `resolver.resolve(model, effortName)`，`effortName(...)` 内部空值直接返回 `props.getDefaultEffort()`，不再看会话列。`ai_conversation.effort` 只在 #2 创建会话时定一次，作为该会话的默认档位——它参与 `effortName` 的回落链，就必须和 `resolve` 用同一条链，否则这个列是摆设。`prepareWritesBeforeStream` 里加一条断言：`verify(repo).insertAssistant(eq(7L), eq(41L), anyString(), eq("High"), any())` 与 `prepared.params().temperature()` 来自同一档位。
2. `stop` 依赖 `repo.findById(Long)`，`ChatTurnRepository` 还没有这个方法。补：
   ```java
       public AiMessage findById(Long id) {
           return id == null ? null : messageMapper.selectById(id);
       }
   ```
3. `onError` 先 `finalizeAssistant(... ST_FAILED ...)` 又 `markFailed(...)`，等于写了两次 UPDATE，直接违反 5.5 的写次数不变式，而且 `markFailed` 的 `status='streaming'` 条件在第一次写之后已经不成立，等于白写一次。**改成只写一次**：给 `AiMessageMapper.finalizeAssistant` 已有的 `error_msg` 缺口补一个参数版本，或直接把 `markFailed` 那行删掉、把 `msg` 塞进 `finalizeAssistant`。本计划选后者：在 `ChatTurnRepository` 加
   ```java
       @Transactional
       public void finalizeFailed(Long id, String content, String errorMsg) {
           messageMapper.finalizeFailed(id, content, ChatTime.truncate(errorMsg, 250));
       }
   ```
   并在 `AiMessageMapper` 加
   ```java
       @Update("UPDATE ai_message SET content = #{content}, status = 'failed', stop_reason = 'upstream', "
               + "error_msg = #{errorMsg}, update_time = NOW(3) WHERE id = #{id}")
       int finalizeFailed(@Param("id") Long id, @Param("content") String content,
                          @Param("errorMsg") String errorMsg);
   ```
   `onError` 里改成 `repo.finalizeFailed(stream.messageId(), stream.accumulated(), msg);`，删掉原来的 `finalizeAssistant` + `markFailed` 两行。测试 `upstreamFailureKeepsPartialContent` 相应改成 `verify(repo).finalizeFailed(42L, "前半后半", anyString())`，并 `verify(repo, never()).markFailed(anyLong(), anyString())`。

- [ ] **Step 6: 跑测试**

```bash
"$MVN" -o -pl AIModule -am test -Dtest=ChatStreamServiceTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全绿。逐条对应：`prepareWritesBeforeStream` 验 T1 写序；`retryBecomesRegenerate` 验幂等；`emitsHappyPath` 验事件序列；`clientGoneTruncates` 验断连；`executorRejectionMarksFailed` 验拒绝。

- [ ] **Step 7: 写 `ChatStreamWriteCountTest`（锁死 5.5 不变式）**

```java
package org.leon.aimodule.chat.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.aimodule.chat.repo.ChatTurnRepository;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatStreamWriteCountTest {

    @Test
    @DisplayName("3000 个 token 的回答，写库次数与 token 数无关")
    void writeCountIsConstant() {
        ChatTurnRepository repo = mock(ChatTurnRepository.class);
        // 复用 ChatStreamServiceTest 的装配：gateway 返回 3000 个 chunk
        when(repo.insertUser(any(), anyString(), any())).thenReturn(41L);
        when(repo.insertAssistant(any(), any(), anyString(), anyString(), any())).thenReturn(42L);
        when(repo.activeHistory(any(), any())).thenReturn(java.util.List.of());
        when(gatewayStream(3000)).thenReturn(Flux.empty());

        ChatStreamFixture fixture = new ChatStreamFixture(repo);
        fixture.prepareAndStream();

        InOrder order = inOrder(repo);
        order.verify(repo).reapStale(eq(7L), eq(5));
        order.verify(repo).insertUser(eq(7L), anyString(), any());
        order.verify(repo).touch(eq(7L), eq(41L), anyString(), eq(1));
        order.verify(repo).insertAssistant(eq(7L), eq(41L), anyString(), anyString(), any());
        order.verify(repo).touch(eq(7L), eq(42L), anyString(), eq(1));
        order.verify(repo).renameIfDefault(eq(7L), anyString());
        order.verify(repo).finalizeAssistant(eq(42L), anyString(), anyString(), anyString(), any(), any());
        verifyNoMoreInteractions(repo);
        order.verify(repo, times(1)).finalizeAssistant(any(), any(), any(), any(), any(), any());
    }

    private static Flux<ChatModelGateway.ChatChunk> gatewayStream(int chunks) {
        return Flux.fromStream(IntStream.rangeClosed(1, chunks).mapToObj(i -> "字"));
    }
}
```

> 这个测试的**价值全在 `verifyNoMoreInteractions(repo)`**：任何人往 chunk 回调里加一句"顺手 update 一下进度"都会立刻红。`ChatStreamFixture` 是把 `ChatStreamServiceTest.setUp()` 的装配抽成一个包私有的构造助手（`ChatStreamFixture(ChatTurnRepository repo)` 暴露 `prepareAndStream()`），两个测试类共用，避免复制 40 行 mock 装配。抽的时候把 gateway 固定成 `gatewayStream(3000)`，并让 executor 用 `Runnable::run`。
> 上面代码里 `when(gatewayStream(3000))` 是笔误（对方法调用打桩没有意义），落地时删掉它，改为在 fixture 内部 `when(gateway.stream(any(), any())).thenReturn(Flux.fromStream(...))`。`InOrder` 之后再 `verifyNoMoreInteractions` 是 Mockito 支持的组合，若版本行为不一致，退化成"先 `verifyNoMoreInteractions` 再单独 `verify(times(1))`"。

- [ ] **Step 8: 跑测试确认通过**

```bash
"$MVN" -o -pl AIModule -am test -Dtest='ChatStreamServiceTest,ChatStreamWriteCountTest' \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全绿。

- [ ] **Step 9: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/chat/repo/ \
        AIModule/src/main/java/org/leon/aimodule/chat/stream/ \
        AIModule/src/main/java/org/leon/aimodule/chat/mapper/AiMessageMapper.java \
        AIModule/src/test/java/org/leon/aimodule/chat/stream/
git commit -m "新增对话编排层：三段短事务、幂等重试即重新生成、写次数不变式测试"
```

---

### Task 11: 流式接口 #7 与停止接口 #8

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/web/dto/StreamRequest.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/web/ChatStreamController.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/web/ChatStreamControllerTest.java`

**Interfaces:**
- Consumes: Task 10 `ChatStreamService.prepare/subscribeAsync/stop`、Task 9 `SseChannel`、Task 5 `ChatExceptionHandler`
- Produces: `POST /ai/chat/completions/stream`、`POST /ai/chat/messages/{id}/stop`

- [ ] **Step 1: 写失败测试**

```java
package org.leon.aimodule.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.stream.ChatStreamService;
import org.leon.aimodule.chat.stream.StreamCommand;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatStreamControllerTest {

    private ChatStreamService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ChatStreamService.class);
        ChatProperties props = new ChatProperties();
        mvc = MockMvcBuilders.standaloneSetup(new ChatStreamController(service, new ObjectMapper(), props,
                        java.util.concurrent.Executors.newSingleThreadScheduledExecutor()))
                .setControllerAdvice(new ChatExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("建流前被拒：返回 application/json 的 Result，绝不返回 SSE")
    void rejectIsPlainJson() throws Exception {
        when(service.prepare(any(), anyString())).thenThrow(new ChatRejectException("上一条回答仍在生成"));
        mvc.perform(post("/ai/chat/completions/stream").header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":7,\"content\":\"hi\",\"clientMsgId\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("上一条回答仍在生成"));
    }

    @Test
    @DisplayName("缺 X-User-Name：不建流")
    void missingHeader() throws Exception {
        mvc.perform(post("/ai/chat/completions/stream").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":7,\"content\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("缺少请求头: X-User-Name"));
        verify(service, never()).prepare(any(), anyString());
    }

    @Test
    @DisplayName("content 与 regenerate 都没有：参数校验拒绝")
    void blankContentRejected() throws Exception {
        mvc.perform(post("/ai/chat/completions/stream").header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(service, never()).prepare(any(), anyString());
    }

    @Test
    @DisplayName("成功：进入异步、Content-Type 是 text/event-stream")
    void happyPathStartsAsync() throws Exception {
        when(service.prepare(any(), eq("alice"))).thenReturn(TestTurns.prepared());
        when(service.subscribeAsync(any(), any())).thenAnswer(inv -> {
            org.leon.aimodule.chat.stream.SseChannel ch = inv.getArgument(1);
            ch.emit(new org.leon.aimodule.chat.stream.ChatEvent.Meta(7L, 42L, "deepseek-chat", "High", false));
            ch.emit(new org.leon.aimodule.chat.stream.ChatEvent.Done("done"));
            ch.close();
            return TestTurns.activeStream();
        });
        mvc.perform(post("/ai/chat/completions/stream").header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":7,\"content\":\"hi\",\"clientMsgId\":\"c1\"}"))
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("stop 转发给编排层并返回 success")
    void stopForwards() throws Exception {
        mvc.perform(post("/ai/chat/messages/42/stop").header("X-User-Name", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        verify(service).stop(42L, "alice");
    }

    @Test
    @DisplayName("stop 别人的消息：统一文案")
    void stopRejectsOthers() throws Exception {
        doThrow(new ChatRejectException("会话不存在")).when(service).stop(42L, "bob");
        mvc.perform(post("/ai/chat/messages/42/stop").header("X-User-Name", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("会话不存在"));
    }
}
```

> `TestTurns` 是同包下的测试助手，造一个 `PreparedTurn` 和一个 `ActiveStream`：
> ```java
> package org.leon.aimodule.chat.web;
> 
> import org.leon.aimodule.chat.stream.ActiveStream;
> import org.leon.aimodule.chat.stream.PreparedTurn;
> import org.leon.aimodule.chat.service.CtxMessage;
> import org.leon.aimodule.chat.service.ModelParams;
> 
> import java.util.List;
> import java.util.concurrent.atomic.AtomicReference;
> 
> final class TestTurns {
>     private TestTurns() {
>     }
> 
>     static PreparedTurn prepared() {
>         return new PreparedTurn(7L, 41L, 42L, List.of(new CtxMessage("user", "hi")),
>                 new ModelParams("deepseek-chat", 1.0, 4096, false), "alice", "High");
>     }
> 
>     static ActiveStream activeStream() {
>         return new ActiveStream(42L, 7L, "alice", null, new AtomicReference<>(), new StringBuilder(),
>                 new AtomicReference<>("done"), new AtomicReference<>(new int[]{-1, -1}));
>     }
> }
> ```

- [ ] **Step 2: 跑测试确认失败**

```bash
"$MVN" -o -pl AIModule -am test -Dtest=ChatStreamControllerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译错误（`ChatStreamController`、`StreamRequest` 不存在）。

- [ ] **Step 3: 写 DTO 与 Controller**

```java
// web/dto/StreamRequest.java
package org.leon.aimodule.chat.web.dto;

import jakarta.validation.constraints.Size;

public record StreamRequest(Long conversationId,
                            @Size(max = 32000, message = "单条消息过长") String content,
                            String modelKey, String effort,
                            @Size(max = 40, message = "clientMsgId 最长 40 字符") String clientMsgId,
                            boolean regenerate) {
}
```

```java
package org.leon.aimodule.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.stream.ChatStreamService;
import org.leon.aimodule.chat.stream.PreparedTurn;
import org.leon.aimodule.chat.stream.SseChannel;
import org.leon.aimodule.chat.stream.StreamCommand;
import org.leon.aimodule.chat.exception.ChatRejectException;
import org.leon.aimodule.chat.web.dto.StreamRequest;
import org.leon.commonjwt.pojo.Result;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * produces 同时声明 SSE 与 JSON：建流前的业务拒绝要返回 Result(JSON)，
 * 若只声明 text/event-stream，客户端带 Accept: text/event-stream 时
 * advice 的 JSON 体协商不出来，会变成 406。
 */
@RestController
@RequestMapping("/ai/chat")
@Validated
public class ChatStreamController {

    private final ChatStreamService service;
    private final ObjectMapper mapper;
    private final ChatProperties props;
    private final ScheduledExecutorService heartbeat;

    public ChatStreamController(ChatStreamService service, ObjectMapper mapper, ChatProperties props,
                               @Qualifier("chatHeartbeatExecutor") ScheduledExecutorService heartbeat) {
        this.service = service;
        this.mapper = mapper;
        this.props = props;
        this.heartbeat = heartbeat;
    }

    @PostMapping(value = "/completions/stream",
            produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public SseEmitter stream(@RequestHeader("X-User-Name") String userName,
                             @RequestBody @Valid StreamRequest req,
                             HttpServletResponse response) {
        if (!req.regenerate() && (req.content() == null || req.content().isBlank())) {
            throw new ChatRejectException("content 不能为空");
        }
        PreparedTurn prepared = service.prepare(new StreamCommand(req.conversationId(), req.content(),
                req.modelKey(), req.effort(), req.clientMsgId(), req.regenerate()), userName);

        // 代理侧不聚合响应体；Nginx 还需 proxy_buffering off。
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");

        SseEmitter emitter = new SseEmitter(props.getStreamTimeoutMs());
        SseChannel channel = new SseChannel(new SseChannel.Sink() {
            @Override
            public void send(String event, String json) throws IOException {
                emitter.send(SseEmitter.event().name(event).data(json));
            }

            @Override
            public void complete() {
                emitter.complete();
            }
        }, mapper);

        // 心跳独立于业务事件：空闲 15 秒以上会被代理断流。
        ScheduledFuture<?> ping = heartbeat.scheduleAtFixedRate(channel::ping,
                props.getHeartbeatIntervalSeconds(), props.getHeartbeatIntervalSeconds(), TimeUnit.SECONDS);
        Runnable cancelPing = () -> {
            ping.cancel(false);
            service.clientGone(prepared.assistantMessageId());
        };
        emitter.onTimeout(() -> {
            ping.cancel(false);
            service.timeout(prepared.assistantMessageId());
        });
        emitter.onError(t -> cancelPing.run());
        emitter.onCompletion(cancelPing);

        service.subscribeAsync(prepared, channel);
        return emitter;
    }

    @PostMapping("/messages/{id}/stop")
    public Result<Void> stop(@RequestHeader("X-User-Name") String userName, @PathVariable Long id) {
        service.stop(id, userName);
        return Result.success();
    }
}
```

> `onCompletion` 在正常 `done` 之后也会被 Spring 调用，所以 `service.clientGone(...)` **必须对已结束的流幂等**（查注册表，查不到直接 return）——Step 4 的 `truncateIfActive` 就是这个保证。心跳在三个回调里都要 `cancel`，否则一条已完成的流会永远占着调度队列，`ScheduledThreadPoolExecutor` 的队列不会自己收缩。

- [ ] **Step 4: 给 `ChatStreamService` 补收尾入口**

```java
    public void timeout(Long messageId) {
        truncateIfActive(messageId, AiMessage.STOP_TIMEOUT);
    }

    public void clientGone(Long messageId) {
        truncateIfActive(messageId, AiMessage.STOP_CLIENT_DISCONNECT);
    }

    /** 只有还在注册表里的流才需要收尾；正常 done 之后这里是空操作。 */
    private void truncateIfActive(Long messageId, String stopReason) {
        ActiveStream stream = registry.find(messageId);
        if (stream == null) {
            return;
        }
        disposeQuietly(stream);
        finishTruncated(stream, stopReason);
    }
```

并在 `ChatStreamServiceTest` 追加两条：

```java
    @Test
    @DisplayName("正常收尾后 emitter 回调不得把 done 改成 truncated")
    void callbacksAfterDoneAreNoop() {
        when(gateway.stream(any(), any())).thenReturn(Flux.just(text("正文"), finish()));
        service.subscribeAsync(prepareNormal(), recorderChannel());
        service.clientGone(42L);
        service.timeout(42L);
        verify(repo, times(1)).finalizeAssistant(eq(42L), anyString(), eq(AiMessage.ST_DONE),
                eq(AiMessage.STOP_DONE), any(), any());
        verify(repo, never()).finalizeAssistant(eq(42L), anyString(), eq(AiMessage.ST_TRUNCATED),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("超时收尾用独立 stop_reason，与客户端断连可区分")
    void timeoutWritesTimeoutReason() {
        when(gateway.stream(any(), any())).thenReturn(Flux.never());
        service.subscribeAsync(prepareNormal(), recorderChannel());
        service.timeout(42L);
        verify(repo).finalizeAssistant(eq(42L), anyString(), eq(AiMessage.ST_TRUNCATED),
                eq(AiMessage.STOP_TIMEOUT), any(), any());
    }
```
（需要 `import static org.mockito.Mockito.times;`。）

- [ ] **Step 5: 跑全部相关测试**

```bash
"$MVN" -o -pl AIModule -am test \
  -Dtest='ChatStreamControllerTest,ChatStreamServiceTest,ChatStreamWriteCountTest' \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全绿。

- [ ] **Step 6: 跑整个模块的单测（排除 IT 标签）**

```bash
"$MVN" -o -pl AIModule -am test
```
Expected: 全绿，且 `ChatMapperDbIT` 不在其中（`db-it` 被 `surefire.excludedGroups` 排除）。

- [ ] **Step 7: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/chat/web/ChatStreamController.java \
        AIModule/src/main/java/org/leon/aimodule/chat/web/dto/StreamRequest.java \
        AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatStreamService.java \
        AIModule/src/test/java/org/leon/aimodule/chat/web/ \
        AIModule/src/test/java/org/leon/aimodule/chat/stream/ChatStreamServiceTest.java
git commit -m "新增流式与停止接口：拒绝走 JSON、成功走 SSE，emitter 回调对已结束流幂等"
```

---

### Task 12: 走网关的端到端验收（关卡 G3、条件 G4）

**Files:**
- Modify: `docs/superpowers/specs/2026-10-23-ai-chat-conversation-design.md`（把实测结论写回对应小节）
- Create: `docs/superpowers/plans/2026-10-23-ai-chat-e2e-log.md`（验收记录，含实测帧样例）

**Interfaces:**
- Consumes: 前 11 个任务的全部产物；运行中的 Gateway(8100)、AuthModule(8101)、AIModule(8105)、Nacos(8848)、Redis(6379)、MySQL(3306)
- Produces: 勾选过的验收清单 + 实测偏差记录

- [ ] **Step 1: 先证明网关这条链是通的（不要跳过）**

`common-jwt.yaml` 这个 dataId 在 Nacos 里**不存在**（实际存在的是 `common-jwt-dev.yaml`），而 `GatewayModule/src/main/resources/application.yaml` 与 `AIModule` 都 `import` 了它。因此"网关能验签"目前只是推断，未证实。先测：

```bash
curl -sS -o /dev/null -w "models_noauth=%{http_code}\n" http://127.0.0.1:8100/ai/chat/models
curl -sS -w "\ntestlogin_noauth=%{http_code}\n" http://127.0.0.1:8100/auth/testLogin | tail -2
```
Expected：两条都 `401`（网关 `JwtAuthGlobalFilter` 生效、`/ai/**` 路由存在）。
- 若 `404`：路由没覆盖到 `/ai/chat/**`，停下报告，需要改 Nacos（G4）。
- 若 `200`：网关没拦，说明该实例的过滤器或白名单与仓库不一致，**停下报告**，不要在没鉴权的链路上继续。

- [ ] **Step 2: 关卡 G3 —— 要一个可用账号**

向用户要 `users_dev` 里可用的 `username` + 密码（登录走 `passwordEncoder.matches` BCrypt）。用户没有的话，请求同意后插一个专用测试账号：用 jshell 加载 `spring-security-crypto` 生成 BCrypt 串，再 `INSERT`（这属于改主库数据，必须单独批准）。

- [ ] **Step 3: 取 token（验证码自动识别）**

```bash
curl -sS http://127.0.0.1:8100/auth/captcha -o /tmp/captcha.json
python - <<'PY'
import json, base64
d = json.load(open('/tmp/captcha.json'))
data = d['data']['image'] if isinstance(d.get('data'), dict) else d['data']['imageBase64Data']
raw = data.split(',', 1)[-1]
open('/tmp/captcha.png','wb').write(base64.b64decode(raw))
print('captchaKey=', (d['data'].get('captchaKey') if isinstance(d.get('data'), dict) else None))
PY
```
用 Read 工具打开 `/tmp/captcha.png` 读出 4 位数字，然后：

```bash
TOKEN=$(curl -sS -X POST http://127.0.0.1:8100/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"<USER>","password":"<PASS>","captchaKey":"<KEY>","captchaCode":"<CODE>"}' \
  | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if isinstance(d.get('data'),dict) else '')")
echo "token_len=${#TOKEN}"
```
Expected：`token_len > 100`。若为空，先 `curl` 原始响应看 `msg`（验证码错 / 密码错 / 字段名不同），把真实字段名记进验收记录——`AuthServiceImpl.login` 的返回结构本任务不假设。

- [ ] **Step 4: 控制面逐个走一遍**

```bash
BASE=http://127.0.0.1:8100/ai/chat
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
curl -sS "${H[@]}" "$BASE/models"; echo
CID=$(curl -sS "${H[@]}" -X POST "$BASE/conversations" -d '{"title":"联调会话","effort":"High"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
echo "CID=$CID"
curl -sS "${H[@]}" "$BASE/conversations?limit=5"; echo
curl -sS "${H[@]}" -X PATCH "$BASE/conversations/$CID" -d '{"title":"改过名"}'; echo
curl -sS "${H[@]}" "$BASE/conversations/$CID/messages?limit=5"; echo
```
Expected：`models` 里 `efforts` 恰为 `["Low","Medium","High","Extra","Max"]`；`createTime/updateTime` 是 13 位数字；PATCH 与 messages 都 `code:1`。

- [ ] **Step 5: 流式主链路**

```bash
curl -sS -N "${H[@]}" -H 'Accept: text/event-stream, application/json' \
  -X POST "$BASE/completions/stream" \
  -d "{\"conversationId\":$CID,\"content\":\"用三句话介绍快速排序\",\"effort\":\"High\",\"clientMsgId\":\"e2e-1\"}" \
  | tee /tmp/stream1.txt
```
Expected：按序出现 `event: meta` → 若干 `event: delta` → `event: usage` → `event: done`，`data` 都是单行 JSON。把 `/tmp/stream1.txt` 的头 5 帧与末 3 帧原样贴进验收记录。

- [ ] **Step 6: 落库与不变式核对**

```bash
cat > /tmp/check_turn.jsh <<'EOF'
import java.sql.*;
Connection c = DriverManager.getConnection("jdbc:mysql://localhost:3306/ezpracticeexercises?useSSL=false&serverTimezone=Asia/Shanghai","root","");
ResultSet r = c.createStatement().executeQuery("SELECT id,conversation_id,role,status,stop_reason,length(content),prompt_tokens,completion_tokens,client_msg_id,update_time,create_time FROM ai_message WHERE conversation_id=" + System.getenv("CID") + " ORDER BY id");
while (r.next()) System.out.println(r.getLong(1)+" | "+r.getString(3)+" | "+r.getString(4)+" | "+r.getString(5)+" | len="+r.getInt(6)+" | pt="+r.getInt(7)+" | ct="+r.getInt(8)+" | cmid="+r.getString(9)+" | dt_ms="+(r.getTimestamp(10).getTime()-r.getTimestamp(11).getTime()));
r.close(); c.close();
/exit
EOF
CID=$CID "$JAVA_HOME/bin/jshell" --class-path "C:/Users/Administrator/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar" -q /tmp/check_turn.jsh
```
Expected：两条行（user `done` + assistant `done`），assistant 的 `update_time - create_time` 等于生成耗时（**不是**几百次更新），`stop_reason=done`，`pt/ct` 非 0。若 `pt=0`，说明 `stream-usage` 没生效或 DeepSeek 不返 usage —— 记进验收记录并回查 Task 1 Step 10 那行配置。

- [ ] **Step 7: 幂等、重新生成、并发拒绝**

```bash
# 同一个 clientMsgId 再发一次 → 不产生第二条用户消息，旧回答转 superseded
curl -sS -N "${H[@]}" -X POST "$BASE/completions/stream" \
  -d "{\"conversationId\":$CID,\"content\":\"重复提交的旧文本\",\"effort\":\"High\",\"clientMsgId\":\"e2e-1\"}" > /tmp/stream2.txt
# 重新生成
curl -sS -N "${H[@]}" -X POST "$BASE/completions/stream" \
  -d "{\"conversationId\":$CID,\"regenerate\":true}" > /tmp/stream3.txt
# 并发：两条流同时打，第二条必须被拒
(curl -sS -N "${H[@]}" -X POST "$BASE/completions/stream" -d "{\"conversationId\":$CID,\"content\":\"第一个长问题，请详细展开\",\"clientMsgId\":\"c-a\"}" > /tmp/a.txt &)
sleep 1
curl -sS "${H[@]}" -X POST "$BASE/completions/stream" -d "{\"conversationId\":$CID,\"content\":\"第二个问题\",\"clientMsgId\":\"c-b\"}"; echo
```
Expected：Step 7 第一条之后 `SELECT count(*) FROM ai_message WHERE conversation_id=$CID AND role='user'` 仍为 1；`superseded` 行数 ≥1；并发第二条返回 JSON `code:0` + `上一条回答仍在生成`（**不是** SSE）。

- [ ] **Step 8: stop 与断连**

```bash
curl -sS -N "${H[@]}" -X POST "$BASE/completions/stream" \
  -d "{\"conversationId\":$CID,\"content\":\"写一段 2000 字的说明\",\"clientMsgId\":\"c-stop\"}" > /tmp/stream4.txt &
sleep 2
MID=$(grep -m1 '^data:' /tmp/stream4.txt | sed 's/^data: //' | python -c "import sys,json;print(json.load(sys.stdin)['messageId'])")
curl -sS "${H[@]}" -X POST "$BASE/messages/$MID/stop"; echo
sleep 1
curl -sS "${H[@]}" "$BASE/conversations/$CID/messages?limit=3" | python -c "import sys,json;[print(m['id'],m['role'],m['status'],m.get('stopReason'),len(m['content'])) for m in json.load(sys.stdin)['data']['items']]"
```
Expected：该 assistant 行 `status=truncated`、`stopReason=stopped`、`content` 长度 > 0（**已产出内容保留**）。再跑一次 stop 应返回 `code:1`（幂等）。

- [ ] **Step 9: 上游故障注入**

把 `AIModule/src/main/resources/application.yaml` 的 `spring.ai.openai.api-key` 临时改成一个非法值，重启 AIModule，再发一次流式请求。
Expected：HTTP 200 + SSE，帧序列以 `event: error`（`"retryable":true`）+ `event: done`（`"finishReason":"upstream"`）收尾；库里该行为 `status=failed`、`error_msg` 非空、`content` 保留已产出部分。**验完立刻把 api-key 改回来**，并确认 `git diff` 里不含该改动。

- [ ] **Step 10: 长流与网关超时（条件关卡 G4）**

发一个必然很长的请求（"请逐条列举 100 个排序算法并各写一段说明"），计时观察是否中途断流：
```bash
time curl -sS -N "${H[@]}" -X POST "$BASE/completions/stream" \
  -d "{\"conversationId\":$CID,\"content\":\"请逐条列举 100 个排序算法并各写一段说明\",\"clientMsgId\":\"c-long\"}" \
  | grep -c '^event:'
```
若在 30s/60s 附近整齐地断，说明网关 `response-timeout` 生效并掐断了长流 → 需要改 Nacos `gateway.yaml`（G4，**必须先征得同意**，它影响所有服务）。若不断，记录实测最长流时长。

- [ ] **Step 11: 把实测结论写回文档并提交**

- 在 `2026-10-23-ai-chat-e2e-log.md` 记录：每步命令、原始响应片段、与 spec 的偏差（尤其 `usage` 是否真到、DeepSeek 是否忽略 `temperature`、stop 的 `stop_reason` 实际值）。
- 把偏差回写进 spec 对应小节（第三节 5.2 事件表、第七节配置段的"必须实测"那段、8.3 验收清单逐项打勾）。
- 清理测试数据：`DELETE FROM ai_message WHERE conversation_id=$CID; DELETE FROM ai_conversation WHERE id=$CID;`（先向用户确认再删）。

```bash
git add docs/superpowers/plans/2026-10-23-ai-chat-e2e-log.md \
        docs/superpowers/specs/2026-10-23-ai-chat-conversation-design.md
git commit -m "记录对话子系统走网关的端到端实测结果并回写设计偏差"
```

---

## 分段与批准点

用户要求"一个 spec 分段执行、每段停下待批准"。按段推进，段内任务连续做，段末必须停下汇报：

| 段 | 任务 | 段末交付 |
|---|---|---|
| A 基础 | Task 1–4 | 配置生效、两张表建好、真库读写有测试、游标可用 |
| B 控制面 | Task 5–7 | #1–#6 六个接口可走网关 curl 打通，上下文组装有测试 |
| C 流式 | Task 8–11 | #7/#8 完成，单测覆盖事件序列与写次数不变式 |
| D 验收 | Task 12 | 端到端实测记录 + spec 回写 |

关卡分布：G1=Task 2 Step 2、G2=Task 3 Step 1、G5=Task 1 Step 9、G3=Task 12 Step 2、G4=Task 12 Step 10（条件触发）。**每个关卡都必须等用户明确同意，不能因为"前面同意过别的关卡"就顺带执行。**

