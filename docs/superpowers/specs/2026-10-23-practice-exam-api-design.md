# EZ 刷题子系统（PracticeModule）接口设计：科目刷题 + 真题套卷考试

> 日期：2026-10-23 · 状态：已与用户逐节确认，待实施
> 提供方：`PracticeModule`（端口 8102，Spring Boot 3.x + MyBatis-Plus + Redis + Seata，Nacos 服务名 `PracticeModuleServer`）
> 消费方：本期**不写前端**。接口契约即将来 `EZ_frontend` 的对接依据。
> 需求来源：用户提供的两张参考页面截图——① 试卷卡片页（`2020年07月四级真题第1套` + 徽章 `2020年/第一套/7月/125分钟` + `已提交次数 1次` + 进度条 + `开始考试`/`答案解析`）；② 答题页（`Part I Listening Comprehension` `248.5分` `25 minutes` + `Section A 短新闻` + `显示原文` + 音频播放器 + 单选题干选项 + 右侧按 `听力部分/阅读部分` 分组的题号网格 + `保存草稿`/`提交试卷`）。
> 前置文档：根目录《EZ刷题设计文档.md》(2026-09-08，产品愿景)、《AI对话思考链路API设计文档.md》(2026-10-10，统一返回体约定出处)、`docs/superpowers/specs/2026-10-12-elasticsearch-rag-design.md`

---

## 一、范围

**本期做**，分两个阶段，共用同一套判分与脱敏地基：

- **阶段一（零 DDL）**：科目列表、脱敏取题、逐题判分、错题本、收藏本、科目统计、断点续做、身份链路（`X-User-Name` → `user_id`）。9 个接口。
- **阶段二（最小 DDL：2 表 + 1 列）**：试卷模型与卷面结构 JSON、考试作答状态机（草稿/已提交/超时提交）、保存草稿、交卷判分与成绩单、答案解析。8 个接口。产出上面两张截图的页面数据。

**实施切分**：17 个接口 + 2 张新表 + 第十节的 4 项前置修复，超出单份实现计划的合理体量。计划阶段按 RAG 那条线的既有做法拆成 roadmap + 每段一份文件（前置修复与身份链路 → 阶段一判分与脱敏 → 阶段一四个 CRUD 面 → 阶段二 DDL 与试卷 → 阶段二考试状态机），每段独立可验收、段间等你点头。

**本期不做**（每一项都在第十一节留下接缝，不写半成品代码）：

| 不做的东西 | 为什么不做 |
|---|---|
| 组卷管理端 CRUD（后台录入试卷、拖拽排序、上传听力音频） | `docs/superpowers/plans/2026-10-21-rag-04-question-index.md:7` 已裁定 `QuestionIndexService` 是唯一写 `qb_question` 的入口。再加一套组卷后台就是第二个题目写入口，边界会糊。阶段二验收用一段 SQL 灌 1 套样例真题。用户已确认 |
| 听力音频的存储与转码 | 本期只存 `audioUrl` 字符串，指向已有对象存储或外链。上传链路属文件服务，与刷题流程正交 |
| 简答题（`type=5`）的自动判分 | 库里存的是 `"参考答案：要点一；要点二"`，机器无法判等。改为"提交后展示参考答案 → 用户自评"，不引入 AI 判分 |
| 定时任务扫描超时试卷 | 超时用懒判（见 6.4），单机 `@Scheduled` 重启会漏扫，且引入调度状态需要额外持久化。收益为零 |
| 无限刷题的"智能推题"算法 | `answer_mode=3`（智能练习）本期只实现"错题优先，其次未作答"一条策略。权重推荐属另一子系统 |
| 个人题库、用户上传题库建库、班级分发、打赏/VIP 配额 | 《EZ刷题设计文档.md》里的产品愿景项，与"还原截图页面"无依赖关系 |
| 生词本、笔记本、留言墙 | 截图导航栏里的其他入口，属独立子系统 |
| Redis 存错题本 ZSet | `RedisTestController.java:33` 里的 `qb:user:2:wrong:1` 只是 Redis 演示。错题本有 `uk_user_question` 唯一索引，落库比 ZSet 可靠且要参与分页查询。Redis 本期只用于身份缓存（见 7.2） |

---

## 二、事实基线（2026-10-23 探测）

所有结论来自一次性 JDBC 探针（`SHOW CREATE TABLE` + 全表采样），**不采信任何文档表述**。探针脚本放在仓库外。

### 2.1 现有表与数据量

| 表 | 注释 | 行数 | 本期角色 |
|---|---|---|---|
| `qb_subject` | 题库-科目表 | 2 | 读。`owner_id DEFAULT 1`（1=公共），`subject_key`（如 `chinese`/`math`） |
| `qb_question` | 题库-题目表 | 7 | **只读**。列：`id,subject_id,type,title,answer_json,analysis,difficulty,tags_json,status,sort_order,created_at,updated_at` |
| `qb_question_option` | 题库-题目选项表 | 16 | 只读。`uk_question_option(question_id,option_key)`，`is_correct` |
| `qb_user_answer_record` | 题库-用户答题记录表 | 6 | 读写。**`is_correct` 是 `NOT NULL`**，`answer_mode` 已有（1刷题 2考试 3智能练习），**无 `attempt_id`** |
| `qb_user_favorite` | 题库-用户收藏表 | 4 | 读写。`uk_user_question(user_id,question_id)` |
| `qb_user_wrong` | 题库-用户错题本 | 2 | 读写。`wrong_count`、`last_wrong_at`、`status`（1未掌握 2已掌握） |
| `qb_user_subject_stat` | 题库-用户科目统计表 | 3 | 读写。`uk_user_subject`，只有 `total_count/correct_count/wrong_count`，**无 mode 维度** |
| `users_dev` | 用户表 | — | 只读，`id` + `username`（**无 `nickname` 列**） |

`answer_json` 实测格式（判分口径的唯一依据）：

| `type` | 含义 | `answer_json` 实例 | 有选项行？ |
|---|---|---|---|
| 1 | 单选 | `"A"` | 是 |
| 2 | 多选 | `["A", "C"]` | 是 |
| 3 | 判断 | `true` | 否 |
| 4 | 填空 | `["光合作用"]` | 否 |
| 5 | 简答 | `"参考答案：要点一；要点二"` | 否 |

`user_answer_json` 与之同构（列注释已声明）。种子数据里 `["A"]` 对 `["A","C"]` 记为 `is_correct=0` → **多选是全对才算对**，本期沿用。

### 2.2 四个必须先修的坑

1. **`QbQuestion` 实体与真实表不符，现有 mapper 是坏的。** `QbQuestion.java:33` 声明 `owner_id`，而 `qb_question` 实测**没有这一列**（只剩遗留索引名 `idx_owner_subject_status_sort` 暗示曾经有过）。`QbQuestionMapper extends BaseMapper<QbQuestion>` 会生成 `SELECT ... owner_id ... FROM qb_question` → 任何调用报 `Unknown column 'owner_id'`。连带 `docs/superpowers/plans/2026-10-21-rag-00-roadmap.md:61` 里"`qb_question.owner_id=1` 表示公共题库"这条被标注为"已核实"的结论也是错的，AIModule Stage 5 执行前必须更正，否则 ES question index 会映射一个不存在的列。
2. **拿不到 `user_id`。** 网关 `JwtAuthGlobalFilter.java:80-83` 只注入 `X-User-Name` 并删除 `Authorization`；`JwtUtil` 只有 `getUsernameFromToken`，没有取 userId 的方法。而所有 `qb_user_*` 表都以 `user_id` 为键。本期在 PracticeModule 内部查库换取（见第七节），**不动 AuthModule / GatewayModule / CommonJwt**——`JwtUtil.java` 有用户自己的未提交修改。
3. **PracticeModule 没有 `Result`。** pom 缺 `CommonJwt` 依赖，启动类是裸 `@SpringBootApplication`（缺 `@ComponentScan`），所以 `org.leon.commonjwt.pojo.Result` 既编译不进也扫不到。`UserModule/pom.xml:103-107` 和 `UserModuleApplication.java:8` 是现成模板。
4. **`/practice/**` 路由已经存在。** Nacos dataId `gateway.yaml`（**不是** `gateway-module.yaml`，后者不存在）里有 `practice-service-route: Path=/practice/** → lb://PracticeModuleServer`，且全仓库无 `StripPrefix` → 控制器 `@RequestMapping` 前缀必须就是 `/practice`。本期零网关改动。`JwtAuthGlobalFilter.WHITE_LIST` 只有 `/auth/login`、`/auth/register`、`/auth/captcha`、`/auth/sms/send`、`/actuator`，`/practice/**` 有意走鉴权，不加白名单。

### 2.3 环境事实

MySQL 9.1.0，`localhost:3306/ezpracticeexercises`，`root` + 空密码，`sql_mode` 仅 `STRICT_TRANS_TABLES`（NOT NULL 列必须显式给值）。所有 `qb_*` 表是 `utf8mb4_unicode_ci`，新表沿用。Redis `127.0.0.1:6379` db=1。Seata 客户端已启用（`a_group`）但本期交卷是单库事务，**不加 `@GlobalTransactional`**——一次考试交卷写十几行，全局事务的锁等待和 `undo_log` 膨胀没有对应收益。

---

## 三、架构与边界

### 3.1 包结构（沿用 `org.leon.<mod>.{controller,service,service.impl,mapper,pojo,pojo.vo,config}`，仓库无 `dto`/`converter` 包，不新造）

```
org.leon.practicemodule
├── controller/  PracticeSubjectController · PracticeQuestionController · PracticeAnswerController
│               PracticeWrongController · PracticeFavoriteController · PracticePaperController
│               PracticeExamController
├── service/ + service/impl/
│   ├── UserIdentityService   X-User-Name → userId（第七节）
│   ├── AnswerGrader          唯一判分口径，无 Spring 依赖的纯函数（5.3）
│   ├── PracticeMasker        题目脱敏，答题态禁止外泄答案（5.4）
│   ├── SubjectService        科目列表 + 科目统计读写
│   ├── QuestionService       脱敏取题、答题卡分页、断点续做
│   ├── AnswerService         逐题作答落库 + 错题/统计联动
│   ├── WrongBookService      错题本 upsert 与查询
│   ├── FavoriteService       收藏 toggle
│   ├── PaperService          试卷查询 + structure_json 四条校验
│   └── ExamService           attempt 状态机、草稿、交卷事务
├── mapper/     10 个，逐个加 @Mapper（不改启动类；PracticeModule 现有 @MapperScan 保留）
├── pojo/       实体（按真实列重生成）+ 请求体（手写 pojo，不用 @Valid）
├── pojo/vo/    响应 VO，camelCase
├── exception/  PracticeException + PracticeExceptionHandler
└── config/     RedisConfig（已有）+ PracticeProperties
```

依赖方向单向：`controller → service → mapper`。`AnswerGrader` 与 `PracticeMasker` 不注入任何东西，因此阶段一/阶段二共用同一份判分与脱敏逻辑，且单测直接 `new` 就能测。

### 3.2 与 AIModule 的写边界

PracticeModule 对 `qb_question` / `qb_question_option` **只读**，永不写。写入范围：`qb_user_answer_record`、`qb_user_wrong`、`qb_user_favorite`、`qb_user_subject_stat`、`qb_paper`、`qb_exam_attempt`。AIModule 的 `QuestionIndexService` 仍是唯一写 `qb_question` 的入口，本设计不改变该裁定。

---

## 四、数据模型

### 4.1 DDL（2 表 + 1 列，执行需用户明确授权）

```sql
CREATE TABLE `qb_paper` (
  `id`               bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '试卷ID',
  `subject_id`       bigint unsigned NOT NULL COMMENT '科目ID，逻辑关联 qb_subject.id',
  `owner_id`         bigint unsigned NOT NULL DEFAULT '1' COMMENT '所属用户id，1表示公共试卷',
  `title`            varchar(200)  NOT NULL COMMENT '试卷名称，如2020年07月四级真题第1套',
  `exam_year`        smallint      DEFAULT NULL COMMENT '年份徽章，如2020',
  `exam_month`       tinyint unsigned DEFAULT NULL COMMENT '月份徽章1-12，如7',
  `set_no`           tinyint unsigned DEFAULT NULL COMMENT '套次徽章，1=第一套',
  `duration_minutes` int unsigned  NOT NULL COMMENT '考试时长分钟，如125',
  `total_score`      decimal(6,2)  NOT NULL COMMENT '试卷总分，如710.00',
  `question_count`   int unsigned  NOT NULL DEFAULT '0' COMMENT '题目总数，冗余自structure_json并在写入时校验',
  `structure_json`   json          NOT NULL COMMENT '卷面结构：parts→sections→questions',
  `description`      varchar(500)  DEFAULT NULL COMMENT '试卷说明',
  `status`           tinyint       NOT NULL DEFAULT '1' COMMENT '状态：1启用，0禁用',
  `sort_order`       int           NOT NULL DEFAULT '0' COMMENT '排序值，越小越靠前',
  `created_at`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_paper_subject_status_sort` (`subject_id`,`status`,`sort_order`),
  KEY `idx_paper_owner_status` (`owner_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题库-试卷表';

CREATE TABLE `qb_exam_attempt` (
  `id`               bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '作答ID',
  `user_id`          bigint unsigned NOT NULL COMMENT '用户ID，逻辑关联 users_dev.id',
  `paper_id`         bigint unsigned NOT NULL COMMENT '试卷ID，逻辑关联 qb_paper.id',
  `subject_id`       bigint unsigned NOT NULL COMMENT '科目ID，冗余自qb_paper，供错题本与统计复用索引',
  `seq_no`           int unsigned  NOT NULL COMMENT '第几次作答，从1递增，对应"已提交次数"',
  `status`           tinyint       NOT NULL DEFAULT '1' COMMENT '状态：1草稿 2已提交 3超时自动提交',
  `answered_json`    json          DEFAULT NULL COMMENT '草稿答案map {"<questionId>":<answer>}，交卷后置NULL',
  `started_at`       datetime      NOT NULL COMMENT '开始时间',
  `deadline_at`      datetime      NOT NULL COMMENT '截止时间=started_at+duration_minutes，懒判超时用',
  `submit_at`        datetime      DEFAULT NULL COMMENT '交卷时间',
  `duration_used_ms` int unsigned  DEFAULT NULL COMMENT '实际用时毫秒',
  `score`            decimal(6,2)  DEFAULT NULL COMMENT '交卷时快照得分',
  `correct_count`    int unsigned  DEFAULT NULL COMMENT '交卷时快照答对数',
  `wrong_count`      int unsigned  DEFAULT NULL COMMENT '交卷时快照答错数',
  `unanswered_count` int unsigned  DEFAULT NULL COMMENT '交卷时快照未答数',
  `created_at`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_attempt_user_paper_seq` (`user_id`,`paper_id`,`seq_no`),
  KEY `idx_attempt_user_paper_status` (`user_id`,`paper_id`,`status`),
  KEY `idx_attempt_user_submit` (`user_id`,`submit_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题库-用户试卷作答表';

ALTER TABLE `qb_user_answer_record`
  ADD COLUMN `attempt_id` bigint unsigned DEFAULT NULL
    COMMENT '考试作答ID，answer_mode=2时非空，逻辑关联 qb_exam_attempt.id' AFTER `subject_id`,
  ADD KEY `idx_record_attempt_question` (`attempt_id`,`question_id`);
```

### 4.2 五个设计要点

**为什么套卷只引用 `qb_question`，不把题目内嵌进 `structure_json`。** 错题本、收藏本、科目统计、`qb_user_answer_record` 全部以 `question_id` 为键。题目一旦内嵌进试卷就成了私有副本，要么为考试再造一套平行的错题/收藏/统计表（违背"以现有表库为基础"），要么这些功能对考试题全部失效。引用式的代价是 `structure_json` 写入时必须校验 `questionId` 存在且可用，这个代价换来的是两个流程共用一套用户数据。

**为什么草稿存 `answered_json` 而不是逐题写 `qb_user_answer_record`。** `is_correct` 是 `NOT NULL`，草稿态根本没有对错；硬写就得塞假值，还会让"未交卷的题"提前进入错题本和科目统计。草稿是整包覆盖写——一行一次 IO，天然幂等，"保存草稿"点多少次都不会脏。交卷那一次事务里才展开成真实记录。代价：草稿期间无法用 SQL 查"某题在当前草稿里的答案"，只能读那个 JSON。

**为什么 `score/correct_count/wrong_count/unanswered_count` 是快照而非实时聚合。** 交卷后这些数字永不重算，读成绩单不需要 `COUNT` 十几行。同时 `answered_json` 在交卷后置 `NULL`，避免"草稿 map"与"展开出的答题行"成为两份真相——**交卷后唯一真相是 `qb_user_answer_record`**。

**为什么不加 `paper_id` 到 `qb_user_answer_record`。** `attempt_id → qb_exam_attempt.paper_id` 一跳即达，加两列是冗余且会给"哪些列必须同时非空"增加一条隐性不变式。只加 `attempt_id` 一列，兑现"2 表 + 1 列"。

**为什么 `exam_year/exam_month` 而不是 `year/month`。** 两者都是 MySQL 非保留字，裸用作列名合法，但 MyBatis-Plus 生成的 SQL 不加反引号，`SELECT month FROM qb_paper` 与 `MONTH()` 函数同形。换名比将来排查一次静默解析问题便宜。

### 4.3 `structure_json` 契约

```json
{"parts":[
  {"roman":"I","name":"Listening Comprehension","cardLabel":"听力部分",
   "score":248.5,"minutes":25,
   "sections":[
     {"label":"Section A","subTitle":"短新闻",
      "direction":"Questions 1 and 2 are based on the news report you have just heard.",
      "audioUrl":"https://.../2020-07-set1-part1.mp3",
      "originalText":"News Report One ...",
      "questions":[{"index":1,"questionId":101,"score":7.0},
                   {"index":2,"questionId":102,"score":7.0}]}
   ]},
  {"roman":"II","name":"Reading Comprehension","cardLabel":"阅读部分",
   "score":142.0,"minutes":40,
   "sections":[
     {"label":"Section A","subTitle":"选词填空","direction":null,
      "audioUrl":null,"originalText":null,
      "questions":[{"index":26,"questionId":126,"score":3.55}]}
   ]}
]}
```

字段与截图的对应关系：`parts[].name` → 页面大标题 `Part I Listening Comprehension`；`score` → `248.5分` 徽章；`minutes` → `25 minutes`；`sections[].label` + `subTitle` → `Section A 短新闻`；`direction` → `Questions 1 and 2 are based on...`；`audioUrl` → 音频播放器；`originalText` → `显示原文`；`cardLabel` + `index` → 右侧 `I 听力部分 1..25` / `II 阅读部分 26..35` 网格。

**四条写入校验**（`PaperService.validateStructure`，任一不满足直接拒绝，不入库）：

1. `index` 从 1 起连续、无重复、跨 part 全局递增；`question_count` == 题目总数。
2. 每个 `questionId` 存在、`status=1`、且其 `subject_id` 等于试卷的 `subject_id`。
3. `Σ questions[].score == total_score`（`BigDecimal` 比对，容差 0）。
4. `Σ parts[].minutes <= duration_minutes`。

这四条不校验，前端会在答题卡上崩给你看——第 1 条断了题号网格出现空洞，第 3 条断了成绩单总分对不上。

### 4.4 作答状态机

```
        start(该 paper 无 status=1 的 attempt)
   (无) ────────────────────────────────────►┌──────────┐
                                             │ 1 草稿    │◄─┐ draft（整包覆盖 answered_json，自环）
                                             └──┬────┬──┘
                                  submit ───────┘    ─────── now > deadline_at + grace
                                 ┌──────────┐            ┌──────────────┐
                                 │ 2 已提交  │            │ 3 超时自动提交 │  （两者均为终态）
                                 └──────────┘            └──────────────┘
   start(存在 status=1) → 复用并返回原 attemptId；start(forceNew=true) → 旧草稿归档为 3，新建 seq_no+1
   start(仅有终态)      → 新建 seq_no+1
```

---

## 五、接口契约

### 5.1 通用约定

- 全部挂在 `/practice/**`，控制器 `@RequestMapping` 前缀即公网前缀（网关无 `StripPrefix`）。
- 一律 HTTP 200 + `Result{code,msg,data}`（`org.leon.commonjwt.pojo.Result`），`code=1` 成功。业务失败用**已存在的** `Result.error(int,String)` 承载子码——前端 `request.ts` 的 `code !== 1` 判错逻辑不变，**不改 CommonJwt**。
- 时间字段一律 epoch 毫秒 number。
- 分页统一 `PageVO{pageNum,pageSize,total,pages,list}`，不外泄 MyBatis-Plus `IPage` 的 `records/size/current`。`pageNum` 从 1 起，`pageSize` 默认 20、上限 100。
- 请求体是手写 pojo（`pojo/` 下），校验在 Service 层显式做——项目未引 `spring-boot-starter-validation`，`@Valid` 是空转的。
- 主键是 `IdType.AUTO` 的小整数，不存在 JS `Number` 精度丢失，**不需要**序列化成字符串。
- 当前用户：每个接口 `@RequestHeader("X-User-Name") String userName` → `UserIdentityService.resolveUserId(userName)`。

### 5.2 阶段一清单（9 个，零 DDL）

| 接口 | 入参 | `data` |
|---|---|---|
| `GET /practice/subject/list` | — | `[{id,name,subjectKey,icon,description,questionCount,myTotal,myCorrect,myAccuracy,lastAnswerAt}]` |
| `GET /practice/question/page` | `subjectId,pageNum,pageSize` | `PageVO<[{index,questionId,type,difficulty,tags,answerStatus,favorited}]>` |
| `GET /practice/question/current` | `subjectId,mode`（1顺序/3智能）**或** `questionId` | 脱敏单题（5.4）+ `index,answerStatus,lastUserAnswerJson?` |
| `POST /practice/answer/submit` | `{subjectId,questionId,userAnswerJson,durationMs,mode}` | `{recordId,isCorrect,correctAnswerJson,analysis,needSelfJudge,statDelta{total,correct,wrong,accuracy},wrongCount,wrongMastered}` |
| `POST /practice/answer/self-judge` | `{subjectId,questionId,recordId,isCorrect}` | `{statDelta}`（仅 `type=5`；`recordId` 来自上一条响应） |
| `GET /practice/wrong/page` | `subjectId,status,pageNum,pageSize` | `PageVO<[{questionId,index,type,titleBrief,wrongCount,lastWrongAt,status,lastUserAnswerJson}]>` |
| `POST /practice/wrong/master` | `{questionId}` | `true` |
| `GET /practice/favorite/page` | `subjectId,pageNum,pageSize` | `PageVO<[{questionId,index,type,titleBrief,createdAt}]>` |
| `POST /practice/favorite/toggle` | `{subjectId,questionId}` | `{favorited:true\|false}` |

`answerStatus`：`0` 未作答 / `1` 最近一次答对 / `2` 最近一次答错，由该用户该题最新一条 `qb_user_answer_record` 得出。

### 5.3 判分规则（`AnswerGrader`，两个阶段唯一口径）

判分**只认 `qb_question.answer_json`**。后端**永不读** `qb_question_option.is_correct`——理由见 5.5。

| type | 规则 |
|---|---|
| 1 单选 | 字母 `trim` + 忽略大小写后全等；且该字母必须存在于本题选项集 |
| 2 多选 | 两侧各去重升序后**集合全等**；部分对算错（沿用种子数据 `["A"]` vs `["A","C"]`→0） |
| 3 判断 | 归一为 `Boolean` 后全等；`"true"/"false"` 字符串与 JSON 布尔等价 |
| 4 填空 | 按空逐位比对：去首尾空白 + 全角转半角 + 忽略大小写。某空的正确答案写成数组时视为"任一命中" |
| 5 简答 | **不判分**。写记录但 `is_correct` 记 **`-1` 待自评** 并返回 `needSelfJudge:true`，等 `self-judge` 回填（**2026-10-23 段 2 更正**，原文"暂记 0"不可实现，见下） |

> **段 2 更正（2026-10-23，已实施并端到端取证）**：本节的"暂记 0"作废，改为 `-1` 三态（`-1` 待自评 / `0` 错 / `1` 对）。理由：自评"错"回填后同样是 0，与"未自评"不可区分，于是本节后半句的两条要求互相矛盾——"跳过未回填记录"会把已判错的记录永久漏计，不跳过则重复计数。实现改为 `UPDATE ... WHERE id=? AND user_id=? AND is_correct=-1` 的条件更新，返回 0 行即"已自评过"（`40900`），既不需要加列加表也天然恰好一次。连带：段 3 的 `answerStatus`（0/1/2）需为该三态显式裁定映射，段 5 需决定考试侧如何表达"待自评"。
>
> 同段实测补一条判分前提：写进 `qb_user_answer_record.user_answer_json`（MySQL `json` 列）的值必须是**整份单值且数值有限**的合法 JSON。Jackson 的 `readTree` 容忍 `"A" "B"` 这类尾随内容、把 `1e999` 读成 `Infinity`，而 MySQL 一律拒收，裸串写库会变成 `code=0`。`AnswerGrader.parse` 已承担此校验，段 4/段 5 写 `structure_json`、`answered_json` 时同理。

答案格式与题型不符（如单选传数组）→ `40000`，不落库。

`type=5` 之所以需要一个"待自评"态，是 `is_correct` 为 `NOT NULL`（没有 NULL 可用）逼出来的，所以**科目统计与错题本都要跳过 `needSelfJudge` 未回填的记录**（`AnswerService` 里一条显式分支），否则用户答一次简答题就被记一笔"错"。推论：简答题的错题本计数与科目统计**发生在 `self-judge` 那一刻**，不在 `submit` 时——`submit` 只落记录行并回 `recordId`，`self-judge` 用该 `recordId` 回填 `is_correct` 后才走统计与错题联动。（"跳过"的判据按上面的更正就是 `is_correct = -1`，不是 0。）

### 5.4 脱敏（答题态的硬约束）

`PracticeMasker` 输出 `QuestionVO`，字段白名单：`questionId,subjectId,type,typeName,title,options[{optionKey,content}],difficulty,tags,index,score?`。答题态另允许两个**用户自身数据**字段，它们不是真值、不构成泄漏：`answerStatus`、`lastUserAnswerJson`（我上次答了什么，错题本点进去要用）。

**禁止出现**：`is_correct`、`answer_json`、`analysis`、`isCorrect`、`correctAnswerJson`。这三个真值只在两处出现：判分响应（`answer/submit` 之后）、考试交卷后的成绩单。否则截图里的答题页按 F12 就能看到答案。5.6 的守卫测试守这条。

### 5.5 一处双写风险（主动指出）

`qb_question_option.is_correct` 与 `qb_question.answer_json` 是同一事实的两份拷贝。本设计认后者，于是前者变成"只被 AIModule 录入路径维护、无人校验"的死数据——将来改了选项没改 `answer_json`，判分静默错。两个处置：本期在 spec 与代码注释里写死"判分不读 `is_correct`"；真正的修法是在录题入口加一致性校验，但那属 AIModule Stage 7 的抽取写回，本期不越界去改。

### 5.6 阶段二清单（8 个）

| 接口 | 入参 | `data` |
|---|---|---|
| `GET /practice/paper/page` | `subjectId,examYear,examMonth,setNo,pageNum,pageSize` | `PageVO<PaperCardVO>` |
| `GET /practice/paper/detail` | `paperId` | `{paperId,title,badges[],durationMinutes,totalScore,questionCount,submitCount,answeredCount,progressPercent,parts[...]}`（骨架，不含题干选项） |
| `POST /practice/exam/start` | `{paperId,forceNew?}` | `{attemptId,seqNo,serverNow,deadlineAt,remainingMs,durationMinutes,structure,answeredMap}` |
| `GET /practice/exam/question` | `attemptId,questionId` | 脱敏单题 + `score`、`index` |
| `POST /practice/exam/draft` | `{attemptId,answers:{qid:userAnswerJson}}` | `{savedAt,answeredCount,progressPercent}` |
| `POST /practice/exam/submit` | `{attemptId}` | `ExamResultVO` |
| `GET /practice/exam/attempt/page` | `paperId,pageNum,pageSize` | `PageVO<[{attemptId,seqNo,status,score,submitAt,durationUsedMs,correctCount,wrongCount}]>` |
| `GET /practice/exam/attempt/detail` | `attemptId` | `ExamResultVO` |

`PaperCardVO` = `{paperId,title,subjectId,subjectName,examYear,examMonth,setNo,durationMinutes,totalScore,questionCount,badges,submitCount,answeredCount,progressPercent,latestAttemptId,latestAttemptStatus}`。`badges` 由后端拼成 `["2020年","第一套","7月","125分钟"]`，前端直接渲染卡片，不拼字符串。

`ExamResultVO` = `{attemptId,seqNo,status,score,fullScore,correctCount,wrongCount,unansweredCount,accuracy,durationUsedMs,sections:[{name,scoreGot,fullScore,correctCount}],questions:[{index,questionId,type,userAnswerJson,correctAnswerJson,isCorrect,scoreGot,fullScore,analysis}]}`。交卷后一次给全，正好喂"答案解析"页；`attempt/detail` 复用同一结构，所以刷新页面不丢解析。

**`originalText` 只在交卷后可见**：`paper/detail` 与 `exam/*` 在 `attempt.status=1` 时 `originalText` 恒为 `null`，`audioUrl` 正常返回。截图的"显示原文"按钮在考试中点开就该是空的——听力原文即答案。

**交卷事务**（`ExamService.submit`，单库本地 `@Transactional`）：
1. 按 `attemptId + userId` 查 attempt（越权 → `40300`）。
2. `status != 1` → 直接返回既有 `ExamResultVO`，幂等，不重复计分。
3. 校验 `now <= deadline_at + grace`，否则归档为 `3` 并继续判分（超时提交仍给成绩）。
4. 逐题 `AnswerGrader` 判分，按 `structure_json` 的 `score` 累计每题 `scoreGot` 与 section/paper 三级分。
5. 展开写 `qb_user_answer_record`：`answer_mode=2`、`attempt_id`、`subject_id`、`is_correct`、`duration_ms`（整场用时均摊到已答题，无逐题计时）。
6. 错题本 upsert：答错 → `wrong_count+1`、`last_wrong_at=now`、`status=1`；答对且原为未掌握 → `status=2`。
7. **不更新 `qb_user_subject_stat`**（7.1 的口径决定）。
8. 回写 attempt：`status=2`、`submit_at`、`duration_used_ms`、`score`、三个 count，`answered_json` 置 `NULL`。

### 5.7 错误矩阵

| 场景 | `code` | `msg` 示例 | 前端处理 |
|---|---|---|---|
| 未带/无效 token | HTTP 401（网关直接回，非 `Result`） | `{"code":401,"message":...}` | `request.ts` 已有 401 收口 |
| 参数非法、答案格式与题型不符、分页越界 | `40000` | 答案格式与题型不匹配 | toast |
| `X-User-Name` 查不到用户 | `40100` | 用户身份不存在，请重新登录 | 跳登录 |
| 越权访问他人 attempt | `40300` | 无权操作该作答 | toast |
| 科目/题目/试卷/attempt/record 不存在 | `40400` | 题目不存在 | toast |
| 题目或试卷 `status=0` | `40900` | 题目已下架 | toast |
| 考试已超时 | `41001` | 考试已结束，请交卷 | 弹交卷 |
| 已提交还想改草稿 | `41002` | 该次作答已提交 | 跳成绩单 |

`PracticeException(msg, code)` + `PracticeExceptionHandler`（`@RestControllerAdvice(basePackages="org.leon.practicemodule")`）统一转 `Result.error(code,msg)`。**按包作用域新建，不扩 AIModule 的 `AiExceptionHandler.basePackages`**——它的现有映射有测试依赖。

---

## 六、关键流程

### 6.1 逐题刷题（阶段一）

```
GET question/current?subjectId=1&mode=1
  → UserIdentityService.resolveUserId → MAX(sort_order) 反推断点 → 取下一题 → PracticeMasker 脱敏 → Result.success
POST answer/submit {subjectId,questionId,userAnswerJson,durationMs,mode:1}
  → 校验题目存在且 status=1 → AnswerGrader 判分
  → 写 qb_user_answer_record → 错题本 upsert → qb_user_subject_stat 累加（同一事务）
  → Result.success({isCorrect,correctAnswerJson,analysis,statDelta,...})
```

### 6.2 断点续做（不加列、不加表）

`mode=1` 的"上次刷到第几题"由答题记录反推：

```sql
SELECT MAX(q.sort_order) FROM qb_user_answer_record r
  JOIN qb_question q ON q.id = r.question_id
 WHERE r.user_id = ? AND r.subject_id = ? AND r.answer_mode IN (1,3)
```

取 `sort_order > 该值 AND status=1` 的最小 `sort_order` 一题；无结果即"已刷完"，返回 `{finished:true}`。《EZ刷题设计文档.md》要求的"刷题带记忆到多少题号"由此满足，代价是每次取题多一个 `MAX` 聚合——`idx_user_subject_time` 已覆盖前缀，7 题量级下无需优化。

### 6.3 考试（阶段二）

```
paper/page → paper/detail → exam/start（拿 attemptId + deadlineAt）
  → exam/question 逐题取 → 前端本地记答案 → exam/draft 整包覆盖（手动按钮 + 每 30s 自动）
  → exam/submit → ExamResultVO（成绩单 + 全解析）→ 卡片页 submitCount 变 2
```

前端倒计时以 `deadlineAt - serverNow` 为准，不信本地时钟；到点主动 `submit`。

### 6.4 超时懒判（不引入定时任务）

`start`、`draft`、`submit` 三个入口共用一次检查：`now > deadline_at + graceSeconds` 时，`draft` 拒绝（`41001`），`submit` 照常判分但 `status=3`，`start` 把旧草稿归档为 `3` 再新建。所以草稿态不会烂在库里，也不需要 `@Scheduled`。

---

## 七、口径决定（三条，均已与用户确认）

**7.1 科目统计不吃考试作答。** `qb_user_subject_stat` 只有一个 `total_count`，没有 mode 维度，必须二选一。定为**只累加 `answer_mode IN (1,3)`**。理由：刷一套 25 题的真题会按题量权重压弯"科目正确率"，而考试本来就有独立计数（卡片上的 `已提交次数` + attempt 自己的 `correct_count`）。副作用：`wrong/page` 里由考试产生的错题，其 `subject_id` 仍来自 attempt 冗余列，错题本不受此口径影响。

**7.2 Redis 只做身份缓存。** key `qb:uid:{userName}` → `userId`，TTL 30 分钟，走已有的 `RedisTemplate<String,Object>`（`RedisConfig.java:13-27`，value 用 `GenericJackson2JsonRedisSerializer`）。题目、错题、收藏、试卷一律不缓存——它们都要参与分页与聚合，缓存会立刻产生一致性问题而收益为零。

**7.3 已掌握的错题再答错则回退。** `status` 是状态不是终态：答对置 `2`，之后答错回 `1` 且 `wrong_count` 继续累加。这样错题本的 `status=1` 才真等于"当前需要练"。

---

## 八、配置项

```yaml
practice:
  identity:
    cache-ttl-seconds: 1800     # qb:uid:{userName}
  exam:
    grace-seconds: 60           # 超时宽限，吸收网络与倒计时误差
  page:
    default-size: 20
    max-size: 100
```

绑定到 `PracticeProperties`（`@ConfigurationProperties(prefix="practice")`，比 `@Value` 好维护，沿用 `CommonJwt` 里 `JwtConfig` 的做法）。

---

## 九、测试与验收

### 9.1 单元与切片（覆盖率主体，不连服务）

1. **`AnswerGrader` 表驱动单测**：5 种题型 × 边界——大小写、全半角、多选乱序、多选部分对算错、填空某空配数组任一命中、答案格式与题型不符报 `40000`、简答不判分。
2. **脱敏守卫测试**（最有价值的一条）：对答题态全部接口响应做 JSON 字符串断言，不含 `isCorrect` / `answerJson` / `analysis` / `is_correct` 任一子串。这道防线保证将来有人图省事直接返回实体时测试会红。清单是 `PracticeMasker.FORBIDDEN_IN_ANSWER_STATE`（段 2 已建，含 snake_case 形态与值级哨兵断言）；**段 3 起必须把断言对象从"VO 的进程内序列化"升级为"真实响应体"**（standalone MockMvc，不违反禁 `@SpringBootTest`），否则守卫保护的是类型而不是 API。
3. **交卷幂等**：同一 `attemptId` 连提两次 → `qb_user_answer_record` 行数不变、`score` 不变。用 mapper 调用次数断言（照 AI 对话"流式期间零写库"的测法）。
4. **超时懒判**：把 `deadline_at` 改到过去 → `draft` 报 `41001`、`submit` 归档 `3` 且仍给成绩、下次 `start` 拿到新 `seq_no`。
5. **越权**：B 用户带自己 token 请求 A 的 `attemptId` → `40300`，且 A 的数据零变化。
6. **错题本流转**：答错 insert `wrong_count=1` → 再错 `2` → 答对置 `2` → 已掌握后再答错回 `1` 且计数继续加。
7. **`structure_json` 四条校验**：各构造一条反例（题号空洞、题不属于本科目、分值和不等于总分、部分时长之和超过时长）。
8. **统计口径**：交一次卷 → `qb_user_subject_stat.total_count` 不变；答一题练习 → 变。锁住 7.1。

### 9.2 手工端到端（全程走网关 8100，不留直连 8102 的口子）

前置：`GET /auth/captcha` → 解码 base64 PNG 读 4 位数字（`CaptchaServiceImpl` 也会打明文）→ `POST /auth/login` 带 `username,password,captchaKey,captchaCode` → 拿 24h token。

**阻塞项**：`users_dev` 目前没有可用的用户名/密码。要么用户提供一个，要么用户同意后插一条 BCrypt 哈希测试行。这一条不解决，9.2 一步都跑不了。

每个可观测场景另配一个浏览器可直打的 GET（`subject/list`、`question/page`、`paper/page`、`paper/detail`、`attempt/page`），便于反复触发和看控制台日志。

### 9.3 验收清单

- [ ] 阶段一 9 个接口经网关返回 `code=1`，且答题态响应不含任何答案字段
- [ ] 同一题答错两次，`wrong_count=2`；答对后 `status=2`；再答错回 `1`
- [ ] 收藏 toggle 三次 → `favorited` 序列 `true,false,true`，表里最多 1 行
- [ ] 刷新页面重新 `exam/start` 同一试卷 → 返回**同一个** `attemptId`，草稿答案还在
- [ ] 交卷后 `qb_user_answer_record` 新增 `question_count` 行、`answered_json` 为 NULL、`submitCount` 变 2
- [ ] 连点两次提交 → 成绩与行数不变
- [ ] 考试中 `paper/detail` 的 `originalText` 为 null，交卷后 `attempt/detail` 里非 null
- [ ] `deadline_at` 过期后 `draft` 报 `41001`
- [ ] 样例试卷 1 套的页面数据能铺满截图两个页面的每个字段

---

## 十、前置修复（不修就没法往下写）

1. **重生成 `QbQuestion`**：删掉 `owner_id` 字段，按 2.1 的真实 12 列重建；`json` 列（`answer_json`/`tags_json`）用 `String` 承载 + 手动 `JSON` 解析，或加 MyBatis-Plus `JacksonTypeHandler`——本期选 `String` + 工具类，少一个 `autoResultMap` 的坑。同时把 `PracticeModuleApplication.java:6` 的 `@MapperScan` 与新增 `@Mapper` 并存（不冲突，保持与仓库其余模块一致）。
2. **`PracticeModule/pom.xml` 加 `CommonJwt` 依赖**（照 `UserModule/pom.xml:103-107`），启动类加 `@ComponentScan({"org.leon.practicemodule","org.leon.commonjwt"})`（照 `UserModuleApplication.java:8`），否则 `Result` 用不了。
3. **删除 `QbQuestionMapper.xml`**：现在躺在 `src/main/java/.../mapper/QbQuestionMapper.xml`，不匹配 `mapper-locations: classpath*:mapper/**/*.xml`，是 MyBatisX 留下的死文件。本期所有查询走 `BaseMapper` + 少量 `@Select` 注解，无自定义 XML 需求 → 直接删，不留"移过去再说"的悬空文件。
4. **更正 AIModule 的错误结论**：`docs/superpowers/plans/2026-10-21-rag-00-roadmap.md:61` 的 `qb_question.owner_id=1` 与事实不符，Stage 5 执行前改掉，否则 ES question index 会映射不存在的列。

---

## 十一、已知边界与后续接缝

| 边界 | 接缝在哪 |
|---|---|
| 组卷只能靠 SQL | `qb_paper` + `structure_json` 的校验已集中在 `PaperService.validateStructure`，将来做管理端只需加一个调用它的 controller，不必改判分或考试逻辑 |
| 听力音频只有 URL | `structure_json.sections[].audioUrl`，换成签名 URL 或 CDN 不改接口 |
| 逐题用时在考试里是均摊 | `qb_user_answer_record.duration_ms` 交卷时按已答题均摊。要真逐题计时得前端埋 `exam/question` 的进出时间，接口不用改形状 |
| 简答靠自评 | `needSelfJudge` + `self-judge`。将来接 AI 判分只替换 `self-judge` 的落库前一步 |
| 智能练习只有一条策略 | `mode=3` 的取题分支收在 `QuestionService` 一处 |
| 卡片进度条语义未定 | 截图里 `已提交次数 1次` 旁边的条，既可能是"已用次数/可用次数"也可能是"已答题/总题数"。`PaperCardVO` 同时给 `submitCount` 与 `progressPercent`，前端二选一，后端不猜。若将来要"次数上限"，那是 VIP 配额子系统（《EZ刷题设计文档.md:11》），需新表 |
| 单实例假设 | 本期无内存态注册表，交卷与草稿都是无状态请求，多实例天然安全。若将来加超时广播，参照 AI 对话"单实例边界只记录不实现"的处理 |
| 断点靠 `MAX(sort_order)` | 题量上万时需给 `qb_user_answer_record` 加覆盖索引或改存进度列，届时是独立决策 |

---

## 十二、需要你点头才能动的动作（关卡）

| 动作 | 影响 |
|---|---|
| 执行 4.1 的 DDL（建 2 表 + 给 `qb_user_answer_record` 加 1 列） | 改 dev 库结构，不可逆 |
| 改 `QbQuestion.java` / `QbQuestionMapper.java` / `QbQuestionService(Impl)` | 现有文件重写（它们本来就是坏的，见 2.2.1） |
| `PracticeModule/pom.xml` 加 `CommonJwt` 依赖 + 启动类加 `@ComponentScan` | 动构建与扫描范围 |
| 灌 1 套样例真题（含 2 个 part、听力音频与原文） | 往 `qb_paper`、`qb_question` 写测试数据 |
| 建 `users_dev` 测试账号（插一条 BCrypt 哈希行） | 9.2 端到端的前置 |
| 删除 `src/main/java/.../mapper/QbQuestionMapper.xml` | 死文件，或改为移到 resources |

本 spec 只提交这一个新文件。`AIModule/.../ChunkPlanner.java` 与 `TokenCounter.java` 当前有你的未提交修改，不会被我扫进提交。

---

## 十三、旧文档处置

| 文档 | 处置 |
|---|---|
| 《EZ刷题设计文档.md》(2026-09-08) | 保留为产品愿景。其"个人题库/上传题库/班级/帖子/打赏配额"超出本期，第一节已列明 |
| 《AI对话思考链路API设计文档.md》`31-32` | 其"统一返回体 `Result{code,msg,data}`，`code=1` 成功 / `0` 失败"与"新接口不得另立标准"两条**继续生效**，本设计遵循 |
| `docs/superpowers/plans/2026-10-21-rag-00-roadmap.md:61` | 结论错误（`qb_question` 无 `owner_id`），见 2.2.1 与第十节第 4 条 |
| `PracticeModule/.../RedisTestController.java:33` | 保留为 Redis 演示，不进业务路径。其 `qb:user:2:wrong:1` 写死 `userId=2` 且与 7.2 的口径不一致 |
| `PracticeController.java` 的 `/practice/tetsgateway` | 拼写有误但属现有探活接口，本期不改名（避免无关改动），新增控制器不复用该文件 |
