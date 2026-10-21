# 登录用户信息跨服务获取：OpenFeign 与 RabbitMQ 双链路设计

日期：2026-10-19
范围：AuthModule（登录方 / 数据方）+ UserModule（消费方）
状态：待评审

---

## 1. 目标与非目标

### 目标

用**同一个业务场景**（用户登录成功）把两种跨服务协作方式各实现一遍，并让它们的差异**可被观察和测量**，而不只是背概念：

1. **RabbitMQ 异步链路**：AuthModule 登录成功后发布"登录事件"，UserModule 订阅并做衍生处理。
2. **OpenFeign 同步链路**：UserModule 消费时发现消息里没有角色、手机号，反查 AuthModule 补全。

两条链路组合成一个真实的生产形态：**瘦消息 + 胖查询**（消息只带事件本身，需要更多字段时按需回查）。

### 非目标

- 不做分布式事务。登录落库和事件发布之间允许短暂不一致，靠 `traceId` 排查。
- 不引入 Seata、Sentinel 熔断、Resilience4j。项目里已有这些依赖，本设计不碰。
- 不建新表、不改表结构。消费端的"后续处理"用内存环形队列 + 查询端点观察。
- 不实现 `queue-suffix`（`AuthModule/config/CaptchaMqConfig.java:37` 那个约定）。它是为多实例避免抢同一延迟队列而存在的，本场景单消费者，加了只有噪音。

---

## 2. 现状事实（设计依据，均已核实）

| 事实 | 证据 |
|---|---|
| 全项目零 `@FeignClient`、零 `@EnableFeignClients` | 检索 `@FeignClient\|@EnableFeignClients` 无任何命中 |
| 但 Feign 依赖是齐的，且是**全模块继承** | 父 `pom.xml:107-111`（openfeign）、`:101-105`（loadbalancer）、`:96-99`（nacos-discovery） |
| 两个服务都已注册到 Nacos | `AuthModuleServer`:8103（`AuthModule/application.yaml:24`）、`UserModuleServer`:8101（`UserModule/application.yaml:13`） |
| **两个服务连的是同一个库同一张表** | `AuthModule/application.yaml:42` 与 `UserModule/application.yaml:31` 均为 `jdbc:mysql://localhost:3306/ezpracticeexercises`；`@TableName("users_dev")` 见 `AuthModule/pojo/Users.java:16` 与 `UserModule/pojo/Users.java:15` |
| 角色/手机号只在 AuthModule 的实体里映射了 | `AuthModule/pojo/Users.java:37-38`（`RoleId`）、`:72-73`（`phone`）；UserModule 的 `Users` 无这两个字段 |
| Redis 只在 AuthModule | `AuthModule/application.yaml:10-22`；UserModule 无 redis 配置与依赖 |
| 登录成功后的可用数据 | `AuthServiceImpl.java:89`（`clientIp`）、`:92`（token）、`:95`（写 Redis 的完整 `Users`） |
| `AuthMapper.selectByUserId` 已存在 | `AuthMapper.xml:23-25`，`resultType` 自动映射 |
| MQ 已有成型约定可照抄 | `CaptchaMqConfig.java:40-84`（QueueBuilder + DLX + 常量）、`SmsCodeProducer`、`SmsCodeConsumer`、`pojo/mq/SmsSendRequest`（含 `traceId`） |
| 项目已有"负向演示端点"的先例 | `UserModule/controller/MqDemoController.java:37-42` 的 `/no-binding` |
| RabbitMQ 本地可用 | 127.0.0.1:5672（两模块 yaml 一致），管理台 15672 |

### 两个必须先解决的拦路问题

**(a) `/auth/internal/**` 现在会返回 401。**
`SecurityConfig.java:33-34` 只放行了登录/注册等 5 个路径，`:35` 是 `anyRequest().authenticated()`，而 `:55` 的 JWT 过滤器整段被注释——也就是说**没有任何东西能完成认证**。Feign 调过去必然 401。这不是"没保护"，是"保护得死死的但没人能过"。

**(b) 两个模块都有重复的 `MessageConverter` bean。**
AuthModule：`MqConfig.java:11`（`jacksonMessageConverter`）+ `CaptchaMqConfig.java:87`（`jsonMessageConverter`）。
UserModule：`config/MqConfig.java:11` + `config/RabbitMqDemoConfig.java:121`。
Spring Boot 通过 `ObjectProvider<MessageConverter>` 取转换器，同类型多候选且无 `@Primary` 时可能取不到，退回默认 JDK 序列化——那会让"MQ 链路到底通不通"这个判断从第一步就失真。**必须在动业务代码之前先收掉。**

---

## 3. 选型结论

先给结论，第 11 节给验证它的实验。

| 维度 | OpenFeign（同步 HTTP） | RabbitMQ（异步消息） |
|---|---|---|
| 调用方是否等待 | 等，登录接口耗时 +一次 RPC | 不等，发完即返回 |
| 语义 | 请求-响应，**现在就必须要答案** | 事件通知，**以后处理就行** |
| 耦合 | 强：依赖对方**在线** + 接口签名 | 弱：只依赖队列存在 + 字段名 |
| 故障传播 | 对方挂 → 本方直接失败 | 对方挂 → 消息堆积，主链路无感 |
| 一对多扩展 | 每加一个下游，调用方都要改 | 加一个绑定即可，生产方不改 |
| 可靠性 | 失败即丢失（除非自己写重试） | Broker 持久化 + ack + 重试 + 死信 |
| 调试成本 | 低：curl 一把梭，断点直达 | 高：要看队列、要防消息被吞、要考虑重复消费 |
| 契约保障 | 编译期（接口签名） | 无编译期保护，靠约定 |

### 本场景的判定

- **登录成功这件事 → MQ。** 登录接口不需要知道下游是谁、下游做几件事；AuthModule 不能因为 UserModule 在重启就让登录变慢或失败。
- **需要角色/手机号才能继续往下处理 → Feign。** 这是请求-响应语义，用 MQ 实现"发个查询消息再等回复"是把自己绕进 RPC 的坑里，还要手搓 correlationId。
- **反面判据（重要）：** 如果只是"按 userId 查用户名/状态"，**两个都不该用**。`UserModule` 和 `AuthModule` 同库同表，`UsersMapper`（`UserModule/mapper/UsersMapper.java:14`，继承 `BaseMapper`）的 `selectById` 本地一次查询就拿到了。跨服务调用只有在"数据/能力只在对端"时才有正当性——这是本设计把消息**故意**做成不带 `roleId`/`phone` 的原因，否则两条腿都是假的。

---

## 4. 架构总览

```
┌──────────────── AuthModule (8103) ────────────────┐
│  POST /auth/login                                 │
│    └─ AuthServiceImpl.login                       │
│         ├─ :89 updateLastLogin(clientIp)          │
│         ├─ :92 generateToken2                     │
│         ├─ :95 saveLoginInfo → Redis              │
│         ├─ 【新】LoginEventPublisher.publish()     │  ← try/catch 包住，MQ 挂了不影响登录
│         └─ :104 return Result.success(data)       │
│                                                   │
│  【新】GET /auth/internal/users/{userId}           │  ← 带 X-Internal-Token 校验
│  【新】GET /auth/demo/login-event?userId=1         │  ← 浏览器手动触发，绕开验证码
│  【新】LoginMqConfig: TopicExchange auth.login.exchange │
└───────────────────────┬───────────────────────────┘
                        │ convertAndSend("auth.login.exchange", "user.login.success", LoginSuccessEvent)
                        ▼
                 ┌──── RabbitMQ ────┐
                 │ user.login.event.queue │──x-dead-letter──▶ user.login.event.dlx
                 └────────┬─────────┘              └─▶ user.login.event.dead.queue
                          │ @RabbitListener
                          ▼
┌──────────────── UserModule (8101) ────────────────┐
│  【新】LoginEventConsumer                          │
│    ├─ 收到 LoginEvent                              │
│    ├─ authModuleClient.getUserProfile(userId) ────┼── Feign 同步反查 ──▶ AuthModule
│    ├─ 组装 ProcessedLogin → 环形队列(20)            │
│    └─ 失败：404 → 立刻进死信；其它 → 重试 3 次       │
│  【新】GET /user/mq/login/recent  观察处理结果       │
│  【新】@EnableFeignClients                         │
└───────────────────────────────────────────────────┘
```

---

## 5. MQ 拓扑与消息契约

### 5.1 拓扑

| 元素 | 名称 | 类型 | 声明方 |
|---|---|---|---|
| 交换机 | `auth.login.exchange` | topic, durable | AuthModule |
| 路由键 | `user.login.success` | — | 双方字符串必须完全一致 |
| 主队列 | `user.login.event.queue` | durable，带 `x-dead-letter-exchange` | UserModule |
| 死信交换机 | `user.login.event.dlx` | direct, durable | UserModule |
| 死信队列 | `user.login.event.dead.queue` | durable | UserModule |

死信队列的 `deadLetterRoutingKey` 用 `user.login.dead`，与主队列的路由键**不同**——这能演示"死信是一次重新投递，路由键要单独绑定"，也正是 `CaptchaMqConfig.java:75-79` 那条注释记录的坑（缺绑定则链路静默失效）。

### 5.2 消息体 `AuthModule/pojo/mq/LoginSuccessEvent.java`

```java
Long   userId;
String username;
String traceId;     // UUID，对齐 SmsSendRequest 的排查约定
String loginIp;     // AuthServiceImpl:89 已有
Long   loginAt;     // epoch millis
String tokenFingerprint;  // SHA-256(token) 前 12 位
```

三个刻意的设计，都是教学支点：

1. **不含 `roleId`、`phone`。** 消费端因此必须回查，Feign 那条腿才是真的。
2. **不含 token 本体，只含指纹。** JWT 进队列 = 凭证落盘，管理台上明文可见。指纹足够做日志关联。
3. **时间用 `long`。** 跨语言消费者解析简单，也不依赖 `jackson-datatype-jsr310` 在两端版本一致。

### 5.3 契约的耦合点（要写进代码注释）

交换机名和字段名是**跨服务的隐式契约**：AuthModule 用常量类，UserModule 用另一个常量类，两边字符串各写一份，**没有任何编译期保护**。改字段名 = 消费端静默拿到 null。这是 MQ 相对 Feign 的真实代价，别把它说成"完全解耦"。

---

## 6. AuthModule 侧改动

### 6.1 生产者 `mq/LoginEventPublisher.java`

构造器注入 `RabbitTemplate`（对齐 `SmsCodeProducer.java:16` 的风格），方法：

```java
public void publish(LoginSuccessEvent event)  // 内部 convertAndSend(EXCHANGE, LOGIN_SUCCESS_KEY, event)
```

### 6.2 挂进登录流程（`AuthServiceImpl.login`，插在 `:95` 之后、`:98` 之前）

```java
try {
    loginEventPublisher.publish(buildLoginEvent(users, token, clientIp));
} catch (Exception e) {
    log.error("[登录事件] 发布失败，不影响登录。userId={}, traceId={}", users.getId(), traceId, e);
}
```

**这段 try/catch 是整个设计的第一个论点**：删掉它，RabbitMQ 一挂登录接口就 500——那 MQ 相对 Feign 的优势就没了，反而变成劣势。

### 6.3 Feign 服务端 `controller/InternalUserController.java`

```java
@GetMapping("/auth/internal/users/{userId}")
public ResponseEntity<UserProfileVO> getUserProfile(
        @PathVariable Long userId,
        @RequestHeader(name = "X-Internal-Token", required = false) String token)
```

- 返回**裸 VO，不套 `Result`**。`AuthModule/pojo/Result.java:9-36` 的 `data` 声明为 `Object`，Jackson 反序列化 `Object` 得到 `LinkedHashMap`，UserModule 侧写 `Result<UserProfileVO>` 会在运行时炸。这是本项目现有 `Result` 设计下唯一的坑位，internal API 绕开它。
- 数据源：`AuthMapper.selectByUserId`（`AuthMapper.xml:23-25`，`resultType` 自动映射 + `map-underscore-to-camel-case: true` 见 `application.yaml:59`）。**不需要新增 SQL。**
- 用户不存在 → `ResponseEntity.notFound()`（404），消费端据此走"不重试"分支（见 8.2）。
- `UserProfileVO`：`userId, username, roleId, status, phone, lastLoginAt, lastLoginIp`。**绝不包含 `password_hash`。**

### 6.4 打通 401 + 加最小鉴权

`SecurityConfig` 加两行，并新增一个只作用于 internal 路径的过滤器：

```java
.requestMatchers("/auth/internal/**").permitAll()   // 交给下面的自定义头校验
```

`config/InternalApiAuthFilter.java`（`OncePerRequestFilter`）：对 `/auth/internal/**` 校验 `X-Internal-Token` 等于配置值 `auth.internal.token`，不等则 401。**不用 permitAll 了之**——8103 是直接对进程外开放的端口，任何能连到这个端口的人都能查任意用户的手机号。

`/auth/demo/login-event` 不在此过滤器范围内（否则浏览器点不动），日志一律打 `[DEMO]` 前缀，并在类注释里写明"生产环境必须删除该控制器"。

### 6.5 手动触发端点 `controller/AuthDemoController.java`

```java
@GetMapping("/auth/demo/login-event")
public Result triggerLoginEvent(@RequestParam(defaultValue = "1") Long userId)
```

直接查库构造事件并发布，返回 `traceId`。**绕开图形验证码**（`AuthServiceImpl.java:74` 会在登录第一步就拦住浏览器调用）。传 `userId=999999` 就是死信演示。

---

## 7. UserModule 侧改动

### 7.1 打开 Feign

`UserModuleApplication.java` 加 `@EnableFeignClients`。`org.leon.usermodule.feign` 已在 `:11` 的 `@ComponentScan(basePackages = {"org.leon.usermodule", ...})` 覆盖范围内，不需要额外扫描配置。

### 7.2 客户端 `feign/AuthModuleClient.java`

```java
@FeignClient(name = "AuthModuleServer", path = "/auth/internal")
public interface AuthModuleClient {
    @GetMapping("/users/{userId}")
    UserProfileVO getUserProfile(@PathVariable("userId") Long userId);
}
```

- `name` 用 Nacos 里的服务名 `AuthModuleServer`，不是 URL。loadbalancer 已在父 pom，无需额外配置。
- **方法签名里不写 header 参数**，token 由一个全局 `RequestInterceptor` bean 统一注入，业务代码保持干净：

```java
@Bean RequestInterceptor internalTokenInterceptor(@Value("${auth.internal.token}") String t) {
    return template -> template.header("X-Internal-Token", t);
}
```

（服务端 6.3 那个 `required = false` 是为了让过滤器自己决定返回 401 还是让 Spring 抛 400，两者取一，本设计走前者。）

### 7.3 消费端 DTO `pojo/mq/LoginEvent.java`

字段与 5.2 完全同名。类名和包名**故意不同**（`org.leon.usermodule.pojo.mq.LoginEvent`）——这是为了真实演示"MQ 传的是数据，不是类型"，代价见 7.5。

### 7.4 消费者 `mq/LoginEventConsumer.java`

```java
@RabbitListener(queues = LoginEventQueueConfig.LOGIN_EVENT_QUEUE)
public void onLoginEvent(LoginEvent event) {
    // 1. MDC.put("traceId", event.getTraceId())
    // 2. UserProfileVO profile = authModuleClient.getUserProfile(event.getUserId())
    // 3. 组装 ProcessedLogin（event + profile）入容量 20 的 ArrayDeque
    // 4. log.info("[登录事件消费者] ...")
}
```

**除 8.2 的 404 分支外，不写 try/catch。** 异常往上抛才会触发重试和死信；catch 掉 = 消息被静默吞掉，这是 MQ 消费端最常见的事故。

`ProcessedLogin`（`UserModule/pojo/vo/ProcessedLogin.java`）：`LoginEvent event` + `UserProfileVO profile` + `long consumedAt`，只用于观察，不落库。

配套 `LoginDeadLetterConsumer` 监听 `user.login.event.dead.queue`，只打日志，让死信在应用日志里可见（管理台上也能看到队列深度）。

### 7.5 必须解决的类型头问题

`Jackson2JsonMessageConverter` 默认把生产端的类全限定名写进 `__TypeId__` 头，值为 `org.leon.authmodule.pojo.mq.LoginSuccessEvent`。UserModule 没有这个类，反序列化会失败。

**采用方案**：UserModule 的消费端转换器设 `TypePrecedence.INFERRED`，让 `@RabbitListener` 方法参数类型成为权威，按字段名映射。生产端零改动。

已核实 API 存在（spring-amqp 3.1.3）：`AbstractJackson2MessageConverter.setTypePrecedence(Jackson2JavaTypeMapper$TypePrecedence)`，枚举只有 `INFERRED` 和 `TYPE_ID` 两个值。

**备选**（若实测 `INFERRED` 行为不符）：`DefaultJackson2JavaTypeMapper.setIdClassMapping(Map.of("org.leon.authmodule.pojo.mq.LoginSuccessEvent", LoginEvent.class))`。

这条**不许凭记忆实现**：E2E 测试里必须有一条真实收发断言（见 10.3），跑过了才算成立。

### 7.6 观察端点

加进现有 `MqDemoController`（沿用 `/user/mq` 前缀）：

```java
@GetMapping("/login/recent")   // 返回环形队列里的 ProcessedLogin，倒序
```

---

## 8. 失败路径

### 8.1 重试与死信参数（`UserModule/application.yaml:40-42` 的 `listener.simple` 块内追加）

```yaml
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 2000
          multiplier: 2
          max-interval: 10000
        default-requeue-rejected: false
```

`default-requeue-rejected: false` 是关键。没有它，重试耗尽后消息重回队首，**死信队列永远是空的**，而且消费端会无限刷同一条消息。`prefetch: 1`（现有配置）保留不动。

### 8.2 区分"可重试"和"重试也没用"

```java
catch (FeignException.NotFound e) {
    throw new AmqpRejectAndDontRequeueException("用户不存在，不重试: " + event.getUserId(), e);
}
```

`AmqpRejectAndDontRequeueException` 是 Spring AMQP 的标准做法：跳过 3×退避（这里约 14 秒）直接进死信。用户不存在这件事，重试一百次也不会突然存在。其它 `FeignException`（5xx、超时、连接拒绝）继续往外抛，走完整重试。

这一条把"重试不是万能药"讲清楚了，也顺便演示了死信的两个入口。

---

## 9. 已知坑清单

| 坑 | 触发条件 | 处理 |
|---|---|---|
| Bean 重名 `BeanDefinitionOverrideException` | 同名 `@Bean` 方法出现在两个 `@Configuration` | 本次先删两个重复的 `MqConfig.java` |
| 队列参数不可变 `PRECONDITION_FAILED` | 队列已存在但 `x-dead-letter-exchange` 等不同 | 队列名是新的，首次创建即带正确参数；日后改参数必须改队列名（`CaptchaMqConfig.java:64-66` 的注释就是这个教训） |
| `__TypeId__` 类型头 | 两端 DTO 全限定名不同 | 7.5 |
| `Result.data` 是 `Object` | internal 接口套 `Result` | 6.3 返回裸 VO |
| `anyRequest().authenticated()` | 直接给 internal 加控制器 | 6.4 permitAll + 头校验 |
| 消息被 catch 吞掉 | 消费者里写 try/catch 且不重抛 | 7.4 禁止；8.2 例外且必须显式 |
| 重复消费 | 重试后的消息可能已部分处理 | 本设计用 traceId 打日志去重演示，不实现幂等表（非目标） |
| 交换机名两边各写一份 | 改一边没改另一边 | 消息进黑洞，无报错。代码注释写明，测试断言两边常量相等 |
| Sentinel 包裹 Feign | 两模块都有 sentinel 依赖 | 观察项，若启动报找不到 fallback 再处理，不预先配置 |

---

## 10. 验证方案

### 10.1 正常链路（浏览器两条 URL）

1. 起 RabbitMQ（本机 4.3.6 服务已在跑）、Nacos、MySQL、两个应用。
2. `GET http://127.0.0.1:8103/auth/demo/login-event?userId=1` → 拿到 traceId。
3. UserModule 日志出现 `[登录事件消费者]`，含反查到的 `roleId`、`phone`。
4. `GET http://127.0.0.1:8101/user/mq/login/recent` → 能看到这条记录。
5. 管理台 `15672`：`user.login.event.queue` 深度 0，无死信。

### 10.2 失败链路（这是选型结论的证据）

**实验 A：数据不存在（不用重启）**
`GET /auth/demo/login-event?userId=999999` → 反查 404 → 立即进死信，**登录/触发端点仍返回 200**，`user.login.event.dead.queue` 深度 +1。

**实验 B：下游依赖解析不到服务（不需要停 AuthModule）**
把 UserModule 的 `@FeignClient(name = "AuthModuleServer")` 临时改成 `AuthModuleServerX`，重启 UserModule，AuthModule 全程正常运行。反查必失败（负载均衡找不到实例）→ 重试 3 次（日志能看到 2s/4s/8s 间隔）→ 进死信；**同时直接 curl AuthModule 的触发端点仍然返回 200**。这就是"下游自己的依赖故障不会传染回上游"。

**实验 C：对端真的挂了，消息不丢**
1. 停 UserModule（消费者下线）。
2. AuthModule 正常触发一批事件（比如 5 条）→ 消息全部堆在 `user.login.event.queue`，管理台深度 +5。
3. 停 AuthModule，再启动 UserModule → 应用照常启动完成（**Feign 客户端不会在启动时校验服务是否存在**），队列里的消息等着。
4. 重启 AuthModule → 堆积的消息被逐条消费掉，深度归 0。

第 3 步是对照组的关键：**换成同步 Feign 方案，AuthModule 挂的那一刻，登录接口就是 500，而且那次调用的数据永远不会再有机会被处理**——消息不存在，也就谈不上重试和堆积。这个推演不在本次实现范围内（13 节第一行），只做结论对照。

### 10.3 端到端断言

`LoginEventFlowE2ETest`（参照 `AuthModule/src/test/.../mq/SmsCodeFlowE2ETest.java`：真 broker + 查 `127.0.0.1:15672/api`）必须断言：

- 消息真的被反序列化成 `LoginEvent` 且 `username` 非空 → **这条同时验证 7.5 的 `INFERRED` 假设**；
- `FeignException.NotFound` 路径下死信计数 +1；
- 触发端点在 MQ 断开时仍返回 200（把 `spring.rabbitmq.port` 指错来验证）。

---

## 11. 测试矩阵

| 测试 | 风格参照 | 断言 |
|---|---|---|
| `LoginEventPublisherTest` | `SmsCodeProducerTest.java:15-16`（`@ExtendWith(MockitoExtension.class)`） | `convertAndSend` 的交换机、路由键、payload 字段 |
| `LoginEventQueueConfigTest` | `CaptchaMqConfig` 拓扑测试 `CaptchaMqTopologyTest.java:29`（`RabbitAdmin`） | 队列存在、`x-dead-letter-exchange`/`deadLetterRoutingKey` 正确、绑定存在 |
| `LoginEventConsumerTest` | `SmsCodeConsumerTest.java:32,56`（`ReflectionTestUtils`） | 成功入环形队列；mock 抛 `FeignException` 时异常必须外抛；抛 `NotFound` 时转成 `AmqpRejectAndDontRequeueException` |
| `InternalUserControllerTest` | AuthModule 现有 controller 测试 | 查得到 roleId/phone；不存在返回 404；响应体不含 `password_hash`；缺 header 401 |
| `LoginEventFlowE2ETest` | `SmsCodeFlowE2ETest.java:40-44` | 见 10.3 |
| `AuthServiceImplLoginEventTest` | `AuthServiceImplLoginIPTest.java:45` | publisher 抛异常时 login 仍返回成功 |

约定延续：`@DisplayName` 每个方法都写；MQ 组件用构造器注入，controller/service 沿用现有 `@Autowired` 字段注入风格。

---

## 12. 实现分批（每段独立可验收）

按"一段一段过"的方式执行，每段结束时应用必须能启动。

**第 1 段：清障。** 删两个重复 `MqConfig.java`、删 `comsumer/Listener/LoginListener.java`（**只删这个文件**，同目录的 `MqListener.java` 是旧 demo，保留，因此 `comsumer` 这个拼错的包目录还要继续存在一段时间，本次不动它以免牵连无关改动）、清 `UserModuleApplication.java:3-7` 残留 import。验收：两模块 `mvn -o -pl <模块> -am -DskipTests compile` 通过，能启动，管理台看得到原有队列。

**第 2 段：MQ 半边跑通（不含 Feign）。** 事件 DTO + 交换机 + 队列/DLX 拓扑 + publisher + 挂在 login + demo 触发端点 + 消费者先只做日志。验收：10.1 的步骤 1-3（此时 profile 还没查）。

**第 3 段：Feign 半边接入。** `@EnableFeignClients` + `InternalUserController` + `InternalApiAuthFilter` + SecurityConfig + 客户端 + `RequestInterceptor` + 消费端反查。验收：10.1 全部通过，`/user/mq/login/recent` 有数据。

**第 4 段：失败路径与实验。** retry/DLX 配置 + `AmqpRejectAndDontRequeueException` 分支 + 死信消费者 + 10.2 两个实验 + E2E 测试。验收：死信队列能看到堆积，重放能消费掉。

---

## 13. 未采纳的备选与理由

| 备选 | 为什么不用 |
|---|---|
| AuthModule 登录时同步 Feign 调 UserModule | 把下游故障传染给登录主链路，正好是要演示的反面；且登录有明确"不等下游"的语义 |
| 消息里带全所有字段，消费端不回查 | Feign 那条腿变成摆设，两条链路的对比消失 |
| 两端共享同一 DTO 类（放 `CommonJwt` 或新建 common 模块） | `CommonJwt` 目前只有 `JwtConfig` + `JwtUtil`（`CommonJwt/src/main/java/org/leon/commonjwt/`，共 2 个类），塞业务 DTO 会让它变耦合点。用 `INFERRED` 换掉编译期耦合，代价写在 5.3 |
| RabbitTemplate + `correlationId` + reply queue 手写 RPC | 用 MQ 模拟请求-响应，等于自己重造 Feign 且更难，本场景没有需要它理由 |
| 消费端新建 `user_login_stat` 表落库 | 要动 DDL，超出本次范围；环形队列 + 查询端点已足够观察 |
| `spring.main.allow-bean-definition-overriding=true` 绕过重名 | 掩盖重复定义而不是解决，上一轮已经踩过一次 |
