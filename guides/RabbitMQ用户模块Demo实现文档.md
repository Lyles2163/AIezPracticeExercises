# RabbitMQ 入门：UserModule 消息队列 Demo 实现文档

日期：2026-10-12　分支：`dev`　模块：`UserModule`

> 文中每个结论都标了来源：
> - **已核实** = 我在你这台机器上实际跑命令验证过，或从反编译/依赖包里读出来的。
> - **官方文档** = 摘自 rabbitmq.com 当前文档。
> - **未验证** = 需要本机装好 RabbitMQ 服务端后才能确认，我不会假装已经跑通。（本文写完当天服务端已装好，原先标"未验证"的条目已逐条改为实测结果，见第 5.3、6 节。）

---

## 0. 这次到底做了什么（30 秒版）

给 `UserModule` 接上 RabbitMQ，用「**用户注册后要发邮件、发短信**」这个场景，做了 1 个 Topic 交换机 + 2 个队列 + 2 个消费者，外加：

- 一个 JUnit 测试方法（`RabbitMqDemoTest`）验证真实收发；
- 两个 HTTP 接口（`/user/mq/register`、`/user/mq/no-binding`），让你在浏览器里手动发一条消息看链路。

**当前状态**：**全链路已实测通过。** RabbitMQ 4.3.6 + Erlang OTP 27.3.4 已装在本机 F 盘（第 5.3 节就是我实际执行的那遍命令），`RabbitMqDemoTest` 两个测试方法**真跑过并全绿**（`Tests run: 2, Failures: 0, Errors: 0`，日志里能看到 `Created new connection: ... amqp://guest@127.0.0.1:5672/`）。你直接从第 6 节开始跟着做就行，不需要再装任何东西。

---

## 1. 为什么需要消息队列

### 1.1 不用 MQ 会怎样

注册用户时，除了往 `users_dev` 表插一行，还要发邮件、发短信。最朴素的写法是：

```java
usersMapper.insert(user);
emailService.sendWelcome(user);   // 调用邮件服务，可能 800ms
smsService.sendNotice(user);      // 调用短信服务，可能 500ms
```

三个问题：

1. **耦合**：`UserModule` 必须知道"邮件服务是谁、短信服务是谁"。以后再加一个"注册送优惠券"，就得回来改这段代码。
2. **慢**：注册接口的耗时 = 插入 + 邮件 + 短信 = 1.3 秒。用户只是注册一下，凭什么是他买单？
3. **连坐**：短信服务挂了，注册接口直接报错。可短信发不出去，本来不该让注册失败。

### 1.2 用 MQ 之后

```java
usersMapper.insert(user);
producer.send(message, "user.create.email");  // 0.5ms，丢给 MQ 就走人
producer.send(message, "user.create.sms");
```

`UserModule` 只喊一嗓子"有人注册了"，剩下谁处理、处理几次、处理失败要不要重试，都跟它无关。于是拿到 MQ 的三大作用，在本 demo 里都能对上：

| 作用 | 大白话 | 本 demo 的体现 |
|---|---|---|
| **解耦** | 发消息的不认识收消息的 | 生产者里没有任何"邮件服务""短信服务"的引用，只有交换机名和路由键 |
| **异步** | 不该我等的我不等 | `convertAndSend` 立刻返回，消费者在别的线程慢慢处理 |
| **削峰** | 先排队，再按节奏消费 | 消息先躺在队列里；消费者一次取几条由 `prefetch` 控制（本 demo 没配，用默认） |

**一句话记住 MQ**：它不是"更快的调用"，它是"把调用变成寄存"。你放到队列里就完事，什么时候被处理由别人决定。

---

## 2. 五个名词，直接对应到本项目的文件

| 名词 | 大白话 | 在本 demo 里是什么 |
|---|---|---|
| **Broker** | RabbitMQ 服务端本身，存消息的进程 | 你本机 `127.0.0.1:5672`（已装好，装在 `F:\RabbitMQ\rabbitmq_server-4.3.6`，服务运行中） |
| **Message** | 传的东西 | `pojo/mq/UserRegisterMessage.java` |
| **Producer** | 扔消息的一方 | `mq/UserRegisterProducer.java` |
| **Exchange** | 分发台。消息先到这里，它决定进哪些队列 | `demo.user.exchange`（Topic 类型） |
| **Binding** | 分发台上的"规则"：路由键长什么样 → 投递到哪个队列 | `config/RabbitMqDemoConfig.java` 里的两个 `Binding` Bean |
| **Queue** | 真正的信箱，消息存在这 | `demo.user.email.queue`、`demo.user.sms.queue` |
| **Consumer** | 从信箱取信的人 | `mq/UserRegisterConsumer.java` 的两个 `@RabbitListener` 方法 |

拓扑图（箭头是消息流向，`⇢` 表示 Binding 规则）：

```
                          routingKey = "user.create.email"
UserRegisterProducer ─────────────────────────────────────┐
                                                          ▼
                                          ┌───────────────────────────────┐
                                          │ TopicExchange                 │
                                          │  demo.user.exchange           │
                                          └───────────┬───────────┬───────┘
                        ⇢ binding "user.create.email" │           │ ⇢ binding "user.*.sms"
                                                      ▼           ▼
                                    ┌───────────────────────┐ ┌──────────────────────┐
                                    │ demo.user.email.queue │ │ demo.user.sms.queue  │
                                    └───────────┬───────────┘ └──────────┬───────────┘
                                                ▼                        ▼
                                    onEmailQueue(...) 日志          onSmsQueue(...) 日志
```

`*` 是 Topic 交换机的通配符，含义是"**恰好一个单词**"（单词以 `.` 分隔）。所以 `user.*.sms` 能命中 `user.create.sms`，但不能命中 `user.create.x.sms`。另一个通配符 `#` 表示"零个或多个单词"，本 demo 没用。

**为什么邮件队列用精确键、短信队列用通配符？** 这样一份代码同时演示两种匹配方式，你不用再看第二个 demo。

---

## 3. 一条消息的完整旅程

以 `GET /user/mq/register?username=leon` 为例：

1. Tomcat 收到请求，进 `MqDemoController.register()`。
2. 组装 `UserRegisterMessage(10086L, "leon", now)`。
3. 调 `producer.send(msg, "user.create.email")`。
4. `RabbitTemplate` 用 `Jackson2JsonMessageConverter` 把对象序列化成 JSON 字节，并在消息头写入 `__TypeId__ = org.leon.usermodule.pojo.mq.UserRegisterMessage`（**已核实**：该常量就是 `DefaultClassMapper.DEFAULT_CLASSID_FIELD_NAME`）。
5. 通过 TCP 长连接（AMQP 0-9-1 协议）发给 Broker 的 5672 端口，指定"投到 `demo.user.exchange`，路由键 `user.create.email`"。
6. Exchange 查自己的 Binding 表：`user.create.email` 精确命中邮件队列，**不**命中 `user.*.sms` → 只往 `demo.user.email.queue` 放一份。
7. 第二个 `send(msg, "user.create.sms")`：`user.create.sms` 命中通配符 `user.*.sms` → 放进 `demo.user.sms.queue`。
8. HTTP 请求早就返回了——**第 6、7 步之后生产者就不管了**。
9. 消费端有 2 个监听容器线程（每个队列一个）一直阻塞在"取消息"上。队列里一旦出现消息，容器线程取出 → 按 `__TypeId__` 把 JSON 反序列化回 `UserRegisterMessage` → 反射调用你的 `@RabbitListener` 方法。
10. 方法正常返回后，客户端向 Broker 发 **ack**（确认），Broker 把这条消息从队列删掉。默认是"自动确认"：方法不抛异常就 ack，抛异常就 requeue 重来。

> 第 9 步是关键：**消费者方法不是你的代码调用的，是 Spring 的容器线程调用的**，所以你永远不知道它什么时候执行。这就是第 4.8 节测试为什么要"等"。

---

## 4. 逐个文件讲实现

### 4.1 `UserModule/pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

不写版本号，由父工程 `spring-boot-starter-parent:3.2.4` 统一管理。**已核实**实际解析到：`spring-rabbit 3.1.3` + `spring-amqp 3.1.3` + `amqp-client 5.19.0`。

**为什么这么做**：RabbitMQ 官方只提供 Java 客户端 `amqp-client`，它是"裸 API"——你得自己建连接、信道、声明队列、写重试。Spring AMQP 在这之上做了三件事：连接自动重连、`RabbitTemplate` 一行代码发消息、`@RabbitListener` 把方法变成消费者。**被否方案**：直接依赖 `com.rabbitmq:amqp-client`，代码量翻 3 倍以上，且学不到 Spring 项目里真正的写法。

### 4.2 `UserModule/src/main/resources/application.yaml`

```yaml
spring:
  rabbitmq:
    host: 127.0.0.1
    port: 5672
    username: guest
    password: guest
    virtual-host: /
```

- `5672` 是 AMQP 协议端口（**官方文档**：管理界面是另一个端口 `15672`）。
- `virtual-host: /` 是默认虚拟主机，相当于"命名空间"，一个 Broker 可以切多个互不相干的 vhost，本机 demo 用默认的就行。
- `guest/guest` 是出厂账号。**官方文档**：`这些凭据默认只能在以 localhost 身份连接时使用`——也就是说你从别的机器连会被 `ACCESS_REFUSED`。本机开发不受影响；将来上服务器要建自己的账号。

**为什么配置放本地 yaml 而不放 Nacos**：你现在的 yaml 里 `spring.config.import` 导入了 `UserModuleServer.yaml`（optional）和 `common-jwt.yaml`，Nacos 的同名 key 会**覆盖**本地值。学习阶段把 `spring.rabbitmq` 写在本地，改一行重启就生效，不用登 Nacos 控制台改配置再刷新，排查链路更短。生产化时迁到 Nacos 是正确做法，那时候这段就该删。

### 4.3 `config/RabbitMqDemoConfig.java`

这个类是整个 demo 里"最不像代码但最重要"的文件——它声明了拓扑结构：

```java
@Bean public TopicExchange userExchange() { return new TopicExchange(USER_EXCHANGE, true, false); }
@Bean public Queue emailQueue() { return new Queue(EMAIL_QUEUE, true); }
@Bean public Binding emailBinding(Queue emailQueue, TopicExchange userExchange) {
    return BindingBuilder.bind(emailQueue).to(userExchange).with(EMAIL_ROUTING_KEY);
}
```

**为什么队列是代码声明的，不是去管理界面手点？**
应用启动时 Spring AMQP 会建一个 `RabbitAdmin`，它监听连接事件，把容器里所有 `Exchange`/`Queue`/`Binding` Bean 自动声明到 Broker 上。声明是**幂等**的：已存在且参数一致就什么都不做。好处是——换台机器、重装 Broker、给同事跑，不需要任何"手工前置步骤"。**被否方案**：管理界面手建队列。手建的东西不在代码里，等于配置漂移，是生产事故的经典来源。

`TopicExchange(name, durable=true, autoDelete=false)`、`Queue(name, durable=true)`：durable 表示元数据在 Broker 重启后还在。注意 **durable 队列 ≠ 消息不丢**，消息是否落盘取决于发送时的 delivery mode（Spring 默认 2 = 持久化），彻底不丢还要配合 confirm、镜像/quorum 队列等，超出 demo 范围。

⚠️ **改了队列/交换机类型会启动失败**：如果你把交换机从 Topic 改成 Fanout，但旧交换机还在 Broker 上，会报 `PRECONDITION_FAILED - inequivalent arg 'type'`。demo 阶段的解法就是去管理界面把旧的 `demo.user.exchange` 删掉再启动（见第 7 节）。

**消息转换器：**

```java
@Bean
public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
    return new Jackson2JsonMessageConverter(objectMapper);
}
```

两个细节都是有意为之：

1. **为什么换成 JSON**：Spring AMQP 默认用 JDK 序列化，消息体是一串二进制，管理界面里你只会看到乱码；而且要求 POJO 实现 `Serializable`、`__TypeId__` 写死全限定类名，跨服务/改包名立刻炸。换成 JSON 后，你在管理界面 `Get Messages` 能直接读出人类可读的 `{"userId":10086,"username":"leon",...}`——对学习者来说，**能看见消息长什么样**非常重要。
2. **为什么注入 Spring 的 `ObjectMapper` 而不是 `new Jackson2JsonMessageConverter()`**：无参构造会自己 new 一个 ObjectMapper，而 Spring Boot 那个是 `Jackson2ObjectMapperBuilder` 建的，它注册了 `jackson-datatype-jsr310` 并关掉了时间戳序列化。我们的消息里有 `LocalDateTime`，用无参的那个会序列化失败或变成数组。

### 4.4 `pojo/mq/UserRegisterMessage.java`

```java
@Data @NoArgsConstructor @AllArgsConstructor
public class UserRegisterMessage {
    private Long userId;
    private String username;
    private LocalDateTime registerTime;
}
```

**为什么必须有 `@NoArgsConstructor`**：反序列化时 Jackson 要先用无参构造 new 出对象再逐字段 set。只写 `@AllArgsConstructor` 会让测试里的收发直接失败，这是新手最容易踩的一个坑。

**为什么没有 `implements Serializable`**：Jackson 不需要它；留着只会让人误以为"发消息必须可序列化"。跨进程传的是 JSON 文本，不是 Java 对象字节流。

### 4.5 `mq/UserRegisterProducer.java`

```java
rabbitTemplate.convertAndSend(RabbitMqDemoConfig.USER_EXCHANGE, routingKey, message);
```

三个参数按顺序回答三个问题：**投给哪个分发台**、**按什么规则分发**、**投什么内容**。

`RabbitTemplate` 不是我们自己 new 的，是 Spring Boot 的 `RabbitAutoConfiguration` 根据 yaml 里的 `spring.rabbitmq.*` 自动创建的；上一步那个 `MessageConverter` Bean 也会被自动注入进去（Boot 检测到容器里只有一个 `MessageConverter` 时，会同时配给 `RabbitTemplate` 和监听容器工厂）。

**为什么用构造器注入而不是 `@Autowired` 字段注入**： final 字段可以在写代码时就确认依赖是否齐全，且单元测试能直接 `new UserRegisterProducer(mockTemplate)`。项目里现有的 `@Autowired` 写法能用，但字段注入一旦漏了依赖，要等到运行时调用那一刻才报 NPE。

### 4.6 `mq/UserRegisterConsumer.java`

```java
@RabbitListener(queues = RabbitMqDemoConfig.EMAIL_QUEUE)
public void onEmailQueue(UserRegisterMessage message) { ... }
```

`@RabbitListener` 的作用：Spring 扫描到它，就为 `demo.user.email.queue` 起一个监听容器线程，循环"取消息 → 转对象 → 反射调用这个方法"。方法签名里直接写 POJO，不需要你处理 `Message` 原始对象。

类里那两个 `BlockingQueue` 是**给测试用的观察点**，不是业务逻辑：

```java
private final BlockingQueue<UserRegisterMessage> emailReceived = new LinkedBlockingQueue<>();
```

消费者收到消息时顺手 `offer()` 进去，测试线程用 `poll(5, SECONDS)` 等在那里。这样测试才拿得到"异步线程实际收到了什么"。**如果不想污染生产类**，进阶做法是测试里用 `@MockBean` 或注册一个自定义 `AfterReceivePostProcessor`——demo 阶段先要能看懂，不追求优雅，我选了最直白的一种。

### 4.7 `controller/MqDemoController.java`

```java
@GetMapping("/register")     // 正常命中两个队列
@GetMapping("/no-binding")   // 路由键 user.delete.phone，没人绑定 → 被 Broker 丢弃
```

第二个接口是专门留的**反面教材**：让你亲眼看到"路由键匹配不到任何 Binding 时，消息不会报错，也不会存到别处，就是消失了"。这是交换机最基本的行为，也是"消息莫名丢失"这类线上问题的根因之一（生产环境的对策是备用队列 / `publisher returns` 回调，本 demo 不做）。

### 4.8 `src/test/java/.../RabbitMqDemoTest.java`

```java
producer.send(message, RabbitMqDemoConfig.EMAIL_ROUTING_KEY);
UserRegisterMessage emailResult = consumer.pollEmailMessage(5, TimeUnit.SECONDS);
assertNotNull(emailResult, "5 秒内邮件消费者没收到：检查 RabbitMQ 是否启动、路由键是否匹配");
```

**这是整个 demo 里最值得学的一段。** 异步代码的测试铁律：

- ❌ 错误写法：`send(); assertNotNull(consumer.getLastMessage())` —— 发送方法返回时，消费线程**很可能还没执行**，断言必挂。这种"偶尔过偶尔挂"的测试比没测试更糟。
- ✅ 正确写法：拿一个能"阻塞等待"的东西（这里是 `BlockingQueue.poll(timeout)`），**给异步结果一个时间上限**，超时就失败。

第二个测试 `messageWithUnboundRoutingKeyIsDropped` 用 `assertNull` 断言"2 秒内不该收到"。注意这是**证明不了永远收不到**的，只能证明没误投递——所以超时设 2 秒而不是 10 秒，别把测试跑成罚站。

`assertEquals(message.getRegisterTime().withNano(0), ...)`：JSON 传输保留到毫秒，两边都抹掉纳秒再比，避免浮点精度带来的假失败。

**跑测试的前提**：`@SpringBootTest` 会拉起整个 UserModule 上下文，所以 MySQL(3306)、Nacos(8848)、Seata(8091) 都要在。已核实这三个端口在你机器上都在监听。

---

## 5. 先把 RabbitMQ 服务端装上（Windows）

### 5.1 版本搭配（已核实，别乱选）

官方 `which-erlang` 兼容矩阵：

| RabbitMQ | 最低 Erlang/OTP | 最高 Erlang/OTP |
|---|---|---|
| 4.3.6 | 27.0 | 28.x |
| 4.2.x ~ 4.3.3 | 27.0 | 27.x |
| 4.2.0 ~ 4.3.0 | 26.2 | 27.x |

官方提示 **OTP 28 只支持全新集群**。所以本项目建议：**Erlang OTP 27.3.4 + RabbitMQ 4.3.6**。
（Spring 这边：`amqp-client 5.19.0` 走的是 AMQP 0-9-1，RabbitMQ 4.x 仍然完整支持，不用担心中间断代。）

### 5.2 下载（已实测你这台机器的网络情况）

**已核实**：你机器直连 `github.com` / `api.github.com` 全部超时，但 `www.erlang.org`、`mirrors.huaweicloud.com` 正常。所以别按教程去 GitHub 下，直接用下面这些链接（我用 HEAD 请求确认过文件真实存在及大小）：

| 装什么 | 地址 | 大小 |
|---|---|---|
| Erlang/OTP 27.3.4（Windows 64 位安装器） | `https://www.erlang.org/download/otp_win64_27.3.4.exe` | 139,166,392 B |
| RabbitMQ 4.3.6 Windows 安装器 | `https://mirrors.huaweicloud.com/rabbitmq-server/v4.3.6/rabbitmq-server-4.3.6.exe` | 23,956,232 B |
| RabbitMQ 4.3.6 免安装 zip（不想装服务就用它） | `https://mirrors.huaweicloud.com/rabbitmq-server/v4.3.6/rabbitmq-server-windows-4.3.6.zip` | 70,605,921 B |

### 5.3 本机实际执行的安装过程（已跑通，命令可直接复制）

上面是通用教程，下面是**你这台机器上真实跑过的一遍**。选的路径是「Erlang 用安装器 + RabbitMQ 用免安装 zip 手工注册服务」，全部落在 F 盘，路径无空格。

```powershell
# 0. 前提：以管理员身份打开 PowerShell（本机能拿到 SeTakeOwnership 级别即可）

# 1. 静默装 Erlang。NSIS 安装器的 /D= 必须是最后一个参数且不能加引号
Start-Process -FilePath 'F:\AAAJAVA\mq-installers\otp_win64_27.3.4.exe' `
              -ArgumentList '/S','/D=F:\erl-27.3.4' -Wait

# 2. 验证 Erlang（看到 Erlang/OTP 27 就成了；-noshell 让它不留在 REPL）
F:\erl-27.3.4\bin\erl.exe -eval "io:format(\"~s~n\",[erlang:system_info(otp_release)]), halt()." -noshell

# 3. 解压 RabbitMQ。zip 里同时有 rabbitmq_server-4.3.1 和 rabbitmq_server-4.3.6 两个目录，用 4.3.6
Expand-Archive -Path 'F:\AAAJAVA\mq-installers\rabbitmq-server-windows-4.3.6.zip' `
               -DestinationPath 'F:\RabbitMQ' -Force

# 4. 设机器级 ERLANG_HOME —— ⚠️ 别用 setx /M
#    Git Bash 会把 /M 当 Unix 路径转换，报"参数过多"。用 PowerShell 才稳：
[Environment]::SetEnvironmentVariable('ERLANG_HOME','F:\erl-27.3.4','Machine')

# 5. 注册 Windows 服务（数据目录也放 F 盘）
cd F:\RabbitMQ\rabbitmq_server-4.3.6\sbin
$env:ERLANG_HOME='F:\erl-27.3.4'; $env:RABBITMQ_BASE='F:\RabbitMQ\data'
.\rabbitmq-service.bat install

# 6. 启用管理插件
.\rabbitmq-plugins.bat enable rabbitmq_management

# 7. 插件是"离线变更"，必须重启服务才生效（这一步最容易忘）
net stop RabbitMQ ; net start RabbitMQ
```

第 5 步如果输出 `RabbitMQ service is already present - only updating service parameters`，说明这台机器上此前已注册过同名服务，它只更新参数，不是失败——继续第 6、7 步即可。`sbin` 目录里能看到的脚本（已核实）：`rabbitmq-service.bat`、`rabbitmq-plugins.bat`、`rabbitmqctl.bat`、`rabbitmq-diagnostics.bat`、`rabbitmq-server.bat`，Windows 版全部带 `.bat` 后缀。

### 5.4 验证装好了

```bash
netstat -ano | grep -E ":(5672|15672)" | grep LISTENING   # 两个端口都要在
```

我这边跑完的实际结果：`5672`、`15672`、`4369`(epmd)、`25672`(节点间通信) 四个端口全部 LISTENING。

浏览器打开 `http://localhost:15672`，用户名密码都是 `guest`。看到 Overview 页就成功了。

```bash
# 服务是否开机自启（本机实测 StartType = Automatic）
powershell -NoProfile -Command "Get-Service RabbitMQ | Format-List Name,Status,StartType"
```

### 5.5 顺带解决 Maven 下载源（已核实：你机器直连 Central 超时）

新建 `C:\Users\Administrator\.m2\settings.xml`（当前不存在，已核实）：

```xml
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <mirrorOf>central</mirrorOf>
      <name>aliyun public</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
```

这次新增依赖能装上，就是靠我临时用这个镜像拉的包。不配也行（包已经在本地仓库里），但 IntelliJ 刷新 Maven 或换机器时会重新下载。

---

## 6. 跑起来，看什么

### 6.1 跑测试

最省事：IntelliJ 打开 `RabbitMqDemoTest`，点绿色箭头。

命令行（**已核实**：你机器 `mvn` 不在 PATH，用已下载的 wrapper 发行版 + JDK 21；根 pom 要求 Java 21，`C:\Program Files\Java` 里的 jdk-17 编不过）：

```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
MVN="/c/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
cd F:/AAAJAVA/code/ezPracticeExercises
"$MVN" -pl UserModule -am test -Dtest=RabbitMqDemoTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.failIfNoTests=false
```

`-am` 会连带构建 `CommonJwt`；那两个 `-Dsurefire` 参数是必须的，否则 `CommonJwt` 里没有匹配的测试会让整个构建中止（这个项目踩过的坑）。

**成功长这样**：日志里出现

```
[生产者] 准备发送 routingKey=user.create.email 消息=UserRegisterMessage(...)
[邮件消费者] 收到消息，准备给 demoUser 发欢迎邮件，注册时间=...
```

注意 `[邮件消费者]` 那行的线程名不是 `main`，而是 `listenerSimpleContainer...` 之类——**这就是"异步"最直观的证据**。

### 6.2 手动接口

启动 `UserModuleApplication` 后，浏览器依次访问：

1. `http://localhost:8101/user/mq/register?username=leon`
   → 控制台应打出 **1 条 `[生产者]` + 1 条 `[邮件消费者]` + 1 条 `[短信消费者]`**。
2. `http://localhost:8101/user/mq/no-binding`
   → 只有 `[生产者]` 一行，**没有任何消费者日志**。消息被交换机丢弃了。

### 6.3 管理界面（`http://localhost:15672`）看这四处

| 位置 | 看什么 |
|---|---|
| Exchanges → `demo.user.exchange` → Binding 标签页 | 两条路由规则的表：routing key ↔ destination queue。**这是本文第 2 节那张图的真身** |
| Queues → `demo.user.email.queue` | `Ready` / `Unacked` / `Total` 三个数的含义：Ready=排队等人取的，Unacked=已发出但还没收到 ack 的，Total=两者之和 |
| 任一队列 → Get Messages | routing key 填 `user.create.email`，Ack 关掉，点 Get Messages → 能看到 **JSON 原文**，这就是 4.3 节换 Jackson 的收益 |
| Admin 标签页 | `Add a user`、`Virtual hosts` —— 了解 vhost 和权限是怎么划的（本机 demo 用不到） |

⚠️ 手动 Get Messages 会把消息**取走**，如果那时消费者还在等，这条消息就永远到不了消费者手里。取完记得别指望测试还成功。

---

## 7. 新手常见报错对照表

| 报错关键字 | 真实原因 | 怎么办 |
|---|---|---|
| `java.net.ConnectException: Connection refused: connect` | RabbitMQ 服务没起 | `services.msc` 里启动 `RabbitMQ`；`netstat` 确认 5672 |
| `ACCESS_REFUSED - Login was refused using authentication mechanism PLAIN` | guest 账号被非本机地址使用，或密码不对 | 本机开发保持 `host: 127.0.0.1`；远程就新建用户并授权 |
| `PRECONDITION_FAILED - inequivalent arg 'type' for exchange 'demo.user.exchange'` | 你在代码里改了交换机类型，但 Broker 上还是旧的 | 管理界面 Exchanges 里删掉 `demo.user.exchange` 和两个队列，重启应用（demo 阶段最省事；生产要走迁移） |
| `No qualifying bean of type 'RabbitTemplate'` | 依赖没生效 | Maven 面板 Reload；确认 `UserModule/pom.xml` 里有 starter |
| 测试 `assertNotNull` 失败但服务端在跑 | 消费者方法抛异常反复 requeue，或路由键拼错 | 看有没有 `Requeue rejected` / `Failed to convert` 日志；核对 `RabbitMqDemoConfig` 常量 |
| `Could not resolve type id 'org.leon...'` | 消息头里的类名和当前包名对不上（你改过包名/挪过模块） | 管理界面把队列里的旧消息清空（Purge），重跑 |
| 启动就失败，跟 MQ 无关 | `spring.config.import: nacos:common-jwt.yaml` 是非 optional 的，Nacos 没起就中断 | 起 Nacos(8848)，或临时改成 `optional:nacos:` |
| 编译报 `release version 21 not supported` | 用了 JDK 17 | `export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"` |

---

## 8. 本次改动清单与验证状态

| 文件 | 改了什么 | 状态 |
|---|---|---|
| `UserModule/pom.xml` | + `spring-boot-starter-amqp` | ✅ 依赖解析成功（已核实本地有 spring-rabbit 3.1.3 / amqp-client 5.19.0） |
| `UserModule/src/main/resources/application.yaml` | + `spring.rabbitmq` 五项 | ✅ 语法有效 |
| `pojo/mq/UserRegisterMessage.java` | 新增消息体 | ✅ 编译通过 |
| `config/RabbitMqDemoConfig.java` | 新增拓扑声明 + JSON 转换器 | ✅ 编译通过 |
| `mq/UserRegisterProducer.java` | 新增发送端 | ✅ 编译通过 |
| `mq/UserRegisterConsumer.java` | 新增两个消费者 | ✅ 编译通过 |
| `controller/MqDemoController.java` | 新增两个手动接口 | ✅ 编译通过 |
| `src/test/java/.../RabbitMqDemoTest.java` | 新增 2 个测试方法 | ✅ 已通过：`Tests run: 2, Failures: 0, Errors: 0`（真实 broker，非 mock） |

`mvn -pl UserModule -am -DskipTests test-compile` → BUILD SUCCESS（已核实）。

---

## 9. 下一步可以学什么（现在别动，先把 demo 跑通）

按对你工作的价值排序：

1. **手动 ack + 失败进死信队列（DLX）** —— 现在消费方法一抛异常就无限 requeue，会把 CPU 打满。真实业务必须"重试 3 次后进死信，人工看"。这是 MQ 用得对不对的分水岭。
2. **publisher confirm / return 回调** —— 让生产者知道消息到底进没进队列，第 6.2 节那个"被丢弃"的情况就能被程序发现而不是靠肉眼看日志。
3. **幂等消费** —— RabbitMQ 至少投递一次，重复消费是常态。业务侧要靠唯一键/状态机去重，不能指望 MQ 只发一次。
4. **延迟队列**（TTL + DLX，或 `rabbitmq_delayed_message_exchange` 插件） —— 典型场景"30 分钟未支付自动取消"。
5. **Spring Cloud Stream** —— 把 MQ 抽象成 binding 配置，换 Kafka/RocketMQ 不改代码。你们已是 Spring Cloud 技术栈，值得了解。

第 1、2 项和你项目里"注册成功要通知多方"的真实需求直接相关，想继续时告诉我，我们把它设计成一个正式方案（那时候就不是 demo 了，要走完整设计）。
