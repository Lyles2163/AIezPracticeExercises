# RAG 知识库接口详细流程文档

> 本文档基于 `AIModule/src/main/java/org/leon/aimodule/rag/` 包下的实际代码编写，覆盖文档入库、检索、上下文拼装、权限、配置、数据模型与异常处理全链路。

---

## 目录

1. [接口总览](#1-接口总览)
2. [权限模型与认证](#2-权限模型与认证)
3. [文档入库流程（Ingest）](#3-文档入库流程ingest)
4. [切片策略（ChunkPlanner）](#4-切片策略chunkplanner)
5. [向量化（EmbeddingGateway）](#5-向量化embeddinggateway)
6. [Elasticsearch 写入（RagIndexWriter）](#6-elasticsearch-写入ragindexwriter)
7. [检索流程（Search）](#7-检索流程search)
8. [混合检索与 RRF 融合](#8-混合检索与-rrf-融合)
9. [上下文拼装（RagContextService）](#9-上下文拼装ragcontextservice)
10. [重放（Reindex）与删除](#10-重放reindex与删除)
11. [数据模型](#11-数据模型)
12. [配置参数（RagProperties）](#12-配置参数ragproperties)
13. [异常处理](#13-异常处理)
14. [关键设计决策与铁律](#14-关键设计决策与铁律)

---

## 1. 接口总览

RAG 模块提供 3 类 HTTP 接口，全部位于 `/ai` 前缀下：

| 控制器 | 路径前缀 | 职责 |
|--------|---------|------|
| `RagDocumentController` | `/ai/kb/documents` | 文档上传、列表、详情、切片查看、重放、删除 |
| `KbSearchController` | `/ai` | 知识检索、检索调参（debug） |
| `KbPingController` | `/ai/kb` | ES 集群健康检查 |

### 1.1 文档管理接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/ai/kb/documents` | 上传文档并同步入库 |
| `GET` | `/ai/kb/documents` | 文档列表（官方库 + 本人个人库） |
| `GET` | `/ai/kb/documents/{id}` | 文档详情 |
| `GET` | `/ai/kb/documents/{id}/chunks` | 文档切片明细（排查用） |
| `POST` | `/ai/kb/documents/{id}/reindex` | 重放未入 ES 的切片 |
| `DELETE` | `/ai/kb/documents/{id}` | 删除文档 |

### 1.2 检索接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/ai/kb/search` | 混合检索（BM25 + kNN → RRF） |
| `POST` | `/ai/retrieval/debug` | 检索调参（需管理员） |
| `GET` | `/ai/kb/ping` | ES 集群信息 |

### 1.3 统一响应结构

所有接口返回 `Result<T>`：

```json
{
  "code": 200,
  "msg": "success",
  "data": { ... }
}
```

---

## 2. 权限模型与认证

### 2.1 身份传递

当前阶段网关只注入请求头 `X-User-Name`，不携带角色信息。所有需要身份的接口都从该请求头取用户名。

### 2.2 scope 与可见性

| scope | 可见范围 | 上传权限 | 删除权限 |
|-------|---------|---------|---------|
| `official`（官方库） | 所有登录用户可见 | 仅管理员 | 仅管理员 |
| `personal`（个人库） | 仅上传者本人可见 | 任意登录用户 | 仅本人 |

### 2.3 管理员判定

`RagAdminGuard` 读取 `rag.adminNames` 配置的白名单：
- 名单为空 → 所有管理员操作一律拒绝（避免默认放行）
- `scope=official` 上传、`/ai/retrieval/debug`、删除官方库文档均需管理员

---

## 3. 文档入库流程（Ingest）

入口：`POST /ai/kb/documents`（`multipart/form-data`）

### 3.1 请求参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `file` | MultipartFile | 是 | - | 文档文件 |
| `scope` | String | 否 | `personal` | `official` 或 `personal` |
| `title` | String | 否 | null | 显式标题，空则从正文首行推导 |
| `book` | String | 否 | null | 书名/课程名 |
| `subjectId` | Long | 否 | null | 学科 ID（0=通用） |
| `X-User-Name` | Header | personal 时必填 | - | 上传者用户名 |

### 3.2 完整流程

```
┌─────────────────────────────────────────────────────────────┐
│ 1. 校验 (validate)                                           │
│    - 文件非空、≤20MB                                          │
│    - scope 合法、personal 时用户名非空                         │
│    - 扩展名白名单 (txt/md/markdown/text)                       │
├─────────────────────────────────────────────────────────────┤
│ 2. SHA-256 去重 (按 scope + owner + file_hash)                 │
│    - 已存在且 status=indexed → 直接返回旧文档（不重复花钱）      │
│    - 已存在且 status=failed  → 清理旧行后重新入库               │
├─────────────────────────────────────────────────────────────┤
│ 3. 解析 (parse)                                               │
│    - PlainTextDocumentParser: UTF-8 严格解码 → GBK 兜底        │
│    - 去 BOM、统一换行、Markdown 标题去井号                      │
├─────────────────────────────────────────────────────────────┤
│ 4. 切片 (chunkPlanner.plan)                                   │
│    - 段落 → 句子 → token 硬切                                  │
│    - 单片 ≤500 token，相邻片重叠 60 token                      │
│    - 识别章节标题维护 chapter_path                             │
├─────────────────────────────────────────────────────────────┤
│ 5. MySQL 落库 (RagChunkPersister.persist) —— 【事务内】        │
│    - 插入 rag_document 行（status=chunking）                   │
│    - 批量插入 rag_chunk 行（index_status=pending）             │
│    - 文档自增 id 回填失败 → 回滚                               │
├─────────────────────────────────────────────────────────────┤
│ 6. 状态置为 embedding，读回切片拿到 chunk id                    │
├─────────────────────────────────────────────────────────────┤
│ 7. Embedding (EmbeddingGateway.embed) —— 【事务外】           │
│    - 按 batchSize=10 分批，concurrency=4 并发                   │
│    - 每批最多重试 3 次（指数退避）                               │
│    - 返回顺序与入参严格一致                                     │
├─────────────────────────────────────────────────────────────┤
│ 8. 写 ES (RagIndexWriter.write) —— 【事务外】                 │
│    - bulk 批量写入，_id = rag_chunk.id                         │
│    - 写完立即 refresh                                          │
├─────────────────────────────────────────────────────────────┤
│ 9. 回写状态                                                    │
│    - rag_chunk.index_status = indexed                         │
│    - rag_document.status = indexed, chunk_count, token_count  │
├─────────────────────────────────────────────────────────────┤
│ 10. 回读一次 rag_document（拿到 create_time/update_time）       │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 事务边界设计

事务**刻意分成两段**，而不是一个大事务：

| 阶段 | 事务 | 原因 |
|------|------|------|
| MySQL 落文档行 + 切片行 | ✅ `@Transactional`（`RagChunkPersister`） | 数据一致性，要么全成要么全不成 |
| Embedding + 写 ES | ❌ 事务外 | 外部 HTTP 耗时数秒到数十秒，圈进事务会长时间占连接池和行锁 |

**顺序铁律：先 MySQL 后 ES。** 反过来一旦 MySQL 失败，ES 里就会出现没有源的孤儿切片。

> ⚠️ `@Transactional` 靠代理生效，同类内部自调用（`this.persist(...)`）不经过代理，注解会被静默忽略。因此 MySQL 落库拆成独立 Bean `RagChunkPersister`，由 `RagDocumentService` 跨 Bean 调用。

### 3.4 失败处理

任一阶段失败时：
- 若文档行已落库（id != null）→ `rag_document.status = failed`，记录 `error_msg`（截断到 512 字）
- 已成功写入 ES 的部分切片不回滚，切片停留在 `pending`
- 抛出 `RagException`，由 `AiExceptionHandler` 转成 `Result.error(...)`
- 用户可调用 `reindex` 重放未入 ES 的切片

---

## 4. 切片策略（ChunkPlanner）

### 4.1 核心参数

| 参数 | 值 | 说明 |
|------|-----|------|
| `BUDGET_TOKENS` | 500 | 单个 chunk 的 token 上限 |
| `OVERLAP_TOKENS` | 60 | 相邻 chunk 间保留的重叠 token |
| Token 计数 | CL100K_BASE | 委托给 `TokenCounter` |

### 4.2 切分优先级

```
段落 → 句子 → token 硬切
```

1. **段落切分**：按空行分块；块内若有多行再按行拆开
2. **句子切分**：按 `。！？!?；;` 与 `[.!?]+空白` 切分（零宽断言，保留标点）
3. **Token 硬切**：单句仍超预算时，按 token 边界硬切兜底

### 4.3 滑动窗口算法

```
窗口累积 segment
  ├─ 拼接后 token ≤ 500 → 继续加入
  └─ 拼接后 token > 500 → 封箱为 chunk，从上一片尾部取 60 token 重叠作为新窗口起点
                            ├─ 尾巴为空 → 新窗口为空
                            └─ 尾巴+下一片段仍超预算 → 放弃重叠（避免挤压后续内容）
```

### 4.4 章节路径识别

识别三种标题样式，维护最多 3 层的章节栈：

1. `第 X 章/节/篇 …`（中文数字或阿拉伯数字）
2. `1 / 1.2 / 1.2.3 …` 数字编号
3. `一、 / 二、 …` 中文序号

- 顶层标题（"第X章/篇"或"1."开头）清空历史栈
- 每个 chunk 的 `chapter_path` 取窗口内最深的章节路径
- 供召回过滤与溯源展示使用

### 4.5 Markdown 标题处理

`PlainTextDocumentParser` 会把 `## 3.2 常见排序算法` 的井号前缀去掉，使 `ChunkPlanner` 的数字编号正则能命中，否则 `chapter_path` 会整片为空。

---

## 5. 向量化（EmbeddingGateway）

全项目唯一的向量出口。

### 5.1 核心逻辑

```
输入: List<String> texts
  │
  ├─ 按 batchSize=10 分批（硬性分批，百炼单次最多 20 条）
  │
  ├─ 每批在 rag-embedding 线程池并发执行（concurrency=4）
  │     ├─ 调用 EmbeddingClient.embed(batch)
  │     ├─ 维度/条数校验（1024 维）
  │     └─ 失败重试：最多 3 次，指数退避（500ms → 1000ms → 2000ms）
  │           ├─ EmbeddingException（维度/条数不符）→ 不重试，立即失败
  │           └─ 其他 RuntimeException → 重试
  │
  └─ 按批顺序拼接返回 List<float[]>
```

### 5.2 关键保证

- **顺序一致**：返回的向量列表下标与入参 texts 严格对应，下游可直接按下标对齐
- **维度校验**：每条向量必须 1024 维，不符则抛 `EmbeddingException`
- **调用计数**：`callCount()` 用于幂等测试断言

---

## 6. Elasticsearch 写入（RagIndexWriter）

### 6.1 ES 索引结构

索引别名 `ai_kb_chunk` → 物理索引 `ai_kb_chunk_v1`

| 字段 | 类型 | 说明 |
|------|------|------|
| `chunk_id` | long | = rag_chunk.id |
| `doc_id` | long | = rag_document.id |
| `scope` | keyword | official / personal |
| `owner_name` | keyword | personal 时为上传者，official 时为空串 |
| `subject_id` | long | 学科 ID（0=通用） |
| `title` | text(+kw) | 标题，权重 ×2 |
| `content` | text | 切片正文（ik_max_word 索引 / ik_smart 搜索） |
| `vec` | dense_vector | 1024 维，cosine 相似度，int8_hnsw 索引 |
| `book` | keyword | 书名 |
| `chapter_path` | keyword | 章节路径 |
| `page_no` | integer | 页码 |
| `token_count` | integer | token 数 |

### 6.2 写入铁律

> **ES 文档的 `_id` 必须等于 `rag_chunk.id`。**

用同一个数字寻址，删除文档时才能按 id 精确删掉 ES 里那批切片，不留孤儿。

### 6.3 写入流程

```
1. 校验 chunks.size() == vectors.size()（契约错误：错位会把向量安到别人的正文上）
2. 按 BULK_SIZE=200 分批 bulk 写入
3. 每条 bulk 操作: index, id=chunk.id, document=KbChunkDoc
4. 收集成功/失败条目
5. 写完立即 refresh（默认 refresh_interval=30s，不刷新则用户传完马上搜不到）
6. 有失败 → 抛 RagException（已成功的 id 仍可返回）
```

### 6.4 删除

`deleteByDocId(docId)` 用 `delete_by_query` 按 `doc_id` 删除，而非按 id 逐条删（id 列表可能有几百个）。版本冲突跳过（不阻断）。

---

## 7. 检索流程（Search）

入口：`POST /ai/kb/search`

### 7.1 请求体（RetrievalQuery）

```json
{
  "query": "快速排序的时间复杂度",      // 必填
  "topK": 6,                           // 可选，1-20，默认 6
  "subjectId": 0,                      // 可选，null=不限；0=只看通用；>0=本学科 OR 通用
  "scope": ["official", "personal"]   // 可选，默认两者都查
}
```

### 7.2 完整流程

```
┌──────────────────────────────────────────────────────────┐
│ 1. 构造 SearchOptions                                      │
│    - scope 校验（只能是 official/personal）                 │
│    - personal 时用户名非空                                  │
│    - topK 取 min(topK, topKMax=20)                         │
├──────────────────────────────────────────────────────────┤
│ 2. 查询向量化 embedQuery(query)                            │
│    - 调用 EmbeddingGateway 得到 1024 维向量                  │
├──────────────────────────────────────────────────────────┤
│ 3. 构建权限过滤器 PermissionFilters.build()                 │
│    - scope + owner_name 过滤                                │
│    - subject_id 过滤（0 或指定学科）                         │
│    - 【必须同时传给 BM25 和 kNN 两条腿】                     │
├──────────────────────────────────────────────────────────┤
│ 4. BM25 查询 (multi_match: title^2, content)               │
│    - size = rankWindowSize = 50                             │
├──────────────────────────────────────────────────────────┤
│ 5. kNN 查询 (vec 字段, cosine 相似度)                       │
│    - k = 20, numCandidates = 100, similarity = 0.55        │
│    - size = rankWindowSize = 50                             │
├──────────────────────────────────────────────────────────┤
│ 6. RRF 融合 (RrfFusion.fuse)                                │
│    - score = Σ 1 / (rankConstant + rank)                   │
│    - rankConstant = 60                                     │
│    - 取 topK 条                                             │
├──────────────────────────────────────────────────────────┤
│ 7. 组装 KnowledgeSearchResponse                             │
│    - took = bm25.took + knn.took（串行执行）                 │
│    - items: chunkId, docId, rank, cosine, title, book,     │
│             chapterPath, pageNo, content                    │
└──────────────────────────────────────────────────────────┘
```

### 7.3 余弦相似度反解

ES 对 cosine 类型的 dense_vector 返回 `_score = (1 + cos) / 2`，因此：

```java
cosine = 2 * score - 1
```

> ⚠️ ES 9.5 起 dense_vector 不再留在 `_source` 里，所以余弦只能从 kNN 腿取。纯 BM25 命中的结果 `cosine` 为 null。

### 7.4 响应示例

```json
{
  "code": 200,
  "data": {
    "took": 45,
    "empty": false,
    "items": [
      {
        "chunkId": 123,
        "docId": 45,
        "rank": 1,
        "cosine": 0.8765,
        "title": "算法导论",
        "book": "算法导论",
        "chapterPath": "第7章 → 7.2 快速排序的性能",
        "pageNo": 1,
        "content": "快速排序的平均时间复杂度是 O(n log n)..."
      }
    ]
  }
}
```

---

## 8. 混合检索与 RRF 融合

### 8.1 为什么用混合检索

| 检索腿 | 优势 | 劣势 |
|--------|------|------|
| BM25（关键词） | 精确词命中好，无需 embedding | 同义词、语义相似查不到 |
| kNN（向量） | 语义相似召回好 | 关键词精确匹配弱，0.55 闸门会卡掉无关向量 |

两者互补，用 RRF 融合兼顾精确与语义。

### 8.2 为什么在 Java 侧融合

ES 原生 `rrf retriever` 属于 **Platinum+ 许可**，本机与生产都是 basic 许可，因此在 Java 侧实现。

### 8.3 RRF 算法

```
score(doc) = Σ 1 / (rankConstant + rank_leg)
```

- 对 BM25 和 kNN 各自的排名列表分别计算分数后求和
- `rankConstant = 60`：控制排名靠前结果的优势衰减速度
- 排序：score 降序 → bestRank 升序 → id 升序（稳定排序）
- 取前 `topK` 条

### 8.4 Debug 接口

`POST /ai/retrieval/debug`（需管理员）把两条腿各自前 50 名摊开，用于调参定位：
- 是切片问题？embedding 问题？BM25 问题？还是 RRF 权重问题？
- 每条结果显示 rank、score、cosine、title、content

---

## 9. 上下文拼装（RagContextService）

检索与对话之间唯一的胶水层。chat 编排层只调这一个入口，不直接接触 `RetrievalService`。

### 9.1 流程

```
输入: question, userName, RagOptions(scopes, subjectId, topK)
  │
  ├─ 1. 调用 RetrievalService.searchKnowledge()
  │
  ├─ 2. 检索为空 → 返回 RagContext.EMPTY（context=""，citations=[]）
  │
  ├─ 3. 拼装 prompt 上下文:
  │     "以下是检索到的参考资料（每条以 [n] 编号，回答时如引用请在句末标注对应编号）：
  │
  │      [1] 《书名》 · 章节路径 · 标题（第 X 页）
  │      切片正文...
  │
  │      [2] ..."
  │
  └─ 4. 同时产出 citations 列表给前端展示
        RagCitation(index, docId, chunkId, title, book, chapterPath, pageNo, cosine)
```

### 9.2 输出

```java
record RagContext(String context, List<RagCitation> citations)
```

- `context`：可直接拼进 system prompt
- `citations`：原样发给前端展示引用列表

---

## 10. 重放（Reindex）与删除

### 10.1 重放

`POST /ai/kb/documents/{id}/reindex`

**场景**：MySQL 写成功但 ES 写失败时的补救入口。切片正文存在 `rag_chunk.content` 里，不必让用户重新上传。

```
1. 查 rag_document 是否存在
2. 查 index_status != 'indexed' 的切片（pending/failed）
3. 没有待补切片 → 直接返回
4. 置 status=embedding
5. 对待补切片做 embedding + 写 ES（复用 indexToEs）
6. 回写 rag_document.status=indexed, chunk_count, token_count
```

### 10.2 删除

`DELETE /ai/kb/documents/{id}`

**顺序：先删 ES，再删 MySQL**（与入库相反）。

```
1. 查 rag_document，校验权限（official→管理员，personal→本人）
2. indexWriter.deleteByDocId(docId) —— 先删 ES
   └─ 失败 → 抛异常，不删 MySQL（保留 doc_id 线索，下次可重试）
3. persister.deleteDocument(docId) —— 再删 MySQL（事务内：切片行 + 文档行）
```

> 为什么先删 ES：如果先删 MySQL，一旦 ES 删失败，就再也不知道该按哪个 doc_id 去清理那批孤儿切片了。

---

## 11. 数据模型

### 11.1 rag_document（文档级）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint AUTO | 主键 |
| `file_name` | varchar(255) | 原始文件名 |
| `title` | varchar(255) | 标题 |
| `book` | varchar | 书名/课程名 |
| `scope` | varchar | official / personal |
| `owner_name` | varchar | 上传者（official 时空串） |
| `subject_id` | bigint | 学科 ID（0=通用） |
| `source_path` | varchar | 原文件落盘路径 |
| `file_hash` | varchar(64) | SHA-256 小写（去重唯一键组成部分） |
| `file_size` | bigint | 字节数 |
| `mime_type` | varchar | 浏览器声明的 MIME |
| `status` | varchar | pending/parsing/chunking/embedding/indexed/failed |
| `chunk_count` | int | 切片数 |
| `token_count` | int | 总 token 数 |
| `error_msg` | varchar(512) | 失败原因 |
| `create_time` | datetime(3) | 创建时间 |
| `update_time` | datetime(3) | 更新时间 |

**唯一键**：`(scope, owner_name, file_hash)`

**状态流转**：
```
pending → parsing → chunking → embedding → indexed
                                              ↘ failed
```

### 11.2 rag_chunk（切片级）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint AUTO | 主键 = ES 的 _id |
| `doc_id` | bigint | 所属文档 |
| `seq` | int | 文档内顺序（从 1 开始） |
| `content` | text | 切片正文（重建索引/重放的唯一来源） |
| `token_count` | int | token 数 |
| `page_no` | int | 页码 |
| `chapter_path` | varchar | 章节路径 |
| `index_status` | varchar | pending/embedded/indexed/failed |
| `error_msg` | varchar | 失败原因 |
| `create_time` | datetime(3) | 创建时间 |
| `update_time` | datetime(3) | 更新时间 |

**唯一键**：`(doc_id, seq)`

> ⚠️ 向量刻意不落库：1024 维 float ≈ 4KB/条，MySQL 存了也检索不了，只有 ES 能做 kNN。`content` 是重建索引的唯一来源。

---

## 12. 配置参数（RagProperties）

配置前缀 `rag.`

### 12.1 顶层

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `rag.analyzer` | `ik` | 索引分析器（ik \| cjk，建索引后不可改） |
| `rag.admin-names` | `[]` | 管理员白名单 |
| `rag.async` | `poll` | 异步模式（poll \| mq） |

### 12.2 elasticsearch

| 参数 | 默认值 |
|------|--------|
| `rag.elasticsearch.uris` | `https://127.0.0.1:9200` |
| `rag.elasticsearch.username` | `elastic` |
| `rag.elasticsearch.password` | (空) |
| `rag.elasticsearch.ca-cert-path` | `F:/ES/.../http_ca.crt` |

### 12.3 index

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `rag.index.auto-create` | `true` | 启动时自动创建索引 |
| `rag.index.kb-alias` | `ai_kb_chunk` | 知识库切片别名 |
| `rag.index.kb-physical` | `ai_kb_chunk_v1` | 物理索引名 |
| `rag.index.question-alias` | `ai_question` | 题目索引别名 |
| `rag.index.question-physical` | `ai_question_v1` | 题目物理索引名 |

### 12.4 embedding

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `rag.embedding.dimensions` | `1024` | 向量维度 |
| `rag.embedding.batch-size` | `10` | 每批条数（百炼上限 20） |
| `rag.embedding.concurrency` | `4` | 并发批次数 |

### 12.5 search

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `rag.search.top-k-default` | `6` | 默认返回条数 |
| `rag.search.top-k-max` | `20` | 最大返回条数 |
| `rag.search.rrf-rank-constant` | `60` | RRF 排名常数 |
| `rag.search.rank-window-size` | `50` | 每条腿取前 N 名参与融合 |
| `rag.search.knn-k` | `20` | kNN 的 k |
| `rag.search.num-candidates` | `100` | kNN 候选数 |
| `rag.search.knn-similarity` | `0.55` | kNN 余弦相似度闸门 |

### 12.6 ingest

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `rag.ingest.max-file-bytes` | `20MB` | 单文件大小上限 |
| `rag.ingest.max-chunks-per-doc` | `2000` | 单文档切片数上限 |
| `rag.ingest.mysql-batch-size` | `200` | MySQL 批量插入条数 |
| `rag.ingest.storage-dir` | (空) | 原文件落盘目录（空则不存原件） |
| `rag.ingest.allowed-extensions` | `txt,md,markdown,text` | 允许的文件扩展名 |

---

## 13. 异常处理

`AiExceptionHandler` 通过 `@RestControllerAdvice(basePackages = "org.leon.aimodule.rag")` 统一拦截。

| 异常类型 | HTTP 响应 | 说明 |
|---------|----------|------|
| `MethodArgumentNotValidException` | `参数校验失败: field message` | `@Valid` 校验失败 |
| `MissingRequestHeaderException` | `缺少请求头: X-User-Name` | 缺少必填请求头 |
| `RagException` | 异常的 message | 业务异常（warn 日志） |
| `ElasticsearchException` / `TransportException` / `IOException` | `检索服务暂时不可用` | ES 不可用（warn 日志） |
| `Exception`（其他） | `服务内部错误` | 未预期异常（error 日志，打印堆栈） |

> ⚠️ Controller 必须放在 `org.leon.aimodule.rag` 包下，否则 `RagException` 不会被转成 `Result`，前端拿到的是 Boot 默认错误体。

---

## 14. 关键设计决策与铁律

### 14.1 铁律清单

| 铁律 | 原因 |
|------|------|
| **ES `_id` = `rag_chunk.id`** | 删文档时才能按 id 精确清理，不留孤儿切片 |
| **先 MySQL 后 ES** | 反过来 MySQL 失败会产生没有源的 ES 孤儿切片 |
| **删除时先 ES 后 MySQL** | 保留 doc_id 线索，ES 删失败可重试 |
| **向量不落 MySQL** | 1024 维 float ≈ 4KB/条，MySQL 存了也检索不了 |
| **切片正文 `content` 必须落库** | 重建索引与重放 embedding 的唯一来源 |
| **权限 filter 必须同时传给 BM25 和 kNN** | 漏一条腿就泄露他人私人库 |
| **official 的 `owner_name` 恒为空串** | 让唯一键 `(scope, owner, hash)` 能拦住官方库重复上传 |
| **Embedding 返回顺序与入参严格一致** | 向量按下标与切片对齐，错位是最坏的静默错误 |
| **`@Transactional` 方法必须跨 Bean 调用** | 同类自调用不经过代理，注解被静默忽略 |

### 14.2 关键决策

1. **事务只包 MySQL，不包 embedding/ES**：外部 HTTP 耗时数秒到数十秒，圈进事务会拖垮连接池。失败时切片停在 `pending`，可重放，数据不丢。

2. **重复上传直接返回旧文档**：同 `(scope, owner, hash)` 已存在且成功，不再付 embedding 的钱，也不在 ES 堆两份。但 `failed` 状态的旧行会被清理后重传（避免"重传成功但实际返回 failed 旧行"的静默撒谎）。

3. **编码探测用严格模式**：GBK 文件用 UTF-8 替换模式解码会静默读成乱码，写进 ES 后永远检索不中且日志看不出异常。所以用 `REPORT` 模式，UTF-8 失败再退 GBK。

4. **kNN 相似度闸门 0.55**：与 embedding 活体测试一致——相近句 > 0.7，无关句 < 0.55，差值 > 0.2。0.55 是卡掉无关向量的阈值。

5. **RRF 在 Java 侧实现**：ES 原生 rrf retriever 需 Platinum+ 许可，basic 许可下只能自己实现。

6. **`chapter_path` 取窗口内最深路径**：一个 chunk 可能跨多个小节，取最深的章节路径，避免早期小节覆盖后续信息。

7. **切片 overlap 60 token**：减少跨片语义断裂。但如果 overlap + 下一片段立刻超预算，则放弃重叠（避免挤压后续内容）。

---

## 附录：核心类索引

| 类 | 路径 | 职责 |
|----|------|------|
| `RagDocumentController` | `web/` | 文档管理接口 |
| `KbSearchController` | `web/` | 检索接口 |
| `KbPingController` | `web/` | ES 健康检查 |
| `RagAdminGuard` | `web/` | 管理员判定 |
| `AiExceptionHandler` | `web/` | 统一异常处理 |
| `RagDocumentService` | `ingest/` | 入库总编排 |
| `RagChunkPersister` | `ingest/` | MySQL 落库（事务边界） |
| `RagDocumentQueryService` | `ingest/` | 文档查询（列表/详情/切片） |
| `IngestCommand` | `ingest/` | 入库请求 DTO |
| `DocumentVo` | `ingest/` | 文档对外视图 |
| `ChunkPlanner` | `chunk/` | 切片规划 |
| `TokenCounter` | `chunk/` | CL100K_BASE token 计数 |
| `PlainTextDocumentParser` | `parse/` | 纯文本/Markdown 解析 |
| `EmbeddingGateway` | `embedding/` | 向量出口（分批+并发+重试） |
| `RagIndexWriter` | `index/` | ES 写入/删除 |
| `IndexMappings` | `index/` | ES mapping 定义 |
| `RetrievalService` | `search/` | 检索总入口 |
| `HybridQueryBuilder` | `search/` | BM25 + kNN 查询构造 |
| `PermissionFilters` | `search/` | 权限过滤器 |
| `RrfFusion` | `search/` | RRF 融合纯函数 |
| `RagContextService` | `qa/` | 上下文拼装（检索→对话胶水层） |
| `RagProperties` | `config/` | 配置参数 |
| `RagDocument` | `entity/` | 文档实体 |
| `RagChunk` | `entity/` | 切片实体 |
