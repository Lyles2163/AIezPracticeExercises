# 仿 ChatGPT 对话子系统（AIModule）设计

> 日期：2026-10-23 · 状态：已与用户逐节确认，待实施
> 提供方：`AIModule`（端口 8105，`spring-boot-starter-web` + Spring AI 1.0.0-M7 + DeepSeek）
> 消费方：本期**不写前端**。接口契约即将来 `EZ_frontend` 的对接依据。
> 前置文档：根目录《AI对话思考链路API设计文档.md》(2026-10-10)、`docs/superpowers/plans/2026-10-21-rag-05-chat.md`

---

## 一、范围

**本期做**：会话与消息的存储模型（2 张表）、8 个后端接口、`SseEmitter` 流式问答、服务端组装多轮上下文、重新生成（以最新版为准）、停止生成、断连与孤儿回收、`effort` 五档 → 服务端参数映射。

**本期不做**（每一项都在第九节留下明确接缝，不写半成品代码）：

| 不做的东西 | 为什么不做 |
|---|---|
| RAG 检索、知识库引用、降级 | 用户决定"先完成对话功能，后接 RAG"。`ai_message_citation` 表也一并推到 RAG 阶段 |
| 思考链路（`ai_thought_step` 表、`thought_step`/`thought_end` 事件、reasoning 切句摘要） | 本期无检索、无工具调用，四类步骤里只有 `reasoning` 有真实来源，其余三类没有生产者。事件名在契约里预留 |
| 附件上传（`ai_attachment`） | 本期服务端不解析任何文件，建表即空表 |
| `effort` 改模型 | 前端模型下拉与 effort 滑块是**两个独立控件**，`PromptBarSendDetail` 同时带 `model` 和 `effort`。经用户确认：`modelKey` 决定调哪个上游模型，`effort` **只决定服务端参数**（temperature / max_tokens），两者互不干涉。`deepseek-chat` + Max 就是低温度大上限，**不会**冒出 reasoning |
| 语音输入（PromptBar 的 `onDictate`） | 前端本地 Web Speech API，后端无接口 |
| 版本切换（ChatGPT 的"2/2 ←→"翻页） | 用户选择"以最新版为准"。`superseded` 行不进对话流 |
| 长会话摘要 / 上下文压缩 | 只做截断，丢最早上下文是已知代价 |
| 多实例下的 stop | registry 是进程内 Map，边界写在第九节 |

**必须复用的既有约定**（出处均已实测核对）：

| 既有事实 | 出处 |
|---|---|
| 网关 8100，`/ai/** → lb://AIModuleServer` 路由**已存在** | Nacos dataId `gateway.yaml`（不是旧文档说的 `gateway-module.yaml`） |
| 网关验签后剥离 `Authorization`，注入 `X-User-Name` | `GatewayModule/.../JwtAuthGlobalFilter.java:81-82` |
| 统一返回体 `Result{code,msg,data}`，`code=1` 成功 / `0` 失败 | `CommonJwt/.../pojo/Result.java`（AIModule 专用那一份） |
| 主键 `IdType.AUTO`，业务表前缀 `qb_` / 用户表 `users_dev` | `Users.java:15-21` |
| AIModule 已配 DeepSeek `base-url`，默认 `deepseek-chat` | `AIModule/src/main/resources/application.yaml:15-21` |
| `TokenCounter.count(String)`（jtokkit `cl100k_base`）可直接复用 | `AIModule/.../rag/chunk/TokenCounter.java:18` |

---

## 二、事实基线（2026-10-23 探测）

写设计前逐条验证过，这些是硬前提，不是假设：

| 探测项 | 结果 | 对设计的影响 |
|---|---|---|
| MySQL 版本 | **9.1.0**，`sql_mode=STRICT_TRANS_TABLES` | `utf8mb4_0900_ai_ci` 可用；NOT NULL 列必须显式给值 |
| MySQL 现有表 | 15 张（`qb_*` + `*_dev`），**没有任何 `ai_*` 表** | 本期从零建表。旧 Stage 6 计划里"`ai_message_citation` 已在 Stage 4a 建好"这个前提是**假的** |
| 服务端口 | 8100 / 8101 / 8103 / 8105 / 6379(Redis) / 8091(Seata TC) / 8848(Nacos) 均在监听 | 端到端可走真网关；Seata TC 在跑，但流式路径仍禁用全局事务（见 5.5） |
| Nacos 配置 | 6 个 dataId：`gateway.yaml`、`common-jwt-dev.yaml`、`AService`、`BService`、`AService_group`、`BService_group` | JWT secret 实际在 **`common-jwt-dev.yaml`**，而 AIModule/Gateway 的 `spring.config.import` 写的是 `common-jwt.yaml`（不存在）——环境既有不一致，本期不改，只记录 |
| 网关白名单 | `startsWith` 匹配：`/auth/login`、`/auth/register`、`/auth/captcha`、`/auth/sms/send`、`/actuator` | `/auth/testLogin` **不在**白名单，无法用它换 token（旧文档隐含此路不通） |
| 验证码 | `CaptchaServiceImpl` 用纯数字 `RandomGenerator`，答案写 Redis，TTL 120s，**并明文打进日志** | 端到端 token 可自动获取（见 8.2） |
| Spring AI M7 流式 usage | `OpenAiChatOptions` 确有 `setStreamUsage(Boolean)` 与 `getStreamOptions()`（javap 验证） | `spring.ai.openai.chat.options.stream-usage: true` 可让最后一个 chunk 带 usage |
| ES 客户端 JSON mapper | `new JacksonJsonpMapper()`，不复用 Spring 容器里的 `ObjectMapper` | 给 MVC 加全局时间序列化配置**不会**污染 ES 文档序列化 |
| `AiExceptionHandler` | `@RestControllerAdvice(basePackages="org.leon.aimodule.rag")`，且**没有任何 `@ResponseStatus`** | 只覆盖 rag 包；缺头/校验失败实际返回 **HTTP 200 + code=0**。旧文档的 400/404 错误表与仓库实际行为矛盾，本期按仓库行为走 |
| 前端 `EZ_frontend` AI 页 | `Chat.vue` 是纯 UI 壳（`send()` 只 console.log），`PromptBar.vue` 的模型/档位都是组件内置演示值 | 后端契约先行；`models`/`efforts` 的**返回形状**必须对齐 `PromptBarModel` 与 `efforts: string[]` 两个 prop，见 4.7 |

---

## 三、数据模型

### 3.1 约定

引擎 `InnoDB`，`utf8mb4` / `utf8mb4_0900_ai_ci`，表前缀 `ai_`；主键 `BIGINT UNSIGNED AUTO_INCREMENT` + `IdType.AUTO`（自增 ID 远小于 2^53，前端 JSON 反序列化无精度丢失，不需要雪花 ID）；时间统一 `DATETIME(3)`；**不建物理外键**（与 Seata 和未来分库分表兼容，也与 `qb_*` 风格一致）；用户身份字段 `user_name VARCHAR(64)`，来自网关注入的 `X-User-Name`；删除一律软删。

### 3.2 DDL

```sql
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
  status              VARCHAR(16)  NOT NULL DEFAULT 'done'
                      COMMENT 'streaming/done/failed/truncated/superseded',
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

### 3.3 三个设计要点

**为什么需要 `reply_to_message_id`**：重新生成不新建用户消息，只在该 user 消息下追加一条 assistant 行并把旧行置 `superseded`。这个列是"哪几条回答属于同一个问题"的分组依据，也是历史接口能正确还原对话链的前提。

**"当前生效回答"用 `status` 过滤表达，不加布尔列**：一个 user 消息下最多一条非 `superseded` 的 assistant。取上下文就是 `WHERE conversation_id=? AND status IN ('done','truncated')`。将来要做版本切换时再加 `is_current` 并改这条查询——现在不需要。

**`uk_conv_client` 不会卡住 assistant 行**：MySQL 唯一索引允许多个 NULL 共存，assistant 行不填 `client_msg_id`，同会话几百条不会撞键。幂等只对用户消息生效，正是重试语义需要的。

### 3.4 与旧设计文档的差异（每条都是主动决定）

| 改动 | 理由 |
|---|---|
| `effort` 保留，但**只映射参数不映射模型** | 前端有滑块且每次发送都带值（`PromptBar.vue:128`、`PromptBarSendDetail.effort`）。旧文档"用 effort 反推模型"的方案不采用——用户同时能选模型，反推会让模型下拉变成误导 |
| 砍 `ai_thought_step` 表 | 本期无步骤生产者。Stage 6 计划里已有其 DDL，接 RAG 时一并建 |
| 砍 `reasoning_text` / `thought_seconds` / `meta_json` 列 | 同上。MySQL 8+ 加 nullable 列走 `ALGORITHM=INSTANT`，秒级不重建表，**预留空列没有收益** |
| 砍 `FULLTEXT KEY ft_content WITH PARSER ngram` | 本期无会话内搜索接口；FULLTEXT 让每次消息写入多付一次分词开销，而聊天是写热路径。将来 `ALTER TABLE ai_message ADD FULLTEXT ft_content(content) WITH PARSER ngram` 即可 |
| 加 `last_preview` 列 | 会话列表一页 20 条，用 `last_message_id` 回表读 `MEDIUMTEXT` 是 20 次点查。改成写入时截断 80 字存下来，列表查询退化为**一次索引范围扫描**，无 JOIN。代价是预览文本冗余 |
| 砍 `total_tokens` 列 | 无消费者。将来做用量统计再从 `ai_message` 聚合或加列 |

### 3.5 `message_count` 维护规则（写死在代码里）

新建用户消息 `+1`；assistant 完成 `+1`；重新生成时先 `-1`（旧行标 superseded）再 `+1`（新行完成），净变化 0。配套单测断言它等于实际计数。

---

## 四、接口契约

### 4.1 通用约定

| 项 | 约定 |
|---|---|
| Base | 走网关 `http://127.0.0.1:8100`，前缀 `/ai/chat`。AIModule 直连 8105 时路径相同，但拿不到 `X-User-Name`，会返回"缺少请求头" |
| 取当前用户 | `@RequestHeader("X-User-Name") String userName`，**不碰 Authorization**（网关已剥离） |
| 返回体 | `Result{code,msg,data}`。仅流式接口 #7 例外 |
| 归属校验 | 会话不属于本人 / 已软删 → 统一 `code=0, msg="会话不存在"`，不区分"无权限"与"不存在"，避免会话 ID 枚举 |
| 时间 | VO 里 `createTime`/`updateTime` 为 epoch 毫秒 number。**实现方式：VO 字段直接用 `Long`，由 `ChatTime.millis(LocalDateTime)` 显式转换**，不注册全局 Jackson 定制器——少动一处全局状态，RAG 出参零风险（原计划的 `Jackson2ObjectMapperBuilderCustomizer` 方案在写实施计划时被否掉，因为它的唯一收益是"万一有人往 VO 里塞 LocalDateTime"，而那个风险用类型就能消掉） |
| 分页 | 只用游标，禁 offset 深翻。会话列表游标 = base64(`updateTime,id`)；消息列表游标 = `beforeId` |

### 4.2 清单

| # | 方法 | 路径 | 约束 |
|---|---|---|---|
| 1 | GET | `/ai/chat/models` | 返回 `{defaultModel, defaultEffort, models:[{key,name,tag,supportsThinking}], efforts:["Low","Medium","High","Extra","Max"]}`。`models`/`efforts` 的形状**逐字段对齐前端 `PromptBarModel` 与 `efforts: string[]` 两个 prop**，前端直接把返回值绑上去即可覆盖组件内置的演示值。模型白名单来源是配置 `chat.models`（见第七节） |
| 2 | POST | `/ai/chat/conversations` | body `{title?, modelKey?, effort?}`；title 省略存"新对话"；modelKey 必须命中 #1 白名单；effort 必须命中 #1 的 efforts |
| 3 | GET | `/ai/chat/conversations` | `?cursor=&limit=`（默认 20，上限 50）→ `{items:[{id,title,messageCount,lastPreview,updateTime}], nextCursor}` |
| 4 | PATCH | `/ai/chat/conversations/{id}` | body `{title}`，1–120 字符 |
| 5 | DELETE | `/ai/chat/conversations/{id}` | 只置 `status=0`，消息保留 |
| 6 | GET | `/ai/chat/conversations/{id}/messages` | `?beforeId=&limit=`（默认 30，上限 100）→ `{hasMore, items:[...]}`，`items` 按 id 升序 |
| 7 | POST | `/ai/chat/completions/stream` | `text/event-stream`，见第五节 |
| 8 | POST | `/ai/chat/messages/{id}/stop` | 中断生成，见 5.6 |

**#6 过滤规则**：`status <> 'superseded'`。被重新生成顶掉的旧回答不出现在对话流里；`streaming` 行会返回，前端据此显示"正在生成"。

```json
{ "code": 1, "data": { "hasMore": true, "items": [
  { "id": 41, "conversationId": 7, "role": "user", "content": "把这段改成 STAR 结构",
    "status": "done", "createTime": 1760000800000 },
  { "id": 42, "conversationId": 7, "role": "assistant", "content": "……",
    "status": "done", "modelKey": "deepseek-chat", "effort": "Max", "stopReason": "done",
    "usage": { "promptTokens": 812, "completionTokens": 1640 },
    "createTime": 1760000809000 }
]}}
```

### 4.3 自动改名

ChatGPT 的会话标题不是用户填的，是第一条用户消息前若干字自动生成的。落法：#2 建会话时存默认"新对话"；#7 第一条用户消息落库后，对**仍是默认标题**的会话执行条件 UPDATE 取前 24 字。用 `title <> '新对话'` 作条件而不是加 `title_locked` 列——用户手动 #4 改名后天然不再被覆盖。

### 4.4 同一会话禁止并发流

#7 进入时检查：该会话内是否存在 `create_time` 在 5 分钟内、状态为 `streaming` 的 assistant 行。存在则不建流，返回 `code=0, msg="上一条回答仍在生成"`。

一条规则同时解决三件事：① 用户边出字边发下一句导致历史链错乱；② 同一 `clientMsgId` 并发重试产生两条同时"生效"的回答；③ 前端本该禁用发送按钮，服务端不信任前端但要能拒。代价是进程重启遗留的 `streaming` 行要等 5 分钟才放行——所以 #7 在建流前顺手把**本会话内**超时的 `streaming` 行标 `failed`，回收与检查在同一处。

### 4.5 幂等：重试即重新生成

前端每次发送生成一个 `nanoid` 作 `clientMsgId`。#7 命中 `uk_conv_client` 时不报错、不新建用户消息，而是转为"为这条已存在的 user 消息重新生成回答"：旧 assistant 标 `superseded`，用派生键 `clientMsgId + ":a"` 插新 assistant 行。这样"断网后点重试"和"点重新生成"在后端是同一条代码路径，不必维护两种语义。

### 4.6 与现有 `GET /ai/chat` 的关系

`AiChatController.chat()` 是 Spring AI 的最小连通验证（同步返回纯文本，不套 `Result`），保留作上游通路健康检查，本期不改造、不调用它。新代码全部放 `org.leon.aimodule.chat` 包，流式接口新建 `ChatStreamController`。

### 4.7 前端契约对齐（`EZ_frontend` 实测）

后端契约的形状不是我们凭空定的，前端已有组件在等它。实测结论：

| 前端事实 | 出处 | 后端对策 |
|---|---|---|
| 模型下拉的 `gpt-5.5 / gpt-5.5-mini / gpt-6` 是**组件内置演示值**，`Chat.vue` 没传 `:models` | `PromptBar.vue:123-127`、`Chat.vue:59-79` | 这些名字与本项目无关。#1 返回真实模型，前端将来 `:models="catalog.models"` 覆盖 |
| effort 五档 `['Low','Medium','High','Extra','Max']` 同样是内置默认值 | `PromptBar.vue:128` | #1 的 `efforts` 数组必须与此**逐字相等**，否则前端滑块索引错位 |
| `send` 一次带出 `{attachments: string[], model?: PromptBarModel, effort?: string}` | `PromptBar.vue:41-45` | #7 接受 `modelKey` + `effort`；`attachments` **本期不受理**（见第九节接缝） |
| `PromptBarModel = {key, name, tag?}` | `PromptBar.vue:35-39` | #1 的 model 项给 `key/name/tag`，`tag` 用于 "Flagship"/"Fast" 那类右侧灰字标签 |
| `busy` prop 决定发送/停止按钮形态 | `PromptBar.vue:58`、`Chat.vue:28-38` | 与 4.4 的"同会话禁并发流"对应：前端禁按钮，服务端仍拒 |
| `Chat.vue` 是纯 UI 壳，`send()` 只 `console.log` + sleep 800ms，无 `src/api/chat.ts` | `Chat.vue:27-35` | 本 spec 即"先立规矩"。前端对接时按项目既有习惯，先建带类型的 `api/chat.ts` 服务函数层 |

---

## 五、流式实现

### 5.1 三层结构，依赖方向单向

```
ChatStreamController   ← 只懂 HTTP/SSE：校验、建 emitter、发帧、注册回调
       ↓ 传入 SseChannel（发帧动作的封装）
ChatStreamService      ← 只懂编排：幂等、并发检查、上下文组装、落库三段
       ↓ Consumer<ChatEvent>（不 import 任何 SseEmitter 类型）
ChatModelGateway       ← 接口；SpringAiChatModelGateway 实现包住 ChatClient
```

`ChatEvent` 是 sealed interface，六个 record：`Meta`、`Delta`、`Usage`、`Done`、`ErrorEvt`、`Ping`。编排层只 `consumer.accept(new ChatEvent.Delta(text))`，单测传一个 `List<ChatEvent>` 收集器即可断言整条事件序列，**不需要真连 DeepSeek、不需要 Spring 上下文**。这是本期最重要的可测试性决定，也是后接 RAG 时唯一不用改的接缝——检索步骤只是多几种 `ChatEvent`。

`EffortResolver` 是编排层与网关之间的一个纯函数：`resolve(modelKey, effort) -> ModelParams(modelKey, temperature, maxTokens)`，参数取自 `chat.models` / `chat.efforts`。`ChatModelGateway.stream(List<CtxMessage> messages, ModelParams params)` 只认 `ModelParams`，不认 effort 这个概念——档位语义集中在一处，换上游时不用改编排层。

`ChatModelGateway` 这层抽象的另一作用：Spring AI M7 的 `ChatClient` 流式 API 具体方法名以实测为准（已用 `javap` 确认 `StreamResponseSpec.chatResponse() -> Flux<ChatResponse>`、`Generation.getOutput().getText()`、`Usage.getPromptTokens()/getCompletionTokens()`、`OpenAiChatOptions.Builder.model(String)`、`ChatClientRequestSpec.messages(List<Message>)`），把它封在接口后面，编排层和单测都不受版本细节影响。

### 5.2 事件契约

| 事件 | 频次 | payload | 说明 |
|---|---|---|---|
| `meta` | 1，最先 | `{conversationId, messageId, modelKey, effort, supportsThinking}` | 前端拿到 `messageId` 才能调 #8；`supportsThinking=false` 时不渲染思考线 |
| `delta` | 0..n | `{text}` | 正文增量 |
| `usage` | 0..1 | `{promptTokens, completionTokens}` | 需 `stream-usage: true`，取上游最后一个 chunk |
| `done` | 1，最后 | `{finishReason}` = `done`/`stopped`/`length`/`upstream` | 发完即 `complete()` |
| `error` | 0..1 | `{errorCode, msg, retryable}` | 紧跟一帧 `done{upstream}` |
| `ping` | 每 15s | `{t}` | 防网关/代理空闲断流 |

`thought_step` / `thought_end` **在本契约中预留但本期不产生**。前端（将来）按"没有 `thought_step` 就不渲染思考线"处理。

响应头：`Content-Type: text/event-stream;charset=UTF-8`、`Cache-Control: no-cache, no-transform`、`X-Accel-Buffering: no`。

```
event: meta
data: {"conversationId":7,"messageId":42,"modelKey":"deepseek-chat"}

event: delta
data: {"text":"## 经"}

event: ping
data: {"t":1760000812000}

event: usage
data: {"promptTokens":812,"completionTokens":1640}

event: done
data: {"finishReason":"done"}
```

**帧必须由 Jackson 序列化后再拼 `data:` 前缀，禁止手拼字符串**——正文里的换行和 `\n\n` 会直接切断帧，这是 SSE 最常见的事故点。

### 5.3 一处真实的并发坑

`SseEmitter.send()` 不是幂等追加，两个线程同时写同一个 response 会交错出坏帧。心跳由 `ScheduledExecutorService` 触发，delta 由 Reactor IO 线程触发，**必然并发**。做法：把 emitter 包进 `SseChannel`，`send`/`complete`/`completeWithError` 全部 `synchronized (this)`，"发一帧"是唯一入口。约二十行，但没有它就是偶发线上故障。

### 5.4 线程与超时

| 参数 | 取值 | 理由 |
|---|---|---|
| `chat.stream-timeout-ms` | 300000 | 长思考 + 长回答；同时是 `SseEmitter` 构造参数 |
| 上游订阅线程池 | core 4 / max 16 / `SynchronousQueue` / 拒绝即返回"服务繁忙"不建流 | 长连接容量上限必须等于并发 emitter 数。**排队 30 秒才出首字比直接拒绝更糟** |
| 心跳 | fixedRate 15s 扫活跃 `SseChannel` | 与 emitter 生命周期绑定，收尾时取消 |
| Tomcat 线程 | 返回 emitter 即释放（servlet 异步） | 需确认网关侧超时 ≥ 本值，见 8.3 |

### 5.5 落库三段，流式期间零次写

```
T1 建流前（一个短事务）
   ├─ 幂等检查：clientMsgId 命中已有 user 消息 → 转"重新生成"分支
   ├─ 并发检查 + 回收本会话超时的 streaming 行
   ├─ INSERT user 消息 (status=done)          ← 普通发送才有
   ├─ UPDATE 旧生效 assistant → superseded     ← 重新生成才有
   ├─ INSERT assistant 占位 (status=streaming, content='')
   ├─ 条件自动改名 (title='新对话')
   └─ UPDATE ai_conversation 计数 / last_preview / last_message_id

（流式过程中：零次数据库写。delta 只进内存 StringBuilder）

T3 收尾（一个短事务）
   ├─ UPDATE ai_message SET content=?, status=?, stop_reason=?, prompt_tokens=?, completion_tokens=?
   └─ UPDATE ai_conversation（preview / 计数 / last_message_id 再校准）
```

**不变式：一条消息在生成过程中被写的数据库次数是常数（INSERT 1 次 + UPDATE 1 次），与 token 数无关。** 配套一个 mapper 调用计数断言测试——因为"每个 chunk 顺手 update 一次 content"是这套设计里最容易无意中写出来的性能事故，且它在 100 token 的回答上表现良好、6000 token 上才暴露。

状态枚举里**没有 `pending`**。线程池拒绝发生在建流之前，那条路径根本不进 T1，所以"已入队但未订阅"这个状态没有对应的行可承载；assistant 占位行一落地就是 `streaming`。少一个状态就少一条"卡在 pending 的孤儿"要回收。

**事务边界与 Seata**：三段都用普通 `@Transactional`，**禁止 `@GlobalTransactional`**。Seata TC 虽在 8091 运行，但一次回答跨分钟持有全局事务会让 undo_log 与分支锁等待成倍放大——这是理由本身，与 TC 是否在跑无关。

### 5.6 停止生成与孤儿回收

进程内 `ChatStreamRegistry`：`messageId → ActiveStream(SseChannel, Disposable, StringBuilder)`。

| 触发 | 动作 |
|---|---|
| `POST /ai/chat/messages/{id}/stop` | 命中 → `disposable.dispose()` → 用缓冲已有内容写 `content`、`status='truncated'`、`stop_reason='stopped'` → 发 `done{stopped}` 并 complete → 摘除。**已产出内容保留** |
| stop 未命中但 DB 该行仍 `streaming` | 孤儿（进程重启过）：直接按 DB 状态改 `truncated`，返回 success。已产出内容拿不回来 |
| stop 时该行已 `done` | 幂等，直接 success |
| `emitter.onTimeout` / `onError` / 发送抛 `IOException` | dispose + 落 `truncated`、`stop_reason='client_disconnect'`（超时用 `timeout`） |

---

## 六、上下文组装与异常处理

### 6.1 上下文

取历史：`SELECT role, content FROM ai_message WHERE conversation_id=? AND status IN ('done','truncated') ORDER BY id DESC LIMIT 200`，从最新往回累加 token 到预算，再翻正为升序。最终 prompt = system（`chat.system-prompt`，可空）+ 截断后的历史（含本轮新用户消息）。

| 决定 | 取值 / 理由 |
|---|---|
| token 预算 | `chat.context-max-tokens: 8000`。DeepSeek 上下文 64K，留出回答空间 |
| 计数器 | 复用 `TokenCounter`（jtokkit `cl100k_base`） |
| **计数是近似的** | DeepSeek 分词不是 cl100k_base，中文侧 cl100k 通常偏多，所以 8000 是保守上限而非精确值。不能假装算的是上游的数 |
| **不撕开一问一答** | 截断后若序列首条是 `assistant`，再往最老方向丢一条。以 assistant 开头的历史会让模型以为对话是它自己起的头。必须配单测 |
| 最新用户消息 | 永不被截断；单条就超预算时直接拒绝（`code=0`） |

### 6.2 异常处理落点

新增 `ChatExceptionHandler`，`@RestControllerAdvice(basePackages = "org.leon.aimodule.chat")`。**不扩宽 RAG 那个 advice 的作用范围**——把 `org.leon.aimodule.rag` 已验证的异常映射（尤其 `RagForbiddenException` 与 ES 不可用文案）卷进改动，爆炸半径不合算，且 RAG 侧有测试依赖它。两者返回同一份 `Result`。

### 6.3 HTTP 状态与前端契约

业务拒绝统一 **HTTP 200 + `Result.code=0`**，与仓库现状一致（`AiExceptionHandler` 无 `@ResponseStatus`）。拒绝建流时 `Content-Type` 是 `application/json`。

**这条必须写进对接说明**：将来前端调 #7 必须先判 `response.headers.get('content-type')`——是 `text/event-stream` 才进 SSE 解析器，是 `application/json` 就当普通错误读。否则对着 JSON 错误体跑帧解析器，表现为"静默无响应"，极难排查。

### 6.4 错误矩阵

| 场景 | 形式 | 落库 |
|---|---|---|
| 参数非法（缺 `content`、`modelKey` 不在 `chat.models`、`effort` 不在 `chat.efforts`、`title` 超长、`limit` 越界） | `code=0`，不建流 | 无 |
| 缺 `X-User-Name` | `code=0, msg="缺少请求头: X-User-Name"` | 无 |
| 会话不存在 / 不属于本人 / 已软删 | `code=0, msg="会话不存在"` | 无 |
| 同会话已有进行中的流 | `code=0, msg="上一条回答仍在生成"` | 无 |
| 线程池打满 | `code=0, msg="服务繁忙，请稍后重试"` | 无 |
| 上游报错 / 超时 | SSE `error{UPSTREAM_MODEL_ERROR, retryable:true}` + `done{upstream}` | `status=failed`、`error_msg` 截断 255，已产出内容保留 |
| 客户端断连 / emitter 超时 | 无（连接已断） | `status=truncated`、`stop_reason=client_disconnect`/`timeout` |
| `finishReason=length` | `done{length}` | `status=done`、`stop_reason=length` |

---

## 七、配置项

```yaml
chat:
  default-model: deepseek-chat
  default-effort: Medium
  models:                      # 既是 #1 的返回内容，也是 modelKey 的白名单（唯一真相来源）
    - key: deepseek-chat
      name: deepseek-chat
      tag: Fast
      supports-thinking: false
    - key: deepseek-reasoner
      name: deepseek-reasoner
      tag: Reasoning
      supports-thinking: true
  efforts:                     # 顺序即滑块从左到右；key 必须与前端 DEFAULT_EFFORTS 逐字一致
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
spring:
  ai:
    openai:
      chat:
        options:
          stream-usage: true   # 必须显式开，否则流式末尾无 usage → usage 事件永不出现
```

`effort → (temperature, maxTokens)` 是**单调且可断言**的真实差异，不是装饰。**一个必须实测的上游限制**：DeepSeek 对 reasoning 模型有资料称 `temperature` / `top_p` 不生效——若实测属实，那么 `modelKey=deepseek-reasoner` 时 effort 只体现为 `max_tokens` 一档差异。这是上游能力边界，要在验收清单里用真实请求记录，而不是在文档里假设它不存在。

---

## 八、测试与验收

### 8.1 单元与切片（覆盖率主体，不连上游）

| 测试 | 断言 |
|---|---|
| `ChatStreamServiceTest` | 事件序列 = `meta → delta* → usage → done`；假上游抛错时 = `… error → done{upstream}` |
| `ChatStreamServiceWriteCountTest` | 一次回答期间 mapper 的 update 调用次数为常数（实现 5.5 不变式） |
| `ContextAssemblerTest` | 预算截断、不撕开问答对、最新消息永不丢、超预算拒绝 |
| `IdempotencyTest` | `clientMsgId` 命中 → 不新建用户消息、旧 assistant 转 `superseded`、计数净变化 0 |
| `CursorCodecTest` | `(updateTime,id)` base64 往返；伪造 cursor 抛业务异常而非 SQL 异常 |
| `ChatControllerWebTest`（MockMvc standalone） | 8 个接口校验、归属统一文案、`Result` 形状、时间字段是 number |
| `MessageMapperIT` | 直连本地 MySQL 的**独立 schema `ezpracticeexercises_chat_it`**，`schema.sql` 建表、跑完 drop，默认跳过、`-Dchat.it=true` 才启用（参照 `dev-seed` 开关风格） |

### 8.2 手工端到端（走网关 8100）

已验证可全自动打通的 token 获取链路：

1. `curl http://127.0.0.1:8100/auth/captcha` → 白名单放行，返回 `{captchaKey, imageBase64Data}`（纯数字码）。
2. 把 base64 落成 PNG，用多模态读取识别数字码。
3. `POST /auth/login {username, password, captchaKey, captchaCode}` → token，有效期 24h。
4. `curl -N -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{...}' http://127.0.0.1:8100/ai/chat/completions/stream` → 观察帧序列与落库。
5. 故障注入：临时把 `spring.ai.openai.api-key` 改错并重启，制造上游 4xx，验 `error` 帧与 `status=failed`。比 mock 更接近真实。

**前置缺口**：`users_dev` 里需要一个可用账号与密码（登录走 `passwordEncoder.matches` BCrypt）。要么用户提供现有 dev 账号，要么经同意后 SQL 插入专用测试用户。

### 8.3 验收清单

- [ ] `done` 收尾后库内正文与流式所见逐字一致
- [ ] 流式期间除 T1/T3 外无任何数据库写入（由 8.1 的计数测试保证）
- [ ] 刷新重进会话，历史完整复原；被 superseded 的旧回答不出现在流里
- [ ] stop：状态收敛为 `truncated` / `stopped`，已产出内容保留
- [ ] 同一 `clientMsgId` 重试不产生第二条用户消息
- [ ] 不属于本人的 `conversationId` 在 #4/#5/#6/#7/#8 上返回同一文案"会话不存在"
- [ ] 全程不出现 `thought_step` 事件
- [ ] 五档 effort 解析出的 `(temperature, maxTokens)` 两两不同（单测断言），且请求体里 effort 非法时拒绝建流
- [ ] 实测 `deepseek-reasoner` 是否忽略 `temperature`，结论写回第七节那一段（若忽略，明确 effort 在该模型上只剩 `maxTokens` 差异）
- [ ] #1 返回的 `efforts` 数组与前端 `DEFAULT_EFFORTS` 逐字一致，`models` 项含 `key/name/tag`
- [ ] 会话列表在无 JOIN 的情况下返回 `lastPreview`；游标翻页无重复无遗漏
- [ ] `usage` 事件真实到达（验证 `stream-usage` 经 DeepSeek 兼容端点是否生效，实测为准）
- [ ] 长回答经网关不断流。若被网关超时掐断，需调 Nacos `gateway.yaml` 的 `response-timeout` ——**改共享配置前先征得用户同意**

---

## 九、已知边界与后接 RAG 的接缝

| 已知边界 | 现状与后果 |
|---|---|
| **单实例假设** | `ChatStreamRegistry` 是进程内 `ConcurrentHashMap`。多实例部署时 stop 可能打到没有该流的实例，走上表的孤儿分支：状态能收敛，但已产出内容会缺。真要多实例需 Redis pub/sub 或粘滞会话——本期不做，不留半成品代码 |
| **上下文只截断不摘要** | 超长会话丢最早上下文，跨主题的长对话会"失忆" |
| **token 计数近似** | cl100k_base 与 DeepSeek 分词不一致，预算取保守值 |
| **`streaming` 遗留需等 5 分钟** | 进程崩溃后该会话被并发检查阻塞到 `stale-stream-reap-minutes` 到期，或到下一次 #7 请求时被回收 |

**后接 RAG 与补前端时的改动面**（这是本期结构设计的验证标准）：

1. 建 `ai_message_citation` 表（DDL 已在 `rag-02-ingest-poll.md:144` 写好，注意那份计划里 Stage 4a 尚未执行过，得真建）。
2. 给 `ChatEvent` 加 `ThoughtStep` / `ThoughtEnd` 两个 record，`ChatStreamService` 在检索调用点前后各发一帧。
3. `ContextAssembler` 增加一段"检索命中注入 + `[n]` 编号"，落在 system 与历史之间。
4. 编排层新增 `sources` 参数处理（对应 PromptBar 的 `@` Sources 菜单，前端当前用的是内置演示值 `PromptBar.vue:102-115`），`RagChatContextService` 捕获 ES 故障转 `available=false`，降级为普通对话。
5. **附件**：#7 请求体当前**不受理** `attachments`（前端 `PromptBarSendDetail.attachments` 会带值上来，`Chat.vue:41-43` 的 `attach()` 现在恒返回 `[]`）。真做时建 `ai_attachment` 表 + `POST /ai/chat/attachments`，并在 #7 接受 `attachments: number[]`。
6. **前端对接**（本期不写）：`Chat.vue` 用 #1 的返回覆盖 `:models` / `:efforts`；新建 `src/api/chat.ts` 服务函数层（含 SSE 帧解析器，不能用 `EventSource`——它不支持 POST body 与自定义 `Authorization` 头）。

以上各项都只加代码，**不需要改 controller 签名、不需要改事件写出路径、不需要改本期两张表**。如果需要，说明本期边界划错了。

---

## 十、需要你点头才能动的动作（关卡）

| 关卡 | 内容 |
|---|---|
| G1 | 在主库 `ezpracticeexercises` 执行 2 张表的 DDL |
| G2 | 建独立测试 schema `ezpracticeexercises_chat_it` |
| G3 | 提供或创建一个 BCrypt 测试账号 |
| G4 | 修改 Nacos `gateway.yaml`（仅当长流被超时掐断时） |
| G5 | 修改 `AIModule/src/main/resources/application.yaml` 加 `chat.*` 与 `stream-usage` |

---

## 十一、旧文档处置

- 根目录《AI对话思考链路API设计文档.md》(2026-10-10)：**对话与数据模型部分以本 spec 为准**。它仍有价值的部分是第二节存储选型（MySQL vs PG，结论不变）和第八节网关注意点。它的第六节 HTTP 状态表与仓库实际行为矛盾（见 6.3），其"用 effort 反推模型"的方案本期不采用。不删除，标记为参考。
- `docs/superpowers/plans/2026-10-21-rag-05-chat.md`：其中 Task 6.0/6.1 的"4 张表 + 最小闭环"被本 spec 取代；Task 6.2–6.6（RAG 上下文、引用、降级、评测）仍是后接 RAG 的有用底稿，届时按本 spec 的接缝改写。该文件第 34 行"`ai_message_citation` 已在 Stage 4a 建好"的前提经实测为假。
