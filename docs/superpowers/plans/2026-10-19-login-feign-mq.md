# 登录场景 Feign + MQ 双链路 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 AuthModule 登录成功后通过 RabbitMQ 发布登录事件，UserModule 消费并通过 OpenFeign 反查用户角色/手机号，两条链路都能独立验收，且失败路径可见。

**Architecture:** AuthModule 生产事件（try/catch 保护登录主链路）+ 暴露带内部令牌校验的 Feign 接口；UserModule 声明消费端拓扑（含死信）、消费事件、用 Feign 反查补全资料、结果进内存环形队列供查询。失败分两档：404 立即进死信，其它异常重试 3 次后进死信。

**Tech Stack:** Java 21 / Spring Boot 3.2.4 / Spring Cloud Alibaba 2023.0.1.2 / Spring AMQP 3.1.3 / OpenFeign 4.1.1 / Nacos / RabbitMQ 4.3.6 / MyBatis-Plus / Lombok / JUnit 5 + Mockito。

**Spec:** `docs/superpowers/specs/2026-10-19-login-feign-mq-design.md`

## Global Constraints

- JAVA_HOME 必须是 `C:/Users/Administrator/.jdks/ms-21.0.11`（仓库 `C:\Program Files\Java` 只有 17，编译会失败）。
- `mvn` 不在 PATH，统一用下面这个路径，并且离线 `-o`：

```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
MVN="/c/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
```

- 用 `-pl <模块> -am` 构建；带 `-Dtest=` 过滤时必须同时加 `-Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`（`-am` 会把这个过滤带到 CommonJwt 上）。
- 不建表、不改表结构、不引入新依赖（openfeign / loadbalancer / nacos-discovery 已由父 pom 继承）。
- 包结构：`org.leon.<模块>.{config,controller,mq,pojo.mq,pojo.vo,feign}`。**不要动** `org.leon.usermodule.comsumer` 下除 `LoginListener.java` 以外的文件（`MqListener.java` 保留）。
- 日志、注释、`@DisplayName`、提交信息一律中文。
- 每个任务结束必须提交一次，且该任务范围内所有测试通过。
- MQ 组件用构造器注入；controller / service 沿用项目现有的 `@Autowired` 字段注入。

## 任务总览

| 任务 | 阶段 | 对应 spec 章节 |
|---|---|---|
| 1 | 清障 | §2(b) |
| 2 | MQ：事件 DTO + 交换机 | §5.2、§6.1 |
| 3 | MQ：生产者 | §6.1 |
| 4 | MQ：挂进登录流程 | §6.2 |
| 5 | MQ：消费端拓扑 | §5.1 |
| 6 | MQ：消费者（仅日志）+ 环形队列 | §7.3、§7.4、§7.6 |
| 7 | MQ：手动触发与观察端点 | §6.5、§7.6 |
| 8 | Feign：内部查询接口 | §6.3 |
| 9 | Feign：内部令牌校验 | §6.4 |
| 10 | Feign：客户端 + 反查 | §7.1、§7.2、§7.5 |
| 11 | 失败路径 | §8.1、§8.2 |
| 12 | E2E 与对比实验 | §10 |

---

### Task 1: 清障——删重复 Bean 与不可编译的旧监听器

**Files:**
- Delete: `UserModule/src/main/java/org/leon/usermodule/config/MqConfig.java`
- Delete: `AuthModule/src/main/java/org/leon/authmodule/config/MqConfig.java`
- Delete: `UserModule/src/main/java/org/leon/usermodule/comsumer/Listener/LoginListener.java`
- Modify: `UserModule/src/main/java/org/leon/usermodule/UserModuleApplication.java:1-8`

**Interfaces:**
- Consumes: 无
- Produces: 每个模块只剩一个 `MessageConverter` bean（`RabbitMqDemoConfig.jsonMessageConverter` / `CaptchaMqConfig.jsonMessageConverter`）

- [x] **Step 1: 确认当前残留的重复声明**

```bash
cd /f/AAAJAVA/code/ezPracticeExercises
grep -rn "public MessageConverter\|jacksonMessageConverter\|jsonMessageConverter" --include=*.java UserModule AuthModule
```

预期：UserModule 命中 `config/MqConfig.java:12` 和 `config/RabbitMqDemoConfig.java:122`；AuthModule 命中 `config/MqConfig.java:12` 和 `config/CaptchaMqConfig.java:88`。

- [x] **Step 2: 删三个文件**

```bash
git rm UserModule/src/main/java/org/leon/usermodule/config/MqConfig.java \
       AuthModule/src/main/java/org/leon/authmodule/config/MqConfig.java \
       UserModule/src/main/java/org/leon/usermodule/comsumer/Listener/LoginListener.java
```

- [x] **Step 3: 清掉启动类里的残留 import**

`UserModuleApplication.java` 只保留这四个 import（`Jackson2JsonMessageConverter`、`MessageConverter`、`Bean`、`Configuration` 已经不再被使用）：

```java
package org.leon.usermodule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@ComponentScan(basePackages = {"org.leon.usermodule", "org.leon.commonjwt"})
@SpringBootApplication
public class UserModuleApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserModuleApplication.class, args);
    }

}
```

- [x] **Step 4: 编译两个模块**

```bash
"$MVN" -o -pl AuthModule,UserModule -am -DskipTests compile
```

预期：`BUILD SUCCESS`。若报 `找不到符号 AuthRedisService`，说明 `LoginListener.java` 没删干净。

- [x] **Step 5: 启动冒烟**

用 IDE 分别启动 `AuthModuleApplication` 与 `UserModuleApplication`。预期：两者都打印 `Started ...`，不再出现 `BeanDefinitionOverrideException`。启动前需要 Nacos(8848)、MySQL(3306)、RabbitMQ(5672) 在线。

- [x] **Step 6: 提交**

```bash
git add -A UserModule AuthModule
git commit -m "清理重复的MessageConverter Bean和无法编译的LoginListener"
```

#### Task 1 执行记录（2026-10-21）

- 已删除：`AuthModule/config/MqConfig.java`、`UserModule/config/MqConfig.java`、`UserModule/comsumer/Listener/LoginListener.java`；`UserModuleApplication` 只保留 3 个 import。
- `mvn -o -pl AuthModule,UserModule -am clean compile -DskipTests` → **BUILD SUCCESS**（44 个源文件）。先跑非 clean 的 compile 时 AuthModule 显示 "Nothing to compile"，旧 class 会残留，必须 clean。
- 冒烟：两个可执行 jar 用 **JDK 21 的 java.exe** 启动（PATH 上的 java 是 17，直接 `java -jar` 会 `UnsupportedClassVersionError`）。AuthModule `Started ... in 9.543s`（8103），UserModule `Started ... in 11.427s`（8101），无 `BeanDefinitionOverrideException`。
- Broker 原有队列全部还在：`auth.sms.*`、`demo.user.*`、`hello.*`、`work.queue` 等。
- 提交：`0b3e1b1`。

---

### Task 2: MQ 事件 DTO 与交换机

**Files:**
- Create: `AuthModule/src/main/java/org/leon/authmodule/pojo/mq/LoginSuccessEvent.java`
- Create: `AuthModule/src/main/java/org/leon/authmodule/config/LoginMqConfig.java`
- Test: `AuthModule/src/test/java/org/leon/authmodule/pojo/mq/LoginSuccessEventTest.java`

**Interfaces:**
- Produces: `LoginSuccessEvent(userId:Long, username:String, traceId:String, loginIp:String, loginAt:long, tokenFingerprint:String)`；常量 `LoginMqConfig.LOGIN_EXCHANGE="auth.login.exchange"`、`LOGIN_SUCCESS_KEY="user.login.success"`、`LOGIN_DEAD_KEY="user.login.dead"`

- [x] **Step 1: 写失败测试**

```java
package org.leon.authmodule.pojo.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginSuccessEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @DisplayName("事件能被 Jackson 往返序列化，字段不丢")
    @Test
    void shouldRoundTripThroughJackson() throws Exception {
        LoginSuccessEvent event = new LoginSuccessEvent(
                10L, "test001", "trace-1", "203.0.113.7", 1_700_000_000_000L, "abcdef123456");

        String json = objectMapper.writeValueAsString(event);
        LoginSuccessEvent back = objectMapper.readValue(json, LoginSuccessEvent.class);

        assertEquals(10L, back.getUserId());
        assertEquals("test001", back.getUsername());
        assertEquals("trace-1", back.getTraceId());
        assertEquals("203.0.113.7", back.getLoginIp());
        assertEquals(1_700_000_000_000L, back.getLoginAt());
        assertEquals("abcdef123456", back.getTokenFingerprint());
    }

    @DisplayName("消息体里不能出现密码或 token 字段")
    @Test
    void shouldNotLeakSecrets() throws Exception {
        String json = objectMapper.writeValueAsString(new LoginSuccessEvent(
                10L, "test001", "trace-1", "203.0.113.7", 1_700_000_000_000L, "abcdef123456"));

        assertFalse(json.contains("password"));
        assertFalse(json.contains("\"token\""));
        assertTrue(json.contains("tokenFingerprint"));
    }
}
```

- [x] **Step 2: 运行，确认失败**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=LoginSuccessEventTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：编译失败，`找不到符号: 类 LoginSuccessEvent`。

- [x] **Step 3: 实现 DTO**

```java
package org.leon.authmodule.pojo.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 登录成功事件：只带"谁在何时从哪登录"，角色/手机号由消费端反查。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginSuccessEvent {
    private Long userId;
    private String username;
    /** 贯穿生产/消费的链路号 */
    private String traceId;
    private String loginIp;
    /** epoch millis，避免跨服务的日期类型绑定 */
    private long loginAt;
    /** SHA-256(token) 的前 12 位，仅用于日志关联，绝不传 token 本体 */
    private String tokenFingerprint;
}
```

- [x] **Step 4: 实现交换机配置**

```java
package org.leon.authmodule.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 登录事件的交换机与路由键。队列由消费方 UserModule 声明。 */
@Configuration
public class LoginMqConfig {

    public static final String LOGIN_EXCHANGE = "auth.login.exchange";
    public static final String LOGIN_SUCCESS_KEY = "user.login.success";
    /** 死信路由键，必须与消费端 LoginEventQueueConfig.LOGIN_DEAD_KEY 完全一致 */
    public static final String LOGIN_DEAD_KEY = "user.login.dead";

    @Bean
    public TopicExchange loginExchange() {
        return new TopicExchange(LOGIN_EXCHANGE, true, false);
    }
}
```

- [x] **Step 5: 运行，确认通过**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=LoginSuccessEventTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`Tests run: 2, Failures: 0, Errors: 0`。

- [x] **Step 6: 提交**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/pojo/mq/LoginSuccessEvent.java \
        AuthModule/src/main/java/org/leon/authmodule/config/LoginMqConfig.java \
        AuthModule/src/test/java/org/leon/authmodule/pojo/mq/LoginSuccessEventTest.java
git commit -m "新增登录事件消息体和交换机配置"
```

---

### Task 3: AuthModule 事件生产者

**Files:**
- Create: `AuthModule/src/main/java/org/leon/authmodule/mq/LoginEventPublisher.java`
- Test: `AuthModule/src/test/java/org/leon/authmodule/mq/LoginEventPublisherTest.java`

**Interfaces:**
- Consumes: `LoginSuccessEvent`、`LoginMqConfig.LOGIN_EXCHANGE` / `LOGIN_SUCCESS_KEY`
- Produces: `void LoginEventPublisher.publish(LoginSuccessEvent event)`

- [x] **Step 1: 写失败测试**

```java
package org.leon.authmodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.config.LoginMqConfig;
import org.leon.authmodule.pojo.mq.LoginSuccessEvent;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoginEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @DisplayName("登录事件投到 auth.login.exchange 的 user.login.success 路由键")
    @Test
    void publishUsesLoginExchangeAndSuccessKey() {
        LoginSuccessEvent event = new LoginSuccessEvent(
                10L, "test001", "trace-1", "203.0.113.7", 1_700_000_000_000L, "abcdef123456");

        new LoginEventPublisher(rabbitTemplate).publish(event);

        verify(rabbitTemplate).convertAndSend(
                LoginMqConfig.LOGIN_EXCHANGE, LoginMqConfig.LOGIN_SUCCESS_KEY, event);
    }
}
```

- [x] **Step 2: 运行，确认失败**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=LoginEventPublisherTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：编译失败，`找不到符号: 类 LoginEventPublisher`。

- [x] **Step 3: 实现生产者**

```java
package org.leon.authmodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.config.LoginMqConfig;
import org.leon.authmodule.pojo.mq.LoginSuccessEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoginEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public LoginEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(LoginSuccessEvent event) {
        log.info("[登录事件生产者] traceId={} userId={}", event.getTraceId(), event.getUserId());
        rabbitTemplate.convertAndSend(
                LoginMqConfig.LOGIN_EXCHANGE, LoginMqConfig.LOGIN_SUCCESS_KEY, event);
    }
}
```

- [x] **Step 4: 运行，确认通过**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=LoginEventPublisherTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`Tests run: 1, Failures: 0`。

- [x] **Step 5: 提交**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/mq/LoginEventPublisher.java \
        AuthModule/src/test/java/org/leon/authmodule/mq/LoginEventPublisherTest.java
git commit -m "新增登录事件生产者"
```

---

### Task 4: 挂进登录流程（不影响登录成败）

**Files:**
- Create: `AuthModule/src/main/java/org/leon/authmodule/util/TokenFingerprint.java`
- Modify: `AuthModule/src/main/java/org/leon/authmodule/service/impl/AuthServiceImpl.java:29-45`（加字段）、`:95-97`（插入发布）
- Test: `AuthModule/src/test/java/org/leon/authmodule/service/impl/AuthServiceImplLoginEventTest.java`
- Test: `AuthModule/src/test/java/org/leon/authmodule/util/TokenFingerprintTest.java`

**Interfaces:**
- Produces: `String TokenFingerprint.of(String token)`（返回 12 位小写十六进制）；登录成功后发布 `LoginSuccessEvent`

- [x] **Step 1: 写指纹的失败测试**

```java
package org.leon.authmodule.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenFingerprintTest {

    @DisplayName("指纹固定 12 位十六进制，且相同 token 结果稳定")
    @Test
    void shouldBeStableTwelveHexChars() {
        String fp1 = TokenFingerprint.of("token-value");
        String fp2 = TokenFingerprint.of("token-value");

        assertEquals(fp1, fp2);
        assertEquals(12, fp1.length());
        assertTrue(fp1.matches("[0-9a-f]{12}"));
    }

    @DisplayName("不同 token 得到不同指纹")
    @Test
    void shouldDifferForDifferentTokens() {
        assertNotEquals(TokenFingerprint.of("a"), TokenFingerprint.of("b"));
    }
}
```

- [x] **Step 2: 运行，确认失败**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=TokenFingerprintTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`找不到符号: 类 TokenFingerprint`。

- [x] **Step 3: 实现指纹工具**

```java
package org.leon.authmodule.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 把 JWT 压成短指纹，用于日志关联；不可逆，不能当凭证使用。 */
public final class TokenFingerprint {

    private TokenFingerprint() {
    }

    public static String of(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }
}
```

- [x] **Step 4: 写登录发布的失败测试**

```java
package org.leon.authmodule.service.impl;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.mq.LoginEventPublisher;
import org.leon.authmodule.pojo.LoginRequest;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.Users;
import org.leon.authmodule.pojo.mq.LoginSuccessEvent;
import org.leon.authmodule.service.AuthRedisService;
import org.leon.authmodule.service.CaptchaService;
import org.leon.commonjwt.utils.JwtUtil;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplLoginEventTest {

    @Mock private AuthMapper authMapper;
    @Mock private JwtUtil jwtUtil;
    @Mock private AuthRedisService authRedisService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CaptchaService captchaService;
    @Mock private LoginEventPublisher loginEventPublisher;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(passwordEncoder);
        ReflectionTestUtils.setField(authService, "AuthMapper", authMapper);
        ReflectionTestUtils.setField(authService, "jwtUtil", jwtUtil);
        ReflectionTestUtils.setField(authService, "authRedisService", authRedisService);
        ReflectionTestUtils.setField(authService, "captchaService", captchaService);
        ReflectionTestUtils.setField(authService, "loginEventPublisher", loginEventPublisher);
        ReflectionTestUtils.setField(authService, "jwtExpiration", 7200000L);
    }

    private void stubSuccessfulLogin() {
        LoginRequest request = new LoginRequest();
        request.setUsername("test001");
        request.setPassword("password123");

        Users users = new Users();
        users.setId(10L);
        users.setUsername("test001");
        users.setPassword_hash("$2a$10$hash");
        users.setRoleId("1");

        Claims claims = mock(Claims.class);
        when(claims.getIssuedAt()).thenReturn(new Date(1_700_000_000_000L));
        when(claims.getExpiration()).thenReturn(new Date(1_700_007_200_000L));

        when(captchaService.verifyAndConsume(any(), any())).thenReturn(true);
        when(authMapper.selectByUsername("test001")).thenReturn(users);
        when(passwordEncoder.matches("password123", "$2a$10$hash")).thenReturn(true);
        when(jwtUtil.generateToken2(10L, "test001", "1")).thenReturn("token-value");
        when(jwtUtil.getClaimsFromToken("token-value")).thenReturn(claims);
    }

    private LoginRequest newRequest() {
        LoginRequest request = new LoginRequest();
        request.setUsername("test001");
        request.setPassword("password123");
        return request;
    }

    @DisplayName("登录成功发布事件：带 userId/IP/traceId，且只带 token 指纹")
    @Test
    void shouldPublishLoginEventWithFingerprint() {
        stubSuccessfulLogin();

        authService.login(newRequest(), "203.0.113.7");

        ArgumentCaptor<LoginSuccessEvent> captor = ArgumentCaptor.forClass(LoginSuccessEvent.class);
        verify(loginEventPublisher).publish(captor.capture());
        LoginSuccessEvent event = captor.getValue();
        assertEquals(10L, event.getUserId());
        assertEquals("test001", event.getUsername());
        assertEquals("203.0.113.7", event.getLoginIp());
        assertNotNull(event.getTraceId());
        assertEquals(12, event.getTokenFingerprint().length());
        assertNotNull(event.getLoginAt());
    }

    @DisplayName("MQ 发布失败时登录仍然成功，且照常返回 token")
    @Test
    void shouldStillSucceedWhenPublishFails() {
        stubSuccessfulLogin();
        doThrow(new RuntimeException("broker down"))
                .when(loginEventPublisher).publish(any());

        Result result = authService.login(newRequest(), "203.0.113.7");

        assertEquals(1, result.getCode());
    }
}
```

- [x] **Step 5: 运行，确认失败**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=AuthServiceImplLoginEventTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：编译失败（`loginEventPublisher` 字段不存在）或断言失败（事件未发布）。

- [x] **Step 6: 修改 `AuthServiceImpl`**

在 `AuthRedisService` 字段（`:35-36`）之后加：

```java
    @Autowired
    private LoginEventPublisher loginEventPublisher;
```

并补 import：

```java
import org.leon.authmodule.mq.LoginEventPublisher;
import org.leon.authmodule.pojo.mq.LoginSuccessEvent;
import org.leon.authmodule.util.TokenFingerprint;
import java.util.UUID;
```

在 `authRedisService.saveLoginInfo(...)`（`:95`）之后、构造返回 Map（`:98`）之前插入：

```java
        // 5. 发布登录事件。MQ 是"尽力而为"：Broker 不可用绝不能把登录搞挂。
        String traceId = UUID.randomUUID().toString();
        try {
            loginEventPublisher.publish(new LoginSuccessEvent(
                    users.getId(), users.getUsername(), traceId, clientIp,
                    System.currentTimeMillis(), TokenFingerprint.of(token)));
        } catch (Exception e) {
            log.error("[登录事件] 发布失败，登录继续。userId={} traceId={}", users.getId(), traceId, e);
        }
```

- [x] **Step 7: 运行两个测试，确认通过**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest='TokenFingerprintTest,AuthServiceImplLoginEventTest,AuthServiceImplLoginIpTest' \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：全部通过，且 `AuthServiceImplLoginIpTest` 的 5 个旧用例不受影响。

- [x] **Step 8: 提交**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/util/TokenFingerprint.java \
        AuthModule/src/main/java/org/leon/authmodule/service/impl/AuthServiceImpl.java \
        AuthModule/src/test/java/org/leon/authmodule/util/TokenFingerprintTest.java \
        AuthModule/src/test/java/org/leon/authmodule/service/impl/AuthServiceImplLoginEventTest.java
git commit -m "登录成功后发布登录事件，发送失败不影响登录"
```

---

### Task 5: UserModule 消费端拓扑（含死信）

**Files:**
- Create: `UserModule/src/main/java/org/leon/usermodule/config/LoginEventQueueConfig.java`
- Test: `UserModule/src/test/java/org/leon/usermodule/config/LoginEventQueueConfigTest.java`

**Interfaces:**
- Produces: 常量 `LOGIN_EXCHANGE`/`LOGIN_SUCCESS_KEY`（与 AuthModule 同值）、`LOGIN_EVENT_QUEUE="user.login.event.queue"`、`LOGIN_DLX="user.login.event.dlx"`、`LOGIN_DEAD_KEY="user.login.dead"`、`LOGIN_DEAD_QUEUE="user.login.event.dead.queue"`

- [x] **Step 1: 写失败测试（纯单元，不连 Broker）**

```java
package org.leon.usermodule.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginEventQueueConfigTest {

    private final LoginEventQueueConfig config = new LoginEventQueueConfig();

    @DisplayName("主队列持久化且挂了死信交换机与独立路由键")
    @Test
    void mainQueueShouldDeclareDeadLetterArguments() {
        Queue queue = config.loginEventQueue();

        assertEquals("user.login.event.queue", queue.getName());
        assertTrue(queue.isDurable());
        assertEquals("user.login.event.dlx",
                queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals("user.login.dead",
                queue.getArguments().get("x-dead-letter-routing-key"));
    }

    @DisplayName("死信队列独立存在，不与主队列同名")
    @Test
    void deadQueueShouldBeSeparate() {
        Queue dead = config.loginDeadQueue();

        assertEquals("user.login.event.dead.queue", dead.getName());
        assertTrue(dead.isDurable());
    }

    @DisplayName("交换机名与路由键必须与生产端 AuthModule 完全一致")
    @Test
    void contractMustMatchProducer() {
        assertEquals("auth.login.exchange", LoginEventQueueConfig.LOGIN_EXCHANGE);
        assertEquals("user.login.success", LoginEventQueueConfig.LOGIN_SUCCESS_KEY);
        assertEquals("user.login.dead", LoginEventQueueConfig.LOGIN_DEAD_KEY);
    }
}
```

- [x] **Step 2: 运行，确认失败**

```bash
"$MVN" -o -pl UserModule -am test -Dtest=LoginEventQueueConfigTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`找不到符号: 类 LoginEventQueueConfig`。

- [x] **Step 3: 实现拓扑配置**

```java
package org.leon.usermodule.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 登录事件的消费端拓扑：1 交换机 + 主队列 + 死信交换机 + 死信队列。
 * 交换机名与路由键必须与 AuthModule 的 LoginMqConfig 一字不差。
 */
@Configuration
public class LoginEventQueueConfig {

    public static final String LOGIN_EXCHANGE = "auth.login.exchange";
    public static final String LOGIN_SUCCESS_KEY = "user.login.success";
    public static final String LOGIN_EVENT_QUEUE = "user.login.event.queue";
    public static final String LOGIN_DLX = "user.login.event.dlx";
    public static final String LOGIN_DEAD_KEY = "user.login.dead";
    public static final String LOGIN_DEAD_QUEUE = "user.login.event.dead.queue";

    @Bean
    public TopicExchange loginExchange() {
        return new TopicExchange(LOGIN_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange loginDlx() {
        return new DirectExchange(LOGIN_DLX, true, false);
    }

    @Bean
    public Queue loginEventQueue() {
        return QueueBuilder.durable(LOGIN_EVENT_QUEUE)
                .deadLetterExchange(LOGIN_DLX)
                .deadLetterRoutingKey(LOGIN_DEAD_KEY)
                .build();
    }

    @Bean
    public Queue loginDeadQueue() {
        return QueueBuilder.durable(LOGIN_DEAD_QUEUE).build();
    }

    @Bean
    public Binding loginEventBinding() {
        return BindingBuilder.bind(loginEventQueue()).to(loginExchange()).with(LOGIN_SUCCESS_KEY);
    }

    @Bean
    public Binding loginDeadBinding() {
        return BindingBuilder.bind(loginDeadQueue()).to(loginDlx()).with(LOGIN_DEAD_KEY);
    }
}
```

- [x] **Step 4: 运行，确认通过**

```bash
"$MVN" -o -pl UserModule -am test -Dtest=LoginEventQueueConfigTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`Tests run: 3, Failures: 0`。

- [x] **Step 5: 启动一次，让 Broker 上真的建出拓扑**

启动 UserModule，打开 `http://127.0.0.1:15672` → Queues，确认 `user.login.event.queue` 的 Features 列有 `D`（durable）与 `Args: x-dead-letter-exchange`。

- [x] **Step 6: 提交**

```bash
git add UserModule/src/main/java/org/leon/usermodule/config/LoginEventQueueConfig.java \
        UserModule/src/test/java/org/leon/usermodule/config/LoginEventQueueConfigTest.java
git commit -m "声明登录事件消费端拓扑与死信队列"
```

---

### Task 6: 消费者（本阶段只打日志）+ 环形队列

**Files:**
- Create: `UserModule/src/main/java/org/leon/usermodule/pojo/mq/LoginEvent.java`
- Create: `UserModule/src/main/java/org/leon/usermodule/pojo/vo/ProcessedLogin.java`
- Create: `UserModule/src/main/java/org/leon/usermodule/mq/RecentLoginStore.java`
- Create: `UserModule/src/main/java/org/leon/usermodule/mq/LoginEventConsumer.java`
- Create: `UserModule/src/main/java/org/leon/usermodule/mq/LoginDeadLetterConsumer.java`
- Test: `UserModule/src/test/java/org/leon/usermodule/mq/LoginEventConsumerTest.java`
- Test: `UserModule/src/test/java/org/leon/usermodule/mq/RecentLoginStoreTest.java`

**Interfaces:**
- Consumes: `LoginEventQueueConfig.*`
- Produces: `LoginEvent`（字段同 AuthModule 的 `LoginSuccessEvent`）；`RecentLoginStore.add(ProcessedLogin)` / `List<ProcessedLogin> recent()`；`ProcessedLogin(LoginEvent event, long consumedAt)`（Task 10 会加 `profile` 字段）

- [x] **Step 1: 写环形队列的失败测试**

```java
package org.leon.usermodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.leon.usermodule.pojo.vo.ProcessedLogin;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecentLoginStoreTest {

    private static ProcessedLogin item(long userId) {
        LoginEvent event = new LoginEvent(userId, "u" + userId, "trace-" + userId,
                "127.0.0.1", 1_700_000_000_000L, "abcdef123456");
        return new ProcessedLogin(event, 1_700_000_000_001L);
    }

    @DisplayName("最新的一条排在最前")
    @Test
    void newestFirst() {
        RecentLoginStore store = new RecentLoginStore();
        store.add(item(1L));
        store.add(item(2L));

        List<ProcessedLogin> recent = store.recent();
        assertEquals(2L, recent.get(0).getEvent().getUserId());
    }

    @DisplayName("超过容量淘汰最旧的")
    @Test
    void evictsOldestBeyondCapacity() {
        RecentLoginStore store = new RecentLoginStore();
        for (long i = 1; i <= 25; i++) {
            store.add(item(i));
        }

        List<ProcessedLogin> recent = store.recent();
        assertEquals(20, recent.size());
        assertEquals(25L, recent.get(0).getEvent().getUserId());
        assertEquals(6L, recent.get(19).getEvent().getUserId());
    }
}
```

- [x] **Step 2: 写消费者的失败测试**

```java
package org.leon.usermodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.leon.usermodule.pojo.vo.ProcessedLogin;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoginEventConsumerTest {

    @DisplayName("收到事件后写进环形队列，traceId 原样保留")
    @Test
    void shouldStoreProcessedLogin() {
        RecentLoginStore store = new RecentLoginStore();
        LoginEventConsumer consumer = new LoginEventConsumer(store);

        consumer.onLoginEvent(new LoginEvent(10L, "test001", "trace-1",
                "203.0.113.7", 1_700_000_000_000L, "abcdef123456"));

        List<ProcessedLogin> recent = store.recent();
        assertEquals(1, recent.size());
        assertEquals("trace-1", recent.get(0).getEvent().getTraceId());
    }
}
```

- [x] **Step 3: 运行，确认失败**

```bash
"$MVN" -o -pl UserModule -am test -Dtest='RecentLoginStoreTest,LoginEventConsumerTest' \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：编译失败，找不到 `LoginEvent` / `ProcessedLogin` / `RecentLoginStore` / `LoginEventConsumer`。

- [x] **Step 4: 实现 DTO 与 VO**

```java
package org.leon.usermodule.pojo.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录事件的消费侧视图。类名/包名与生产端不同是刻意的：
 * MQ 传的是数据契约，不是类型契约（转换器已设为 INFERRED，见 Task 10）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginEvent {
    private Long userId;
    private String username;
    private String traceId;
    private String loginIp;
    private long loginAt;
    private String tokenFingerprint;
}
```

```java
package org.leon.usermodule.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.leon.usermodule.pojo.mq.LoginEvent;

/** 一条"已处理"的登录记录，供 /user/mq/login/recent 观察。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedLogin {
    private LoginEvent event;
    private long consumedAt;
}
```

- [x] **Step 5: 实现环形队列**

```java
package org.leon.usermodule.mq;

import org.leon.usermodule.pojo.vo.ProcessedLogin;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 只保留最近 20 条，纯粹为了演示可观察，不落库。 */
@Component
public class RecentLoginStore {

    static final int CAPACITY = 20;

    private final Deque<ProcessedLogin> recent = new ArrayDeque<>();

    public synchronized void add(ProcessedLogin processed) {
        if (recent.size() == CAPACITY) {
            recent.removeLast();
        }
        recent.addFirst(processed);
    }

    public synchronized List<ProcessedLogin> recent() {
        return new ArrayList<>(recent);
    }
}
```

- [x] **Step 6: 实现两个消费者**

```java
package org.leon.usermodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.usermodule.config.LoginEventQueueConfig;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.leon.usermodule.pojo.vo.ProcessedLogin;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 本任务只落观察队列；Task 10 在这里加 Feign 反查。 */
@Slf4j
@Component
public class LoginEventConsumer {

    private final RecentLoginStore store;

    public LoginEventConsumer(RecentLoginStore store) {
        this.store = store;
    }

    @RabbitListener(queues = LoginEventQueueConfig.LOGIN_EVENT_QUEUE)
    public void onLoginEvent(LoginEvent event) {
        log.info("[登录事件消费者] traceId={} userId={} username={} ip={}",
                event.getTraceId(), event.getUserId(), event.getUsername(), event.getLoginIp());
        store.add(new ProcessedLogin(event, System.currentTimeMillis()));
    }
}
```

```java
package org.leon.usermodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.usermodule.config.LoginEventQueueConfig;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 死信只做可见性：能在这条日志里看到，说明消息确实耗尽重试进了死信。 */
@Slf4j
@Component
public class LoginDeadLetterConsumer {

    @RabbitListener(queues = LoginEventQueueConfig.LOGIN_DEAD_QUEUE)
    public void onDeadLetter(LoginEvent event) {
        log.error("[登录死信] 消息进入死信队列，traceId={} userId={}",
                event.getTraceId(), event.getUserId());
    }
}
```

- [x] **Step 7: 运行，确认通过**

```bash
"$MVN" -o -pl UserModule -am test -Dtest='RecentLoginStoreTest,LoginEventConsumerTest,LoginEventQueueConfigTest' \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`Tests run: 5, Failures: 0`。

- [x] **Step 8: 提交**

```bash
git add UserModule/src/main/java/org/leon/usermodule/pojo/mq/LoginEvent.java \
        UserModule/src/main/java/org/leon/usermodule/pojo/vo/ProcessedLogin.java \
        UserModule/src/main/java/org/leon/usermodule/mq/RecentLoginStore.java \
        UserModule/src/main/java/org/leon/usermodule/mq/LoginEventConsumer.java \
        UserModule/src/main/java/org/leon/usermodule/mq/LoginDeadLetterConsumer.java \
        UserModule/src/test/java/org/leon/usermodule/mq/LoginEventConsumerTest.java \
        UserModule/src/test/java/org/leon/usermodule/mq/RecentLoginStoreTest.java
git commit -m "新增登录事件消费者和最近登录记录环形队列"
```

---

### Task 7: 手动触发与观察端点

**Files:**
- Create: `AuthModule/src/main/java/org/leon/authmodule/controller/AuthDemoController.java`
- Modify: `UserModule/src/main/java/org/leon/usermodule/controller/MqDemoController.java:21-43`
- Test: `AuthModule/src/test/java/org/leon/authmodule/controller/AuthDemoControllerTest.java`

**Interfaces:**
- Consumes: `AuthMapper.selectByUserId(Long)`、`LoginEventPublisher.publish(LoginSuccessEvent)`、`RecentLoginStore.recent()`
- Produces: `GET /auth/demo/login-event?userId=1` → `Result`（`data` 为 traceId 字符串）；`GET /user/mq/login/recent` → `Result`（`data` 为 `List<ProcessedLogin>`）

- [x] **Step 1: 写触发端点的失败测试**

```java
package org.leon.authmodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.mq.LoginEventPublisher;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.Users;
import org.leon.authmodule.pojo.mq.LoginSuccessEvent;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthDemoControllerTest {

    private static MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }

    @DisplayName("按 userId 查库并发布事件，返回 traceId")
    @Test
    void shouldPublishEventForExistingUser() {
        AuthMapper authMapper = mock(AuthMapper.class);
        LoginEventPublisher publisher = mock(LoginEventPublisher.class);
        Users users = new Users();
        users.setId(1L);
        users.setUsername("leon");
        users.setRoleId("1");
        users.setLast_login_ip("127.0.0.1");
        when(authMapper.selectByUserId(1L)).thenReturn(users);

        Result result = new AuthDemoController(authMapper, publisher)
                .triggerLoginEvent(1L, requestFrom("203.0.113.7"));

        assertEquals(1, result.getCode());
        assertNotNull(result.getData());
        ArgumentCaptor<LoginSuccessEvent> captor = ArgumentCaptor.forClass(LoginSuccessEvent.class);
        verify(publisher).publish(captor.capture());
        assertEquals(1L, captor.getValue().getUserId());
        assertEquals("203.0.113.7", captor.getValue().getLoginIp());
    }

    @DisplayName("用户不存在时不发布事件")
    @Test
    void shouldNotPublishForMissingUser() {
        AuthMapper authMapper = mock(AuthMapper.class);
        LoginEventPublisher publisher = mock(LoginEventPublisher.class);
        when(authMapper.selectByUserId(999999L)).thenReturn(null);

        Result result = new AuthDemoController(authMapper, publisher)
                .triggerLoginEvent(999999L, requestFrom("203.0.113.7"));

        assertEquals(0, result.getCode());
        verify(publisher, org.mockito.Mockito.never()).publish(any());
    }
}
```

- [x] **Step 2: 运行，确认失败**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=AuthDemoControllerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`找不到符号: 类 AuthDemoController`。

- [x] **Step 3: 实现触发端点**

```java
package org.leon.authmodule.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.mq.LoginEventPublisher;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.Users;
import org.leon.authmodule.pojo.mq.LoginSuccessEvent;
import org.leon.authmodule.util.IpUtil;
import org.leon.authmodule.util.TokenFingerprint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 仅用于演示：[DEMO] 前缀，生产环境必须删除。绕开图形验证码直接发事件。 */
@Slf4j
@RestController
@RequestMapping("/auth/demo")
public class AuthDemoController {

    private final AuthMapper authMapper;
    private final LoginEventPublisher publisher;

    public AuthDemoController(AuthMapper authMapper, LoginEventPublisher publisher) {
        this.authMapper = authMapper;
        this.publisher = publisher;
    }

    @GetMapping("/login-event")
    public Result triggerLoginEvent(@RequestParam(defaultValue = "1") Long userId,
                                    HttpServletRequest request) {
        String clientIp = IpUtil.getClientIp(request);
        Users users = authMapper.selectByUserId(userId);
        if (users == null) {
            log.warn("[DEMO] 用户不存在，不发布事件。userId={}", userId);
            return Result.error("用户不存在，未发布事件");
        }

        String traceId = UUID.randomUUID().toString();
        publisher.publish(new LoginSuccessEvent(
                users.getId(), users.getUsername(), traceId, clientIp,
                System.currentTimeMillis(), TokenFingerprint.of("demo-token-" + userId)));
        log.info("[DEMO] 已发布登录事件 traceId={} userId={}", traceId, userId);
        return Result.success(traceId);
    }
}
```

**只使用构造器注入**：不要在字段上再加 `@Autowired`，否则 Spring 会用构造器创建、再尝试为字段注入，测试里 `new AuthDemoController(...)` 也会出现字段为 null 的假象。

- [x] **Step 4: 加观察端点（UserModule）**

在 `MqDemoController` 构造函数里多注入一个 `RecentLoginStore`：

```java
    private final UserRegisterProducer producer;
    private final RecentLoginStore recentLoginStore;

    public MqDemoController(UserRegisterProducer producer, RecentLoginStore recentLoginStore) {
        this.producer = producer;
        this.recentLoginStore = recentLoginStore;
    }
```

新增方法：

```java
    @GetMapping("/login/recent")
    public Result loginRecent() {
        return Result.success(recentLoginStore.recent());
    }
```

- [x] **Step 5: 编译并跑测试**

```bash
"$MVN" -o -pl AuthModule,UserModule -am test -Dtest=AuthDemoControllerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：AuthModule 通过；UserModule 编译通过（该模块没有匹配测试，靠 `failIfNoTests=false` 放过）。

- [x] **Step 6: 手工验证 MQ 半边**

两个应用都启动后，浏览器访问：

```
http://127.0.0.1:8103/auth/demo/login-event?userId=1
http://127.0.0.1:8101/user/mq/login/recent
```

预期：AuthModule 日志出现 `[登录事件生产者]`；UserModule 日志出现 `[登录事件消费者]`；第二个 URL 返回的 `data` 数组长度为 1。

- [x] **Step 7: 提交**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/controller/AuthDemoController.java \
        AuthModule/src/test/java/org/leon/authmodule/controller/AuthDemoControllerTest.java \
        UserModule/src/main/java/org/leon/usermodule/controller/MqDemoController.java
git commit -m "新增登录事件手动触发端点和最近登录查询端点"
```

#### 第 2 段验收记录（2026-10-21，Task 2–7 已完成）

- 测试全绿：Task 2 2 个、Task 3 1 个、Task 4 9 个（含旧 `AuthServiceImplLoginIpTest` 5 个）、Task 5 3 个、Task 6 6 个、Task 7 2 个；`RabbitMqDemoTest` 2 个也通过，确认 `INFERRED` 没破坏原有 demo 链路。
- 真实链路：两应用启动（8103/8101）→ `GET /auth/demo/login-event?userId=1` 两次均返回 traceId → UserModule 日志出现 `[登录事件消费者] ... userId=1 username=admin` → `GET /user/mq/login/recent` 返回两条 `ProcessedLogin`；主队列与死信队列均 `messages=0`，两个队列各有 1 个消费者。
- 两处计划偏差（均为本段验收必需）：
  1. `SecurityConfig` 增加 `/auth/demo/**` permitAll。原计划 Task 7 未列此文件，但 `anyRequest().authenticated()` 会让 demo 端点直接 401。
  2. `RabbitMqDemoConfig.jsonMessageConverter` 提前改为 `TypePrecedence.INFERRED`（原计划 Task 10 Step 5）。不改则消费端按生产端 `__TypeId__=org.leon.authmodule.pojo.mq.LoginSuccessEvent` 找不到类，Task 7 的消费验收无法通过。单独提交 `29f4312`，Task 10 执行时跳过该步。
- 提交：`df72a76`（Task 2）、`cd6fbc1`（Task 3）、`f376c02`（Task 4）、`a2a69bf`（Task 5）、`e786f56`（Task 6）、`e938c7f`（Task 7 + SecurityConfig）、`29f4312`（INFERRED）。
- 本段未覆盖：Feign 反查（Task 8–10）、重试/死信（Task 11）、E2E（Task 12）。

---

### Task 8: Feign 服务端——内部用户查询接口

**Files:**
- Create: `AuthModule/src/main/java/org/leon/authmodule/pojo/vo/UserProfileVO.java`
- Create: `AuthModule/src/main/java/org/leon/authmodule/controller/InternalUserController.java`
- Test: `AuthModule/src/test/java/org/leon/authmodule/controller/InternalUserControllerTest.java`

**Interfaces:**
- Consumes: `AuthMapper.selectByUserId(Long)`
- Produces: `GET /auth/internal/users/{userId}` → `ResponseEntity<UserProfileVO>`；`UserProfileVO(userId:Long, username:String, roleId:String, status:Integer, phone:String, lastLoginAt:Date, lastLoginIp:String)`

- [x] **Step 1: 写失败测试（MockMvc standalone，不加载安全链）**

```java
package org.leon.authmodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.pojo.Users;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalUserControllerTest {

    private final AuthMapper authMapper = mock(AuthMapper.class);

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new InternalUserController(authMapper)).build();
    }

    @DisplayName("返回角色与手机号，且响应体不含密码哈希")
    @Test
    void shouldReturnProfileWithoutPassword() throws Exception {
        Users users = new Users();
        users.setId(10L);
        users.setUsername("test001");
        users.setRoleId("2");
        users.setPhone("13631155285");
        users.setStatus(1);
        users.setPassword_hash("$2a$10$hash");
        when(authMapper.selectByUserId(10L)).thenReturn(users);

        mockMvc().perform(get("/auth/internal/users/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleId").value("2"))
                .andExpect(jsonPath("$.phone").value("13631155285"))
                .andExpect(jsonPath("$.password_hash").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @DisplayName("用户不存在返回 404")
    @Test
    void shouldReturn404WhenMissing() throws Exception {
        when(authMapper.selectByUserId(999999L)).thenReturn(null);

        mockMvc().perform(get("/auth/internal/users/999999"))
                .andExpect(status().isNotFound());
    }
}
```

- [x] **Step 2: 运行，确认失败**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=InternalUserControllerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`找不到符号: 类 InternalUserController`。

- [x] **Step 3: 实现 VO 与控制器**

```java
package org.leon.authmodule.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** 内部查询返回的用户资料。刻意不含 password_hash。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileVO {
    private Long userId;
    private String username;
    private String roleId;
    private Integer status;
    private String phone;
    private Date lastLoginAt;
    private String lastLoginIp;
}
```

```java
package org.leon.authmodule.controller;

import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.pojo.Users;
import org.leon.authmodule.pojo.vo.UserProfileVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供 UserModule 通过 Feign 反查。直接返回 VO，不套 Result：
 * Result.data 声明为 Object，Jackson 会反序列化成 LinkedHashMap。
 */
@RestController
@RequestMapping("/auth/internal")
public class InternalUserController {

    private final AuthMapper authMapper;

    public InternalUserController(AuthMapper authMapper) {
        this.authMapper = authMapper;
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<UserProfileVO> getUserProfile(@PathVariable Long userId) {
        Users users = authMapper.selectByUserId(userId);
        if (users == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new UserProfileVO(
                users.getId(), users.getUsername(), users.getRoleId(), users.getStatus(),
                users.getPhone(), users.getLast_login_at(), users.getLast_login_ip()));
    }
}
```

- [x] **Step 4: 运行，确认通过**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=InternalUserControllerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`Tests run: 2, Failures: 0`。

- [x] **Step 5: 提交**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/pojo/vo/UserProfileVO.java \
        AuthModule/src/main/java/org/leon/authmodule/controller/InternalUserController.java \
        AuthModule/src/test/java/org/leon/authmodule/controller/InternalUserControllerTest.java
git commit -m "新增供Feign反查的内部用户资料接口"
```

---

### Task 9: 内部接口的令牌校验与安全配置

**Files:**
- Create: `AuthModule/src/main/java/org/leon/authmodule/config/InternalApiAuthFilter.java`
- Modify: `AuthModule/src/main/java/org/leon/authmodule/config/SecurityConfig.java:31-36`
- Modify: `AuthModule/src/main/resources/application.yaml:62-67`
- Modify: `UserModule/src/main/resources/application.yaml:34-42`
- Test: `AuthModule/src/test/java/org/leon/authmodule/config/InternalApiAuthFilterTest.java`

**Interfaces:**
- Consumes: 配置项 `auth.internal.token`
- Produces: 请求头 `X-Internal-Token`；`/auth/internal/**` 在 Spring Security 中 `permitAll`，由过滤器校验

- [x] **Step 1: 写失败测试**

```java
package org.leon.authmodule.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalApiAuthFilterTest {

    private InternalApiAuthFilter filter() {
        InternalApiAuthFilter filter = new InternalApiAuthFilter();
        ReflectionTestUtils.setField(filter, "internalToken", "dev-internal-token");
        return filter;
    }

    @DisplayName("缺少或不匹配的令牌返回 401")
    @Test
    void shouldRejectWhenTokenMissingOrWrong() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/internal/users/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilterInternal(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @DisplayName("令牌正确时放行")
    @Test
    void shouldPassWithCorrectToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/internal/users/1");
        request.addHeader("X-Internal-Token", "dev-internal-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilterInternal(request, response, chain);

        assertTrue(chain.getRequest() != null, "放行后请求应继续进入后续过滤器链");
    }
}
```

- [x] **Step 2: 运行，确认失败**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=InternalApiAuthFilterTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`找不到符号: 类 InternalApiAuthFilter`。

- [x] **Step 3: 实现过滤器**

```java
package org.leon.authmodule.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * /auth/internal/** 不走 JWT（它是服务间调用），改校验共享令牌。
 * 端口是直接对进程外开放的，permitAll 之外必须加这层。
 */
@Component
public class InternalApiAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Token";

    @Value("${auth.internal.token:}")
    private String internalToken;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/auth/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (internalToken == null || internalToken.isBlank() || !matches(provided, internalToken)) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"code\":401,\"msg\":\"内部接口令牌无效\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String provided, String expected) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [x] **Step 4: 放开 Security 的 internal 路径**

在 `SecurityConfig.java:33-35` 的 `requestMatchers` 列表里，另起一行加：

```java
                        .requestMatchers("/auth/internal/**").permitAll()
```

（顺序放在 `/auth/login...` 那一行之后；Spring Security 按声明顺序匹配，`/auth/internal/**` 不会与现有路径冲突。）

- [x] **Step 5: 两个模块都加配置**

`AuthModule/src/main/resources/application.yaml` 末尾追加：

```yaml
auth:
  internal:
    token: "dev-internal-token"
```

（注意：该文件 `:62` 已有一个 `auth:` 根节点，必须把 `internal:` 合并进同一个 `auth:` 下，不要出现两个顶层 `auth:`。合并后形如：）

```yaml
auth:
  sms:
    code-ttl-ms: 300000
    queue-suffix: ""
  internal:
    token: "dev-internal-token"
```

`UserModule/src/main/resources/application.yaml` 同理，在 `:11` 的 `spring:` 之外新增（UserModule 目前没有 `auth:` 节点）：

```yaml
auth:
  internal:
    token: "dev-internal-token"
```

- [x] **Step 6: 运行过滤器测试 + 旧安全相关测试**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=InternalApiAuthFilterTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`Tests run: 2, Failures: 0`。

- [x] **Step 7: 手工验证 401**

重启 AuthModule，然后：

```bash
curl -i http://127.0.0.1:8103/auth/internal/users/1
```

预期：`HTTP/1.1 401`，body 为 `{"code":401,"msg":"内部接口令牌无效"}`。

```bash
curl -i -H "X-Internal-Token: dev-internal-token" http://127.0.0.1:8103/auth/internal/users/1
```

预期：`HTTP/1.1 200`，body 含 `roleId` 与 `phone`。

- [x] **Step 8: 提交**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/config/InternalApiAuthFilter.java \
        AuthModule/src/main/java/org/leon/authmodule/config/SecurityConfig.java \
        AuthModule/src/main/resources/application.yaml \
        UserModule/src/main/resources/application.yaml \
        AuthModule/src/test/java/org/leon/authmodule/config/InternalApiAuthFilterTest.java
git commit -m "内部接口改用共享令牌校验并放开Security拦截"
```

---

### Task 10: Feign 客户端接入与消费端反查

**Files:**
- Modify: `UserModule/src/main/java/org/leon/usermodule/UserModuleApplication.java:6`（加 `@EnableFeignClients`）
- Create: `UserModule/src/main/java/org/leon/usermodule/feign/AuthModuleClient.java`
- Create: `UserModule/src/main/java/org/leon/usermodule/feign/FeignInternalTokenConfig.java`
- Create: `UserModule/src/main/java/org/leon/usermodule/pojo/vo/UserProfileVO.java`
- Modify: `UserModule/src/main/java/org/leon/usermodule/pojo/vo/ProcessedLogin.java`（加 `profile` 字段）
- Modify: `UserModule/src/main/java/org/leon/usermodule/mq/LoginEventConsumer.java`
- Modify: `UserModule/src/main/java/org/leon/usermodule/config/RabbitMqDemoConfig.java:121-124`（转换器设 INFERRED）
- Test: `UserModule/src/test/java/org/leon/usermodule/mq/LoginEventConsumerTest.java`（改写）
- Test: `UserModule/src/test/java/org/leon/usermodule/feign/FeignInternalTokenConfigTest.java`

**Interfaces:**
- Consumes: `GET /auth/internal/users/{userId}`（Task 8）、`auth.internal.token`（Task 9）
- Produces: `AuthModuleClient.getUserProfile(Long)` → `UserProfileVO`；`ProcessedLogin(event, consumedAt, profile)`

- [x] **Step 1: 改写消费者测试（先失败）**

```java
package org.leon.usermodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.usermodule.feign.AuthModuleClient;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.leon.usermodule.pojo.vo.ProcessedLogin;
import org.leon.usermodule.pojo.vo.UserProfileVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginEventConsumerTest {

    private static LoginEvent event() {
        return new LoginEvent(10L, "test001", "trace-1",
                "203.0.113.7", 1_700_000_000_000L, "abcdef123456");
    }

    @DisplayName("收到事件后反查资料并写进环形队列")
    @Test
    void shouldEnrichWithFeignProfile() {
        RecentLoginStore store = new RecentLoginStore();
        AuthModuleClient client = mock(AuthModuleClient.class);
        when(client.getUserProfile(10L)).thenReturn(
                new UserProfileVO(10L, "test001", "2", 1, "13631155285", null, "203.0.113.7"));

        new LoginEventConsumer(store, client).onLoginEvent(event());

        verify(client).getUserProfile(10L);
        List<ProcessedLogin> recent = store.recent();
        assertEquals(1, recent.size());
        assertEquals("2", recent.get(0).getProfile().getRoleId());
        assertEquals("13631155285", recent.get(0).getProfile().getPhone());
    }
}
```

- [x] **Step 2: 运行，确认失败**

```bash
"$MVN" -o -pl UserModule -am test -Dtest=LoginEventConsumerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：编译失败（`AuthModuleClient`、`UserProfileVO`、`ProcessedLogin.getProfile()` 都不存在）。

- [x] **Step 3: 建 VO 与 Feign 客户端**

```java
package org.leon.usermodule.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** 与 AuthModule 的 UserProfileVO 字段一一对应；类不同名是刻意为之（数据契约）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileVO {
    private Long userId;
    private String username;
    private String roleId;
    private Integer status;
    private String phone;
    private Date lastLoginAt;
    private String lastLoginIp;
}
```

```java
package org.leon.usermodule.feign;

import org.leon.usermodule.pojo.vo.UserProfileVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "AuthModuleServer", path = "/auth/internal")
public interface AuthModuleClient {

    /** 用户不存在时抛 FeignException(status=404)，消费端据此走"不重试"分支。 */
    @GetMapping("/users/{userId}")
    UserProfileVO getUserProfile(@PathVariable("userId") Long userId);
}
```

```java
package org.leon.usermodule.feign;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 所有 Feign 调用统一带上内部令牌，业务代码不用关心。 */
@Configuration
public class FeignInternalTokenConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(
            @Value("${auth.internal.token}") String internalToken) {
        return template -> template.header("X-Internal-Token", internalToken);
    }
}
```

- [x] **Step 4: 打开 Feign 并改消费端**

`UserModuleApplication` 加注解：

```java
import org.springframework.cloud.openfeign.EnableFeignClients;

@ComponentScan(basePackages = {"org.leon.usermodule", "org.leon.commonjwt"})
@SpringBootApplication
@EnableFeignClients
public class UserModuleApplication {
```

`ProcessedLogin` 加字段：

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedLogin {
    private LoginEvent event;
    private long consumedAt;
    private UserProfileVO profile;
}
```

`LoginEventConsumer` 改为：

```java
    private final RecentLoginStore store;
    private final AuthModuleClient authModuleClient;

    public LoginEventConsumer(RecentLoginStore store, AuthModuleClient authModuleClient) {
        this.store = store;
        this.authModuleClient = authModuleClient;
    }

    @RabbitListener(queues = LoginEventQueueConfig.LOGIN_EVENT_QUEUE)
    public void onLoginEvent(LoginEvent event) {
        log.info("[登录事件消费者] traceId={} userId={} 开始反查资料", event.getTraceId(), event.getUserId());

        // 故意不 catch：反查失败必须外抛，交给重试和死信（Task 11 只对 404 例外）
        UserProfileVO profile = authModuleClient.getUserProfile(event.getUserId());

        log.info("[登录事件消费者] traceId={} userId={} roleId={} phone={}",
                event.getTraceId(), event.getUserId(), profile.getRoleId(), profile.getPhone());
        store.add(new ProcessedLogin(event, System.currentTimeMillis(), profile));
    }
```

同步更新 `RecentLoginStoreTest` 里的 `item(long)`：

```java
        return new ProcessedLogin(event, 1_700_000_000_001L,
                new UserProfileVO(userId, "u" + userId, "1", 1, "13800000000", null, "127.0.0.1"));
```

- [x] **Step 5: 转换器设 INFERRED**

> 已在第 2 段提前完成（提交 `29f4312`，见第 2 段验收记录），执行 Task 10 时跳过此步。

`RabbitMqDemoConfig.java:121-124` 改为：

```java
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        // 生产端的 __TypeId__ 是 org.leon.authmodule.pojo.mq.LoginSuccessEvent，
        // 本服务没有这个类。INFERRED 让监听方法参数类型成为权威，按字段名映射。
        converter.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }
```

补 import：

```java
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
```

- [x] **Step 6: 加拦截器测试**

```java
package org.leon.usermodule.feign;

import feign.RequestTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeignInternalTokenConfigTest {

    @DisplayName("拦截器给每个请求加上 X-Internal-Token")
    @Test
    void shouldAddInternalTokenHeader() {
        RequestTemplate template = new RequestTemplate();
        new FeignInternalTokenConfig().internalTokenInterceptor("dev-internal-token").apply(template);

        Collection<String> values = template.headers().get("X-Internal-Token");
        assertTrue(values != null && values.contains("dev-internal-token"));
    }
}
```

- [x] **Step 7: 运行 UserModule 全部相关测试**

```bash
"$MVN" -o -pl UserModule -am test -Dtest='LoginEventConsumerTest,RecentLoginStoreTest,FeignInternalTokenConfigTest,RabbitMqDemoTest' \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：全部通过。`RabbitMqDemoTest` 用于确认 `INFERRED` 没有破坏原有的 demo 链路（它自己也走同一个转换器）。该测试需要 Broker 在线。

- [x] **Step 8: 端到端手工验证**

两个服务都启动后：

```
GET http://127.0.0.1:8103/auth/demo/login-event?userId=1
GET http://127.0.0.1:8101/user/mq/login/recent
```

预期：第二个返回的 `profile.roleId`、`profile.phone` 有值；UserModule 日志出现 `roleId=`。若返回 401，检查两处 `auth.internal.token` 是否一致；若反序列化报错，说明 `INFERRED` 没生效，改用 spec §7.5 的 `setIdClassMapping` 备选。

- [x] **Step 9: 提交**

```bash
git add UserModule/src/main/java/org/leon/usermodule/UserModuleApplication.java \
        UserModule/src/main/java/org/leon/usermodule/feign/ \
        UserModule/src/main/java/org/leon/usermodule/pojo/vo/ \
        UserModule/src/main/java/org/leon/usermodule/mq/LoginEventConsumer.java \
        UserModule/src/main/java/org/leon/usermodule/config/RabbitMqDemoConfig.java \
        UserModule/src/test/java/org/leon/usermodule/mq/ \
        UserModule/src/test/java/org/leon/usermodule/feign/
git commit -m "消费端通过Feign反查用户资料并支持跨服务消息类型"
```

#### 第 3 段验收记录（2026-10-21，Task 8–10 已完成）

- 测试全绿：Task 8 2 个、Task 9 2 个、Task 10 6 个（含 `RabbitMqDemoTest` 2 个，确认 Feign 开启后旧 demo 链路仍正常）。
- 内部接口手工验证：无令牌 → `401 {"code":401,"msg":"内部接口令牌无效"}`；带 `X-Internal-Token: dev-internal-token` → `200`，返回裸 JSON（`roleId=0`）。
- Feign 真实链路：两应用启动 → 触发 `userId=1` → UserModule 日志先打 `开始反查资料`，再打 `roleId=0 phone=null` → `/user/mq/login/recent` 的 `profile` 完整。**本地 `users_dev` 所有用户 phone 均为 null**，手机号透传以 `LoginEventConsumerTest` 的 13631155285 断言为准。
- 计划偏差：`InternalUserController` 增加 `produces = application/json`。classpath 存在 Jackson XML，standalone MockMvc 无 Accept 头时默认选中 XML；显式 JSON 同时保证 Feign 与浏览器拿到的都是 JSON。
- Task 10 Step 5（`INFERRED`）已在第 2 段提前完成（`29f4312`），本段未重复提交。
- 提交：`8ea1b5e`（Task 8）、`0c1c81e`（Task 9）、`f0c9f3e`（Task 10）。
- 本段未覆盖：404 → 死信、重试/死信配置（Task 11）、E2E 自动化（Task 12）。

---

### Task 11: 失败路径——重试与死信分流

**Files:**
- Modify: `UserModule/src/main/resources/application.yaml:40-42`（listener.simple 内追加重试）
- Modify: `UserModule/src/main/java/org/leon/usermodule/mq/LoginEventConsumer.java`
- Test: `UserModule/src/test/java/org/leon/usermodule/mq/LoginEventConsumerFailureTest.java`

**Interfaces:**
- Consumes: `FeignException.status()`
- Produces: 404 → 直接 `AmqpRejectAndDontRequeueException`；其它异常原样上抛

- [x] **Step 1: 写失败测试**

```java
package org.leon.usermodule.mq;

import feign.FeignException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.usermodule.feign.AuthModuleClient;
import org.leon.usermodule.pojo.mq.LoginEvent;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginEventConsumerFailureTest {

    private static LoginEvent event() {
        return new LoginEvent(999999L, "ghost", "trace-404",
                "127.0.0.1", 1_700_000_000_000L, "abcdef123456");
    }

    @DisplayName("反查 404 时立即拒绝进死信，不浪费重试")
    @Test
    void shouldRejectImmediatelyOnNotFound() {
        AuthModuleClient client = mock(AuthModuleClient.class);
        FeignException notFound = mock(FeignException.class);
        when(notFound.status()).thenReturn(404);
        when(client.getUserProfile(999999L)).thenThrow(notFound);

        LoginEventConsumer consumer = new LoginEventConsumer(new RecentLoginStore(), client);

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.onLoginEvent(event()));
    }

    @DisplayName("反查 500 时原样抛出，交给重试")
    @Test
    void shouldRethrowForRetryableFailure() {
        AuthModuleClient client = mock(AuthModuleClient.class);
        FeignException serverError = mock(FeignException.class);
        when(serverError.status()).thenReturn(500);
        when(client.getUserProfile(999999L)).thenThrow(serverError);

        LoginEventConsumer consumer = new LoginEventConsumer(new RecentLoginStore(), client);

        FeignException thrown = assertThrows(FeignException.class,
                () -> consumer.onLoginEvent(event()));
        org.junit.jupiter.api.Assertions.assertEquals(500, thrown.status());
    }
}
```

- [x] **Step 2: 运行，确认失败**

```bash
"$MVN" -o -pl UserModule -am test -Dtest=LoginEventConsumerFailureTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：第一个用例失败——当前抛的是 `FeignException`，不是 `AmqpRejectAndDontRequeueException`。

- [x] **Step 3: 消费者加 404 分支**

```java
        UserProfileVO profile;
        try {
            profile = authModuleClient.getUserProfile(event.getUserId());
        } catch (FeignException e) {
            if (e.status() == 404) {
                // 用户不存在：重试一百次也不会出现，直接进死信
                throw new AmqpRejectAndDontRequeueException(
                        "用户不存在，不重试: " + event.getUserId(), e);
            }
            throw e;
        }
```

补 import：`feign.FeignException`、`org.springframework.amqp.AmqpRejectAndDontRequeueException`。

- [x] **Step 4: 加 listener 重试配置**

`UserModule/application.yaml` 的 `listener.simple` 块改为：

```yaml
    listener:
      simple:
        prefetch: 1   # 预取值，即一次只处理一个消息
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 2000
          multiplier: 2
          max-interval: 10000
        default-requeue-rejected: false
```

- [x] **Step 5: 运行，确认通过**

```bash
"$MVN" -o -pl UserModule -am test -Dtest='LoginEventConsumerFailureTest,LoginEventConsumerTest' \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`Tests run: 3, Failures: 0`。

- [x] **Step 6: 手工验证死信**

两个服务都启动，访问：

```
http://127.0.0.1:8103/auth/demo/login-event?userId=999999
```

预期：触发端点返回"用户不存在，未发布事件"——这个端点自己就把不存在的用户挡住了，走不到 MQ。所以死信路径必须绕过端点，**直接从管理 API 往交换机投一条 userId=999999 的消息**：

```bash
curl -u guest:guest -H "Content-Type: application/json" -X POST \
  http://127.0.0.1:15672/api/exchanges/%2F/auth.login.exchange/publish \
  -d '{"properties":{"content_type":"application/json"},"routing_key":"user.login.success","payload":"{\"userId\":999999,\"username\":\"ghost\",\"traceId\":\"manual-404\",\"loginIp\":\"127.0.0.1\",\"loginAt\":1700000000000,\"tokenFingerprint\":\"abcdef123456\"}","payload_encoding":"string"}'
```

预期：UserModule 日志 1 次 `[登录事件消费者]` + 1 次 `[登录死信]`（404 不重试）；管理台 `user.login.event.dead.queue` 深度 +1。

- [x] **Step 7: 提交**

```bash
git add UserModule/src/main/resources/application.yaml \
        UserModule/src/main/java/org/leon/usermodule/mq/LoginEventConsumer.java \
        UserModule/src/test/java/org/leon/usermodule/mq/LoginEventConsumerFailureTest.java
git commit -m "登录事件消费失败改为重试三次后进死信，404直接拒绝"
```

---

### Task 12: E2E 与对比实验

**Files:**
- Create: `UserModule/src/test/java/org/leon/usermodule/mq/ForeignLoginEvent.java`（测试专用，模拟"别的服务的类"）
- Create: `UserModule/src/test/java/org/leon/usermodule/mq/LoginEventFlowE2ETest.java`
- Create: `docs/superpowers/specs/2026-10-19-login-feign-mq-experiments.md`

**Interfaces:**
- Consumes: Broker、`RecentLoginStore`、`LoginEventQueueConfig`
- Produces: 可重复执行的三个实验记录

- [x] **Step 1: 写类型头的 E2E 断言（先失败或先通过都要记录）**

```java
package org.leon.usermodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.leon.usermodule.config.LoginEventQueueConfig;
import org.leon.usermodule.feign.AuthModuleClient;
import org.leon.usermodule.pojo.vo.UserProfileVO;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * 关键断言：消息的 __TypeId__ 指向 ForeignLoginEvent（本服务里另一个类），
 * 消费端仍能按字段名反序列化成 LoginEvent —— 证明 INFERRED 生效。
 * Feign 用 @MockBean 替身，本测试只验证 MQ 与反序列化，不要求 AuthModule 在线。
 */
@SpringBootTest
class LoginEventFlowE2ETest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RecentLoginStore store;

    @MockBean
    private AuthModuleClient authModuleClient;

    @Test
    @DisplayName("异构类型头也能被消费端识别，且完成 Feign 反查")
    void shouldConsumeForeignTypedMessageAndEnrich() throws Exception {
        when(authModuleClient.getUserProfile(10L)).thenReturn(
                new UserProfileVO(10L, "test001", "2", 1, "13631155285", null, "127.0.0.1"));

        rabbitTemplate.convertAndSend(LoginEventQueueConfig.LOGIN_EXCHANGE,
                LoginEventQueueConfig.LOGIN_SUCCESS_KEY,
                new ForeignLoginEvent(10L, "test001", "e2e-1", "127.0.0.1",
                        1_700_000_000_000L, "abcdef123456"));

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && store.recent().isEmpty()) {
            TimeUnit.MILLISECONDS.sleep(200);
        }

        assertEquals(1, store.recent().size(), "10 秒内没消费到，检查类型头或队列绑定");
        assertNotNull(store.recent().get(0).getProfile());
        assertEquals("e2e-1", store.recent().get(0).getEvent().getTraceId());
    }
}
```

```java
package org.leon.usermodule.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 仅用于测试：模拟另一个服务里"字段相同、类名不同"的事件类。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ForeignLoginEvent {
    private Long userId;
    private String username;
    private String traceId;
    private String loginIp;
    private long loginAt;
    private String tokenFingerprint;
}
```

- [x] **Step 2: 运行**

```bash
"$MVN" -o -pl UserModule -am test -Dtest=LoginEventFlowE2ETest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：通过。若报 `Could not convert` 或参数类型不匹配，把 `RabbitMqDemoConfig` 的转换器改用 spec §7.5 的 `setIdClassMapping` 备选并重跑。

- [x] **Step 3: 跑完整回归**

```bash
"$MVN" -o -pl AuthModule,UserModule -am test \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：两个模块全部测试通过（这个命令会跑 CommonJwt 与既有 MQ/Redis/seata 相关测试，需要 Nacos、MySQL、RabbitMQ、Redis 在线）。

> spec §10.3 的第三条"MQ 断开时端点仍返回 200"，由 Task 4 的 `AuthServiceImplLoginEventTest.shouldStillSucceedWhenPublishFails` 覆盖（在单元层面验证 publisher 抛异常不影响返回值），不再单独起一个把 `spring.rabbitmq.port` 指错的 Spring 上下文——那样会污染其他 `@SpringBootTest` 的连接配置。

- [x] **Step 4: 执行并记录三个对比实验**

新建 `docs/superpowers/specs/2026-10-19-login-feign-mq-experiments.md`，按 spec §10.2 逐条记录（每条：操作、观察值、结论）：

1. **正常链路**：`/auth/demo/login-event?userId=1` → `/user/mq/login/recent` 有数据，两个队列深度 0。
2. **实验 B（下游故障不传染）**：把 `AuthModuleClient` 的 `name` 改成 `AuthModuleServerX`，重启 UserModule，触发事件 → UserModule 日志出现 3 次失败（间隔 2s/4s/8s）+ `[登录死信]`；AuthModule 的触发端点仍返回 200。
3. **实验 C（堆积不丢）**：停 UserModule → 用 Task 11 Step 6 的 curl 向交换机投 5 条 → 管理台主队列深度 5 → 启动 UserModule → 深度归 0。

**实验 B 结束后必须把 `name` 改回 `AuthModuleServer` 并重新编译**，这是最容易忘的一步。

- [x] **Step 5: 提交**

```bash
git add UserModule/src/test/java/org/leon/usermodule/mq/ForeignLoginEvent.java \
        UserModule/src/test/java/org/leon/usermodule/mq/LoginEventFlowE2ETest.java \
        docs/superpowers/specs/2026-10-19-login-feign-mq-experiments.md
git commit -m "补充登录链路E2E测试和对比实验记录"
```

---

## 收尾检查

- [x] `grep -rn "jacksonMessageConverter" AuthModule UserModule` 无输出（重复 Bean 已彻底清除）。
- [x] `grep -rn "AuthRedisService" UserModule` 无输出（跨服务引用已清除）。
- [x] `AuthModule` 与 `UserModule` 各只有一个 `MessageConverter` bean。
- [x] `application.yaml` 中两处 `auth.internal.token` 值一致。
- [x] 管理台上四条队列都存在：`user.login.event.queue`、`user.login.event.dead.queue`，以及原有的 `demo.*` / `auth.sms.*`。

#### 第 4 段验收记录（2026-10-21，Task 11–12 已完成）

- Task 11 测试：`LoginEventConsumerFailureTest` 2 个（404 → `AmqpRejectAndDontRequeueException`、500 → 原样抛出）通过；正常链路复测通过。
- 404 实测：直接向交换机投 `userId=999999`，**637ms** 进死信。过程中发现 `AmqpRejectAndDontRequeueException` 默认仍被 Spring Retry 重试 3 次（约 6.7s），新增 `UserModule/config/LoginRetryConfig.java`（`RabbitRetryTemplateCustomizer` + `SimpleRetryPolicy` 开启 `traverseCauses`）后才真正跳过退避。
- Task 12：`LoginEventFlowE2ETest` 通过（`ForeignLoginEvent` 的 `__TypeId__` 仍能按 `LoginEvent` 反序列化）；全量回归 **AuthModule 52 + UserModule 12，0 失败**。
- 实验 B：Feign 名改错 → 3 次尝试（实测间隔约 3.6s / 4.0s）→ 死信；AuthModule 触发端点两次均返回 200；结束后已恢复服务名并重新打包。
- 实验 C：停 UserModule 投 5 条 → 主队列深度 5 → 启动消费者 → 5 条全部处理、深度归 0、`recent` 返回 5 条；AuthModule 下线时 UserModule 仍启动成功。原计划的启动顺序会让在线消费者把堆积消息重试进死信，已修正并记录。
- 测试隔离：E2E 与 `RabbitMqDemoTest` 的缓存上下文会争抢同一批队列，两个类加 `@DirtiesContext(AFTER_CLASS)`；关闭上下文后 Seata 静态状态报错，故两个 MQ 全上下文测试加 `seata.enabled=false`。
- 实验记录：`docs/superpowers/specs/2026-10-19-login-feign-mq-experiments.md`。
- 提交：`b78d038`（Task 11）、`bfd295c`（Task 12）。
- 收尾检查 5 项全部通过。
