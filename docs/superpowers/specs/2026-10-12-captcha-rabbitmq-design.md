# 注册/登录验证码 + RabbitMQ 设计

日期：2026-10-12
范围：`AuthModule`（后端主战场）、`EZ_frontend`（前端）、`GatewayModule` 与 Nacos（放行配置）、MySQL（改表）
状态：待用户评审
作者：与 Qoder 协作产出，所有现状结论均带文件行号证据

---

## 0. 阅读前提：三条已核实的事实

设计建立在事实之上，不是猜测。以下三条是我这轮排查得到的，且都会影响方案成立与否：

1. **后端 `@Valid` 当前完全不起作用。** `spring-boot-starter-validation` 在 8 个 `pom.xml` 里一个都没有（grep 零命中），所以没有 `Validator` bean，`RegisterRequest.java:20` 上的 `@NotBlank(message="验证码不能为空")` 是一条装饰。前端设计文档 `docs/superpowers/specs/2026-10-10-login-gateway-jwt-design.md:239` 独立记录了实测："不带 captchaCode 的登录照样 200 出票"。
   → 结论：**不能顺手加 validation starter**，否则注册 tab 立刻 400（它现在压根不发 `captchaCode`）。见决策 D5。

2. **验证码在后端是纯空壳。** `AuthController.java:111` 只有一行被注释掉的 `// if (!captchaService.verify(...))`；`captchaService` 这个 bean 不存在；全仓库（含 git 历史 `git log --all -S captcha`）没有任何生成/存储/校验实现；8 个 pom 里没有 captcha artifact。

3. **前端已有活的验证码 UI，但数据是假的。** `EZ_frontend/src/api/auth.ts:78-84` 的 `fetchCaptcha` 用 `Math.random()` 在浏览器里造 6 位数字，`verifyCaptchaLocal` 在同一标签页自校验；`Login.vue:95-127` 有 6 格 `CodeSlots`、60 秒冷却按钮、`开发占位` 角标。`auth.ts:4-5` 的注释写明了约定："接入时只替换这两个函数的实现，签名与调用点不动"。
   → 结论：前端改动是"接线"，不是"重做"。

另外三条与基础设施相关：

4. 功能归属：`/auth/register`、`/auth/login` 都在 **AuthModule**（端口 8103），且 `AuthModule/pom.xml:52` **已有 Redis**；UserModule 既没有登录注册也没有 Redis。→ 本功能落 AuthModule。
5. RabbitMQ 本机已就绪并已实测跑通（见附录 A）。Redis `127.0.0.1:6379` 在监听。**网关 8100 当前没起**，前端联调前必须先起它。
6. `users_dev` 表**没有 phone 列**，且全仓库零个 `.sql` 文件，表结构无法从代码确认。改表前必须先 `SHOW CREATE TABLE users_dev`。

---

## 1. 目标

注册要过两道门：图形验证码（防机器批量注册）+ 手机验证码（防抵赖/防顶号）。登录过一道门：图形验证码（防撞库）。其中**手机验证码的生成与投递走 RabbitMQ**，注册接口不再被任何外部通道阻塞。

非目标（明确不做，避免范围蔓延）：

- 不接真实短信通道（本机无通道账号，消费者用 `log.info` 模拟发送）
- 不做邮箱验证码
- 不做图片验证码的样式美化
- 不改 UserModule 那个 MQ demo（它是教具，保留）
- 不引入 `spring-boot-starter-validation`
- 不做 IP 维度限流（只做手机号维度，见 §6）

---

## 2. 方案总览

```
① GET /auth/captcha
   AuthModule: hutool 生图 → 答案写 Redis(auth:captcha:{key}, TTL 120s)
   → 返回 {captchaKey, imageBase64}          ← 同步，不过 MQ（图形码生成只需几毫秒）

② POST /auth/sms/send  {phone, captchaKey, captchaCode}
   AuthModule: 校图形码(一次性, 校完即删) → 限频(Redis SETNX, 60s)
   → convertAndSend(auth.sms.exchange, "sms.send", {phone})
   → 立即返回 Result.success                 ← 用户不等任何外部通道
        │
        ▼
   ┌─ auth.sms.send.queue ─────────────────────────┐
   │ 消费者1: 生成6位码 → 写 Redis(auth:sms:code:{phone}, TTL 300s)
   │          → log.info 模拟发短信
   │          → convertAndSend(exchange, "sms.expire", {phone, code})  │
   └───────────────────────────────────────────────┘                  │
                                                                       ▼
   ┌─ auth.sms.expire.delay.queue  (x-message-ttl=300000, 无消费者!) ──┐
   │  消息在这里静静躺 5 分钟，超时后变成死信                            │
   └──────────────────────────────────────────────────────────────────┘
                     │ x-dead-letter-exchange = auth.sms.expire.dlx
                     ▼
   ┌─ auth.sms.expired.queue ──────────────────────┐
   │ 消费者2: 比对 Redis 里的码 == 事件里的码 ? 删 : 什么都不做（幂等）
   │          → 解锁/记录（可选）                    │
   └───────────────────────────────────────────────┘

③ POST /auth/register {username, password, phone, smsCode}
   AuthModule: 校短信码 → BCrypt → INSERT users_dev(含 phone) → 校验通过后删码
④ POST /auth/login    {username, password, captchaKey, captchaCode}
   AuthModule: 校图形码 → 原有逻辑(IP + JWT + Redis 登录态) 一行不改
```

MQ 在这里承担的三件事：**异步**（发送动作与请求解耦）、**削峰**（大量申请时队列排队，消费者按自己节奏处理）、**延迟**（5 分钟过期用 TTL+死信表达，不需要定时器扫表）。

---

## 3. 后端设计

### 3.1 新增/修改文件（AuthModule）

| 文件 | 职责 |
|---|---|
| `AuthModule/pom.xml` | 加 `spring-boot-starter-amqp` + `cn.hutool:hutool-all:5.8.25`（与 UserModule/PracticeModule/AIModule 同版本，已核实它们都用 5.8.25） |
| `application.yaml` | 加 `spring.rabbitmq`（host/port/username/password/virtual-host，与 UserModule demo 同构） |
| `config/CaptchaMqConfig.java` | 声明 **2 交换机 + 3 队列 + 3 绑定**（见 §3.2）+ `Jackson2JsonMessageConverter` |
| `config/AuthCacheKeys.java` | Redis key 前缀常量（现有 key 前缀散落在 `AuthRedisService.java:16,18`，新 key 统一放这里） |
| `pojo/mq/SmsSendRequest.java` | MQ 消息体：`{phone, traceId}` |
| `pojo/mq/SmsExpireEvent.java` | MQ 消息体：`{phone, code, traceId}`。带 `code` 是幂等比对的需要（§3.3）；代价是队列里存了有效验证码明文，见 §7 的安全说明 |
| `service/CaptchaService.java` + `impl` | 图形码生成（hutool `LineCaptcha`）、base64 输出、一次性校验并删除 |
| `service/SmsSender.java` | **接口**，只有一个 `send(String phone, String code)`。本机唯一实现 `MockSmsSender` 打日志。将来接通道是加实现类，不是改消费者 |
| `mq/SmsCodeProducer.java` | `convertAndSend` 封装，两个方法：`sendSms` / `sendExpireDelay` |
| `mq/SmsCodeConsumer.java` | `@RabbitListener` ×2：`auth.sms.send.queue` 与 `auth.sms.expired.queue` |
| `pojo/LoginRequest.java` `RegisterRequest.java` `SendSmsRequest.java` | **拆分**现有那个被三处共用的 `RegisterRequest` |
| `controller/AuthController.java` | 新增 `/auth/captcha`(GET)、`/auth/sms/send`(POST)；`/auth/register`、`/auth/login` 换 DTO 并接校验 |
| `service/impl/AuthServiceImpl.java` | 注册前置校短信码、捕获 `DuplicateKeyException`（现在没有，`AuthServiceImpl.java:44-48`）、泛型从 `ServiceImpl<AuthMapper, RegisterRequest>` 改成 `ServiceImpl<AuthMapper, Users>`（现状是拿请求 DTO 当实体，误用） |
| `mapper/AuthMapper.java` + `AuthMapper.xml` | `register` INSERT 加 `phone` 列；泛型同步改 `Users` |

> 为什么把 DTO 拆开也要算进这次改动：`RegisterRequest` 同时当"注册请求""登录请求""MyBatis-Plus 实体泛型"用，而注册和登录**将来必填字段不同**（注册要 `phone/smsCode`，登录要 `captchaKey/captchaCode`）。不拆的话，任何一侧加校验都会误伤另一侧。这属于"影响本次工作的既有问题"，顺手修，不做无关重构。

### 3.2 MQ 拓扑（名字写死，供代码直接引用）

```java
exchange  auth.sms.exchange          // TopicExchange, durable   ← 业务消息入口
queue     auth.sms.send.queue        // durable
binding   auth.sms.send.queue       ← auth.sms.exchange  routingKey = "sms.send"
queue     auth.sms.expire.delay.queue  // durable
        //   x-message-ttl            = ${auth.sms.code-ttl-ms:300000}
        //   x-dead-letter-exchange   = auth.sms.expire.dlx
        //   x-dead-letter-routing-key= "sms.expired"
binding   auth.sms.expire.delay.queue ← auth.sms.exchange  routingKey = "sms.expire"   // ★ 缺这条，延迟队列永远是空的
exchange  auth.sms.expire.dlx        // TopicExchange, durable   ← 死信交换机
queue     auth.sms.expired.queue     // durable
binding   auth.sms.expired.queue    ← auth.sms.expire.dlx  routingKey = "sms.expired"
```

**自检时改掉的一个真错误**：初稿只写了 `expired.queue ← dlx` 这一条绑定，漏了 `delay.queue ← auth.sms.exchange(sms.expire)`。少了这条，消费者1 发出的 `sms.expire` 消息匹配不到任何 Binding，会被交换机**静默丢弃**（正是 §2 那个"路由键没人绑定→消息消失"的现象），延迟过期整条链路根本不会触发。最终形态是 **2 个交换机、3 个队列、3 条 Binding**。

**关键：`auth.sms.expire.delay.queue` 故意不放任何 `@RabbitListener`。** 一旦有消费者，消息会被立刻取走，永远不会变成死信，也就永远不会"延迟"。这是新手最容易踩的那个坑。

**为什么用 TTL + 死信，而不是装 `rabbitmq_delayed_message_exchange` 插件**：你那份 RAG 设计文档 `docs/superpowers/specs/2026-10-12-elasticsearch-rag-design.md:197-201` 已经用同一手法做 30s→2m→10m 重试链，并明确避开了插件。TTL+DLX 是纯队列参数，不需要动 broker，也不需要多一个 Erlang 依赖。代价写在 §7。

**为什么 TTL 做成配置项**：`@Value("${auth.sms.code-ttl-ms:300000}")`。测试里要压到 2000ms，否则验一次过期得真等 5 分钟。

⚠️ **但只改 TTL 数值是不够的**：TTL 是**队列的创建参数**，Broker 上同名队列参数不一致会报 `PRECONDITION_FAILED - inequivalent arg 'x-message-ttl'`。所以测试必须同时换一个队列名后缀：

```java
@Value("${auth.sms.queue-suffix:}")      // 生产为空串
String queueSuffix;                       // 队列名 = "auth.sms.expired.queue" + queueSuffix
```

测试用 `auth.sms.queue-suffix=-test` + `auth.sms.code-ttl-ms=2000`，得到一套独立命名的 2 秒拓扑，与本机残留的 5 分钟队列互不冲突。手工开发时如果要改 TTL，仍然得先去管理界面删旧队列——这条写进文档的报错对照表。

### 3.3 Redis key 设计

| key | 内容 | TTL | 谁写 | 谁删 |
|---|---|---|---|---|
| `auth:captcha:{captchaKey}` | 图形码答案 | 120s | `/auth/captcha` | 校验时立即删（一次性） |
| `auth:sms:code:{phone}` | 6 位短信码 | 300s | 消费者1 | 注册校验通过时删；或 5 分钟后消费者2 比对删 |
| `auth:sms:limit:{phone}` | `"1"` | 60s | `/auth/sms/send` 用 `SETNX` | 自动过期（不主动删） |

沿用现有 `AuthRedisService.java:16,18` 的 `auth:` 前缀风格，`RedisConfig` 已配好 String key / JSON value 序列化（`RedisConfig.java:13-27`）。

**一次性语义必须写进代码**：图形码校验无论成败都 `DELETE`。否则同一个 `captchaKey` 可以被拿来无限次试错，图形码就退化成了"给你 6 位数字的空间慢慢猜"。

**消费者2 为什么必须先比对码值**：用户可能在第 3 分钟重新申请了一个新码，而第 0 分钟那条延迟消息在第 5 分钟到期时才会到。无脑 `DELETE` 会把**还有效的新码**删掉，用户收到短信却提示验证码失效。所以：`GET auth:sms:code:{phone}` 与事件里的 code 相等才删。这是幂等消费的最小示范。

---

## 4. 接口契约

| 方法 | 路径 | 请求 | 成功响应 | 主要失败 |
|---|---|---|---|---|
| GET | `/auth/captcha` | — | `Result.success({captchaKey, imageBase64})` | — |
| POST | `/auth/sms/send` | `{phone, captchaKey, captchaCode}` | `Result.success("验证码已发送")` | `图形验证码错误或已失效` / `请60秒后再试` / 手机号格式错误 |
| POST | `/auth/register` | `{username, password, phone, smsCode}` | `Result.success("注册成功")` | `验证码错误或已失效` / `该手机号已注册` |
| POST | `/auth/login` | `{username, password, captchaKey, captchaCode}` | 现有结构不变（token/userName/iat/exp） | `图形验证码错误或已失效` / 现有错误 |

`imageBase64` 形如 `data:image/png;base64,iVBORw0KGgo...`，前端 `<img :src>` 直接绑，省掉第二次请求，也省掉给图片 URL 单独放行。

字段规则（**显式校验**，不靠 `@Valid`，理由见决策 D5）：`phone` 用 `^1[3-9]\d{9}$`；`smsCode` 长度 6 且纯数字；`captchaCode` 长度 6 且纯数字（D11，与前端 `CodeSlots` 的数字输入约束一致）；`username` 沿用前端已有正则 `^[A-Za-z0-9_]{4,20}$`。

---

## 5. 三处放行（漏一处就是 401，且报错很难查）

1. **网关白名单**：`GatewayModule/filter/JwtAuthGlobalFilter.java:43-48` 的 `WHITE_LIST` 现在是 `List.of("/auth/login","/auth/register","/actuator")`，且匹配是 `path.startsWith`（:113）。必须加 `/auth/captcha`、`/auth/sms/send`。
2. **AuthModule SecurityConfig**：`config/SecurityConfig.java:33` 只 permit 了 `/auth/login`、`/auth/register`、`/auth/logout`，两个新路径要加。
3. **Nacos 路由**：`/auth/**` 已经覆盖新路径（GatewayModule 本地 yaml 里注释掉的那段就是这个前缀，已核实 `GatewayModule/application.yaml:11-23`），**大概率不用动**，但要在 Nacos 控制台确认一次实际 Data ID 内容（路由已外置到 Nacos，仓库里看不到，属未核实项）。

---

## 6. 限频与防刷

- **手机号维度**：`SETNX auth:sms:limit:{phone}`，TTL 60s。60 秒内重复申请直接拒。
- **图形码一次性**：见 §3.3，等于"每过一道图形码才能发一条短信"，把脚本刷短信的门槛抬到必须先破解图形码。
- 明确**不做**：IP 维度限频、单手机号当日总量、滑块/行为验证。这些是运维阶段的事，现在写进来只会让 demo 变大而学不到东西。

---

## 7. 诚实的取舍说明（不美化）

**短信码走 MQ 是真有价值的**：它把"投递到外部通道"这件慢且可能失败的事从注册接口里摘出去了，这是消息队列的本职。

**过期走延迟队列的价值要打折看**：`auth:sms:code:{phone}` 用 Redis TTL 自己就会过期，**不需要 MQ**。延迟队列在这里换来的只有"到期时有一个可编程事件"（解锁计数、审计、后续接真通道时的失败重试）。这一段的**主要目的是教学**——让你亲手摸到 TTL + 死信这个几乎所有 Java 后端面试和生产实践都绕不开的手法。我把它保留在设计里，但不假装它是 Redis TTL 的必需品。

**TTL+DLX 的已知缺陷**：RabbitMQ 的队列级 TTL 是**从队头开始检查**的。如果先发一条 5 分钟的、再发一条 10 秒的，10 秒那条不会提前出队——它被前面的消息挡住了（head-of-line blocking）。本设计里所有过期消息 TTL 相同，所以不受影响；但一旦将来要做"每个码不同有效期"，就必须换插件方案或时间轮。这个限制写在这里，不要等踩了才发现。

**MockSmsSender 不是可以上生产的状态**：现在"发"就是打日志。真通道要在 `SmsSender` 上加实现 + 配置切换，并把 `log.info` 的码值输出关掉（日志里印验证码等于把码泄露到日志系统）。

**队列里存着有效验证码明文，这是本次设计有意接受的风险**：`SmsExpireEvent` 带着 `code`，意味着任何能打开 `http://localhost:15672` 或用 `guest` 连 5672 的人，都能从 `auth.sms.expire.delay.queue` 里读到当前有效的码。本机开发无所谓；上生产有三步必须做——① 建独立账号并禁掉 `guest` 远程登录，② 消息体改带 `codeHash`（SHA-256）而不是明文，消费者2 用 `GET` 到的值算哈希再比对（幂等逻辑不变），③ 队列访问权限收敛到服务账号。第 ② 步的唯一代价是多一个哈希计算，没有功能损失，属于"知道就做"级别。

---

## 8. 测试方案

**单元测试**（Mockito，不起 Spring，沿用项目里已有的 `AuthServiceImplLoginIpTest.java` 风格）：
- `CaptchaServiceTest`：生成后 Redis 有 key；校验成功后 key 消失；校验失败后 key 同样消失（一次性）。
- `AuthServiceImplTest`：图形码错 → 断言 `rabbitTemplate` **一次都没被调用**（`verify(..., never())`）；限频命中 → 同样没被调用；手机号格式非法 → 不进 MQ。
- `SmsCodeConsumerTest`：mock Redis 断言写入的 key 名和 TTL；过期事件里码值与 Redis 不等 → **不删**（这条是最容易被写错、也最该测的）。

**集成测试**（`@SpringBootTest`，本机 broker 与 Redis 都在跑，已实测 UserModule demo 能连通）：
- `SmsCodeFlowIntegrationTest`：发 MQ → 等消费者1 → 断言 Redis 出现码 → 用 `auth.sms.code-ttl-ms=2000` + `auth.sms.queue-suffix=-test` 起一套独立命名的 2 秒拓扑（**必须同时换后缀**，否则撞 `PRECONDITION_FAILED`，见 §3.2）→ 等消费者2 → 断言码被删。**必须用 `poll`/`Awaitility` 式等待，不能用 `Thread.sleep(固定)`**——异步测试直接断言必挂，这套写法在 `UserModule/src/test/.../RabbitMqDemoTest.java` 里已经用过。
- 回归：`IpUtilTest`、`AuthServiceImplLoginIpTest`、`AuthControllerTest` 必须仍然绿（DTO 拆分和泛型修正会影响它们，属于预期内的连带修改）。

**前端**：`EZ_frontend` 没有测试框架（无 vitest，零 spec 文件，已核实），所以只能手动验收清单，见 §9 阶段 4。我不会假装前端有自动化测试。

---

## 9. 分阶段实施（每阶段独立可验收）

**阶段 1 — 图形码闭环（不含 MQ）**
`/auth/captcha` + Redis 一次性答案 + 登录接图形码 + 三处放行 + 前端登录 tab 把 `题面` 换成 `<img>` 并删 `verifyCaptchaLocal`。
验收：浏览器登录必须输对图形码才能过；刷新页面能拿到新图。
（这一阶段故意不碰 MQ，先把 Redis、网关、SecurityConfig 这条最容易出问题的"放行链"打通。）

**阶段 2 — 短信码走 MQ + 注册改造 + 改表**
`auth.sms.exchange` + `auth.sms.send.queue` + 消费者1 + `MockSmsSender` + `/auth/sms/send`（含限频）+ 注册校短信码 + DTO 拆分 + `users_dev` 加 `phone` + 前端注册 tab 两步式。
验收：完整注册一次；60 秒内重复申请被限；错误短信码被拒。

**阶段 3 — 延迟过期（TTL + 死信）**
`auth.sms.expire.delay.queue` + `auth.sms.expire.dlx` + `auth.sms.expired.queue` + 消费者2 幂等清理 + 集成测试（TTL 压到 2s）。
验收：测试里能在 2 秒内观测到"过期事件被消费且码被删除"。

**阶段 4 — 文档与手动验收清单**
`RabbitMQ验证码实现文档.md`：为什么用 MQ、每个技术选择的理由与被否方案、逐步骤运行说明、管理界面看什么、常见报错对照表（延续 `RabbitMQ用户模块Demo实现文档.md` 的写法）。
验收：你照着文档能从零跑通一次注册。

---

## 10. 决策记录

| 编号 | 决策 | 备选 | 为什么不选备选 |
|---|---|---|---|
| D1 | 图形码同步生成，不进 MQ | 图形码也走 MQ | 生成只需几毫秒，异步化反而要求前端轮询取图，复杂度全是净亏损 |
| D2 | 手机码走 MQ | 同步调 MockSmsSender | 同步就把"外部通道"塞回了注册接口，通道一慢/一挂注册即失败，正是 §1 要解决的问题 |
| D3 | 图形码返回 JSON+base64 | 返回二进制流 | 二进制流要单独放行图片 URL、还要处理浏览器缓存刷新，收益为零 |
| D4 | 过期用 TTL+死信 | 官方延迟插件 / Redis keyspace 通知 | 插件多一个 broker 侧依赖且与版本绑定（与既有 RAG 设计的决策一致）；Redis keyspace 通知默认关闭且不可靠投递，学不到队列 |
| D5 | 不加 validation starter，用显式校验 | 加 starter 让 `@NotBlank` 生效 | `@Valid` 现在是摆设（§0 事实 1），一旦生效，前端注册不带 `captchaCode` 立刻 400，等于把"加验证码"变成"破坏现有接口"。显式校验代码可读，也更贴合学习目的 |
| D6 | DTO 拆成三个 | 继续共用 `RegisterRequest` | 两个接口的必填字段已经不同，共用会互相误伤；且现状还把它当 MyBatis 实体泛型用，是明确的误用 |
| D7 | 功能落 AuthModule | 落 UserModule（上次 demo 在那） | 登录注册和 Redis 都在 AuthModule；UserModule 两者皆无，跨模块调用只会平添复杂度 |
| D8 | Mock 短信实现 + `SmsSender` 接口 | 直接 `log.info` 写在消费者里 | 接口让"接真通道"变成加一个类而不是改消费者，成本一行，收益明显 |
| D9 | `phone` 加**唯一索引** | 普通索引或无索引 | 防一人占多号。列是新加的，历史行全是 `NULL`，而 MySQL 唯一索引允许多行 `NULL`，所以**不会与既有数据冲突**（这条让附录 B 的"历史数据会不会撞"变成不存在的问题） |
| D10 | `captchaKey` 由**后端**生成（32 位 UUID），前端只回传 | 前端生成 key | key 是服务端状态存储的索引，交给客户端等于让客户端决定 Redis 里写哪个 key，可被构造碰撞/覆盖 |
| D11 | 图形码是 **6 位纯数字** | 字母+数字混合 | 前端 `CodeSlots.vue` 是 6 格数字输入（`inputmode="numeric"`、`pattern="[0-9]*"`、`CAPTCHA_LENGTH = 6`，已核实）。混合字符要改组件和校验正则，纯为难度不值。实现上给 hutool `LineCaptcha` 换成**只含数字的 `RandomGenerator`**，别用默认的字母数字生成器 |

---

## 附录 A：本机环境（已实测）

| 组件 | 位置/状态 |
|---|---|
| Erlang/OTP | `F:\erl-27.3.4`，静默安装，`erl` 自测输出 `Erlang/OTP 27 [erts-15.2.7]` |
| 机器级环境变量 | `ERLANG_HOME=F:\erl-27.3.4`（PowerShell `SetEnvironmentVariable(...,'Machine')` 设置；`setx /M` 在 Git Bash 下会把 `/M` 当路径转换而失败，这是个已知坑） |
| RabbitMQ | `F:\RabbitMQ\rabbitmq_server-4.3.6`，数据目录 `F:\RabbitMQ\data`，服务名 `RabbitMQ`，StartType=Automatic，运行中 |
| 端口 | 5672 / 15672 / 4369 / 25672 全部 LISTENING（已核实） |
| 管理插件 | `rabbitmq-plugins enable rabbitmq_management` 后**必须重启服务**才生效（离线变更），已验证 |
| 下载源 | GitHub 在本机不可达；实际用的镜像：`mirrors.huaweicloud.com/rabbitmq-server/v4.3.6/...`、`www.erlang.org/download/otp_win64_27.3.4.exe`，两者字节数与 HEAD 响应核对一致 |
| demo 验证 | `UserModule` 的 `RabbitMqDemoTest` 两个测试**已通过**（`Tests run: 2, Failures: 0`），日志可见 `Created new connection: amqp://guest@127.0.0.1:5672/` |
| 待办 | 网关 8100 未启动；前端联调前需要起 `GatewayModule` + `AuthModule` |

## 附录 B：只剩一件必须由你定的事

1. **`ALTER TABLE users_dev` 由谁执行。** 本机没有 `mysql.exe`（`Program Files` 下已搜过，未找到），有两条路：
   - 你在 IDEA 的 Database 面板里跑（推荐，你自己看得见执行结果）；
   - 你授权我用 JDBC 跑（`~/.m2` 里有 `mysql-connector-j-8.3.0.jar`，可以写个一次性脚本）。**这条路需要你明确同意**，而且我会先把 `SHOW CREATE TABLE users_dev` 的真实结果贴给你，再贴 `ALTER` 语句，两条都确认后才执行。
2. 表结构是**未核实项**：仓库零 `.sql`，`Users.java` 里声明的列不等于库里真实存在的列。所以上面第 1 步里那条 `SHOW CREATE TABLE` **必须真的跑一次**，不能拿代码当依据。
3. Nacos 里 `/auth/**` 路由的实际内容（§5 第 3 条）：需要你在控制台看一眼。

（原附录 B 里"phone 要不要唯一索引""captchaKey 谁生成""图形码几位"三条已经在自检时定为 D9 / D10 / D11，不再悬空。）
