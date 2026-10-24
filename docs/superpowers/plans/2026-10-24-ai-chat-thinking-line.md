# E 段：后端思考链（reasoning 实时展示）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **本仓库的例外**：执行者**绝不运行 `git add` / `git commit`** —— 用户自己提交（见 Global Constraints 第 1 条）。计划里每个任务以"检查点"收尾，不是提交。

**Goal:** 让 `deepseek-reasoner` 的推理过程以按句切分的 `thought_step` 帧实时流到前端，并在换掉上游读取方式之后保持既有 8 个接口的全部行为不变。

**Architecture:** 用自建的 `OpenAiStreamChatModelGateway`（`WebClient` + spring-webflux 的 SSE 解码器）替换 `SpringAiChatModelGateway`，因为只有自建才能看见 Spring AI 在 JSON 绑定时就丢掉的 `delta.reasoning_content`。上游只交出"原料" `ChatChunk`，切句与 flush 时机全在编排层，由一个纯函数状态机 `ReasoningSentenceSplitter` 完成。reasoning **不落库**，所以两张表、三段短事务、写次数不变式一行都不动。

**Tech Stack:** Java 21、Spring Boot 3.2.4（servlet 栈 + `SseEmitter`）、spring-webflux 6.1.5 的 `WebClient`/`ServerSentEvent`（已在 classpath，零新增依赖）、reactor-core 3.6.4、Jackson、JUnit 5 + Mockito、JDK `com.sun.net.httpserver.HttpServer`（测试内的假上游，零依赖）。

**Spec:** `docs/superpowers/specs/2026-10-24-ai-chat-thinking-line-design.md`（§4 是本计划的范围；§5 前端是 F 段，不在这里）
**上一期的对应文档：** spec `docs/superpowers/specs/2026-10-23-ai-chat-conversation-design.md`、e2e 取证 `docs/superpowers/plans/2026-10-23-ai-chat-e2e-log.md`

## Global Constraints

1. **不提交。** 执行者不运行 `git add`、`git commit`、`git push`。工作树留给用户。
2. **构建命令**：`mvn` 不在 PATH。用
   `export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"`
   `MVN="/c/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"`
   一律 `"$MVN" -pl AIModule clean test`（**必须带 `clean`**，ENV-1 的陈旧字节码踩过两次；**不要 `-am`**，裁定 D2；**不要 `-o`**）。
   IT：`"$MVN" -pl AIModule test -Dtest=ChatMapperDbIT -Dgroups=db-it -Dsurefire.excludedGroups=`。
3. **零新增 Maven 依赖、零 DDL、零表结构改动。** 不许碰 `AiMessage`/`AiConversation`/两个 mapper/`ChatTurnRepository` 的写方法。
4. **能力上报单一口径**：`supportsThinking` 一律经 `ChatProperties.thinkingSupported(...)` 计算，`#1` 与 `meta` 不允许各算各的（上一期留过"报 true 却永远发不出内容"的空头旗子）。
5. **测试必须用真实录制帧**，不许凭想象造上游形状。本轮录到的事实：`data: ` **带空格**；63 帧只有 `reasoning_content`、26 帧只有 `content`、0 帧两者都有；尾帧 `content:"" + finish_reason:"length" + usage`；`usage.completion_tokens_details.reasoning_tokens` 存在；上游还发我们没建模的字段（`system_fingerprint`、`prompt_cache_hit_tokens`）。
6. **变异取证**：每条新守护都要证明它能让对应用例变红，恢复后 `grep -rn "MUTATION" AIModule/src/` 必须为 0。
7. **Windows/编码**：非 ASCII 请求体一律 `--data-binary @<utf-8 文件>`；`python` 读写要 `newline=''`，否则 CRLF 会造出假的不一致。
8. **服务与关卡**：8105 由用户启动/重启；不碰 Nacos 配置；真跑 reasoner 会计费（一次短问答约 63 个 reasoning tokens），取证够用即止。

## 文件结构

| 动作 | 路径 | 责任 |
|---|---|---|
| Modify | `AIModule/src/main/java/org/leon/aimodule/chat/config/ChatProperties.java` | 四个新配置项 + `thinkingSupported()` 单一口径 |
| Modify | `AIModule/src/main/java/org/leon/aimodule/chat/service/ConversationService.java:40-46` | `#1` 按单一口径上报 |
| Modify | `AIModule/src/main/java/org/leon/aimodule/chat/service/EffortResolver.java` | `ModelParams.supportsThinking` 走同一口径 |
| Modify | `AIModule/src/main/resources/application.yaml` | 加 `chat.thinking-*`/`upstream-idle-timeout-seconds`；删 `spring.ai.openai.chat.options.temperature` 与 `stream-usage`（**`model` 保留**，遗留 `GET /ai/chat` 依赖它） |
| Create | `AIModule/src/main/java/org/leon/aimodule/chat/stream/ReasoningSentenceSplitter.java` | 纯函数：token 流 → 短句 |
| Modify | `AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatModelGateway.java` | `ChatChunk` 加 `reasoning`、`reasoningTokens` |
| Modify | `AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatEvent.java` | 加 `ThoughtStep`/`ThoughtEnd`，`TokenUsage` 加第三字段 |
| Modify | `AIModule/src/main/java/org/leon/aimodule/chat/stream/SseChannel.java` | 穷尽 switch 加两分支 |
| Create | `AIModule/src/main/java/org/leon/aimodule/chat/stream/UpstreamChunk.java` | 上游帧的 Jackson DTO（ignoreUnknown） |
| Create | `AIModule/src/main/java/org/leon/aimodule/chat/stream/UpstreamStatusException.java` | 带上游 status 的错误，供编排层判 retryable |
| Create | `AIModule/src/main/java/org/leon/aimodule/chat/stream/OpenAiStreamChatModelGateway.java` | 唯一的上游实现 |
| Create | `AIModule/src/main/java/org/leon/aimodule/chat/stream/ThinkingTracker.java` | splitter + 思考起点 + open 标志 |
| Modify | `AIModule/src/main/java/org/leon/aimodule/chat/stream/ActiveStream.java` | 挂 `ThinkingTracker` |
| Modify | `AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatStreamService.java` | `onChunk` 三路、flush 四时机、retryable 判定 |
| Delete | `AIModule/src/main/java/org/leon/aimodule/chat/stream/SpringAiChatModelGateway.java` | Task 19 |
| Delete | `AIModule/src/test/java/org/leon/aimodule/chat/stream/SpringAiChatModelGatewayTest.java` | Task 19（用例先迁到新测试） |
| Tests | `.../chat/config/ChatPropertiesThinkingTest.java`、`.../chat/stream/ReasoningSentenceSplitterTest.java`、`.../chat/stream/UpstreamChunkParseTest.java`、`.../chat/stream/OpenAiStreamChatModelGatewayTest.java` | 各任务的 RED→GREEN 载体 |

---

## Task 13: 配置项与能力上报单一口径

**Files:**
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/config/ChatProperties.java`（在 `staleStreamReapMinutes` 之后加字段）
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/service/ConversationService.java:44`
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/service/EffortResolver.java`（最后一行 `new ModelParams(...)`）
- Modify: `AIModule/src/main/resources/application.yaml`（`chat:` 段与 `spring.ai.openai.chat.options`）
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/config/ChatPropertiesThinkingTest.java`（新建）

**Interfaces:**
- Consumes: 无（本任务是地基）
- Produces: `boolean ChatProperties.isThinkingEnabled()`、`int ChatProperties.getThinkingMaxStepChars()`、`int ChatProperties.getThinkingMaxSteps()`、`int ChatProperties.getUpstreamIdleTimeoutSeconds()`、`boolean ChatProperties.thinkingSupported(boolean modelFlag)`。Task 14 用中间两个，Task 17 用第四个，Task 18 用 `thinkingSupported` 决定要不要喂 splitter。

- [x] **Step 1: 写失败测试**

```java
package org.leon.aimodule.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.EffortResolver;
import org.leon.aimodule.chat.service.ModelParams;

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
        p.setModels(new java.util.ArrayList<>(List.of(chat, reasoner)));
        p.setEfforts(new java.util.ArrayList<>(List.of(
                effort("Medium"), effort("Max"))));
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
```

- [x] **Step 2: 跑测试确认失败**

Run: `"$MVN" -pl AIModule clean test -Dtest=ChatPropertiesThinkingTest -Dsurefire.excludedGroups=`
Expected: 编译失败 —— `isThinkingEnabled()` / `thinkingSupported(boolean)` 不存在。

- [x] **Step 3: 加配置字段与单一口径**

`ChatProperties.java`，在 `private int staleStreamReapMinutes = 5;` 之后追加（类上是 `@Data`，getter/setter 由 lombok 生成）：

```java
    private boolean thinkingEnabled = true;
    private int thinkingMaxStepChars = 120;
    private int thinkingMaxSteps = 12;
    private int upstreamIdleTimeoutSeconds = 60;

    /**
     * 能力上报的唯一口径。上一期留过"报 true 却永远发不出内容"的空头旗子，
     * 所以 #1、meta、以及要不要喂 splitter 都必须问这里，不许各算各的。
     */
    public boolean thinkingSupported(boolean modelFlag) {
        return thinkingEnabled && modelFlag;
    }
```

- [x] **Step 4: 两处上报改走口径**

`ConversationService.java:44`：

```java
                        .map(m -> new ModelVo(m.getKey(), m.getName(), m.getTag(),
                                props.thinkingSupported(m.isSupportsThinking())))
```

`EffortResolver.java` 最后一行：

```java
        return new ModelParams(model.getKey(), level.getTemperature(), level.getMaxTokens(),
                props.thinkingSupported(model.isSupportsThinking()));
```

- [x] **Step 5: yaml 增删**

`chat:` 段 `stale-stream-reap-minutes: 5` 之后加：

```yaml
  thinking-enabled: true
  thinking-max-step-chars: 120
  thinking-max-steps: 12
  upstream-idle-timeout-seconds: 60
```

`spring.ai.openai.chat.options` 下**删掉两行**（`temperature: 0.7`、`stream-usage: true`），**保留 `model: deepseek-chat`** 并在它上面加一行注释说明为什么不能删：

```yaml
      chat:
        options:
          # 遗留演示接口 GET /ai/chat（AiChatController）用 ChatClient 且不显式指定 model，
          # 这行是它唯一的模型来源 —— 删了会静默弄坏一个不相干的接口。
          # temperature / stream-usage 已删除：chat 子系统不再走 ChatClient。
          model: deepseek-chat
```

- [x] **Step 6: 跑测试确认通过**

Run: `"$MVN" -pl AIModule clean test -Dtest='ChatPropertiesThinkingTest,ConversationServiceTest,EffortResolverTest' -Dsurefire.excludedGroups=`
Expected: 全绿。若 `ConversationServiceTest` 里 `#1` 的断言依赖 `supportsThinking` 原值，它会继续绿（默认开关为 true）—— 这正是"默认行为不变"的证据。

- [x] **Step 7: 变异取证**

把 `thinkingSupported` 改成 `return modelFlag;`（摘掉全局开关），跑本测试 → 期望 `thinkingSupportedIsAnd` 与 `resolverUsesTheSameGate` 两条红。恢复，确认 `grep -rn MUTATION AIModule/src/` 为 0。

- [x] **检查点**：全模块 `clean test` 绿；把结果行数报给用户（不提交）。

---

## Task 14: `ReasoningSentenceSplitter`（纯函数切句）

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/ReasoningSentenceSplitter.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/stream/ReasoningSentenceSplitterTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `new ReasoningSentenceSplitter(int maxStepChars, int maxSteps)`、`List<String> feed(String piece)`、`List<String> flush()`。Task 18 的 `ThinkingTracker` 是唯一调用方。

- [x] **Step 1: 写失败测试（表驱动，覆盖 spec §4.6 全部十条 + 执行期补的第 11 条跨帧用例）**

```java
package org.leon.aimodule.chat.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningSentenceSplitterTest {

    private static ReasoningSentenceSplitter s() {
        return new ReasoningSentenceSplitter(120, 12);
    }

    private static String feedAll(ReasoningSentenceSplitter sp, String... pieces) {
        StringBuilder out = new StringBuilder();
        for (String p : pieces) {
            for (String step : sp.feed(p)) {
                out.append("[").append(step).append("]");
            }
        }
        for (String step : sp.flush()) {
            out.append("[").append(step).append("]");
        }
        return out.toString();
    }

    @Test
    @DisplayName("逐 token 喂英文整句：一句一步，边界标点留在句内")
    void englishSentences() {
        assertEquals("[The user asks 17*23.][I should compute it.]",
                feedAll(s(), "The", " user", " asks", " 17*23", ".", " I", " should", " compute", " it", "."));
    }

    @Test
    @DisplayName("边界被劈在两个分片中间也要等齐再切")
    void boundarySplitAcrossPieces() {
        assertEquals("[together now.]", feedAll(s(), "toge", "ther now", "."));
    }

    @Test
    @DisplayName("小数点不切：1.5 属于同一句（lookahead 规则）")
    void doesNotSplitOnDecimal() {
        assertEquals("[The value is 1.5 meters.][Shown here.]",
                feedAll(s(), "The value is 1.5 meters", ". ", "Shown here", ". "));
    }

    @Test
    @DisplayName("小数点跨帧到达：句末点号落在分片末尾时必须等下一帧再判")
    void trailingPeriodWaitsForNextPiece() {
        // 上游按 token 吐，"…1" / "." / "5 meters" 三帧把一个小数劈开是真实形状。
        // 若分片末尾的 '.' 被当成已确认的边界，这里就会切出 "The value is 1." 这种半句。
        // 摘掉"末尾点号要等下一个字符"这条规则时，只有这条用例会红 ——
        // 其余用例都把点号放在分片中间，看不出时机差异。
        assertEquals("[The value is 1.5 meters.]",
                feedAll(s(), "The value is 1", ".", "5 meters", ". "));
    }

    @Test
    @DisplayName("已知取舍：e.g. 这类缩写会在后随空格处提前断句 —— 接受，不要当 bug 修")
    void abbreviationSplitsAreAccepted() {
        // "See e.g. below." 被切成两步。切句只服务展示层，多切一刀不影响任何语义；
        // 为它维护缩写表是 scope creep。这条用例钉住"当前行为"，
        // 将来有人加缩写表时会在这里被提醒，而不是悄悄改掉语义。
        assertEquals("[See e.g.][below.]", feedAll(s(), "See e.g. below", ". "));
    }

    @Test
    @DisplayName("中文句号自终结，不需要后视")
    void cjkBoundaryIsSelfTerminating() {
        assertEquals("[先算乘法。][再核对。]", feedAll(s(), "先算乘法。", "再核对", "。"));
    }

    @Test
    @DisplayName("换行是边界，且清洗后不留空白步骤")
    void newlineIsBoundary() {
        assertEquals("[line one][line two]", feedAll(s(), "line one\nline two\n"));
    }

    @Test
    @DisplayName("无标点的长文触发强切：在空格处断，不硬砍单词，并标出被切过")
    void forceCutAtCap() {
        ReasoningSentenceSplitter sp = new ReasoningSentenceSplitter(20, 12);
        assertEquals("[aaaa bbbb cccc dddd…][eeee ffff]",
                feedAll(sp, "aaaa bbbb cccc dddd eeee ffff"));
    }

    @Test
    @DisplayName("纯空白与纯标点碎片不产出步骤")
    void blankFragmentsProduceNothing() {
        ReasoningSentenceSplitter sp = s();
        assertEquals(List.of(), sp.feed("   "));
        assertEquals(List.of(), sp.feed("\n"));   // 切出来是空串，被丢弃
        assertEquals(List.of(), sp.flush());
    }

    @Test
    @DisplayName("超步骤上限后不再产出，但 flush 不报错")
    void saturatesAtMaxSteps() {
        ReasoningSentenceSplitter sp = new ReasoningSentenceSplitter(120, 2);
        // 每个分片以空格收尾：单独喂 "a." 时末尾的 '.' 会因 lookahead 而"等下一个字符"，
        // 那样测的是后视规则而不是上限规则。
        assertEquals("[a.][b.]", feedAll(sp, "a. ", "b. ", "c. ", "d. "));
    }

    @Test
    @DisplayName("flush 连调两次不重复吐出（进入正文相与 onComplete 都会调）")
    void flushIsIdempotent() {
        ReasoningSentenceSplitter sp = s();
        sp.feed("tail without boundary");
        assertEquals(List.of("tail without boundary"), sp.flush());
        assertEquals(List.of(), sp.flush());
    }

    @Test
    @DisplayName("超长单句截断到上限并加省略号")
    void truncatesOverlongSentence() {
        ReasoningSentenceSplitter sp = new ReasoningSentenceSplitter(10, 12);
        List<String> out = sp.feed("abcdefghijklmno.");
        assertEquals(1, out.size());
        assertEquals(10, out.get(0).length());
        assertTrue(out.get(0).endsWith("…"));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `"$MVN" -pl AIModule clean test -Dtest=ReasoningSentenceSplitterTest -Dsurefire.excludedGroups=`
Expected: 编译失败 —— 类不存在。

- [x] **Step 3: 实现**

```java
package org.leon.aimodule.chat.stream;

import java.util.ArrayList;
import java.util.List;

/**
 * reasoning token 流 → 可读短句。上游是按 token 吐的（实测一个短问题的 92 帧里
 * 63 帧是 reasoning，每帧一两个词），直接贴到思考线上就是几十行碎片。
 *
 * 只有编排层的 onNext 串行路径碰它，所以不加锁 —— 别顺手加一把没用的锁。
 * 不计时：时间不是切句职责，见 ThinkingTracker。
 */
public final class ReasoningSentenceSplitter {

    private static final String CJK_BOUNDARY = "。！？\n\r";
    private static final String LATIN_BOUNDARY = ".!?";
    private static final String ELLIPSIS = "…";

    private final StringBuilder buf = new StringBuilder();
    private final int maxStepChars;
    private final int maxSteps;
    private int emitted;
    private boolean saturated;

    public ReasoningSentenceSplitter(int maxStepChars, int maxSteps) {
        this.maxStepChars = Math.max(8, maxStepChars);
        this.maxSteps = Math.max(1, maxSteps);
    }

    public List<String> feed(String piece) {
        List<String> out = new ArrayList<>();
        if (saturated || piece == null || piece.isEmpty()) {
            return out;
        }
        buf.append(piece);
        int cut;
        while (!saturated && (cut = findBoundary()) >= 0) {
            emit(out, buf.substring(0, cut));
            buf.delete(0, cut);
        }
        while (!saturated && buf.length() >= maxStepChars) {
            int force = forceCutIndex();
            // 强切是"我知道句子还没完"，所以补省略号；自然边界不补。
            emit(out, buf.substring(0, force) + ELLIPSIS);
            buf.delete(0, force);
        }
        return out;
    }

    public List<String> flush() {
        List<String> out = new ArrayList<>();
        if (saturated) {
            return out;
        }
        emit(out, buf.toString());
        buf.setLength(0);
        return out;
    }

    /** 返回"切点之后第一个位置"，-1 表示还没有完整边界。 */
    private int findBoundary() {
        for (int i = 0; i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (CJK_BOUNDARY.indexOf(c) >= 0) {
                return i + 1;
            }
            if (LATIN_BOUNDARY.indexOf(c) >= 0) {
                // 必须看到后一个字符才敢切：否则 "1.5" 和 "e.g." 会被劈开。
                if (i + 1 == buf.length()) {
                    return -1;
                }
                char next = buf.charAt(i + 1);
                if (Character.isWhitespace(next) || CJK_BOUNDARY.indexOf(next) >= 0
                        || LATIN_BOUNDARY.indexOf(next) >= 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private int forceCutIndex() {
        int lastSpace = -1;
        for (int i = 0; i < maxStepChars && i < buf.length(); i++) {
            if (buf.charAt(i) == ' ') {
                lastSpace = i;
            }
        }
        return lastSpace > 0 ? lastSpace : maxStepChars;
    }

    private void emit(List<String> out, String raw) {
        if (saturated) {
            return;
        }
        String step = clean(raw);
        if (step.isEmpty()) {
            return;
        }
        out.add(step);
        if (++emitted >= maxSteps) {
            saturated = true;
            buf.setLength(0);
        }
    }

    private String clean(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        if (s.length() > maxStepChars) {
            s = s.substring(0, maxStepChars - 1) + ELLIPSIS;
        }
        return s;
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `"$MVN" -pl AIModule clean test -Dtest=ReasoningSentenceSplitterTest -Dsurefire.excludedGroups=`
Expected: 12/12 绿。

- [x] **Step 5: 变异取证（三条，逐条做）**

1. lookahead 规则里有**两个独立守护**，本计划原先把它们当成一个，写错了预测：
   - **1a｜句末点号在缓冲区末尾时要等下一帧**（`if (i + 1 == buf.length()) return -1;` 改成 `return i + 1;`）。
     实测**变异存活** —— 11 条用例全绿。原因：`feedAll` 把所有步骤拼成一个串，而 1a 只是把某一步的产出时机从"本次 feed 末尾"挪到"下次 feed 开头"，聚合串不变。
     这是一次真实的用例缺口，不是实现有问题 → **补第 12 条用例** `trailingPeriodWaitsForNextPiece`（`"The value is 1"` + `"."` + `"5 meters"` + `". "` 三帧劈开一个小数），补完后重做 1a：**恰好 1 条红**，失败信息 `expected: <[The value is 1.5 meters.]> but was: <[The value is 1.][5 meters.]>` 正是这条规则要防的危害。
   - **1b｜后随字符不是空白/标点就不算边界**（整个 LATIN 分支无条件 `return i + 1;`）。
     实测 **3 条红**：`doesNotSplitOnDecimal`、`abbreviationSplitsAreAccepted`、`trailingPeriodWaitsForNextPiece`。原计划写的"两条红"漏算了新用例。
2. 把 `flush()` 里的 `buf.setLength(0)` 去掉 → 实测**恰好 `flushIsIdempotent` 1 条红**。
3. 把 `saturated` 的置位去掉 → 实测**恰好 `saturatesAtMaxSteps` 1 条红**。
全部恢复后 `grep -rn MUTATION AIModule/src/` 为 0。

- [x] **检查点**：`"$MVN" -pl AIModule clean test` 全绿（此时新类还没人用，属正常）。

---

## Task 15: `ChatChunk` 与事件契约扩展

**Files:**
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatModelGateway.java`
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatEvent.java`
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/stream/SseChannel.java`（emit 的 switch）
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/stream/SpringAiChatModelGateway.java`（**只为编译通过**，Task 19 删）
- Modify: `AIModule/src/test/java/org/leon/aimodule/chat/stream/ChatStreamServiceTest.java`（两个 helper 的实参）
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/stream/SseChannelTest.java`（加两条）

**Interfaces:**
- Consumes: Task 13 无
- Produces:
  - `ChatModelGateway.ChatChunk(String text, String reasoning, String finishReason, Integer promptTokens, Integer completionTokens, Integer reasoningTokens)`
  - `ChatEvent.ThoughtStep(String text)` → 帧名 `thought_step`；`ChatEvent.ThoughtEnd(double seconds)` → 帧名 `thought_end`
  - `ChatEvent.TokenUsage(Integer promptTokens, Integer completionTokens, Integer reasoningTokens)`

- [x] **Step 1: 写失败测试**

`SseChannelTest.java` 追加。该类已有的收集设施是 `private final List<String> frames`，假 sink 每帧 `add("event:" + event + "\ndata:" + json + "\n\n")`，所以断言按下标取元素，**不要** `frames.toString()`（List 的 toString 会在元素间插 `, `，跨帧断言会假失败）：

```java
    @Test
    @DisplayName("thought_step / thought_end 的帧名是 snake_case，载荷单行")
    void thoughtFramesWireNames() {
        channel.emit(new ChatEvent.ThoughtStep("先算乘法"));
        channel.emit(new ChatEvent.ThoughtEnd(8.4));
        assertEquals("event:thought_step\ndata:{\"text\":\"先算乘法\"}\n\n", frames.get(0));
        assertEquals("event:thought_end\ndata:{\"seconds\":8.4}\n\n", frames.get(1));
    }

    @Test
    @DisplayName("usage 帧带 reasoningTokens，字段顺序即 record 声明顺序")
    void usageCarriesReasoningTokens() {
        channel.emit(new ChatEvent.TokenUsage(42, 90, 63));
        assertEquals("event:usage\ndata:{\"promptTokens\":42,\"completionTokens\":90,"
                + "\"reasoningTokens\":63}\n\n", frames.get(0));
    }
```

同文件已有的 `emitsAllEventKinds` 里 `new ChatEvent.TokenUsage(812, 1640)` 要补第三参（`null`），并在它后面加一行 `ThoughtStep`/`ThoughtEnd` 的 emit 让 `assertEquals(5, frames.size())` 变成 7 —— **改这条断言时想清楚它是守什么的**：它守的是"每类事件恰好一帧、顺序不乱"，加两类就得改两处，这是诚实的代价，不要为了少改而把新事件塞进别的用例。

- [x] **Step 2: 跑测试确认失败**

Run: `"$MVN" -pl AIModule clean test -Dtest=SseChannelTest -Dsurefire.excludedGroups=`
Expected: 编译失败 —— `ThoughtStep` / 三参 `TokenUsage` 不存在。

- [x] **Step 3: 扩 `ChatEvent`**

```java
    record Delta(String text) implements ChatEvent {
    }

    /** 一条可读推理短句。kind/seq 是故意不做的：只有 reasoning 一种生产者。 */
    record ThoughtStep(String text) implements ChatEvent {
    }

    /** seconds 单位是秒（小数）—— 前端 ThoughtLine 的 elapsed 就是这个单位。 */
    record ThoughtEnd(double seconds) implements ChatEvent {
    }

    /** reasoningTokens 是 completionTokens 的拆分，不是加数。 */
    record TokenUsage(Integer promptTokens, Integer completionTokens, Integer reasoningTokens) implements ChatEvent {
    }
```

- [x] **Step 4: `SseChannel.emit` 的 switch 加两支**

```java
            case ChatEvent.Meta e -> send("meta", e);
            case ChatEvent.Delta e -> send("delta", e);
            case ChatEvent.ThoughtStep e -> send("thought_step", e);
            case ChatEvent.ThoughtEnd e -> send("thought_end", e);
            case ChatEvent.TokenUsage e -> send("usage", e);
            case ChatEvent.Done e -> send("done", e);
            case ChatEvent.ErrorEvt e -> send("error", e);
```

- [x] **Step 5: 扩 `ChatChunk` 并补编译**

```java
    /** 上游一帧的归一化视图，六个字段都可能为 null。 */
    record ChatChunk(String text, String reasoning, String finishReason,
                     Integer promptTokens, Integer completionTokens, Integer reasoningTokens) {
    }
```

`SpringAiChatModelGateway.toChunk` 末尾的构造改成补两个 `null`，并把 R17 那段注释搬走时留一句指向新实现：

```java
        return new ChatChunk(text, null, finish, pt, ct, null);
```

`ChatStreamServiceTest` 的两个 helper：

```java
    private ChatModelGateway.ChatChunk text(String t) {
        return new ChatModelGateway.ChatChunk(t, null, null, null, null, null);
    }

    private ChatModelGateway.ChatChunk finish() {
        return new ChatModelGateway.ChatChunk(null, null, "stop", 812, 1640, null);
    }
```

同文件里 `lengthFinishReason` 与 `upstreamFailureKeepsPartialContent` 直接 new 的地方补成 6 参。

- [x] **Step 6: 跑全模块确认没漏改的调用点**

Run: `"$MVN" -pl AIModule clean test`
Expected: 全绿。若还有编译错误，就是漏改的 `ChatChunk`/`TokenUsage` 构造点，逐个补 —— **不要**为了少改而给 record 加便捷构造器（那是向后兼容垫片，会掩盖真实调用面）。

- [x] **检查点**：全模块绿；`grep -rn "new ChatEvent.TokenUsage(" AIModule/src | wc -l` 的数量与改前一致（没有新增构造点）。

---

## Task 16: 上游帧 → `ChatChunk` 的纯映射

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/UpstreamChunk.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/OpenAiStreamChatModelGateway.java`（本任务只放 `static` 解析部分）
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/stream/UpstreamChunkParseTest.java`

**Interfaces:**
- Consumes: Task 15 的 `ChatChunk` 六参形状
- Produces: `static ChatModelGateway.ChatChunk OpenAiStreamChatModelGateway.toChunk(String dataPayload)`（`[DONE]`/全空返回 null）；`UpstreamChunk` DTO。Task 17 在同一类里加实例方法。

- [x] **Step 1: 写失败测试（全部用本轮真实录到的帧）**

```java
package org.leon.aimodule.chat.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpstreamChunkParseTest {

    @Test
    @DisplayName("[DONE] 与空白载荷丢弃")
    void doneSentinel() {
        assertNull(OpenAiStreamChatModelGateway.toChunk("[DONE]"));
        assertNull(OpenAiStreamChatModelGateway.toChunk(""));
        assertNull(OpenAiStreamChatModelGateway.toChunk(null));
    }

    @Test
    @DisplayName("真实 reasoning 帧：只有 reasoning_content")
    void reasoningFrame() {
        String wire = "{\"id\":\"7220f3cf\",\"object\":\"chat.completion.chunk\",\"created\":1792813205,"
                + "\"model\":\"deepseek-flash\",\"system_fingerprint\":\"e72d3c7f\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null,"
                + "\"reasoning_content\":\"We\"},\"logprobs\":null,\"finish_reason\":null}]}";
        ChatModelGateway.ChatChunk c = OpenAiStreamChatModelGateway.toChunk(wire);
        assertEquals("We", c.reasoning());
        assertNull(c.text());
        assertNull(c.finishReason());
    }

    @Test
    @DisplayName("真实正文帧：只有 content")
    void contentFrame() {
        String wire = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"391\"},"
                + "\"finish_reason\":null}]}";
        ChatModelGateway.ChatChunk c = OpenAiStreamChatModelGateway.toChunk(wire);
        assertEquals("391", c.text());
        assertNull(c.reasoning());
    }

    @Test
    @DisplayName("真实尾帧：content 空串 + finish_reason + usage 明细，三者都要留住")
    void finishFrameCarriesUsage() {
        String wire = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\",\"reasoning_content\":null},"
                + "\"finish_reason\":\"length\"}],"
                + "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":90,\"total_tokens\":132,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":63},"
                + "\"prompt_cache_hit_tokens\":0,\"prompt_cache_miss_tokens\":42}}";
        ChatModelGateway.ChatChunk c = OpenAiStreamChatModelGateway.toChunk(wire);
        assertEquals("length", c.finishReason());
        assertEquals(42, c.promptTokens());
        assertEquals(90, c.completionTokens());
        assertEquals(63, c.reasoningTokens());
        assertNull(c.text(), "空串不能变成一帧空 delta");
    }

    @Test
    @DisplayName("未知字段不炸（上游已经在发我们没建模的字段）")
    void unknownFieldsIgnored() {
        String wire = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":null}],"
                + "\"brand_new_field\":{\"nested\":[1,2,3]},\"also_new\":\"v\"}";
        assertEquals("x", OpenAiStreamChatModelGateway.toChunk(wire).text());
    }

    @Test
    @DisplayName("finish_reason 大写也归一小写（R17 的教训留在适配器）")
    void finishReasonLowerCased() {
        String wire = "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"LENGTH\"}]}";
        assertEquals("length", OpenAiStreamChatModelGateway.toChunk(wire).finishReason());
    }

    @Test
    @DisplayName("role-only 首帧（content/reasoning 都是 null）整帧丢弃，不产生空 delta")
    void roleOnlyFrameDropped() {
        String wire = "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null,"
                + "\"reasoning_content\":null},\"finish_reason\":null}]}";
        assertNull(OpenAiStreamChatModelGateway.toChunk(wire));
    }

    @Test
    @DisplayName("usage 全 0 的兜底帧按\"这帧没有 usage\"处理（R11）")
    void zeroUsageDropped() {
        String wire = "{\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}";
        assertNull(OpenAiStreamChatModelGateway.toChunk(wire));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `"$MVN" -pl AIModule clean test -Dtest=UpstreamChunkParseTest -Dsurefire.excludedGroups=`
Expected: 编译失败 —— `UpstreamChunk` / `OpenAiStreamChatModelGateway` 不存在。

- [x] **Step 3: 写 DTO**

```java
package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 上游一帧的原始形状。ignoreUnknown 是硬要求：DeepSeek 已经在发
 * system_fingerprint / prompt_cache_hit_tokens 这类我们没建模的字段，
 * 严格绑定就是等着某天 500。
 *
 * 每个字段都写显式 @JsonProperty，不用 SNAKE_CASE 命名策略：实测
 * "命名策略 + record 隐式组件名" 的绑定只在特定属性顺序下生效 ——
 * 真实的 usage 尾帧（prompt_tokens / completion_tokens / completion_tokens_details
 * 三个属性）会把 completion_tokens_details 静默丢成 null，两个属性时却正常。
 * 这种"看运气"的绑定不能留在计费字段上。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record UpstreamChunk(@JsonProperty("choices") List<Choice> choices,
                     @JsonProperty("usage") Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(@JsonProperty("delta") Delta delta,
                  @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Delta(@JsonProperty("content") String content,
                 @JsonProperty("reasoning_content") String reasoningContent) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
                 @JsonProperty("completion_tokens") Integer completionTokens,
                 @JsonProperty("completion_tokens_details") CompletionTokenDetails completionTokenDetails) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompletionTokenDetails(@JsonProperty("reasoning_tokens") Integer reasoningTokens) {
    }
}
```

- [x] **Step 4: 写解析核心（静态部分）**

```java
package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

/**
 * DeepSeek 的 OpenAI 兼容端点。之所以自己读上游：Spring AI 1.0.0-M7 的
 * ChatCompletionMessage 没有 reasoning 字段，delta.reasoning_content 在 JSON
 * 绑定阶段就被丢掉（1.0.9 GA 同样没有，只有 1.1.x 有，而那要求整项目升框架）。
 *
 * 本任务只有静态解析部分，所以还没 implements ChatModelGateway —— Task 17 加 stream() 时一起接上。
 */
public class OpenAiStreamChatModelGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static ChatModelGateway.ChatChunk toChunk(String dataPayload) {
        if (dataPayload == null || dataPayload.isBlank() || "[DONE]".equals(dataPayload.trim())) {
            return null;
        }
        UpstreamChunk chunk;
        try {
            chunk = MAPPER.readValue(dataPayload, UpstreamChunk.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("上游帧不是合法 JSON: " + e.getMessage(), e);
        }
        String text = null;
        String reasoning = null;
        String finish = null;
        List<UpstreamChunk.Choice> choices = chunk.choices();
        if (choices != null && !choices.isEmpty()) {
            UpstreamChunk.Choice choice = choices.get(0);
            UpstreamChunk.Delta delta = choice.delta();
            if (delta != null) {
                text = nonBlank(delta.content());
                reasoning = nonBlank(delta.reasoningContent());
            }
            finish = lower(choice.finishReason());
        }
        Integer pt = null;
        Integer ct = null;
        Integer rt = null;
        UpstreamChunk.Usage u = chunk.usage();
        if (u != null) {
            pt = positive(u.promptTokens());
            ct = positive(u.completionTokens());
            rt = u.completionTokenDetails() == null ? null : positive(u.completionTokenDetails().reasoningTokens());
        }
        if (text == null && reasoning == null && finish == null && pt == null && ct == null && rt == null) {
            return null;
        }
        return new ChatModelGateway.ChatChunk(text, reasoning, finish, pt, ct, rt);
    }

    private static String nonBlank(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    private static String lower(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim().toLowerCase(java.util.Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static Integer positive(Integer v) {
        return v == null || v <= 0 ? null : v;
    }
}
```

- [x] **Step 5: 跑测试确认通过**

Run: `"$MVN" -pl AIModule clean test -Dtest=UpstreamChunkParseTest -Dsurefire.excludedGroups=`
Expected: 8/8 绿。

- [x] **Step 6: 变异取证**

1. 把 `positive()` 换成直接取值（回到 R11 前的写法）→ 只有 `zeroUsageDropped` 红。
2. 把 `lower()` 改成原样返回 → 只有 `finishReasonLowerCased` 红（这就是 R17 的哨兵）。
3. 把 `@JsonIgnoreProperties` 与 `FAIL_ON_UNKNOWN_PROPERTIES=false` 同时去掉 → 实测 **6 条 error**（原计划只预测 `unknownFieldsIgnored` 1 条红，低估了：真实帧满是未建模字段，严格绑定后连 `contentFrame` 都解析不出来）。
4. （执行期自补，配套上面的显式 `@JsonProperty` 修复）把 `@JsonProperty("completion_tokens_details")` 改成单数 → 恰好 `finishFrameCarriesUsage` 1 条红，失败信息与修复前一致。
5. （执行期自补）两道 ignoreUnknown 守护**拆开各摘一次** → 各自单独都 8/8 绿，即两道各就够用、同时保留是纵深防御；正因如此，删掉其中一道不会被测试发现，这条结论必须留在文档里。
恢复，`grep -rn MUTATION AIModule/src/` 为 0。

- [x] **检查点**：全模块绿。

---

## Task 17: `WebClient` 接线（请求、SSE 解码、空闲超时、错误映射）

**Files:**
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/stream/OpenAiStreamChatModelGateway.java`（加实例部分）
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/UpstreamStatusException.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/chat/stream/OpenAiStreamChatModelGatewayTest.java`（用 JDK `HttpServer` 当假上游，零新增依赖）

**Interfaces:**
- Consumes: Task 13 的 `getUpstreamIdleTimeoutSeconds()`；Task 16 的 `toChunk(String)`
- Produces:
  - `OpenAiStreamChatModelGateway(ChatProperties props, ObjectMapper mapper)` 上的 `Flux<ChatChunk> stream(List<CtxMessage>, ModelParams)`（`@Component`，取代旧实现成为唯一 `ChatModelGateway` bean —— 旧 bean 在 Task 19 才删，所以**本任务先给旧实现加 `@ConditionalOnMissingBean` 式让位是不允许的**：改为在测试里手工构造本类，Spring 装配到 Task 19 一次切换）
  - `UpstreamStatusException(int status, String message)`，`public int status()`

- [ ] **Step 1: 写失败测试**

```java
package org.leon.aimodule.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.config.ChatProperties;
import org.leon.aimodule.chat.service.CtxMessage;
import org.leon.aimodule.chat.service.ModelParams;
import reactor.core.publisher.Flux;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 假上游用 JDK 自带的 HttpServer：不引 MockWebServer，也不打真接口花钱。
 * 帧内容是本机实测录下的 DeepSeek 响应（含 data: 后那个空格与 system_fingerprint）。
 */
class OpenAiStreamChatModelGatewayTest {

    private HttpServer server;
    private ChatProperties props;
    private OpenAiStreamChatModelGateway gateway;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        props = new ChatProperties();
        props.setUpstreamIdleTimeoutSeconds(2);
        props.setApiKey("sk-test-for-fake-upstream");
        gateway = new OpenAiStreamChatModelGateway(props, new ObjectMapper());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 把 body 拆成不定长的小块写出去，专门用来验证半帧拼接。 */
    private void serve(String body, int status, int chunkSize) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] all = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(status, status == 200 ? 0 : all.length);
            if (status != 200) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(all);
                }
                return;
            }
            try (OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < all.length; i += chunkSize) {
                    os.write(all, i, Math.min(chunkSize, all.length - i));
                    os.flush();
                }
            }
        });
        server.start();
        gateway.setBaseUrlForTest("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private List<ChatModelGateway.ChatChunk> collect() {
        return gateway.stream(List.of(new CtxMessage("user", "hi")),
                new ModelParams("deepseek-reasoner", 1.0, 4096, true)).collectList().block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("请求体与头：stream、stream_options.include_usage、Bearer 都在")
    void requestShape() throws Exception {
        serve("data: [DONE]\n\n", 200, 64);
        collect();
        assertTrue(lastBody.get().contains("\"stream\":true"), lastBody.get());
        assertTrue(lastBody.get().contains("\"stream_options\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"include_usage\":true"), lastBody.get());
        assertTrue(lastBody.get().contains("\"model\":\"deepseek-reasoner\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"max_tokens\":4096"), lastBody.get());
        assertEquals("Bearer " + props.getApiKey(), lastAuth.get());
    }

    @Test
    @DisplayName("真实录制的帧序列：63 reasoning + 26 content + 尾帧，半帧拼接也不丢")
    void streamsRecordedFramesWithTornChunks() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 63; i++) {
            body.append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":null,")
                    .append("\"reasoning_content\":\"r").append(i).append("\"},\"finish_reason\":null}]}\n\n");
        }
        for (int i = 0; i < 26; i++) {
            body.append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"c").append(i)
                    .append("\"},\"finish_reason\":null}]}\n\n");
        }
        body.append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},")
                .append("\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":90,")
                .append("\"completion_tokens_details\":{\"reasoning_tokens\":63}}}\n\n");
        body.append("data: [DONE]\n\n");
        serve(body.toString(), 200, 7);   // 7 字节一片，必然把帧劈开

        List<ChatModelGateway.ChatChunk> chunks = collect();
        assertEquals(90, chunks.size(), "63 reasoning + 26 content + 1 尾帧");
        assertEquals("r0", chunks.get(0).reasoning());
        assertEquals("c0", chunks.get(63).text());
        ChatModelGateway.ChatChunk last = chunks.get(89);
        assertEquals("stop", last.finishReason());
        assertEquals(63, last.reasoningTokens());
    }

    @Test
    @DisplayName("上游 401 → UpstreamStatusException(401)，供编排层判不 retryable")
    void unauthorizedMapsToStatusException() throws Exception {
        serve("{\"error\":{\"message\":\"Authenticated Failed\"}}", 401, 64);
        UpstreamStatusException use = cause(UpstreamStatusException.class);
        assertEquals(401, use.status());
        assertTrue(use.getMessage().contains("Authenticated"), use.getMessage());
    }

    @Test
    @DisplayName("上游 503 → 同一个异常类型，status 带着走")
    void serverErrorKeepsStatus() throws Exception {
        serve("{\"error\":{\"message\":\"Too Many Requests\"}}", 503, 64);
        assertEquals(503, cause(UpstreamStatusException.class).status());
    }

    @Test
    @DisplayName("上游只出一帧后卡住 → 空闲超时抛 TimeoutException")
    void idleTimeoutFires() {
        props.setUpstreamIdleTimeoutSeconds(1);
        streamStalledUpstream();
        assertInstanceOf(TimeoutException.class, cause(TimeoutException.class));
    }

    /**
     * block() 会把 checked 异常包进 ReactiveException，所以断言要顺着 cause 链找，
     * 不能 assertThrows(TimeoutException.class, ...) —— 那会红得让人误判成实现错了。
     */
    private <T> T cause(Class<T> type) {
        try {
            collect();
            throw new AssertionError("期望抛错，但流正常结束");
        } catch (Throwable t) {
            for (Throwable cur = t; cur != null; cur = cur.getCause()) {
                if (type.isInstance(cur)) {
                    return type.cast(cur);
                }
            }
            throw new AssertionError("异常链里没有 " + type.getSimpleName() + "：" + t, t);
        }
    }

    private void streamStalledUpstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", ex -> {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}]}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    Thread.sleep(5000);
                } catch (Exception ignored) {
                }
            });
            server.start();
            gateway.setBaseUrlForTest("http://127.0.0.1:" + server.getAddress().getPort());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `"$MVN" -pl AIModule clean test -Dtest=OpenAiStreamChatModelGatewayTest -Dsurefire.excludedGroups=`
Expected: 编译失败 —— `setBaseUrlForTest` / `getApiKey` / `UpstreamStatusException` 不存在。

- [ ] **Step 3: 加 `chat.api-key` 与 `chat.base-url` 到配置**

`ChatProperties.java` 追加（值由 yaml 从 `spring.ai.openai.*` 引过来，保持"key 只有一个真相源"）：

```java
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
```

`application.yaml` 的 `chat:` 段追加：

```yaml
  # 值直接引用 spring.ai.openai.*：API key 的唯一真相源仍是 config/local.properties
  # 里的 EZ_DEEPSEEK_API_KEY。不新增第二个键，免得两处能配得不一样。
  api-key: ${spring.ai.openai.api-key:}
  base-url: ${spring.ai.openai.base-url:https://api.deepseek.com}
```

- [ ] **Step 4: 写 `UpstreamStatusException`**

```java
package org.leon.aimodule.chat.stream;

/** 上游返回非 2xx。status 给编排层判 retryable，不在网关里猜。 */
public class UpstreamStatusException extends RuntimeException {

    private final int status;

    public UpstreamStatusException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
```

- [ ] **Step 5: 写实例部分**

`OpenAiStreamChatModelGateway` 追加（构造 + 请求 + 解码 + 超时 + 错误映射）：

```java
    private final ChatProperties props;
    private final WebClient webClient;

    public OpenAiStreamChatModelGateway(ChatProperties props, ObjectMapper mapper) {
        this.props = props;
        this.webClient = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .codec(c -> c.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();
    }

    /** 测试把请求打到本机假上游；生产不调。 */
    void setBaseUrlForTest(String url) {
        this.testBaseUrl = url;
    }

    private volatile String testBaseUrl;

    @Override
    public Flux<ChatChunk> stream(List<CtxMessage> messages, ModelParams params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", params.modelKey());
        body.put("messages", messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content() == null ? "" : m.content()))
                .toList());
        body.put("stream", true);
        // 不发这一行就没有 usage 帧 —— 上一期是靠 spring.ai.openai...stream-usage 让库代发。
        body.put("stream_options", Map.of("include_usage", true));
        if (params.maxTokens() != null) {
            body.put("max_tokens", params.maxTokens());
        }
        if (params.temperature() != null) {
            body.put("temperature", params.temperature());
        }
        if (!props.getSystemPrompt().isBlank()) {
            body.put("messages", prependSystem(body.get("messages"), props.getSystemPrompt()));
        }
        String base = testBaseUrl == null ? props.getBaseUrl() : testBaseUrl;
        return webClient.mutate().baseUrl(base).build()
                .post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToFlux(resp -> {
                    if (resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                        });
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMapMany(text -> Flux.error(
                                    new UpstreamStatusException(resp.statusCode().value(),
                                            resp.statusCode().value() + " from POST /v1/chat/completions: "
                                                    + trim(text))));
                })
                // 心跳只保活"我们→前端"，保不了"上游→我们"这一跳，所以这里必须有空闲判定。
                .timeout(Duration.ofSeconds(props.getUpstreamIdleTimeoutSeconds()))
                .mapNotNull(sse -> toChunk(sse.data()));
    }

    private static String trim(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> prependSystem(Object messages, String system) {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        out.add(Map.of("role", "system", "content", system));
        out.addAll((List<Map<String, String>>) messages);
        return out;
    }
```

需要 `import`：`WebClient`、`ServerSentEvent`、`ParameterizedTypeReference`、`HttpHeaders`、`MediaType`、`Duration`、`LinkedHashMap`、`Map`、`List`。

- [ ] **Step 6: 跑测试确认通过**

Run: `"$MVN" -pl AIModule clean test -Dtest='OpenAiStreamChatModelGatewayTest,UpstreamChunkParseTest' -Dsurefire.excludedGroups=`
Expected: 13/13 绿（本类 5 条 + Task 16 的 8 条）。

- [ ] **Step 7: 变异取证**

1. 把 `.timeout(...)` 去掉 → 只有 `idleTimeoutFires` 红（它会挂到 block 的 10s 上限，注意别把它误判成"环境慢"）。
2. 把 `exchangeToFlux` 里的非 2xx 分支改成 `retrieve()`（丢 status）→ `unauthorizedMapsToStatusException` 红。
3. 把 `chunkSize` 从 7 改成整块写（改测试）→ `streamsRecordedFramesWithTornChunks` 仍绿，说明它没在测半帧拼接 —— **这条要如实记进报告**：半帧拼接的实际守护者是 spring-webflux 的解码器，不是我们的代码，所以我们不为它写"看起来在测"的用例。

- [ ] **检查点**：全模块绿。

---

## Task 18: 编排层接线（`ThinkingTracker`、三路 `onChunk`、flush 四时机、retryable）

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/chat/stream/ThinkingTracker.java`
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/stream/ActiveStream.java`
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/stream/ChatStreamService.java`
- Modify: `AIModule/src/test/java/org/leon/aimodule/chat/web/TestTurns.java`
- Modify: `AIModule/src/test/java/org/leon/aimodule/chat/stream/ChatStreamServiceTest.java`、`ChatStreamWriteCountTest.java`

**Interfaces:**
- Consumes: Task 13（`thinkingSupported`、上限）、Task 14（splitter）、Task 15（事件）、Task 17（`UpstreamStatusException`）
- Produces:
  - `ThinkingTracker(boolean enabled, int maxStepChars, int maxSteps)`；`List<String> onReasoning(String)`；`boolean open()`；`List<String> closeAndDrain()`；`double elapsedSeconds()`
  - `ActiveStream` 多一个 record 分量 `ThinkingTracker thinking`

- [ ] **Step 0: 先补测试夹具（否则思考相的用例全是假绿）**

`ChatStreamServiceTest.setUp()` 现在只注册了 `deepseek-chat` 一个模型，而 `prepareNormal()` 走的也是它 —— `ModelParams.supportsThinking` 因此恒为 false，**任何喂 reasoning 的用例都会因为 tracker 关着而"正确地什么都不发"，看着绿其实什么都没测**。所以先加：

```java
    // setUp() 里 props.setModels(...) 那一段改成两个模型
    ChatProperties.Model reasoner = new ChatProperties.Model();
    reasoner.setKey("deepseek-reasoner");
    reasoner.setName("deepseek-reasoner");
    reasoner.setSupportsThinking(true);
    props.setModels(new ArrayList<>(List.of(m, reasoner)));
```

```java
    /** 走 reasoner 的一轮：ModelParams.supportsThinking 为 true，tracker 才真的开。 */
    private PreparedTurn prepareThinking() {
        return service.prepare(new StreamCommand(7L, "17 乘 23", "deepseek-reasoner",
                "Medium", "cm-think", false), "alice");
    }

    private ChatModelGateway.ChatChunk reasoning(String t) {
        return new ChatModelGateway.ChatChunk(null, t, null, null, null, null);
    }
```

需要 `import static org.junit.jupiter.api.Assertions.assertFalse;`（该类目前只 import 了 `assertEquals`/`assertThrows`/`assertTrue`）。

- [ ] **Step 1: 写失败测试**

`ChatStreamServiceTest.java` 追加，全部用 `prepareThinking()`（沿用该类已有的 `recorderChannel()` / `events` 设施）：

```java
    @Test
    @DisplayName("思考相帧序：meta → thought_step* → thought_end → delta* → usage → done")
    void thinkingPhaseOrder() {
        when(gateway.stream(any(), any())).thenReturn(Flux.just(
                reasoning("先算乘法。"), reasoning("再核对"),
                text("391"), finish()));
        service.subscribeAsync(prepareThinking(), recorderChannel());
        List<String> kinds = events.stream().map(e -> e.getClass().getSimpleName()).toList();
        assertEquals(List.of("Meta", "ThoughtStep", "ThoughtStep", "ThoughtEnd",
                "Delta", "TokenUsage", "Done"), kinds);
        assertEquals("先算乘法", ((ChatEvent.ThoughtStep) events.get(1)).text());
        assertEquals("再核对", ((ChatEvent.ThoughtStep) events.get(2)).text());
        assertTrue(((ChatEvent.ThoughtEnd) events.get(3)).seconds() >= 0.0);
    }

    @Test
    @DisplayName("纯推理无正文就结束，也要收到 thought_end")
    void reasoningOnlyStillCloses() {
        when(gateway.stream(any(), any())).thenReturn(Flux.just(reasoning("只想不答。"), finish()));
        service.subscribeAsync(prepareThinking(), recorderChannel());
        List<String> kinds = events.stream().map(e -> e.getClass().getSimpleName()).toList();
        assertEquals(List.of("Meta", "ThoughtStep", "ThoughtEnd", "TokenUsage", "Done"), kinds);
    }

    @Test
    @DisplayName("上游 error：flush 已攒步骤，但不发 thought_end")
    void errorFlushesStepsButNoThoughtEnd() {
        when(gateway.stream(any(), any())).thenReturn(Flux.concat(
                Flux.just(reasoning("想到一半")), Flux.error(new IllegalStateException("reset"))));
        service.subscribeAsync(prepareThinking(), recorderChannel());
        List<String> kinds = events.stream().map(e -> e.getClass().getSimpleName()).toList();
        assertEquals(List.of("Meta", "ThoughtStep", "ErrorEvt", "Done"), kinds);
    }

    @Test
    @DisplayName("不支持思考的模型一帧 thought 都不发（宁缺毋滥）")
    void noThoughtWhenModelCannotThink() {
        when(gateway.stream(any(), any())).thenReturn(Flux.just(
                reasoning("不该出现的推理"), text("答"), finish()));
        service.subscribeAsync(prepareNormal(), recorderChannel());   // deepseek-chat
        assertTrue(events.stream().noneMatch(e -> e instanceof ChatEvent.ThoughtStep), events.toString());
        assertTrue(events.stream().noneMatch(e -> e instanceof ChatEvent.ThoughtEnd), events.toString());
    }

    @Test
    @DisplayName("上游 401 → error{retryable:false}；503 → retryable:true")
    void retryableFollowsStatus() {
        when(gateway.stream(any(), any())).thenReturn(
                Flux.error(new UpstreamStatusException(401, "401 Unauthorized")));
        service.subscribeAsync(prepareNormal(), recorderChannel());
        ChatEvent.ErrorEvt e = (ChatEvent.ErrorEvt) events.stream()
                .filter(x -> x instanceof ChatEvent.ErrorEvt).findFirst().orElseThrow();
        assertFalse(e.retryable());

        events.clear();
        when(gateway.stream(any(), any())).thenReturn(
                Flux.error(new UpstreamStatusException(503, "503 Service Unavailable")));
        service.subscribeAsync(prepareNormal(), recorderChannel());
        ChatEvent.ErrorEvt e2 = (ChatEvent.ErrorEvt) events.stream()
                .filter(x -> x instanceof ChatEvent.ErrorEvt).findFirst().orElseThrow();
        assertTrue(e2.retryable());
    }

    @Test
    @DisplayName("思考相被 stop：不补发 thought_end，落 truncated/stopped 且正文为空")
    void stopDuringThinking() {
        when(gateway.stream(any(), any())).thenReturn(
                Flux.concat(Flux.just(reasoning("想到一半。")), Flux.never()));
        service.subscribeAsync(prepareThinking(), recorderChannel());
        service.stop(42L, "alice");
        List<String> kinds = events.stream().map(e -> e.getClass().getSimpleName()).toList();
        assertTrue(kinds.contains("ThoughtStep"), "stop 前已经发出去的步骤不能撤回");
        assertTrue(kinds.stream().noneMatch("ThoughtEnd"::equals), kinds.toString());
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(repo).finalizeAssistant(eq(42L), content.capture(), eq(AiMessage.ST_TRUNCATED),
                eq(AiMessage.STOP_STOPPED), any(), any());
        assertEquals("", content.getValue(), "推理不落库，所以这一条正文是空的");
    }
```

**"思考不落库"的哨兵放在 `ChatStreamWriteCountTest` 里，而不是新写一条** —— 那个类的价值全在 `interactions(int)` 末尾的 `verifyNoMoreInteractions(repo)`，把 reasoning 混进它现有的帧流，等于让既有那条"3 帧 vs 3000 帧"的断言顺便守住 reasoning。改 `interactions(int)` 里构造帧流的三行：

```java
    private static ChatModelGateway.ChatChunk chunk(String text) {
        return new ChatModelGateway.ChatChunk(text, null, null, null, null, null);
    }

    private static ChatModelGateway.ChatChunk reasoning(String text) {
        return new ChatModelGateway.ChatChunk(null, text, null, null, null, null);
    }
```

```java
        List<ChatModelGateway.ChatChunk> frames = new ArrayList<>(
                IntStream.rangeClosed(1, chunks).mapToObj(i -> i % 2 == 0
                        ? chunk("字") : reasoning("思考片段" + i + "。")).toList());
        frames.add(new ChatModelGateway.ChatChunk(null, null, "stop", 812, 1640, null));
```

`finalizeAssistant(42L, "字".repeat(chunks), ...)` 那条断言**不用改** —— 它现在同时证明"reasoning 没有漏进正文"。注意 `interactions()` 里 `prepare(...)` 用的还是 `deepseek-chat`（tracker 关着），所以要另加一条走 reasoner 的：把 `interactions(int)` 拆成 `interactions(int chunks, String modelKey)`，`writeCountDoesNotDependOnTokenCount` 里比对 `interactions(3, "deepseek-reasoner")` 与 `interactions(3000, "deepseek-reasoner")`，并保留原有那条 `deepseek-chat` 的比对。**否则哨兵测的是关着的状态。**

- [ ] **Step 2: 跑测试确认失败**

Run: `"$MVN" -pl AIModule clean test -Dtest='ChatStreamServiceTest,ChatStreamWriteCountTest' -Dsurefire.excludedGroups=`
Expected: 编译失败 —— `ThinkingTracker` 不存在 / `ActiveStream` 构造参数不匹配。

- [ ] **Step 3: 写 `ThinkingTracker`**

```java
package org.leon.aimodule.chat.stream;

import java.util.List;

/**
 * 思考相的状态：切句器 + 起点 + 是否还开着。
 * 时间在这里而不在 splitter 里 —— 切句是纯文本变换，可测性优先；
 * 计时是编排语义，只有编排层知道"进入正文相"这个时刻。
 * 只被 onNext 串行路径访问，不加锁。
 */
final class ThinkingTracker {

    private final ReasoningSentenceSplitter splitter;
    private final boolean enabled;
    private long startedAtNanos = -1L;
    private boolean open;

    ThinkingTracker(boolean enabled, int maxStepChars, int maxSteps) {
        this.enabled = enabled;
        this.splitter = new ReasoningSentenceSplitter(maxStepChars, maxSteps);
    }

    List<String> onReasoning(String piece) {
        if (!enabled) {
            // 全局关掉时上游的 reasoning 直接丢，一帧 thought 都不该出现。
            return List.of();
        }
        if (!open) {
            open = true;
            startedAtNanos = System.nanoTime();
        }
        return splitter.feed(piece);
    }

    boolean open() {
        return open;
    }

    List<String> closeAndDrain() {
        if (!open) {
            return List.of();
        }
        open = false;
        return splitter.flush();
    }

    double elapsedSeconds() {
        if (startedAtNanos < 0) {
            return 0.0;
        }
        return Math.round((System.nanoTime() - startedAtNanos) / 100_000_000.0) / 10.0;
    }
}
```

- [ ] **Step 4: `ActiveStream` 挂上它**

```java
public record ActiveStream(Long messageId, Long conversationId, String userName, SseChannel channel,
                           AtomicReference<reactor.core.Disposable> upstream, StringBuilder buffer,
                           AtomicReference<String> finishReason, AtomicReference<int[]> usage,
                           ThinkingTracker thinking) {
```

`usage` 的 `int[]` 从 2 槽扩到 3 槽（`{-1,-1,-1}` 是"没有 usage"），注释里写明第三槽是 `reasoningTokens` 且**它不是 `completionTokens` 的加数**。

`TestTurns.activeStream()` 补最后一个实参：`new ThinkingTracker(true, 120, 12)`。

`ChatStreamService.subscribeAsync(...)` 里构造 `ActiveStream` 的地方补一个实参，**开关取 `params.supportsThinking()`**（Task 13 已经把它收敛成"全局开关 ∧ 模型能力"，所以这里不再判第二次）：

```java
        ActiveStream stream = new ActiveStream(prepared.assistantMessageId(), prepared.conversationId(),
                prepared.userName(), channel, new AtomicReference<>(), new StringBuilder(),
                new AtomicReference<>(null), new AtomicReference<>(new int[]{-1, -1, -1}),
                new ThinkingTracker(prepared.params().supportsThinking(),
                        props.getThinkingMaxStepChars(), props.getThinkingMaxSteps()));
```

（以文件里实际的构造语句为准，实参个数与顺序按上面的形状补 `thinking` 与三槽 `usage`。）

- [ ] **Step 5: `onChunk` 三路**

```java
    private void onChunk(ActiveStream stream, ChatModelGateway.ChatChunk chunk) {
        if (stream.channel().isClosed()) {
            return;
        }
        if (chunk.reasoning() != null) {
            for (String step : stream.thinking().onReasoning(chunk.reasoning())) {
                emitOrDisconnect(stream, new ChatEvent.ThoughtStep(step));
            }
            return;
        }
        if (chunk.text() != null) {
            closeThinking(stream);
            stream.append(chunk.text());
            emitOrDisconnect(stream, new ChatEvent.Delta(chunk.text()));
            return;
        }
        if (chunk.finishReason() != null) {
            stream.finishReason().set(chunk.finishReason());
        }
        if (chunk.promptTokens() != null || chunk.completionTokens() != null
                || chunk.reasoningTokens() != null) {
            stream.usage().set(new int[]{
                    chunk.promptTokens() == null ? -1 : chunk.promptTokens(),
                    chunk.completionTokens() == null ? -1 : chunk.completionTokens(),
                    chunk.reasoningTokens() == null ? -1 : chunk.reasoningTokens()});
        }
    }

    /** 进入正文相：把残留短句发完，再发 thought_end。开过一次才发，没开过什么都不发。 */
    private void closeThinking(ActiveStream stream) {
        if (!stream.thinking().open()) {
            return;
        }
        double seconds = stream.thinking().elapsedSeconds();
        for (String step : stream.thinking().closeAndDrain()) {
            emitOrDisconnect(stream, new ChatEvent.ThoughtStep(step));
        }
        emitQuietly(stream.channel(), new ChatEvent.ThoughtEnd(seconds));
    }

    private void emitOrDisconnect(ActiveStream stream, ChatEvent event) {
        try {
            stream.channel().emit(event);
        } catch (SseChannel.ClientGoneException gone) {
            stream.disposeUpstream();
            truncate(stream, AiMessage.STOP_CLIENT_DISCONNECT);
        }
    }
```

`onComplete` 开头加一行 `closeThinking(stream);`（覆盖"纯推理无正文"），并把 `usage` 读取处补第三槽：

```java
        Integer reasoningTokens = usage.length > 2 && usage[2] >= 0 ? usage[2] : null;
```

`emitQuietly(..., new ChatEvent.TokenUsage(promptTokens, completionTokens, reasoningTokens))`。

- [ ] **Step 6: `retryable` 按状态判定**

`onError` 里替换硬编码 `true`：

```java
        emitQuietly(stream.channel(), new ChatEvent.ErrorEvt("UPSTREAM_MODEL_ERROR",
                "模型服务暂时不可用", retryable(error)));
```

```java
    /** 401/403 是"重试也没用"，其余（408/429/5xx/IO/超时/解析失败）才值得重试。 */
    private static boolean retryable(Throwable error) {
        if (error instanceof UpstreamStatusException status) {
            int code = status.status();
            return code != 401 && code != 403 && (code == 408 || code == 429 || code >= 500);
        }
        return true;
    }
```

`stop`/`clientGone`/`timeout` 路径**不调** `closeThinking` —— 流已断，补发只会多一次无意义往返（spec §4.6 的第四行）。

- [ ] **Step 7: 跑测试确认通过**

Run: `"$MVN" -pl AIModule clean test -Dtest='ChatStreamServiceTest,ChatStreamWriteCountTest,SseChannelTest' -Dsurefire.excludedGroups=`
Expected: 全绿（`ChatStreamServiceTest` 从 22 涨到 28）。

- [ ] **Step 8: 变异取证**

1. 把 `closeThinking` 的 `if (!open) return;` 去掉 → `noThoughtWhenModelCannotThink` 与 `stopDuringThinking` 至少一条红（不该出现 `thought_end` 的地方出现了）。
2. 把 `retryable(...)` 改回 `true` → `retryableFollowsStatus` 红。
3. 把 `ThinkingTracker.onReasoning` 里 `if (!enabled) return List.of();` 去掉 → 需要临时把测试的 tracker 建成 `enabled=false` 才红；**如实记录**：这条守护在 `ChatStreamServiceTest` 里默认构造是 `true`，所以真正的守门人是 Task 13 的 `thinkingSupported` 单一口径，不是 tracker 内部。别为了让变异红而伪造断言。
恢复，`grep -rn MUTATION AIModule/src/` 为 0。

- [ ] **检查点**：全模块 `clean test` 绿。

---

## Task 19: 切换唯一实现、删旧类、段 D 回归重跑

**Files:**
- Delete: `AIModule/src/main/java/org/leon/aimodule/chat/stream/SpringAiChatModelGateway.java`
- Delete: `AIModule/src/test/java/org/leon/aimodule/chat/stream/SpringAiChatModelGatewayTest.java`
- Modify: `AIModule/src/main/java/org/leon/aimodule/chat/stream/OpenAiStreamChatModelGateway.java`（加 `@Component`）
- Test: 迁移 `SpringAiChatModelGatewayTest` 里仍有效的用例到 `UpstreamChunkParseTest`（R11/R17 那两条已在 Task 16 重建，逐条核对后删旧文件）

**Interfaces:**
- Consumes: Task 13–18 全部
- Produces: 一个 `ChatModelGateway` bean；段 D 的 13 项回归证据

- [ ] **Step 1: 核对旧测试的每条断言都有新归宿**

```
旧用例                                     新归宿
textChunk / emptyTextBecomesNull      →  UpstreamChunkParseTest.roleOnlyFrameDropped
usageOnlyChunk                        →  finishFrameCarriesUsage
keepsFinishReason                     →  finishFrameCarriesUsage
blankFinishReasonBecomesNull          →  finishReasonLowerCased（大写/空白都归 null）
zeroUsageIsNotUsage                   →  zeroUsageDropped
realLengthFrameIsLowercased (R17)     →  finishReasonLowerCased
toMessage 的两条                      →  删除（新实现直接构 JSON，无 Spring AI Message）
```
若某条在新测试里没有对应，**先补新用例再删旧文件**，不许净减少覆盖。

- [ ] **Step 2: 删旧类、加 `@Component`**

```java
@Component
public class OpenAiStreamChatModelGateway implements ChatModelGateway {
```

Run: `grep -rn "SpringAiChatModelGateway" AIModule/src`
Expected: 0 处引用（有就先改）。

- [ ] **Step 3: 全量单测 + IT**

```
"$MVN" -pl AIModule clean test
"$MVN" -pl AIModule test -Dtest=ChatMapperDbIT -Dgroups=db-it -Dsurefire.excludedGroups=
```
Expected: 单测全绿，总数 **172** 条 = 现有 144 + 本计划新增 36（Task 13 三、14 十二、15 二、16 八、17 五、18 六）− 随旧网关一起删除的 8 条。**如果实际数对不上，先搞清楚少了哪条或多哪条再往下走**，不要改期望值凑绿。IT 仍 8/8。

- [ ] **Step 4: 真上游取证（会计费，够用即止）**

诊断实例方式（不改 `application.yaml`、不注册进 Nacos）：

```
CP="AIModule/target/classes;$(cat $TEMP/ai-cp.txt)"
"$JAVA_HOME/bin/java.exe" -XX:TieredStopAtLevel=1 -cp "$CP" org.leon.aimodule.AiModuleApplication \
  --server.port=8106 --spring.cloud.nacos.discovery.enabled=false
```
建会话（`modelKey=deepseek-reasoner`）→ 打一条需要思考的问题，断言：
1. 帧序 `event:meta → event:thought_step ×N → event:thought_end → event:delta ×M → event:usage → event:done`；
2. `thought_step` 载荷是**可读短句**而不是单词碎片，且条数 ≤ `thinking-max-steps`；
3. `usage` 里 `reasoningTokens > 0` 且 `reasoningTokens < completionTokens`；
4. 换 `deepseek-chat` 再打一条 → **零** `thought_step`/`thought_end` 帧。

- [ ] **Step 5: 段 D 的 13 项回归重跑（换了 transport 就必须重跑）**

按 `docs/superpowers/plans/2026-10-23-ai-chat-e2e-log.md` 的清单逐项重做，至少覆盖：逐字一致（拼 delta 与库内 `cmp`）、写次数（`dur_ms` 等于生成耗时）、`length` 收尾（Max 档数到 9000）、幂等三发同 `clientMsgId`、并发拒绝走 JSON、stop 中途 `truncated/stopped`、断连 `client_disconnect`、软删后 #6/#7 拒绝、坏 cursor、44s 长流经网关不断、`event:ping` 仍出现、`Accept` 四值矩阵仍全 200 JSON。

- [ ] **Step 6: 数据与进程清理**

JDBC 探针删除本轮取证行（`user_name` 限定），`ai_conversation`/`ai_message` 归零；停掉 8106；**不 commit**。

- [ ] **Step 7: 回写文档**

- spec §5.2 事件表把 `thought_step`/`thought_end` 从"预留不产生"改成正式契约（载荷 `{text}` / `{seconds}`，并注明 `data:` 无空格）。
- spec §6.4 错误矩阵加 `retryable` 按状态判定那两行。
- 新写 `.superpowers/sdd/2026-10-24-ai-chat-thinking-line/task-1X-report.md` 与 ledger（每个任务一份，沿用上一期格式：Status / 实现 / 关键发现 / 变异取证 / Deviations / 留给下一段）。
- 若 8105 需要新代码，告诉用户重启（不要自己动他的服务）。

---

## 自审结论（写完后回看 spec 与本计划）

- **覆盖**：spec §4.1→Task 13/15/19；§4.2→Task 17 Step 3/5；§4.3→Task 16；§4.4→Task 17 Step 5 + Task 18 Step 6；§4.5→Task 15；§4.6→Task 14；§4.7→Task 18；§4.8→Task 13；§7.1→各任务的测试与变异步骤 + Task 19 Step 3/5。§5（前端）按约定属 F 段，不在本计划。
- **计划纠正了 spec 一处**：`spring.ai.openai.chat.options.model` 不能删（遗留 `GET /ai/chat` 的 `AiChatController` 不显式指定 model，那行是它唯一来源），已回写 spec §4.1/§4.2。
- **计划新增而 spec 未点名的东西**：`chat.api-key`/`chat.base-url` 两个配置键（spec 只说"复用 `spring.ai.openai.*`"，落地方式是 yaml 里用 `${spring.ai.openai.…}` 引用，保持单一真相源）；`UpstreamStatusException` 这个类型（spec 只说"按状态判定 retryable"，没说 status 怎么跨层传）。
- **自审实际抓出并修掉的计划错误**（都是"照抄会编译不过或假绿"级别）：
  1. Task 15 的断言原本用 `frames.toString()`，而 `SseChannelTest.frames` 是 `List<String>` → 跨帧 `contains` 会因 `", "` 分隔而假失败。改成按下标断言，并补上"改 `emitsAllEventKinds` 的 size 断言"这一步。
  2. Task 18 的思考相用例原本全用 `prepareNormal()`（`deepseek-chat`，`supportsThinking=false`）→ tracker 关着，**用例会因为"什么都不发"而假绿**。补了 Step 0：注册 reasoner 模型 + `prepareThinking()`，并说明为什么必须这样。
  3. Task 18 的"思考不落库"哨兵原本写成往 `ChatStreamWriteCountTest` 里加一条独立用例，但那个类没有 `repo`/`service` 字段（全在 `interactions(int)` 里）→ 照抄编译不过。改成把 reasoning 混进既有的帧流，并把 `interactions(int)` 拆成 `(int, String modelKey)`，**否则哨兵测的是 tracker 关着的状态**。
  4. Task 17 的异常断言原本 `assertThrows`/裸 instanceof，但 `block()` 会把 checked 的 `TimeoutException` 包进 `ReactiveException` → 会红得让人误判实现错。改成顺着 cause 链找的 `cause(Class)` helper。
  5. Task 14 的切句期望值有四处与规则不自洽（边界标点该不该留在句内、`"a."` 单独喂因 lookahead 不切、强切没标省略号、`e.g.` 用例算错），已按 `findBoundary` 的两条规则逐条手推重写，并把"缩写会提前断句"从"我以为不切"改成**显式接受的用例**。
- **仍是推测、要到执行时才知道的东西**：`WebClient` 在没有 reactor-netty 时是否真能走 JDK `HttpClient` 请求工厂（Spring 6.1 支持，但 Task 17 Step 6 第一次跑就是它的取证点）；`ServerSentEvent` 解码器对"7 字节一片"的撕裂是否真的无损（同上）。这两条若失败，**升级路径是加 `spring-boot-starter-webflux` 依赖 —— 那属于新增依赖，必须先问用户**，不许自己加。

---

## 执行记录（逐任务回填）

### Task 13 配置项与能力上报单一口径 — 完成
- RED：`ChatPropertiesThinkingTest` 编译失败，缺的正是 6 个符号（`setThinkingEnabled`/`isThinkingEnabled`/`getThinkingMaxStepChars`/`getThinkingMaxSteps`/`getUpstreamIdleTimeoutSeconds`/`thinkingSupported`）。
- GREEN：17 条 = 新增 3 + `ConversationServiceTest` 10 + `EffortResolverTest` 4。后两个类**未改一行仍绿**，这就是"默认行为不变"的证据（全局开关默认 true）。
- 变异：`thinkingSupported` 改成 `return modelFlag;` → 恰好 `thinkingSupportedIsAnd` 与 `resolverUsesTheSameGate` 2 条红，恢复后 `grep MUTATION` = 0。
- 单一口径取证：`grep -rn supportsThinking AIModule/src/main` 只剩 `ChatProperties`（定义）、两处 `props.thinkingSupported(...)`、`ModelParams`/`ChatEvent.Meta`/`ModelVo` 的字段声明，以及 `ChatStreamService:197` 的 `params.supportsThinking()` —— meta 帧经 `ModelParams` 走同一口径，没有第二处计算点。
- 全模块 `clean test` 147/147（原 144 + 3）。
- yaml：删 `spring.ai.openai.chat.options.temperature` 与 `stream-usage`；`model: deepseek-chat` 保留并写明"遗留 `GET /ai/chat` 唯一的模型来源"。`chat.api-key`/`chat.base-url` 按计划留到 Task 17。
- **与计划的偏差 1 处**：测试文件省略了计划里的 `import org.leon.aimodule.chat.service.CtxMessage;` —— 该类型在本测试中未被使用，留着是无效 import。

### Task 14 `ReasoningSentenceSplitter` — 完成
- RED：类不存在 → 编译失败。
- GREEN：12/12 一次通过。11 条期望值在写测试前全部按 `findBoundary` 的两条规则手推过（含 `truncatesOverlongSentence` 走强切分支再 `clean` 截断到 10 字符这条最容易算错的路径）。
- **变异 1a 存活** —— 本计划 Task 14 Step 5 的第一条预测被证伪：`if (i + 1 == buf.length()) return i + 1;` 摘掉后 11 条用例全绿，因为 `feedAll` 把步骤聚合成一个串，而 1a 只改变了产出时机。这是**用例缺口**而非实现缺陷，补 `trailingPeriodWaitsForNextPiece`（三帧劈开一个小数）后重做：恰好 1 条红，失败信息 `expected: <[The value is 1.5 meters.]> but was: <[The value is 1.][5 meters.]>` 正是该规则要防的危害。
- 变异 1b（LATIN 分支无条件切）→ 3 条红：`doesNotSplitOnDecimal`、`abbreviationSplitsAreAccepted`、`trailingPeriodWaitsForNextPiece`。变异 2（flush 不清 buf）→ 恰好 `flushIsIdempotent` 1 条红。变异 3（去掉 `saturated` 置位）→ 恰好 `saturatesAtMaxSteps` 1 条红。恢复后 `grep -rn MUTATION AIModule/src/` = 0。
- 全模块 `clean test` 159/159（147 + 12）。新类暂无生产调用方（Task 18 才接线），属计划预期。

### Task 15 `ChatChunk` 与事件契约扩展 — 完成
- RED：6 处编译错误，位置正是两条新用例 + `emitsAllEventKinds` 里 `ThoughtStep`/`ThoughtEnd`/三参 `TokenUsage` 的调用点。
- GREEN：`SseChannelTest` 8/8；全模块 `clean test` **161/161**（159 + 2）。
- 计划没写但必须一起改的编译点（**本计划 Task 15 的漏项**，逐个补过）：
  1. `ChatStreamService.java:243` 是**生产代码里唯一的 `TokenUsage` 构造点**，必须补第三参；本任务先传 `null`，Task 18 换成累计的 reasoningTokens。
  2. `ChatStreamServiceTest.kinds()` 里的 `switch` 也是对 sealed 类型的穷尽 switch，新增两类事件后编译不过 → 补两个分支返回 `thought_step`/`thought_end`。
  3. `ChatStreamServiceTest` 假 sink 的 `case "usage"` 直接 `.asInt()` 会把 JSON 里的 `null` 读成 `0` → 改成 null-safe 取第三参。
  4. `ChatStreamWriteCountTest` 有两处 `ChatChunk` 构造（计划的文件清单里没列这个类）。
- 检查点取证（口径修正）：`new ChatEvent.TokenUsage(` 在 `src/main` 下**改前 1 处、改后 1 处**；总数 3→4 的增加全部来自本任务新增的那条用例自己的构造，不是新增生产构造点。`ChatChunk` 构造点 6→6。
- 变异（计划 Task 15 没有变异步骤，这里按"每条新守护都要可证伪"补两条）：
  - A｜帧名 `thought_step` 改成 `thoughtStep` → 恰好 `thoughtFramesWireNames` 与 `emitsAllEventKinds` 2 条红，证明 wire 名断言有效。
  - B｜删掉 `case ChatEvent.ThoughtStep` 整支 → **编译失败**（javac：switch 分支数少于 total pattern），这就是"穷尽 switch 会在漏映射帧名时拦住你"这句注释的证据。
  - 恢复后 `grep -rn MUTATION AIModule/src/` = 0。
- 遗留（按计划属 Task 18）：假 sink 的 `default -> {}` 仍会吞掉 `thought_step`/`thought_end`，所以现在还没有"编排层发出思考帧"的用例。
- **与计划的偏差 1 处**：`text`/`finish` helper 实际是 `private static`（计划写成 `private`），按现状改参。

### Task 16 上游帧 → `ChatChunk` 的纯映射 — 完成
- RED：类不存在 → 9 处编译错误。
- **计划 Step 4 的 mapper 设计被真实录制帧证伪**（这是本任务最重要的一条记录）：
  计划写的是 `new ObjectMapper().setPropertyNamingStrategy(SNAKE_CASE)` + record 组件靠隐式名匹配。
  照抄后 `finishFrameCarriesUsage` 红：`expected: <63> but was: <null>` —— `reasoning_tokens` 被静默丢掉。
  用一次性探针类（跑完即删）扫出规律：**同一份 JSON，属性顺序决定能不能绑上** ——
  `prompt_tokens + completion_token_details`（2 个属性）能绑，
  `prompt_tokens + completion_tokens + completion_tokens_details`（真实尾帧的 3 个属性）绑不上，
  单独一个 details 也绑不上；直接 `readValue(..., Usage.class)` 反而能绑。
  即"命名策略 + record 隐式组件名"的绑定是不确定的，绝不能留在计费字段上。
  → 改为**每个组件显式 `@JsonProperty("snake_case 原名")`，并去掉 SNAKE_CASE 策略**。
  这条改动的可证伪性由变异 4 证明（见下）。Global Constraints 第 5 条"测试必须用真实录制帧"就是它救回来的。
- GREEN：`UpstreamChunkParseTest` 8/8；全模块 `clean test` **169/169**（161 + 8）。
- 变异取证（计划 3 条 + 自补 3 条）：
  1. `positive()` 换成直接取值 → 恰好 `zeroUsageDropped` 1 条红（与计划一致）。
  2. `lower()` 原样返回 → 恰好 `finishReasonLowerCased` 1 条红（与计划一致，R17 哨兵）。
  3. **`@JsonIgnoreProperties` 与 `FAIL_ON_UNKNOWN_PROPERTIES=false` 同时摘掉 → 6 条 error，不是计划预测的 1 条红**：
     真实帧带着我们没建模的 `id/object/created/model/system_fingerprint/index/logprobs`，严格绑定后几乎所有用例都在解析阶段抛。
     这道守护的作用被计划低估了。
  4. （自补，证伪上面的修复）`@JsonProperty("completion_tokens_details")` 改成单数 `completion_token_details`
     → 恰好 `finishFrameCarriesUsage` 1 条红，且失败信息与修复前完全相同（`expected: <63> but was: <null>`）
     —— 说明显式名字就是那条绑定规则的载体。
  5. （自补，拆开计划第 3 条的两道守护）**只摘 DTO 注解、保留 mapper configure** → 8/8 绿。
  6. （自补）**只摘 mapper configure、保留 DTO 注解** → 8/8 绿。
     结论：两道守护**各自单独就够用**，同时保留是纵深防御（上游加字段不受我们控制，spec §4.3 要求的就是这个）；
     只有两道全摘才会崩，所以谁删一道都不会被测试立刻发现 —— 这一点必须写在这里，而不是留给下个人踩。
- 恢复后 `grep -rn MUTATION AIModule/src/` = 0。
- **与计划的偏差 2 处**：
  1. 本任务的新类**没有 `implements ChatModelGateway`** —— 接口要求 `stream()`，而 `stream()` 是 Task 17 的内容，
     留一个 `throw new UnsupportedOperationException()` 的占位实现是半成品。Task 17 加 `stream()` 时一起接上并删掉类注释里这句说明。
  2. mapper 的绑定方式改为显式 `@JsonProperty`（上面已详述），计划的 SNAKE_CASE 写法不采用。
