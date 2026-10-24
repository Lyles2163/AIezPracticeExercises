# EZ 刷题子系统实现计划（五段总览）

> **For agentic workers:** 本总览是入口。每一段有一份独立计划文件，执行某段时只读该段的计划，并按其中的 checkbox 逐项推进。计划使用 `- [ ]` 语法跟踪进度。

**Goal:** 按 `2026-10-23-practice-exam-api-design.md` 落地 `PracticeModule` 的 17 个接口：段 1–3 零 DDL 的科目刷题地基，段 4–5 最小 DDL（2 表 + 1 列）的真题套卷考试流，产出两张参考截图（试卷卡片页、答题页）所需的全部后端数据。

**Architecture:** 两个阶段共用一套判分与脱敏内核——`AnswerGrader` 是唯一判分口径且只认 `qb_question.answer_json`，`PracticeMasker` 是答题态唯一的出口，两者都是无 Spring 依赖的纯对象，因此单测直接 `new`。PracticeModule 对 `qb_question`/`qb_question_option` 只读，写入范围限于 `qb_user_*` 与新增的 `qb_paper`/`qb_exam_attempt`。考试草稿整包存 `qb_exam_attempt.answered_json`，交卷时在一个本地事务里展开成逐题记录，超时靠懒判不引入定时任务。

**Tech Stack:** Java 21 / Spring Boot 3.2.4 / Spring Cloud Alibaba（Nacos 注册与配置、Gateway 8100、Sentinel、Seata 客户端但本期不用全局事务）/ MyBatis-Plus 3.5.17 / MySQL 9.1.0 / Spring Data Redis (Lettuce + commons-pool2) / Hutool 5.8.25 / JUnit 5 + Mockito。

**Spec:** `docs/superpowers/specs/2026-10-23-practice-exam-api-design.md`

---

## 1. 分段计划文件

| 段 | 计划文件 | 交付物 | 验收信号 | DDL | 状态 |
|---|---|---|---|---|---|
| 1 | `2026-10-23-practice-01-foundation.md` | `CommonJwt` 依赖与 `@ComponentScan`、surefire 测试分组、重生成 `QbQuestion` 等实体与 mapper、`PracticeException` + 按包作用域的 `PracticeExceptionHandler`、`UserIdentityService`（`X-User-Name`→`user_id` + Redis 缓存）、网关 JWT 链路实测、`GET /practice/subject/list` | 走 8100 带 token 拿到 `code=1`；`qb:uid:{userName}` 缓存命中；身份解析失败返回 `40100` | 无 | **已完成**（2026-10-23，30 单测 + 4 db-it 绿，端到端已取证） |
| 2 | `2026-10-23-practice-02-grader-masker.md` | `AnswerGrader`（5 题型表驱动）、`PracticeMasker` + 脱敏守卫测试、`AnswerService` 逐题落库与错题/统计联动、`POST /practice/answer/submit`、`POST /practice/answer/self-judge` | Grader 表驱动全绿；脱敏断言全绿；同题答错两次 `wrong_count=2`、答对置 `status=2`、已掌握再错回退 | 无 | **已完成**（2026-10-23，91 单测 + 3 db-it 绿，错题流转与自评幂等经网关取证） |
| 3 | `2026-10-23-practice-03-practice-read.md` | `QuestionService`（`current`/`page`/断点反推）、`WrongBookService`（`page`/`master`）、`FavoriteService`（`page`/`toggle`）→ **阶段一 9 接口全部可用**（段 1 的 `subject/list` + 段 2 的两个答题接口 + 本段 6 个） | 9 个接口经网关 `code=1`；`question/current` 响应不含任何答案字段；收藏 toggle 三次得 `true,false,true` 且表里最多 1 行 | 无 | 待执行 |
| 4 | `2026-10-23-practice-04-ddl-paper.md` | 执行 `qb_paper` + `qb_exam_attempt` 建表与 `qb_user_answer_record.attempt_id` 加列、两表实体与 mapper、`PaperService.validateStructure` 四条校验、1 套样例真题 SQL、`GET /practice/paper/page`、`GET /practice/paper/detail` | 卡片响应能铺满截图①的每个字段（标题、四个徽章、`submitCount`、进度）；四条校验各有反例测试 | **需授权** | 待执行 |
| 5 | `2026-10-23-practice-05-exam.md` | `ExamService` 状态机（`start`/`draft`/`submit`）、超时懒判、交卷事务与幂等、`exam/*` 6 个接口、`ExamResultVO` 成绩单与全解析 | 截图②全字段可渲染；刷新重进拿到同一 `attemptId`；连点两次提交成绩与行数不变；考试中 `originalText` 为 null；`deadline` 过期 `draft` 报 `41001` | 无（段 4 已建） | 待执行 |

**执行规则：一段验收通过后再开始下一段。** 段 1–3 零 DDL，可以立刻推进；段 4 是本计划里唯一需要用户明确授权动数据库的段落。段 5 依赖段 4 的表和样例数据。

**段 3 结束即是一个可用的产品状态**（科目顺序刷题 + 逐题判分 + 错题本 + 收藏本 + 统计），段 4–5 才产出截图里的套卷考试页。如果中途需要交付，段 3 是天然的停点。

---

## 2. 全局约束（每个任务都隐含）

- **JDK 21**：`JAVA_HOME=C:/Users/Administrator/.jdks/ms-21.0.11`。`C:\Program Files\Java` 只有 17，直接编译会失败。
- **Maven 不在 PATH**，统一使用：

```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
MVN="/c/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
```

- 构建统一 `-pl PracticeModule -am`；带 `-Dtest=` 时必须追加 `-Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`（`-am` 会把测试过滤带到 `CommonJwt`，不带这两个开关会因"没有匹配测试"而失败）。完整形态：

```bash
"$MVN" -pl PracticeModule -am test -Dtest=AnswerGraderTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

- **禁止使用 `@SpringBootTest`。** `PracticeModuleApplicationTests.java` 是个空的 `@SpringBootTest` 壳（唯一的方法被注释掉了），照它写新测试会去拉 Nacos + MySQL + Redis + Seata 四个外部依赖，测试变成环境探测。单测一律纯 JUnit 5 + Mockito；需要真库的集成测试用 `@Tag("db-it")` 并在测试内自建 `DataSource`（段 1 提供 `PracticeDbTestSupport`）。
- 测试分组沿用 AIModule 的既有机制：`PracticeModule/pom.xml` 需补 `<properties><surefire.excludedGroups>db-it</surefire.excludedGroups></properties>` 与 `maven-surefire-plugin` 的 `<excludedGroups>${surefire.excludedGroups}</excludedGroups>`（照 `AIModule/pom.xml:32,149-155`）。**显式跑集成测试的唯一正确形式**（段 1 Task 1.2 实测确立）：`-Dtest=<测试类> -Dsurefire.excludedGroups=` 再加上面两个 `failIf` 开关。**不要用 `-Dgroups=db-it`** —— 它在 `-am` 下会因 `CommonJwt` 没有 JUnit 5 引擎而硬失败；也不要只传 `-Dtest=<类>` 而漏掉 `-Dsurefire.excludedGroups=`，那样标签仍被排除、零个测试运行，而 `failIfNoSpecifiedTests=false` 会让构建报 SUCCESS（假绿灯）。
- 所有日志、注释、`@DisplayName`、提交信息使用中文；实体/接口/VO 的英文名保持与 spec 第五节一致，不得另立命名。
- 统一返回体只用 `org.leon.commonjwt.pojo.Result`：成功 `Result.success(data)`（`code=1`），业务失败 `Result.error(code, msg)`（**必须用带 int 的重载**，`Result.error(String)` 会把码写成 0，前端就拿不到 `40100`/`41001` 这些子码了）。
- 时间字段对外一律 epoch 毫秒 `Long`；`Date`/`LocalDateTime` 不出 VO。
- 分页一律自定义 `PageVO`，不外泄 MyBatis-Plus `IPage`。
- 校验在 Service 层显式做。项目**未引入** `spring-boot-starter-validation`，`@Valid`/`@NotBlank` 是空转的，写了也不生效。
- 控制器一律 `@RequestHeader("X-User-Name") String userName` 取身份，经 `UserIdentityService` 换 `user_id`。**不得**从请求体、查询参数或 Cookie 接受 `userId`——那是越权入口。
- 判分只读 `qb_question.answer_json`。**后端任何代码不得读取 `qb_question_option.is_correct`**（spec 5.5）。
- PracticeModule 对 `qb_question`、`qb_question_option`、`qb_subject`、`users_dev` 只读，不写。
- **绝不把 JWT 密钥、数据库口令复制进仓库任何文件、命令回显或日志。** 见第 3 节第 5 条。
- 每个任务结束前该任务范围内测试必须通过；每段最后一个任务提交一次 git commit（提交信息中文，只 `git add` 该段列出的文件）。
- **DDL 不自动执行**：段 4 的建表与加列脚本必须先征得用户明确同意，再用仓库外的 JDBC 脚本执行（本机无 `mysql` 客户端），不引入 Flyway/Liquibase。

---

## 3. 已核实并必须遵守的实测修正

以下结论来自 2026-10-23 对 `ezpracticeexercises` 的 `SHOW CREATE TABLE`、全表采样、Nacos HTTP 查询与 `netstat`，**执行时以本节为准**，spec 与既有文档中相冲突的表述作废：

1. **`qb_question` 没有 `owner_id` 列。** 实测列为 `id,subject_id,type,title,answer_json,analysis,difficulty,tags_json,status,sort_order,created_at,updated_at` 共 12 列（遗留索引名 `idx_owner_subject_status_sort` 是历史痕迹）。`QbQuestion.java:33` 仍声明 `owner_id`，因此 `QbQuestionMapper extends BaseMapper<QbQuestion>` 生成的 `SELECT ... owner_id ...` 必然报 `Unknown column 'owner_id'` —— **现有的 POJO/Mapper/Service 三者不可用，段 1 第一个任务就是重生成**。
   连带作废：`docs/superpowers/plans/2026-10-21-rag-00-roadmap.md:61` 里"`qb_question.owner_id=1` 表示公共题库"这条被标注为已核实的结论。AIModule Stage 5 执行前必须按此更正，否则 ES question index 会映射一个不存在的列。`owner_id` 只存在于 `qb_subject`（`DEFAULT 1`）。
2. **`/practice/**` 路由已经在 Nacos 里配好。** dataId 是 **`gateway.yaml`**（`practice-service-route: Path=/practice/** → lb://PracticeModuleServer`），全仓库无 `StripPrefix` → 控制器 `@RequestMapping` 前缀必须就是 `/practice`。`GatewayModule/application.yaml:26` 还非可选地导入了一个**不存在的** `gateway-module.yaml`，别去改它找路由。本期零网关改动。
3. **Nacos 实际只有 6 个 dataId**：`AService`、`gateway.yaml`、`AService_group`(SEATA_GROUP)、`BService_group`(SEATA_GROUP)、`BService`、`common-jwt-dev.yaml`。**`common-jwt.yaml` 不存在**，而所有模块的 `spring.config.import` 都以非 optional 方式导入它。
4. **网关 JWT 验签实测可用，但密钥来源不是 Nacos（2026-10-23 实测）。** 证据链：① 无 `Authorization` 头打 `/practice/tetsgateway` → `HTTP 401 {"code":401,"message":"缺少或无效的授权头"}`；② 垃圾 token → `HTTP 401 {"code":401,"message":"无效或过期令牌"}`（非 500）；③ 决定性的一条是 `JwtUtil.init()` 在 `leon.jwt.secret` 为空时抛 `IllegalStateException` 直接让应用启动失败，而网关进程在运行 → 密钥必然非空。它来自 `CommonJwt/.../config/JwtConfig.java` 的**字段默认值（硬编码在源码里）**，不是 `common-jwt-dev.yaml`——那份 Nacos 配置当前**没有被任何模块加载**，改它无效。
   推论：五段的端到端验收**不被这条卡住**，段 1 无需再做验签探测，只需按第 6 条拿一个真实账号的 token。
5. **安全债（本期不修，但必须留痕）**：HMAC 密钥以明文同时存在于 `CommonJwt/src/main/java/org/leon/commonjwt/config/JwtConfig.java` 的字段默认值与 `UserModule/src/main/resources/application.yaml:7`，且已入 git。任何能读仓库的人都能自签合法 token 冒充任意 `userId`。本计划不复制该值、不新增任何明文密钥、验收命令一律用环境变量占位。**轮换它必须改代码里的默认值，改 Nacos 无效。** 真正的处置（移除默认值 + 强制外部配置 + 密钥轮换）属独立事项，建议单开一轮。
6. **`token` 载荷里已经有 `userId`**：`AuthServiceImpl.java:99` 用 `generateToken2(users.getId(), users.getUsername(), users.getRoleId())` 签发，claims 含 `userId`/`username`/`userRole`；`JwtUtil` 只暴露了 `getUsernameFromToken`，没有取 `userId` 的 getter，网关也只注入 `X-User-Name`。因此 spec 第七节选定的"PracticeModule 查库换 id"**不是唯一可行路径**——"网关注入 `X-User-Id` + `JwtUtil` 加一个 getter"只需两处小改，且**不需要用户重新登录**（现有 token 就带着该 claim）。选 B 的理由是"不碰 GatewayModule 与 `CommonJwt`（后者有用户未提交修改）"，不是"做不到"。若日后改判，替换点只有 `UserIdentityService` 一个方法体。
7. **`users_dev` 无 `nickname` 列**，用户名列是 `username`。端到端验收需要一个真实账号的明文密码，目前**没有**——段 1 第一个任务一并解决（向用户索取，或经用户同意后插一条 BCrypt 哈希测试行）。`/auth/captcha` 实测返回字段名是 **`imageBase64`**（不是既有文档写的 `imageBase64Data`）。
8. **`answer_json` 五种题型的真实形态**：`1单选`→`"A"`；`2多选`→`["A","C"]`；`3判断`→`true`；`4填空`→`["光合作用"]`；`5简答`→`"参考答案：要点一；要点二"`。`user_answer_json` 同构。判断/填空/简答三类**没有 `qb_question_option` 行**（实测题 3/4/5 无选项），这是"判分只认 `answer_json`"的硬依据。
9. **多选全对才算对**：种子数据里 `["A"]` 对 `["A","C"]` 记 `is_correct=0`，本期沿用该口径。
10. **`qb_user_answer_record.is_correct` 是 `NOT NULL` 的有符号 `tinyint`**，所以草稿不能写进这张表（spec 4.2）。**段 2 更正**：简答题未自评时不是"暂记 0"而是 **`-1` 三态**（`-1` 待自评 / `0` 错 / `1` 对）——0 无法把"待自评"与"自评判错"分开，spec 5.3 同节的两条要求会互相矛盾（要么永久漏计、要么重复计数）。自评用 `UPDATE ... WHERE is_correct = -1` 保证恰好生效一次。**段 3 的 `answerStatus`（0/1/2）必须为 `-1` 显式裁定映射，段 5 必须决定考试侧用什么表达"待自评"**（详见段 2 计划的裁定一）。
11. **`PracticeModule` 编译不进 `Result`**：pom 无 `CommonJwt` 依赖，`PracticeModuleApplication.java:7` 是裸 `@SpringBootApplication`（缺 `@ComponentScan({"org.leon.practicemodule","org.leon.commonjwt"})`）。模板见 `UserModule/pom.xml:103-107` 与 `UserModuleApplication.java:8`。
12. **`PracticeModule/src/main/java/.../mapper/QbQuestionMapper.xml` 是死文件**：位于 `src/main/java`，不匹配 `mapper-locations: classpath*:mapper/**/*.xml`。段 1 删除，不保留"移过去再说"。
13. **`RedisTestController.java:33` 的 `qb:user:2:wrong:1` 只是 Redis 演示**，写死 `userId=2`。本期 Redis 只用于身份缓存 `qb:uid:{userName}`，错题本走表。该控制器保留不动，不进业务路径。
14. **`PracticeModule` 当前未运行**（`netstat` 只有 8100 在听）。每段验收前需启动 PracticeModule 并确认它注册进 Nacos，否则网关返回 503 而非业务错误——这两种失败的区分写在各段的验收步骤里。
15. **MySQL 9.1.0，`sql_mode` 仅 `STRICT_TRANS_TABLES`**：`NOT NULL` 列必须显式给值，`utf8mb4_unicode_ci` 沿用现有 `qb_*` 表。
16. **写进 `json` 列的值必须按 MySQL 的严格度校验，不能只按 Jackson 的宽松度**（段 2 端到端实测）。Jackson 的 `readTree` 容忍尾随内容（`"A" "B"` 只取第一个值）并把 `1e999` 读成 `Infinity`，而 MySQL `JSON_VALID` 对二者都返回 0 → 裸串写库报 `DataIntegrityViolationException` → 接口回 `code=0`（对客户端错误说谎）。`AnswerGrader.parse` 已收紧为"整份单值 + 有限数值"；**段 4 写 `qb_paper.structure_json`、段 5 写 `qb_exam_attempt.answered_json` 同样适用**。反面教训：单测把 mapper 全 mock 了，列类型约束只有端到端或真库测试才看得见。

---

## 4. 运行环境清单

| 组件 | 状态（2026-10-23 实测） | 说明 |
|---|---|---|
| MySQL 9.1.0 | 运行中，3306 | `root` / 空密码，库 `ezpracticeexercises`；本机无 `mysql` 客户端，schema 操作走仓库外 JDBC 探针 |
| Nacos | 运行中，8848 | 6 个 dataId，见第 3 节第 3 条 |
| Redis | 运行中，6379 | PracticeModule 用 db=1 |
| Gateway | 运行中，8100 | 路由来自 `gateway.yaml`；`/practice/**` 需鉴权，不在白名单 |
| PracticeModule | 段 2 结束时**在运行**，8102 | 由 `mvn -pl PracticeModule spring-boot:run` 启动；改完代码重启要先确认 8102 的持有 PID（`spring-boot:run` fork 出的 JVM 不会被 Maven 进程的退出带走） |
| Seata TC | 运行中，8091 | 客户端已启用但本期交卷用本地事务，不加 `@GlobalTransactional` |
| RabbitMQ | 运行中 | 本期不涉及 |
| `users_dev` 测试账号 | **已解决** | `test918` / `123456`（`user_id=10`），段 1 起用它做端到端；验证码明文在 Redis **db0** 的 `auth:captcha:{key}`，实测 6 位 |

---

## 5. 与 spec 的覆盖对照

| spec 章节 | 落在哪一段 |
|---|---|
| 一 范围（两阶段 17 接口 / 本期不做 8 项） | 本总览第 1 节；"不做"项分散为各段的负面断言测试 |
| 二 事实基线（2.1 表与数据量、2.2 四个坑、2.3 环境） | 本总览第 3 节；坑 1/3/4 在段 1，坑 2 决定段 1 首个任务的形态 |
| 三 架构与边界（3.1 包结构、3.2 与 AIModule 的写边界） | 段 1 建包骨架；3.2 是全局约束第 13 条 |
| 四 数据模型（4.1 DDL、4.2 五个要点、4.3 structure_json、4.4 状态机） | 4.1/4.3 → 段 4；4.4 → 段 5 |
| 5.1 通用约定（Result/时间/分页/校验/身份头） | 全局约束第 6–12 条；段 1 落地 `Result` 与 `PageVO` |
| 5.2 阶段一 9 接口 | `subject/list` → 段 1；`answer/submit` + `self-judge` → 段 2；其余 6 个（`question/*` 2、`wrong/*` 2、`favorite/*` 2）→ 段 3 |
| 5.3 判分规则（5 题型） | 段 2 |
| 5.4 脱敏白名单 | 段 2（`PracticeMasker` + 守卫测试），段 3、段 5 复用 |
| 5.5 双写风险 | 全局约束第 12 条；段 2 的守卫测试是本期唯一防线 |
| 5.6 阶段二 8 接口 | `paper/*` 2 个 → 段 4；`exam/*` 6 个 → 段 5 |
| 5.7 错误矩阵（7 个子码） | 段 1 建 `PracticeException` + handler 与码表，各段补各自分支 |
| 6.1 逐题刷题流程 | 段 2 |
| 6.2 断点续做（`MAX(sort_order)` 反推） | 段 3 |
| 6.3 考试流程 | 段 5 |
| 6.4 超时懒判 | 段 5 |
| 7.1 统计只算 `answer_mode IN (1,3)` | 段 2（`AnswerService`）、段 5（交卷不累加），段 2 有锁定测试 |
| 7.2 Redis 只做身份缓存 | 段 1 |
| 7.3 已掌握错题可回退 | 段 2 |
| 八 配置项（`PracticeProperties` 三项） | 段 1（identity ttl）、段 5（grace、page max） |
| 九 测试与验收（8 条单元 + 端到端 + 9 条清单） | 9.1 单元 1/2/6/8 → 段 2；3/4/5 → 段 5；7 → 段 4。9.2 端到端与 9.3 清单逐段分摊，段 1 建立 token 获取流程 |
| 十 前置修复四项 | 段 1 全部四项（含第 4 项对 RAG roadmap 的更正） |
| 十一 已知边界与接缝 | 各段计划末尾的"边界"小节，不写半成品代码 |
| 十二 六项关卡 | 段 1（pom+`@ComponentScan`、重生成实体、删死文件、测试账号）、段 4（DDL、样例数据） |
| 十三 旧文档处置 | 段 1 执行记录里逐条确认，不改《EZ刷题设计文档.md》 |

---

## 6. 需要用户授权的动作（关卡）

| 动作 | 所在段 | 影响 |
|---|---|---|
| 在 Nacos 建 `common-jwt.yaml` 或修改各模块 `spring.config.import` | 段 1（仅当 Task 1.1 实测确认验签不可用） | 影响全部模块的鉴权链路，不在本计划里自行决定 |
| 建 `users_dev` 测试账号（插一条 BCrypt 哈希行） | 段 1 | 写用户表；或改由用户直接提供一个真实账号密码 |
| `PracticeModule/pom.xml` 加 `CommonJwt` 依赖 + 启动类加 `@ComponentScan` + 补 surefire 分组 | 段 1 | 动构建与组件扫描范围 |
| 重写 `QbQuestion.java`/`QbQuestionMapper.java`/`QbQuestionService(Impl)`，删除 `src/main/java/.../QbQuestionMapper.xml` | 段 1 | 改现有文件（它们当前是坏的） |
| 执行 `qb_paper` + `qb_exam_attempt` 建表、`qb_user_answer_record` 加 `attempt_id` | 段 4 | 改 dev 库结构，不可逆 |
| 灌 1 套样例真题（新增题目行 + 试卷行） | 段 4 | 往 `qb_question` 写数据——**与"PracticeModule 对 `qb_question` 只读"的边界冲突，因此样例 SQL 由仓库外脚本执行，不由业务代码执行** |

---

## 7. 执行记录约定

每段计划末尾有"执行记录"小节。开始执行时在该小节追加：日期、实际命令、关键输出、与计划的偏差及原因。

学习型结论必须写进去——这是本仓库"设计文档解释为什么"的延续，下一段会引用上一段的数字。本计划特别需要留痕的四项：

1. 网关 JWT 验签是否可用、密钥从哪加载（第 3 节第 4 条的唯一答案）。
2. 脱敏守卫测试实际扫描的字段清单与响应体样例（段 2 建立后，段 3、段 5 直接扩这个清单，不另起一套）。
3. `structure_json` 四条校验里**实际踩到的那条**——样例数据手写 JSON 极易在"分值和 == 总分"上出错，记录真实报错与修正。
4. 交卷展开 `qb_user_answer_record` 的实际行数与耗时（25 题一套卷，验证"一次事务十几行写"没有性能问题，为将来上万题留基线）。
