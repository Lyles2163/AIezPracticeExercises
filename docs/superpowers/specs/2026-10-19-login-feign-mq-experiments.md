# 登录场景 Feign + MQ 双链路实验结果

日期：2026-10-21
对应设计：`docs/superpowers/specs/2026-10-19-login-feign-mq-design.md`
对应计划：`docs/superpowers/plans/2026-10-19-login-feign-mq.md`

---

## 1. 结论摘要

1. **正常链路成立**：登录事件经 RabbitMQ 到达 UserModule，消费端用 Feign 反查 AuthModule，`/user/mq/login/recent` 能看到补全后的 `profile.roleId`。
2. **MQ 让登录主链路与下游解耦**：Feign 目标服务解析失败时，UserModule 重试 3 次后进死信；AuthModule 的触发端点全程返回 200。
3. **404 不该重试**：改造后 `userId=999999` 在 **637ms** 内直接进死信；此前会被 Spring Retry 白重试 3 次（约 6.7s）。
4. **消费者离线期间消息不丢**：停掉 UserModule 后投 5 条，主队列深度为 5；消费者启动后 5 条全部处理，深度归 0。
5. **Feign 不会在启动时校验目标服务**：AuthModule 完全下线时 UserModule 仍能正常启动，只在真正调用时失败。

---

## 2. 实验环境

| 组件 | 状态 |
|---|---|
| AuthModule | 8103，注册到 Nacos 的 `AuthModuleServer` |
| UserModule | 8101，注册到 Nacos 的 `UserModuleServer` |
| RabbitMQ | 4.3.6，127.0.0.1:5672，管理台 15672（guest/guest） |
| Nacos / MySQL / Redis | 127.0.0.1:8848 / 3306 / 6379 |
| JDK | `C:/Users/Administrator/.jdks/ms-21.0.11` |

> 注意：PATH 上的 `java` 是 17，直接 `java -jar` 会 `UnsupportedClassVersionError`，必须用 JDK 21 的完整路径。

---

## 3. 实验一：正常链路

### 操作

```bash
GET http://127.0.0.1:8103/auth/demo/login-event?userId=1
GET http://127.0.0.1:8101/user/mq/login/recent
```

### 实测

触发端点返回：

```json
{"code":1,"msg":"success","data":"2da29335-7272-4992-a12e-21cb7a5f3bb5"}
```

UserModule 日志：

```text
15:26:36.783 [登录事件消费者] traceId=2da29335... userId=1 roleId=0 phone=null
```

观察端点返回（节选）：

```json
{"event":{"userId":1,"username":"admin","traceId":"2da29335...","loginIp":"127.0.0.1","loginAt":1792567596738,"tokenFingerprint":"65d01b54c870"},
 "consumedAt":1792567596783,
 "profile":{"userId":1,"username":"admin","roleId":"0","status":1,"phone":null,"lastLoginAt":null,"lastLoginIp":null}}
```

主队列与死信队列深度均为 0。

### 结论

- 瘦消息（只有 userId/username/traceId/IP/时间/指纹）成功驱动了消费端。
- `roleId` 不存在于消息里，只能来自 Feign 反查，因此 Feign 这条腿是真实生效的。
- 本机 `users_dev` 所有用户的 `phone` 都是 null，所以端到端看到的是 `phone=null`；手机号透传由 `LoginEventConsumerTest` 中的 `13631155285` 断言覆盖。

---

## 4. 实验二：404 数据不存在 → 立即进死信

### 操作

演示端点会先查库，用户不存在时根本不发布事件，所以绕过端点、直接从管理 API 往交换机投消息：

```bash
curl -u guest:guest -H "Content-Type: application/json" -X POST \
  http://127.0.0.1:15672/api/exchanges/%2F/auth.login.exchange/publish \
  -d '{"properties":{"content_type":"application/json"},"routing_key":"user.login.success","payload":"{\"userId\":999999,\"username\":\"ghost\",\"traceId\":\"manual-404-fast\",\"loginIp\":\"127.0.0.1\",\"loginAt\":1700000000000,\"tokenFingerprint\":\"abcdef123456\"}","payload_encoding":"string"}'
```

### 实测

```text
15:26:23.610 [登录事件消费者] traceId=manual-404-fast userId=999999 开始反查资料
15:26:24.247 [登录死信] 消息进入死信队列，traceId=manual-404-fast userId=999999
```

从消费到进死信 **637ms**，中间只有一次反查。

### 关键发现：`AmqpRejectAndDontRequeueException` 本身不会跳过 Spring Retry

第一次实现只抛 `AmqpRejectAndDontRequeueException`，实测仍然重试了 3 次（约 6.7 秒）才进死信。原因是 Spring Retry 的 `RetryOperationsInterceptor` 默认重试所有异常，`AmqpReject...` 只影响重试耗尽后的 requeue 行为。

解决办法是 `UserModule/config/LoginRetryConfig.java` 中的 `RabbitRetryTemplateCustomizer`：

```java
new SimpleRetryPolicy(maxAttempts,
        Map.of(AmqpRejectAndDontRequeueException.class, false), true, true)
```

`traverseCauses=true` 让它能看穿 `ListenerExecutionFailedException` 包装，找到真正的 `AmqpRejectAndDontRequeueException`。这是设计文档 §8.2 没写到的实现细节。

---

## 5. 实验三（设计实验 B）：下游服务解析失败，不传染上游

### 操作

1. 临时把 `AuthModuleClient` 的 `@FeignClient(name = "AuthModuleServer")` 改成 `AuthModuleServerX`，重新打包并重启 UserModule。
2. AuthModule 全程正常运行。
3. 触发 `/auth/demo/login-event?userId=1`。

### 实测

- 触发端点返回 `code=1`，traceId `3dee5ad5-6a18-4035-b224-b0a0d29bc107`。
- UserModule 连续 3 次进入消费者方法：

```text
15:33:17.212 开始反查资料
15:33:20.773 开始反查资料   (+3.56s，含一次失败耗时 + 2s 退避)
15:33:24.776 开始反查资料   (+4.00s 退避)
15:33:24.805 [登录死信] 消息进入死信队列
```

- 异常为 `feign.RetryableException: AuthModuleServerX executing GET http://AuthModuleServerX/auth/internal/users/1`。
- 故障期间再次 `GET /auth/demo/login-event?userId=1` 仍返回 **HTTP 200**。
- UserModule 恢复配置并重启后，链路回到实验一的结果。

### 结论

- 下游自己的依赖故障不会反向传染给 AuthModule；登录端点只负责发事件。
- 重试次数由 `max-attempts: 3` 决定，实际只有两次退避（2s、4s），总耗时约 6 秒；设计文档里写的 "2s/4s/8s、约 14 秒" 对应的是 4 次尝试，实际配置是 3 次。
- 重试耗尽后 `default-requeue-rejected: false` 保证消息进死信，而不是重回队首无限循环。

---

## 6. 实验四（设计实验 C）：消费者离线，消息堆积不丢

### 操作（对设计文档顺序的修正）

设计文档的原顺序是：停 UserModule → 投 5 条 → 停 AuthModule → 启动 UserModule → 再启 AuthModule。第 4 步存在一个问题：消费者一上线就会立刻取消息并调用 Feign，而此时 AuthModule 还没启动，5 条消息会重试后进死信，而不是"等着"。本次按下面的顺序实测：

1. 停 UserModule（AuthModule 保持运行）。
2. 触发 5 次 `/auth/demo/login-event?userId=1`。
3. 检查主队列深度。
4. 启动 UserModule，检查队列深度与观察端点。
5. 停两个应用，再单独启动 UserModule（AuthModule 下线），验证 Feign 客户端不阻塞启动。

### 实测

- 5 次触发全部返回 `code=1`，traceId 分别为 `f06c9cf8...`、`58489e1d...`、`990beda1...`、`b7238e05...`、`26f62ce1...`。
- 管理 API 的 `messages` 统计有延迟，立即查询显示 0；随后查询为：

```json
{"name":"user.login.event.queue","messages":5,"messages_ready":5,"messages_unacknowledged":0,"consumers":0}
```

- 启动 UserModule 后，5 条全部被消费，`/user/mq/login/recent` 返回 5 条记录，日志中 5 次 `roleId=0 phone=null`，主队列 `messages=0`。
- 单独启动 UserModule（AuthModule 不在线）：

```text
FeignClientFactoryBean : For 'AuthModuleServer' URL not provided. Will try picking an instance via load-balancing.
Started UserModuleApplication in 7.842 seconds
```

启动成功，没有因目标服务不存在而失败。

### 结论

- 消费者离线不会丢消息；队列承担了缓冲，消费者恢复后按序补处理。
- 但是"消费者在线、依赖不在线"与"消费者离线"是两件事：前者会重试/死信，后者才会堆积。设计文档实验 C 的原顺序把两者混在了一起，这里明确修正。
- Feign 客户端只在调用时解析服务，启动阶段不会校验。

---

## 7. 自动化验证

### 全量回归

```bash
mvn -o -pl AuthModule,UserModule -am test \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

结果：

| 模块 | 用例数 | 失败 |
|---|---|---|
| AuthModule | 52 | 0 |
| UserModule | 12 | 0 |

### `LoginEventFlowE2ETest` 的断言

- 发送的类是本服务测试目录下的 `ForeignLoginEvent`，`__TypeId__` 指向它，而不是 `LoginEvent`。
- 消费端仍成功反序列化并按字段名映射，证明 `TypePrecedence.INFERRED` 生效。
- Feign 用 `@MockBean` 替身，因此该测试不依赖 AuthModule 在线，只验证 MQ 与反序列化。

### 测试隔离的两个坑

1. E2E 与 `RabbitMqDemoTest` 都是完整 `@SpringBootTest`，Spring TestContext 会缓存上下文。两个上下文同时监听同一批队列时，消息可能被另一个上下文的消费者取走，表现为 `RabbitMqDemoTest` 偶发超时。解决：两个测试类都加 `@DirtiesContext(AFTER_CLASS)`，让上下文在类结束后关闭。
2. 关闭上下文后再启动新上下文时，Seata 的 `GlobalTransactionScanner` 会引用已关闭的 `GenericWebApplicationContext`，导致 `usersMapper` 创建失败。MQ 测试不需要 Seata，因此两个测试类都加 `@SpringBootTest(properties = "seata.enabled=false")`。

---

## 8. 与设计/计划的差异记录

| 差异 | 原因 | 落点 |
|---|---|---|
| `SecurityConfig` 增加 `/auth/demo/**` permitAll | 否则 `anyRequest().authenticated()` 让演示端点 401 | Task 7 |
| `INFERRED` 提前到 Task 7 前完成 | 不改则消费端按生产端 `__TypeId__` 找不到类，Task 7 无法验收 | 提交 `29f4312` |
| `InternalUserController` 增加 `produces=application/json` | classpath 有 Jackson XML，无 Accept 时会把 VO 序列化成 XML | Task 8 |
| 新增 `LoginRetryConfig` | `AmqpRejectAndDontRequeueException` 默认仍会被 Spring Retry 重试 | Task 11 |
| 实验 C 调整启动顺序 | 原顺序会让在线消费者把堆积消息重试进死信，测不到"等待" | 本文档 §6 |
| 重试耗时 2s/4s，总计约 6s | `max-attempts=3` 只有两次退避；文档原写的 2/4/8 是 4 次尝试 | 本文档 §5 |
| 死信队列深度观察为 0 | 死信消费者在线，消息被立即消费并打日志；要看"到达过"应看日志或 `message_stats` | Task 11 |
| `RabbitMqDemoTest` 增加测试隔离注解 | 与 E2E 上下文争抢队列；Seata 静态状态在上下文关闭后报错 | Task 12 |

---

## 9. 清理与复现

实验结束后已执行：

- Feign 服务名恢复为 `AuthModuleServer`，`git diff` 为空。
- UserModule 重新打包，避免残留 `AuthModuleServerX` 的 jar。
- 两个应用停止，8101/8103 端口释放。
- `user.login.event.queue` 与 `user.login.event.dead.queue` 已清空。

复现顺序建议：

1. 确认 Nacos/MySQL/Redis/RabbitMQ 在线。
2. `mvn -o -pl AuthModule,UserModule -am -DskipTests package`。
3. 用 JDK 21 启动两个 jar。
4. 正常链路：`/auth/demo/login-event?userId=1` → `/user/mq/login/recent`。
5. 404 死信：用 §4 的 curl 直接投 `userId=999999`，看 `[登录死信]`。
6. 下游故障：临时改 Feign 服务名 → 重启 UserModule → 触发 → 观察 3 次尝试与死信；**结束后务必改回并重新打包**。
7. 堆积：停 UserModule → 触发 5 次 → 看主队列深度 → 启动 UserModule → 深度归 0。

---

## 10. 用实测回答"什么时候用哪个"

| 问题 | 实测答案 |
|---|---|
| 登录主链路要不要等下游？ | 不要。实验三中 Feign 目标不存在时登录端点仍 200；消息和重试都在 UserModule 内部完成。 |
| 需要角色/手机号才能继续处理怎么办？ | 用 Feign。消息里没有这些字段，消费端必须同步拿到答案才能组装 `ProcessedLogin`。 |
| 下游挂了消息会丢吗？ | 消费者离线不会（实验四，深度 5→0）；消费者在线但依赖挂了会重试后进死信（实验三）。 |
| 调试成本差多少？ | Feign 一条 curl 直达；MQ 要看消费者日志、两个队列深度、`__TypeId__` 和重试配置。实验二/三的排查都依赖这些观察点。 |
| 契约哪里最容易坏？ | 两边手写的交换机名/路由键/字段名。本次 `INFERRED` 只解决了类型名，不解决字段改名；字段改名仍然是静默 null。 |
