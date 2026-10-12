# 教材考点知识库 RAG 后端设计（Elasticsearch 9.5.4）

> 状态：设计稿，待评审 · 日期：2026-10-12
> 提供方：`AIModule`（端口 8105）新增 `org.leon.aimodule.rag` 包
> 消费方：`EZ_frontend` → `src/views/AI/Chat.vue`（`sources` 勾选）、知识库管理页、语义搜题页、录题审核页
> 前置阅读：《AI对话思考链路API设计文档.md》（本文档接续它的 `sources` 字段与 `thought_step` 契约，不重复其内容）
> 需求来源：《企业知识库问答（RAG）→ 教材考点知识库.md》的场景 1、2、6

本文是**实现方法与所用技术栈的完整说明**，每一处选型都写出被否掉的方案和原因，便于后续独立评审和复习。带 `【为什么这么做】` 的段落是设计依据，不带的是既定约定。

---

## 0. 范围与基线

### 0.1 本文覆盖

三条流水线共用一套检索内核：

1. **知识库文档 RAG**：上传教材/讲义/考纲/真题解析 → 解析 → 切片 → 向量化 → ES 入库 → 对话时检索并给出知识点出处（场景 1）。
2. **题目语义搜索**：`qb_question` 建向量索引，自然语言描述也能搜到相似题（场景 6）。
3. **上传文件 → 自动录题**：解析试卷 → LLM 结构化抽取 → 草稿审核 → 写入 `qb_question` → 回头触发题目索引（场景 2）。

以及：ES 索引 mapping、MySQL 表与入库状态机、**异步派发（RabbitMQ）**、REST 接口契约、`sources` 在流式问答中的语义、实现顺序、测试方案、风险清单。

### 0.2 本文不覆盖

思考线（ThoughtLine）本身的事件契约、effort 档位映射、会话与消息存储——这些在《AI对话思考链路API设计文档》里已定稿，本文只与之对接。

### 0.3 已确认的设计基线（评审时不再讨论）

| 决策 | 取值 | 出处 |
|---|---|---|
| 检索引擎 | Elasticsearch **9.5.4**，本机 zip 版，已启动 | 用户确认 + 附录 A 实测 |
| 向量模型 | 阿里云百炼 `text-embedding-v3`，**1024 维** | 用户选择 |
| 中文分词 | IK（`ik_max_word` 索引 / `ik_smart` 查询），**`cjk` 兜底** | 附录 A：当前节点未装任何插件 |
| 对话侧模型 | DeepSeek（现有配置，不提供 embedding） | `AIModule/application.yaml:15-22` |
| 权限模型 | 两级：`official` 全局库 + `personal` 个人库 | 用户选择 |
| 文档解析 | Apache Tika，抽不出文本的扫描件直接拒收 | 用户选择 |
| 业务主库 | MySQL 8 不变，ES 只承担检索 | 《AI对话思考链路》第二节结论 |
| 异步派发 | RabbitMQ 作为**可选派发层**，MySQL 状态机不变；本机尚未安装，故 `poll` 模式为默认 | 用户提出 + 附录 A 实测 |

### 0.4 必须复用的既有约定

| 既有事实 | 出处 | 本文如何遵守 |
|---|---|---|
| 网关 8100，`/ai/**` 路由在 Nacos `gateway-module.yaml` | `GatewayModule/application.yaml` | 新接口一律 `/ai/` 前缀，不新增路由段 |
| JWT 验签后剥离 `Authorization`，注入 `X-User-Name` | `JwtAuthGlobalFilter.java:75-80` | 身份只从 `@RequestHeader("X-User-Name")` 取 |
| 统一返回体 `Result{code,msg,data}`，`code=1` 成功 | `UserModule/pojo/Result.java:11-37` | 控制面沿用；泛型版新增到 `CommonJwt`（见 5.1） |
| 主键 `IdType.AUTO`，表名模块前缀 | `Users.java:15-21`、`QbQuestion.java:15-21` | 全部 `ai_` 前缀 + `BIGINT UNSIGNED AUTO_INCREMENT` |
| 时间列 `DATETIME(3)`，`create_time`/`update_time` | 《AI对话思考链路》3.1 | `ai_*` 表内部一致；跨模块读 `qb_question` 时显式映射 `created_at` |
| 不建物理外键，软删 | 同上 | 一致 |

### 0.5 与 `ai_attachment` 的关系（评审必问）

《AI对话思考链路》定义了 `ai_attachment`（PromptBar 会话附件，只登记元数据不解析）。本文的 `ai_kb_doc` 是知识库文档（解析并入库）。**两张表不合并**：附件属于单次会话、生命周期跟随对话、内容不进检索索引；知识库文档属于用户资产、生命周期独立、必须可被检索。语义不同的两类资源共用一张表会让"删除会话是否删除文件"这类问题无法回答。

---

## 1. 技术栈清单

| 组件 | 版本 | 作用 | 为什么是它 |
|---|---|---|---|
| `co.elastic.clients:elasticsearch-java` | **9.5.4** | ES 官方 Java 客户端，建索引 + 混合检索 + bulk | 见 1.1 |
| `co.elastic.clients:elasticsearch-rest5-client` | 9.5.4（随上者传递引入） | HttpClient 5 传输层 | 见 1.2 |
| `spring-ai-starter-model-openai` | 1.0.0-M7（已存在） | 复用其 `EmbeddingModel` 调百炼 | 见 1.3 |
| `spring-ai-rag` / `spring-ai-advisors-vector-store` | 1.0.0-M7，**可选** | 对话侧 prompt 注入的 advisor 链 | 见 1.4 |
| `tika-core` + `tika-parsers-standard-package` | 3.x | PDF/DOCX 文本抽取，含逐页分页 | 见 1.5 |
| `com.knuddels:jtokkit` | 1.x | BPE token 计数，切片预算靠它 | 见 1.6 |
| `spring-boot-starter-validation` | 随 Boot | 让 `@Valid` 真正生效 | 见 5.1 |
| `spring-boot-starter-amqp` | 随 Boot | 入库/抽取/索引任务的异步派发 | 见 2.5（可选，`rag.async=poll` 时不需要） |
| JUnit 5 + Mockito | 随 `spring-boot-starter-test` | 单测 | 已有 |

### 1.1 为什么自建检索内核，不用 Spring AI 的 `VectorStore`

`spring-ai-starter-vector-store-elasticsearch:1.0.0-M7` 在中央仓库确实存在（已核实），技术上不需要升级 Spring AI 或 Boot 就能用。但否掉：

- M7 的 `ElasticsearchVectorStore` 是**纯 kNN**，自建索引时 content 字段用默认 standard analyzer，中文按单字切（附录 A 实测：`快速排序时间复杂度` → 9 个单字 token），**BM25 分量基本不存在**。而选 ES 而不是 Milvus 的唯一理由就是"全文 + 语义二合一"。
- 带 `semantic_text` + RRF 的是另一个类 `ElasticsearchAiSearchVectorStore`，它要求 ES 侧部署 ELSER 推理模型（ML node + 许可证），与"百炼 embedding"是互斥路线。
- 两级权限要表达成"官方库 **OR** 我的库"这种 `should` 语义，`filterExpression` 的转换器表达不出来。

【为什么这么做】mapping 控制权是这套设计的核心资产——IK analyzer、`dense_vector` 量化方式、`scope`/`owner_name` 过滤字段、章节页码元数据，全都在 mapping 里。VectorStore 抽象把这些全藏起来，等于把最要紧的部分交给别人决定。代价是自己写约 200 行检索代码，这笔交易划算。

### 1.2 客户端版本与 Boot 自动配置的冲突（第 0 步必须处理）

Spring Boot 3.2.4 的 `spring-boot-dependencies` 把 ES 客户端托管在 **8.10.4**（属性 `<elasticsearch-client.version>`）。两个后果：

1. `retriever.rrf` 这套检索 DSL 是 **ES 8.14 才引入**的。不覆盖版本号，混合检索代码在**编译期**就找不到 `Retriever` 类。
2. `elasticsearch-java:9.5.4` 的 pom 声明的传输层依赖是 **`elasticsearch-rest5-client`**，不再依赖老的 `elasticsearch-rest-client`。而 Boot 的 `ElasticsearchClientAutoConfiguration` 构造的是 `org.elasticsearch.client.RestClient`——**这个类在 9.x 里不存在，自动配置不可用**。

处理：在 `AIModule/pom.xml` 显式声明 9.5.4，并自己写 `@Bean ElasticsearchClient`，用 `Rest5ClientTransport` + `JacksonJsonpMapper`。

**不需要** `org.eclipse.parsson`（8.x 时代为 `SimpleJsonpMapper` 提供 JSON-P 实现才需要；9.x 走 Jackson，`jackson-core`/`jackson-databind` 由 Boot 管理）。

### 1.3 embedding 为什么复用 OpenAI starter

百炼提供 OpenAI 兼容端点，而 AIModule 已经引入 `spring-ai-starter-model-openai`，所以加 embedding **零新依赖**，只加配置段。

关键陷阱：顶层 `spring.ai.openai.base-url` 指向 DeepSeek，必须用 `spring.ai.openai.embedding.base-url` 和 `spring.ai.openai.embedding.api-key` 单独覆盖，否则 embedding 请求会被发到 DeepSeek（它没有 embedding 接口，直接 404）。

### 1.4 `spring-ai-rag` 是可选实验，不是设计依赖

`spring-ai-rag:1.0.0-M7` 与 `spring-ai-advisors-vector-store:1.0.0-M7` 在中央仓库存在（已核实），`RetrievalAugmentationAdvisor` 在其中。用它可以把召回结果经 `DocumentRetriever` 注入 prompt，`retrieval` 类 `thought_step` 的埋点天然落在调用点上。

但**本设计不依赖它**：检索契约是我们自己的 `RetrievalService`，注入这一步手写也只有十几行。M7 的 advisor 是否完整支持 stream 链尚未实测，因此它作为第 6 步实现时的一次性小实验——测得过就用，测不过就退回手写，两种结果都不影响其他任何一节。

### 1.5 为什么是 Tika，不是多模态模型

Tika 纯 Java 本地抽取，无外部服务、无额外 Key，且能拿到**逐页分页**（`PaginationHandler`），这是"跳到原书第 N 页"这个出处需求的技术前提。双栏排版和表格会退化成连续文本，可用但不精。

多模态模型（qwen-vl 读 PDF 转 Markdown）在公式、表格、双栏上质量明显更高，但需要另一个模型的 Key 与费用、入库变异步、要处理调用失败重试。作为**第 2 阶段的替换项**：`DocParser` 是接口，将来插多模态实现不改入库流程。

扫描件（图片型 PDF）任何文本抽取器都抽不出东西，**直接拒收并明确提示**，不做 OCR——那是拍照识题（场景 7）的范围。

### 1.6 为什么要 jtokkit

切片按 **token** 而不是按字符计。中文 1 字 ≈ 1 token 左右，但混排英文和公式时差别很大；用字符数当预算会让含大量代码的教材切片实际超预算。embedding 模型有输入上限，超了直接报错。

---

## 2. 架构：组件边界与数据流

### 2.1 六个单元

全部新代码收在 `org.leon.aimodule.rag` 包下，每个单元一个职责：

| 单元 | 唯一职责 | 靠什么实现 | 对外只暴露 |
|---|---|---|---|
| `EmbeddingGateway` | 全项目唯一的向量出口 | Spring AI `EmbeddingModel`（百炼） | `embed(List<String>)`：分批、重试、维度断言 |
| `DocParser`（接口） | 文件字节 → 带页码的文本段 | Tika（首个实现） | `parse(stream, mime) → ParsedDoc` |
| `ChunkPlanner` | 文本段 → 切片，保留章节路径/页码 | jtokkit | `plan(parsed) → List<ChunkDraft>` |
| `IndexRegistry` | 索引建/删/别名切换，mapping 固化 | `elasticsearch-java` | `ensureIndex` / `swapAlias` / `stats` |
| `RetrievalService` | **唯一的检索读入口** | BM25 + kNN → RRF → 权限过滤 | `search(query, scope, topK) → List<Hit>` |
| `IngestService` | 编排 parse→chunk→embed→bulk 写 | 上面四个 | `submit(docId)` / `progress(docId)` |

外加两个流水线专属服务：`QuestionIndexService`（唯一允许读写 `qb_question` 的类）、`ExtractService`（录题抽取与审核）。

### 2.2 三条流水线如何复用

```
① 知识库文档： ai_kb_doc → IngestService → DocParser → ChunkPlanner
                                                     → EmbeddingGateway → ES ai_kb_chunk

② 题目索引：   qb_question → QuestionIndexService → EmbeddingGateway → ES ai_question
                （无解析、无切片，题文本身即单元）

③ 自动录题：   文件 → DocParser → LLM 结构化输出 → ai_extract_draft → 人工审核
                                                    → qb_question → 回头触发 ②

对话侧： stream(sources=knowledge_base) → RetrievalService.search
          → thought_step(retrieval, refCount) → 片段拼 system prompt → ChatClient.stream()
          → 命中写 ai_message_citation
```

### 2.3 三条贯穿全局的原则

**原则 1：MySQL 是真相源，ES 是可丢弃的派生视图。**
原文与元数据全在 MySQL（`ai_kb_doc` + `ai_kb_chunk`），ES 只存检索所需字段。索引损坏或策略变更时从 MySQL 全量回灌，不重新解析文件、不重新向量化。
【为什么这么做】`dense_vector` 的 `dims` 和字段的 `analyzer` 建索引后不可修改，换 embedding 模型或调切片参数必然要重建索引。如果 ES 是唯一存储，这一步就是数据丢失事故。

**原则 2：权限过滤只能在 `RetrievalService` 内部做，任何 controller 不许自己拼 ES query。**
【为什么这么做】两级知识库最容易出的安全事故不是被攻击，是忘记加 filter 导致甲读到乙的私人讲义。把 filter 关进一个类，评审只需看一个地方。落到 DSL 里两条腿各写一份（见 3.4），Java 侧是同一个方法产出。

**原则 3：入库一律异步，异步的派发走 RabbitMQ，状态与恢复走 MySQL。**
上传接口立刻返回 `docId` + `pending`，解析/切片/向量化由消息触发，进度单独查。
【为什么这么做】一份 200 页教材要发几百次 embedding 请求（每请求上限 20 条，见 3.6），同步做会把 HTTP 线程挂住几十秒并撞上网关超时。MQ 与状态机的职责切分见 2.5。

### 2.4 一处已知技术债

AIModule 直接读写同一个 MySQL 库里的 `qb_question`，而不是 Feign 调 PracticeModule。依据是既有事实：AIModule 的 datasource 已指向 `ezpracticeexercises` 同一个库（`application.yaml:41`），PracticeModule 没有任何对内接口，且 `@EnableFeignClients` 在全部 7 个模块里一个都没写。

这违反"每个服务独占自己的库"，属于**已知技术债**，用一条边界围住：只有 `QuestionIndexService` 允许碰 `qb_question`，将来拆库只改这个类。

---

### 2.5 异步派发：RabbitMQ 拓扑与消费契约

#### 2.5.1 定位：MQ 管"什么时候开始干"，MySQL 管"干到哪了"

这是本节唯一重要的边界。**RabbitMQ 不替换 4.4 的状态机，只是把触发方式从轮询换成事件驱动。**

【为什么这么做】MQ 是 at-least-once 语义：消费者在处理中途崩溃，消息会被重新投递。但"这份文档已经向量化到哪一片刻"这件事，只有 `ai_kb_chunk.es_synced` 知道——MQ 只知道"有个任务没确认"，不知道任务内部的进度。所以：

| 关注点 | 归属 | 不能交给对方的原因 |
|---|---|---|
| 任务何时开始、失败后何时重试、削峰 | RabbitMQ | DB 轮询有延迟，且重试退避要自己写 |
| 任务真实进度、断点续跑、崩溃恢复 | MySQL 状态机 | MQ 不存业务状态，重投只到"任务级"粒度 |
| 最终一致性兜底 | 两边都做（见 2.5.5） | 单靠 MQ 会静默丢任务 |

#### 2.5.2 拓扑

```
exchange: ai.topic            (topic, durable)
  ├─ rk ai.kb.ingest          → queue ai.kb.ingest       (prefetch=1, DLX=ai.retry)
  ├─ rk ai.question.index     → queue ai.question.index  (prefetch=8, DLX=ai.retry)
  └─ rk ai.extract.run        → queue ai.extract.run     (prefetch=1, DLX=ai.retry)

ai.retry   (x-message-ttl=30000, DLX=ai.dlq)   退避重试用 TTL 链 30s → 2m → 10m
ai.dlq     人工介入，由 5.7 的运维接口列出与重投
```

退避用 TTL + DLX 链，**不引 `rabbitmq_delayed_message_exchange` 插件**。【为什么这么做】多一个插件就多一个"你机器上没装所以跑不起来"的故障源，而三档固定退避用 TTL 完全够，代价只是重试延迟不精确——这对一个入库任务无所谓。

**消息体只放 ID，不放正文**：`{"kind":"kb_ingest","docId":41,"traceId":"..."}`。
【为什么这么做】真相在 MySQL（原则 1）。往 MQ 里塞切片正文会制造第二种不一致——"消息说改了、库里没改"；而且 embedding 输入体积大，队列不是存储。消息小，重投和排障都便宜。

#### 2.5.3 消费契约四条

1. **手动 ack**：处理成功才 ack；异常 `nack(requeue=false)` 让它进 retry 队列，绝不 `requeue=true`（会热循环打满 CPU）。
2. **`prefetch=1`**（入库与抽取这类分钟级任务；题目单条索引可放到 8）。
   【为什么这么做】默认 prefetch=250 会让一个 worker 抱着一堆消息、别的实例闲着；更糟的是这个 worker 崩溃时，那 250 条它根本没开始处理的消息全部重投，把一次崩溃放大成一次风暴。
3. **幂等仍靠条件 UPDATE 抢占（4.4），不靠 MQ**。重复投递在 at-least-once 下是**正常事件而非异常**，两道幂等已经覆盖：CAS 保证同一文档只有一个 worker，ES 同 `_id` 覆盖写保证重复 bulk 不产生重复文档。
4. **先提交 MySQL 事务，再发消息**——用 `@TransactionalEventListener(phase = AFTER_COMMIT)` 发。
   【为什么这么做】这是引入 MQ 最容易踩的坑：消息先发出去、事务随后回滚，消费者拿到一个库里根本不存在的 `docId`，然后无限重试直到进 DLQ。本地测试"看起来一切正常"，直到第一次唯一约束冲突（`uk_scope_owner_hash` 命中）才炸——而那恰好是最不该出错的重复上传路径。

#### 2.5.4 与状态机的接合点

```
POST /ai/kb/documents
  → INSERT ai_kb_doc(status='pending')      ← 事务在此提交
  → (AFTER_COMMIT) publish rk=ai.kb.ingest, body={docId}
消费者
  → 条件 UPDATE 抢占 pending→parsing，行数==1 才继续，==0 直接 ack 丢弃
  → 跑阶段，每批更新 heartbeat_time
  → 成功 ack；异常 nack → ai.retry → 三次后进 ai.dlq
```

抢占失败时**必须 ack 而不是 requeue**：说明别的 worker 已经在做，这条消息的使命已完成。

#### 2.5.5 兜底扫描保留，不是冗余

`@Scheduled` 每 2 分钟扫 `status='pending'` 且 `update_time` 落后、以及心跳超时的中间态，**重新 publish 一条**（不自己执行，执行权仍归消费者）。

【为什么这不是冗余设计】MQ 会丢消息：节点重启且队列未持久化、消费者 ack 前崩溃且重投次数耗尽、运维手工清过队列、RabbitMQ 装不上或没起来。只留 MQ 的话，一条消息进了 DLQ 就永久静默，表现为"学生的文档永远停在 pending 而没人知道"。**MQ 负责快，DB 扫描负责不丢**，两者共同构成可用性；这也让 2.5.6 的降级路径成立。

#### 2.5.6 没有 RabbitMQ 也要能跑（降级路径）

`rag.async=mq|poll` 配置项：`poll` 模式下不发 MQ，由 `@Scheduled` 每 5 秒扫 `pending` 并用本地线程池执行，其余逻辑（CAS 抢占、心跳、回收、断点续跑）**完全不变**。

【为什么这么做】实测本机没有 RabbitMQ（5672/15672 无监听，全仓库无 `amqp` 依赖），而 4a 之后的 5、6、7 步都不依赖 MQ。把 MQ 做成可选派发方式，你才能在装好它之前继续推进，也让"引入 MQ 到底带来什么差别"变成一件可以实测对比的事，而不是必须一次到位的前提。

#### 2.5.7 诚实的收益评估

| 收益 | 在你这个场景下值多少 |
|---|---|
| 削峰、失败自动退避重试 | 真实收益，省掉自己写重试定时器 |
| 上传接口响应更快（不等线程池排队） | 有，但 `poll` 模式的差距只有秒级 |
| 多实例水平扩展消费 | 你现在单实例，**用不上** |
| 与已有微服务组件（Nacos/Sentinel/Seata）凑齐一套 | 学习价值，不是工程价值 |

代价：多一个中间件（Windows 要装 Erlang + RabbitMQ，或用 Docker）、AIModule 多 `spring-boot-starter-amqp`、多一套"为什么任务没跑"的排障面（现在要同时看 DB 状态、队列堆积、DLQ 三处）。

**结论：值得引入，但按 2.5.6 做成可选派发层，并且第 4a 步先不接它。**



---

## 3. Elasticsearch 索引设计与混合检索

### 3.1 环境与安装

本机 ES 9.5.4（zip 版，Lucene 10.5.1，**已启用 TLS 与认证**，明文 http 被拒）。IK 插件需安装：

```bat
bin\elasticsearch-plugin.bat install "https://get.infini.cloud/elasticsearch/analysis-ik/9.5.4"
:: 该 URL 实测返回 application/zip，4.6MB；装完必须重启进程
curl -k -u elastic "https://127.0.0.1:9200/_cat/plugins?v"
```

`infinilabs/analysis-ik` 的 GitHub release **没有** v9.5.4 标签，不要按 GitHub 地址找。

若 IK 装不上，切内置 `cjk` 分词器（附录 A 实测可用，输出二元 gram：`快速/速排/排序/...`）。通过配置项 `rag.analyzer=ik|cjk` 切换，**但索引一旦建立 analyzer 就固定，切换等于重建索引**——这是唯一代价，必须提前决定。

`rag.analyzer` 不是运行时开关，而是 `IndexRegistry` **生成 mapping 时**读取的参数：`ik` 产出 `tokenizer: ik_max_word`，`cjk` 产出 `analyzer: cjk`（`cjk` 是内置分析器，没有 max_word/smart 之分，此时 3.3 里的 `zh_index`/`zh_search` 两个自定义分析器整体替换为 `cjk`）。代码里只有 `IndexRegistry` 一处读它，检索与入库都不感知分词方式。

连接配置（密码只从环境变量或 Nacos 注入，**绝不写进 `application.yaml` 提交**）：

```yaml
spring:
  elasticsearch:
    uris: https://127.0.0.1:9200
    username: elastic
    password: ${ELASTIC_PASSWORD}
    # 开发期信任 ES 生成的 http_ca.crt；不要用"跳过证书校验"的写法上生产
```

### 3.2 索引命名与别名

两个索引，各带版本化物理名 + 去版本号别名。**代码只写别名，`_v1` 只出现在运维命令里。**

```
ai_kb_chunk_v1  ← alias →  ai_kb_chunk
ai_question_v1  ← alias →  ai_question
```

【为什么这么做】reindex 流程是"建 `_v2` → 从 MySQL 全量回灌 → 原子切别名"，全程读请求不断，不需要停机。没有别名机制，改 mapping 就只能删索引重建，中间是检索真空期。

### 3.3 知识库切片索引 mapping

```json
PUT /ai_kb_chunk_v1
{
  "aliases": { "ai_kb_chunk": {} },
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "refresh_interval": "30s",
    "analysis": { "analyzer": {
      "zh_index":  { "tokenizer": "ik_max_word" },
      "zh_search": { "tokenizer": "ik_smart" }
    }}
  },
  "mappings": { "properties": {
    "chunk_id":     { "type": "long" },
    "doc_id":       { "type": "long" },
    "scope":        { "type": "keyword" },
    "owner_name":   { "type": "keyword" },
    "subject_id":   { "type": "long" },
    "title":        { "type": "text", "analyzer": "zh_index", "search_analyzer": "zh_search",
                      "fields": { "kw": { "type": "keyword" } } },
    "content":      { "type": "text", "analyzer": "zh_index", "search_analyzer": "zh_search" },
    "vec":          { "type": "dense_vector", "dims": 1024, "index": true, "similarity": "cosine",
                      "index_options": { "type": "int8_hnsw", "m": 16, "ef_construction": 100 } },
    "book":         { "type": "keyword" },
    "chapter_path": { "type": "keyword", "index": false, "doc_values": true },
    "page_no":      { "type": "integer" },
    "token_count":  { "type": "integer" }
  }}
}
```

四个字段的设计意图：

- **`content` 两套 analyzer**：建索引 `ik_max_word`（细，切出所有可能的词），查询 `ik_smart`（粗，切最少的词）。细索引 + 粗查询是 IK 标准搭配，反过来会漏召回。
- **`vec` 显式 `int8_hnsw`**：ES 9.0 起 `dense_vector` 默认量化改为 `bbq_hnsw`（二值量化，省内存更狠但精度损失大）。1024 维 float 每条 4KB，int8 压到 1KB，10 万条切片从 400MB 降到 100MB 级，单节点 1G 堆才扛得住。【为什么这么做】量化损失精度，但 RRF 只关心**排名**不关心分数，量化对最终排序的影响远小于它对内存的影响。这里显式声明不是可选项，是把 9.x 的新默认值挡住。
- **`chapter_path` `index: false`**：只用于展示"出处：第3章 → 3.2 节"和原样取回，从不作为查询条件，建倒排索引纯浪费。
- **`chunk_id` 同时作为 ES 文档 `_id`**：幂等靠它——重复入库是同 `_id` 覆盖写，不会越灌越多。
- **`subject_id` 从所属 `ai_kb_doc` 复制**，切片表本身不存这一列（学科是文档级属性，不是切片级）。`0` 表示全学科通用，见 3.5 的过滤写法。

### 3.4 题目索引与权限列的统一

`ai_question_v1` 与上面同构，差异：`_id` 用 `question_id`；无 `chapter_path`/`page_no`/`book`；多 `tags`（keyword 数组）、`difficulty`（integer）、`qtype`（keyword）。BM25 腿查 `title + analysis` 两个字段——**题目大量信息在解析里**，只查标题召回会很差。

**权限列矛盾的解法**：知识库用 `user_name`（JWT subject），`qb_question` 用 `owner_id`（数字）。定案是**两个索引都只按 `owner_name` 过滤**，题目索引在回灌时 JOIN `users` 把 `owner_name` 冗余写进每条文档。

代价说清楚：用户改名后题目索引要重建。本系统用户名注册后不可改，所以用一条冗余换"全项目单一权限语义"是划算的。**如果将来支持改名，这条冗余必须换成 `owner_id`。**

同理，`qb_question.subject_id` 若存在 `NULL`（那是别人的表，我们不改它），`QuestionIndexService` 回灌时必须 `IFNULL(subject_id, 0)` 写进 ES——**哨兵值转换发生在写 ES 的那一刻，不回流到 `qb_question`**。

### 3.5 混合检索 DSL

```json
GET /ai_kb_chunk/_search
{
  "size": 6,
  "retriever": {
    "rrf": {
      "rank_constant": 60,
      "rank_window_size": 50,
      "retrievers": [
        { "standard": { "query": { "bool": {
            "must": { "multi_match": { "query": "快排为什么是 O(nlogn)",
                                        "fields": ["title^2", "content"] } },
            "filter": [ { "bool": { "minimum_should_match": 1, "should": [
                            { "term": { "subject_id": 12 } },
                            { "term": { "subject_id": 0 } } ] } },
                        { "bool": { "minimum_should_match": 1, "should": [
                            { "term": { "scope": "official" } },
                            { "bool": { "filter": [ { "term": { "scope": "personal" } },
                                                    { "term": { "owner_name": "alice" } } ] } }
                        ] } } ]
        } } } },
        { "knn": {
            "field": "vec", "query_vector": [/* 1024 floats */],
            "k": 20, "num_candidates": 100, "similarity": 0.55,
            "filter": [ /* 与上面完全相同的两个 filter，逐字重复 */ ]
        } }
      ]
    }
  }
}
```

四个必须遵守的点：

**一、权限 filter 在两条腿里逐字重复，不写在 `rrf` 层共享。**
【为什么这么做】RRF 是取每条腿各自的前 50 名再融合排名，**任何一条腿漏了 filter，另一条腿的结果救不回来**，泄露的就是乙的私人讲义。这里重复的是安全性而非代码——Java 侧由同一个方法产出两份，落到 DSL 必须是两份。

**二、相关度闸门只能设在 kNN 腿上，绝不能设 `min_score`。**
RRF 的 `_score` 是 `Σ 1/(rank_constant + rank)`，量纲是"名次"，与余弦相似度无关，典型值在 0.03 左右。写 `"min_score": 0.8` 会**把所有结果过滤光**，看起来像索引坏了。这是 RRF 最容易踩的坑。所以：kNN 腿用 `similarity: 0.55` 卡掉语义不相关的，BM25 腿天然只有真实命中词才出现，融合后由 `size: 6` 截断。

**三、学科过滤要写成"本学科 OR 通用"，不能写成单个 `term`。**
`subject_id` 用 `0` 表示全学科通用，所以按学科检索时 filter 是 `should[term(12), term(0)]`。
【为什么这么做】如果沿用 MySQL 的 `NULL` 表示"通用"，ES 里**字段缺失的文档永远不匹配任何 `term` 过滤**——写 `term(subject_id=12)` 会把所有通用教材静默排除，写 `must_not exists` 补救则要两个查询。这是 4.1 那条"哨兵值"规则在检索侧的兑现：**MySQL 允许 NULL，ES 的过滤语义不允许。**

**四、召回为空时绝不注入任何上下文。**
0 命中 → `thought_step` 的 `refCount=0`，system prompt 显式加"知识库中未检索到相关内容，请基于你自己的知识回答，并明确告知学生没有教材依据"。**不允许**为了显得 RAG 在工作而塞进不相关片段或谎称引用——编造出处比没有出处严重得多。

参数起步值：`rank_constant: 60`（TREC 经验值），调低到 20 会让头部权重更陡，**等看到实际排序再调**。`num_candidates: 100` 是 kNN 召回质量旋钮，10 万级数据上已接近全量扫描，再高只涨延迟。

### 3.6 切片策略与百炼硬限制

**切片：500 token 预算，重叠 60 token（12%）**，段落边界优先，单段超预算才按句号硬切。
【为什么这么做】embedding 输入上限远高于 500，切片小不是被迫的，而是召回粒度：2000 token 的大切片只要主题稍有偏移，向量就会被平均掉、检索精度断崖式下降，而 LLM 上下文现在很便宜，宁可多召回几段小的。重叠防止一个考点正好被切断在边界上。

检索后按 token 预算从 Top6 往下拼 prompt，超预算丢**排名最后**的那条。

**百炼硬限制：单次请求最多 20 条文本，超过直接 400**（附录 A 已核实）。所以 `EmbeddingGateway` 的分批不是性能优化而是必须项，实现取 `batchSize=10` + 并发 4，留出余量。`token_count` 落进 MySQL 与 ES 供拼 prompt 用。

---

## 4. MySQL 表与入库状态机

### 4.1 一条贯穿规则

> **参与唯一索引的列一律 `NOT NULL DEFAULT ''` 或 `DEFAULT 0`，禁止用 NULL 表示"没有"。**

【为什么这么做】MySQL 唯一索引里 NULL 互不相等，`UNIQUE KEY (a,b)` 中 b 为 NULL 的行可以无限重复插入，判重会**静默失效**——不报错，只是重复数据越积越多。本设计里这条规则出现三次：`ai_kb_doc.owner_name`（官方文档用 `''`）、`ai_message_citation.chunk_id`/`question_id`（用 `0`）、`ai_extract_draft.question_id`（用 `0`）。

同一条思路在检索侧更要紧：ES 里**字段缺失的文档不参与任何 `term` 过滤**，所以 `subject_id` 也一律 `NOT NULL DEFAULT 0`，不保留 NULL（详见 3.5 第三点）。

时间列沿用 `create_time`/`update_time` DATETIME(3)，与同前缀 `ai_*` 表内部一致；代价是与 `qb_question` 的 `created_at`/`updated_at` 两套风格并存，跨模块读它时用显式 `@TableField` 映射，**不改别人的表**。

### 4.2 DDL

```sql
-- ① 知识库文档：真相源，也是状态机宿主
CREATE TABLE ai_kb_doc (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  scope           VARCHAR(16)  NOT NULL COMMENT 'official / personal',
  owner_name      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'personal=JWT subject；official 固定空串',
  subject_id      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=全学科通用；ES 过滤语义不接受 NULL，见 3.5',
  title           VARCHAR(255) NOT NULL,
  file_name       VARCHAR(255) NOT NULL,
  mime            VARCHAR(128) NOT NULL,
  size            INT UNSIGNED NOT NULL,
  storage_key     VARCHAR(255) NOT NULL COMMENT '只存 key，不存二进制',
  sha256          CHAR(64)     NOT NULL COMMENT '文件指纹，判重省 embedding 费',
  chunk_count     INT UNSIGNED NOT NULL DEFAULT 0,
  token_total     INT UNSIGNED NOT NULL DEFAULT 0,
  status          VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '状态机，见 4.4',
  stage_error     VARCHAR(500) NULL,
  retry_count     TINYINT UNSIGNED NOT NULL DEFAULT 0,
  heartbeat_time  DATETIME(3)  NULL COMMENT 'worker 心跳，崩溃回收靠它',
  parser_version  VARCHAR(32)  NOT NULL DEFAULT 'tika-3.x',
  chunk_strategy  VARCHAR(32)  NOT NULL DEFAULT 'tok500-ov60',
  embed_model     VARCHAR(64)  NOT NULL DEFAULT 'text-embedding-v3:1024',
  create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  ready_time      DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_scope_owner_hash (scope, owner_name, sha256),
  KEY idx_list (scope, owner_name, status, update_time),
  KEY idx_recovery (status, heartbeat_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT '知识库文档';

-- ② 切片：真相源的一部分，ES 从它回灌
CREATE TABLE ai_kb_chunk (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '直接用作 ES 文档 _id',
  doc_id       BIGINT UNSIGNED NOT NULL,
  seq          INT UNSIGNED    NOT NULL COMMENT '文档内顺序，从 1',
  content      MEDIUMTEXT      NOT NULL,
  token_count  SMALLINT UNSIGNED NOT NULL,
  page_no      INT UNSIGNED    NULL,
  book         VARCHAR(128)    NULL,
  chapter_path VARCHAR(255)    NULL,
  es_synced    TINYINT         NOT NULL DEFAULT 0 COMMENT '0=未进ES，断点续跑靠它',
  create_time  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_seq (doc_id, seq),
  KEY idx_unsynced (doc_id, es_synced)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT '知识库切片';

-- ③ 索引作业：全量回灌/reindex 的可观测与断点
CREATE TABLE ai_index_job (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  index_name    VARCHAR(64)  NOT NULL COMMENT 'ai_kb_chunk / ai_question',
  kind          VARCHAR(16)  NOT NULL COMMENT 'reindex_kb | backfill_question | single_question',
  target_total  INT UNSIGNED NOT NULL DEFAULT 0,
  done_count    INT UNSIGNED NOT NULL DEFAULT 0,
  failed_count  INT UNSIGNED NOT NULL DEFAULT 0,
  cursor_id     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '断点续跑游标，按 id 升序',
  status        VARCHAR(16)  NOT NULL DEFAULT 'running' COMMENT 'running/succeeded/failed/cancelled',
  err_msg       VARCHAR(500) NULL,
  operator_name VARCHAR(64)  NOT NULL DEFAULT '',
  create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  finish_time   DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT '索引作业';

-- ④ 索引水位：不改 PracticeModule 的表，靠它判断题目脏不脏
CREATE TABLE ai_question_index_state (
  question_id   BIGINT UNSIGNED NOT NULL,
  indexed_time  DATETIME(3)     NOT NULL,
  PRIMARY KEY (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT '题目向量索引水位';

-- ⑤ 录题草稿：AI 抽取结果先落这里，审核通过才进 qb_question
CREATE TABLE ai_extract_draft (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  doc_id         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  raw_index      SMALLINT UNSIGNED NOT NULL COMMENT '文件内出现顺序',
  qtype          VARCHAR(16)  NOT NULL COMMENT 'single/multi/blank/judge/short',
  title          MEDIUMTEXT   NOT NULL,
  options_json   JSON         NULL,
  answer_json    JSON         NOT NULL,
  analysis       MEDIUMTEXT   NULL,
  tags_json      JSON         NULL,
  subject_id     BIGINT UNSIGNED NULL,
  difficulty     TINYINT      NULL,
  model_key      VARCHAR(64)  NOT NULL DEFAULT '',
  prompt_version VARCHAR(32)  NOT NULL DEFAULT 'extract-v1',
  status         VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/converted/rejected',
  reviewer_name  VARCHAR(64)  NOT NULL DEFAULT '',
  review_time    DATETIME(3)  NULL,
  reject_reason  VARCHAR(255) NULL,
  question_id    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'converted 后回填 qb_question.id',
  create_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_raw (doc_id, raw_index),
  KEY idx_review (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT 'AI 录题草稿与审核';

-- ⑥ 回答引用：RAG 溯源落库，逻辑关联 ai_message.id
CREATE TABLE ai_message_citation (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  message_id      BIGINT UNSIGNED NOT NULL,
  conversation_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  chunk_id        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '知识库命中时非 0',
  question_id     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '题目命中时非 0，与 chunk_id 二选一',
  index_name      VARCHAR(64)  NOT NULL,
  rank            SMALLINT UNSIGNED NOT NULL COMMENT 'RRF 融合后名次，从 1',
  cosine          DECIMAL(6,5) NULL COMMENT 'kNN 腿相似度，供检索质量分析',
  book            VARCHAR(128)  NOT NULL DEFAULT '',
  chapter_path    VARCHAR(255)  NOT NULL DEFAULT '',
  page_no         INT UNSIGNED  NOT NULL DEFAULT 0,
  snapshot_title  VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '出处定格',
  create_time     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_msg_ref (message_id, chunk_id, question_id),
  KEY idx_msg (message_id, rank)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT '回答的知识库引用';
```

建表脚本落到 `AIModule/src/main/resources/sql/ai_rag_schema.sql`，手工执行。仓库里目前**没有任何 `.sql` 文件**（`qb_question` 的结构只能从实体类反推），也**不为了建表引入 Flyway**——那是另一套运维心智，等表结构稳定后再谈。

### 4.3 四个不显然但会救你的设计

**`ai_kb_doc` 的三个版本列（`parser_version`/`chunk_strategy`/`embed_model`）。**
【为什么这么做】换 embedding 模型或调切片参数时索引必须重建（`dims` 不可改）。有了版本列，一条 SQL 就能算出"还是旧模型/旧策略的文档有多少篇"，重建作业按它拉清单。没有这三列，你永远不知道该重建什么，只能全量重来。

**`ai_question_index_state` 而不是给 `qb_question` 加 `indexed_at` 列。**
【为什么这么做】后者要改别的模块的表，把 AI 流水线的关注点焊进题库 schema，PracticeModule 每次建表迁移都要跟着动。靠水位表左连接判脏，`qb_question` 一行不动：

```sql
SELECT q.id FROM qb_question q
LEFT JOIN ai_question_index_state s ON s.question_id = q.id
WHERE s.question_id IS NULL OR q.updated_at > s.indexed_time
ORDER BY q.id LIMIT 500;
```

**`ai_extract_draft` 独立成表，而不是往 `qb_question` 写 `status='ai_draft'`。**
【为什么这么做】`qb_question.status` 是"上架/下架"的业务语义，混进"AI 待审核"会让 PracticeModule 所有查题条件被迫理解一条它不该知道的 AI 流水线状态。审核通过才 INSERT `qb_question`，回填 `draft.question_id`，两边职责都不脏。

**`ai_message_citation` 冗余 `book`/`chapter_path`/`page_no`/`snapshot_title`。**
【为什么这么做】这是**引用定格**。老师改了教材标题或删了某篇文档，历史回答里"出自第 3 章 3.2 节第 45 页"绝不能跟着变——那等于篡改已经给出的出处。引用写死后不再 JOIN 回来。

### 4.4 入库状态机

```
       上传落库
          ↓
       pending ──→ parsing ──→ chunking ──→ embedding ──→ indexing ──→ ready
          ↑           │            │             │            │
          │           └────────────┴─────────────┴────────────┘
          │                        ↓ 异常
          └── retry_count<3 ←──  failed  ──→ retry_count>=3 终态，人工介入
       （心跳超时回收）

  删除：任意状态 → deleted（MySQL 保留行，ES 物理删）
```

**抢占用条件 UPDATE，不用分布式锁，也不用 Seata。**

```sql
UPDATE ai_kb_doc
   SET status='parsing', heartbeat_time=NOW(3), retry_count=retry_count+1
 WHERE id=? AND status='pending';
-- 受影响行数 == 1 才算抢到；== 0 说明别的线程/实例已在做，直接放弃
```

【为什么这么做】InnoDB 单行 UPDATE 自带行锁，`WHERE status='pending'` 让"检查 + 占位"成为一个原子动作，多实例同时跑也不会重复向量化。项目里有 Seata，但**这里绝不能用 `@GlobalTransactional` 包住整条流水线**——一份教材从解析到入库要几十秒到几分钟，挂一个跨分钟的全局事务，undo_log 和锁等待都会出问题。这与《AI对话思考链路》"流式过程不得持有全局事务"是同一条理由。

**触发方式与状态机解耦**：`pending → parsing` 由谁发起是可插拔的——MQ 消费者（2.5.4）或 `poll` 模式下的定时器。状态机本身不感知触发源，这正是它能同时支撑两种派发方式、且引入 MQ 不改动 4.4 任何一条 SQL 的原因。

**每个状态边界对应一个明确的"从这里重跑要花多少钱"。** 这是设计状态机的方法论：

| 边界 | 崩溃后重跑的代价 | 所以单独成态 |
|---|---|---|
| `parsing` | Tika 抽一遍，秒级，零 API 费 | 可合并，保留是为了进度百分比 |
| `chunking` | 纯内存计算，微秒级 | 可合并 |
| `embedding` | **每 20 条一次百炼调用**，一份 200 页教材几百次，真金白银 | 必须单独——失败绝不能退回 parsing 重跑 |
| `indexing` | ES bulk 写，无 API 费 | 必须单独，它决定 `ready` 的可见性 |

断点续跑靠 `ai_kb_chunk.es_synced`：待办清单是 `WHERE doc_id=? AND es_synced=0`，重启后只补没进 ES 的切片，已向量化的不重算。`indexing` 完成判定也是它——`COUNT(*) WHERE es_synced=0` 归零才置 `ready`。

**回收扫描必须有，否则服务重启后任务永远卡在 `embedding`。** `@Scheduled` 每 2 分钟走 `idx_recovery`：

```sql
UPDATE ai_kb_doc
   SET status='pending', stage_error='worker 心跳超时，已回收重跑'
 WHERE status IN ('parsing','chunking','embedding','indexing')
   AND heartbeat_time < NOW(3) - INTERVAL 5 MINUTE
   AND retry_count < 3;

UPDATE ai_kb_doc SET status='failed', stage_error='重试次数耗尽'
 WHERE status IN ('parsing','chunking','embedding','indexing')
   AND heartbeat_time < NOW(3) - INTERVAL 5 MINUTE
   AND retry_count >= 3;
```

心跳在每个阶段入口和每批 embedding 完成时更新。**5 分钟必须显著大于单批最慢耗时**，否则会把正在干活的 worker 判死、造成两边同时向量化同一文档——多实例部署时这个阈值要按最慢批次实测来定。

**MySQL 写和 ES 写不可能原子，靠固定顺序 + 重放收敛，而不是靠事务。**
① 切片写 MySQL（`es_synced=0`）→ ② bulk 写 ES（同 `_id`，重复写覆盖）→ ③ 回标 `es_synced=1`。任何一步崩溃，残留的都是"MySQL 有、ES 没有"，重放一次就收敛；反过来（ES 有、MySQL 没有）才是脏数据，固定顺序保证它不发生。**这就是原则 1 在代码层的兑现。**

**删除必须物理删 ES。** MySQL 置 `status='deleted'` 保留行，ES 走 `delete_by_query` + `term:{doc_id:N}`。
【为什么这么做】个人库存的是学生自己的讲义，用户点删除后还能被检索到是隐私问题，不是数据问题。软删只在 MySQL 这一侧成立。

**`ready` 之前不许被检索。** ES 是 near-real-time，写完到能被搜到默认要等 1 秒 refresh。索引设 `refresh_interval: 30s` 降开销，`indexing` 阶段末尾对该索引显式 `_refresh` 一次再置 `ready`。这样"检索到这则知识"和"文档在知识库里"不会自相矛盾。

---

## 5. 接口契约

Base：`http://<gateway>:8100`，AIModule 直连调试用 `http://127.0.0.1:8105`，路径相同。控制面一律包 `Result`，时间字段一律 epoch 毫秒 number（沿用《AI对话思考链路》第四节约定）。

### 5.1 四个前置项（不补则新接口无处安放）

| 缺口（附录 A 实测） | 处理 |
|---|---|
| AIModule 没有 `Result` 类，两个 AI controller 直接返回裸 String | 新增**泛型** `Result<T>` 到 `CommonJwt`，AIModule 用它；UserModule/AuthModule 那两份重复的**不动** |
| 只有 `jakarta.validation-api` 接口，无实现，`@Valid` 空转 | AIModule 加 `spring-boot-starter-validation` |
| 全仓库没有 `@RestControllerAdvice` | AIModule 加一个，统一转 `Result{code:0}`，并区分校验失败 / 业务异常 / ES 连接异常 |
| `AiModuleApplication` 只有 `@SpringBootApplication`，无 `@MapperScan` | 新 mapper 接口逐个加 `@Mapper`（与 `UsersMapper` 风格一致），不改启动类 |

【为什么 Result 放 CommonJwt 但不统一旧的两份】`CommonJwt` 已经是被 AIModule 依赖的共享 jar，放这里是对的。但**不顺手统一 UserModule 和 AuthModule**——那会让三个模块的接口返回行为同时进入本次改动的爆炸半径，而这次目标是加 RAG，不是还债。技术债登记，不扩大处理范围。

### 5.2 A 组：知识库管理 `/ai/kb/**`

| # | 方法 | 路径 | 语义 |
|---|---|---|---|
| 0 | GET | `/ai/kb/ping` | 连通性：返回 ES cluster version + 别名列表（第 0 步验收用） |
| 1 | POST | `/ai/kb/documents` | multipart 上传。**响应必带 `duplicated` 布尔**：sha256 命中已有文档时返回旧 `docId`，不重新解析、不重新向量化 |
| 2 | GET | `/ai/kb/documents` | 列表，游标 `(update_time,id)`，参数 `scope`/`limit`（上限 50） |
| 3 | GET | `/ai/kb/documents/{id}` | 详情 + 进度：`status`/`chunkCount`/`tokenTotal`/`stageError`/`retryCount` |
| 4 | GET | `/ai/kb/documents/{id}/chunks` | 切片浏览（游标 `beforeSeq`），**调参工具** |
| 5 | POST | `/ai/kb/documents/{id}/retry` | `failed → pending`，`retry_count` 归零 |
| 6 | PATCH | `/ai/kb/documents/{id}` | 改 `title`/`subjectId`，同步 ES `update_by_query` |
| 7 | DELETE | `/ai/kb/documents/{id}` | MySQL 软删 + ES `delete_by_query` |

`#1` 的 `duplicated` 不是锦上添花：学生重复点上传是常态，响应不区分"新入库"和"早就有了"，前端就只能显示一个莫名的进度条，用户等两秒发现什么都没发生。省下 embedding 调用是附带好处。

`#6` 改标题要同步 ES，但比想象的便宜：`title` 参与 BM25，MySQL 改了 ES 不改会出现"列表显示新名字、搜旧名字还能命中"。用 `update_by_query` 只更新 `title` 字段，**`vec` 一字节不动**，不需要重新向量化。这里要记住的成本区分是：**改元数据 = 更新索引字段；改正文 = 重新切片 + 重新向量化 + 重新入库**，差着真金白银——所以 `content` 类字段在本设计里**不提供编辑接口**。

### 5.3 B 组：检索与调参

| # | 方法 | 路径 | 用途 |
|---|---|---|---|
| 8 | POST | `/ai/kb/search` | 走 `RetrievalService` 真身，返回命中切片 + 出处 + `rank` + `cosine` |
| 9 | POST | `/ai/retrieval/debug` | **管理员专用**。同一内核，额外返回 BM25 腿与 kNN 腿各前 50 名 |
| 10 | POST | `/ai/questions/search` | 语义搜题，返回 `questionId` + 相似度 + 标题 |

请求体（#8/#9/#10 同构）：

```json
{ "query": "快排为什么是 O(nlogn)", "topK": 6, "subjectId": 12,
  "scope": ["official","personal"] }
```

响应：

```json
{ "code": 1, "msg": "success", "data": {
  "took": 37, "empty": false, "items": [
    { "chunkId": 8812, "docId": 41, "rank": 1, "cosine": 0.7143,
      "title": "数据结构 第3章", "book": "《数据结构（Java版）》",
      "chapterPath": "第3章 → 3.2 常见排序算法", "pageNo": 45,
      "content": "……快速排序的期望时间复杂度为 O(nlogn)……" }
  ]}}
```

`empty=true` 时 `items` 为空数组而非 null，前端不用判两种情况。

【为什么 #8 和 #9 分开】混合检索最难的地方是**不可观察**：结果不对时你分不清是切片切坏了、embedding 不好、BM25 没命中词、还是 RRF 把两条腿搅乱了。把两条腿各自结果摊开，一眼定位是哪条腿的问题——这是调 `similarity: 0.55` 和 `rank_constant` 的唯一办法，没有它只能瞎猜。#9 不合并进 #8 是因为它要返回两倍数据且只给管理员用。

### 5.4 C 组：索引运维（管理员）

| # | 方法 | 路径 | 用途 |
|---|---|---|---|
| 11 | POST | `/ai/admin/index/jobs` | `{indexName, kind}` 启动回灌/reindex，返回 `jobId` |
| 12 | GET | `/ai/admin/index/jobs/{id}` | 进度：`targetTotal/doneCount/failedCount/cursorId/status` |
| 13 | POST | `/ai/admin/index/aliases` | 原子切别名，reindex 完成后唯一动作 |
| 14 | GET | `/ai/admin/index/status` | **对账**：ES `docs.count` vs MySQL 切片行数，差值非零即漏灌 |

【为什么必须有 #14】"MySQL 真相源 + ES 派生视图"架构一定会漂移：bulk 部分失败、ES 重启丢未 flush 数据、手工删过索引。没有对账接口，漂移只在用户抱怨"搜不到"时才暴露，且无法判断是漂移还是本来就没入库。

### 5.5 D 组：录题流水线

| # | 方法 | 路径 | 用途 |
|---|---|---|---|
| 15 | POST | `/ai/extract/tasks` | `{docId, subjectId}` 触发抽取，异步，返回 taskId |
| 16 | GET | `/ai/extract/drafts?status=pending` | 待审列表，按 `raw_index` 保持文件内顺序 |
| 17 | POST | `/ai/extract/drafts/{id}/review` | `{decision: approve|reject, patch?, reason}`；approve **直接** INSERT `qb_question`、置 `converted`、回填 `question_id`、触发单题索引 |

状态比 4.2 的表定义**少一个态**：`pending / converted / rejected`，**去掉了 `approved`**。
【为什么这么做】"审核通过"和"已入库"之间没有任何需要人工介入的动作，中间态只会制造"approved 但忘了 convert"的悬挂数据。少一个状态比多一个状态便宜。

### 5.6 E 组：`sources` 在 stream 里的真实语义

这张表是两份设计文档的接缝：

| `sources` 取值 | 检索动作 | 发出的 `thought_step` |
|---|---|---|
| `knowledge_base` | `ai_kb_chunk`，official + 我的 personal | `{kind:"retrieval", title:"检索知识库 · 命中 4 条", refCount:4}` |
| `question_bank` | `ai_question` | `{kind:"retrieval", title:"检索题库 · 命中 2 条", refCount:2}` |
| `attachment` | **不检索内容**，只登记附件名 | `{kind:"retrieval", title:"读取附件 resume.docx", refCount:1}` |

三条硬约束（前两条来自《AI对话思考链路》9.3 的组件源码事实）：

1. **`title` ≤ 24 字符**。"检索知识库 · 命中 4 条"是 11 字符，安全；但 `refCount` 位数增加时截断规则要生效。
2. **`refCount=0` 时照发这一步**，因为"检索过但没找到"是事实不是失败；但 prompt 必须显式告知"未检索到相关内容"，且**不注入任何片段**。
3. **命中切片写 `ai_message_citation`，精简版进 `meta_json.citations`** 供前端渲染 `[1]` 角标，正文要求模型用 `[n]` 标注。出处跳转读引用表的定格快照，不重新检索。

**降级策略：ES 不可用时不整体报错。** 发一条 `thought_step(title="知识库不可用", refCount=0)`，然后按无 RAG 的普通对话走完 `delta → done`。
【为什么这么做】检索是**增强**不是**依赖**。ES 挂了聊天就 500，等于给一条已验证稳定的链路新增一个故障源——而 ES 恰恰是这套架构里最容易挂的组件（单节点、1G 堆、插件要重启）。用户问一道不需要知识库的题，不该因为知识库连不上就问不了。

### 5.7 F 组：异步作业运维（管理员，仅 `rag.async=mq` 时有意义）

| # | 方法 | 路径 | 用途 |
|---|---|---|---|
| 18 | GET | `/ai/admin/mq/dlq` | 列出死信：`{routingKey, docId, failCount, lastError, deadTime}` |
| 19 | POST | `/ai/admin/mq/replay` | `{routingKey, docIds[]}` 把死信重投；**同时把对应 `ai_kb_doc.retry_count` 归零** |

【为什么 #19 必须连带重置 `retry_count`】死信进 DLQ 时 `retry_count` 已经到 3，若只重投消息不清计数，消费者抢到的瞬间就会被回收扫描判定"重试耗尽"再打回 `failed`——运维看到的是"重投了但没反应"，且日志里没有任何错误。这是 DLQ 重投最典型的坑。

---

## 6. 实现顺序

顺序里唯一不显然的决定：**先做读路径，再做写路径。** 第 2、3 步就把 embedding 和检索跑通了——切片手工 bulk 灌进 ES，不需要解析和状态机。
【为什么这么做】分词器和 embedding 模型这两个决定，只有在"真的搜一次"之后才知道选对没有；而写路径一旦跑起来索引里就有存量数据，改 mapping 要重建。先用最便宜的方式验证最贵的决定。

| 步 | 内容 | 成功信号 |
|---|---|---|
| 0 | 依赖与地基：pom 钉 `elasticsearch-java:9.5.4`、`CommonJwt` 加泛型 `Result<T>`、validation starter、`@RestControllerAdvice`、手工 `ElasticsearchClient` bean（`Rest5ClientTransport` + https + 信任 `http_ca.crt`）、密码走环境变量、mapper 加 `@Mapper` | `GET /ai/kb/ping` 返回集群 version 和别名列表 |
| 1 | `IndexRegistry` + `ChunkPlanner`（纯函数）+ 手工建两个索引 | `ChunkPlannerTest` 全绿；`_analyze` 中文切词符合预期 |
| 2 | `EmbeddingGateway`：≤20 分批、重试、维度断言 | 真实调用：一句中文出 1024 维；相近句 cosine>0.7、无关句<0.4 |
| 3 | `RetrievalService` 混合检索 + #8/#9（**读路径闭环**） | 手工灌 5 条切片后问"快排复杂度"能命中；debug 接口两条腿各有结果 |
| 4a | 写路径：`DocParser`(Tika) + `IngestService` + 状态机 + 回收扫描 + #1~#7，**派发走 `poll` 模式，不依赖 RabbitMQ** | 上传真 PDF 轮询到 `ready`，命中带正确页码；**中途 kill 再启动，卡住的任务被自动回收** |
| 4b | 接 RabbitMQ：拓扑声明 + `AFTER_COMMIT` 发布 + 手动 ack 消费者 + TTL 退避 + DLQ + #18/#19 | 停掉消费者上传文档 → 消息堆积不丢；起消费者后自动消化；把同一消息手工投两次 → 切片数与 embedding 调用次数**都不增加** |
| 5 | 题目索引：`QuestionIndexService` + 水位表 + 回灌 job + #10 | 全量回灌完成、对账零漂移；自然语言描述能搜到相似题 |
| 6 | 接进对话：`sources` → 检索 → `thought_step` → citation 落库 → prompt 注入 + 降级（此处顺带实测 1.4 的 advisor） | 《AI对话思考链路》10.2 里 RAG 相关项通过 |
| 7 | 录题：extract task + draft + review + convert + 触发单题索引 | 一份 5 题 docx → 5 条草稿 → 审核通过 → `qb_question` 有题且能被语义搜到 |

---

## 7. 测试方案

三层 + 一层人工。

### 7.1 第一层：单元测试（不碰任何外部系统）

- `ChunkPlanner`：token 预算、重叠区、段落边界优先、单段超预算硬切、空文档、纯英文、中英混排
- `EmbeddingGateway` 分批：21 条必须切成 20+1（**直接对应百炼 400 错误，是回归防线**）
- 状态机迁移：CAS 抢占成功/失败、重试耗尽转 `failed`、心跳超时回收
- 引用定格：ES 命中 → `ai_message_citation` 字段映射，尤其 `chunk_id=0`/`question_id=0` 哨兵不能写成 null

### 7.2 第二层：集成测试（直连本地 ES，不打 Docker）

决定**不引 Testcontainers**——本机 ES 是 zip 解压跑的，为测试再引入 Docker 依赖不值得。MySQL 侧**不用 H2**。三条纪律：

1. 测试用独立索引 `test_ai_kb_chunk_v1`，绝不碰业务索引
2. `@AfterAll` 删索引，跑完不留痕
3. 打 `@Tag("es-it")`，surefire 默认跳过，本地显式 `-Dgroups=es-it` 才跑

【为什么这么做】没有这三条纪律，集成测试会在任何没有 ES 的环境（同学机器、以后的 CI）把整个构建染红，然后所有人第一反应是加 `-DskipTests`——测试就此作废。默认跳过 + 显式开启是让测试活得久的做法。H2 不认 `DATETIME(3)`、`JSON` 类型和 `utf8mb4_0900_ai_ci`，测试全绿而真库建表失败是这类项目最常见的假安全感。

### 7.3 第三层：检索质量评测（RAG 特有，最重要）

RAG 的 bug 不表现为崩溃，表现为**一个看起来很对的错答案**。光测"接口 200、字段齐全"没有意义：切片参数从 500 调到 800，所有单测照样全绿，而检索质量可能已经烂了。唯一防线是 golden set：

```
AIModule/src/test/resources/rag/eval.jsonl
{"q":"快速排序为什么是 O(nlogn)","expect":[{"docId":41,"page":45}],"k":6}
{"q":"火车相遇问题求相遇时间","expect":[{"docId":37,"page":112}],"k":6}
```

每次改切片参数、换 analyzer、调 `similarity` 都跑一遍：

**golden set 绑定一批固定的本地语料。** 换语料或删文档会让 `docId`/页码全部失效，必须重标——所以第 4a 步入库的第一批教材要当作"评测基线语料"对待，不要随手删。

| 指标 | 含义 | 门槛 |
|---|---|---|
| **Recall@6** | 期望切片出现在前 6 条的比例 | ≥ 0.8 才允许接进对话 |
| **MRR** | 期望结果排名的倒数均值 | 只看趋势 |
| **空召回率** | 该有结果的 query 返回 0 条的比例 | ≤ 0.05 |
| **出处正确率** | 引用表页码/章节与期望一致 | 100%——出处错了比没有出处更糟 |

【为什么用排名指标而不是分数阈值】3.5 那条结论在这里兑现：RRF 的 `_score` 是名次的函数，跨 query 不可比，**没法设全局分数门槛**。能稳定比较的只有"第几名"。

### 7.4 第四层：人工抽查 20 问查幻觉

不做 LLM-as-judge 自动打分。【为什么这么做】在这个阶段属于过度设计——需要的不是"85 分"这个数字，而是亲眼看到"模型引用了第 45 页，但那页其实没讲这件事"。人工看 20 条约 20 分钟，收益高于搭一套打分流水线。

### 7.5 必须自动化的六条安全与健壮性断言

1. **越权检索 0 命中**：甲的 personal 文档用乙的身份检索，`docId` 交集为空——**两条腿各测一次，漏一条就算失败**
2. **删除即刻不可见**：`DELETE` 后同一 query 立刻搜不到
3. **ES 停机降级**：关掉 ES，`stream` 不 500，走 `delta → done` 且带"知识库不可用"那一步
4. **批量上限**：21 条文本不产生 400
5. **消息重复投递幂等**（`mq` 模式）：同一 `{docId}` 手工投两次 → `ai_kb_chunk` 行数不变、embedding 调用次数不变（用计数器断言，不是看结果）
6. **事务与发布顺序**（`mq` 模式）：让事务回滚（构造 `uk_scope_owner_hash` 冲突），断言队列里**没有**消息产生

### 7.6 性能基线（测数量级，不做压测）

单条 query 的 embedding 延迟、hybrid search 的 p50/p95（1 万和 10 万切片各测一次）、一份 200 页 PDF 端到端入库耗时。测法：#9 返回 `took`，入库耗时用 `ready_time - create_time`。
【为什么现在就要测】`num_candidates` 和 `rank_window_size` 的合理值完全取决于数据量级，10 万级和 100 万级调法相反。没有基线数字，3.5 说的"等看到实际排序再调"永远执行不了。

---

## 8. 风险清单

| 风险 | 兜底 |
|---|---|
| IK 装不上或重启后不加载 | 切 `cjk` 兜底（附录 A 实测可用），代价是重建索引；analyzer 走配置项，代码不改 |
| **ES java client 9.5.4 与 Boot 3.2.4 托管的 jackson 版本冲突** | **第 0 步就实测**。冲突则在 AIModule 覆盖 jackson 版本。这是整个设计里最不确定的一环，所以放在第一步暴露它 |
| 百炼 Key 限流或额度耗尽 | `EmbeddingGateway` 加令牌桶；批量作业可暂停续跑（状态机已支持） |
| `qb_question` 的 scope 假设未经数据核实 | 见 9.1，上线前必须确认 |
| 密码/Key 泄进 git | ES 密码与百炼 Key 一律走 Nacos 或环境变量，`application.yaml` 只留 `${ELASTIC_PASSWORD}` 占位。已在本机对话中出现过的 ES 密码建议轮换 |
| 心跳阈值 5 分钟与实际批次耗时不符 | 第 4a 步实测最慢批次后回调；阈值必须 > 最慢批次 |
| **消息已发但事务回滚，消费者拿到不存在的 docId** | 一律用 `@TransactionalEventListener(AFTER_COMMIT)` 发布（2.5.3 第 4 条），并由 7.5 第 6 条断言守住 |
| RabbitMQ 未安装/未启动，任务永不触发 | `rag.async=poll` 降级路径（2.5.6）；且兜底扫描在两种模式下都在，消息丢失不会静默 |
| 死信重投后仍立刻失败，运维以为"没反应" | #19 重投时连带重置 `retry_count`（5.7） |

---

## 9. 待确认假设（实现前必须核实，不是"到时候再说"）

### 9.1 `qb_question` 如何区分官方题库与个人题库

设计假定 `owner_id` 为 `NULL` 或 `0` 即平台官方题，否则为个人题。这个假设**没有经过数据核实**（`QbQuestion.java` 只有列定义，没有注释说明语义）。若假设错了，改的是 `QuestionIndexService` 一个类里的 scope 判定。上线前跑：

```sql
SELECT (owner_id IS NULL) AS is_null, owner_id = 0 AS is_zero, COUNT(*)
  FROM qb_question GROUP BY 1,2;
```

### 9.2 其余待核实项

| 项 | 核实方式 | 影响 |
|---|---|---|
| `RetrievalAugmentationAdvisor` 在 M7 是否支持 stream 链 | 第 6 步一次性实测 | 只影响注入方式，不影响其他任何一节 |
| 百炼 `text-embedding-v3` 单条输入 token 上限 | 官方文档 + 一次超长调用 | 切片预算 500 远低于任何合理上限，只影响"是否要加硬校验" |
| `users` 表的用户名列与 `user_name` 的实际对应关系 | 读 `Users.java` 与真实数据 | 影响 3.4 的 JOIN 回灌 SQL |
| `qb_question.subject_id` 是否存在 NULL | `SELECT COUNT(*) FROM qb_question WHERE subject_id IS NULL` | 有则按 3.4 在回灌时 `IFNULL(...,0)`，不改别人的表 |
| 本机是否已有 Docker | 一条命令 | 若已有，7.2 可改用 Testcontainers，但不改也不影响正确性 |
| RabbitMQ 用哪种方式装（Windows 原生要 Erlang + 服务；Docker 一条命令） | 你决定 | 只影响 4b 的开始时间；`poll` 模式下第 0~7 步全部可完成 |

---

## 附录 A：本机环境实测记录（2026-10-12）

| 事实 | 实测方式 | 结果 |
|---|---|---|
| ES 版本 | `curl -k https://127.0.0.1:9200/` | 9.5.4，Lucene 10.5.1，zip build，`minimum_index_compatibility_version` 8.0.0 |
| ES 需要认证与 TLS | 明文 http 返回 `Empty reply`；https 未带凭据返回 401 `security_exception` | 是 |
| 已装插件 | `GET /_cat/plugins?v` | **空**，一个都没有 |
| `ik_smart`/`ik_max_word`/`smartcn` | `POST /_analyze` | `failed to find global analyzer` |
| `standard` 分中文 | `POST /_analyze` | 切成单字（`快/速/排/序/...`）——不装 IK 时 BM25 的真实表现 |
| `cjk` 分中文 | `POST /_analyze` | 可用，二元 gram（`快速/速排/排序/...`） |
| IK 9.5.4 安装包 | `curl -sIL get.infini.cloud/...` | HTTP 200，`application/zip`，4619662 字节 |
| `elasticsearch-java` 9.x 可用性 | Maven Central metadata | 9.5.4 存在 |
| 9.5.4 客户端的传输层依赖 | 其 pom | `elasticsearch-rest5-client`（**无** `elasticsearch-rest-client`）；JSON 走 `jakarta.json-api` + `jackson-core/databind` |
| Boot 3.2.4 托管的 ES 客户端版本 | `spring-boot-dependencies-3.2.4.pom` | `<elasticsearch-client.version>8.10.4</elasticsearch-client.version>` |
| 百炼 embedding 批量上限 | 官方文档与多方实测复述 | **单次最多 20 条，超过报 400** |
| 仓库 ES/embedding 足迹 | 全仓 grep | **零**。无 ES 依赖、无 `EmbeddingModel` 配置、无 `.sql` 文件 |
| AIModule 基础设施 | 读代码 | 无 `Result`、无 `@MapperScan`、无 validation 实现、无 `@RestControllerAdvice` |
| 网关 `/ai/**` 路由 | `GatewayModule/application.yaml` 全注释，真实路由在 Nacos | 本地不可验证 |
| MQ 足迹 | grep 全仓 `amqp\|rabbit\|kafka\|rocketmq`；`netstat` 查 5672/15672 | **零**：无依赖、无 `@RabbitListener`、无监听进程——RabbitMQ 尚未安装 |
