# 设置页「自定义 API 服务商」前端实现流程

> 日期：2026-11-06
> 仓库：`EZ_frontend`（前端） / `ezPracticeExercises`（后端 AIModule）
> 后端来源：`AIModule/src/main/java/org/leon/aimodule/chat/provider/`（Controller / Service / Views / Requests / Probe / Guard）
> 前端落点：`src/views/AI/Settings.vue`、`src/api/provider.ts`、`src/composables/useProviders.ts`、`src/components/AI/Provider{Form,Card}.vue`

---

## 0. 目的与范围

让用户在设置页自助接入任意 OpenAI 兼容端点（自己的 baseUrl + key + 模型），接入后这些模型出现在对话页的模型选择器里，调用走用户自己的 key。密钥在服务端 AES-GCM 加密落库，接口只回脱敏值。

**在范围内**：服务商的增删改查、模型的增改删、测试连接、错误与空态呈现、模型目录联动刷新。

**不在范围内**：协议适配（只支持 `OPENAI_COMPAT`，即 `/chat/completions`）、用量/计费统计、供应商排序拖拽、密钥轮换 UI。

---

## 1. 后端契约（写代码前逐条核准，别靠猜）

### 1.1 八个端点

| 方法 | 路径 | 请求体 | 响应 `data` |
|---|---|---|---|
| GET | `/ai/chat/providers` | — | `Provider[]` |
| POST | `/ai/chat/providers` | `CreateProvider` | `Provider` |
| PUT | `/ai/chat/providers/{providerId}` | `UpdateProvider` | `Provider` |
| DELETE | `/ai/chat/providers/{providerId}` | — | `null` |
| POST | `/ai/chat/providers/{providerId}/models` | `ModelInput` | `Model` |
| PUT | `/ai/chat/providers/{providerId}/models/{modelId}` | `UpdateModel` | `Model` |
| DELETE | `/ai/chat/providers/{providerId}/models/{modelId}` | — | `null` |
| POST | `/ai/chat/providers/{providerId}/test` | — | `{ ok, latencyMs, message }` |

全部经网关 `/gw` 前缀（dev 由 vite 代理转发到 `127.0.0.1:8100`），归属由网关注入的 `X-User-Name` 决定，**请求体里没有任何身份字段**。

### 1.2 视图字段

```
Provider { id, providerName, protocol, baseUrl, serviceTypes[], configured, maskedKey, status, models[] }
Model    { id, modelKey, displayName, modelType, supportsThinking, status }
```

- `serviceTypes`：供应商声明的服务能力集合，取值 `CHAT` / `IMAGE` / `RAG`，缺省（老数据、老请求）为 `["CHAT"]`。
- `modelType`：模型类型，取值 `CHAT` / `IMAGE` / `EMBEDDING` / `RERANK`，缺省为 `CHAT`；必须被所属供应商的 `serviceTypes` 覆盖，否则后端拒绝。`EMBEDDING` / `RERANK` 目前只登记、不参与运行链路。
- 2026-11-06 起新增 `AI 生图` 链路（`/ai/imagegen/*`，图片落 MinIO），详见 `docs/superpowers/plans/2026-11-06-ai-imagegen-provider-capability.md`。

- `protocol`：后端常量 `OPENAI_COMPAT`，前端展示成「OpenAI 兼容」。
- `baseUrl`：已归一化（去尾斜杠；路径不以 `/v1` 结尾则补 `/v1`），前端**不要**再拼 `/v1`。
- `configured` / `maskedKey`：明文永不返回；`maskedKey` 形如 `sk-x...abcd`（比 8 位短的统一给 `********`）。
- `status`：1 启用 / 0 已删除。

### 1.3 请求体约束

```
CreateProvider { providerName(≤80), baseUrl(≤500), apiKey(≤4096), models[min 1, max 30] }
UpdateProvider { providerName(≤80), baseUrl(≤500), apiKey(可选), status(可选) }
ModelInput     { modelKey(≤128), displayName(≤120), supportsThinking }
UpdateModel    { modelKey, displayName, supportsThinking, status }
```

**`UpdateProvider` 不收 models** —— 模型只能走 `/models` 子资源单条改。这是表单在编辑态不显示模型编辑区的原因。

### 1.4 错误语义

- 业务拒绝（`ChatRejectException`、参数校验失败）→ **HTTP 200 + `Result(code=0, msg)`**，`msg` 是可直接展示的中文（「供应商数量已达上限」「baseUrl 不允许指向本机或内网地址」…）。
- token 失效 → HTTP 401/403，由 `request.ts` 统一清态跳登录，页面不能再挂一条错误条。
- 前端 `errorText()` 对 `BizError` 原样展示 `msg`，所以后端新增文案不需要改前端。

### 1.5 目录联动

`GET /ai/chat/models` 会把当前用户的模型追加到目录末尾，key 形如 `u:{providerId}:{modelId}`，`tag` 是供应商名。**增删/改名模型后旧目录立刻失效**，这正是要刷新目录的原因。

---

## 2. 文件清单与分层

```
src/api/request.ts                     ← 改：method 联合类型补 'PUT'
src/api/provider.ts                    ← 新增：8 个接口 + 类型
src/composables/useProviders.ts        ← 新增：列表/提交态/探活结果/校验规则（唯一真相源）
src/components/AI/ProviderForm.vue     ← 新增：新增+编辑共用表单
src/components/AI/ProviderCard.vue     ← 新增：卡片 + 模型清单 + 行内增改
src/composables/useChat.ts             ← 改：reloadCatalog + refreshChatCatalog()
src/views/AI/Settings.vue              ← 改：新增「自定义 API 服务商」板块
```

分层口径与仓库既有约定一致：

| 层 | 职责 | 不做的事 |
|---|---|---|
| `api/provider.ts` | 拼路径、声明类型、超时口径 | 不校验、不提示 |
| `useProviders.ts` | 状态、校验、文案、写成功后局部更新列表 | 不碰 DOM |
| 组件 | 渲染与交互 | 不直接调 api，不自己攒列表 |
| `Settings.vue` | 编排：新增表单 / 卡片互斥，回调归并 | 不放字段级逻辑 |

---

## 3. 实现流程（按时序）

### 3.1 进入页面

1. 路由 `/ai/settings` 命中 `AI/Layout.vue` 的子路由（`meta.requiresAuth`，未登录会被守卫送去 `/login`）。
2. `Settings.vue` `onMounted` 调 `store.reload()`。
3. `reload()` → `GET /ai/chat/providers` → **过滤 `status !== 1` 的服务商与其 `status !== 1` 的模型** → 写入 `providers`。
4. 列表为空且不在新增态 → 渲染虚线空态卡片；有数据 → 每个服务商一张 `ProviderCard`。

### 3.2 新建服务商

1. 点「添加服务商」→ `creating = true`、`editingId = null`（两块互斥，避免两张表单同时提交）。
2. `ProviderForm`（`mode=create`）渲染：名称、Base URL、API Key、至少一行模型。
3. 提交 → `emit('submit', draft)` → `Settings.submitCreate` → `store.create(draft)`。
4. `create()` 内：`validateProviderDraft({ apiKeyRequired: true, withModels: true })`
   - 失败 → 写入 `error`，**表单不收起**，用户填的内容还在，原因在顶部提示条。
   - 通过 → `saving = true` → `POST /ai/chat/providers`。
5. 成功 → 用响应里的 `Provider` 前插列表（`visible()` 过一遍过滤）、写 `notice`、`refreshChatCatalog()`、返回 `true` → 表单收起。
6. 失败 → `error = errorText(err)`（后端中文原文），返回 `false`，表单保留。

### 3.3 编辑服务商

1. 卡片「编辑」→ `editingId = provider.id`，该卡片位置换渲染 `ProviderForm`（`mode=edit`，`initial` 带 `providerName/baseUrl/maskedKey`）。
2. 提交 → `store.update(id, draft)`，校验用 `apiKeyRequired: false, withModels: false`。
3. 请求体：`{ providerName, baseUrl }`，**只有 key 非空时才追加 `apiKey`** —— 发空串会被后端当成"要写一个空密钥"。
4. 成功 → 就地替换该服务商、写 `notice`、`refreshChatCatalog()`、`editingId = null`。

### 3.4 模型增 / 改 / 删（卡片内行内编辑）

1. 卡片本地状态 `editing: 'new' | number | null`，同一时刻只允许一行处于编辑态（避免并发写同一供应商）。
2. 点「添加模型」或某行「编辑」→ 填 `modelKey / displayName / 支持思考` → 「添加 / 保存」。
3. 回调 `onSaveModel(modelId | null, draft)` → `Settings.saveModel` → `store.addModel` / `store.editModel`。
4. **返回 `Promise<boolean>`，只有 `true` 才收起行内编辑器**；失败时用户停在自己刚填的那一行，而不是被清空后从顶部提示条里猜改的是哪一行。
5. 删除：`window.confirm` 二次确认（删掉后模型立刻从对话的模型选择器消失）→ `store.removeModel` → 就地剔掉该行。
6. 每次都 `refreshChatCatalog()`。

### 3.5 测试连接

1. 卡片「测试连接」→ `store.test(id)` → `testingId = id`（按钮变「测试中…」并禁用）。
2. `POST /ai/chat/providers/{id}/test`，`timeoutMs: 25000`（后端探活默认 15s，用默认的 15s 会自己先掐断）。
3. 返回 `{ ok, latencyMs, message }` 存进 `probes[id]`，卡片渲染成一条结果条：成功绿底「连接成功 · 123ms」，失败红底并展示 `message`（上游错误体前 200 字符，绝不含我们发出去的 key）。
4. HTTP 层失败（网关没起、超时）走 `catch`：清掉该 id 的探活结果 + 顶部错误条，因为没有耗时数据可展示。

### 3.6 删除服务商

`window.confirm` → `DELETE /ai/chat/providers/{id}` → 从列表与 `probes` 中移除 → `notice` → `refreshChatCatalog()`。

> 后端是软删（`status` 置 0），并且连带把该供应商的模型一起置 0。

---

## 4. 关键决策与踩过的坑

1. **PUT 要先扩传输层**。`request.ts` 的 `method` 原本只有 `'GET' | 'POST' | 'DELETE'`，而 `updateProvider`/`updateModel` 都是 PUT，不补类型就是编译错误。

2. **列表必须前端过滤 `status`**。`ProviderService.list` 不过滤 `status`，`modelsOf` 也不过滤 —— 软删的行照样回来。不过滤的话，删掉的卡片刷新后原地复活。
   → 因此 **UI 不提供「停用」开关**：在列表接口语义改掉之前，停用和删除对用户是不可区分的两件事，多一个按钮只会让人以为删不掉。

3. **`UpdateProvider` 的 `status` 缺省会被重置为 1**（`normalizeStatus(null) → 1`）。因为视图里只有启用中的服务商，编辑不会造成意外启用；但如果将来加了停用能力，这里必须一并改。

4. **API Key 留空 ≠ 发空串**。后端判的是 `req.apiKey() != null && !isBlank()`，发空串虽然也不覆盖，但语义上等于"我要写一个空密钥"，容易在后续改动里变成事故点，所以直接不带该字段。

5. **探活超时必须放宽**。`probeTimeoutSeconds` 默认 15s，前端默认 15s 会和它打平（谁先到谁掐），设成 25s 让后端的那条超时先说话。

6. **目录要刷新，但别为了刷新而创建单例**。`refreshChatCatalog()` 只在 `useChat` 单例已存在时才真的去重拉：设置页不该凭空造出一份 chat 状态（它带着 `onScopeDispose` 与流式定时器）。

7. **表单失败不收起**。收起等于把用户刚填的三四个字段一起丢掉，只剩一条错误条可看。

8. **`ProviderCard` 用函数 props 而不是 emits**。Vue 的 `emits` 拿不到返回值，而"成功才收起编辑器"需要 `Promise<boolean>`，所以 `onSaveModel / onDeleteModel` 走函数 prop。

9. **样式只用 AI 模块的变量**（`--sb-bg / --sb-hover / --sb-active / --main-bg / --text / --text-muted / --border`）。Practice 模块的 `--divider / --text-secondary / --primary-500` 在 `/ai/*` 下不存在，用了是不报错但看不见的 bug。

---

## 5. 校验规则（前端唯一的规则表，在 `useProviders.ts`）

| 字段 | 规则 | 失败文案 |
|---|---|---|
| providerName | 非空、≤80 | 请填写服务商名称 / 服务商名称最多 80 个字符 |
| baseUrl | 非空、≤500、`http(s)://` 开头、`new URL()` 可解析、有 hostname | 请填写 Base URL / Base URL 要以 http:// 或 https:// 开头 / Base URL 格式不合法 |
| apiKey | 新增必填、编辑可空、≤4096 | 请填写 API Key |
| modelKey | 非空、≤128 | 请填写模型标识 |
| displayName | 非空、≤120 | 请填写模型显示名 |
| models | 新增态至少 1 条 | 至少添加一个模型 |

> 私网/回环地址、`http` 是否放行、`/v1` 补齐，全部由后端 `BaseUrlGuard` 裁决（它同时是 SSRF 闸门），前端不重复实现，也不做「看起来合法就放行」的臆测。

---

## 6. 部署前置（第一次跑必踩）

后端 `chat.provider.encryption-key` 取自环境变量占位：

```yaml
# AIModule/src/main/resources/application.yaml
provider:
  allow-http: false
  encryption-key: ${EZ_PROVIDER_ENCRYPTION_KEY:}
```

缺这项时 `ApiKeyCryptoService.requireConfigured()` 抛异常，创建服务商必然失败。该异常已改成 `ChatRejectException`，设置页会直接显示：

> 服务端未配置 API Key 加密主密钥（EZ_PROVIDER_ENCRYPTION_KEY），服务商功能暂不可用

本机取值放在 gitignore 的 `config/local.properties`（与 `EZ_DEEPSEEK_API_KEY` 同处）：

```properties
EZ_PROVIDER_ENCRYPTION_KEY=<32 位以上随机串>
```

改这一行等于废掉所有已存的密文（旧 key 解不出来），用户需重填 API Key。**改完必须重启 AIModule**。

---

## 7. 验证步骤

### 7.1 静态门

```bash
npx vue-tsc --build        # 类型
npx vite build            # 模板编译
npx vitest run             # 153 passed
```

### 7.2 手工链路（登录后在 `/ai/settings`）

1. 空态：无服务商时显示虚线提示卡。
2. 校验：名称留空、Base URL 填 `api.deepseek.com`（缺 scheme）、模型标识留空，各自被拦下且表单不收起。
3. 新建：填 `https://api.deepseek.com` + 真 key + 一个模型 → 卡片出现，Base URL 显示为 `https://api.deepseek.com/v1`，key 显示 `sk-x...abcd`。
4. 测试连接：成功显示「连接成功 · Nms」；把 key 改错再测 → 红底展示上游 401 文案。
5. 模型：加一行、改显示名、删一行，三步都不刷新整页。
6. 目录联动：切到对话页，模型选择器末尾出现「DeepSeek 测试」分组下的新模型（`tag` 为供应商名）。
7. 编辑：改名称保存；API Key 留空再保存一次，进对话验证仍可用（说明密文没被覆盖）。
8. 删除：确认框 → 卡片消失 → 刷新页面不复现（验证前端 `status` 过滤生效）。
9. 错误条：停掉网关后点刷新，应看到「无法连接服务器，请确认网关已启动」。

---

## 8. 验收清单

- [x] 设置页新增板块，与既有「外观 / 会话」视觉一致
- [x] 服务商 CRUD + 模型 CRUD + 测试连接全部打通
- [x] 密钥只显示脱敏值，前端从不持有明文回显
- [x] 软删行不会在刷新后复活
- [x] 模型增删后对话页目录即时同步
- [x] 后端中文拒绝文案能原样展示
- [x] 401 跳登录、网络断开、超时三种故障各有可读提示
- [x] 类型检查 / 构建 / 单测全绿

---

## 9. 遗留与后续

1. **列表接口应按 `status` 过滤**（`ProviderService.list` / `modelsOf`）。前端过滤是兜底，不是正解；改掉之后设置页的 `visible()` 可以退化为纯展示，且「停用」开关才有可能做出来。
2. **`UpdateProvider.status` 缺省重置为 1** 同上，一并修。
3. **启动期 fail-fast**：`EZ_PROVIDER_ENCRYPTION_KEY` 缺失目前是"用的时候才报错"，可以在 `ApiKeyCryptoService` 构造时校验，把配置错误提前到启动阶段。
4. **模型探测**：现在只能手填 modelKey，可加一个「从上游 `/models` 拉取」的辅助按钮（注意并非所有兼容端点都实现该接口，`ProviderProbe` 当初正因如此才改用 `/chat/completions` 探活）。
5. **用量视图**：`ai_message` 已有 usage 两槽，可按 `modelKey` 前缀 `u:` 聚合到供应商维度。
