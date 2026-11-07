# AI 对话与思考链路（ThoughtLine）后端 API 设计文档

> 状态：设计稿，待评审 · 日期：2026-10-10
> 消费方：`EZ_frontend` → `src/views/AI/Chat.vue`、`src/components/AI/ThoughtLine.vue`
> 提供方：`AIModule`（端口 8105，`spring-boot-starter-web` + Spring AI 1.0.0-M7 + DeepSeek）

---

## 一、范围

**本文覆盖**：会话与消息的存储模型、控制面 REST 接口、流式问答的 SSE 事件契约、思考步骤（ThoughtLine 展开的 `steps`）的产生与落库、effort 档位与模型能力矩阵、网关与中间件联调约束、前后端契约对齐点。

**本文不覆盖**：RAG 向量检索的实现（见《企业知识库问答（RAG）→ 教材考点知识库.md》）、PromptBar 组件内部逻辑、附件文件系统的实际存储介质选型。

**已确认的设计基线**（评审时不再讨论）：

| 决策 | 取值 |
|---|---|
| Chat 页形态 | 消息流 + 每条助手回答顶部一条思考线 |
| `steps` 来源 | 后端推送**真实**思考事件，前端不编造文案 |
| 落库范围 | 会话 + 消息 + 思考步骤 + reasoning 原文 + 耗时 + token 用量 |
| 入口与传输 | Gateway(8100) + JWT + SSE |
| 存储引擎 | MySQL 8（理由见第二节） |

必须复用的既有约定，出处如下，**新接口不得另立标准**：

| 既有事实 | 出处 |
|---|---|
| 网关 8100，`/ai/**` 路由配置在 Nacos `gateway-module.yaml` | `GatewayModule/src/main/resources/application.yaml` |
| `Authorization: Bearer` 验签后剥离原头，注入 `X-User-Name` 给下游 | `JwtAuthGlobalFilter.java:78-81` |
| 统一返回体 `Result{code, msg, data}`，`code=1` 成功 / `0` 失败 | `UserModule/pojo/Result.java` |
| 主键策略 `IdType.AUTO`，表名 `users_dev` / `qb_question` | `Users.java:15-21` |
| AIModule 已配 DeepSeek `base-url`，默认模型 `deepseek-chat` | `AIModule/src/main/resources/application.yaml` |

---

## 二、存储选型：用 MySQL 8，不要换 PostgreSQL

### 2.1 结论

**沿用 MySQL 8。** 聊天数据（会话、消息、思考步骤）是教科书式的 OLTP 关系数据——强主键、按会话分页、行级更新、事务写入，MySQL 与 PostgreSQL 在这一层能力上没有实质差距。而换库的成本在这套工程里是**跨切面**的：7 个 Maven 模块清一色 `mysql-connector-j` + MyBatis-Plus，AIModule 还挂着 Seata 分布式事务和 Nacos 配置中心，单独把 AIModule 的数据源改成 PostgreSQL 会让连接池参数、Seata undo_log 表、`application.yaml` 的 `datasource` 段、DBA 备份链路、本地开发环境**全部二分为两套**。收益不足以支付这个代价。

### 2.2 逐项对比

| 维度 | MySQL 8 | PostgreSQL 16 | 本项目权重 |
|---|---|---|---|
| 与现有 MyBatis-Plus / 分页插件适配 | 原生 | 需换 `DbType`、方言、分页拦截器 | **高** |
| Seata 分布式事务 | undo_log 已跑通 | 需重新验证 jdbc 驱动与锁机制 | **高** |
| 团队与云环境（RDS、备份、监控） | 现成 | 需新增一套运维 | **高** |
| 长文本消息正文 | `MEDIUMTEXT` 16MB | `text` + TOAST 自动压缩 | 中，两者够用 |
| JSON 元数据 | `JSON` 类型 + 函数索引 | `JSONB` + GIN，索引能力更强 | **低**——本设计只把 JSON 当"配置快照"存，不参与条件查询 |
| 中文全文检索 | `ngram` 分词器内置 | 需 `zhparser` 扩展（装编译都麻烦） | 中，MySQL 反而省事 |
| 向量检索 | 9.0 的 `VECTOR` 尚不成熟，无 HNSW | `pgvector` 成熟 | **高，但不该由业务主库承担** |
| 高并发写 + 长事务混合 | MVCC + redo，够用 | 无回滚段膨胀，vacuum 需运维 | 中 |

PG 唯一真正的胜出项是 pgvector。但那是 RAG 子系统的诉求，把它塞进业务主库等于让刷题、用户、鉴权模块共同承担向量负载的爆炸半径——正确做法是向量索引外置（Milvus / Elasticsearch / 或一个独立的 PG+pgvector 实例），业务主库只存 `doc_id` 与文本。

### 2.3 什么情况下这份结论要重新评估

出现以下**任一**信号，回到本节重议：

1. 确定要把向量索引放进业务主库，且不想引入独立检索组件；
2. 消息元数据从"配置快照"变成**高频查询条件**（例如"检索所有 effort=Max 且引用过知识库 3 篇以上的回答"），此时 JSONB + GIN 的优势才真正兑现；
3. 需要会话级的逻辑复制 / 时序扩展 / 表分区之外的复杂约束（排他约束、部分索引）。

---

## 三、数据库设计

### 3.1 通用约定

- 引擎 `InnoDB`，字符集 `utf8mb4`，排序规则 `utf8mb4_0900_ai_ci`（MySQL 5.7 环境降级为 `utf8mb4_general_ci`）。
- 表名前缀 `ai_`，与 `qb_question` 的模块前缀风格一致。
- 主键 `BIGINT UNSIGNED AUTO_INCREMENT` + `IdType.AUTO`。**自增 ID 远小于 2^53，前端 JSON 反序列化为 number 无精度丢失**，不需要雪花 ID 或字符串化。
- 时间统一 `DATETIME(3)`（毫秒精度，思考步骤耗时统计需要）。
- **不建物理外键**：会话-消息一致性放在应用层与索引上，理由是与 Seata / 未来分库分表兼容，也与 `qb_*` 现有表风格一致。
- 用户身份直接用 JWT 的 subject，字段名 `user_name VARCHAR(64)`，来自网关注入的 `X-User-Name`。
- 删除一律软删（`status` 位），流式内容不可逆，误删无法恢复。

### 3.2 DDL

```sql
-- 会话
CREATE TABLE ai_conversation (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_name         VARCHAR(64)  NOT NULL COMMENT 'JWT subject，网关 X-User-Name',
  title             VARCHAR(120) NOT NULL DEFAULT '新对话',
  model_key         VARCHAR(64)  NOT NULL DEFAULT 'deepseek-chat',
  effort            VARCHAR(16)  NOT NULL DEFAULT 'Medium' COMMENT 'Low/Medium/High/Extra/Max',
  last_message_id   BIGINT UNSIGNED NULL COMMENT '列表页预览用，避免子查询',
  message_count     INT UNSIGNED NOT NULL DEFAULT 0,
  total_tokens      BIGINT UNSIGNED NOT NULL DEFAULT 0,
  status            TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0已删除',
  create_time       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_list (user_name, status, update_time, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'AI 会话';


-- 消息
CREATE TABLE ai_message (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  conversation_id   BIGINT UNSIGNED NOT NULL,
  role              VARCHAR(16)  NOT NULL COMMENT 'user / assistant / system',
  content           MEDIUMTEXT   NOT NULL COMMENT '正文，Markdown 源文',
  status            VARCHAR(16)  NOT NULL DEFAULT 'done'
                    COMMENT 'pending/streaming/done/failed/truncated，与 SSE 生命周期一致',
  stop_reason       VARCHAR(24)  NULL COMMENT 'done 时的 finishReason：stopped/length/upstream',
  model_key         VARCHAR(64)  NULL,
  effort            VARCHAR(16)  NULL,
  thought_seconds   DECIMAL(5,1) NULL COMMENT '思考耗时，直接喂 ThoughtLine 的 elapsed',
  reasoning_text    MEDIUMTEXT   NULL COMMENT '上游 reasoning_content 原文，超 64KB 截断',
  prompt_tokens     INT UNSIGNED NULL,
  completion_tokens INT UNSIGNED NULL,
  reasoning_tokens  INT UNSIGNED NULL,
  meta_json         JSON         NULL COMMENT '请求参数快照：sources/attachments/温度',
  client_msg_id     VARCHAR(40)  NULL COMMENT '前端生成，重试幂等键',
  error_msg         VARCHAR(255) NULL,
  create_time       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_conv_client (conversation_id, client_msg_id),
  KEY idx_conv_page (conversation_id, id),
  FULLTEXT KEY ft_content (content) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'AI 消息';


-- 思考步骤（ThoughtLine 展开区的数据源）
CREATE TABLE ai_thought_step (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  message_id        BIGINT UNSIGNED NOT NULL COMMENT '归属的 assistant 消息',
  seq               SMALLINT UNSIGNED NOT NULL COMMENT '从 1 递增，前端按此排序',
  kind              VARCHAR(16)  NOT NULL COMMENT 'plan/retrieval/tool/reasoning',
  title             VARCHAR(64)  NOT NULL COMMENT '短标签，硬上限 24 字符，见 9.3',
  detail            VARCHAR(512) NULL COMMENT '仅详情用途，不进 steps 渲染',
  ref_count         SMALLINT UNSIGNED NULL COMMENT '检索命中条数等计数',
  elapsed_ms        INT UNSIGNED NULL COMMENT '该步耗时',
  create_time       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_msg_seq (message_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '思考步骤轨迹';


-- 附件（PromptBar 的 onAttach 数据源；本期只登记元数据，不解析文件内容）
CREATE TABLE ai_attachment (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_name         VARCHAR(64)  NOT NULL,
  conversation_id   BIGINT UNSIGNED NULL,
  name              VARCHAR(255) NOT NULL,
  mime              VARCHAR(128) NOT NULL,
  size              INT UNSIGNED NOT NULL,
  storage_key       VARCHAR(255) NOT NULL COMMENT '对象存储 key，不存二进制',
  create_time       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user (user_name, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'AI 会话附件';
```

`ngram` 全文索引需要确认 `ngram_token_size`（默认 2）与业务匹配，且建 FULLTEXT 后无法直接 `ALTER` 分词长度，需 `DROP` 重建。会话内消息检索（"在这场对话里搜"）走 `idx_conv_page` 过滤后再全文匹配即可，数据量级不需要外部搜索引擎。

### 3.3 四条刻意的设计取舍

1. **`thought_seconds` 单列，不塞进 JSON**：思考线的耗时是每条历史消息都要渲染的字段，放 JSON 会让列表查询每行都解析一遍。
2. **`steps` 独立成表，不存 JSON 数组**：步骤要能被追加写（流式过程中逐条 INSERT），且将来做"步骤级耗时分析/埋点"时可直接聚合。一行一步换来的是 `uk_msg_seq` 天然幂等——重连后重复事件按 `seq` 覆盖即可。
3. **不做逐 token 落库**：`delta` 事件在服务端累积到缓冲区，只在 `done` / `error` / `stop` 时一次性写 `content`。每 token 一次 UPDATE 会把一次问答放大成上千次行锁更新，是这套设计里最容易踩的性能坑。
4. **`reasoning_text` 存但截断**：满足"重进会话能展开完整思考"的需求；超 64KB 截断并在 `meta_json` 打 `reasoningTruncated` 标记，避免单行过大拖垮 `idx_conv_page` 的页读取。

---

## 四、接口总览与通用约定

Base：`http://<gateway>:8100`。AIModule 直连调试时 `http://127.0.0.1:8105`，同样路径。

| # | 方法 | 路径 | 说明 | 返回类型 |
|---|---|---|---|---|
| 1 | GET | `/ai/chat/models` | 模型与 effort 能力矩阵，喂 PromptBar | `Result` |
| 2 | POST | `/ai/chat/conversations` | 创建会话 | `Result` |
| 3 | GET | `/ai/chat/conversations` | 会话列表（游标分页） | `Result` |
| 4 | PATCH | `/ai/chat/conversations/{id}` | 重命名 / 置顶 | `Result` |
| 5 | DELETE | `/ai/chat/conversations/{id}` | 软删会话 | `Result` |
| 6 | GET | `/ai/chat/conversations/{id}/messages` | 历史消息（含思考步骤） | `Result` |
| 7 | POST | `/ai/chat/completions/stream` | **流式问答主接口** | `text/event-stream` |
| 8 | POST | `/ai/chat/messages/{id}/stop` | 中断生成 | `Result` |
| 9 | POST | `/ai/chat/attachments` | 上传附件 | `Result` |

通用约定：

- **鉴权**：`Authorization: Bearer <jwt>`。控制器用 `@RequestHeader("X-User-Name") String username` 取当前用户，**不得**从 Authorization 解析（网关已剥离）。
- **无权限资源**：会话不属于该用户时统一返回 `code=0` + `msg="会话不存在"`，不区分"无权限"与"不存在"，避免会话 ID 枚举。
- **返回体**：控制面沿用 `Result`，不改动这个类。需要机器判别错误种类时，把结构化码放进 `data`：`{"code":0,"msg":"模型服务暂时不可用","data":{"errorCode":"UPSTREAM_MODEL_ERROR","retryable":true}}`。
- **SSE 端点（#7）不包 `Result`**，见第六节。
- **时间**：AIModule 注册一个全局 `LocalDateTime` → epoch 毫秒 number 的 Jackson serializer（`DateSerializer` + 固定 `Asia/Shanghai` 时区），VO 里 `createTime` 一律是 number。理由：前端 `src/api/image.ts` 已有 `createdAt: number` 约定，若这里输出 `"2026-10-10T09:00:00"` 字符串，`useChat` 就要多做一层解析且各端时区行为不一致。
- **分页**：一律游标，不用 offset。会话列表游标 = `(update_time, id)` 降序的 opaque base64；消息列表游标 = `beforeId`。深翻 `LIMIT 10000` 这种写法在本项目禁止。
- **幂等**：写请求带 `clientMsgId`（前端生成 `nanoid`），命中 `uk_conv_client` 时直接返回既有记录。

---

## 五、控制面接口详解

### 1. 获取模型能力

```
GET /ai/chat/models
```
```json
{ "code": 1, "msg": "success", "data": {
  "defaultModel": "deepseek-chat", "defaultEffort": "Medium",
  "models": [
    { "key": "deepseek-chat",     "name": "deepseek-chat",     "supportsThinking": false, "efforts": ["Low","Medium"],            "maxContextTokens": 65536 },
    { "key": "deepseek-reasoner", "name": "deepseek-reasoner", "supportsThinking": true,  "efforts": ["High","Extra","Max"],       "maxContextTokens": 65536 }
  ]
}}
```
前端把 `efforts` 映射到 PromptBar 的 `efforts: string[]` prop，`models` 列表用于把 `meta.modelKey` 显示成人类可读名称（模型选择器本身只读，见第七节）。

### 2. 创建会话

```
POST /ai/chat/conversations
{ "title": "帮我改简历", "effort": "High" }
```
`title` 可省略，服务端取首条用户消息前 24 字符自动生成；`effort` 省略则用默认值 `Medium`。请求体不含 `model`——上游模型由 effort 解析（第七节），服务端把解析结果写入 `ai_conversation.model_key`。**该接口是"新对话"按钮的实现**，前端不要在发送第一条消息时才创建，否则 `stream` 事件里要承担建会话的失败分支。

```json
{ "code": 1, "data": { "id": 18, "title": "帮我改简历", "modelKey": "deepseek-reasoner",
                        "effort": "Medium", "createTime": 1760000000000 } }
```

### 3. 会话列表

```
GET /ai/chat/conversations?cursor=eyJ1dHMiOi...&limit=20
```
```json
{ "code": 1, "data": { "items": [
    { "id": 18, "title": "帮我改简历", "messageCount": 6, "updateTime": 1760000900000,
      "lastPreview": "把项目经历改成 STAR 结构……" }
  ], "nextCursor": "eyJ1dHMiOi..." } }
```
`items[].lastPreview` 由 `ai_conversation.last_message_id` 关联取，`limit` 上限 50。

### 4/5. 重命名与删除

```
PATCH  /ai/chat/conversations/18   { "title": "简历 v2" }
DELETE /ai/chat/conversations/18   →  Result.success()
```
删除只置 `status=0`，消息与思考步骤保留。置顶本期不做：它要求 `ai_conversation` 加 `pinned TINYINT` 并把 `idx_user_list` 改成 `(user_name, status, pinned, update_time, id)`，在真实诉求出现前不值得动索引结构。

### 6. 历史消息（思考线复原的数据来源）

```
GET /ai/chat/conversations/18/messages?beforeId=1050&limit=30
```
```json
{ "code": 1, "data": { "hasMore": true, "items": [
  { "id": 1049, "conversationId": 18, "role": "user",
    "content": "帮我把这段改成 STAR 结构", "status": "done", "createTime": 1760000800000 },
  { "id": 1050, "conversationId": 18, "role": "assistant",
    "content": "……", "status": "done", "modelKey": "deepseek-reasoner", "effort": "High",
    "thoughtSeconds": 8.4,
    "thought": { "steps": [
        { "seq": 1, "kind": "plan",     "title": "拆解简历结构" },
        { "seq": 2, "kind": "retrieval","title": "检索范例 · 命中 4 条" },
        { "seq": 3, "kind": "reasoning","title": "重写项目经历" }
      ] },
    "reasoningAvailable": true,
    "usage": { "promptTokens": 812, "completionTokens": 1640, "reasoningTokens": 512 },
    "createTime": 1760000809000 }
]}}
```
`items` 按 `id` 升序返回（前端直接 append），游标 `beforeId` 向前翻页。`reasoningAvailable=true` 时前端才在展开区显示"查看完整推理"入口。

### 8. 中断生成

```
POST /ai/chat/messages/1050/stop
```
服务端动作：取消上游订阅 → 把已产出的部分内容写入 `content` → `status='truncated'`、`stop_reason='stopped'` → 返回 `Result.success()`。**已产出内容保留**，不丢弃。前端不等这个接口返回就先把 UI 切到终止态（见 6.6）。

### 9. 上传附件

```
POST /ai/chat/attachments   (multipart/form-data, file)
→ { "code": 1, "data": { "id": 7, "name": "resume.docx", "mime": "...", "size": 48211 } }
```
返回的 `id`（number）在 #7 请求体的 `attachments` 数组中引用。本期服务端只登记元数据与 `storage_key`，**不解析文件内容**——`retrieval` 类思考步骤对附件只报"读取附件 xxx"这一事实，不谎称已理解正文。

---

## 六、流式问答接口与 SSE 事件契约

### 6.1 请求

```
POST /ai/chat/completions/stream
Authorization: Bearer <jwt>
Content-Type: application/json
Accept: text/event-stream
```
```json
{
  "conversationId": 18,
  "content": "帮我把这段改成 STAR 结构",
  "effort": "High",
  "sources": ["knowledge_base"],
  "attachments": [7],
  "clientMsgId": "cnd_9f3k21"
}
```
- `conversationId` 省略时服务端新建会话（并在 `meta` 里回传 ID，前端把 URL 补上）。
- `effort` **决定实际使用的上游模型**（见第七节），请求体不接受 `model` 字段；`meta.modelKey` 是最终事实，前端 PromptBar 的模型选择器要据此回显，不允许前端各自推断。
- `sources` 决定服务端会发哪些 `retrieval` 类步骤——这是 PromptBar 数据源菜单与思考线之间的唯一桥梁。
- `attachments` 为 number 数组（`ai_attachment.id`）。PromptBar 的 `onAttach` 返回 `string[]`，由 `useChat` 负责 `Number()` 转换，不在 API 层兼容两种类型。

响应头：
```
Content-Type: text/event-stream;charset=UTF-8
Cache-Control: no-cache, no-transform
X-Accel-Buffering: no
```

### 6.2 事件类型

| 事件 | 频次 | payload | 用途 |
|---|---|---|---|
| `meta` | 1，最先 | `{conversationId, messageId, modelKey, effort, supportsThinking}` | 前端拿到 `messageId` 才能调 `/stop` |
| `thought_step` | 0..n | `{seq, kind, title, refCount?, elapsedMs?}` | append 到 ThoughtLine 的 `steps` |
| `thought_end` | 0..1 | `{seconds}` | 置 `settled=true`，写入 `elapsed` |
| `delta` | 0..n | `{text}` | 追加正文 |
| `usage` | 0..1 | `{promptTokens, completionTokens, reasoningTokens}` | 计费与统计 |
| `done` | 1，最后 | `{finishReason}` `stopped/length/truncated/upstream` | 关流 |
| `error` | 0..1 | `{code, errorCode, msg, retryable}` | 见 6.5 |
| `ping` | 每 15s | `{t}` | 防网关/代理空闲断流 |

**`kind` 四种取值与前端图标语义**：`plan`（拆解任务）、`retrieval`（检索/读附件，带 `refCount`）、`tool`（调用外部工具/函数）、`reasoning`（模型推理摘要）。

### 6.3 时序

```
meta → thought_step* → thought_end → delta* → usage → done
                   ↑ 可与 delta 交错（模型边想边说时），交错顺序见 6.4
```
无思考能力的模型（`supportsThinking=false`）不发 `thought_step` / `thought_end`，前端此时**不渲染思考线**，而不是渲染一条空的。

示例帧（`data` 必须是单行 JSON，帧间空行分隔）：
```
event: meta
data: {"conversationId":18,"messageId":1051,"modelKey":"deepseek-reasoner","effort":"High","supportsThinking":true}

event: thought_step
data: {"seq":1,"kind":"plan","title":"拆解 STAR 四要素"}

event: thought_step
data: {"seq":2,"kind":"retrieval","title":"读取附件 resume.docx","refCount":1}

event: thought_end
data: {"seconds":8.4}

event: delta
data: {"text":"## 经"}

: keep-alive
event: ping
data: {"t":1760000812000}

event: usage
data: {"promptTokens":812,"completionTokens":1640,"reasoningTokens":512}

event: done
data: {"finishReason":"stopped"}
```

### 6.4 步骤从哪里来（本设计的实质内容）

后端不能"演"思考。四类步骤的产生来源明确如下：

- `plan` / `tool` / `retrieval`：由**服务端编排代码**发出。知识库检索、附件解析、函数调用这些动作在业务层是真实存在的调用点，就在调用前后各发一帧，`elapsedMs` 用实测差值。
- `reasoning`：来自 `reasoning_content` 的**摘要而非原文**。DeepSeek 的 reasoning 流是连续 token，直接吐进去就是几百个碎片。服务端按句边界（。！？\n）缓冲，攒够一个可读短句即作为一步发出，`title` 截断到 24 字符、超出加省略号，完整句进 `detail`。原文整体落 `ai_message.reasoning_text`。
- 若上游既无 reasoning、业务层又没有任何检索动作，就**只发 `meta → delta → done`**，思考线不出现。宁缺毋滥是这条契约的硬要求。

### 6.5 错误处理

| 场景 | HTTP | SSE | 前端表现 |
|---|---|---|---|
| 参数非法 | 400 | 不建流 | `Result{code:0}` |
| 鉴权失败 | 401 | 不建流 | 网关直出，跳登录 |
| 会话不属于本人 | 404 | 不建流 | 统一 `msg="会话不存在"` |
| 上游模型异常 / 超时 | 200 | `error` + `done` | 思考线 `doneLabel="思考中断"`，正文保留已产出部分 |
| 客户端断连 | — | 无 | 服务端 `onTimeout/onError` 回调落库 `status='truncated'` |

`retryable=true` 时前端在气泡下方给"重试"，重试复用同一 `clientMsgId`，靠 `uk_conv_client` 保证不产生第二条用户消息。

### 6.6 前端实现约束（必须写进对接说明）

1. **不能用 `EventSource`**：它不支持 POST body，也不支持自定义 `Authorization` 头。前端用 `fetch` + `response.body.getReader()` 手工解帧，按 `\n\n` 切帧、解析 `event:` / `data:` 行，并处理跨 chunk 半帧。`src/api/chat.ts` 提供这一个解析器，`useChat` 不感知字节流。
2. **停止要两段式**：点停止时**先本地 `done`**（立即关闭 reader、把该消息标 `settled` 并冻结已渲染内容），再异步 `POST /stop`。反过来等接口返回会卡在网关往返上，用户 perceive 到的延迟就是"停止按钮没反应"。
3. **重连不续流**：v1 不支持 `Last-Event-ID`。网络断开后该条消息直接落 `failed`，UI 给"重试"，不做半截续传。

---

## 七、effort 档位与模型能力矩阵

PromptBar 暴露 `Low / Medium / High / Extra / Max` 五档，但 `application.yaml` 里配死的 `deepseek-chat` **不返回 `reasoning_content`**——若不做映射，用户选 High 会以为能拿到真实推理步骤，实际一条 `reasoning` 事件都不会有。

映射规则（服务端 `EffortResolver` 负责，前端只传档位不选模型）：

| effort | 上游模型 | thinking | 说明 |
|---|---|---|---|
| Low / Medium | `deepseek-chat` | 关闭 | 不发 `thought_step`，思考线不出现 |
| High / Extra / Max | `deepseek-reasoner` | 开启 | 发 `reasoning` 类步骤 |

两点必须写清：DeepSeek 没有 OpenAI 式的 `thinking_budget` 数值旋钮，所以 **High/Extra/Max 三档在上游是同一个模型**，差异只能体现在服务端参数（温度、`maxTokens`、是否注入检索增强上下文）。如果将来要求三档有真实差异，接入带 budget 参数的模型（如 Qwen 的 `thinking.budget`），而不是在文档里假装它们不同。

`GET /ai/chat/models` 是这张矩阵的**唯一真相来源**，前端不硬编码档位归属。由于模型由 effort 反推，PromptBar 的模型选择器在本设计中**不做独立输入**：它只回显当前档位解析出的 `modelKey`（来自 `meta` 事件），选择器置为只读或由档位菜单替代。若产品坚持让用户直接选模型，则本节映射要改成 `(model, effort) → 上游参数` 二维表，并让 `#2 创建会话` 与 `#7` 都接受 `model` 字段——那是一个更大的改动，需要重新评审。

---

## 八、网关与中间件注意点

| 项 | 要求 | 不处理的后果 |
|---|---|---|
| Nacos 路由 | `gateway-module.yaml` 增加 `Path=/ai/**` → `lb://AIModuleServer` | 前端 404（现有 `/ai/testGateway` 已通说明路由部分已配，需确认覆盖到 `/ai/chat/**`） |
| 响应超时 | 该路由 `response-timeout` 置空或 ≥300s；网关全局默认值会掐断长思考 | 思考到一半连接被杀，`status` 卡在 `streaming` |
| 缓冲 | 网关侧不聚合响应体（Spring Cloud Gateway 默认流式转发），Nginx 需 `proxy_buffering off` + `X-Accel-Buffering: no` | delta 整段憋到最后一次性到达，流式效果全废 |
| 空闲断开 | 15s `ping` 心跳（见 6.2） | 代理空闲超时断连 |
| Sentinel | `/ai/chat/completions/stream` 单独配规则，按**并发连接数**而非 QPS 限流 | 长连接被 QPS 阈值误伤，或单用户开多路流打满上游配额 |
| Seata | **流式过程中不得持有 `@GlobalTransactional`**。落库拆成三段短事务：①`meta` 前写用户消息 + assistant 占位行；②`thought_step` 每条独立提交；③`done`/`error` 时写正文与 usage | 一次回答挂着一个跨分钟的全局事务，undo_log 与锁等待都会出问题 |
| MVC 实现 | AIModule 是 `spring-boot-starter-web`，用 `SseEmitter` 桥接 `ChatClient.prompt().stream()` 的 `Flux`；`emitter` 超时设为 300s，配独立线程池并限制最大并发 emitter 数 | 上游 `Flux` 默认跑在公共池上，长流耗尽 Web 线程 |

---

## 九、前端契约对齐

### 9.1 文件

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/api/chat.ts` | 新增 | 类型 + service 函数（先 mock 函数体，签名按本文档），含 SSE 帧解析器 |
| `src/composables/useChat.ts` | 新增 | 模块级单例状态机：`messages / sending / activeConversationId / send / stop / loadConversation` |
| `src/components/AI/ChatMessage.vue` | 新增 | user / assistant 气泡，assistant 顶部渲染 ThoughtLine |
| `src/views/AI/Chat.vue` | 修改 | 空态 greeting 居中 ↔ 有消息时列表模式，PromptBar 吸底 |

状态归属：**`useChat` 持状态机，ThoughtLine 保持无状态展示组件**。SSE 事件写进 `msg.thought = {steps, settled, seconds}`，`ChatMessage.vue` 只做 prop 映射。

### 9.2 prop 映射

| ThoughtLine prop | 取值 |
|---|---|
| `working` | `thought.settled === false` |
| `steps` | `thought.steps.map(s => s.title)` |
| `label` / `doneLabel` | `'思考中…'` / `'已思考'`；中断消息用 `'思考中断'` |
| `elapsed` | 进行中**传 `undefined`**；`thought_end` 后传 `thoughtSeconds` |
| `collapsible` / `collapseOnSettle` | `true` / `true` |
| `color` / `fontSize` / `glyph` | `useTheme` 派生 / `13~14` / `'sparkle'` |
| `@settle` | 不接，耗时以后端 `thought_end.seconds` 为准 |

历史消息复原无需特殊分支：`working=false` + 已加载的 `steps`，`collapseOnSettle` 自动给折叠态，点开即见完整步骤。

### 9.3 三条由组件源码决定的硬约束

1. **`title` ≤ 24 字符**。steps 容器是 `w-max` + `whitespace-nowrap`（`ThoughtLine.vue:350`），长句会横向撑破气泡。服务端截断，`detail` 不进 `steps`。
2. **进行中绝不传 `elapsed`**。组件的计时 watch 在 `props.elapsed != null` 时直接 paint 并 return（`ThoughtLine.vue:190`），本地 10Hz 计时器根本不启动，表现为时间卡在 0.0s。
3. **无思考能力时不渲染思考线**，而不是渲染一条空的。组件的 `hasTrace` 只控制展开区，空的头部仍会占据一行视觉重量。

---

## 十、实现顺序与验收清单

### 10.1 建议顺序

1. 后端：4 张表 + MyBatis-Plus entity/mapper；`Result` 复用不动。
2. 后端：控制面 #1/#2/#3/#5/#6（不含流式），前端即可用 mock 层对照真实契约。
3. 后端：#7 `SseEmitter` 桥接 `ChatClient.stream()`，先只发 `meta/delta/usage/done`。
4. 后端：编排层插 `thought_step` 埋点（检索与附件解析的调用点），接 `reasoning_content` 摘要切句。
5. 后端：#8 stop + 断连回写 `truncated`。
6. 前端：`api/chat.ts` 换成真实实现（签名不变），`useChat` + `ChatMessage.vue` + `Chat.vue` 改造。
7. 联调：网关超时/缓冲、Sentinel 规则、长思考稳定性。

按此顺序，第 6 步之前前端可以完全脱离后端进度推进——与项目现有"UI 先行 + 占位 API 层"的做法一致。

### 10.2 验收清单

- [ ] `deepseek-chat`（Low/Medium）请求全程不出现 `thought_step`，前端不渲染思考线
- [ ] `deepseek-reasoner`（High 及以上）思考线展开区步骤 ≥1 条，且每条 `title` ≤24 字符、无横向溢出
- [ ] 流式过程中计时器走秒，`thought_end` 到达后数值平滑切到后端 `seconds` 且自动折叠
- [ ] 刷新页面重进会话，历史消息思考线呈折叠态，展开后步骤与中断前一致
- [ ] 点停止：UI 立即冻结，已产出的正文与步骤已落库，`status='truncated'`
- [ ] 上游报错：思考线显示 `'思考中断'`，无成功勾；`retryable=true` 时重试不产生重复用户消息
- [ ] 64K token 长回答全程不断流（网关超时/缓冲配置生效）
- [ ] 单条消息 token 落库次数为常数级（正文一次写、步骤每步一次），无逐 token UPDATE
- [ ] 不属于当前用户的 `conversationId` 返回统一的"会话不存在"
- [ ] MySQL 与 PostgreSQL 的选择在评审会上被确认，且 RAG 向量方案确认为外置引擎

---

## 附：与现有 `GET /ai/chat` 的关系

`AiChatController.chat()` 是 Spring AI 的最小连通验证（同步返回纯文本 String，不套 `Result`），保留作健康检查与上游通路验证，前端不调用。本文 #7 端点新建于 `AiChatStreamController`，不改造旧方法，避免把已验证的通路推倒重来。
