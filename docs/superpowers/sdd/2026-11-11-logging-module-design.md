# 日志审计与 AI 使用记录模块设计文档（LoggingModule）

> 目标读者：后端开发、架构评审、QA。
> 目标模块：`LoggingModule`（当前为空壳，应用名 `AuthorizationModuleServer`，端口 `8104`）。
> 设计范围：记录**所有调用者对所有接口的使用记录**，以及**AI 使用/调取记录**。
> 交付形态：仅设计文档，**不包含接口定义与实现代码**。

---

## 一、背景与目标

### 1.1 背景

项目已拆分为 `UserModule`、`PracticeModule`、`AIModule`、`AuthModule`、`GatewayModule` 等微服务，由 `GatewayModule`（Spring Cloud Gateway）统一入口，网关在 `JwtAuthGlobalFilter` 中解析 JWT 并向下游注入 `X-User-Name`、`X-User-Id`、`X-Internal-Token`。各模块独立部署、共享单一 MySQL 库 `ezpracticeexercises`，数据订正通过 `db/migration/*.sql` 人工执行（无 Flyway/Liquibase）。

现状痛点：

1. **没有统一的调用者使用记录**：无法回答“哪个用户、何时、调用了哪个接口、耗时多久、返回什么状态”。
2. **AI 用量无法归因**：`AIModule` 的 `ai_message` 表已记录 `prompt_tokens`/`completion_tokens`，但只记到对话消息层，没有“调用者 → AI 接口 → 模型/服务商 → token 用量”的三方关联，无法做配额与计费分析。
3. **AOP 分散且未启用**：`UserModule` 已有 `LogOperation` 注解与 `AspectTest1`，但 `@Aspect` 被注释掉，且切点只锁死在 `org.leon.usermodule.service.impl.*`，不跨模块，无法复用。

### 1.2 设计目标

| 目标 | 说明 |
|---|---|
| 全量接口使用记录 | 记录每次跨网关进入微服务的 API 调用：谁、何时、调什么、耗时、结果、来源 IP。 |
| AI 使用记录 | 记录每次 AI 接口调用（对话流式/非流式、生图、RAG 检索等）：调用者、模型、服务商、输入/输出/推理 token、耗时。 |
| 低侵入 | 不修改现有 Controller/Service 接口签名；对业务模块零改动或仅加注解。 |
| 不影响主链路 | 日志写入必须异步、独立于业务事务；日志失败绝不导致业务请求失败。 |
| 安全合规 | 不记录密码、Token、授权码等敏感字段；请求/响应参数做脱敏。 |

### 1.3 非目标（本版不做）

- 不做日志查询/展示前端 UI（先落库，查询靠 SQL 或后续单独做审计后台）。
- 不做分布式链路追踪（SkyWalking/Zipkin），只保留 `traceId` 字段以便与外部追踪系统对齐。
- 不做日志实时告警（先落库 + 计数器，告警后续接入 Sentinel/Nacos 监控）。

---

## 二、现状调研

### 2.1 整体架构摘录

- **GatewayModule**：统一入口，`JwtAuthGlobalFilter` 校验 JWT 后移除原始 `Authorization`，注入 `X-User-Name`、`X-User-Id`、`X-Internal-Token`；白名单路径（如 `/ai/v1/chat/completions`、`/actuator`）不注入身份。
- **下游模块**：`PracticeModule` 用 `InternalTokenFilter` 校验 `X-Internal-Token`，防止绕过网关直连。
- **配置中心**：Nacos（服务发现 + 配置），`common-jwt.yaml` 共享 JWT 公共配置；各模块 `application.yaml` 以 `classpath:config/*.yaml` + `optional:nacos:*.yaml` 分层导入。
- **数据库**：单 MySQL 库 `ezpracticeexercises`，字符集 `utf8mb4` / `utf8mb4_0900_ai_ci`，`DATETIME(3)` 精度，无物理外键，一致性在应用层保证。

### 2.2 现有 AOP 实践（UserModule）

- `LogOperation`：方法级注解，`@Target(ElementType.METHOD)`，`@Retention(RetentionPolicy.RUNTIME)`。
- `AspectTest1`：`@Aspect` + `@Component`，`@Before("@annotation(LogOperation)")`，**`@Aspect` 被注释**，未生效。
- `RecordTimeAspect`：`@Around("execution(* org.leon.usermodule.service.impl.*.*(..))")`，**`@Aspect` 被注释**，且切点硬编码到 `UserModule` 的 service impl 包。

结论：现有 AOP 代码不能直接复用，`LoggingModule` 需自建切面，切点放在 Controller 层（边界），而非 Service 层。

### 2.3 AI 用量现状（AIModule）

- `ai_message` 表已有 `prompt_tokens`、`completion_tokens`、`model_key`、`effort`、`status` 等字段。
- 用量来自上流式 OpenAI 兼容端点返回的 `stream_options.include_usage` 帧（`OpenAiStreamChatModelGateway.toChunk` 解析 `usage`）。
- 缺失：没有“调用者 → 接口 → 模型/服务商”的统一汇总表，无法按天/按用户/按服务商统计 token 消耗与调用次数。

### 2.4 LoggingModule 现状

- 仅 `AuthorizationModuleApplication.java` + `application.yaml`（端口 `8104`，注册 Nacos），无任何业务代码、无依赖除 `spring-boot-starter-web` 外。
- 可作为绿地模块重建，独立部署，提供审计日志落库能力。

---

## 三、核心决策：是否使用 AOP？

### 3.1 结论

**使用 AOP，但采用“双轨制”：**

| 轨道 | 手段 | 记录对象 | 触发时机 | 适用场景 |
|---|---|---|---|---|
| 轨道 A：边界自动记录 | **Controller 层 AOP（`@Around` + 注解/切点表达式）** | 调用者 → 接口的使用记录 | 请求进入 Controller 方法前后 | 所有对外 API（REST/Feign 调用边界） |
| 轨道 B：AI 用量显式埋点 | **Service 层显式调用记录器（非 AOP，或辅助注解）** | AI 接口调用 + token 用量 | AI 流式结束帧 / 非流式返回后 / 错误结束 | `AIModule` 的对话/生图/RAG 接口 |
| 轨道 C：业务敏感操作 | **方法注解 `@LogBiz`（可选，Service 层）** | 业务关键操作（如注册、提交判题、修改密码） | 标注方法执行前后 | 需要审计的业务操作，非全量接口 |

**为什么不用单一 AOP 覆盖全部场景？**

- API 使用记录的粒度在“请求进入 Controller 方法”，AOP 天然适合（跨方法、跨类、无侵入）。
- AI 用量的 token 数（prompt/completion/reasoning）只在**上游响应流结束后**才可知，且需结合 `conversation_id`、`message_id` 等业务上下文，纯 AOP 切 Controller 方法无法获取；应在 `AIModule` 流式处理完成点显式写入。
- 敏感业务操作（如“提交判题”）不应依赖“调用了哪个 Controller 方法”来推断，应由业务方显式标注。

### 3.2 替代方案对比

| 方案 | 优点 | 缺点 | 本方案采纳 |
|---|---|---|---|
| Servlet Filter / Gateway 过滤器 | 无需改业务代码，拦所有请求 | 只能看到 HTTP 层信息（路径、方法、状态、耗时），看不到 Java 方法签名、异常堆栈、业务上下文；无法记录 AI token | 作为 AOP 的补充，仅做兜底（记录丢失的请求） |
| 全量手工埋点 | 控制最精细 | 每个接口都要写重复代码，侵入大，易遗漏 | 不采纳为主，仅用于轨道 B |
| AOP（Controller 层） + 显式埋点（AI 层） | 边界自动、AI 精确、业务模块零侵入 | 需新建模块与切面，有一定维护成本 | **采纳** |

---

## 四、整体架构设计

### 4.1 模块定位

`LoggingModule` 是基础设施模块：

- **不对外暴露业务接口**：不作为 Feign 被调用方提供业务 API；只对内部写入日志。
- **以写为主，读为辅**：核心能力是“接收审计事件 → 异步批量落库”；查询先靠数据库 SQL，后续如需可单独建审计查询服务。
- **独立部署**：注册 Nacos 服务名 `logging-server`，端口 `8104`（沿用），自身无 JWT 鉴权需求（内部模块直写，通过 `X-Internal-Token` 保护，参考 `PracticeModule.InternalTokenFilter`）。

### 4.2 内部分层（设计层次，不写代码）

```
LoggingModule
├── config          # Nacos/bootstrap 配置绑定（开关、采样率、脱敏字段、批量参数）
├── aspect          # 轨道 A：Controller 层切面（@Around，拦所有模块 Controller 入口）
├── collector       # 审计事件聚集器：从请求上下文提取 traceId/user/ip 等，构造审计事件
├── recorder        # 轨道 B/C 对外暴露的记录入口（AI 用量记录器、业务审计记录器）
├── storage         # 异步写入层：内存队列 + 批量插入 + 故障降级
└── exception       # 日志模块自身异常（不向上抛出影响主链路）
```

### 4.3 调用链路（文字图）

```
客户端 HTTPS 请求
      │
      ▼
GatewayModule（JwtAuthGlobalFilter）
  ├─ 解析 JWT → 注入 X-User-Name / X-User-Id / X-Internal-Token
  └─ 转发至目标微服务（UserModule / PracticeModule / AIModule …）
            │
            ▼
    目标微服务 Controller（被 LoggingModule 切面拦截）
            │
            ├─▶ 轨道 A：构造 ApiCallEvent → 写入 LoggingModule 异步队列 → 批量落库 sys_api_call_log
            │
            └─▶ 业务执行
                  │
                  ▼ (若是 AIModule 对话/生图/RAG)
            AIModule 业务处理完成
                  │
                  └─▶ 轨道 B：构造 AiCallEvent → 写入 LoggingModule 异步队列 → 批量落库 sys_ai_call_log / sys_ai_usage_log
```

### 4.4 与其他模块关系

| 模块 | 关系 | 改动 |
|---|---|---|
| GatewayModule | 身份信息的唯一可信来源（`X-User-Name`/`X-User-Id`） | 无需改动；LoggingModule 直接读取下游请求头 |
| AIModule | AI 使用记录的数据产生方 | 在流式结束/非流式返回点调用记录器（不改接口签名） |
| UserModule / PracticeModule / AuthModule | API 使用记录的数据产生方 | Controller 层被统一切面拦截；可选加 `@LogBiz` 标注敏感业务 |
| CommonJwt | `Result` 统一返回体、`JwtUtil` 工具可复用 | LoggingModule 内部可引用，用于解析内部头信息（若需要） |

---

## 五、数据模型设计

遵循仓库现有迁移风格：无 `IF NOT EXISTS`（重跑必报，避免漂移）、无物理外键、字段带注释、`utf8mb4` / `utf8mb4_0900_ai_ci`、`DATETIME(3)`。新增迁移文件按 `db/migration/2026-11-11-xxx.sql` 命名。

### 5.1 sys_api_call_log —— 接口使用记录（调用者 → 接口）

一行记一次 API 调用。

```sql
-- 2026-11-11 日志审计：接口使用记录
-- 目标库：本地 MySQL 的 ezpracticeexercises。
-- 重复执行会因表已存在报 MySQL 1050，属于刻意行为。

CREATE TABLE sys_api_call_log (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  trace_id         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '链路追踪 ID，与网关/前端 traceId 对齐',
  span_id          VARCHAR(64)  NULL     COMMENT '本次调用的跨度 ID',
  user_id          BIGINT UNSIGNED NULL     COMMENT 'X-User-Id，网关注入；未登录请求为 NULL',
  user_name        VARCHAR(64)  NULL     COMMENT 'X-User-Name，网关注入；未登录请求为 NULL',
  source_service   VARCHAR(64)  NOT NULL COMMENT '请求来源微服务：gateway/user/practice/ai/…',
  target_service   VARCHAR(64)  NOT NULL COMMENT '被调用微服务',
  http_method      VARCHAR(10)  NOT NULL COMMENT 'GET/POST/PUT/DELETE/…',
  request_uri      VARCHAR(512) NOT NULL COMMENT '请求完整路径（已脱敏）',
  handler_method   VARCHAR(256) NULL     COMMENT '执行的 Controller 方法全限定名',
  remote_addr      VARCHAR(64)  NULL     COMMENT '客户端 IP（经 X-Forwarded-For 回溯）',
  user_agent       VARCHAR(256) NULL     COMMENT '客户端 UA',
  request_size     INT UNSIGNED NULL     COMMENT '请求体字节数',
  response_size    INT UNSIGNED NULL     COMMENT '响应体字节数',
  status_code      SMALLINT UNSIGNED NOT NULL COMMENT 'HTTP 状态码',
  error_code       VARCHAR(32)  NULL     COMMENT '业务错误码（如有）',
  error_msg        VARCHAR(512) NULL     COMMENT '异常简要信息（已脱敏）',
  cost_ms          INT UNSIGNED NOT NULL COMMENT '接口耗时(毫秒)',
  create_time      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, create_time),
  KEY idx_service_time (target_service, create_time),
  KEY idx_status_time (status_code, create_time),
  KEY idx_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='API 接口使用记录';
```

### 5.2 sys_ai_call_log —— AI 接口调用记录（调用维度）

一行记一次 AI 接口调用（对话一轮、生图一次、RAG 检索一次）。

```sql
-- 2026-11-11 日志审计：AI 接口调用记录

CREATE TABLE sys_ai_call_log (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  trace_id        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '链路追踪 ID',
  user_id         BIGINT UNSIGNED NULL     COMMENT '调用者 ID',
  user_name       VARCHAR(64)  NULL     COMMENT '调用者姓名/账号',
  conversation_id BIGINT UNSIGNED NULL     COMMENT '所属会话 ID（对话场景）',
  message_id      BIGINT UNSIGNED NULL     COMMENT '所属消息 ID（对话场景）',
  service_type    VARCHAR(16)  NOT NULL COMMENT 'CHAT/IMAGE/RAG/…，与 ai_user_model_provider.service_types 对齐',
  provider        VARCHAR(64)  NULL     COMMENT '服务商名称（如 deepseek/dashscope/自建）',
  model_key       VARCHAR(64)  NULL     COMMENT '使用的模型 key',
  upstream_model  VARCHAR(128) NULL     COMMENT '上游实际模型名',
  prompt_tokens   INT UNSIGNED NULL     COMMENT '输入 token 数',
  completion_tokens INT UNSIGNED NULL COMMENT '输出 token 数',
  reasoning_tokens INT UNSIGNED NULL    COMMENT '推理 token 数（completion 的拆分，不累加）',
  total_tokens    INT UNSIGNED NULL     COMMENT '总 token 数（prompt + completion）',
  cost_ms         INT UNSIGNED NOT NULL COMMENT '调用耗时(毫秒)',
  status          VARCHAR(16)  NOT NULL COMMENT 'done/failed/truncated',
  error_msg       VARCHAR(255) NULL     COMMENT '失败原因（已脱敏）',
  create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_service (user_id, service_type, create_time),
  KEY idx_model_time (model_key, create_time),
  KEY idx_conversation (conversation_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 接口调用记录';
```

### 5.3 sys_ai_usage_log —— AI Token 用量明细（计费/配额维度，可选）

若需要按天/按用户/按模型汇总计费，建议在此基础上再建用量明细表（或直接从 `sys_ai_call_log` 聚合，数据量小时可不单独建表）。设计中保留该表以备扩展：

```sql
-- 2026-11-11 日志审计：AI Token 用量明细（扩展表）

CREATE TABLE sys_ai_usage_log (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id      BIGINT UNSIGNED NOT NULL COMMENT '调用者 ID',
  user_name    VARCHAR(64)  NOT NULL,
  service_type VARCHAR(16)  NOT NULL COMMENT 'CHAT/IMAGE/RAG',
  model_key    VARCHAR(64)  NOT NULL,
  prompt_tokens   INT UNSIGNED NOT NULL DEFAULT 0,
  completion_tokens INT UNSIGNED NOT NULL DEFAULT 0,
  total_tokens    INT UNSIGNED NOT NULL DEFAULT 0,
  stat_date     DATE         NOT NULL COMMENT '统计日期（按天分区键）',
  create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_model_date (user_id, model_key, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI Token 用量明细';
```

### 5.4 索引与归档策略

- 日志表为写多读少，索引仅保留最常用查询维度（用户、服务、时间、状态），避免过多索引拖慢批量插入。
- 保留期：`sys_api_call_log` 90 天、`sys_ai_call_log` 180 天（或按业务配置），超期数据通过定时任务归档到历史表或物理删除；不在本版实现归档任务，仅在设计中预留 `stat_date` / `create_time` 作为分区/删除依据。
- 不建立跨表物理外键，保持与现有模块一致（`ai_message.conversation_id` 等也是无物理外键）。

---

## 六、切点与埋点设计

### 6.1 轨道 A：API 使用记录切点（AOP）

- **切点位置**：各微服务的 Controller 层（`…web…` / `…controller…` 包下的 `@RestController` 方法）。
- **切点表达式**：`execution(* org.leon.*.web.*.*(..))` 或按模块包名展开（`org.leon.usermodule.web.*`、`org.leon.practicemodule.controller.*` 等），具体表达式由 Nacos 配置化，避免硬编码导致重新编译。
- **拦截时机**：`@Around`，在 `proceed()` 前后分别捕获入参（元数据，不含敏感字段）与返回结果/异常，计算 `cost_ms`。
- **排除路径**：`/actuator/**`、`/error`、网关健康检查、Swagger/OpenAPI 文档路径、静态资源；白名单在 Nacos 中配置，支持前缀匹配。
- **标注逃生舱**：在不想被全量记录的接口上加 `@LogIgnore`（设计中保留，实现时由切面检查），如内部健康探测、高频心跳。

### 6.2 轨道 B：AI 用量记录埋点（非 AOP）

- **埋点位置**：`AIModule` 的对话流式处理完成点（`ChatStreamService` 写入 `ai_message` 完成后）、生图服务返回后、RAG 检索返回后。
- **触发条件**：只要调用了上游 AI 接口，无论成功/失败/截断，都写入一条 `sys_ai_call_log`；失败时 `error_msg` 记录脱敏后的简要原因。
- **不使用纯 AOP 的原因**：token 用量在响应流结束帧才可知，且需关联 `conversation_id`/`message_id`，Controller 层切面无法获取。

### 6.3 轨道 C：业务敏感操作注解（可选）

- 定义 `@LogBiz(value = "提交判题", type = "OJ_SUBMIT")` 等注解，打在 Service 方法上；切面记录操作类型、操作人、前后快照（仅关键字段，已脱敏）。
- 本版作为扩展点保留，不强制各模块接入。

### 6.4 采集字段（与数据库字段对应）

| 字段 | 来源 | 备注 |
|---|---|---|
| traceId / spanId | 请求头 `X-Trace-Id`（网关或前端透传），缺失则生成 | 用于跨模块串联 |
| userId / userName | 请求头 `X-User-Id` / `X-User-Name`（网关注入，可信） | 未登录/白名单路径为 NULL |
| source_service | 请求头 `X-Source-Service` 或 Feign 客户端标识 | 调用方服务名 |
| target_service | 当前应用 `spring.application.name` | 被调方服务名 |
| httpMethod / requestUri / handlerMethod | `HttpServletRequest` + `JoinPoint` | URI 已脱敏（见下） |
| remoteAddr / userAgent | `HttpServletRequest` | 经 `X-Forwarded-For` 回溯真实 IP |
| requestSize / responseSize | Servlet 请求/响应包装器 | 字节数 |
| statusCode / errorCode / errorMsg | 响应状态 + 捕获异常 | 错误信息已脱敏 |
| costMs | `@Around` 前后时间戳差 | 毫秒 |

### 6.5 敏感参数脱敏（强制）

日志表**不记录请求体/响应体原文**，只记录元数据。若业务方确需记录参数，必须经过脱敏：

- 脱敏字段：`password`、`token`、`authorization`、`smsCode`、`idCard`、`phone`、银行卡号、手机号。
- 脱敏策略：中文姓名保留姓（如 `Leon*`）、手机号前三后四（如 `138****1234`）、邮箱保留前缀第一个字符（如 `l***@example.com`）、ID 号保留前四后四。
- 脱敏规则由 Nacos 配置 `logging.audit.sensitive-fields`（字段名列表）与正则集中管理，切面统一应用。

---

## 七、AI 使用记录设计详述

### 7.1 记录粒度

1. **会话级**：对应 `ai_conversation` 一行，记录会话总 token、总调用次数（由 `sys_ai_call_log` 聚合）。
2. **消息级**：对应 `ai_message` 一行，`prompt_tokens`/`completion_tokens` 已在现有表记录。
3. **调用级（新增）**：`sys_ai_call_log` 一行记一次 AI 接口调用，关联 `user_id` + `conversation_id` + `model_key` + `service_type`，用于“谁用了哪个模型多少次”的统计。

### 7.2 写入时机

| 场景 | 时机 | 状态 |
|---|---|---|
| 流式对话 | 上游返回带 `usage` 的最后一帧（`[DONE]` 之前） | `done` |
| 流式对话异常/中断 | 捕获 `UpstreamStatusException` 或流超时时 | `failed` / `truncated` |
| 非流式对话 | 上游 HTTP 响应读完、解析出 `usage` 后 | `done` |
| 生图 | 图片上传 MinIO 完成并返回 objectKey 后 | `done` |
| RAG 检索 | 检索服务返回结果后 | `done` |

### 7.3 与 AIModule 的耦合点

- 在 `AIModule` 流式处理完成后（`ChatStreamService` 结束时）调用 `LoggingModule.recorder.recordAiCall(AiCallEvent)`。
- 该调用为**异步、独立于业务事务**：`AIModule` 的 `ai_message` 写入事务回滚时，AI 使用记录**不回滚**（保留审计痕迹），反之亦然。

---

## 八、写入链路与可用性

### 8.1 异步批量写入（核心）

1. 切面/埋点将审计事件放入**内存有界队列**（`ArrayBlockingQueue` 或 `LinkedBlockingQueue`，容量可配，默认 10000）。
2. 专写入线程（或 `ScheduledExecutorService`）每批读取（默认 100 条或每 5 秒 flush），通过 `JdbcTemplate`/`MyBatis` 批量插入对应日志表。
3. 入队操作必须在 **< 1 ms** 完成；队列满时按配置策略处理（默认丢弃新事件并计数，可选阻塞或回写主线程—不推荐）。

### 8.2 失败降级

- 数据库不可用：写入失败计数 + 打日志（`LoggingModule` 自身日志，不写入审计表避免循环），事件丢失；业务请求完全不受影响。
- 可选本地文件兜底：若配置开启，失败事件落本地 `logs/audit-fallback/` 的按天滚动文件，供人工补录（本版不实现，仅预留）。

### 8.3 事务边界

- 审计日志写入**不参与业务方法的 `@Transactional`**；使用独立数据源或独立 `DataSourceTransactionManager`，`PROPAGATION_REQUIRES_NEW`。
- 业务回滚不撤销审计记录；审计写入失败不导致业务回滚。

### 8.4 性能预算

| 指标 | 目标 |
|---|---|
| 切面入队开销 | < 1 ms（99%） |
| 批量插入延迟 | 5 秒 / 100 条 内落库 |
| 日志模块自身可用性 | 即使审计库宕机，不影响任何业务模块 |

---

## 九、配置与运维（Nacos）

`LoggingModule` 独立 Nacos Data ID `logging-server.yaml`，关键项：

```yaml
server:
  port: 8104
spring:
  application:
    name: logging-server
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
      config:
        server-addr: 127.0.0.1:8848
logging:
  audit:
    enabled: true                       # 总开关
    exclude-paths: /actuator/**,/error  # 不记录的路径前缀
    sample-rate: 1.0                    # 采样率 0~1，1=全量
    sensitive-fields: password,token,authorization,smsCode,idCard,phone
    queue-capacity: 10000
    batch-size: 100
    flush-interval-seconds: 5
    retention-days: 90                  # API 日志保留期
    ai-retention-days: 180              # AI 日志保留期
```

运维指标（待后续接入监控系统，本版先打印日志）：

- `logging.audit.events.received`：入队事件数
- `logging.audit.events.dropped`：队列满丢弃数
- `logging.audit.events.persisted`：成功落库数
- `logging.audit.events.failed`：写库失败数

---

## 十、对现有模块的改动点（最小侵入）

| 模块 | 改动内容 | 是否必需 |
|---|---|---|
| GatewayModule | 无。`X-User-Name`/`X-User-Id` 已注入，LoggingModule 直接读取 | — |
| AIModule | 在对话流式完成/生图返回/RAG 检索完成处增加 AI 用量记录器调用 | 是（轨道 B） |
| UserModule | 可选：Controller 上加 `@LogBiz` 标注敏感业务；不改已有接口签名 | 否 |
| PracticeModule | 可选：同上 | 否 |
| AuthModule | 可选：同上 | 否 |
| LoggingModule | 从空壳变为可启动的基础设施服务：切面、收集器、写入器、配置、DDL | 是 |

---

## 十一、安全与合规

1. **不记录敏感凭据**：`Authorization`、`Cookie`、`Set-Cookie`、`password`、`token` 等请求/响应头不落库；请求体字段按 `sensitive-fields` 脱敏后仅记录元数据。
2. **日志只读**：审计表仅支持插入，不支持更新/删除（超期清理由定时任务执行，需审计人员审批）。
3. **访问控制**：查询审计日志的后续接口需鉴权，且只允许管理员角色。
4. **数据留存**：遵守 `retention-days`；超期数据归档后删除，保留元数据摘要（如按天汇总调用次数）用于统计。

---

## 十二、非功能性需求

| 需求 | 指标 |
|---|---|
| 可用性 | 审计写入失败不影响主业务流程（降级丢弃 + 计数） |
| 一致性 | 最终一致；审计记录与业务数据独立提交 |
| 性能 | 入队 < 1 ms，不增加接口响应时间 |
| 可观测性 | 所有调用携带 `traceId`，方便与外部追踪系统关联 |
| 可扩展性 | 新增服务类型（如 `EVAL` 评测）只需在 `service_type` 枚举中增加，无需改表结构 |

---

## 十三、里程碑与验收（简述）

- M1：建表迁移 SQL + LoggingModule 空壳启动（Nacos 注册、配置加载）。
- M2：Controller 层 AOP 切面落库 `sys_api_call_log`（UserModule 为试点）。
- M3：AIModule 接入 AI 用量记录，落库 `sys_ai_call_log`。
- M4：脱敏规则、采样、排除路径配置化；性能与降级测试。

验收标准：任意用户登录后调用任一非白名单接口，`sys_api_call_log` 产生一条带 `user_id`/`user_name`/`trace_id`/`status_code`/`cost_ms` 的记录；AI 对话完成后，`sys_ai_call_log` 产生一条带 `prompt_tokens`/`completion_tokens`/`model_key` 的记录；审计库宕机时业务接口仍正常返回。

---

> 后续步骤：评审通过后，按上述 M1–M4 逐步实现；实现阶段再补充 Java 接口、切面类、服务类与测试用例。
