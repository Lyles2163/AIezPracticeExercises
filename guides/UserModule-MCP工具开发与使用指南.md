# UserModule MCP 工具开发与使用指南

> 本指南说明如何在 UserModule 里新增一个 MCP 工具、应该把代码写在哪一层、以及怎么通过 AIModule 验证它真的被模型调用了。
> 项目使用 Spring Boot 3.2.4 + Spring AI 1.0.0-M7，下面的配置和 API 都按这个版本写。

---

## 一、先看懂分层：工具类只是"适配层"

新增 MCP 工具时，**不要**把业务逻辑塞进工具类，也不要让工具类直接调 Mapper。正确的职责链是：

```text
MCP 工具类（XxxTools）        把 MCP 参数 → 业务调用 → 模型能读懂的返回值
        ↓
Service 层（XxxService）       业务规则、权限、状态校验
        ↓
Mapper / Repository           只负责数据库访问
```

各层职责对照：

| 位置 | 职责 | 在 MCP 里的角色 |
|------|------|----------------|
| `mcp_server/XxxTools.java` | 定义 AI 能调用的工具、参数说明，把结果转成模型友好的结构 | 适配层 |
| `service/XxxService.java` | 业务逻辑、校验、事务 | 复用现有代码 |
| `mapper/XxxMapper.java` | SQL / 数据库访问 | 不直接被工具类调用 |
| `mcp_server/McpServerToolConfig.java` | 把工具类注册成 `ToolCallbackProvider` | 注册表，不写业务 |
| `controller/XxxController.java` | 普通 HTTP 接口 | 与 MCP 并列的另一个入口 |

对应的目录结构：

```text
UserModule/src/main/java/org/leon/usermodule
├── controller
│   └── UserController.java
├── service
│   ├── UsersService.java
│   └── impl
│       └── UsersServiceImpl.java
├── mapper
│   └── UsersMapper.java
└── mcp_server
    ├── McpServerToolConfig.java   ← 注册（只改这里做登记）
    ├── OrderTools.java            ← 已有示例
    └── UserTools.java             ← 你要新增的
```

---

## 二、新增一个工具的完整流程

下面以「根据用户 ID 查询用户信息」为例，完整走一遍。代码用的都是本项目真实的 `UsersService` / `Users`。

### 第一步：业务逻辑放在 Service

现有 Service：

```java
public interface UsersService extends IService<Users> {

    List<Users> selectAll();

    void deleteById(Integer id);
}
```

需要"按 ID 查用户"就给接口加一个方法：

```java
public interface UsersService extends IService<Users> {

    List<Users> selectAll();

    void deleteById(Integer id);

    Users findById(Long id);
}
```

实现放在 `UsersServiceImpl`：

```java
@Override
public Users findById(Long id) {
    return getById(id);
}
```

> 如果 Service 里已经有能用的方法（比如 `selectAll()`、MyBatis-Plus 自带的 `getById`、`lambdaQuery()`），直接复用，不要为了工具再写一遍。

### 第二步：写工具类，注入 Service

新建 `UserModule/src/main/java/org/leon/usermodule/mcp_server/UserTools.java`：

```java
package org.leon.usermodule.mcp_server;

import org.leon.usermodule.pojo.Users;
import org.leon.usermodule.service.UsersService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class UserTools {

    private final UsersService usersService;

    public UserTools(UsersService usersService) {
        this.usersService = usersService;
    }

    @Tool(name = "get_user_by_id", description = "根据用户 ID 查询用户基本信息（用户名、账号状态）")
    public UserInfo getUserById(
            @ToolParam(description = "用户 ID，例如 1") Long userId) {

        Users user = usersService.findById(userId);
        if (user == null) {
            return new UserInfo(userId, null, null);
        }
        // 注意：password_hash 这类敏感字段不能返回给模型
        return new UserInfo(user.getId(), user.getUsername(), statusText(user.getStatus()));
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "禁用";
            case 1 -> "正常";
            case 2 -> "冻结";
            case 3 -> "已注销";
            default -> "未知";
        };
    }

    public record UserInfo(Long userId, String username, String status) {
    }
}
```

要点：

- `@Component` 让它成为 Bean，`UsersService` 用**构造器注入**，不要 `new`，也不要 `@Autowired` 字段注入。
- 工具方法只做三件事：参数校验 → 调 Service → 转成模型能读懂的返回值。
- 返回值用 `record` / 简单 DTO / `String`，别直接返回数据库实体（`Users` 里带着 `password_hash`）。

### 第三步：在 `McpServerToolConfig` 里登记

当前配置只注册了 `OrderTools`：

```java
@Bean
public ToolCallbackProvider orderToolCallbackProvider(OrderTools orderTools) {
    return MethodToolCallbackProvider.builder()
            .toolObjects(orderTools)
            .build();
}
```

加上 `UserTools` 后：

```java
@Bean
public ToolCallbackProvider orderToolCallbackProvider(
        OrderTools orderTools,
        UserTools userTools) {

    return MethodToolCallbackProvider.builder()
            .toolObjects(orderTools, userTools)
            .build();
}
```

这一步**只加名字，不加业务代码**。多个工具类共用一个 Provider Bean 即可，方法多了也不需要新建 Bean：

```text
McpServerToolConfig（注册表）
└── ToolCallbackProvider
        ├── OrderTools.getOrderStatus
        └── UserTools.getUserById
```

### 第四步：编译并重启 UserModule

```text
1. mvn compile（或 IDEA 直接重启）
2. 重启 UserModule，确认 8101 端口起来了
3. 重启 AIModule，让 MCP 客户端重新拉取工具列表
```

工具列表是在 MCP 客户端初始化 / 注册回调时读取的，**只重启服务端不重启客户端，模型看到的还是旧工具**。

### 第五步：验证真的连通了

```http
GET http://localhost:8105/agent?message=帮我查一下用户 1 的信息
```

判定标准：

- AIModule 启动日志里没有 `Server does not provide tools capability`、没有连接异常。
- 模型回答的内容确实来自数据库（不是你本地 `OrderTools` 的假数据）。

> AIModule 有降级逻辑：MCP 不可用时会退回进程内的本地 `OrderTools`。所以「AIModule 启动成功」**不等于**「MCP 通了」，必须实际问一句验证结果来源。

---

## 三、工具类写法要点

1. **必须是 Spring Bean。**`@Component` 是必须的，否则 `MethodToolCallbackProvider` 拿不到实例。
2. **必须登记进 Provider。**只加 `@Component` 不加进 `toolObjects(...)`，MCP Server 不会发布这个工具——这是最容易踩的坑。
3. **工具方法要薄。**校验 → 调 Service → 组装返回值，别在工具里写 SQL、算业务规则。
4. **工具名全局唯一。**建议显式写 `@Tool(name = "...", description = "...")`，用稳定英文名（如 `get_user_by_id`），不要依赖默认方法名撞车。
5. **参数说明要写给模型看。**`@ToolParam(description = "用户 ID，例如 1")` 讲清含义和格式，模型传参准确率会明显提高。
6. **返回值要对模型友好。**`String`、`record`、简单 DTO 都可以；不要返回连接、`Request`、带敏感字段的实体。
7. **模型给的参数不可信。**工具入参来自大模型，仍然要做边界检查；涉及权限的数据必须以服务端身份为准。
8. **异常要可读。**抛异常会让模型收到报错文本，建议在工具里捕获业务异常并返回结构化信息（如 `new UserInfo(id, null, "用户不存在")`）。
9. **有副作用的工具要慎重。**删除、修改、扣款类操作不要暴露给模型，除非有确认机制和审计。

---

## 四、不推荐的写法（以及原因）

```java
// ❌ 工具类直接调 Mapper，绕过 Service
@Component
public class UserTools {

    @Autowired
    private UsersMapper usersMapper;

    @Tool(description = "查询用户")
    public Users getUser(Long id) {
        return usersMapper.selectById(id);
    }
}
```

问题：

| 问题 | 说明 |
|------|------|
| 业务规则无法复用 | Controller 走 Service，MCP 走 Mapper，两套逻辑迟早不一致 |
| 校验/权限容易漏 | 状态判断、权限过滤写在 Service 里，绕过后直接缺失 |
| 敏感字段泄露 | 直接返回实体会把 `password_hash` 等字段交给模型 |
| 事务失效 | 复杂写操作需要 Service 层事务边界 |
| 维护成本 | 表结构变更要改两处 |

正确做法始终是：**`XxxTools` → `XxxService` → `XxxMapper`**。

---

## 五、现有示例参考

`OrderTools` 是最小的工具类样板：

```java
@Component
public class OrderTools {

    @Tool(description = "根据订单号查询订单状态和物流信息")
    public String getOrderStatus(@ToolParam(description = "订单号，格式如A88888") String orderId) {
        return ORDERS.getOrDefault(orderId, "订单号不存在");
    }
}
```

它当前用的是进程内假数据；要接真实业务时，把它改成注入 `XxxService` 即可，`McpServerToolConfig` 不用动（工具名不变时）。

---

## 六、配置说明

### UserModule（MCP Server）

`UserModule/src/main/resources/config/user_mcp.yaml` 配置服务器名称与传输：

- HTTP SSE 端点默认 `/sse`，服务端口 `8101`。
- 本项目固定 Spring AI `1.0.0-M7`，**只支持 SSE 与 stdio**，没有 `streamable-http`（照搬新版本文档会静默失效）。

### AIModule（MCP Client）

`AIModule/src/main/resources/config/mcp_server.yaml`：

| 配置项 | 作用 |
|--------|------|
| `spring.ai.mcp.client.enabled: true` | 开启 MCP 客户端 |
| `spring.ai.mcp.client.toolcallback.enabled: true` | 打开工具回调（M7 起默认关闭，必须显式打开） |
| `spring.ai.mcp.client.sse.connections.order-server.url` | 指向 UserModule 的 base URL，当前 `http://localhost:8101` |

---

## 七、启动顺序与验证

### 7.1 启动顺序（不能反）

1. 启动 Nacos 及 UserModule 依赖的中间件。
2. 启动 **UserModule**（8101），确认端口已监听。
3. 启动 **AIModule**（8105），确认 MCP 客户端初始化成功。

AIModule 默认会**在启动时就连接** `/sse` 并拉取工具列表；UserModule 没起来时，AIModule 可能直接启动失败。

### 7.2 调用验证

```http
GET http://localhost:8105/agent?message=查询订单A88888的状态
```

新增用户工具后换成：

```http
GET http://localhost:8105/agent?message=帮我查一下用户 1 的账号状态
```

### 7.3 验证清单

- [ ] UserModule 启动成功，8101 端口有新的进程（不是旧实例占着）
- [ ] AIModule MCP 客户端初始化成功，无 `Server does not provide tools capability`
- [ ] 工具类是 Spring Bean，且已加入某个 `ToolCallbackProvider` 的 `toolObjects(...)`
- [ ] AIModule 的 MCP URL 指向正确的 UserModule 地址
- [ ] 实际发问验证结果来自真实业务数据
- [ ] 改完 UserModule 后重启，再重启 AIModule

---

## 八、常见问题

### 加了 `@Component` 和 `@Tool`，但模型看不到工具

99% 是忘了登记：检查工具对象有没有加入 `MethodToolCallbackProvider.builder().toolObjects(...)`。`@Component` 只负责创建 Bean，不会自动变成 MCP 工具。

### 报 `No qualifying bean of type 'ToolCallbackProvider'`

- AIModule 是否显式配置 `spring.ai.mcp.client.toolcallback.enabled: true`；
- SSE connection 的 `url` 是否配了；
- UserModule 是否先启动。

### 报 `Server does not provide tools capability`

说明客户端连上了某个 MCP Server，但那个 Server 一个工具都没发布。检查：

- `McpServerToolConfig` 是否创建了 `ToolCallbackProvider`；
- `toolObjects(...)` 里是否至少有一个带 `@Tool` 方法的对象；
- 运行的是不是**重新编译并重启后**的 UserModule。

### 加了工具，模型仍然只会调旧工具

先重启 UserModule，再重启 AIModule。客户端工具列表是初始化时拉的，不重启不会刷新。

### 两个工具重名

给每个方法显式写唯一的 `@Tool(name = "...")`。重名会在构建 callback 时报 `Multiple tools with the same name`。

### 工具被调用了但报错 / 返回空

按 `Tools → Service → Mapper` 顺序排查：先看工具方法入参是否符合预期，再看 Service 查询条件，最后看数据库里到底有没有数据。

---

## 九、速查清单

```text
1. Service 层准备好方法（已有就直接复用）
2. 新建 UserModule/.../mcp_server/XxxTools.java
3. 标 @Component，构造器注入 XxxService
4. 公开方法标 @Tool(name=..., description=...)，参数标 @ToolParam
5. 返回值用 record / DTO / String，去掉敏感字段
6. 在 McpServerToolConfig 的 Provider Bean 参数里注入 XxxTools
7. 把 XxxTools 加进 toolObjects(...)
8. 编译 → 重启 UserModule → 重启 AIModule
9. 用 /agent 发一个针对性的问题验证
```

一句话原则：**Config 管登记，Tools 管适配，Service 管业务，Mapper 管取数。**
