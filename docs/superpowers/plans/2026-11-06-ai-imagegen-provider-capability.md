# AI 生图模块 + 自定义服务商能力扩展（后端）实施计划

> 日期：2026-11-06
> 模块：`AIModule`
> 来源需求：新增 AI 生图文件夹与接口，图片落本地 MinIO；自定义服务商要区分「聊天服务 / 生图服务 / RAG 服务」；数据仍然只用 `ai_user_model_provider`、`ai_user_model` 两张表。

---

## 0. 四个已定决策

| 决策 | 结论 |
|---|---|
| 能力如何落表 | `ai_user_model_provider` 加 `service_types`（CSV 集合，如 `CHAT,IMAGE`）；`ai_user_model` 加 `model_type`（单值：`CHAT`/`IMAGE`/`EMBEDDING`/`RERANK`）。同一供应商一条记录可同时提供聊天 + 生图。 |
| 生图覆盖范围 | 第一版只做文生图：OpenAI 兼容 `POST {baseUrl}/images/generations`（`prompt`/`size`/`n`，`response_format=b64_json`）。图生图/编辑、variations 不做。 |
| 图片怎么回前端 | 后端代理回源：接口只回 `objectKey` + `GET /ai/imagegen/files/**`，由 AIModule 从 MinIO 读出转发。MinIO 桶保持私有，链接不过期，端口不对外。 |
| RAG 模型范围 | 这次只落 schema/enum/接口字段（`model_type=EMBEDDING/RERANK`，服务商可声明 `RAG`），设置页能登记、能列表。**运行时不接** `EmbeddingGateway` / 检索链。 |

---

## 1. 现状（已核对，别重猜）

两张表已在 MySQL 里手工建好，仓库 `db/migration/` 里没有它们的 DDL：

- `ai_user_model_provider`：`id` / `user_name` / `provider_name` / `protocol` / `base_url` / `api_key_ciphertext` / `api_key_nonce` / `api_key_fingerprint` / `status` / `create_time` / `update_time`
- `ai_user_model`：`id` / `provider_id` / `user_name` / `model_key` / `display_name` / `supports_thinking` / `status` / `create_time` / `update_time`

现有代码：

- `org.leon.aimodule.chat.provider`：两个实体 + BaseMapper、`ApiKeyCryptoService`（AES-GCM）、`BaseUrlGuard`（SSRF 闸门）、`ProviderProperties`、`ProviderRequests`、`ProviderViews`、`ProviderRoute`、`ProviderService`、`ProviderController`（`/ai/chat/providers`，8 个端点）、`ProviderProbe`、`UserModelCatalogService`、`ModelSelection`（选择器 key `u:{providerId}:{modelId}`）。
- 聊天链：`ModelResolver` → `UserModelCatalogService.resolve()` → `ProviderRoute` → `PreparedTurn`。**个人模型一律不带 tools**（`supportsTools()` 恒 false）。
- 异常约定：业务拒绝 = HTTP 200 + `Result(code=0, msg)`，由 `chat/web/ChatExceptionHandler`（`basePackages = org.leon.aimodule.chat`）兜底。
- 鉴权约定：所有非白名单请求由网关校验 JWT 后注入 `X-User-Name`；`<img src>` 发不出 `Authorization`，所以前端取图必须用带 header 的 fetch + blob URL（见 §6.4）。
- `pom.xml` 目前没有 MinIO 依赖；`AIModule` 端口 8105，MinIO 本机未启动（127.0.0.1:9000 探测不通），Docker 29.9.2 可用。

---

## 2. 能力模型

```
ServiceType (provider 级，可多选)        ModelType (model 级，单选)
  CHAT  ─────────────────────────────►  CHAT
  IMAGE ─────────────────────────────►  IMAGE
  RAG   ─────────────────────────────►  EMBEDDING
                                     └► RERANK
```

规则：

1. 建/改模型时，`model_type` 必须被所属供应商的 `service_types` 覆盖，否则拒绝：`该供应商未声明生图服务` / `该供应商未声明 RAG 服务`。
2. `service_types` 缺省（老前端不传、老数据 null）按 `CHAT` 处理；`model_type` 缺省按 `CHAT` 处理。这样升级过程零迁移风险。
3. 聊天模型目录（`/ai/chat/models`）只收 `model_type=CHAT` 且供应商声明 `CHAT` 的模型。
4. 生图模型目录（`/ai/imagegen/models`）只收 `model_type=IMAGE` 且供应商声明 `IMAGE` 的模型。
5. `UserModelCatalogService.resolve()` 给聊天链用，遇到非 `CHAT` 模型一律「模型不存在」，避免 user 拿生图模型去打 `/chat/completions`。

---

## 3. 生图链路

```
POST /ai/imagegen/generations  { modelKey, prompt, size?, n? }
  └─ ImageGenController
      └─ ImageGenService
          ├─ UserModelCatalogService.resolveImage(user, modelKey)   → ProviderRoute(baseUrl, apiKey, upstreamModel)
          ├─ OpenAiImageGateway.generate(...)                       → List<byte[]> + contentType
          └─ ImageObjectStore.put(user, contentType, bytes)         → objectKey
      ← { images: [{ objectKey, url, bytes, contentType }], modelKey, latencyMs }

GET /ai/imagegen/files/{objectKey}
  └─ 校验 objectKey 第一段 == 当前 X-User-Name（防越权读别人的图）
  └─ ImageObjectStore.open(objectKey) → 流式写回 + Content-Type + Cache-Control
```

存储键格式：`{清洗后用户名}[/-摘要]/{yyyyMMdd}/{uuid}.{ext}`。用户名里非 `[A-Za-z0-9_-]` 的字符一律换成 `_`；只要清洗结果和原名不一致（中文名、带斜杠的脏名），就补一段原名 SHA-256 的前 8 位十六进制，否则「张」和「李」会撞成同一个前缀，两个用户就能互读对方的图。越权判断用同一个函数比较。

---

## 4. 任务清单

### Task 1：数据库迁移

**Files:** Create `db/migration/2026-11-06-ai-provider-capability.sql`

```sql
-- 能力扩展：供应商声明它能做什么，模型声明它是什么。
-- 老数据一律落到 CHAT，行为与升级前完全一致。
ALTER TABLE ai_user_model_provider
    ADD COLUMN service_types VARCHAR(64) NOT NULL DEFAULT 'CHAT'
        COMMENT '服务能力，CSV：CHAT,IMAGE,RAG' AFTER base_url;

ALTER TABLE ai_user_model
    ADD COLUMN model_type VARCHAR(32) NOT NULL DEFAULT 'CHAT'
        COMMENT '模型类型：CHAT/IMAGE/EMBEDDING/RERANK' AFTER display_name;
```

- [ ] **Step 1:** 建文件（MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`，这个脚本只跑一次）
- [ ] **Step 2:** 在 ezpracticeexercises 库执行
- [ ] **Step 3:** 验证：`SELECT id, service_types FROM ai_user_model_provider LIMIT 5;`

### Task 2：枚举 `ServiceType` / `ModelType`

**Files:** Create `AIModule/src/main/java/org/leon/aimodule/chat/provider/ServiceType.java`、`ModelType.java`

- `ModelType.of(String)`：null/空白/未知 → `CHAT`（老数据兜底，绝不抛错把设置页点崩）；`ModelType.parseStrict(String)` 给请求校验用。
- `ModelType.serviceType()`：`CHAT→CHAT`、`IMAGE→IMAGE`、`EMBEDDING/RERANK→RAG`。
- `ServiceType.parseCsv(String)`：去重、大写归一、未知值丢弃；空集合 → `[CHAT]`。
- `ServiceType.toCsv(Collection<ServiceType>)`：固定按 `CHAT,IMAGE,RAG` 顺序输出，便于比对与索引。
- [ ] 单测 `ServiceTypeTest`：CSV 往返、乱序去重、空值兜底。

### Task 3：实体与请求/视图字段

**Files:** Modify `AiUserModelProvider.java`（加 `private String serviceTypes;`）、`AiUserModel.java`（加 `private String modelType;`）、`ProviderRequests.java`、`ProviderViews.java`

- `CreateProvider` 加 `List<String> serviceTypes`（可选，null → `CHAT`）
- `UpdateProvider` 加 `List<String> serviceTypes`（可选，null → 保持不变，不重置）
- `ModelInput` / `UpdateModel` 加 `String modelType`（可选，null → `CHAT`）
- `ProviderViews.Provider` 加 `List<String> serviceTypes`；`ProviderViews.Model` 加 `String modelType`
- [ ] 编译通过后同步修 `ProviderServiceTest` 里 7 处 record 构造（加参数）

### Task 4：`ProviderService` 校验与能力一致性

**Files:** Modify `ProviderService.java`

- `create`：`serviceTypes` 归一化后落 CSV；`validateModels` 逐条校验 `modelType ∈ provider.serviceTypes`
- `update`：`serviceTypes` 非 null 才覆盖，并校验「新能力集合不能小于该供应商已有模型的类型集合」，否则拒绝：`该供应商下还有生图模型，不能取消生图能力`
- `addModel` / `updateModel`：同样做能力覆盖校验
- `view`：`serviceTypes` 从 CSV 转回 `List<String>`；`modelView` 带上 `modelType`
- [ ] 单测：默认 CHAT、未声明 IMAGE 时加 IMAGE 模型被拒、取消能力被已有模型挡住
- [ ] 单测：`testConnection` 按第一个启用模型的类型选探针

### Task 5：探针按能力分流

**Files:** Modify `ProviderProbe.java`

- 保留现有 `probe(baseUrl, apiKey, model)`（聊天，`/chat/completions` + `max_tokens=1`）
- 新增 `probeModels(baseUrl, apiKey)`：`GET {baseUrl}/models`，2xx 即成功。生图/RAG 用这个——不对生图端点发一次真生成（真花钱），也不对 embedding 端点发 `chat/completions`（必然 400）。
- `ProviderService.testConnection` 按启用模型的 `model_type` 选：`CHAT` → `probe`，其余 → `probeModels`

### Task 6：目录分流

**Files:** Modify `UserModelCatalogService.java`

- `choices(userName)`：只收 `modelType=CHAT` 且供应商声明 `CHAT`（不传 `modelType` 的旧数据照旧能进目录）
- 新增 `imageChoices(userName)`：只收 `modelType=IMAGE` 且供应商声明 `IMAGE`，`Choice` 复用
- 新增 `resolveImage(userName, selectionKey)`：`resolve()` 基础上再断言 `modelType=IMAGE` 且供应商声明 `IMAGE`，否则抛 `ChatRejectException("模型不存在")`
- `resolve()`：断言 `modelType=CHAT`，否则同样「模型不存在」
- [ ] 单测：两种目录互不串台、`resolveImage` 拒绝聊天模型

### Task 7：MinIO 依赖与配置

**Files:** Modify `AIModule/pom.xml`、`AIModule/src/main/resources/application.yaml`、`config/local.properties`

```xml
<dependency>
  <groupId>io.minio</groupId>
  <artifactId>minio</artifactId>
  <version>8.5.17</version>
</dependency>
```

```yaml
imagegen:
  minio:
    enabled: ${MINIO_ENABLED:true}
    endpoint: ${MINIO_ENDPOINT:http://127.0.0.1:9000}
    access-key: ${MINIO_ACCESS_KEY:minioadmin}
    secret-key: ${MINIO_SECRET_KEY:minioadmin}
    bucket: ${MINIO_BUCKET:ai-images}
  limits:
    max-prompt-chars: 2000
    max-images-per-request: 4
    max-image-bytes: 20971520      # 20MB
    request-timeout-seconds: 120   # 生图慢，别用探活那 15s
```

`config/local.properties`（已 gitignore）追加：

```properties
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
```

本机起 MinIO（Docker 已装，127.0.0.1:9000 当前不通）：

```powershell
docker run -d --name ez-minio -p 9000:9000 -p 9001:9001 `
  -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin `
  -v F:/minio-data:/data minio/minio server /data --console-address ":9001"
```

- [ ] `& $MVN -pl AIModule -am -DskipTests compile`

### Task 8：MinIO 对象存储适配

**Files:** Create `imagegen/config/ImageGenProperties.java`、`imagegen/minio/ImageObjectKeys.java`、`imagegen/minio/ImageObjectStore.java`、`imagegen/minio/MinioImageObjectStore.java`

- `ImageObjectStore`（接口，便于单测 mock）：`String put(String userName, String contentType, byte[] data)`、`StoredImage open(String objectKey)`、`boolean exists(String objectKey)`
- `MinioImageObjectStore`：`MinioClient` 懒构建（配置缺失/连不上不炸启动，只在真正生图时给中文错误）；首个 `put` 前 `bucketExists` → `makeBucket`
- 上传时带 `Content-Type`；`open` 返回 `InputStream` + 真实 `contentType` + 长度
- 失败一律转 `ImageGenException("图片存储不可用，请检查 MinIO 配置")`，原始异常只进日志
- [ ] 单测 `ImageObjectKeysTest`：清洗非法字符、越权判断、扩展名推导

### Task 9：OpenAI 兼容生图网关

**Files:** Create `imagegen/gateway/ImageGateway.java`、`imagegen/gateway/OpenAiImageGateway.java`、`imagegen/gateway/GeneratedImage.java`

```java
record GeneratedImage(byte[] data, String contentType) {}
record ImageRequest(String baseUrl, String apiKey, String model, String prompt,
                    String size, int n, int timeoutSeconds) {}
record ImageResponse(List<GeneratedImage> images, long latencyMs) {}

interface ImageGateway { ImageResponse generate(ImageRequest req); }
```

- 请求：`POST {baseUrl}/images/generations`，body `{model, prompt, n, size, response_format: "b64_json"}`
- 响应解析：优先 `data[].b64_json`；缺失时回退 `data[].url`，但下载前用 `BaseUrlGuard.isBlockedHost` 拦内网地址（供应商返回的 URL 也不能把服务端当跳板）
- 非 2xx：`ImageGenException("生图上游返回 " + status + ": " + 前 200 字符)`，绝不带 `Authorization`
- 超时取 `imagegen.limits.request-timeout-seconds`（默认 120s）
- 用 JDK `HttpClient`，与 `ProviderProbe` 同风格（不引 WebClient/Spring AI 的新链路）
- [ ] 单测 `OpenAiImageGatewayTest`：b64 解析、url 回退、非 2xx 文案、内网 URL 被拒

### Task 10：生图服务与接口

**Files:** Create `imagegen/service/ImageGenService.java`、`imagegen/web/ImageGenController.java`、`imagegen/web/ImageGenExceptionHandler.java`、`imagegen/exception/ImageGenException.java`、`imagegen/web/dto/ImageGenRequest.java`、`imagegen/web/dto/ImageGenVo.java`、`imagegen/web/dto/ImageModelCatalogVo.java`

端点（都在网关 `/gw` 之后，全部要求 `X-User-Name`）：

| 方法 | 路径 | 请求 | 响应 `data` |
|---|---|---|---|
| GET | `/ai/imagegen/models` | — | `{ models: [{key, name, tag}] }`，`key` 形如 `u:1:2` |
| POST | `/ai/imagegen/generations` | `{modelKey, prompt, size?, n?}` | `{modelKey, latencyMs, images:[{objectKey, url, bytes, contentType}]}` |
| GET | `/ai/imagegen/files/{*objectKey}` | — | 图片二进制流 |

- `ImageGenService.generate`：校验 prompt 非空且 ≤ 2000、`n ∈ [1,4]`、`size` 允许 `256x256/512x512/1024x1024/1024x1792/1792x1024`（不传就不发，交给上游默认）
- 每张图 `put` 进 MinIO，`url = "/ai/imagegen/files/" + objectKey`（相对路径，前端自行拼网关前缀）
- `files` 端点：`@GetMapping("/files/{*objectKey}")`，`objectKey` 第一段必须等于清洗后的 `X-User-Name`，否则 404（不 403，别告诉越权者「这个 key 存在」）
- `ImageGenExceptionHandler` 只覆盖 `org.leon.aimodule.imagegen`（不扩宽 chat 那个 advice），映射到 `Result(code=0)`；响应体手写，理由同 `ChatExceptionHandler`
- [ ] 单测 `ImageGenServiceTest`：mock `ImageGateway` + `ImageObjectStore`，验证 objectKey 落库路径、n 超限拒绝、越权读被拒

### Task 11：回归与验收

- [ ] `& $MVN -pl AIModule -am test -Dtest="ProviderServiceTest,UserModelCatalogServiceTest,ImageGenServiceTest,OpenAiImageGatewayTest,ImageObjectKeysTest,ServiceTypeTest,ModelSelectionTest,ModelResolverTest"`
- [ ] `& $MVN -pl AIModule -am -DskipTests compile` 全绿
- [ ] 改动清单复核：未触碰 `ChatStreamService` / SSE 链路 / `EmbeddingGateway`

---

## 5. 前端联动（本次不做，先记契约）

1. 设置页「自定义 API 服务商」新增「服务能力」多选（聊天 / 生图 / RAG），列表卡片按能力分组展示模型，模型行加「模型类型」下拉。
2. 生图页：`GET /ai/imagegen/models` 拉模型 → `POST /ai/imagegen/generations` → 拿 `url` 后**用 `request.ts` 带 `Authorization` 去 fetch，再 `URL.createObjectURL(blob)` 渲染**。`<img src="/gw/ai/imagegen/files/...">` 会 401，因为网关只认 `Authorization` 头。
3. 老前端不传 `serviceTypes` 仍能工作（默认 CHAT），但模型行会缺 `modelType`，展示成「聊天」即可。

---

## 6. 风险与已知坑

1. **网关路由**：`/ai/imagegen/**` 是否已有 `/ai/**` 路由在 Nacos 的 `gateway.yaml` 里，需要在启动后实测一次；没有就补一条 route（本次不改 Nacos）。
2. **MinIO 未启动**：功能不可用但服务能起；错误文案走「图片存储不可用，请检查 MinIO 配置」，不是 500。
3. **生图耗时**：上游常要 10–60s，`request-timeout-seconds=120`；前端届时别用 15s 默认超时。
4. **`UpdateProvider` 不传 `serviceTypes` 不能重置为 CHAT**，否则改了名字就把生图能力抹掉。
5. **软删行仍会出现在 `list()` 里**（既有问题，本次不动）；新字段的过滤逻辑只受 `status` 控制。
6. **能力集合缩小要挡住**：`update` 里先查该供应商仍启用的模型类型，再决定能否摘掉某项能力。

---

## 7. 验收清单

- [ ] 两张表加列后，老数据全部按 CHAT 正常工作，聊天目录、历史会话不受影响
- [ ] 供应商可声明 `CHAT`、`CHAT,IMAGE`、`CHAT,RAG` 等组合；模型类型不在声明内直接拒绝
- [ ] `GET /ai/imagegen/models` 只出 IMAGE 模型，`GET /ai/chat/models` 只出 CHAT 模型
- [ ] 生图成功：图片落 MinIO `{user}/{date}/{uuid}.png`，响应含 `objectKey` 与 `url`
- [ ] `GET /ai/imagegen/files/{objectKey}` 能取回图片；换别人的 `objectKey` 返回「图片不存在」（HTTP 200 + Result，与全仓约定一致）
- [ ] MinIO 挂掉时返回中文拒绝文案，服务不崩、不 500
- [ ] `model_type=EMBEDDING/RERANK` 能登记、能列出，运行时不参与任何链路
- [ ] AIModule 全量单测通过（`-DskipTests` 只用于快速编译门）

---

## 8. 实施结果（2026-11-06 当次完成）

### 8.1 改了什么

**数据层**

- `db/migration/2026-11-06-ai-provider-capability.sql`（新增，待执行）：两张表各加一列，老数据默认 `CHAT`。

**服务商能力（`org.leon.aimodule.chat.provider`）**

- 新增 `ServiceType`（CHAT/IMAGE/RAG，CSV 集合，带宽松/严格两种解析）、`ModelType`（CHAT/IMAGE/EMBEDDING/RERANK，带 `serviceType()` 映射）
- `AiUserModelProvider.serviceTypes`、`AiUserModel.modelType` 两个字段
- `ProviderRequests`：`CreateProvider`/`UpdateProvider` 加 `serviceTypes`，`ModelInput`/`UpdateModel` 加 `modelType`
- `ProviderViews`：`Provider.serviceTypes`（`List<String>`）、`Model.modelType`
- `ProviderService`：能力覆盖校验、取消能力前查孤儿模型、`serviceTypes` 不传不清空、探活按模型类型分流
- `ProviderProbe`：新增 `probeModels`（`GET /models`），生图/RAG 用它探活，避免对生图端点发真生成
- `UserModelCatalogService`：`choices()` 只出 CHAT，新增 `imageChoices()`、`resolveImage()`；`resolve()` 断言 CHAT
- `BaseUrlGuard.isBlockedHost` 由包内可见改为 `public`（生图网关回落 URL 时复用同一套内网拦截）

**生图（`org.leon.aimodule.imagegen`）**

```
config/ImageGenProperties       imagegen.* 配置（含 minio 与 limits）
ImageContentTypes               魔数嗅探 Content-Type / 扩展名
exception/ImageGenException     可展示错误
gateway/ImageGateway            接口 + Command/Response/GeneratedImage
gateway/OpenAiImageGateway      POST {baseUrl}/images/generations，b64 优先、url 回路拦内网
minio/ImageObjectKeys           对象键生成 + 归属判定 + 用户名清洗
minio/ImageObjectStore          存储接口（便于单测）
minio/MinioImageObjectStore     MinIO 适配，客户端与桶懒初始化
service/ImageGenService         校验 → 解析路由 → 生图 → 落 MinIO
web/ImageGenController          /ai/imagegen/{models,generations,files/**}
web/ImageGenExceptionHandler    只覆盖 imagegen 包
web/dto/*                       ImageGenRequest / ImageGenVo / ImageModelCatalogVo
```

**配置**

- `AIModule/pom.xml`：`io.minio:minio:8.5.17`
- `application.yaml`：新增 `imagegen:` 段
- `config/local.properties`：`MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`（已 gitignore）

### 8.2 验证结果

| 门 | 结果 |
|---|---|
| `mvn -pl AIModule -am -DskipTests compile` | BUILD SUCCESS |
| 定向单测（ProviderService/UserModelCatalog/ServiceType/ModelType/ImageObjectKeys/OpenAiImageGateway/ImageGenService/ImageGenController） | 41 passed |
| AIModule 全量单测 | 284 run，仅 `ChunkPlannerTest` 6 条失败 |
| `ChunkPlannerTest` 失败归因 | **既有问题**，与本次改动无关：`org.leon.aimodule.rag.chunk` 下的文件本次一行未动（`git status` 可证），是 token 预算断言的历史失败 |

本机构建口径（踩过的坑）：

```powershell
$env:JAVA_HOME = "C:\Users\Administrator\.jdks\ms-21.0.11"   # 默认 java 是 17，编译会报"不支持发行版本 21"
$MVN = "C:\Users\Administrator\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd"
# repo.maven.apache.org 在本机 443 超时，拉新依赖（minio）要么在网络好的时候跑，要么临时挂阿里云镜像：
& $MVN -s C:\Users\Administrator\AppData\Local\Temp\ez-mvn-settings.xml -pl AIModule -am -DskipTests compile
```

### 8.3 还需人工做的三件事

1. 执行 `db/migration/2026-11-06-ai-provider-capability.sql`（本机没有 mysql 命令行，未代跑）。
2. 起 MinIO（命令见 Task 7），否则生图接口会返回「图片存储不可用，请检查 MinIO 配置」。
3. 端到端手工验证一次：设置页给某供应商加 `IMAGE` 能力 → 加一个 `model_type=IMAGE` 的模型 → `GET /ai/imagegen/models` 能看到 → `POST /ai/imagegen/generations` 拿到 `objectKey` → 带 `Authorization` fetch `url` 能出图。
