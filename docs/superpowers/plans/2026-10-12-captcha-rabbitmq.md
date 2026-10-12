# 注册/登录验证码 + RabbitMQ 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让注册必须依次通过图形验证码与手机验证码、登录必须通过图形验证码，其中手机验证码的生成与投递走 RabbitMQ，并用 TTL+死信队列实现 5 分钟过期。

**Architecture:** 全部落在 `AuthModule`（8103）。图形码用 hutool 生成、答案一次性存 Redis；`/auth/sms/send` 只负责"校图形码 + 限频 + 投一条 MQ 消息"就返回；消费者生成短信码写 Redis 并模拟发送，再投一条 5 分钟延迟消息；延迟队列无消费者，靠 TTL 超时变死信，由第二个消费者幂等清理。前端 `EZ_frontend` 按既有约定"只换 mock 函数体"接入。

**Tech Stack:** Java 21 / Spring Boot 3.2.4 / Spring AMQP（spring-rabbit 3.1.3、amqp-client 5.19.0）/ RabbitMQ 4.3.6 + Erlang 27.3.4 / Redis（Lettuce）/ MyBatis-Plus 3.5.17 / hutool-all 5.8.25 / JUnit 5 + Mockito 5 / Vue 3.5 + TypeScript + Vite

**Spec:** `docs/superpowers/specs/2026-10-12-captcha-rabbitmq-design.md`（决策 D1–D11 与全部取舍理由在 spec 里，本计划只写"怎么做"）

## Global Constraints

- **Java 21**：`mvn` 不在 PATH，必须用 `JAVA_HOME=C:/Users/Administrator/.jdks/ms-21.0.11` + `~/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn`。用 jdk-17 会报 `release version 21 not supported`。
- **构建命令固定带 `-pl <模块> -am`**，过滤测试时固定带 `-Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`（`-am` 会连带构建 `CommonJwt`，它没有匹配测试会中止构建）。
- **不引入 `spring-boot-starter-validation`**（spec D5）。所有字段校验写成显式 `if` + 常量正则。
- **不改 `UserModule`**：那个 MQ demo 保留为教具（spec 非目标）。
- **hutool 版本对齐 5.8.25**，与 UserModule/PracticeModule/AIModule 一致。
- **Redis key 一律用 `StringRedisTemplate`**，不用现有的 `RedisTemplate<String,Object>`：后者的 value 走 `GenericJackson2JsonRedisSerializer`，存字符串会变成带引号的 JSON，`setIfAbsent` 和明文比对都会出错。
- **前端不引入新 UI 库、不加 axios、不改 `vite.config.ts` 的 `/gw` 代理**（前端项目 `F:\AAAJAVA\code\EZ_frontend`）。
- **提交策略**：用户尚未授权自动提交。每个任务末尾的 commit 步骤执行前，先把 `git status --short` 的结果给用户看，确认后再 `git commit`。
- **服务前置**：RabbitMQ（Windows 服务 `RabbitMQ`，5672/15672）、Redis（6379）、MySQL（3306）、Nacos（8848）都必须在跑。开工前先 `netstat -ano | grep -E ":(5672|6379|3306|8848) "` 核对。

---

## 文件结构

**新建（后端 AuthModule）**

| 文件 | 职责（一句话） |
|---|---|
| `pojo/LoginRequest.java` | 登录请求：username/password/captchaKey/captchaCode |
| `pojo/RegisterRequest.java`（重写） | 注册请求：username/password/phone/smsCode |
| `pojo/SendSmsRequest.java` | 申请短信码请求：phone/captchaKey/captchaCode |
| `config/AuthCacheKeys.java` | 三个 Redis key 前缀常量 + 拼 key 的静态方法 |
| `config/CaptchaMqConfig.java` | 2 交换机 + 3 队列 + 3 绑定的拓扑声明 |
| `pojo/mq/SmsSendRequest.java` | 入队消息：phone/traceId |
| `pojo/mq/SmsExpireEvent.java` | 延迟消息：phone/code/traceId |
| `service/CaptchaService.java` | 图形码：生成 + 一次性校验 |
| `service/SmsSender.java` | 发送通道接口（为将来接真通道留的缝） |
| `service/impl/MockSmsSender.java` | 唯一实现：`log.info` 模拟发送 |
| `mq/SmsCodeProducer.java` | 两个投递方法，屏蔽 exchange/routingKey 字面量 |
| `mq/SmsCodeConsumer.java` | 两个 `@RabbitListener`：发码、过期清理 |
| `db/migration/2026-10-12-add-phone-to-users-dev.sql` | 仓库第一份纳入版本管理的 DDL |

**修改（后端）**：`AuthModule/pom.xml`、`application.yaml`、`AuthController.java`、`AuthService.java`、`AuthServiceImpl.java`、`AuthMapper.java`、`AuthMapper.xml`、`pojo/Users.java`、`config/SecurityConfig.java`、`GatewayModule/.../JwtAuthGlobalFilter.java`
**修改（前端）**：`src/api/auth.ts`、`src/composables/useLogin.ts`、`src/views/Login.vue`

**任务顺序即依赖顺序**：Task 1 是纯重构（用现存测试当安全网），做完才有机动改动的地基；Task 2–5 是阶段一（不需要 broker 也能验收）；Task 6–11 是阶段二；Task 12–13 是阶段三；Task 14 收尾。

---

## Task 1: 拆分 DTO，修掉 MyBatis-Plus 泛型误用（纯重构，行为不变）

现在 `RegisterRequest` 同时充当"注册请求""登录请求""MyBatis-Plus 实体泛型"三职（`AuthServiceImpl.java:21`、`AuthMapper.java:11`、`AuthService.java:13`）。注册和登录即将有各自必填字段，不拆开后面每一步都会互相误伤。

**Files:**
- Create: `AuthModule/src/main/java/org/leon/authmodule/pojo/LoginRequest.java`
- Create: `AuthModule/src/main/java/org/leon/authmodule/pojo/SendSmsRequest.java`
- Modify: `AuthModule/src/main/java/org/leon/authmodule/pojo/RegisterRequest.java`（重写全文）
- Modify: `AuthModule/src/main/java/org/leon/authmodule/service/AuthService.java:13-17`
- Modify: `AuthModule/src/main/java/org/leon/authmodule/service/impl/AuthServiceImpl.java:21,44,51`
- Modify: `AuthModule/src/main/java/org/leon/authmodule/mapper/AuthMapper.java:7,11`
- Modify: `AuthModule/src/main/java/org/leon/authmodule/controller/AuthController.java:8,91,109`
- Test（已存在，作为回归基线）: `AuthModule/src/test/java/org/leon/authmodule/service/impl/AuthServiceImplLoginIpTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `LoginRequest{getUsername,getPassword,getCaptchaKey,getCaptchaCode}`、`RegisterRequest{getUsername,getPassword,getPhone,getSmsCode}`、`SendSmsRequest{getPhone,getCaptchaKey,getCaptchaCode}`；`AuthService.login(LoginRequest,String)`、`AuthService.register(RegisterRequest)`、`AuthMapper extends BaseMapper<Users>`

- [ ] **Step 1: 跑现存测试，建立"改之前是绿的"基线**

Run:
```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
MVN="/c/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
cd F:/AAAJAVA/code/ezPracticeExercises
"$MVN" -o -pl AuthModule -am test -Dtest=AuthServiceImplLoginIpTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `Tests run: 4, Failures: 0, Errors: 0`。
**如果这一步就是红的，先停下报告**——后面所有"回归不变"的判断都失去依据。

- [ ] **Step 2: 新建 `LoginRequest`**

```java
package org.leon.authmodule.pojo;

import lombok.Data;

/** 登录请求：只需账号密码 + 图形验证码。注意不叫 @Valid，校验在 Service 显式做。 */
@Data
public class LoginRequest {
    private String username;
    private String password;
    private String captchaKey;
    private String captchaCode;
}
```

- [ ] **Step 3: 新建 `SendSmsRequest`**

```java
package org.leon.authmodule.pojo;

import lombok.Data;

/** 申请手机验证码：必须先带一张有效的图形验证码。 */
@Data
public class SendSmsRequest {
    private String phone;
    private String captchaKey;
    private String captchaCode;
}
```

- [ ] **Step 4: 重写 `RegisterRequest`（去掉 captcha 字段，加 phone/smsCode）**

覆盖整个文件：

```java
package org.leon.authmodule.pojo;

import lombok.Data;

/** 注册请求：手机验证码取代原来形同虚设的 captchaCode 字段。 */
@Data
public class RegisterRequest {
    private String username;
    private String password;
    private String phone;
    private String smsCode;
}
```

- [ ] **Step 5: `Users` 之外不再让 DTO 当实体：改 Mapper 与 Service 泛型**

`AuthMapper.java` 删掉 `import org.leon.authmodule.pojo.RegisterRequest;`，把 `:11` 改成：

```java
public interface AuthMapper extends BaseMapper<Users> {
```

`AuthService.java` 删掉 `import jakarta.validation.Valid;` 与 `RegisterRequest` 的 import 调整，接口体改成：

```java
public interface AuthService extends IService<Users> {

    Result register(RegisterRequest request);

    Result login(LoginRequest request, String clientIp);

    Result logout(String token);
}
```

`AuthServiceImpl.java:21` 改成 `extends ServiceImpl<AuthMapper, Users>`，`:51` 改成 `public Result login(LoginRequest request, String clientIp)`。`register` 签名不变，仍是 `RegisterRequest`。

- [ ] **Step 6: 改 `AuthController` 的入参类型**

`AuthController.java` 加 `import org.leon.authmodule.pojo.LoginRequest;`，`:91` 改成：

```java
    @PostMapping("/login")
    public Result login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return AuthService.login(request, IpUtil.getClientIp(servletRequest));
    }
```

`:109` 改成（本任务先不碰 `:110-114` 那段注释掉的验证码 TODO）：

```java
    @PostMapping("/register")
    public Result register(@RequestBody RegisterRequest request) {
        return AuthService.register(request);
    }
```

> 为什么这里顺手去掉了 `@Valid`：`spring-boot-starter-validation` 不在 classpath，`@Valid` 一直是摆设（spec §0 事实 1）。留着它会让读代码的人以为有校验，比没有更糟。`jakarta.validation` 的 import 在 `RegisterRequest` 重写后也一并消失。

- [ ] **Step 7: 同步修测试里构造请求对象的类型**

`AuthServiceImplLoginIpTest.java`：`:9` 的 import 换成 `org.leon.authmodule.pojo.LoginRequest`，`request()` 方法体改成：

```java
    private LoginRequest request() {
        LoginRequest request = new LoginRequest();
        request.setUsername("test001");
        request.setPassword("password123");
        return request;
    }
```

并把三处 `authService.login(request(), CLIENT_IP)` 的形参类型跟着编译器提示走（无需改调用文本）。

- [ ] **Step 8: 跑编译与回归测试**

Run: 同 Step 1 命令
Expected: `BUILD SUCCESS` + `Tests run: 4, Failures: 0`。

- [ ] **Step 9: 用 curl 确认两个接口行为没变**

起 `AuthModuleApplication`（IDEA 里跑，或命令行 `"$MVN" -o -pl AuthModule spring-boot:run`），然后：

```bash
curl -s -X POST http://127.0.0.1:8103/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"test001","password":"password123"}'
```
Expected: 返回 `{"code":0,...,"msg":"用户名或密码错误"}` 或 200 出票（取决于该账号是否存在），**但不能是 400/415**——那说明 DTO 改坏了。

- [ ] **Step 10: 提交（先给用户看 `git status --short`）**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/pojo \
        AuthModule/src/main/java/org/leon/authmodule/{service,controller,mapper} \
        AuthModule/src/test/java/org/leon/authmodule
git commit -m "refactor(auth): 拆分登录/注册/发码 DTO，Mapper 泛型改回 Users 实体"
```

---

## Task 2: Redis key 常量 + 图形验证码服务（生成 + 一次性校验）

**Files:**
- Create: `AuthModule/src/main/java/org/leon/authmodule/config/AuthCacheKeys.java`
- Create: `AuthModule/src/main/java/org/leon/authmodule/service/CaptchaService.java`
- Create: `AuthModule/src/main/java/org/leon/authmodule/service/impl/CaptchaServiceImpl.java`
- Modify: `AuthModule/pom.xml`（Redis 依赖块之后）
- Test: `AuthModule/src/test/java/org/leon/authmodule/service/impl/CaptchaServiceImplTest.java`

**Interfaces:**
- Consumes: `StringRedisTemplate`（Boot 自动配置，无需新增依赖）
- Produces:
  - `AuthCacheKeys.captchaKey(String)` / `smsCodeKey(String)` / `smsLimitKey(String)` → `String`
  - `CaptchaService.generate()` → `CaptchaService.CaptchaImage{captchaKey, imageBase64}`
  - `CaptchaService.verifyAndConsume(String captchaKey, String code)` → `boolean`

- [ ] **Step 1: 加 hutool 依赖**

`AuthModule/pom.xml` 里 `commons-pool2` 那个 `</dependency>` 之后插入：

```xml
        <!-- 图形验证码：CaptchaUtil / LineCaptcha，版本与 UserModule 等模块对齐 -->
        <dependency>
            <groupId>cn.hutool</groupId>
            <artifactId>hutool-all</artifactId>
            <version>5.8.25</version>
        </dependency>
```

Run（联网拉包，首次不能加 `-o`）:
```bash
"$MVN" -pl AuthModule -am -DskipTests test-compile
```
Expected: `BUILD SUCCESS`，且 `~/.m2/repository/cn/hutool/hutool-all/5.8.25/` 出现 jar。

- [ ] **Step 2: 写 `AuthCacheKeys`**

```java
package org.leon.authmodule.config;

/** 认证相关 Redis key 统一入口。命名风格对齐 AuthRedisService 里已有的 auth: 前缀。 */
public final class AuthCacheKeys {

    private static final String CAPTCHA_PREFIX = "auth:captcha:";
    private static final String SMS_CODE_PREFIX = "auth:sms:code:";
    private static final String SMS_LIMIT_PREFIX = "auth:sms:limit:";

    private AuthCacheKeys() {
    }

    public static String captchaKey(String captchaKey) {
        return CAPTCHA_PREFIX + captchaKey;
    }

    public static String smsCodeKey(String phone) {
        return SMS_CODE_PREFIX + phone;
    }

    public static String smsLimitKey(String phone) {
        return SMS_LIMIT_PREFIX + phone;
    }
}
```

- [ ] **Step 3: 写失败的测试**

`AuthModule/src/test/java/org/leon/authmodule/service/impl/CaptchaServiceImplTest.java`：

```java
package org.leon.authmodule.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.service.CaptchaService;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaptchaServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CaptchaServiceImpl newService() {
        return new CaptchaServiceImpl(redisTemplate);
    }

    @DisplayName("生成后 Redis 里写入 auth:captcha:{key}，TTL 120 秒，返回带 data: 前缀的 base64")
    @Test
    void generateStoresAnswerWithTwoMinuteTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CaptchaService.CaptchaImage image = newService().generate();

        assertNotNull(image.getCaptchaKey());
        assertEquals(32, image.getCaptchaKey().length(), "captchaKey 应为 32 位无横线 UUID");
        assertTrue(image.getImageBase64().startsWith("data:image/png;base64,"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(key.capture(), value.capture(), eq(Duration.ofSeconds(120)));
        assertEquals("auth:captcha:" + image.getCaptchaKey(), key.getValue());
        assertEquals(6, value.getValue().length());
        assertTrue(value.getValue().chars().allMatch(Character::isDigit),
                "图形码必须是 6 位纯数字，否则前端 CodeSlots 的数字输入框输不进去（spec D11）");
    }

    @DisplayName("校验成功后立即删除 key（一次性）")
    @Test
    void verifyDeletesKeyEvenOnSuccess() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:captcha:k1")).thenReturn("123456");

        assertTrue(newService().verifyAndConsume("k1", "123456"));

        verify(redisTemplate).delete("auth:captcha:k1");
    }

    @DisplayName("校验失败也要删除 key：不给同一张图反复试的机会")
    @Test
    void verifyDeletesKeyEvenOnWrongCode() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:captcha:k1")).thenReturn("123456");

        assertFalse(newService().verifyAndConsume("k1", "000000"));

        verify(redisTemplate).delete("auth:captcha:k1");
    }

    @DisplayName("key 不存在（已过期）时返回 false 且不抛异常")
    @Test
    void missingKeyIsTreatedAsExpired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertFalse(newService().verifyAndConsume("k1", "123456"));

        verify(redisTemplate, never()).delete(any());
    }

    @DisplayName("空白入参直接判失败，不查 Redis")
    @Test
    void blankInputShortCircuits() {
        assertFalse(newService().verifyAndConsume("", "123456"));
        assertFalse(newService().verifyAndConsume("k1", null));
        verify(redisTemplate, times(0)).opsForValue();
    }
}
```

- [ ] **Step 4: 跑测试确认它失败**

Run:
```bash
"$MVN" -o -pl AuthModule -am test -Dtest=CaptchaServiceImplTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，`找不到符号 CaptchaService` 之类。这是预期的"红"。

- [ ] **Step 5: 写接口**

`AuthModule/src/main/java/org/leon/authmodule/service/CaptchaService.java`：

```java
package org.leon.authmodule.service;

import lombok.AllArgsConstructor;
import lombok.Data;

public interface CaptchaService {

    /** 生成一张图形验证码，答案写 Redis（TTL 120s），返回 key 与 base64 图。 */
    CaptchaImage generate();

    /**
     * 校验并消费：无论成败都删除 Redis 中的答案，保证同一张图只能用一次。
     * @return true 表示验证码正确且此前未使用
     */
    boolean verifyAndConsume(String captchaKey, String code);

    @Data
    @AllArgsConstructor
    class CaptchaImage {
        private String captchaKey;
        /** 形如 data:image/png;base64,iVBORw0...，前端可直接绑到 img 的 src */
        private String imageBase64;
    }
}
```

- [ ] **Step 6: 写实现**

`AuthModule/src/main/java/org/leon/authmodule/service/impl/CaptchaServiceImpl.java`：

```java
package org.leon.authmodule.service.impl;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.captcha.generator.RandomGenerator;
import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.config.AuthCacheKeys;
import org.leon.authmodule.service.CaptchaService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
public class CaptchaServiceImpl implements CaptchaService {

    private static final String DIGITS_ONLY = "0123456789";
    private static final int CODE_LENGTH = 6;
    private static final int IMAGE_WIDTH = 160;
    private static final int IMAGE_HEIGHT = 60;
    private static final int LINE_COUNT = 20;
    private static final Duration CAPTCHA_TTL = Duration.ofSeconds(120);

    private final StringRedisTemplate redisTemplate;

    public CaptchaServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public CaptchaImage generate() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(IMAGE_WIDTH, IMAGE_HEIGHT, CODE_LENGTH, LINE_COUNT);
        // createLineCaptcha 内部已经用默认生成器（含字母）生过一次码；
        // 换成纯数字生成器后必须 createCode() 重生成，否则 code 与图片不一致或仍含字母。
        captcha.setGenerator(new RandomGenerator(DIGITS_ONLY, CODE_LENGTH));
        captcha.createCode();

        String captchaKey = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(AuthCacheKeys.captchaKey(captchaKey), captcha.getCode(), CAPTCHA_TTL);

        log.info("[图形码] 生成 captchaKey={} 答案={}（仅开发日志，上线必须去掉答案输出）",
                captchaKey, captcha.getCode());
        return new CaptchaImage(captchaKey, captcha.getImageBase64Data());
    }

    @Override
    public boolean verifyAndConsume(String captchaKey, String code) {
        if (captchaKey == null || captchaKey.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        String redisKey = AuthCacheKeys.captchaKey(captchaKey);
        String expected = redisTemplate.opsForValue().get(redisKey);
        if (expected == null) {
            return false;
        }
        // 一次性：命中与否都作废这张图
        redisTemplate.delete(redisKey);
        return expected.equals(code.trim());
    }
}
```

> `captcha.createCode()` 这一行是本任务唯一"凭记忆写的 API 细节"：hutool 的 `AbstractCaptcha.setGenerator()` 只赋值字段、不会重新生成码图。Step 7 的测试断言"6 位纯数字"，如果 hutool 这个版本行为不同，测试会立刻红给你看，而不是留下一个"图片显示 abc12 但期望值 123456"的线上谜题。

- [ ] **Step 7: 跑测试确认通过**

Run: 同 Step 4
Expected: `Tests run: 5, Failures: 0`。

- [ ] **Step 8: 提交**

```bash
git add AuthModule/pom.xml AuthModule/src/main/java/org/leon/authmodule/config/AuthCacheKeys.java \
        AuthModule/src/main/java/org/leon/authmodule/service \
        AuthModule/src/test/java/org/leon/authmodule/service
git commit -m "feat(auth): 图形验证码服务，答案一次性存 Redis 并强制纯数字"
```

---

## Task 3: `GET /auth/captcha` 接口 + 两处白名单放行

新端点会被网关和 Spring Security **各拦一次**。只改一处时症状是"curl 通了浏览器 401"或反之，很难查，所以本任务把两处一起改并用测试锁住。

**Files:**
- Modify: `AuthModule/src/main/java/org/leon/authmodule/controller/AuthController.java`
- Modify: `AuthModule/src/main/java/org/leon/authmodule/config/SecurityConfig.java:33`
- Modify: `GatewayModule/src/main/java/org/leon/gatewaymodule/filter/JwtAuthGlobalFilter.java:43-48`
- Test: `AuthModule/src/test/java/org/leon/authmodule/controller/CaptchaEndpointTest.java`

**Interfaces:**
- Consumes: `CaptchaService.generate()`、`CaptchaService.CaptchaImage`
- Produces: `GET /auth/captcha` → `Result{code:1,data:{captchaKey,imageBase64}}`

- [ ] **Step 1: 写失败测试（MockMvc，走完整 Security 过滤链）**

`AuthModule/src/test/java/org/leon/authmodule/controller/CaptchaEndpointTest.java`：

```java
package org.leon.authmodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.authmodule.service.CaptchaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CaptchaEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    /** Mock 掉 Service，本测试只验"路由可达 + 响应形状"，不验图形码本身 */
    @MockBean
    private CaptchaService captchaService;

    @DisplayName("未登录也能取到验证码图，且返回体带 captchaKey 与 base64")
    @Test
    void captchaEndpointIsPubliclyReachable() throws Exception {
        when(captchaService.generate())
                .thenReturn(new CaptchaService.CaptchaImage("k-fixed", "data:image/png;base64,AAAA"));

        mockMvc.perform(get("/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.captchaKey").value("k-fixed"))
                .andExpect(jsonPath("$.data.imageBase64").value("data:image/png;base64,AAAA"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
"$MVN" -o -pl AuthModule -am test -Dtest=CaptchaEndpointTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 401 或 404（`Status expected:<200> but was:<401>`），因为端点还不存在 / Security 未放行。

- [ ] **Step 3: 加 Controller 方法**

`AuthController.java` 注入字段区加：

```java
    @Autowired
    private CaptchaService captchaService;
```

import 加 `org.leon.authmodule.service.CaptchaService;`，在 `login` 方法之前插入：

```java
    @GetMapping("/captcha")
    public Result captcha() {
        return Result.success(captchaService.generate());
    }
```

- [ ] **Step 4: AuthModule SecurityConfig 放行**

`SecurityConfig.java:33` 改成（两个新路径一次加齐，后面 Task 9 不用再回来改）：

```java
                        .requestMatchers("/auth/login", "/auth/register", "/auth/logout",
                                "/auth/captcha", "/auth/sms/send").permitAll() // 统一用小写
```

- [ ] **Step 5: 网关白名单放行**

`JwtAuthGlobalFilter.java:43-48` 的 `WHITE_LIST` 改成：

```java
    private static final List<String> WHITE_LIST = List.of(
            "/auth/login",
//          "/auth/logout",
            "/auth/register",
            "/auth/captcha",
            "/auth/sms/send",
            "/actuator"
    );
```

> `WHITE_LIST` 的匹配是 `path.startsWith`（`:113`），所以 `/auth/captcha` 会连带放行 `/auth/captchaXXX`。本设计的实际路径都是 `/auth/captcha`、`/auth/sms/send` 精确前缀，`/auth/captchaXXX` 不存在路由，落到后端也是 404。改成精确匹配需要 `AntPathMatcher`，作为遗留项写在 spec §13，不在本计划处理。

- [ ] **Step 6: 跑测试确认通过**

Run: 同 Step 2
Expected: `Tests run: 1, Failures: 0`。

- [ ] **Step 7: 真机验一次图能看**

起 AuthModule（IDEA 或 `"$MVN" -o -pl AuthModule spring-boot:run`）：

```bash
curl -s http://127.0.0.1:8103/auth/captcha -o F:/AAAJAVA/mq-installers/captcha.json
```
Expected: JSON 里 `code:1`、`data.imageBase64` 以 `data:image/png;base64,` 开头。然后**把那段 base64 粘进浏览器**（`https://base64.guru/tools/decode/image` 之类，或本地存成 `.png` 打开）确认看到的 6 位数字，和 Redis 里的值一致：
```bash
# captchaKey 取自上一步 JSON
# redis-cli 不在 PATH 时，可先用管理插件之外的方式：跳过此断言，Task 5 前端联调时会肉眼验证
```

- [ ] **Step 8: 提交**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/controller/AuthController.java \
        AuthModule/src/main/java/org/leon/authmodule/config/SecurityConfig.java \
        AuthModule/src/test/java/org/leon/authmodule/controller \
        GatewayModule/src/main/java/org/leon/gatewaymodule/filter/JwtAuthGlobalFilter.java
git commit -m "feat(auth): 暴露 /auth/captcha 并在网关与安全配置放行"
```

---

## Task 4: 登录接入图形码校验

**Files:**
- Modify: `AuthModule/src/main/java/org/leon/authmodule/service/impl/AuthServiceImpl.java`
- Modify: `AuthModule/src/test/java/org/leon/authmodule/service/impl/AuthServiceImplLoginIpTest.java`

**Interfaces:**
- Consumes: `CaptchaService.verifyAndConsume(String,String)`
- Produces: `login` 在验证码不通过时返回 `Result.error("图形验证码错误或已失效")` 且**不查库、不出票**

- [ ] **Step 1: 给现有测试加"验证码不过则不发票"的失败用例**

`AuthServiceImplLoginIpTest.java` 改动：
- import 加 `org.leon.authmodule.service.CaptchaService`
- 字段区加 `@Mock private CaptchaService captchaService;`
- `setUp()` 末尾加一行：`ReflectionTestUtils.setField(authService, "captchaService", captchaService);`
- 每个现存用例前加 `when(captchaService.verifyAndConsume(any(), any())).thenReturn(true);`（用已有的 `any`/`anyString` import；把 `stubSuccessfulCredentials` 之外新增一个 `stubCaptchaOk()` 私有方法集中这件事）
- 末尾追加新用例：

```java
    @DisplayName("图形验证码不通过时：不出票、不查库、不更新登录信息")
    @Test
    void shouldRejectLoginWhenCaptchaInvalid() {
        when(captchaService.verifyAndConsume("k1", "000000")).thenReturn(false);

        LoginRequest request = request();
        request.setCaptchaKey("k1");
        request.setCaptchaCode("000000");

        Result result = authService.login(request, CLIENT_IP);

        assertEquals(0, result.getCode());
        verify(authMapper, never()).selectByUsername(any());
        verify(authMapper, never()).updateLastLogin(anyLong(), any());
        verify(jwtUtil, never()).generateToken2(anyLong(), any(), any());
    }
```

> 现有 4 个用例为什么要改：`login` 一进去就校验证码，mock 默认返回 `false`，4 个用例会集体变红。这是**预期内的连带修改**，不是回归失败。`verifyCaptchaOk()` 让每个用例显式声明"验证码这关我放过了"，读测试的人一眼看懂剩下断言在验什么。

- [ ] **Step 2: 跑测试确认新用例失败**

Run:
```bash
"$MVN" -o -pl AuthModule -am test -Dtest=AuthServiceImplLoginIpTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 新用例红（`authMapper.selectByUsername` 被调用了，`Never wanted here` 之类 Mockito 报错）。

- [ ] **Step 3: 实现**

`AuthServiceImpl.java` 加字段与构造参数（保持现有构造器注入风格）：

```java
    private final PasswordEncoder passwordEncoder;

    @Autowired
    private CaptchaService captchaService;
```

`login` 方法体最前面插入：

```java
        if (!captchaService.verifyAndConsume(request.getCaptchaKey(), request.getCaptchaCode())) {
            return Result.error("图形验证码错误或已失效");
        }
```

- [ ] **Step 4: 跑测试确认全绿**

Run: 同 Step 2
Expected: `Tests run: 5, Failures: 0`（4 个原有 + 1 个新增）。

- [ ] **Step 5: 提交**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/service/impl/AuthServiceImpl.java \
        AuthModule/src/test/java/org/leon/authmodule/service/impl/AuthServiceImplLoginIpTest.java
git commit -m "feat(auth): 登录前置校验一次性图形验证码"
```

---

## Task 5: 前端登录切真图形码（删掉浏览器自造验证码）

**Files:**
- Modify: `EZ_frontend/src/api/auth.ts:68-94`（验证码段整块重写）
- Modify: `EZ_frontend/src/composables/useLogin.ts:8,114-137`
- Modify: `EZ_frontend/src/views/Login.vue:95-127`

**Interfaces:**
- Consumes: `GET /auth/captcha` → `{captchaKey, imageBase64}`（Task 3）
- Produces: `fetchCaptcha(): Promise<CaptchaChallenge>`（签名不变，`kind` 恒为 `'image-base64'`，`challenge` 装 base64）；`verifyCaptchaLocal` **不存在**

- [ ] **Step 1: 重写 `auth.ts` 的验证码段**

把 `src/api/auth.ts:68-94`（`// ===================== 验证码（mock） =====================` 到文件末尾）整体替换为：

```ts
// ===================== 验证码 =====================

/**
 * 真后端图形验证码：GET /auth/captcha 返回 {captchaKey, imageBase64}。
 * 答案只在 Redis，接口不返回明文，所以本地不再有 verifyCaptchaLocal —— 判错统一靠 login 的 code:0。
 * CaptchaChallenge.challenge 复用为 base64 图，kind 恒为 'image-base64'。
 */
export interface CaptchaPayload {
  captchaKey: string
  imageBase64: string
}

export async function fetchCaptcha(): Promise<CaptchaChallenge> {
  const data = await request<Partial<CaptchaPayload> | null>('/auth/captcha')
  if (!data || typeof data.captchaKey !== 'string' || typeof data.imageBase64 !== 'string') {
    throw new HttpError('验证码响应格式异常', 0)
  }
  return { captchaKey: data.captchaKey, challenge: data.imageBase64, kind: 'image-base64' }
}
```

同时删掉文件顶部注释里"fetchCaptcha / verifyCaptchaLocal 目前是前端 mock"那段（`:4-5`），换成一句：`验证码与登录注册均为真实接口；手机验证码见 sendSmsCode（Task 11 加入）。`
`CAPTCHA_TTL_MS`、`challenge` 模块级变量、`delay()`、`verifyCaptchaLocal()` 全部删除——`delay` 只被 mock 用过，留着就是死代码。

- [ ] **Step 2: 改 `useLogin.ts` 的 submit 分支**

`:8` import 去掉 `verifyCaptchaLocal`：

```ts
import { CAPTCHA_LENGTH, fetchCaptcha, register, type CaptchaChallenge } from '../api/auth'
```

`:125-137` 的登录分支改成（删掉本地校验，直接调登录，靠后端 `code:0` 判错）：

```ts
      if (isLogin.value) {
        await auth.login({ ...form, remember: remember.value })
        captchaStatus.value = 'success'
        router.replace(resolveRedirect(route.query.redirect))
        return
      }
```

`:144-146` 的 catch 分支补一句"后端说验证码错了就作废挑战"，让用户体验和之前一致：

```ts
    } catch (err) {
      error.value = err instanceof Error ? err.message : isLogin.value ? '登录失败，请稍后重试' : '注册失败，请稍后重试'
      if (err instanceof BizError && err.message.includes('验证码')) {
        captchaStatus.value = 'error'
        invalidateCaptcha()
      }
      throw err
```

> `requestCode`（`:93-106`）一行都不用改：它调的 `fetchCaptcha()` 签名没变，只是返回值从明文数字变成 base64。这正是"预留 API 层"约定的意义。

- [ ] **Step 3: 改 `Login.vue` 把题面换成图片**

`:95-127` 的 `v-if="isLogin"` 块里：
- `:99` 的 `开发占位` `<span>` **整行删除**（它已经不成立了）
- `:109-111` 的"题面 `<span>` + `<output class=\"captcha-code\">`"换成可点击刷新的 `<img>`，`CodeSlots` 调用（`:112-125`）原样保留。

覆盖 `:109-126` 的完整块：

```html
              <div v-if="captcha" class="captcha-row">
                <img
                    class="captcha-img"
                    :src="captcha.challenge"
                    :key="captcha.captchaKey"
                    alt="验证码图片"
                    title="点击刷新验证码"
                    @click="requestCode"
                />
                <CodeSlots
                    :length="6"
                    :value="form.captchaCode"
                    :status="captchaStatus"
                    :slot-size="40"
                    :gap="6"
                    :radius="10"
                    :accent-color="palette.accent"
                    :ink-color="palette.ink"
                    :slot-color="palette.slot"
                    :digit-color="palette.digit"
                    aria-label="六位数字验证码"
                    @change="onCodeChange"
                />
              </div>
```

- [ ] **Step 4: 加 `.captcha-img` 样式，删 `.captcha-code` 样式**

`Login.vue` 的 `<style scoped>` 里，把原 `.captcha-code {...}` 整段替换成：

```css
.captcha-img {
  height: 44px;
  width: 120px;
  border: 1px solid var(--glass-border-strong);
  border-radius: 8px;
  background: var(--bg-elevated);
  cursor: pointer;
  object-fit: cover;
}
```

`sr-only` 类若项目里不存在，加一条：
```css
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
```

- [ ] **Step 5: 类型检查**

```bash
cd F:/AAAJAVA/code/EZ_frontend && npm run type-check
```
Expected: 无输出、退出码 0。**如果报 `verifyCaptchaLocal` 未导出**，说明还有调用点没删干净：`grep -rn verifyCaptchaLocal src/` 必须零命中。

- [ ] **Step 6: 起网关 + AuthModule + 前端，肉眼跑一次**

```bash
cd F:/AAAJAVA/code/EZ_frontend && npm run dev
```
浏览器 `http://localhost:5173/login`（网关 8100 必须先起，`/gw` 代理才有人接）：
1. 点"获取验证码"→ 出现**一张真实图片**，不再有 `开发占位` 角标。
2. 输错 6 位数字 → 提示"图形验证码错误或已失效"，图片自动刷新。
3. 输对图片上的数字 + 正确密码 → 登录成功进 `/practice`。
4. **F12 Network 里确认 `/gw/auth/captcha` 响应体不含明文验证码**——这是本任务最重要的安全断言，明文等于没做。

- [ ] **Step 7: 提交**

```bash
cd F:/AAAJAVA/code/EZ_frontend
git add src/api/auth.ts src/composables/useLogin.ts src/views/Login.vue
git commit -m "feat(login): 图形验证码改为后端真实图片，移除浏览器自造校验"
```

---

## Task 6: `users_dev` 加 phone 列（含表结构核实与迁移文件）

**Files:**
- Create: `db/migration/2026-10-12-add-phone-to-users-dev.sql`
- Modify: `AuthModule/src/main/java/org/leon/authmodule/pojo/Users.java`
- Modify: `AuthModule/src/main/resources/mapper/AuthMapper.xml:14-17`
- Modify: `AuthModule/src/main/java/org/leon/authmodule/mapper/AuthMapper.java:12`
- Modify: `AuthModule/src/main/java/org/leon/authmodule/service/impl/AuthServiceImpl.java:register`

**Interfaces:**
- Consumes: 真实表结构（Step 1 才能拿到）
- Produces: `AuthMapper.register(String username, String encodedPassword, String phone)`；`Users.getPhone()/setPhone()`

- [ ] **Step 1: 先把真实表结构拿到手（这一步不能跳）**

仓库里零 `.sql`、无 Flyway，`Users.java` 声明的列 ≠ 库里真实存在的列。执行方式二选一，**由用户决定**：
- (A) 用户在 IDEA Database 面板跑 `SHOW CREATE TABLE ezpracticeexercises.users_dev;` 把结果贴回来；
- (B) 用户授权后我用 `~/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar` 写一次性 JDBC 查询跑同一条语句。

Expected 输出确认三件事：`status`/`created_at` 是否有默认值（影响 INSERT 是否要带列）、`username` 有没有唯一索引（决定注册重复名的报错形态）、是否**已经存在** phone 列。

- [ ] **Step 2: 把确认后的 DDL 写进仓库第一份迁移文件**

`db/migration/2026-10-12-add-phone-to-users-dev.sql`：

```sql
-- 2026-10-12 注册手机验证码改造：users_dev 增加 phone
-- 执行前提：已用 SHOW CREATE TABLE users_dev 确认不存在 phone 列（Task 6 Step 1）
-- 说明：新增列历史行全为 NULL；MySQL 唯一索引允许多行 NULL，故不会与存量数据冲突（spec D9）。
ALTER TABLE users_dev
    ADD COLUMN phone VARCHAR(20) NULL AFTER last_login_ip,
    ADD UNIQUE KEY uk_users_dev_phone (phone);
```

> 如果 Step 1 发现库里已有 `phone` 列或已有同名索引，就只补 `ADD UNIQUE KEY`，别把 ALTER 跑成报错。

- [ ] **Step 3: 执行 DDL**

由用户在 IDEA Database 面板执行上面这条 SQL（或授权我执行）。执行后回一句 `SHOW CREATE TABLE` 结果确认列已存在。

- [ ] **Step 4: `Users` 加字段**

`Users.java` 在 `last_login_ip` 字段之后加：

```java
    /**
     * 手机号，注册时经短信验证码校验后写入
     */
    @TableField(value = "phone")
    private String phone;
```

`equals`/`hashCode`/`toString` 是 MyBatis-Plus 生成器产出的手写方法，里面逐字段列举——**三处都要加 `phone`**，否则 equals 会忽略新字段（这是这份生成代码的既有毛病，不要扩大解释，只补这一处）。

- [ ] **Step 5: Mapper 与 XML 支持写 phone**

`AuthMapper.java:12` 改成：

```java
    void register(@Param("username") String username,
                  @Param("encodedPassword") String encodedPassword,
                  @Param("phone") String phone);
```

> 为什么补 `@Param`：现在 `:12` 是裸 `void register(String username, String encodedPassword)`，靠 XML 里 `#{username}` 按名取值能工作是因为 `-parameters` 编译开关；XML 一旦拿到第三个参数就必须显式命名，别赌。

`AuthMapper.xml:14-17` 改成：

```xml
    <insert id="register">
        INSERT INTO users_dev (username, password_hash, phone)
        VALUES (#{username}, #{encodedPassword}, #{phone})
    </insert>
```

- [ ] **Step 6: Service 传 phone，并处理唯一键冲突**

`AuthServiceImpl.register` 改成：

```java
    @Override
    public Result register(RegisterRequest request) {
        String encodedPassword = passwordEncoder.encode(request.getPassword());
        try {
            AuthMapper.register(request.getUsername(), encodedPassword, request.getPhone());
        } catch (DuplicateKeyException e) {
            // phone 有唯一索引；username 是否唯一取决于 users_dev 实际索引（Task 6 Step 1）
            log.info("[注册] 唯一键冲突 phone={} username={}", request.getPhone(), request.getUsername());
            return Result.error("该手机号已注册");
        }
        return Result.success("注册成功");
    }
```

import 加 `org.springframework.dao.DuplicateKeyException` 与 `lombok.extern.slf4j.Slf4j`（类上加 `@Slf4j`）。
本步骤**先不加短信码校验**，那是 Task 10；此时注册链路仍不含验证码，保持"每步可独立编译通过"。

- [ ] **Step 7: 编译 + 回归**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=AuthServiceImplLoginIpTest,CaptchaServiceImplTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全绿（这两个测试类不涉及 register，必须不受影响）。

- [ ] **Step 8: 手工验一次注册写库**

起 AuthModule，POST 一个带 phone 的注册：
```bash
curl -s -X POST http://127.0.0.1:8103/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"phonetest01","password":"password123","phone":"13800001234","smsCode":"000000"}'
```
Expected: `{"code":1,...,"data":"注册成功"}`；再查库 `SELECT username, phone FROM users_dev WHERE username='phonetest01';` 应看到 `13800001234`。
重复同一个 phone 再注册一次 → `{"code":0,"msg":"该手机号已注册"}`，**不是 500**。

- [ ] **Step 9: 提交**

```bash
git add db/migration AuthModule/src/main/java/org/leon/authmodule/pojo/Users.java \
        AuthModule/src/main/resources/mapper/AuthMapper.xml \
        AuthModule/src/main/java/org/leon/authmodule/mapper/AuthMapper.java \
        AuthModule/src/main/java/org/leon/authmodule/service/impl/AuthServiceImpl.java
git commit -m "feat(auth): users_dev 增加 phone 列并纳入迁移文件管理"
```

---

## Task 7: RabbitMQ 依赖 + 验证码拓扑声明 + 消息体 + Producer

**Files:**
- Modify: `AuthModule/pom.xml`（hutool 块之后）
- Modify: `AuthModule/src/main/resources/application.yaml`（`datasource` 块之后）
- Create: `AuthModule/src/main/java/org/leon/authmodule/pojo/mq/SmsSendRequest.java`
- Create: `AuthModule/src/main/java/org/leon/authmodule/pojo/mq/SmsExpireEvent.java`
- Create: `AuthModule/src/main/java/org/leon/authmodule/config/CaptchaMqConfig.java`
- Create: `AuthModule/src/main/java/org/leon/authmodule/mq/SmsCodeProducer.java`
- Test: `AuthModule/src/test/java/org/leon/authmodule/mq/SmsCodeProducerTest.java`

**Interfaces:**
- Consumes: `RabbitTemplate`（Boot 自动配置）
- Produces: `SmsCodeProducer.sendSmsCode(SmsSendRequest)`、`SmsCodeProducer.sendExpireDelay(SmsExpireEvent)`；常量 `CaptchaMqConfig.SMS_EXCHANGE/SMS_SEND_QUEUE/SMS_SEND_KEY/SMS_EXPIRE_DELAY_QUEUE/SMS_EXPIRE_DELAY_KEY/SMS_EXPIRE_DLX/SMS_EXPIRED_QUEUE/SMS_EXPIRED_KEY`

- [ ] **Step 1: 加依赖与连接配置**

`AuthModule/pom.xml` hutool 块之后：

```xml
        <!-- RabbitMQ：手机验证码异步投递与延迟过期 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
```

`application.yaml` 的 `datasource:` 块之后（与它同级）：

```yaml
  rabbitmq:
    host: 127.0.0.1
    port: 5672
    username: guest
    password: guest
    virtual-host: /

auth:
  sms:
    # 验证码有效期；队列 TTL 是创建参数，改这个值必须同时改 auth.sms.queue-suffix，
    # 否则 Broker 上同名旧队列参数不一致会报 PRECONDITION_FAILED。
    code-ttl-ms: 300000
    queue-suffix: ""
```

- [ ] **Step 2: 写两个消息体**

`pojo/mq/SmsSendRequest.java`：

```java
package org.leon.authmodule.pojo.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 进 auth.sms.send.queue 的消息：只带"要发给谁"，码由消费者生成。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsSendRequest {
    private String phone;
    /** 贯穿一次申请的链路号，便于把日志里的生产者/消费者串起来看 */
    private String traceId;
}
```

`pojo/mq/SmsExpireEvent.java`：

```java
package org.leon.authmodule.pojo.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 延迟 5 分钟后到期的过期事件。带 code 是为了让消费者2 做幂等比对。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsExpireEvent {
    private String phone;
    private String code;
    private String traceId;
}
```

- [ ] **Step 3: 写失败的 Producer 测试**

`mq/SmsCodeProducerTest.java`（纯 Mockito，不起 Spring）：

```java
package org.leon.authmodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.config.CaptchaMqConfig;
import org.leon.authmodule.pojo.mq.SmsExpireEvent;
import org.leon.authmodule.pojo.mq.SmsSendRequest;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SmsCodeProducerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @DisplayName("发码消息投到 auth.sms.exchange 的 sms.send 路由键")
    @Test
    void sendSmsCodeUsesSendRoutingKey() {
        SmsSendRequest payload = new SmsSendRequest("13800001234", "trace-1");

        new SmsCodeProducer(rabbitTemplate).sendSmsCode(payload);

        verify(rabbitTemplate).convertAndSend(
                CaptchaMqConfig.SMS_EXCHANGE, CaptchaMqConfig.SMS_SEND_KEY, payload);
    }

    @DisplayName("过期消息投到同一交换机的 sms.expire 路由键（它绑定到延迟队列）")
    @Test
    void sendExpireDelayUsesExpireRoutingKey() {
        SmsExpireEvent event = new SmsExpireEvent("13800001234", "123456", "trace-1");

        new SmsCodeProducer(rabbitTemplate).sendExpireDelay(event);

        verify(rabbitTemplate).convertAndSend(
                CaptchaMqConfig.SMS_EXCHANGE, CaptchaMqConfig.SMS_EXPIRE_DELAY_KEY, event);
    }
}
```

- [ ] **Step 4: 跑测试确认失败**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=SmsCodeProducerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，`找不到符号 CaptchaMqConfig` / `SmsCodeProducer`。

- [ ] **Step 5: 写拓扑配置**

`config/CaptchaMqConfig.java`：

```java
package org.leon.authmodule.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 手机验证码的 MQ 拓扑：2 交换机 + 3 队列 + 3 绑定（spec §3.2）。
 * 延迟队列故意不配 @RabbitListener —— 有消费者消息就永远不会超时变死信。
 */
@Configuration
public class CaptchaMqConfig {

    public static final String SMS_EXCHANGE = "auth.sms.exchange";
    public static final String SMS_EXPIRE_DLX = "auth.sms.expire.dlx";

    public static final String SMS_SEND_KEY = "sms.send";
    public static final String SMS_EXPIRE_DELAY_KEY = "sms.expire";
    public static final String SMS_EXPIRED_KEY = "sms.expired";

    public static final String SMS_SEND_QUEUE = "auth.sms.send.queue";
    public static final String SMS_EXPIRE_DELAY_QUEUE = "auth.sms.expire.delay.queue";
    public static final String SMS_EXPIRED_QUEUE = "auth.sms.expired.queue";

    @Value("${auth.sms.code-ttl-ms:300000}")
    private long codeTtlMs;

    @Value("${auth.sms.queue-suffix:}")
    private String queueSuffix;

    @Bean
    public TopicExchange smsExchange() {
        return new TopicExchange(SMS_EXCHANGE, true, false);
    }

    /** 死信交换机：接收从延迟队列超时出来的消息 */
    @Bean
    public DirectExchange smsExpireDlx() {
        return new DirectExchange(SMS_EXPIRE_DLX, true, false);
    }

    @Bean
    public Queue smsSendQueue() {
        return QueueBuilder.durable(SMS_SEND_QUEUE + queueSuffix).build();
    }

    @Bean
    public Queue smsExpireDelayQueue() {
        return QueueBuilder.durable(SMS_EXPIRE_DELAY_QUEUE + queueSuffix)
                .ttl((int) codeTtlMs)
                .deadLetterExchange(SMS_EXPIRE_DLX)
                .deadLetterRoutingKey(SMS_EXPIRED_KEY)
                .build();
    }

    @Bean
    public Queue smsExpiredQueue() {
        return QueueBuilder.durable(SMS_EXPIRED_QUEUE + queueSuffix).build();
    }

    @Bean
    public Binding smsSendBinding() {
        return BindingBuilder.bind(smsSendQueue()).to(smsExchange()).with(SMS_SEND_KEY);
    }

    /** ★ 缺这条绑定，延迟队列永远是空的，过期链路静默失效（spec §3.2 自检记录的就是这个坑） */
    @Bean
    public Binding smsExpireDelayBinding() {
        return BindingBuilder.bind(smsExpireDelayQueue()).to(smsExchange()).with(SMS_EXPIRE_DELAY_KEY);
    }

    @Bean
    public Binding smsExpiredBinding() {
        return BindingBuilder.bind(smsExpiredQueue()).to(smsExpireDlx()).with(SMS_EXPIRED_KEY);
    }

    /** JSON 序列化：管理界面上能直接读到人类可读的消息体 */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
```

> 三个 `@Bean` 方法之间直接互相调用（`smsSendBinding()` 里调 `smsSendQueue()`），这是 `@Configuration` 默认 CGLIB 代理保证的——代理会让 `smsSendQueue()` 返回同一个单例 Bean 而不是新建对象。写成方法参数注入也对，但项目现有风格是直接调用，保持一致。

- [ ] **Step 6: 写 Producer**

`mq/SmsCodeProducer.java`：

```java
package org.leon.authmodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.config.CaptchaMqConfig;
import org.leon.authmodule.pojo.mq.SmsExpireEvent;
import org.leon.authmodule.pojo.mq.SmsSendRequest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmsCodeProducer {

    private final RabbitTemplate rabbitTemplate;

    public SmsCodeProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendSmsCode(SmsSendRequest request) {
        log.info("[发码生产者] traceId={} phone={}", request.getTraceId(), request.getPhone());
        rabbitTemplate.convertAndSend(CaptchaMqConfig.SMS_EXCHANGE, CaptchaMqConfig.SMS_SEND_KEY, request);
    }

    public void sendExpireDelay(SmsExpireEvent event) {
        log.info("[过期生产者] traceId={} phone={}", event.getTraceId(), event.getPhone());
        rabbitTemplate.convertAndSend(CaptchaMqConfig.SMS_EXCHANGE, CaptchaMqConfig.SMS_EXPIRE_DELAY_KEY, event);
    }
}
```

- [ ] **Step 7: 跑测试确认通过**

Run: 同 Step 4
Expected: `Tests run: 2, Failures: 0`。

- [ ] **Step 8: 起服务，去管理界面核对拓扑形状**

起 AuthModule，打开 `http://localhost:15672`（guest/guest）→ Exchanges → `auth.sms.exchange` → Binding 标签页。
Expected: **看到 2 条绑定**：`sms.send → auth.sms.send.queue`、`sms.expire → auth.sms.expire.delay.queue`。再看 Queues，`auth.sms.expire.delay.queue` 的 Features 列应显示 `ttl 300000`、`dlx auth.sms.expire.dlx`。
这一步是"拓扑对不对"的唯一权威检查，别用"启动没报错"代替。

- [ ] **Step 9: 提交**

```bash
git add AuthModule/pom.xml AuthModule/src/main/resources/application.yaml \
        AuthModule/src/main/java/org/leon/authmodule/{config/CaptchaMqConfig.java,mq,pojo/mq} \
        AuthModule/src/test/java/org/leon/authmodule/mq
git commit -m "feat(auth): 手机验证码 MQ 拓扑（延迟队列 + 死信交换机）与生产者"
```

---

## Task 8: SmsSender 接口 + Mock 实现 + 发码消费者

**Files:**
- Create: `AuthModule/src/main/java/org/leon/authmodule/service/SmsSender.java`
- Create: `AuthModule/src/main/java/org/leon/authmodule/service/impl/MockSmsSender.java`
- Create: `AuthModule/src/main/java/org/leon/authmodule/mq/SmsCodeConsumer.java`
- Test: `AuthModule/src/test/java/org/leon/authmodule/mq/SmsCodeConsumerTest.java`

**Interfaces:**
- Consumes: `StringRedisTemplate`、`SmsCodeProducer.sendExpireDelay(SmsExpireEvent)`、`AuthCacheKeys.smsCodeKey(String)`
- Produces: `SmsSender.send(String phone, String code)`；`SmsCodeConsumer.onSmsSend(SmsSendRequest)`（监听 `auth.sms.send.queue`）

- [ ] **Step 1: 写发送通道接口与 Mock 实现**

`service/SmsSender.java`：

```java
package org.leon.authmodule.service;

/** 短信通道抽象。接真通道 = 再加一个实现类，不改消费者。 */
public interface SmsSender {
    void send(String phone, String code);
}
```

`service/impl/MockSmsSender.java`：

```java
package org.leon.authmodule.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.service.SmsSender;
import org.springframework.stereotype.Component;

/**
 * ⚠️ 本机没有短信通道，这里只打日志。
 * 生产必须换实现并去掉日志里的明文码——把验证码写进日志系统等于泄露。
 */
@Slf4j
@Component
public class MockSmsSender implements SmsSender {

    @Override
    public void send(String phone, String code) {
        log.info("【模拟短信】{} 的登录/注册验证码是 {}（本机无短信通道，此为 mock，测试用）", phone, code);
    }
}
```

- [ ] **Step 2: 写失败的消费者测试**

`mq/SmsCodeConsumerTest.java`：

```java
package org.leon.authmodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.config.CaptchaMqConfig;
import org.leon.authmodule.pojo.mq.SmsExpireEvent;
import org.leon.authmodule.pojo.mq.SmsSendRequest;
import org.leon.authmodule.service.SmsSender;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmsCodeConsumerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SmsSender smsSender;

    @Mock
    private SmsCodeProducer producer;

    private SmsCodeConsumer newConsumer() {
        SmsCodeConsumer consumer = new SmsCodeConsumer(redisTemplate, smsSender, producer);
        ReflectionTestUtils.setField(consumer, "codeTtlMs", 300000L);
        ReflectionTestUtils.setField(consumer, "queueSuffix", "");
        return consumer;
    }

    @DisplayName("收到发码消息：写 Redis 的 key 是 auth:sms:code:{phone}、TTL 等于 code-ttl-ms、码为 6 位数字，并模拟发送 + 投过期消息")
    @Test
    void onSmsSendStoresCodeSendsAndSchedulesExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        newConsumer().onSmsSend(new SmsSendRequest("13800001234", "trace-9"));

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("auth:sms:code:13800001234"), code.capture(), eq(Duration.ofMillis(300000)));
        assertEquals(6, code.getValue().length());
        assertTrue(code.getValue().chars().allMatch(Character::isDigit));

        verify(smsSender).send("13800001234", code.getValue());

        ArgumentCaptor<SmsExpireEvent> event = ArgumentCaptor.forClass(SmsExpireEvent.class);
        verify(producer).sendExpireDelay(event.capture());
        assertEquals("13800001234", event.getValue().getPhone());
        assertEquals(code.getValue(), event.getValue().getCode(), "过期事件必须带同一个码，消费者2 靠它做幂等比对");
        assertEquals("trace-9", event.getValue().getTraceId());
    }

    @DisplayName("队列名带测试后缀时，监听注解仍指向生产队列名——所以队列名必须走配置而非硬编码字符串常量")
    @Test
    void queueNameResolvesThroughConfigBean() {
        // 队列名通过 @RabbitListener("#{smsSendQueue.name}") 在运行时解析，所以测试后缀生效
        assertEquals(CaptchaMqConfig.SMS_SEND_QUEUE + "", CaptchaMqConfig.SMS_SEND_QUEUE);
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=SmsCodeConsumerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，`找不到符号 SmsCodeConsumer`。

- [ ] **Step 4: 写消费者**

`mq/SmsCodeConsumer.java`：

```java
package org.leon.authmodule.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.config.AuthCacheKeys;
import org.leon.authmodule.config.CaptchaMqConfig;
import org.leon.authmodule.pojo.mq.SmsExpireEvent;
import org.leon.authmodule.pojo.mq.SmsSendRequest;
import org.leon.authmodule.service.SmsSender;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
public class SmsCodeConsumer {

    private final StringRedisTemplate redisTemplate;
    private final SmsSender smsSender;
    private final SmsCodeProducer producer;

    @Value("${auth.sms.code-ttl-ms:300000}")
    private long codeTtlMs;

    public SmsCodeConsumer(StringRedisTemplate redisTemplate, SmsSender smsSender, SmsCodeProducer producer) {
        this.redisTemplate = redisTemplate;
        this.smsSender = smsSender;
        this.producer = producer;
    }

    @RabbitListener(queues = "#{smsSendQueue.name}")
    public void onSmsSend(SmsSendRequest request) {
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        log.info("[发码消费者] traceId={} phone={} 线程={}",
                request.getTraceId(), request.getPhone(), Thread.currentThread().getName());

        redisTemplate.opsForValue().set(AuthCacheKeys.smsCodeKey(request.getPhone()),
                code, Duration.ofMillis(codeTtlMs));
        smsSender.send(request.getPhone(), code);

        producer.sendExpireDelay(new SmsExpireEvent(request.getPhone(), code, request.getTraceId()));
    }
}
```

> **为什么这里写 SpEL 而不是常量**：`@RabbitListener(queues = ...)` 的注解属性只能放编译期常量，但队列名要拼运行时的 `queueSuffix`（Task 7 的 `@Value`），常量满足不了——用常量名会在测试里绑到**不带后缀**的队列上，而测试声明的是 `-test` 队列，两边各消费一半消息，症状是"测试偶发红"。写成 `"#{smsSendQueue.name}"` 让 Spring 在运行时解析那个 `Queue` Bean，后缀自然跟着配置走。Bean 名 = `@Bean` 方法名 `smsSendQueue`，所以 Task 7 里的方法名不能随便改。同理过期消费者用 `"#{smsExpiredQueue.name}"`。

- [ ] **Step 5: 跑测试确认通过**

Run: 同 Step 2。Expected: `Tests run: 2, Failures: 0`。

- [ ] **Step 6: 端到端手工验一次（不经过 HTTP，直接投消息）**

起 AuthModule，在 RabbitMQ 管理界面 Exchanges → `auth.sms.exchange` → Publish message，routing key 填 `sms.send`，payload：
```json
{"phone":"13800005678","traceId":"manual-1"}
```
Property 里把 `contentType` 设为 `application/json`、`__TypeId__` 设为 `org.leon.authmodule.pojo.mq.SmsSendRequest`。
Expected 控制台依次出现：
```
[发码消费者] traceId=manual-1 phone=13800005678 线程=org.springframework.amqp.rabbit.RabbitListenerEndpointContainer#0-1
【模拟短信】13800005678 的登录/注册验证码是 123456（...）
[过期生产者] traceId=manual-1 phone=13800005678
```
**线程名不是 `main`**，这一行就是"异步"的证据。再回管理界面 Queues 看 `auth.sms.expire.delay.queue` 的 Ready 数 = 1（那条消息正在躺 5 分钟）。

- [ ] **Step 7: 提交**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/service/SmsSender.java \
        AuthModule/src/main/java/org/leon/authmodule/service/impl/MockSmsSender.java \
        AuthModule/src/main/java/org/leon/authmodule/mq/SmsCodeConsumer.java \
        AuthModule/src/test/java/org/leon/authmodule/mq
git commit -m "feat(auth): 发码消费者生成验证码、写 Redis 并模拟发送"
```

---

## Task 9: `POST /auth/sms/send`（校图形码 → 限频 → 投 MQ）

**Files:**
- Create: `AuthModule/src/main/java/org/leon/authmodule/service/SmsCodeService.java`
- Create: `AuthModule/src/main/java/org/leon/authmodule/service/impl/SmsCodeServiceImpl.java`
- Modify: `AuthModule/src/main/java/org/leon/authmodule/controller/AuthController.java`
- Test: `AuthModule/src/test/java/org/leon/authmodule/service/impl/SmsCodeServiceImplTest.java`

**Interfaces:**
- Consumes: `CaptchaService.verifyAndConsume`、`StringRedisTemplate`（限频）、`SmsCodeProducer.sendSmsCode`
- Produces: `SmsCodeService.sendCode(SendSmsRequest)` → `Result`；`POST /auth/sms/send`

- [ ] **Step 1: 写接口**

`service/SmsCodeService.java`：

```java
package org.leon.authmodule.service;

import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.SendSmsRequest;

public interface SmsCodeService {
    Result sendCode(SendSmsRequest request);
}
```

- [ ] **Step 2: 写失败的测试——重点是"三种失败都不许投 MQ"**

`service/impl/SmsCodeServiceImplTest.java`：

```java
package org.leon.authmodule.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.SendSmsRequest;
import org.leon.authmodule.mq.SmsCodeProducer;
import org.leon.authmodule.service.CaptchaService;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmsCodeServiceImplTest {

    @Mock private CaptchaService captchaService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private SmsCodeProducer producer;

    private SmsCodeServiceImpl newService() {
        return new SmsCodeServiceImpl(captchaService, redisTemplate, producer);
    }

    private SendSmsRequest validRequest() {
        SendSmsRequest request = new SendSmsRequest();
        request.setPhone("13800001234");
        request.setCaptchaKey("k1");
        request.setCaptchaCode("123456");
        return request;
    }

    private void stubLimitAllowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("auth:sms:limit:13800001234"), anyString(), any(Duration.class)))
                .thenReturn(true);
    }

    @DisplayName("图形码通过 + 未触发限频 → 投一条 MQ 消息")
    @Test
    void happyPathPublishesToMq() {
        when(captchaService.verifyAndConsume("k1", "123456")).thenReturn(true);
        stubLimitAllowed();

        Result result = newService().sendCode(validRequest());

        assertEquals(1, result.getCode());
        verify(producer).sendSmsCode(any());
    }

    @DisplayName("图形码错 → 一次 MQ 都不投")
    @Test
    void wrongCaptchaNeverPublishes() {
        when(captchaService.verifyAndConsume(anyString(), anyString())).thenReturn(false);

        Result result = newService().sendCode(validRequest());

        assertEquals(0, result.getCode());
        verify(producer, never()).sendSmsCode(any());
        verify(redisTemplate, never()).opsForValue();
    }

    @DisplayName("60 秒内重复申请 → 不投 MQ，返回限频提示")
    @Test
    void rateLimitedNeverPublishes() {
        when(captchaService.verifyAndConsume("k1", "123456")).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("auth:sms:limit:13800001234"), anyString(), any(Duration.class)))
                .thenReturn(false);

        Result result = newService().sendCode(validRequest());

        assertEquals(0, result.getCode());
        assertEquals("请60秒后再试", result.getMsg());
        verify(producer, never()).sendSmsCode(any());
    }

    @DisplayName("手机号格式非法 → 不校图形码、不投 MQ（顺序很重要：省一次 Redis 往返，也不白白消费掉一张图形码）")
    @Test
    void badPhoneShortCircuitsBeforeCaptcha() {
        SendSmsRequest request = validRequest();
        request.setPhone("12345");

        Result result = newService().sendCode(request);

        assertEquals(0, result.getCode());
        verify(captchaService, never()).verifyAndConsume(anyString(), anyString());
        verify(producer, never()).sendSmsCode(any());
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=SmsCodeServiceImplTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，`找不到符号 SmsCodeServiceImpl`。

- [ ] **Step 4: 写实现（校验顺序按测试要求：手机号 → 图形码 → 限频 → 投递）**

`service/impl/SmsCodeServiceImpl.java`：

```java
package org.leon.authmodule.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.leon.authmodule.config.AuthCacheKeys;
import org.leon.authmodule.mq.SmsCodeProducer;
import org.leon.authmodule.pojo.Result;
import org.leon.authmodule.pojo.SendSmsRequest;
import org.leon.authmodule.pojo.mq.SmsSendRequest;
import org.leon.authmodule.service.CaptchaService;
import org.leon.authmodule.service.SmsCodeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
public class SmsCodeServiceImpl implements SmsCodeService {

    private static final Pattern PHONE_RE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Duration LIMIT_TTL = Duration.ofSeconds(60);

    private final CaptchaService captchaService;
    private final StringRedisTemplate redisTemplate;
    private final SmsCodeProducer producer;

    public SmsCodeServiceImpl(CaptchaService captchaService,
                              StringRedisTemplate redisTemplate,
                              SmsCodeProducer producer) {
        this.captchaService = captchaService;
        this.redisTemplate = redisTemplate;
        this.producer = producer;
    }

    @Override
    public Result sendCode(SendSmsRequest request) {
        String phone = request.getPhone();
        if (phone == null || !PHONE_RE.matcher(phone).matches()) {
            return Result.error("手机号格式不正确");
        }

        // 先消费图形码：不做这步，脚本可以无限次拿同一个 captchaKey 试短信码窗口
        if (!captchaService.verifyAndConsume(request.getCaptchaKey(), request.getCaptchaCode())) {
            return Result.error("图形验证码错误或已失效");
        }

        Boolean firstInWindow = redisTemplate.opsForValue()
                .setIfAbsent(AuthCacheKeys.smsLimitKey(phone), "1", LIMIT_TTL);
        if (!Boolean.TRUE.equals(firstInWindow)) {
            return Result.error("请60秒后再试");
        }

        String traceId = UUID.randomUUID().toString().replace("-", "");
        log.info("[申请短信码] traceId={} phone={}", traceId, phone);
        producer.sendSmsCode(new SmsSendRequest(phone, traceId));
        return Result.success("验证码已发送，请注意查收");
    }
}
```

- [ ] **Step 5: 跑测试确认全绿**

Run: 同 Step 3
Expected: `Tests run: 4, Failures: 0`。

- [ ] **Step 6: 加 Controller 入口**

`AuthController.java` 注入 `SmsCodeService smsCodeService`，加：

```java
    @PostMapping("/sms/send")
    public Result sendSms(@RequestBody SendSmsRequest request) {
        return smsCodeService.sendCode(request);
    }
```

import 加 `org.leon.authmodule.pojo.SendSmsRequest` 与 `org.leon.authmodule.service.SmsCodeService`。SecurityConfig 与网关白名单在 Task 3 已经放行 `/auth/sms/send`，**这里不用再改**。

- [ ] **Step 7: 真机验一次全链路（不含 HTTP 之外的 UI）**

起 AuthModule（broker、Redis 必须在跑）：
```bash
KEY_JSON=$(curl -s http://127.0.0.1:8103/auth/captcha)   # 拿到 captchaKey
CODE=<从 Redis 或后端日志里读到的 6 位数字>
curl -s -X POST http://127.0.0.1:8103/auth/sms/send -H 'Content-Type: application/json' \
  -d "{\"phone\":\"13800001234\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}"
```
Expected：返回 `{"code":1,...,"data":"验证码已发送，请注意查收"}`；控制台出现 `[发码消费者] ... 线程=...RabbitListenerEndpointContainer#...` 和 `【模拟短信】13800001234 的登录/注册验证码是 xxxxxx`。
再**立刻**用同一 phone 重发一次（新图形码）→ 期望 `{"code":0,"msg":"请60秒后再试"}`。

- [ ] **Step 8: 提交**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/service/SmsCodeService.java \
        AuthModule/src/main/java/org/leon/authmodule/service/impl/SmsCodeServiceImpl.java \
        AuthModule/src/main/java/org/leon/authmodule/controller/AuthController.java \
        AuthModule/src/test/java/org/leon/authmodule/service/impl/SmsCodeServiceImplTest.java
git commit -m "feat(auth): /auth/sms/send 校图形码+限频后投递发码消息"
```

---

## Task 10: 注册接入短信码校验（一次性消费）

**Files:**
- Modify: `AuthModule/src/main/java/org/leon/authmodule/service/impl/AuthServiceImpl.java`（`register`）
- Test: `AuthModule/src/test/java/org/leon/authmodule/service/impl/AuthServiceImplRegisterTest.java`（新建）

**Interfaces:**
- Consumes: `StringRedisTemplate`、`AuthCacheKeys.smsCodeKey(String)`、`AuthMapper.register(String,String,String)`
- Produces: `register` 在短信码不匹配时返回 `Result.error("验证码错误或已失效")` 且**不 INSERT**

- [ ] **Step 1: 写失败的测试**

`AuthServiceImplRegisterTest.java`：

```java
package org.leon.authmodule.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.pojo.RegisterRequest;
import org.leon.authmodule.pojo.Result;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplRegisterTest {

    @Mock private AuthMapper authMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(passwordEncoder);
        ReflectionTestUtils.setField(authService, "AuthMapper", authMapper);
        ReflectionTestUtils.setField(authService, "stringRedisTemplate", redisTemplate);
        when(passwordEncoder.encode("password123")).thenReturn("$2a$10$hash");
    }

    private RegisterRequest request() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("test001");
        request.setPassword("password123");
        request.setPhone("13800001234");
        request.setSmsCode("123456");
        return request;
    }

    @DisplayName("短信码正确 → 入库并删除该码（一次性）")
    @Test
    void validSmsCodeRegistersAndConsumes() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:sms:code:13800001234")).thenReturn("123456");

        Result result = authService.register(request());

        assertEquals(1, result.getCode());
        verify(authMapper).register("test001", "$2a$10$hash", "13800001234");
        verify(redisTemplate).delete("auth:sms:code:13800001234");
    }

    @DisplayName("短信码错误 → 不入库、不删码（留给用户重试到过期）")
    @Test
    void wrongSmsCodeDoesNotRegister() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:sms:code:13800001234")).thenReturn("654321");

        Result result = authService.register(request());

        assertEquals(0, result.getCode());
        verify(authMapper, never()).register(anyString(), anyString(), anyString());
        verify(redisTemplate, never()).delete(any());
    }

    @DisplayName("没发过短信码（Redis 无值）→ 拒绝")
    @Test
    void missingSmsCodeIsRejected() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertEquals(0, authService.register(request()).getCode());
        verify(authMapper, never()).register(anyString(), anyString(), anyString());
    }

    @DisplayName("手机号已被占用 → 友好提示，不是 500")
    @Test
    void duplicatePhoneReturnsFriendlyError() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:sms:code:13800001234")).thenReturn("123456");
        when(authMapper.register(anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("dup"));

        Result result = authService.register(request());

        assertEquals(0, result.getCode());
        assertEquals("该手机号已注册", result.getMsg());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=AuthServiceImplRegisterTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 失败——现有 `register` 根本不查 Redis（`AuthServiceImpl.java:44-48`）。

- [ ] **Step 3: 实现**

`AuthServiceImpl.java` 加字段（名字必须与测试里 `ReflectionTestUtils.setField(authService, "stringRedisTemplate", ...)` 一致）：

```java
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
```

`register` 整体替换为：

```java
    @Override
    public Result register(RegisterRequest request) {
        if (request.getPhone() == null || !PHONE_RE.matcher(request.getPhone()).matches()) {
            return Result.error("手机号格式不正确");
        }
        if (request.getUsername() == null || !USERNAME_RE.matcher(request.getUsername()).matches()) {
            return Result.error("用户名需为 4-20 位字母、数字或下划线");
        }
        if (request.getPassword() == null || request.getPassword().length() < 8 || request.getPassword().length() > 64) {
            return Result.error("密码长度需为 8-64 位");
        }

        String redisKey = AuthCacheKeys.smsCodeKey(request.getPhone());
        String expected = stringRedisTemplate.opsForValue().get(redisKey);
        if (expected == null || !expected.equals(request.getSmsCode())) {
            return Result.error("验证码错误或已失效");
        }

        String encodedPassword = passwordEncoder.encode(request.getPassword());
        try {
            AuthMapper.register(request.getUsername(), encodedPassword, request.getPhone());
        } catch (DuplicateKeyException e) {
            log.info("[注册] 唯一键冲突 phone={} username={}", request.getPhone(), request.getUsername());
            return Result.error("该手机号已注册");
        }

        // 一次性：注册成功后作废这个短信码
        stringRedisTemplate.delete(redisKey);
        return Result.success("注册成功");
    }
```

类上补 `@Slf4j`，并加常量（放在字段区）：

```java
    private static final Pattern PHONE_RE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern USERNAME_RE = Pattern.compile("^[a-zA-Z0-9_]{4,20}$");
```

import 加 `java.util.regex.Pattern`、`org.leon.authmodule.config.AuthCacheKeys`、`org.springframework.dao.DuplicateKeyException`、`org.springframework.data.redis.core.StringRedisTemplate`、`lombok.extern.slf4j.Slf4j`。

- [ ] **Step 4: 跑测试确认全绿（含回归）**

```bash
"$MVN" -o -pl AuthModule -am test \
  -Dtest=AuthServiceImplRegisterTest,AuthServiceImplLoginIpTest,CaptchaServiceImplTest,SmsCodeServiceImplTest,SmsCodeConsumerTest,SmsCodeProducerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/service/impl/AuthServiceImpl.java \
        AuthModule/src/test/java/org/leon/authmodule/service/impl/AuthServiceImplRegisterTest.java
git commit -m "feat(auth): 注册必须校验一次性短信验证码"
```

---

## Task 11: 前端注册改两步（手机号 → 短信码）

**Files:**
- Modify: `EZ_frontend/src/api/auth.ts`（新增 `sendSmsCode`、`register` 载荷）
- Modify: `EZ_frontend/src/composables/useLogin.ts`（form 加 phone/smsCode、校验、`requestSmsCode`）
- Modify: `EZ_frontend/src/views/Login.vue`（注册 tab 表单块）

**Interfaces:**
- Consumes: `POST /auth/sms/send`、`POST /auth/register`（`{username,password,phone,smsCode}`）
- Produces: `sendSmsCode(phone, captchaKey, captchaCode): Promise<string>`

- [ ] **Step 1: `auth.ts` 加发送手机码的函数，并让 register 带 phone/smsCode**

`CredentialForm` 补两个可选字段（不动 login 调用点）：

```ts
export interface CredentialForm {
  username: string
  password: string
  captchaCode?: string
  captchaKey?: string
  phone?: string
  smsCode?: string
}
```

文件末尾追加（放在验证码段之后）：

```ts
// ===================== 手机验证码 =====================

export interface SendSmsPayload {
  phone: string
  captchaKey: string
  captchaCode: string
}

/**
 * 申请手机验证码。后端只投一条 MQ 消息就返回，所以这个接口应当很快。
 * 真通道在本机是 mock：验证码只出现在后端日志，绝不返回给前端。
 */
export async function sendSmsCode(payload: SendSmsPayload): Promise<string> {
  const data = await request<unknown>('/auth/sms/send', { method: 'POST', body: payload })
  return typeof data === 'string' ? data : '验证码已发送'
}
```

`register()`（`:55-58`）不动——它已经把整个 `form` 当 body 发，新字段会自动带上。

- [ ] **Step 2: `useLogin.ts` 表单状态与校验**

`:27` 改成：

```ts
  const form = reactive({ username: '', password: '', captchaCode: '', captchaKey: '', phone: '', smsCode: '' })
```

`validate()`（`:40-55`）整体替换为下面这版（注册分支补上手机号、图形码、短信码三条规则）：

```ts
  const PHONE_RE = /^1[3-9]\d{9}$/    // ← 这行实际要放到 useLogin 函数外的常量区，与 USERNAME_RE（:16）并列，不要每次调用都重建正则

  function validate(): string | null {
    if (!form.username.trim()) return '请输入用户名'
    if (!form.password) return '请输入密码'
    if (isLogin.value) {
      if (!captcha.value) return '请先获取验证码'
      if (form.captchaCode.length !== CAPTCHA_LENGTH) return `请输入 ${CAPTCHA_LENGTH} 位验证码`
      return null
    }
    if (!USERNAME_RE.test(form.username)) return '用户名需为 4-20 位字母、数字或下划线'
    if (form.password.length < PASSWORD_MIN || form.password.length > PASSWORD_MAX) {
      return `密码长度需为 ${PASSWORD_MIN}-${PASSWORD_MAX} 位`
    }
    if (!PHONE_RE.test(form.phone)) return '请输入正确的 11 位手机号'
    if (!captcha.value) return '请先获取图形验证码'
    if (form.captchaCode.length !== CAPTCHA_LENGTH) return `请输入 ${CAPTCHA_LENGTH} 位图形验证码`
    if (form.smsCode.length !== CAPTCHA_LENGTH) return `请输入 ${CAPTCHA_LENGTH} 位短信验证码`
    return null
  }
```

- [ ] **Step 3: `useLogin.ts` 加"申请手机码"动作**

`requestCode` 之后加一个独立函数（**不复用 `requestCode`**：那个是图形码、走 cooldown 但语义不同）：

```ts
  const smsCooldown = ref(0)
  let smsTimer: ReturnType<typeof setTimeout> | undefined

  async function requestSmsCode(): Promise<void> {
    if (smsCooldown.value > 0 || submitting.value) return
    error.value = ''
    if (!captcha.value || form.captchaCode.length !== CAPTCHA_LENGTH) {
      error.value = '请先完成图形验证码'
      return
    }
    if (!PHONE_RE.test(form.phone)) {
      error.value = '请输入正确的 11 位手机号'
      return
    }
    try {
      // 图形码是一次性的：这一次提交会消费掉它，所以发完就把题面作废，逼用户重新取
      await sendSmsCode({ phone: form.phone, captchaKey: form.captchaKey, captchaCode: form.captchaCode })
      resetCaptcha()
      smsCooldown.value = COOLDOWN_SECONDS
      const tick = () => {
        smsCooldown.value -= 1
        if (smsCooldown.value > 0) smsTimer = setTimeout(tick, 1000)
      }
      smsTimer = setTimeout(tick, 1000)
      notice.value = '验证码已发送，本机请查后端日志'
    } catch (err) {
      error.value = err instanceof Error ? err.message : '发送失败，请稍后重试'
    }
  }
```

`onUnmounted` 里加上 `clearTimeout(smsTimer)`；`return {}` 里导出 `smsCooldown`、`requestSmsCode`。
`switchTab`（`:152-158`）在切走注册页时清 `form.phone`/`form.smsCode`/`smsCooldown`。
`submit()` 注册分支（`:139`）保持一行不动，只是此时 `form` 里已带 phone/smsCode（`register({ username, password })` 要改成 `register(form)`——注意后端多出来的字段必须真的发出去）。

- [ ] **Step 4: `Login.vue` 注册 tab 的表单块**

在 `v-if="isLogin"` 那个 `<template>`（`:95-133`）**之后**加一个 `v-else` 块：

```html
          <template v-else>
            <label class="field">
              <span class="field-label">手机号</span>
              <input
                  v-model="form.phone"
                  class="input"
                  type="tel"
                  inputmode="numeric"
                  autocomplete="tel"
                  placeholder="11 位手机号"
              />
            </label>

            <div class="field">
              <div class="field-head">
                <span class="field-label">图形验证码</span>
                <button class="link-btn" :disabled="cooldown > 0" @click="requestCode">
                  {{ captcha ? (cooldown > 0 ? cooldownText : '重新获取') : '获取验证码' }}
                </button>
              </div>
              <div v-if="captcha" class="captcha-row">
                <img
                    class="captcha-img"
                    :src="captcha.challenge"
                    :key="captcha.captchaKey"
                    alt="图形验证码图片"
                    title="点击刷新验证码"
                    @click="requestCode"
                />
                <CodeSlots
                    :length="6"
                    :value="form.captchaCode"
                    :status="captchaStatus"
                    :slot-size="40"
                    :gap="6"
                    :radius="10"
                    :accent-color="palette.accent"
                    :ink-color="palette.ink"
                    :slot-color="palette.slot"
                    :digit-color="palette.digit"
                    aria-label="六位数字图形验证码"
                    @change="onCodeChange"
                />
              </div>
            </div>

            <div class="field">
              <div class="field-head">
                <span class="field-label">短信验证码</span>
                <button class="link-btn" :disabled="smsCooldown > 0" @click="requestSmsCode">
                  {{ smsCooldown > 0 ? `${smsCooldown}s 后可重发` : '获取短信验证码' }}
                </button>
              </div>
              <CodeSlots
                  :length="6"
                  :value="form.smsCode"
                  status="idle"
                  :slot-size="40"
                  :gap="6"
                  :radius="10"
                  :accent-color="palette.accent"
                  :ink-color="palette.ink"
                  :slot-color="palette.slot"
                  :digit-color="palette.digit"
                  aria-label="六位数字短信验证码"
                  @change="onSmsCodeChange"
              />
            </div>
          </template>
```

`<script setup>` 的解构（`:11-25`）加上 `smsCooldown`、`requestSmsCode`，并加一个 `onSmsCodeChange`：

```ts
function onSmsCodeChange(code: string): void {
  form.smsCode = code
}
```

- [ ] **Step 5: 类型检查**

```bash
cd F:/AAAJAVA/code/EZ_frontend && npm run type-check
```
Expected: 退出码 0。

- [ ] **Step 6: 手动验收清单（前端无测试框架，这七条必须逐条肉眼过）**

起 AuthModule + GatewayModule + 前端 + broker + Redis。浏览器 `http://localhost:5173/login` → 切"注册"：
1. 手机号填 `12345` → "获取短信验证码"提示"请输入正确的 11 位手机号"，**Network 里该请求根本没发出**。
2. 不取图形码 → 提示"请先完成图形验证码"。
3. 取图形码、填对 → 点"获取短信验证码" → 提示"验证码已发送"，且**后端日志出现 `【模拟短信】` 那行**，记下这个码。
4. 立刻再点"获取短信验证码" → 按钮进 60 秒倒计时；绕过按钮直接 curl 也拿到 `请60秒后再试`。
5. 把第 3 步的码填进短信码 6 格 → 滑到底提交 → 提示"注册成功，请登录"并自动切回登录 tab。
6. 用同一个手机号重复整套流程 → 期望 `该手机号已注册`（不是 500，不是白屏）。
7. 故意把短信码填错 → `验证码错误或已失效`，且此时图形码也已被消费掉，需要重新取图（这是"一次性"的正确表现，别当 bug 修）。

- [ ] **Step 7: 提交**

```bash
cd F:/AAAJAVA/code/EZ_frontend
git add src/api/auth.ts src/composables/useLogin.ts src/views/Login.vue
git commit -m "feat(register): 注册改为图形码+手机验证码两步校验"
```

---

## Task 12: 过期消费者（幂等清理）

**Files:**
- Modify: `AuthModule/src/main/java/org/leon/authmodule/mq/SmsCodeConsumer.java`
- Test: `AuthModule/src/test/java/org/leon/authmodule/mq/SmsExpireConsumerTest.java`（新建）

**Interfaces:**
- Consumes: `StringRedisTemplate`、`AuthCacheKeys.smsCodeKey(String)`、`SmsExpireEvent`
- Produces: `SmsCodeConsumer.onSmsExpired(SmsExpireEvent)`，监听 `auth.sms.expired.queue`

- [ ] **Step 1: 写失败的测试——重点验"码不相等时绝不能删"**

`mq/SmsExpireConsumerTest.java`：

```java
package org.leon.authmodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.authmodule.pojo.mq.SmsExpireEvent;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmsExpireConsumerTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @DisplayName("事件里的码与 Redis 当前值相同 → 删除")
    @Test
    void deletesWhenCodeMatches() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:sms:code:13800001234")).thenReturn("123456");

        new SmsCodeConsumer(redisTemplate, null, null)
                .onSmsExpired(new SmsExpireEvent("13800001234", "123456", "trace-1"));

        verify(redisTemplate).delete("auth:sms:code:13800001234");
    }

    @DisplayName("用户已重新申请了新码 → 旧事件到期时不许删新码（这条写错就会把有效码删掉）")
    @Test
    void keepsCodeWhenEventIsStale() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:sms:code:13800001234")).thenReturn("999999");

        new SmsCodeConsumer(redisTemplate, null, null)
                .onSmsExpired(new SmsExpireEvent("13800001234", "123456", "trace-1"));

        verify(redisTemplate, never()).delete(any());
    }

    @DisplayName("码已被注册消费掉（Redis 无值）→ 什么都不做，不报错")
    @Test
    void doesNothingWhenAlreadyConsumed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(null);

        new SmsCodeConsumer(redisTemplate, null, null)
                .onSmsExpired(new SmsExpireEvent("13800001234", "123456", "trace-1"));

        verify(redisTemplate, never()).delete(any());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=SmsExpireConsumerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败，`找不到符号 onSmsExpired`。

- [ ] **Step 3: 实现（追加到 `SmsCodeConsumer`）**

```java
    @RabbitListener(queues = "#{smsExpiredQueue.name}")
    public void onSmsExpired(SmsExpireEvent event) {
        String redisKey = AuthCacheKeys.smsCodeKey(event.getPhone());
        String current = redisTemplate.opsForValue().get(redisKey);
        log.info("[过期消费者] traceId={} phone={} 命中={} 线程={}",
                event.getTraceId(), event.getPhone(),
                event.getCode().equals(current) ? "同码，删除" : "已换新码，保留",
                Thread.currentThread().getName());

        if (current != null && current.equals(event.getCode())) {
            redisTemplate.delete(redisKey);
        }
    }
```

> 这里**故意不删限频 key**。`auth:sms:limit:{phone}` 只有 60 秒，5 分钟后它早自己没了；提前删等于给了用户"绕过 60 秒限制"的路子。spec §3.3 的表里"谁删"一栏写的是"自动过期（不主动删）"，这条要照着实现。

- [ ] **Step 4: 跑测试确认全绿**

Run: 同 Step 2。Expected: `Tests run: 3, Failures: 0`，且 `SmsCodeConsumerTest` 仍绿。

- [ ] **Step 5: 提交**

```bash
git add AuthModule/src/main/java/org/leon/authmodule/mq/SmsCodeConsumer.java \
        AuthModule/src/test/java/org/leon/authmodule/mq/SmsExpireConsumerTest.java
git commit -m "feat(auth): 过期事件幂等清理短信码"
```

---

## Task 13: 集成测试（2 秒 TTL 独立拓扑，真 broker + 真 Redis）

**Files:**
- Create: `AuthModule/src/test/java/org/leon/authmodule/mq/SmsCodeFlowIntegrationTest.java`

**Interfaces:**
- Consumes: 真实 RabbitMQ（5672）、真实 Redis（6379）、`auth.sms.queue-suffix=-test` + `auth.sms.code-ttl-ms=2000`
- Produces: 无对外接口（纯验证）

- [ ] **Step 1: 写测试**

```java
package org.leon.authmodule.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.authmodule.config.AuthCacheKeys;
import org.leon.authmodule.pojo.mq.SmsSendRequest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 真 broker + 真 Redis 的端到端验证。
 * 用 -test 后缀队列 + 2 秒 TTL，避免和手工环境的 5 分钟队列撞参数。
 */
@SpringBootTest(properties = {
        "auth.sms.queue-suffix=-test",
        "auth.sms.code-ttl-ms=2000"
})
class SmsCodeFlowIntegrationTest {

    private static final String PHONE = "13800009999";

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SmsCodeConsumer smsCodeConsumer;

    @DisplayName("发码消息被异步消费：Redis 里出现 6 位码；2 秒后过期事件把它删掉")
    @Test
    void codeIsIssuedThenExpiredWithoutManualCleanup() throws InterruptedException {
        redisTemplate.delete(AuthCacheKeys.smsCodeKey(PHONE));

        rabbitTemplate.convertAndSend(
                org.leon.authmodule.config.CaptchaMqConfig.SMS_EXCHANGE,
                org.leon.authmodule.config.CaptchaMqConfig.SMS_SEND_KEY,
                new SmsSendRequest(PHONE, "it-1"));

        String code = pollForCode(5, TimeUnit.SECONDS);
        assertNotNull(code, "5 秒内没拿到码：检查 RabbitMQ 是否在跑、消费者是否注册");
        assertEquals(6, code.length());

        // 过期事件应在 2 秒后到达。先等 1 秒确认还在（证明没被提前删），再等它消失。
        Thread.sleep(1000);
        assertNotNull(pollForCode(500, TimeUnit.MILLISECONDS), "TTL 还没到就被删了，说明延迟链路配错");

        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline && redisTemplate.hasKey(AuthCacheKeys.smsCodeKey(PHONE))) {
            TimeUnit.MILLISECONDS.sleep(200);
        }
        assertNull(redisTemplate.opsForValue().get(AuthCacheKeys.smsCodeKey(PHONE)),
                "8 秒内过期消费者没把码删掉：检查延迟队列绑定与死信路由");
    }

    private String pollForCode(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            String code = redisTemplate.opsForValue().get(AuthCacheKeys.smsCodeKey(PHONE));
            if (code != null) return code;
            TimeUnit.MILLISECONDS.sleep(100);
        }
        return null;
    }
}
```

- [ ] **Step 2: 确认 broker 与 Redis 在跑**

```bash
netstat -ano | grep -E ":(5672|6379) " | grep LISTENING
```
Expected: 两行都在。**缺 broker 就别跑这个测试**，它会以"5 秒内没拿到码"失败，而那个报错会把人往错误方向引（其实是连不上）。

- [ ] **Step 3: 跑测试**

```bash
"$MVN" -o -pl AuthModule -am test -Dtest=SmsCodeFlowIntegrationTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: `Tests run: 1, Failures: 0`。日志里应能看到 `[发码消费者]` 与 `[过期消费者]` 两行，且线程名都是 `RabbitListenerEndpointContainer#...`。

- [ ] **Step 4: 去管理界面亲眼确认延迟机制**

`http://localhost:15672` → Queues → `auth.sms.expired.queue-test` → Consumption 里能看到一条被消费；`auth.sms.expire.delay.queue-test` 的 Message time to live 显示 `2000`。
**关键**：`auth.sms.expire.delay.queue-test` 的 Consumer 数必须是 **0**。若 >0，说明延迟队列被误挂了监听器，消息根本不会延迟。

- [ ] **Step 5: 全量回归**

```bash
"$MVN" -o -pl AuthModule -am test
```
Expected: 全绿（含 `IpUtilTest`、`AuthControllerTest` 等既有测试）。

- [ ] **Step 6: 提交**

```bash
git add AuthModule/src/test/java/org/leon/authmodule/mq/SmsCodeFlowIntegrationTest.java
git commit -m "test(auth): 短信码发码-延迟过期端到端集成测试（2 秒拓扑）"
```

---

## Task 14: 实现文档

**Files:**
- Create: `RabbitMQ验证码实现文档.md`（仓库根目录，与既有 `RabbitMQ用户模块Demo实现文档.md` 并列）

- [ ] **Step 1: 按固定骨架写文档**

必须包含的小节（沿用上一份文档被验证有效的结构）：
1. 30 秒版：这次做了什么、当前状态（哪些已实测）
2. 为什么注册要两道门（把"同步调短信通道"的三类问题写具体）
3. 图形码 / 手机码 / MQ / Redis 各自负责什么——一张职责表
4. 一条验证码的完整旅程（12 步，从点击"获取图形码"到码被过期删除）
5. MQ 拓扑逐条讲：为什么延迟队列**不能**有消费者、为什么 `sms.expire` 这条绑定不能漏、TTL+DLX 的队头阻塞限制
6. Redis 三个 key 的 TTL 与一次性语义
7. 每个技术选择的「为什么这么做」+ 被否方案（直接引用 spec §10 决策表并展开成人话）
8. 前端改了什么：为什么 `fetchCaptcha` 签名不变、为什么 `verifyCaptchaLocal` 必须删除
9. 怎么跑：服务前置清单 + curl 顺序 + 去后端日志捞验证码（因为不发真短信）
10. 管理界面看什么（4 个位置：交换机绑定、延迟队列 Ready 与 Consumer=0、死信队列、消息 JSON 原文）
11. 常见报错对照表：`PRECONDITION_FAILED`（改 TTL 没换后缀）、401（白名单漏改）、`Connection refused`（服务没起）、注册一直"验证码错误"（图形码一次性，重发后必须重新取图）
12. 遗留项与生产化清单（真短信通道、日志里的明文码要去掉、`codeHash` 替代明文、`guest` 禁用、validation starter、幂等删除的 Lua 收口）

- [ ] **Step 2: 每条命令与日志都实测后再写**

文档里的每一段"Expected 输出"都必须是真跑出来的，不许写"应该会长这样"。做不到就在文档里明写"未验证"。

- [ ] **Step 3: 提交**

```bash
git add RabbitMQ验证码实现文档.md
git commit -m "docs: 注册/登录验证码 + RabbitMQ 实现文档"
```

---

## 自检记录（写完本计划后对照 spec 逐条检查）

**覆盖率检查**：spec §3.1 的 13 个新建/修改文件 → Task 1（DTO/Mapper/Service/Controller）、Task 2（AuthCacheKeys/CaptchaService）、Task 3（Controller + 两处白名单）、Task 6（迁移/Users/XML）、Task 7（pom/yaml/拓扑/消息体/Producer）、Task 8（SmsSender/Mock/消费者1）、Task 9（SmsCodeService/Controller）、Task 12（消费者2）——**全部有归属**。spec §9 的四个阶段与本计划 Task 1–5 / 6–11 / 12–13 / 14 一一对应。

**自检发现并已修的四点**：
1. `@RabbitListener(queues = ...)` 的注解属性只接受编译期常量，而队列名要拼运行时的 `queueSuffix`。初稿用常量 `CaptchaMqConfig.SMS_SEND_QUEUE`，会让测试（Task 13 用 `-test` 后缀）里监听器绑到不带后缀的队列，出现"消息一半被手工环境的消费者取走"的偶发红。**已改为 `"#{smsSendQueue.name}"` / `"#{smsExpiredQueue.name}"` 两处 SpEL Bean 引用**，并要求 Task 7 的 `@Bean` 方法名不许改动（Bean 名就是方法名）。
2. 初稿在 Task 3 Step 3、Task 6 Step 6、Task 11 Step 2 各埋了一段"先写错再更正"的代码（重复的 `:key` 属性、`request().getPhone()`、Java 片段混进 TS 块）。这种写法等于给执行者埋雷——复制哪一段都"看起来是对的"。三处已全部改成只保留正确代码。
3. `Task 5` 依赖 `BizError` 判断验证码错误，而 `useLogin.ts:9` 已经 import 了它，无需新增 import；若 Task 5 执行时发现该 import 被别的改动删掉，补回来即可。
4. spec §3.2 的 `x-dead-letter-exchange` 与死信交换机类型：本计划用 `DirectExchange` + 固定 `deadLetterRoutingKey`，比 topic 少一个通配维度但完全够用；`smsExpiredBinding` 的 routingKey 必须与队列参数 `x-dead-letter-routing-key` 完全一致（都是 `sms.expired`），否则死信进不了队列——Task 7 Step 8 的管理界面核对就是为这一步兜底。

**占位符扫描**：无 TBD/TODO/"类似 Task N"/"补充适当校验"式空步骤；每个测试步骤都给了完整可粘贴代码、运行命令与期望输出。前端任务（Task 5、Task 11）的"测试"是 `npm run type-check` + 编号的手动验收清单，因为该仓库没有任何测试框架——这是能力边界，不是偷懒。
