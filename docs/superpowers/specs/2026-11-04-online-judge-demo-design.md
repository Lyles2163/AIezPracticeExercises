# 在线 OJ 判题功能 —— Docker 沙箱 + 多语言 + MQ 队列（零基础版）

> 目标读者：Java / Spring 初学者。
> 目标：在 `PracticeModule` 上，加一个「提交代码 → 进消息队列 → 后台 Docker 沙箱判题 → 前端轮询结果」的完整在线判题（OJ）功能。
> 本版相对上一版的升级：**只用 Docker 沙箱**、**支持 Java/Python/C++ 多语言**、**用 RabbitMQ 队列代替线程池**。

---

## 一、先用大白话理解"在线判题"是什么

选择题判分是「你选的 A vs 正确答案 A」。编程题完全不同：

```
你写一段代码  →  服务器把它跑起来  →  喂测试数据进 stdin
             →  读它打印到 stdout 的结果  →  和标准答案比对  →  一致=通过(AC)
```

这个"服务器替你跑代码、自动打分"的东西就是 **Online Judge（OJ）**，力扣、牛客都是。

它难在两件事：
1. **怎么安全地跑别人写的代码** → 用 **Docker 容器**把代码关进小黑屋；
2. **怎么让"跑代码"不卡住别人** → 用 **消息队列（MQ）** 把判题任务排好队慢慢做。

本版就是围绕这两点设计的。

---

## 二、整体架构（一张图）

```
① 前端 POST /practice/oj/submit
        │
        ▼
┌──────────────────────────────────────────────┐
│ PracticeModule（生产者）                       │
│  1. 校验题目 + 语言 + 代码长度                  │
│  2. 插入 oj_submission 一行，状态=PENDING       │
│  3. 往 RabbitMQ 发一条消息：{submissionId:42}    │
│  4. 立刻返回 {submissionId:42}                  │  ← 不等判题！
└──────────────────────────────────────────────┘
        │
        ▼  消息进入 oj.judge.queue（排队）
┌──────────────────────────────────────────────┐
│ RabbitMQ                                       │
│  exchange: oj.judge.exchange                   │
│  queue:    oj.judge.queue                      │
│  失败兜底: oj.judge.dead.queue（死信队列）       │
└──────────────────────────────────────────────┘
        │
        ▼  PracticeModule（消费者）拉取消息
┌──────────────────────────────────────────────┐
│ OjJudgeConsumer                                │
│  1. 按 id 查提交记录，状态置 RUNNING             │
│  2. 用 Docker 沙箱编译用户代码（一次）            │
│  3. 逐个测试用例起容器跑，比对输出                │
│  4. 写回最终状态 AC/WA/TLE/CE/RE                 │
└──────────────────────────────────────────────┘
        │
        ▼
② 前端每秒 GET /practice/oj/submission/42 轮询
   直到状态不再是 PENDING / RUNNING，显示结果
```

### 为什么用 MQ 而不是"线程池后台跑"？

线程池的版本有个硬伤：**服务一重启，正在排队的判题任务全没了**。MQ 不会：

| 场景 | 线程池 | MQ |
|---|---|---|
| 服务重启 | 排队任务丢失 | 消息还在队列里，重启后继续判 |
| 判题太慢堆积 | 线程池满，新任务被拒 | 消息堆在队列里，慢慢消化 |
| 想多开判题机 | 只能在一台机器上 | 多个消费者共同抢队列，天然分布式 |
| 某次判题失败 | 很难重试 | 自动重试 + 死信兜底 |

打个比方：线程池是"店里就一个厨师，来一个客人做一个，厨师下班客人就没人管了"；MQ 是"所有点单先写进订单本，厨师换班了单子还在"。

---

## 三、Docker 沙箱：怎么把用户代码关进"小黑屋"

### 3.1 为什么必须用 Docker

如果直接在你的 Spring 进程里跑用户代码：

```java
while (true) {}      // 死循环，烧满 CPU
System.exit(0);      // 直接杀掉你的服务器进程
new File("/etc/passwd").read()   // 偷读你机器的文件
```

Docker 容器给你四道锁，全在一次 `docker run` 的参数里：

```bash
docker run --rm                      # 跑完自动删容器
  --network none                     # ① 禁网：不能连外网、不能内网横向攻击
  --memory 256m --memory-swap 256m    # ② 限内存：超了直接 OOM 杀，且禁用 swap
  --cpus 1                           # ③ 限 CPU：只能用一个核，死循环也炸不了整机
  --pids-limit 64                    # ④ 限进程数：防 fork 炸弹
  --security-opt no-new-privileges   # ⑤ 禁止提权
  -v <临时目录>:/box -w /box          # 把你的代码目录挂进去
  <语言镜像> sh -c "<编译命令> && <运行命令>"
```

判题期间用户代码只活在这个容器里，`--rm` 让它跑完即焚。

### 3.2 一次判题的 Docker 操作

**编译做一次，运行做 N 次**（N = 测试用例数量）。编译产物（`.class`/可执行文件）落在挂载的宿主临时目录里，后续运行容器直接读到，不用重复编译。

```
提交 → 创建临时目录，写入 main.xxx
     → docker run（编译容器）：编译，产物写回临时目录
        ├─ 编译失败 → CE（编译错误），直接结束
        └─ 编译成功 ↓
     → 对每个测试用例：
          docker run（运行容器）：喂 stdin，限时读 stdout
          比对期望输出
     → 删除临时目录
```

### 3.3 超时的坑（初学者最容易漏）

超时的时候你 `kill` 掉的是宿主上的 `docker run` 命令，**容器本身可能还在跑**！所以运行容器要起名字，超时后额外 `docker kill <名字>`：

```java
String name = "oj-" + UUID.randomUUID().toString().substring(0, 12);
// docker run --name <name> ...
boolean finished = process.waitFor(timeLimitMs, TimeUnit.MILLISECONDS);
if (!finished) {
    process.destroyForcibly();              // 杀 docker CLI
    new ProcessBuilder("docker", "kill", name).start().waitFor(3, TimeUnit.SECONDS); // 杀容器
    return RunResult.timeout(elapsed);
}
```

---

## 四、多语言怎么支持

把"语言"抽象成一个枚举，每种语言只需要回答四个问题：**用哪个镜像、源码文件叫什么、怎么编译、怎么运行**。

```java
package org.leon.practicemodule.oj.sandbox;

/**
 * 每种语言的判题参数。加一门语言 = 在这里加一个枚举项，
 * 判题器、沙箱、控制器都不用改（这才是"可扩展"）。
 */
public enum JudgeLanguage {
    JAVA("java", "eclipse-temurin:17-jdk", "Main.java",
            "javac -encoding UTF-8 Main.java",
            "java -Xmx{mem}m Main"),

    PYTHON("python3", "python:3.11-slim", "main.py",
            "python3 -m py_compile main.py",     // 只做语法检查，语法错=CE
            "python3 main.py"),

    CPP("cpp", "gcc:13", "main.cpp",
            "g++ -O2 -std=c++17 -o main main.cpp",
            "./main");

    private final String code;
    private final String image;
    private final String sourceFile;
    private final String compileCommand;
    private final String runCommand;

    JudgeLanguage(String code, String image, String sourceFile,
                  String compileCommand, String runCommand) {
        this.code = code;
        this.image = image;
        this.sourceFile = sourceFile;
        this.compileCommand = compileCommand;
        this.runCommand = runCommand;
    }

    public String code()          { return code; }
    public String image()         { return image; }
    public String sourceFile()    { return sourceFile; }
    public String compileCommand(){ return compileCommand; }

    /** {mem} 是占位符：Java 需要 -Xmx，Python/C++ 靠 docker --memory 兜底 */
    public String runCommand(int memoryLimitMb) {
        return runCommand.replace("{mem}", String.valueOf(memoryLimitMb));
    }

    public static JudgeLanguage of(String code) {
        for (JudgeLanguage lang : values()) {
            if (lang.code.equalsIgnoreCase(code)) {
                return lang;
            }
        }
        throw new IllegalArgumentException("暂不支持的语言：" + code);
    }
}
```

**为什么每种语言都要有编译步骤？**

- Java：`javac`，天经地义；
- Python：用 `py_compile` 做**语法检查**，这样语法错误能判成 CE（而不是运行时才崩成 RE）；
- C++：`g++` 编译成可执行文件。

三种语言都"先编译、再运行"，判题器逻辑就完全统一，不用写三套。

**镜像要先拉好**（一次性）：

```bash
docker pull eclipse-temurin:17-jdk
docker pull python:3.11-slim
docker pull gcc:13
```

---

## 五、数据库设计（3 张新表）

你现有 `qb_question` 存的是选择题答案（`answer_json` 存 `"A"`、`["A","C"]`），编程题要**题目描述 + 多组测试用例 + 提交记录**，结构差太远，所以独立建表。沿用你项目 `db/migration` 手写 DDL 习惯：无物理外键、`utf8mb4`、`DATETIME(3)`。

```sql
-- 2026-11-04-oj.sql
-- 目标库：ezpracticeexercises。无 Flyway，人工执行；重跑会因表已存在失败（有意为之）。

-- 编程题本身
CREATE TABLE oj_problem (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  subject_id      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '所属科目，0=通用',
  title           VARCHAR(128) NOT NULL COMMENT '题目标题',
  description     MEDIUMTEXT   NOT NULL COMMENT '题目描述(Markdown)',
  template_code   MEDIUMTEXT   NULL     COMMENT '初始代码模板',
  difficulty      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1简单 2中等 3困难',
  time_limit_ms   INT UNSIGNED NOT NULL DEFAULT 2000 COMMENT '单用例时限(毫秒)',
  memory_limit_mb INT UNSIGNED NOT NULL DEFAULT 256  COMMENT '内存限制(MB)',
  status          TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1启用 0下架',
  create_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_subject_status (subject_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OJ 编程题';

-- 测试用例：判分就靠它
CREATE TABLE oj_testcase (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  problem_id      BIGINT UNSIGNED NOT NULL COMMENT 'oj_problem.id，无物理外键',
  seq             INT UNSIGNED NOT NULL COMMENT '用例顺序，从1开始',
  input_data      MEDIUMTEXT NULL COMMENT '喂给 stdin 的输入',
  expected_output MEDIUMTEXT NOT NULL COMMENT '期望的 stdout',
  is_sample       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '1=示例(用户可见) 0=隐藏用例',
  create_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_problem_seq (problem_id, seq),
  KEY idx_problem_sample (problem_id, is_sample, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OJ 测试用例';

-- 每次提交一行，判题结果也回写到这里
CREATE TABLE oj_submission (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id        BIGINT UNSIGNED NOT NULL,
  problem_id     BIGINT UNSIGNED NOT NULL,
  language       VARCHAR(16) NOT NULL DEFAULT 'java' COMMENT 'java/python3/cpp',
  source_code    MEDIUMTEXT NOT NULL COMMENT '提交的源码',
  status         VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                 COMMENT 'PENDING排队 RUNNING判题中 AC WA TLE MLE CE RE SE',
  score          INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '通过用例数',
  total          INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '用例总数',
  time_used_ms   INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最大单用例耗时',
  memory_used_kb INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最大单用例内存(Docker版可读cgroup, demo先留0)',
  error_msg      TEXT NULL COMMENT 'CE/RE 的详细错误，给用户看',
  create_time    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_problem (user_id, problem_id, id),
  KEY idx_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OJ 提交记录';
```

> 为什么存源码？为了"查看历史提交""我上次怎么写的"，真实 OJ 都会存。

---

## 六、RabbitMQ 拓扑（照你项目现有风格）

你项目 `LoginEventQueueConfig` / `CaptchaMqConfig` 已经是标准写法：**Topic 交换机 + 持久化队列 + 死信交换机 + 死信队列 + 绑定**。新 OJ 完全照抄这套。

```java
package org.leon.practicemodule.oj.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OJ 判题队列拓扑：1 主交换机 + 主队列 + 死信交换机 + 死信队列 + 2 条绑定。
 * 生产者与消费者在同一个服务(PracticeModule)内，但交换机/路由键仍写成常量，
 * 与项目里 LoginMqConfig 保持一致的跨边界显式契约风格。
 */
@Configuration
public class OjJudgeMqConfig {

    public static final String JUDGE_EXCHANGE  = "oj.judge.exchange";
    public static final String JUDGE_SUBMIT_KEY = "oj.judge.submit";
    public static final String JUDGE_QUEUE      = "oj.judge.queue";

    public static final String JUDGE_DLX        = "oj.judge.dlx";
    public static final String JUDGE_DEAD_KEY   = "oj.judge.dead";
    public static final String JUDGE_DEAD_QUEUE = "oj.judge.dead.queue";

    @Bean
    public TopicExchange ojJudgeExchange() {
        return new TopicExchange(JUDGE_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange ojJudgeDlx() {
        return new DirectExchange(JUDGE_DLX, true, false);
    }

    @Bean
    public Queue ojJudgeQueue() {
        return QueueBuilder.durable(JUDGE_QUEUE)
                .deadLetterExchange(JUDGE_DLX)
                .deadLetterRoutingKey(JUDGE_DEAD_KEY)
                .build();
    }

    @Bean
    public Queue ojJudgeDeadQueue() {
        return QueueBuilder.durable(JUDGE_DEAD_QUEUE).build();
    }

    @Bean
    public Binding ojJudgeBinding() {
        return BindingBuilder.bind(ojJudgeQueue()).to(ojJudgeExchange()).with(JUDGE_SUBMIT_KEY);
    }

    /** ★ 缺这条绑定，死信能进交换机但没有队列接，死信链路静默失效 */
    @Bean
    public Binding ojJudgeDeadBinding() {
        return BindingBuilder.bind(ojJudgeDeadQueue()).to(ojJudgeDlx()).with(JUDGE_DEAD_KEY);
    }

    /** JSON 序列化：管理界面能直接读到人类可读的消息体 */
    @Bean
    public MessageConverter ojJsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
```

**消息体只放 submissionId，不放源码。** 源码可能几十 KB，MQ 消息应该小而稳；**数据库才是事实之源**，消费者按 id 回查即可。

```java
package org.leon.practicemodule.oj.pojo.mq;

import lombok.Data;

/** 判题任务消息：只投递 id，正文由消费者回库读取 */
@Data
public class OjJudgeEvent {
    private Long submissionId;
}
```

生产者：

```java
package org.leon.practicemodule.oj.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.practicemodule.oj.config.OjJudgeMqConfig;
import org.leon.practicemodule.oj.pojo.mq.OjJudgeEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OjJudgePublisher {

    private final RabbitTemplate rabbitTemplate;

    public OjJudgePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(Long submissionId) {
        OjJudgeEvent event = new OjJudgeEvent();
        event.setSubmissionId(submissionId);
        log.info("[OJ判题生产者] submissionId={}", submissionId);
        rabbitTemplate.convertAndSend(
                OjJudgeMqConfig.JUDGE_EXCHANGE, OjJudgeMqConfig.JUDGE_SUBMIT_KEY, event);
    }
}
```

消费者（这里是判题真正的执行入口）：

```java
package org.leon.practicemodule.oj.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.practicemodule.oj.config.OjJudgeMqConfig;
import org.leon.practicemodule.oj.mapper.*;
import org.leon.practicemodule.oj.pojo.*;
import org.leon.practicemodule.oj.pojo.mq.OjJudgeEvent;
import org.leon.practicemodule.oj.sandbox.JudgeLanguage;
import org.leon.practicemodule.oj.service.OjJudger;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class OjJudgeConsumer {

    /** 这些是终态：说明已经判完了，重复消息直接忽略 */
    private static final Set<String> TERMINAL =
            Set.of("AC", "WA", "TLE", "MLE", "CE", "RE", "SE");

    private final OjSubmissionMapper submissionMapper;
    private final OjProblemMapper problemMapper;
    private final OjTestcaseMapper testcaseMapper;
    private final OjJudger judger;

    public OjJudgeConsumer(OjSubmissionMapper submissionMapper,
                           OjProblemMapper problemMapper,
                           OjTestcaseMapper testcaseMapper,
                           OjJudger judger) {
        this.submissionMapper = submissionMapper;
        this.problemMapper = problemMapper;
        this.testcaseMapper = testcaseMapper;
        this.judger = judger;
    }

    @RabbitListener(queues = OjJudgeMqConfig.JUDGE_QUEUE)
    public void onJudgeRequest(OjJudgeEvent event) {
        Long id = event.getSubmissionId();
        OjSubmission submission = submissionMapper.selectById(id);
        if (submission == null) {
            // 重试一百次也不会凭空出现这条记录，直接进死信，不占用重试
            throw new AmqpRejectAndDontRequeueException("提交记录不存在: " + id);
        }
        if (TERMINAL.contains(submission.getStatus())) {
            log.info("[OJ判题消费者] submissionId={} 已是终态 {}，忽略重复消息", id, submission.getStatus());
            return;
        }

        // PENDING 或上次判到一半(RUNNING，比如服务重启)都继续判。
        // 判题本身是幂等的：重判一遍结果一样，不会错，所以不做"只允许PENDING→RUNNING"的硬闸门。
        submissionMapper.markRunning(id);
        try {
            OjProblem problem = problemMapper.selectById(submission.getProblemId());
            List<OjTestcase> cases = testcaseMapper.selectByProblemId(submission.getProblemId());
            JudgeLanguage language = JudgeLanguage.of(submission.getLanguage());

            OjJudger.Verdict verdict =
                    judger.judge(problem, cases, submission.getSourceCode(), language);
            submissionMapper.markFinished(id, verdict.status(), verdict.passed(), verdict.total(),
                    verdict.timeUsedMs(), verdict.errorMsg());
            log.info("[OJ判题消费者] submissionId={} verdict={} {}/{}",
                    id, verdict.status(), verdict.passed(), verdict.total());
        } catch (RuntimeException e) {
            // 数据已存在但 Docker/判题器暂时异常时，让异常抛回 RabbitMQ，按配置重试；
            // 重试耗尽后消息进入死信队列，由死信消费者标记 SE。
            log.error("[OJ判题消费者] submissionId={} 判题异常，交给 MQ 重试", id, e);
            throw e;
        }
    }
}
```

死信消费者（单独一个文件 `mq/OjJudgeDeadConsumer.java`）：**重试耗尽后必须落一个终态**，否则提交记录会永远卡在 `RUNNING`，前端一直转圈。

```java
package org.leon.practicemodule.oj.mq;

import lombok.extern.slf4j.Slf4j;
import org.leon.practicemodule.oj.config.OjJudgeMqConfig;
import org.leon.practicemodule.oj.mapper.OjSubmissionMapper;
import org.leon.practicemodule.oj.pojo.mq.OjJudgeEvent;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OjJudgeDeadConsumer {

    private final OjSubmissionMapper submissionMapper;

    public OjJudgeDeadConsumer(OjSubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    @RabbitListener(queues = OjJudgeMqConfig.JUDGE_DEAD_QUEUE)
    public void onDead(OjJudgeEvent event) {
        Long id = event.getSubmissionId();
        log.error("[OJ判题死信] submissionId={} 重试耗尽，标记 SE", id);
        submissionMapper.markFinished(id, "SE", 0, 0, 0,
                "系统错误：判题服务暂时不可用，请稍后重试");
    }
}
```

---

## 七、沙箱与判题器代码

### 7.1 沙箱接口与结果类型

```java
// sandbox/CodeSandbox.java
public interface CodeSandbox {
    /** 编译，成功返回带临时目录+语言的上下文；失败抛 OjCompileException */
    CompileContext compile(String sourceCode, JudgeLanguage language);
    /** 在容器里跑一个用例 */
    RunResult run(CompileContext ctx, String input, int timeLimitMs, int memoryLimitMb);
    /** 删除临时目录 */
    void cleanup(CompileContext ctx);
}

// sandbox/CompileContext.java
public record CompileContext(Path workDir, JudgeLanguage language) {}

// sandbox/OjCompileException.java
public class OjCompileException extends RuntimeException {
    public OjCompileException(String message) { super(message); }
}

// sandbox/RunResult.java
public record RunResult(RunStatus status, String stdout, String stderr, long elapsedMs) {
    public enum RunStatus { OK, TIMEOUT, RUNTIME_ERROR }

    public static RunResult ok(String stdout, long elapsedMs) {
        return new RunResult(RunStatus.OK, stdout, "", elapsedMs);
    }
    public static RunResult timeout(long elapsedMs) {
        return new RunResult(RunStatus.TIMEOUT, "", "", elapsedMs);
    }
    public static RunResult runtimeError(String stderr, long elapsedMs) {
        return new RunResult(RunStatus.RUNTIME_ERROR, "", stderr, elapsedMs);
    }
}
```

### 7.2 `DockerSandbox`（完整实现）

关键设计：**输出重定向到文件**，避免 `waitFor` 之后才读 pipeline 导致缓冲区写满死锁（这是判题器最常见的坑）。

```java
package org.leon.practicemodule.oj.sandbox;

import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Docker 沙箱：每次编译/运行都起一个 --rm 容器，跑完即焚。
 * 编译做一次，运行做 N 次（N=用例数），编译产物经挂载目录复用。
 */
@Component
public class DockerSandbox implements CodeSandbox {

    private static final int COMPILE_TIMEOUT_MS = 15_000;
    private static final int COMPILE_MEMORY_MB  = 512;
    private static final int MAX_OUTPUT_BYTES   = 1 << 20;   // 输出最多读 1MB，防内存打爆

    @Override
    public CompileContext compile(String sourceCode, JudgeLanguage language) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("oj-");
            // 容器内以 root 运行，挂载目录必须可写
            dir.toFile().setWritable(true, false);
            Files.writeString(dir.resolve(language.sourceFile()), sourceCode, StandardCharsets.UTF_8);

            ExecOutcome r = exec(language.image(), language.compileCommand(),
                    dir, "", COMPILE_TIMEOUT_MS, COMPILE_MEMORY_MB);

            if (r.timedOut()) {
                throw new OjCompileException("编译超时");
            }
            if (r.exitCode() != 0) {
                String msg = (r.stderr() + r.stdout()).strip();
                throw new OjCompileException(msg.isBlank() ? "编译失败" : msg);
            }
            return new CompileContext(dir, language);
        } catch (OjCompileException e) {
            deleteQuietly(dir);
            throw e;
        } catch (Exception e) {
            deleteQuietly(dir);
            throw new OjCompileException("编译异常：" + e.getMessage());
        }
    }

    @Override
    public RunResult run(CompileContext ctx, String input, int timeLimitMs, int memoryLimitMb) {
        ExecOutcome r = exec(ctx.language().image(),
                ctx.language().runCommand(memoryLimitMb),
                ctx.workDir(), input == null ? "" : input, timeLimitMs, memoryLimitMb);

        if (r.timedOut()) {
            return RunResult.timeout(r.elapsedMs());
        }
        if (r.exitCode() != 0) {
            String err = r.stderr().isBlank() ? "退出码 " + r.exitCode() : r.stderr();
            return RunResult.runtimeError(err, r.elapsedMs());
        }
        return RunResult.ok(r.stdout(), r.elapsedMs());
    }

    @Override
    public void cleanup(CompileContext ctx) {
        deleteQuietly(ctx.workDir());
    }

    // ---------------- 内部：起一次容器 ----------------

    private ExecOutcome exec(String image, String script, Path dir,
                             String stdin, int timeoutMs, int memoryMb) {
        String name = "oj-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path outFile = null;
        Path errFile = null;
        Process p = null;
        long start = System.currentTimeMillis();
        try {
            outFile = Files.createTempFile("oj-out-", ".log");
            errFile = Files.createTempFile("oj-err-", ".log");

            List<String> cmd = List.of(
                    "docker", "run", "--rm",
                    "--name", name,
                    "--network", "none",
                    "--memory", memoryMb + "m",
                    "--memory-swap", memoryMb + "m",
                    "--cpus", "1",
                    "--pids-limit", "64",
                    "--security-opt", "no-new-privileges",
                    "-i",
                    "-v", dir.toAbsolutePath() + ":/box",
                    "-w", "/box",
                    image,
                    "sh", "-c", script);

            p = new ProcessBuilder(cmd)
                    .redirectOutput(outFile.toFile())
                    .redirectError(errFile.toFile())
                    .start();

            try (OutputStream os = p.getOutputStream()) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
            }

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            if (!finished) {
                p.destroyForcibly();
                killContainer(name);        // ★ 只杀 CLI 不够，容器还在跑
                return new ExecOutcome(true, -1, "", "", elapsed);
            }
            return new ExecOutcome(false, p.exitValue(),
                    readCapped(outFile), readCapped(errFile), elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            if (p != null) {
                p.destroyForcibly();
            }
            killContainer(name);
            return new ExecOutcome(false, -1, "", "容器执行异常：" + e.getMessage(), elapsed);
        } finally {
            deleteQuietly(outFile);
            deleteQuietly(errFile);
        }
    }

    private static void killContainer(String name) {
        try {
            new ProcessBuilder("docker", "kill", name)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static String readCapped(Path file) {
        try {
            if (file == null || !Files.exists(file)) {
                return "";
            }
            if (Files.size(file) > MAX_OUTPUT_BYTES) {
                return new String(Files.readAllBytes(file), 0, MAX_OUTPUT_BYTES, StandardCharsets.UTF_8);
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** 一次容器执行的原始结果 */
    private record ExecOutcome(boolean timedOut, int exitCode,
                               String stdout, String stderr, long elapsedMs) {}
}
```

### 7.3 `OjJudger`（判题大脑）

```java
package org.leon.practicemodule.oj.service;

import org.leon.practicemodule.oj.pojo.OjProblem;
import org.leon.practicemodule.oj.pojo.OjTestcase;
import org.leon.practicemodule.oj.sandbox.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OjJudger {

    private final CodeSandbox sandbox;

    public OjJudger(CodeSandbox sandbox) {
        this.sandbox = sandbox;
    }

    public Verdict judge(OjProblem problem, List<OjTestcase> testcases,
                         String sourceCode, JudgeLanguage language) {
        CompileContext ctx;
        try {
            ctx = sandbox.compile(sourceCode, language);
        } catch (OjCompileException e) {
            return new Verdict("CE", 0, testcases.size(), 0, e.getMessage());
        }

        try {
            int passed = 0;
            long maxTime = 0;
            for (OjTestcase tc : testcases) {
                RunResult r = sandbox.run(ctx, tc.getInputData(),
                        problem.getTimeLimitMs(), problem.getMemoryLimitMb());
                maxTime = Math.max(maxTime, r.elapsedMs());

                if (r.status() == RunResult.RunStatus.TIMEOUT) {
                    return new Verdict("TLE", passed, testcases.size(), maxTime,
                            "用例 " + tc.getSeq() + " 超时");
                }
                if (r.status() == RunResult.RunStatus.RUNTIME_ERROR) {
                    return new Verdict("RE", passed, testcases.size(), maxTime,
                            "用例 " + tc.getSeq() + " 运行错误：" + r.stderr());
                }
                if (!normalize(r.stdout()).equals(normalize(tc.getExpectedOutput()))) {
                    return new Verdict("WA", passed, testcases.size(), maxTime,
                            "用例 " + tc.getSeq() + " 输出不正确");
                }
                passed++;
            }
            String status = passed == testcases.size() ? "AC" : "WA";
            return new Verdict(status, passed, testcases.size(), maxTime, null);
        } finally {
            sandbox.cleanup(ctx);
        }
    }

    /** 统一换行、去掉行尾空白/末尾空行，避免"肉眼看一样却判错" */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\r\n", "\n").replace("\r", "\n").stripTrailing();
    }

    public record Verdict(String status, int passed, int total, long timeUsedMs, String errorMsg) {}
}
```

###### **判题状态码（务必记住）：**

| 缩写 | 含义 | 触发条件 |
|---|---|---|
| AC | Accepted 通过 | 全部用例输出都对 |
| WA | Wrong Answer 答案错误 | 某用例输出与标准答案不一致 |
| TLE | Time Limit Exceeded 运行时间超时 | 单用例运行超过 `time_limit_ms` |
| MLE | Memory Limit Exceeded内存超出限制 | 内存超限（Docker `--memory` OOM） |
| CE | Compilation Error 编译错误 | 编译命令非 0 退出 |
| RE | Runtime Error 运行错误 | 程序异常退出（数组越界、栈溢出） |
| SE | System Error 系统错误 | 沙箱/Docker 本身出错 |

---

## 八、提交接口 + 轮询接口

### 8.1 请求 / 返回对象

```java
// pojo/OjSubmitReq.java
@Data
public class OjSubmitReq {
    private Long problemId;
    private String language;    // java / python3 / cpp，缺省 java
    private String sourceCode;
}

// pojo/vo/OjSubmitVO.java
@Data
public class OjSubmitVO {
    private Long submissionId;
    public OjSubmitVO(Long submissionId) { this.submissionId = submissionId; }
}

// pojo/vo/OjSubmissionVO.java
@Data
public class OjSubmissionVO {
    private Long id;
    private String status;
    private Integer score;      // 通过用例数
    private Integer total;      // 用例总数
    private Integer timeUsedMs;
    private String errorMsg;    // CE/RE 时展示给用户
    private Date createTime;    // import java.util.Date;
}
```

### 8.2 Service

```java
package org.leon.practicemodule.oj.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.oj.mapper.*;
import org.leon.practicemodule.oj.mq.OjJudgePublisher;
import org.leon.practicemodule.oj.pojo.*;
import org.leon.practicemodule.oj.pojo.vo.*;
import org.leon.practicemodule.oj.sandbox.JudgeLanguage;
import org.springframework.stereotype.Service;

@Service
public class OjService {

    private final OjProblemMapper problemMapper;
    private final OjTestcaseMapper testcaseMapper;
    private final OjSubmissionMapper submissionMapper;
    private final OjJudgePublisher publisher;

    public OjService(OjProblemMapper problemMapper, OjTestcaseMapper testcaseMapper,
                     OjSubmissionMapper submissionMapper, OjJudgePublisher publisher) {
        this.problemMapper = problemMapper;
        this.testcaseMapper = testcaseMapper;
        this.submissionMapper = submissionMapper;
        this.publisher = publisher;
    }

    public OjSubmitVO submit(Long userId, OjSubmitReq req) {
        if (req.getProblemId() == null || req.getSourceCode() == null || req.getSourceCode().isBlank()) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "题目或代码不能为空");
        }
        if (req.getSourceCode().length() > 64_000) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "代码长度超出限制");
        }
        String langCode = (req.getLanguage() == null || req.getLanguage().isBlank())
                ? "java" : req.getLanguage();
        JudgeLanguage language;
        try {
            language = JudgeLanguage.of(langCode);
        } catch (IllegalArgumentException e) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, e.getMessage());
        }

        OjProblem problem = problemMapper.selectById(req.getProblemId());
        if (problem == null || problem.getStatus() == null || problem.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在或已下架");
        }

        OjSubmission sub = new OjSubmission();
        sub.setUserId(userId);
        sub.setProblemId(problem.getId());
        sub.setLanguage(language.code());
        sub.setSourceCode(req.getSourceCode());
        submissionMapper.insert(sub);          // 状态默认 PENDING

        publisher.publish(sub.getId());        // 丢进队列，立刻返回
        return new OjSubmitVO(sub.getId());
    }

    public OjSubmissionVO getSubmission(Long userId, Long id) {
        OjSubmission sub = submissionMapper.selectById(id);
        if (sub == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "提交记录不存在");
        }
        if (!userId.equals(sub.getUserId())) {
            throw new PracticeException(PracticeErrorCode.FORBIDDEN, "无权查看该提交");
        }
        OjSubmissionVO vo = new OjSubmissionVO();
        vo.setId(sub.getId());
        vo.setStatus(sub.getStatus());
        vo.setScore(sub.getScore());
        vo.setTotal(sub.getTotal());
        vo.setTimeUsedMs(sub.getTimeUsedMs());
        vo.setErrorMsg(sub.getErrorMsg());
        vo.setCreateTime(sub.getCreateTime());
        return vo;
    }
}
```

### 8.3 Controller

```java
package org.leon.practicemodule.oj.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.oj.pojo.OjSubmitReq;
import org.leon.practicemodule.oj.pojo.vo.*;
import org.leon.practicemodule.oj.service.OjService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/practice/oj")
public class OjController {

    private final OjService ojService;
    private final UserIdentityService userIdentityService;

    public OjController(OjService ojService, UserIdentityService userIdentityService) {
        this.ojService = ojService;
        this.userIdentityService = userIdentityService;
    }

    @PostMapping("/submit")
    public Result<OjSubmitVO> submit(@RequestHeader("X-User-Name") String userName,
                                     @RequestBody OjSubmitReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(ojService.submit(userId, req));
    }

    @GetMapping("/submission/{id}")
    public Result<OjSubmissionVO> submission(@RequestHeader("X-User-Name") String userName,
                                             @PathVariable Long id) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(ojService.getSubmission(userId, id));
    }
}
```

---

## 九、Mapper 里的两个条件更新

```java
public interface OjSubmissionMapper extends BaseMapper<OjSubmission> {

    @Update("UPDATE oj_submission SET status='RUNNING', update_time=NOW(3) WHERE id=#{id}")
    int markRunning(@Param("id") Long id);

    @Update("UPDATE oj_submission SET status=#{status}, score=#{passed}, total=#{total}, " +
            "time_used_ms=#{timeUsedMs}, error_msg=#{errorMsg}, update_time=NOW(3) WHERE id=#{id}")
    int markFinished(@Param("id") Long id, @Param("status") String status,
                     @Param("passed") int passed, @Param("total") int total,
                     @Param("timeUsedMs") long timeUsedMs, @Param("errorMsg") String errorMsg);
}
```

> 注意：`markRunning` 这里是**无条件**改成 RUNNING（不是 `WHERE status='PENDING'`）。原因在消费者注释里说了——判题是幂等的，服务重启导致消息重投时允许把"判到一半"的 RUNNING 重判一遍。如果你更喜欢严格闸门，加回 `AND status IN ('PENDING','RUNNING')` 即可。

---

## 十、依赖与配置

### 10.1 `PracticeModule/pom.xml` 加 AMQP

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

### 10.2 `PracticeModule/src/main/resources/application.yaml`

```yaml
spring:
  rabbitmq:
    host: 127.0.0.1
    port: 5672
    username: guest
    password: guest
    virtual-host: /
    listener:
      simple:
        prefetch: 1              # 一次只取一条：判题是重活，别囤积
        concurrency: 2           # 两个消费者并行判，别太多，Docker 扛不住
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 2000
          multiplier: 2
          max-interval: 10000
        default-requeue-rejected: false   # 重试耗尽必须拒绝，否则死信永远为空

oj:
  sandbox:
    # 单次提交源码上限（字节）。超过直接 40000，不落库
    max-source-bytes: 65536
    # 编译阶段的容器内存与超时，与题目自身的运行限制分开
    compile-memory-mb: 512
    compile-timeout-ms: 15000
    # 单个用例输出最多读回多少字节，防止用户程序无限打印把服务端内存打爆
    max-output-bytes: 1048576
```

> `prefetch: 1` 和你项目 `UserModule` 登录事件消费者的配置一致：**重活不要一次预取一堆消息**。

---

## 十一、一次提交的完整时序

1. 前端 `POST /practice/oj/submit`，body `{problemId:1, language:"cpp", sourceCode:"#include..."}`。
2. 网关校验 JWT → 传 `X-User-Name` → Controller 换 `userId`。
3. `OjService.submit`：校验 → 插 `oj_submission`（`PENDING`）→ `publisher.publish(id)` → **立刻返回** `{submissionId:42}`。
4. 消息进 `oj.judge.queue`。
5. `OjJudgeConsumer` 拉到消息 → 查记录 → `markRunning`。
6. `OjJudger.judge`：
   - `sandbox.compile(源码, CPP)` → 起编译容器 → 产物在临时目录；
   - 对每个用例 `sandbox.run(...)` → 起运行容器，喂 stdin，读 stdout；
   - 比对 → 得出结论。
7. `markFinished` 写回 `AC/WA/...`。
8. 前端每秒 `GET /practice/oj/submission/42`，状态不是 `PENDING/RUNNING` 就展示结果。

---

## 十二、安全提醒（最重要）

Docker 已经挡掉大部分攻击面，但**上线前仍应至少做到**：

1. **绝不要**把 Docker socket 挂进判题容器（`-v /var/run/docker.sock` 会让人逃逸控制宿主）；
2. 判题容器用**非 root 用户**跑（镜像里加 `--user 1000`），并加 `--read-only`、`--tmpfs /tmp`；
3. 用 **seccomp / AppArmor** 白名单限制系统调用；
4. 判题机**和业务数据库分开部署**，判题机被攻破也拿不到业务数据；
5. 加**提交频率限制**（Redis 计数，比如每人每分钟 5 次），防刷。

`--network none` + `--memory` + `--cpus` + `--pids-limit` 已经是最小可用的隔离基线，比上一版本地子进程安全得多。

---

## 十三、落地清单（按顺序做，每步都能验证）

1. **装 Docker Desktop**（你当前机器没有），`docker version` 能出信息为准。
2. **拉镜像**：`docker pull eclipse-temurin:17-jdk && docker pull python:3.11-slim && docker pull gcc:13`。
3. **建表**：`db/migration/2026-11-04-oj.sql` 执行三张表。
4. **pom 加 AMQP 依赖**，`application.yaml` 加 rabbitmq + oj 配置。
5. **实体 + Mapper**：`OjProblem/OjTestcase/OjSubmission`，照 `QbQuestion` 风格。
6. **`JudgeLanguage` + `DockerSandbox`**：先写个 JUnit 测试，直接 `new DockerSandbox().compile("public class Main{public static void main(String[] a){System.out.print(\"hi\");}}", JudgeLanguage.JAVA)`，再 `run` 一次，确认能输出 `hi`。
7. **`OjJudger`**：用「两数之和」用例跑通 AC / WA。
8. **MQ**：`OjJudgeMqConfig` + `OjJudgePublisher` + `OjJudgeConsumer` + `OjJudgeDeadConsumer`，在 RabbitMQ 管理台（<http://localhost:15672>，guest/guest）能看到 `oj.judge.queue` 有消息进出。
9. **`OjService` + `OjController`**：Postman 提交 + 轮询。
10. **造数据**：往 `oj_testcase` 插示例用例和隐藏用例。

---

## 十四、这一版刻意"不做"的东西（YAGNI）

- **精确内存计量**：Docker 版可以读 cgroup 的 `memory.peak` 补上，demo 先留 0；
- **Special Judge**：浮点误差、多解判定的自定义校验器；
- **代码查重 / 提交限流**：先用 Redis 简单计数即可，不在最小 demo 内；
- **判题集群拆分**：现在消费者和提交接口在同一个 `PracticeModule` 里。以后量大了，把 `OjJudgeConsumer` 单独起一个判题服务、连同一个 RabbitMQ 队列，**代码几乎不用改**——这正是用 MQ 而不是线程池的最大好处。

---

## 附录：本方案与你现有代码的契合点

| 你项目已有约定 | 本方案如何遵守 |
|---|---|
| `Result<T>` 统一返回体 | Controller 全部 `Result.success(...)` |
| `X-User-Name` 换 userId | `UserIdentityService.resolveUserId` |
| `PracticeErrorCode` 业务码 | 校验失败抛 `PracticeException` |
| MyBatis-Plus | 实体 `@TableName` + Mapper 继承 `BaseMapper` |
| `TopicExchange` + 死信队列 | `OjJudgeMqConfig` 照 `LoginEventQueueConfig` 写 |
| `Jackson2JsonMessageConverter` | 与 `CaptchaMqConfig` 一致的 JSON 消息 |
| `prefetch: 1` | 与 `UserModule` 一致，重活不预取 |
| `default-requeue-rejected: false` | 与 `UserModule` 一致，保证死信生效 |
| `db/migration` 手写 DDL | 三张表无外键 + utf8mb4 + DATETIME(3) |

---

## 十五、本次已落地的代码清单

### 15.1 数据库

| 文件 | 作用 |
|---|---|
| `db/migration/2026-11-04-oj.sql` | 三张表 DDL |
| `db/migration/2026-11-04-oj-demo-data.sql` | 一道 A+B 演示题 + 2 示例 2 隐藏用例 |

### 15.2 后端代码（`org.leon.practicemodule.oj`）

| 包 | 文件 | 作用 |
|---|---|---|
| `controller` | `OjController` | 提交、轮询、题目列表、题目详情 |
| `service` | `OjService` | 提交守门（参数/语言/题目状态/用例非空）+ 落库 + 投递 |
| `service` | `OjJudger` | 编译一次 → 逐用例跑 → 比对 → 出结论 |
| `sandbox` | `JudgeLanguage` | 多语言 4 要素（镜像/文件名/编译/运行） |
| `sandbox` | `DockerSandbox` | 起容器编译与运行，超时强杀容器，输出落文件防死锁 |
| `sandbox` | `CodeSandbox` / `CompileContext` / `RunResult` / `OjCompileException` | 沙箱接口与结果类型 |
| `config` | `OjJudgeMqConfig` | 交换机/队列/死信 + JSON 消息转换器 |
| `mq` | `OjJudgePublisher` / `OjJudgeConsumer` / `OjJudgeDeadConsumer` | 投递 / 判题 / 死信兜底 |
| `mapper` | `OjProblemMapper` / `OjTestcaseMapper` / `OjSubmissionMapper` | 查询 + `markRunning` / `markFinished` |
| `pojo` | `OjProblem` / `OjTestcase` / `OjSubmission` / `OjSubmitReq` / `mq.OjJudgeEvent` | 实体与请求 |
| `pojo.vo` | `OjSubmitVO` / `OjSubmissionVO` / `OjProblemItemVO` / `OjProblemVO` / `OjTestcaseSampleVO` | 返回体 |

### 15.3 改动的既有文件

| 文件 | 改动 |
|---|---|
| `PracticeModule/pom.xml` | 加 `spring-boot-starter-amqp` |
| `PracticeModule/src/main/resources/application.yaml` | 加 `spring.rabbitmq` 与 `oj.sandbox` 配置 |
| `PracticeModuleApplication` | `@MapperScan` 增加 `org.leon.practicemodule.oj.mapper` |

### 15.4 单测（不依赖 Docker）

| 文件 | 覆盖内容 |
|---|---|
| `JudgeLanguageTest` | 语言归一化、未知语言报错、`{mem}` 替换 |
| `OjJudgerTest` | AC / WA（早停）/ TLE / RE / CE / 无用例 SE / 换行归一化 |
| `OjServiceTest` | 空代码、未知语言、题目下架、无用例、正常提交投递、越权查看 |

### 15.5 端到端验证步骤

1. 装 Docker Desktop，拉镜像：

```bash
docker pull eclipse-temurin:17-jdk
docker pull python:3.11-slim
docker pull gcc:13
```

2. 启 MySQL（`ezpracticeexercises`）与 RabbitMQ（5672，管理台 15672），执行两个 SQL 文件。
3. 编译：`mvn -pl PracticeModule -am -DskipTests compile`（需 JDK 21）。
4. 只跑新单测：`mvn -pl PracticeModule test -Dtest=JudgeLanguageTest,OjJudgerTest,OjServiceTest`。
5. 启动 `PracticeModuleApplication`，确认日志里出现队列声明。
6. 提交一道 A+B 的 Java 解：

```bash
curl -X POST http://localhost:8102/practice/oj/submit \
  -H "X-User-Name: alice" -H "Content-Type: application/json" \
  -d '{"problemId":1,"language":"java","sourceCode":"import java.util.*;public class Main{public static void main(String[]a){Scanner s=new Scanner(System.in);System.out.println(s.nextLong()+s.nextLong());}}"}'
```

7. 拿返回的 `submissionId` 轮询：

```bash
curl http://localhost:8102/practice/oj/submission/1 -H "X-User-Name: alice"
```

预期先看到 `PENDING`/`RUNNING`，随后变成 `AC`，`score=4`、`total=4`。

### 15.6 本机环境现状（实测）

- Java 17 在 PATH，但项目要 **JDK 21**，编译需 `JAVA_HOME` 指向 JDK 21；
- **Docker 未安装**，不装的话判题会走重试后进死信，最终标记 `SE`；
- `mvn` 不在 PATH，可用 `~/.m2/wrapper/dists` 里的 Maven 直接调用，或装 Maven。
