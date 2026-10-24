# AI 对话「思考线」+ 前端全量对接 · 设计文档

日期：2026-10-24
范围：给已交付的对话子系统（`docs/superpowers/specs/2026-10-23-ai-chat-conversation-design.md`）加上**模型推理过程的实时展示**，并把 `EZ_frontend` 的 AI 聊天页真正接到 8 个后端接口上。
前置：对话子系统 12 个任务已完成并经网关取证（`docs/superpowers/plans/2026-10-23-ai-chat-e2e-log.md`）。

---

## 一、目标与非目标

**目标**

1. 选 `deepseek-reasoner` 时，前端能看到一条**滚动的思考线**（按句切分的推理短句），思考结束后折叠成"Thought for Ns"，然后正文开始流出来。
2. 选 `deepseek-chat` 时**什么都不显示** —— 不渲染空思考线。
3. 前端聊天页从"一个居中标语 + 输入框"变成能真用的会话界面：会话列表、历史翻页、流式渲染、停止、重新生成。

**非目标**（详见第七节 YAGNI 清单）：思考链持久化、断线续流、多实例 stop 路由、思考链动画调参、附件、RAG 检索步骤。

---

## 二、事实基线（全部本轮实测，不是推断）

| 事实 | 怎么测出来的 |
|---|---|
| DeepSeek reasoner 逐帧发 `delta.reasoning_content` | 直连 `api.deepseek.com/v1/chat/completions`，92 帧里 63 帧带 reasoning、26 帧带 content、**0 帧两者都有** |
| 上游 usage 里有 `completion_tokens_details.reasoning_tokens` | 同一条响应尾帧：`completion_tokens=90`、`reasoning_tokens=63` |
| 上游帧前缀是 `data: `（**带**空格） | 原始字节 `d a t a : ␠ {` |
| **Spring AI 1.0.0-M7 丢弃 reasoning** | `javap` 它的 `OpenAiApi$ChatCompletionMessage`，字段只有 `rawContent/role/name/toolCallId/toolCalls/refusal/audioOutput` —— 没有 reasoning 字段，JSON 绑定时就没了 |
| **1.0.9 GA 也没有** | 同样 `javap`，字段止于 `annotations` |
| **1.1.8 才有 `reasoningContent`** | `javap` 确认字段存在 |
| 但 1.0.9 就要 Spring Framework 6.2.19 | 它的 pom 里 `spring-webflux` 版本；我们是 6.1.5（Boot 3.2.4） |
| WebClient 可用且零新增依赖 | AIModule 运行 classpath 里已有 `spring-webflux 6.1.5` + `reactor-core 3.6.4`；Spring 6.1 的 `ClientHttpRequestFactory` 支持 JDK `HttpClient`，不需要 reactor-netty |
| 前端组件已存在且契约比旧文档简单 | `src/components/AI/ThoughtLine.vue`（395 行）props 是 `steps: string[]` + `working` + `elapsed` + `doneLabel`，**不是**旧文档的 `{seq, kind, title, refCount}` |
| `elapsed` 单位是**秒（小数）** | 组件里 `paint(Math.round(props.elapsed * 10))` —— 秒转十分秒 |
| `Chat.vue` 目前是空壳 | 122 行，`send()` 只 `console.log`，没有消息列表也没有会话列表 |
| `PromptBar` 的模型列表是演示值 | `DEFAULT_MODELS`（`gpt-5.5` 那类）与 `DEFAULT_EFFORTS`，且 `models`/`efforts` 是可覆盖的 props |
| 前端请求层可复用 | `src/api/request.ts` 已有 `apiUrl()`、token getter、`BizError`/`AuthError`/`HttpError`、`Result` 信封解析；`/gw → 127.0.0.1:8100` 的 dev 代理已配好 |

---

## 三、方案选择

**采用：自写上流式客户端，替掉 `SpringAiChatModelGateway`。**

排除的两条路，理由都是量出来的：

- **升级 Spring AI**：`reasoningContent` 只在 1.1.x 有，而**连 1.0.9 都要求 Spring Framework 6.2.19**。任何升级都等于把 gateway/auth/practice/AIModule 整体抬到 Boot 3.4+，还要重钉 MyBatis-Plus/Seata/Nacos。为一个展示特性做全项目框架升级，爆炸半径不成比例。
- **ChatClient 管正文 + 另开一路拿 reasoning**：reasoning 与正文在**同一个 HTTP 响应里顺序到达**（实测 0 帧同时有），拆两路只能发两次请求 —— 双倍计费且两次答案不一致。

选 A 的附带收益：R11（`EmptyUsage` 每帧 0/0）和 R17（`LENGTH` 大写）这两个坑本来就是我们在适配层擦的；自己读上游之后，"库替我们撒谎"这一整类问题消失，`reasoning_tokens` 也顺手能拿（Spring AI 的 `Usage` 接口不透出 token 明细）。

代价说清楚：`deepseek-chat` 的流式路径也一起换掉了，所以**上一期段 D 的 13 项取证必须全部重跑**，不是"只测新模型"。

---

## 四、后端设计

### 4.1 分层与文件清单

分层与依赖方向不变：`web → stream(编排) → ChatModelGateway(接口) → 上游`。只换最右边的实现，接口只加字段。

| 动作 | 文件 | 说明 |
|---|---|---|
| 新增 | `chat/stream/OpenAiStreamChatModelGateway.java` | 唯一的 `ChatModelGateway` 实现 |
| **删除** | `chat/stream/SpringAiChatModelGateway.java` | 不留两条上游路径（见 §3） |
| 改 | `chat/stream/ChatModelGateway.java` | `ChatChunk` 加 `reasoning` 分量 |
| 新增 | `chat/stream/ReasoningSentenceSplitter.java` | 纯函数，无 IO |
| 改 | `chat/stream/ChatEvent.java` | sealed 加 `ThoughtStep` / `ThoughtEnd` |
| 改 | `chat/stream/SseChannel.java` | 穷尽 switch 加分支（编译期提醒，这正是当初选 sealed+switch 的目的） |
| 改 | `chat/stream/ChatStreamService.java` | `onChunk` 三路分岔、flush 时机、`retryable` 判定 |
| 改 | `chat/stream/ActiveStream.java` | 挂 splitter、思考起点、`thinkingOpen` |
| 改 | `chat/config/ChatProperties.java` | 四个新配置项 |
| 改 | `AIModule/src/main/resources/application.yaml` | 加 `chat.thinking-*` / `chat.upstream-idle-timeout-seconds`；删 `spring.ai.openai.chat.options.temperature` 与 `stream-usage` 两行（`model` 保留，见 §4.2） |

**绝对不动**：两张表、所有 entity/mapper、三段短事务与"一条消息 1 INSERT + 1 UPDATE"的写次数不变式、`clientMsgId` 幂等键、控制面 #1–#6、#8 的语义、网关路由与鉴权链。

### 4.2 请求构造

`POST {base-url}/v1/chat/completions`，头 `Authorization: Bearer …` + `Accept: text/event-stream` + `Content-Type: application/json`，体：

```json
{"model":"deepseek-reasoner","messages":[…],"stream":true,
 "stream_options":{"include_usage":true},"max_tokens":8192,"temperature":0.7}
```

- **配置复用 `spring.ai.openai.base-url` 与 `api-key`**，不新增 `chat.api-key`。key 的唯一真相源仍是 gitignored 的 `config/local.properties` 里那个 `EZ_DEEPSEEK_API_KEY`；多一个键就多一处能配错的地方。embedding 的 `base-url`/`api-key` 覆盖不动。
- **删掉 `spring.ai.openai.chat.options.temperature` 与 `stream-usage` 两行，但保留 `model: deepseek-chat`**。
  原计划三行全删，写计划时发现一条依赖链：遗留演示接口 `controller/AiChatController.java`（`GET /ai/chat`）用 `ChatClient` 且**不显式指定 model**，那行 `options.model` 是它唯一的模型来源 —— 删了它会静默弄坏一个不相干的接口。
  `temperature: 0.7` 与 effort 表直接冲突（谁生效取决于调用方是否传 options），`stream-usage` 现在由我们在请求体里发 `stream_options.include_usage`，这两行删掉没有读者。
- `temperature` 只在 effort 档位解析出值时才发（沿用 `EffortResolver` 的结果，不新增第二条回落链）。

### 4.3 解帧与 DTO

**不手写 SSE 解析器**。用 spring-webflux 自带的解码器：

```java
webClient.post().uri(… ).headers(…).bodyValue(req)
    .retrieve().bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
```

跨 chunk 半帧、`\r\n`、`data:` 后那个可选空格、多行 `data` 合并、`:` 注释行，它都处理了；我们只需要认 `data == "[DONE]"` 和解析 JSON。

> **一个必须写下来的不对称**：前端那一侧**反而必须手写**解析器（`fetch` 没有 SSE 解码器，`EventSource` 又发不了 POST 和自定义头）。两边规则不同但语义一致，将来有人想"统一"它们纯属浪费时间。

DTO 全部 `@JsonIgnoreProperties(ignoreUnknown = true)` + `PropertyNamingStrategies.SNAKE_CASE`。上游已经在发我们没建模的字段（`system_fingerprint`、`prompt_cache_hit_tokens`），**不忽略未知字段就是等着某天 500**。

分相：`delta.reasoning_content` 与 `delta.content` 各自非空各自成一路。实测 0 帧两者都有，但按"一帧内先 reasoning 后 content"处理，**不把分相当成契约**。

`finish_reason` 仍然小写归一 —— R17 那一课留在适配器里，即使现在上游给的是小写。

### 4.4 取消、超时与错误映射

- **取消**：`bodyToFlux` 的 Flux 可 `dispose()`，取消即关连接。`ActiveStream.upstream` 的 `Disposable` 契约一字不动，R14 的 claim + `status='streaming'` DB 守卫原样有效。
- **新增空闲超时**：`chat.upstream-idle-timeout-seconds: 60`，用 Reactor 的 `.timeout()` 挂在流上。理由：reasoner 可以十几秒不出帧，而 15 秒心跳只保活"我们→前端"这一跳，保不了"上游→我们"这一跳。总时长仍由 `SseEmitter` 的 300s 封顶，两条超时不冲突，先到者收尾、收尾走既有 `timeout`/`clientGone` 路径。
- **错误映射**：非 2xx → `WebClientResponseException`；401/403 → 不 retryable；408/429/5xx → retryable；IO/超时/解析失败 → retryable。事件与落库形状完全不变（`error{UPSTREAM_MODEL_ERROR}` + `done{upstream}` + `status=failed`）。
  **唯一行为变化：`retryable` 不再恒为 `true`**（现在 `ChatStreamService.java:260` 硬编码 true）。key 失效时给前端一个"重试"按钮是骗人。

### 4.5 事件契约扩展

沿用上一期 spec §5.2 里**预留但从未产生**的两个事件名，正式启用：

| 事件 | 频次 | payload | 说明 |
|---|---|---|---|
| `thought_step` | 0..n，只出现在思考相 | `{text}` | 一条可读推理短句，前端 append 到 `steps` |
| `thought_end` | 0..1 | `{seconds}` | **单位是秒（小数）**，前端置 `settled` 并写 `elapsed` |
| `usage` | 0..1 | `{promptTokens, completionTokens, reasoningTokens?}` | `reasoningTokens` **是 `completionTokens` 的拆分，不是加数** |

`reasoningTokens` **只上帧、不落库**：`ai_message` 没有对应列，而本期零 DDL。落库的 `completion_tokens` 已经是包含推理的总数（实测 `completion_tokens=90` 含 `reasoning_tokens=63`），所以统计口径不变，前端只是当场多显示一个数。刷新后这个数字就没了 —— 与思考线本身同生命周期。

时序：

```
meta → thought_step* → thought_end → delta* → usage → done
```

`kind` / `seq` / `refCount` 是**故意丢掉**的：现在只有 reasoning 一种生产者，`steps` 的消费方只收字符串数组。等 RAG 的检索步骤真存在了再加，那时加是有语义，现在加是猜语义。单连接内帧序由 TCP 保证，`seq` 无用。

**硬要求（宁缺毋滥）**：`supportsThinking=false` 的模型**一帧 `thought_step` 都不发，也不发 `thought_end`**。前端按"没收到就不渲染"处理。空思考线比没有思考线更糟。

### 4.6 切句状态机

```
feed(piece) → List<String>   // 零条或多条完整步骤
flush()     → List<String>   // 收尾，吐出缓冲区残留
```

1. **边界字符**：`。！？` 与换行**自终结**（见到即切）。`.!?` 必须**看到后一个字符**才切 —— 只有后随空白、另一标点或已到末尾才认。这条是为了不切 `1.5`、`e.g.`、`vs.` —— reasoning 文本里全是数字和缩写。
2. **强切上限**：缓冲区到 `thinking-max-step-chars`（默认 120）仍无边界，就在最后一个空格处强切，没空格就硬切。防"上游吐一整段无标点长文"把思考线撑成一块砖。
3. **步骤数上限**：`thinking-max-steps`（默认 12）。超了不再发新步骤，但**照样发 `thought_end`** —— 折叠成"Thought for 12.4s"比滚 300 行好。反正不落库，多发的帧立刻就没了。
4. **清洗**：每条步骤把 `\r\n\t` 与连续空格压成单空格再 trim；空白结果直接丢弃（纯标点、纯换行的碎片不产出步骤）；截断到 120 字符加省略号（行内渲染，长句撑破布局）。
5. **计时**：`elapsed` 从**第一个 reasoning 分片**起算，到进入正文相为止，用 `System.nanoTime()`（不能用 `currentTimeMillis`，挂钟会跳）。
6. **幂等**：`flush()` 连调两次不得重复吐出（"进入正文相"和"`onComplete`"可能都调它）。

**四种 flush 时机**

| 时机 | 行为 |
|---|---|
| 收到第一个 `content` 分片 | `flush()` → 发剩余步骤 → 发 `thought_end{seconds}` → 正常发 delta |
| 上游正常结束但全程无正文（纯推理就 finish） | 在 `onComplete` 里 flush + `thought_end` |
| 上游 error | flush 步骤，**不发** `thought_end`，改发 `error`；前端把思考线标"思考中断" |
| stop / 客户端断连 | **什么都不发**。流已断，前端走两段式停止的本地收尾 |

splitter 状态挂在 `ActiveStream`，**不需要锁**：Reactor 保证 `onNext` 串行，只有那一条路径碰它。要锁的是 `SseChannel.send`（心跳线程与 delta 线程并发），那是上一期已解决的另一件事。**别顺手给 splitter 加一把没用的锁。**

### 4.7 编排层改动

`onChunk` 从一路变三路，顺序即优先级：

```
chunk.reasoning() != null → 喂 splitter，每条句子发 thought_step（首条前记 thinkingStartedAtNanos，置 thinkingOpen）
chunk.text()      != null → 若 thinkingOpen：flush + thought_end，再发 delta
usage / finishReason      → 与现在完全一样
```

**写次数不变式一行都不用改**（reasoning 不入库 → 零 `repo` 调用），但要加一条哨兵测试：**3000 个 reasoning 帧的交互序列与 3 个 reasoning 帧逐条相等**。它守的是"不落库"这个决定 —— 将来谁想在思考阶段顺手 `touch` 一下进度（很自然的冲动，因为 UI 想显示"已思考 12 秒"），它会立刻红。

**必须直说的代价**：用户在"只想没答"的阶段按停止 → 库里是 `status=truncated`、`stop_reason=stopped`、`content=''`，#6 会返回它，前端渲染出来是空气泡。
决定：**保留这条行，由前端显式处理**（`status=truncated && content===''` → "已停止（模型还在思考）"）。
备选都被否掉：改标 `failed` 语义错（用户主动停止不是故障）；删掉这行违反"1 INSERT + 1 UPDATE"且 stop 之后行已写过、删除是第二次写。

**多轮对话不回传 reasoning**：它不入库，所以 `activeHistory` 天然不带。这是有意的，不是遗漏 —— DeepSeek 自己也建议多轮不回传 `reasoning_content`（会被忽略甚至报错），且思考文本动辄几千 token，带两轮就把 `context-max-tokens: 8000` 吃光。写死这条是为了将来有人翻到这儿不误判为 bug。

### 4.8 配置与能力上报一致性

| 键 | 默认 | 作用 |
|---|---|---|
| `chat.thinking-enabled` | `true` | 总开关。关掉时上游的 `reasoning_content` **在网关处直接丢弃**，且 `#1`/`meta` 报 `supportsThinking=false` |
| `chat.thinking-max-step-chars` | `120` | 单步长度与强切上限 |
| `chat.thinking-max-steps` | `12` | 单次思考最多几条步骤 |
| `chat.upstream-idle-timeout-seconds` | `60` | 上游空闲超时 |

**`supportsThinking = model.supportsThinking && thinking-enabled`**，`#1` 与 `meta` 都按这个报。前端只看这一个字段，不需要知道后端有全局开关。
上一期留下了一个"报 true 但永远发不出内容"的空头旗子（reasoner 的 `supports-thinking`），这一期绝不再犯：**能力上报必须与实际会发出的帧严格一致**。

---

## 五、前端设计

### 5.1 `src/api/chat.ts`（新增）

- `#1–#6`、`#8` 复用 `request.ts` 的 `request<T>` / `apiUrl()` / `BizError`，风格跟 `auth.ts` 一致。
- `streamChat(req, handlers, signal)` 是**唯一**手工解帧的地方，`useChat` 不感知字节流：
  1. **先看 `content-type`**：`application/json` → 按 `Result` 解析、走 `onReject(msg)`；`text/event-stream` → 进帧解析器。这一步不能省 —— 对着 JSON 错误体跑帧解析器表现为"静默无响应"，极难排查。
  2. 按 `\n\n` 切帧，**半帧留到下一个 chunk 再拼**（网络不按帧给你边界）。
  3. 帧内 `event:` 取名字；`data:` 去掉**最多一个**前导空格后 `JSON.parse`（后端发的是不带空格的 `data:{…}`，上游发的是带空格的，规则要能吃两种）。
  4. `ping` 直接忽略（它在流数据正常滚动时也会插进来，不是只在空闲时）。
  5. `error` 帧不抛异常（HTTP 是 200），走 `onError` 回调。
- 请求头带 `Accept: text/event-stream, application/json`。R16 之后单值也能拿到 JSON 拒绝，但双值仍是正确写法。

### 5.2 `src/composables/useChat.ts`（新增）

状态：会话列表（含 `nextCursor`）、当前会话消息（含 `beforeId` 向上翻页）、流式中的 `steps[]` / `text` / `thinkingOpen` / `elapsed` / `abort`。

行为：
- `clientMsgId = crypto.randomUUID()`，重试复用同一个值（靠 `uk_conv_client` 保证不产生第二条用户消息）。
- **停止两段式**：先本地 `settled` + `abort()` 冻结已渲染内容，**再**异步 `POST /stop`。反过来等接口返回会卡在网关往返上，用户 perceive 到的就是"停止按钮没反应"。
- 重新生成：`{conversationId, regenerate:true}`，不带 content。
- `retryable` 决定气泡下方给不给"重试"。

### 5.3 `src/views/AI/Chat.vue`（重写）+ 新组件

现在是 122 行居中标语 + 输入框，**没有消息列表也没有会话列表**，需新建 `ConversationList.vue` / `MessageItem.vue`。助手气泡 = `ThoughtLine` + 正文。

**一个必须显式处理的坑**：`PromptBar` 的 `DEFAULT_MODELS` 是 `gpt-5.5` 那类演示值。不传 `:models`（从 `#1` 拿）和 `:efforts`，用户在下拉里选到的 model 就是假的，发出去被后端拒成"不支持的模型"。`efforts` 虽然值恰好一致，但**恰好一致不是可以依赖的理由**。

### 5.4 流式 Markdown 渲染

依赖：**`marked` + `DOMPurify`**（合计约 35KB gzip）。模型输出是不可信内容，`v-html` 直插就是 XSS 口子，所以 sanitize 不是可选项。

两个具体坑：
1. **未闭合的代码块**：模型吐到 ```` ```java ```` 而结尾围栏还没来时，marked 会把后面整段吞进代码块，正文突然整块变灰。做法：流式期间若检测到奇数个 ``` 围栏，解析前先补一个闭合围栏；`done` 之后用原文重解析定稿。
2. **每帧重解析是 O(n²)**：16000 帧会把浏览器钉住。做法：delta 只累积字符串，渲染用 `requestAnimationFrame` 合并（一帧一次），`done` 时做最后一次全量解析。

`DOMPurify` **每次更新都要过**，不是只在首次；链接统一 `rel="noopener"`，禁 `iframe`/`script`/事件属性。

### 5.5 边界显示

| 情况 | 显示 |
|---|---|
| 业务拒绝（并发、会话不存在、超 32000 字） | 输入框上方一条错误条，文案用 `Result.msg` 原文 |
| `error` 帧 | 思考线 `doneLabel="思考中断"`，正文保留已产出部分 |
| `status=truncated && content===''` | "已停止（模型还在思考）" |
| 刷新重进会话 | 思考线**消失**，只剩正文 —— 这是"不落库"的预期结果，别当 bug 修 |

---

## 六、错误矩阵增量

在上一期 §6.4 之上，只改两行、加一行：

| 场景 | 形式 | 落库 |
|---|---|---|
| 上游 4xx（401/403，鉴权类） | `error{retryable:**false**}` + `done{upstream}` | `status=failed` |
| 上游 408/429/5xx / IO / 解析失败 / 空闲超时 | `error{retryable:true}` + `done{upstream}` | `status=failed` |
| 思考相中断（stop / 断连） | 无补充帧 | `status=truncated`、`content=''`、`stop_reason=stopped`/`client_disconnect` |

---

## 七、测试与验收

### 7.1 后端单测

- **真实帧夹具**：把本轮实测录下的上游序列（63 reasoning + 26 content + 尾帧 usage）当夹具喂进新网关，断言归一化后的 `ChatChunk` 序列与 `thought_step` 条数。这是 R17 那课的制度化 —— 不再用"我以为上游长这样"的 mock。
- 解帧：`[DONE]`、注释行、`data:` 带/不带空格、半帧跨 chunk、未知字段不炸。
- 分相与四种 flush 时机、`retryable` 按状态判定、空闲超时、`thinking-enabled=false` 时 reasoning 被丢弃且 `meta` 报 false。
- 切句（表驱动，纯函数）：逐 token 英文整句 / 边界被劈在两个分片中间（`"toge"`+`"ther."`）/ `1.5` 与 `e.g.` 不被切 / 中文 `。` 自终结 / 换行边界 / 120 字符无边界触发强切 / 纯空白碎片不产出 / `flush()` 尾句 / 超 `maxSteps` 后不再产出但仍计时 / `flush()` 连调两次不重复。
- 编排层帧序：`meta → thought_step* → thought_end → delta* → usage → done`；纯推理无正文也发 `thought_end`；error 不发 `thought_end` 但已发步骤保留；stop 在思考相不补发帧。
- **哨兵**：3000 个 reasoning 帧的 `repo` 交互与 3 个逐条相等。
- **回归**：上一期段 D 的 13 项清单**全部重跑**（逐字一致、写次数、`length` 收尾、幂等、stop、断连、并发拒绝、44s 长流、心跳、软删、游标）。换了 transport 就等于换了被测对象。
- **变异取证**：每条新守护都要证明它能红（本项目从 R13 起固定的规矩）。至少包括：把 `thinking-enabled` 的判断摘掉、把 `.timeout()` 摘掉、把 splitter 的 lookahead 摘掉（应只让 `1.5` 那条用例红）。

### 7.2 前端

**不引入 vitest**：项目现在零测试框架，为一个解帧器装一整套前端测试栈不划算。替代方案是把解帧写成纯函数（`parseSseFrames(buffer): Frame[]`）以便将来可测。
**这是有意的取舍：前端正确性本期只有人工取证，没有自动化回归。**

浏览器实测矩阵（`npm run dev` + 真网关 + 真上游）：
1. 选 reasoner → 思考线滚出短句 → 折叠成"Thought for Ns" → 正文开始 → markdown 标题/表格/代码块正常。
2. 选 chat → 一条思考线都不出现。
3. 代码块流式期间不整块变灰（未闭合围栏补偿生效）。
4. 思考相按停止 → 气泡显示"已停止（模型还在思考）"，不是空气泡。
5. 生成中点停止 → 立即冻结，不卡。
6. 并发开第二条流 → 错误条显示"上一条回答仍在生成"。
7. 上游 error（临时改坏 key）→ 思考线"思考中断" + 正文保留 + 无重试按钮（`retryable=false`）。
8. 刷新重进会话 → 思考线消失、正文完整。
9. 长回答期间 `ping` 被安静忽略（不产生空气泡）。
10. 历史向上翻页不重复不遗漏。

### 7.3 明确不做

思考链持久化 · 多实例 stop 路由 · reasoning 回传上下文 · `thought_step` 的 `kind`/`seq`/`refCount` · `Last-Event-ID` 续流 · 附件上传 · RAG 检索步骤 · 前端测试框架 · 思考线动画参数调整。

### 7.4 分段交付

这份 spec 覆盖两个可独立验收的段，按你一贯的节奏**一段一段做、段末停下取证**：

| 段 | 内容 | 段末交付 |
|---|---|---|
| **E 后端思考链** | §4 全部：新网关、切句、事件、配置、单测 + 变异取证、段 D 回归重跑 | curl 直连与经网关各取一次真 reasoner 帧序（含 `thought_step`/`thought_end`），单测全绿 |
| **F 前端对接** | §5 全部：`chat.ts`、`useChat`、`Chat.vue` 重写、markdown 渲染 | §7.2 那 10 条浏览器实测矩阵逐条过，附帧记录或截图 |

E 段做完就能用 curl 证明思考链是对的；F 段出问题（比如未闭合围栏的补偿逻辑）不会回头改后端契约。两段的批准点各自独立。

---

## 八、需要你点头的动作

| 动作 | 为什么单独问 |
|---|---|
| **重启 8105** | R17 的修复（`finish_reason` 大写归一）只在工作树里，正在跑的实例还是修复前的构建 |
| **装两个 npm 依赖**（`marked`、`dompurify`） | 改 `EZ_frontend/package.json` 与 lockfile，且那是**另一个项目仓库** |
| **真跑 reasoner 若干次取证** | reasoning tokens 是**计费**的（实测一次短问答 63 个 reasoning tokens，长思考能到几千）。取证会真花钱 |
| 主库 DDL | **不需要** —— 这是"不落库"换来的。本设计零表变更、零迁移 |
| Nacos / 网关配置 | **不需要** —— 上一期已实测 44s 长流经网关不断流 |

---

## 九、与既有文档的关系

- 上一期 spec 的 §5.2 里 `thought_step`/`thought_end` 标注为"预留但本期不产生"，**本设计正式启用它们**，事件表与 §6.4 错误矩阵按本文增量回写。
- 2026-10-10 的 `AI对话思考链路API设计文档.md`：它的事件载荷（`{seq, kind, title, refCount}`）与真实组件 `ThoughtLine.vue` 的 `steps: string[]` **不匹配**，以组件为准；它的 §6.4"宁缺毋滥"与 §6.6"不能用 EventSource / 停止两段式 / 重连不续流"三条被本文原样采纳。该文档其余部分（存储选型、附件、`ai_thought_step` 表）不在本期范围。
- 上一期 e2e 记录 §10 记的"reasoner 的 reasoning 内容压根到不了 `getOutput().getText()`，`supports-thinking: true` 是空头旗子" —— 本文就是那面旗子的兑现方案。
