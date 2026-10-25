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

> **段 4 已执行（2026-10-24，用户当场授权"段 4 一路执行到底"，授权范围只到本段的三条 DDL，不延续到段 5）**：三条语句在 dev 库 `ezpracticeexercises` 全部 `OK`，逐列核对来自 `information_schema`（取证在 `plans/2026-10-23-practice-04-ddl-paper.md` 的 DDL 一节，原始 `SHOW CREATE TABLE` 落盘 `seg4/ddl-evidence.txt`）。实测结果与上面的语句**一字不差**，只补三条执行期才显形的数字：
>
> 1. `qb_paper` **16 列**、`qb_exam_attempt` **17 列**、`qb_user_answer_record` **10 列**；`attempt_id` 是 `bigint unsigned` / 可空 / `ordinal_position=4`（紧跟 `subject_id`，`AFTER` 生效）。
> 2. 九张 `qb_*` 表 collation 全是 `utf8mb4_unicode_ci`，新表没有引入第二个 collation——这条不是形式检查：`qb_paper` 要与 `qb_subject` join，混了 collation 的等值比较在 MySQL 9 上是 `Illegal mix of collations`。
> 3. **`json` 列回读的是 MySQL 规范化后的形态，不是写入的原串**（冒号后补空格）。写进本节的两处 `structure_json` 字符串比较、以及段 5 对 `answered_json` 的任何字符串改写，都必须按规范化形态匹配（`"key"\s*:\s*value`），否则"改写没生效 → 期望异常没抛"会伪装成一条绿用例——本段 `crossSubjectReferenceIsRejected` 第一次就是这么做空的，已在测试里加"改写必须真的生效"的自我把关断言。
>
> 另记两条同段实测：`STRICT_TRANS_TABLES` 下漏 `started_at` 的 attempt 行以 **1364** 被拒（所以 db-it 与端到端的插入必须显式给时间），唯一键报错消息**带表名前缀**（`qb_exam_attempt.uk_attempt_user_paper_seq`）。

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

> **段 5 更正（2026-10-25，状态机已实现并端到端取证）**：
>
> 1. **归档即判分**——`2 已提交` 与 `3 超时自动提交` 是**同一个出口**（裁定三，实现是 `ExamFinalizer.finalizeAttempt`）：两条边都跑完整判分、都展开 `qb_user_answer_record`、都联动错题本、都写 `score/correct_count/wrong_count/unanswered_count` 快照并把 `answered_json` 置 `NULL`。差别只有 `status` 的值与 `submit_at` 的语义。所以"3"的两条来源（超时提交、开考时放弃旧草稿）**都有分数快照**，不存在"归档 = 丢成绩"。
> 2. 图上的 `draft` 自环**只在未过期时成立**：过期后 `draft` 报 `41001` 且**不归档**（见 6.4 的段 5 更正），归档发生在下一次 `start` 或 `submit`。
> 3. 终态是幂等的：对 `status IN (2,3)` 的行再调 `submit` 不再走闸门，而是**从已落库的答题行重建同一份成绩单**（零写入）。活体证据：连点两次提交后 `qb_user_answer_record WHERE attempt_id=95` 仍是 2 行、两次响应 `data` 逐字相同。

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

> **段 3 更正（2026-10-24，已实施并端到端取证）**：本表有四处按实现更正。
> 1. `answerStatus` 增加 **`3` = 待自评**。段 2 落地的 `is_correct` 是三态（`-1/0/1`），而 `0`（未作答）与 `2`（答错）都表达不了"简答题等用户自评"——并进 `2` 会让简答题答一次就在答题卡上显示"错"（与"自评那一刻才统计"矛盾），并进 `0` 会让断点反复推同一题。映射的唯一实现点是 `AnswerStatus.of`。
> 2. `question/current` 的 `data` 除 `answerStatus`/`lastUserAnswerJson` 外，再加 **`pendingRecordId`**，并把题目字段收在嵌套的 `question`（`QuestionVO`）里，同级还有 `finished`。理由：`recordId` 原本"来自上一条响应"，可一旦刷新页面或换设备就再也拿不到，`self-judge` 在真实前端里成为不可达接口；`pendingRecordId` 是它的补捞入口。平铺会把这三个字段塞进段 5 也在用的 `QuestionVO`，而 `finished=true` 时压根没有题可平铺。
> 3. `pendingRecordId` 只在该记录 **`answer_mode IN (1,3)`** 时非空。段 2 的自评闸门拒绝 `mode=2`，回一个注定 `40000` 的按钮比不回更糟。
> 4. `question/current` 的 `index` 与 `question/page` 的 `index` 都是**位置序号**（本科目启用题按 `(sort_order,id)` 排到第几位），不是 `sort_order` 的值——见 6.2 的更正，`sort_order` 在科目内会重复。
> 另外：三个列表接口（`question/page`、`wrong/page`、`favorite/page`）的 `subjectId` 必填，缺失走 5.7 的 `40000`；`wrong/page` 的 `status` 可选（`null` 全部 / `1` 未掌握 / `2` 已掌握）；`pageSize>100` 报 `40000` 而不是静默夹取（静默夹会让前端翻页游标错位且不报错）。

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

> **段 4 更正（2026-10-25，两个 paper 接口已实施并端到端取证，本表按实现补正）**：
>
> 1. `subjectId` 是**必填**（缺失经 `PracticeExceptionHandler` 转 `40000`，不是"省略即查全部科目"）——本段没有"查全部科目"的形态，因为 `idx_paper_subject_status_sort` 的最左列就是 `subject_id`。
> 2. 三个徽章字段 `examYear/examMonth/setNo` **既给原值也给拼好的 `badges`**：原值供前端做筛选回显，`badges` 供直接渲染。`badges` 顺序固定为 **年 / 套 / 月 / 时长**（截图如此），套次用中文序数"第一套…第十套"，第 11 套起回落阿拉伯数字。
> 3. `submitCount` 是 `long`（不是 `int`），语义是**只数终态** attempt（`status IN (2,3)`），草稿不占"已提交次数"；`answeredCount` 则是**草稿数 `answered_json` 的 key 数、终态数展开出的 `qb_user_answer_record` 行数**（`progressPercent` 用它除以 `questionCount`，2 位小数 HALF_UP，`questionCount<=0` 给 `0.0`）。
> 4. `detail` 的 `data` 除本表列出的字段外，**还有 `description`、`latestAttemptId`、`latestAttemptStatus`**：卡片进详情页需要"继续考试"入口，这两个字段是入口的原料，原表漏列。骨架三层是 `parts[{roman,name,cardLabel,score,minutes,sections[]}]` → `sections[{label,subTitle,direction,audioUrl,originalText,questions[]}]` → `questions[{index,questionId,score}]`，与 `structure_json` 一一对应；**`QuestionRefVO` 刻意不含 `type`**——题型由段 5 的 `exam/question` 给，骨架里塞题型就要多查一次 `qb_question`，而题的正文本就不该出现在这里。
> 5. `originalText` 闸门的判据按裁定二实现为"**该用户该卷有终态 attempt 才可见**"（即 `submitCount > 0`），本段库里没有 attempt，所以端到端实测到的形态是"三个 section 的 `originalText` 全为 `null`、`audioUrl` 照常给值"；闸门在真库上的三态（无 attempt / 草稿 / 已交卷）由 `PaperSqlDbIT.originalTextGateOnRealData` 用**真服务 + 真库**钉住。

`ExamResultVO` = `{attemptId,seqNo,status,score,fullScore,correctCount,wrongCount,unansweredCount,accuracy,durationUsedMs,sections:[{name,scoreGot,fullScore,correctCount}],questions:[{index,questionId,type,userAnswerJson,correctAnswerJson,isCorrect,scoreGot,fullScore,analysis}]}`。交卷后一次给全，正好喂"答案解析"页；`attempt/detail` 复用同一结构，所以刷新页面不丢解析。

> **段 5 更正（2026-10-25，六个 exam 接口已实施并端到端取证，本表与本结构按实现补正）**：
>
> 1. **`ExamResultVO` 有两个原表没列的字段**：`pendingSelfJudgeCount` 与 `paperId`/`title`（成绩单要能独立显示"这是哪张卷子"）。其中 **`pendingSelfJudgeCount` 是成绩单唯一的自洽判据**——`correctCount + wrongCount + unansweredCount + pendingSelfJudgeCount == question_count`，缺这一位就永远少 1（考试简答落 `is_correct=-1` 不判分，裁定一）。实测活体：4 题的卷子答 2 题（1 选择答错 + 1 简答）→ `0+1+2+1=4`。
> 2. **`accuracy = correctCount / (correctCount + wrongCount)`**，2 位小数 HALF_UP（裁定六）：**未答与待自评都不进分母**，分母为 0 时给 `0.00` 而不是 `null` 也不是"除以题量"。原表只写了字段名没写口径。
> 3. `sections[]` 另给 `pendingCount` 与 `unansweredCount`（节级也要能显示"这一节还有两题没做"），三级分（题/节/卷）都由 `ExamGrading` 一次算完。
> 4. `questions[].isCorrect` 在待自评那题是 **`null`**（不是 `false`），且 `needSelfJudge=true`；原表没有 `needSelfJudge` 这一位，前端靠它决定"这题显示'待自评'而不是'答错'"。
> 5. `exam/question` 的 `data` 完整形态是 `{attemptId,questionId,index,score,question,userAnswerJson}`：`question` 是脱敏后的 `QuestionVO`（真值三样一律缺席），`index`/`score` 来自 `structure_json` 而不是 `qb_question`，`userAnswerJson` 是**用户自己已存的草稿**（回显自己的数据不算泄漏，5.4 的清单不约束它）。
> 6. `exam/draft` 的 `answers` 是**整包覆盖**：这次没带的题号等于删掉。`answers` 允许 `null`，语义是"清空草稿"（落库 `{}`）。key 是题号的十进制字符串、value 是**整份单值 JSON 的文本**（裁定五），非数字/≤0/重复题号/卷面外题号/value 不是 MySQL 收得下的单值 JSON（尾随内容、`1e999`、空串）一律 `40000` 且**零写入**。
> 7. `attempt/page` 的行是 `{attemptId,seqNo,status,score,submitAt,durationUsedMs,correctCount,wrongCount,unansweredCount,pendingSelfJudgeCount}`——**`pendingSelfJudgeCount` 不是库里的列**（`qb_exam_attempt` 只有 17 列，段 4 实测），由 `question_count - correct - wrong - unanswered` 减出；草稿行的三个快照都是 `NULL`，此时这一位也给 `NULL` 而不是 `0`（草稿没成绩，给 0 等于谎报）。`attempt/page` **不过上下架/归属闸门**：`paper == null` 才 `40400`，下架卷与别人转让过的卷的历史成绩照看。
> 8. `exam/start` 的 `data` 另给 `paperId,title,totalScore,seqNo,questionCount,answeredCount,progressPercent`，且 `structure` 与 `paper/detail` **共用同一个 VO 类型**（`PaperDetailVO.PartVO`），两份骨架迟早会漂。

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

> **段 3 更正（2026-10-24，上述算法作废并证伪）**：`sort_order` **在同一科目内不唯一**——实测科目 1 的 qid 1 与 qid 6 都是 `sort_order=1`（qid 1→1、qid 6→1、qid 2→2、qid 5→3）。于是本节的算法**可证明会漏题**：用户只答过 qid 6 时 `MAX(sort_order)=1`，`sort_order > 1` 直接跳过同样未作答的 qid 1，实测该情形下旧算法给出 qid 2、新算法给出 qid 1（`QuestionReadSqlDbIT.breakpointSkipsNothingWhereOldAlgorithmSkips` 把这两个值同时钉成断言，`duplicateSortOrderIsReal` 钉住前提数据）。
> 实现改为与排序键对齐的 `NOT EXISTS`：
> ```sql
> SELECT q.id FROM qb_question q
>  WHERE q.subject_id = ? AND q.status = 1
>    AND NOT EXISTS (SELECT 1 FROM qb_user_answer_record r
>                    WHERE r.user_id = ? AND r.subject_id = ? AND r.question_id = q.id
>                      AND r.answer_mode IN (1,3))
>  ORDER BY q.sort_order, q.id LIMIT 1
> ```
> `answer_mode IN (1,3)` 保留本节原意（考试作答不消耗练习进度，与 7.1 同源）。注意它与 5.2 的 `answerStatus` 口径**故意不同**：状态是"我最近一次对这道题的作答"（含考试），断点是"下一个该刷的题"（不含考试）。可见后果写进段 3 计划的接缝表：考完一卷回刷题页，答题卡显示"已答"但断点仍会把这些题依次推来。
> `mode=3` 的策略也在这里定下：先取本科目未掌握错题里 `last_wrong_at` 最早的一题（`qb_user_wrong` JOIN `qb_question` 且 `status=1`），没有候选再回落到上面的断点。

### 6.3 考试（阶段二）

```
paper/page → paper/detail → exam/start（拿 attemptId + deadlineAt）
  → exam/question 逐题取 → 前端本地记答案 → exam/draft 整包覆盖（手动按钮 + 每 30s 自动）
  → exam/submit → ExamResultVO（成绩单 + 全解析）→ 卡片页 submitCount 变 2
```

前端倒计时以 `deadlineAt - serverNow` 为准，不信本地时钟；到点主动 `submit`。

### 6.4 超时懒判（不引入定时任务）

`start`、`draft`、`submit` 三个入口共用一次检查：`now > deadline_at + graceSeconds` 时，`draft` 拒绝（`41001`），`submit` 照常判分但 `status=3`，`start` 把旧草稿归档为 `3` 再新建。所以草稿态不会烂在库里，也不需要 `@Scheduled`。

> **段 5 更正（2026-10-25，实测行为）**：
>
> 1. 判据只有一份实现：`ExamDeadline.expired(deadlineAt, now, graceSeconds)` = `now > deadline_at + grace*1000`，`deadline_at` 为 `NULL` 视为**未过期**（这种行只能来自手改库，不能因为读不出时间就判用户超时）。宽限期来自 `practice.exam.grace-seconds`（默认 60，实测生效值就是 60）。
> 2. `draft` 在过期时**只拒绝、不归档**——归档发生在下一次 `start` 或 `submit`。这不是省事：把"用户看一眼屏幕发现超时"变成"已经被判了分"是两种产品事实，写权限的闸门不能顺手产生判分副作用。
> 3. `exam/question` **不受过期影响**（读路径无副作用，用户到点仍要能翻题目看自己已存的草稿）；`attempt/detail` 只拒绝草稿态（`41002`），过期但仍是草稿的行同样报 `41002`，不因为它"看起来该归档了"就给成绩单。
> 4. 端到端实测（2026-10-25，把 `user_id=10` 自己那条 attempt 的 `deadline_at` 改到过去 120 秒）：`draft` → `41001`；`question` → `code=1`；`submit` → `code=1`、`status=3`、`score` 照常给、四个计数之和仍等于 `question_count`（那次是 `0+0+4+0=4`，因为整场一道没答）。跑完库里不留 `status=1` 的过期行。

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
   > **段 3 已升级（2026-10-24）**：`AnswerStateResponseGuardTest` 用真控制器 + 真服务 + 真 `PracticeMasker` + mock mapper 对**响应字节**跑同一份清单，覆盖 `question/page`、`question/current`、`wrong/page`、`favorite/page` 四个答题态 GET；段 5 的 `exam/question`、`paper/detail` 直接往这个类里加用例，不另开测试类。两处非空转证据：① 类内 `LeakController` 直接返回 `QbQuestion` 实体，断言扫描**必须抓到** `answerJson`/`analysis`；② 两次伪阳性实测——临时给 `QuestionItemVO` 加 `answerJson`、给 `QuestionVO.OptionVO` 加 `isCorrect`，守卫分别报 `响应体泄漏了 answerJson` / `响应体泄漏了 isCorrect`（含完整报文），回滚后 165/165 绿。端到端另对 42 个真实响应文件扫过一遍，除判分响应（`submit`/`self-judge`，5.4 允许带真值）外零命中。
   > 一处刻意的边界：`titleBrief` 的原料是 `title`，而题干本身允许外泄，所以**不把值哨兵塞进题干**去断言——那会把"题干里恰好写了这几个字"判成泄漏，是假阳性。值级哨兵只放在真值列（`answer_json`/`analysis`）和选项的 `isCorrect` 上。
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

- [x] 阶段一 9 个接口经网关返回 `code=1`，且答题态响应不含任何答案字段（**2026-10-24 段 3 完成**：`subject/list`+`answer/submit`+`self-judge`+`question/page`+`question/current`+`wrong/page`+`wrong/master`+`favorite/page`+`favorite/toggle` 全部经 8100 实跑 `code=1`；42 个响应字节扫描零命中，仅判分响应按 5.4 允许带真值。负例矩阵同时落位：缺参/越界 → `40000`，科目或题不存在 → `40400`，无 token → 网关 HTTP 401，绕网关直连 → `40100`）
- [x] 同一题答错两次，`wrong_count=2`；答对后 `status=2`；再答错回 `1`（**段 2 已证**：`AnswerFlowDbIT` 真库 + 网关实跑；段 3 补上读侧半边：错题本 `status=1/2` 分页与 `wrong/master` 连点两次都回 `true`）
- [x] 收藏 toggle 三次 → `favorited` 序列 `true,false,true`，表里最多 1 行（**2026-10-24 段 3 完成**：网关实跑四次得 `[true,false,true,false]`，`qb_user_favorite` 里 `user_id=10` 始终 0 或 1 行；`uk_user_question` + `ON DUPLICATE KEY UPDATE id=id` 保证并发双击不出双行）
- [x] 阶段二 6 个 exam 接口经网关返回 `code=1`，且答题态响应不含任何答案字段（**2026-10-25 段 5 完成**：`exam/start`+`exam/question`+`exam/draft`+`exam/submit`+`exam/attempt/page`+`exam/attempt/detail` 全部经 8100 实跑；43 个响应文件字节扫描 `scanned=36 leaks=0`（7 个成绩单/登录/验证码按 5.4 豁免），三条专属断言分别证 `exam/question` 七份响应零真值且带 `userAnswerJson`+`score`、`attempt/detail` **确实带** `correctAnswerJson`（反向锚点）、交卷后 `paper/detail` 的 `originalText` 至少一节非 null。负例矩阵 14 条落位：缺参 → `40000`、卷不存在 → `40400`、下架卷开考 → `40900`、终态行取题/存草稿 → `41002`、草稿看成绩单 → `41002`、过期存草稿 → `41001`、无 token → 网关 HTTP 401、绕网关直连 → `40100`。**唯一没被端到端触发的是 `40300` 越权**：库里只有 `user_id=10` 自己的 attempt，而规则禁止为了造负例往验收库里插别人的行——它由 `ExamSubmitTest`/`ExamServiceTest` 的 40300 用例 + `finalizeIfDraft` 带 `user_id` 的真库反证（回 0 行）覆盖）
- [x] 刷新页面重新 `exam/start` 同一试卷 → 返回**同一个** `attemptId`，草稿答案还在（**2026-10-25 段 5 完成**：网关实跑 `POST exam/start{paperId:8}` 两次都回 `attemptId=95`、`seqNo=1`；存草稿两题后第三次 `start` 回 `answeredCount=2` 且 `answeredMap` 的 key 正是那两题。另证：`deadlineAt - serverNow == 65*60000` 精确成立，`structure` 是 2 部分 / 3 节 / 4 题）
- [x] 交卷后 `qb_user_answer_record` 新增 `question_count` 行、`answered_json` 为 NULL、`submitCount` 变 2（**2026-10-25 段 5 完成，按实测更正这句本身**：新增的行数 = **已答题数**而不是 `question_count`——样例卷 P1 是 4 题、只答了 2 题，库里就是 2 行（`attempt_id=95`），未答的 2 题**不落行**（`user_answer_json` 是 `json NOT NULL`，事实 1）；`answered_json` 实测为 `NULL`；`submitCount` 实测**变 1 而不是 2**，因为"连点两次提交"是同一次 attempt 的幂等重放，不产生第二条终态 attempt。要 `submitCount=2` 得开考两次（第二次是 `seq_no=2`）——本段的实测里第二条 attempt 是被超时归档的，所以 `submitCount` 到 2 时 `attempt/page` 的 `total=2`）
- [x] 连点两次提交 → 成绩与行数不变（**2026-10-25 段 5 完成**：两次 `POST exam/submit` 的 `data` **逐字节相同**（同一份 1389 字符响应），`qb_user_answer_record WHERE attempt_id=95` 前后都是 2 行；闸门是条件更新 `WHERE id AND user_id AND status=1`，第二次回 0 行 → 零 `insert`、零错题本写入，成绩单改从库里已落的行重建。单测层同名断言在 `ExamSubmitTest.secondSubmitWritesNothing`）
- [ ] 考试中 `paper/detail` 的 `originalText` 为 null，交卷后 `attempt/detail` 里非 null（**前半边的段 4 部分已证，2026-10-25**：网关实跑 `paper/detail?paperId=8` 三个 section 的 `originalText` 全 `null` 而 `audioUrl` 有值；真库真服务的三态（无 attempt / 草稿 / 已交卷）由 `PaperSqlDbIT.originalTextGateOnRealData` 钉住——**草稿态仍 null、交卷态非 null**。**后半边按实现更正**：`attempt/detail` 的响应体里**根本没有 `originalText` 字段**——它是成绩单（`ExamResultVO`），只有 `sections[]`/`questions[]` 两级，不含 section 的听力原文。所以"交卷后原文可见"这件事实测由 **`paper/detail` 在交卷之后**覆盖：2026-10-25 网关实跑，交卷前三个 section 全 `null`，交卷后 `1/3` 节非 null（样例卷只有听力节写了原文）。这条验收项的原表述是错的，勾不掉，改成上面这句）
- [x] `deadline_at` 过期后 `draft` 报 `41001`（**2026-10-25 段 5 完成**：把 `user_id=10` 自己那条 attempt 的 `deadline_at` 改成过去 120 秒（> grace 60）→ `draft` 回 `41001` 且**没归档**（`status` 仍是 1）、`question` 仍 `code=1`（读无副作用）、`submit` 回 `code=1` 且 `status=3`、`score` 照常给、四个计数之和仍 = 4。跑完库里不留过期草稿）
- [ ] 样例试卷 1 套的页面数据能铺满截图两个页面的每个字段（**卡片半边已完成，2026-10-25**：截图①的每个字段都从网关活体拿到了——`badges=["2020年","第一套","7月","65分钟"]`、`questionCount=4`、`totalScore=30.00`（原始字节形态，`decimal(6,2)` 出网保留两位）、`subjectName=语文`、`submitCount=0`、`progressPercent=0.0`、`latestAttemptId=null`；截图②的卷面骨架三层也已铺满（2 部分 / 3 节 / 4 题、`index` 1..4、听力节 `audioUrl` 有值）。**但"铺满截图每个字段"这条整体不成立**：样例卷实际是 4 套、题量 4/2/3/1（不是真题的 65 题），截图右侧那张 65 格答题卡网格**无法用现有真数据复现**；另一半（考试中作答、交卷成绩单、解析页）要段 5）（**2026-10-25 段 5 补齐，且"不成立"这句被实测再次确认**：考试中那一面——`exam/question` 给了题号 `index`、卷面 `score`、脱敏题干与四个选项、自己的草稿回显；交卷成绩单那一面——`ExamResultVO` 的 13 个题级字段全部有值（含 `correctAnswerJson`/`analysis`/`scoreGot`/`fullScore`），节级 `sections[]` 2 节、卷级 `score/fullScore/accuracy` 都是两位小数原始字节形态。**仍然铺不满的是那张 65 格网格**：样例卷每卷最多 4 题，这是数据问题不是接口问题；截图里"用时"这类逐题计时字段在考试路径是均摊值（`durationMs=291` 是整场用时/已答题数，不是真的 per-question 计时））

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
| 卡片进度条语义未定 | 截图里 `已提交次数 1次` 旁边的条，既可能是"已用次数/可用次数"也可能是"已答题/总题数"。`PaperCardVO` 同时给 `submitCount` 与 `progressPercent`，前端二选一，后端不猜。若将来要"次数上限"，那是 VIP 配额子系统（《EZ刷题设计文档.md:11》），需新表（**段 4 裁定三已把后端这半边定死，2026-10-25**：`submitCount` 只数终态 attempt（`status IN (2,3)`，草稿不算），`answeredCount` 草稿取 `answered_json` 的 key 数、终态取展开出的答题行数，`progressPercent` 是后两者的比——"已用次数/可用次数"这一支后端不给，因为可用次数属 VIP 配额。前端二选一仍然成立） |
| 单实例假设 | 本期无内存态注册表，交卷与草稿都是无状态请求，多实例天然安全。若将来加超时广播，参照 AI 对话"单实例边界只记录不实现"的处理 |
| 断点靠 `MAX(sort_order)` | 题量上万时需给 `qb_user_answer_record` 加覆盖索引或改存进度列，届时是独立决策 |
| **考试简答题本期没有自评入口**（段 5 裁定一，2026-10-25） | 考试展开出的行是 `answer_mode=2`、`is_correct=-1`，而段 2 的 `self-judge` 只接受 `answer_mode IN (1,3)` 的行，所以成绩单上这类题显示"待自评"而点不动。接缝有两处：① 给 `self-judge` 放开 `mode=2`（要同时决定"改判之后 `attempt.score` 与 `correct_count` 快照要不要重算"——那是新一次聚合，不是改一行）；② 接 AI 判分，替换 `self-judge` 落库前那一步。**本期只把状态摆出来，不开闸门**：`ExamResultVO.pendingSelfJudgeCount` 与 `is_correct=-1` 的行都是给将来的入口留的位置 |
| **考试作答会成为该题练习侧的"最近一次作答"**（段 5 事实 13，2026-10-25） | `selectLatestByQuestions` 不带 `answer_mode` 过滤（段 3 裁定二：语义是"我对这道题最近的一次作答"），所以交完卷回练习页，那道题的答题卡会显示考试里的作答、`answerStatus=3`（待自评），而 `pendingRecordId` 是 `null`——因为 `selfJudgeable` 要 `mode IN (1,3)`。这是**已取证的副作用不是缺陷**：`ExamFlowDbIT.examRecordIsTheLatestAnswerForPracticeSide` 钉住了这对矛盾形状。谁要给它加 `mode` 过滤，先读裁定一 |

> **段 4 补一条"已知未校验项"（2026-10-25，做组卷接口时必须补成第五条校验）**：4.3 的四条校验只约束 **题目分值之和** 与 `total_score` 相等（规则 3），**`Σ parts[].score` 与 `total_score` 的关系 spec 没列、实现也没查**。样例卷是手工对齐的（P1 的 `14.00 + 16.00 = 30.00`），那**不是校验出来的**——`PaperStructureValidator` 里查 `part.score` 只用到"非负"，`db-it` 的 `everySeededPaperPassesValidation` 也过不了一个"部分分之和 ≠ 总分"的卷，因为它根本不查。段 5 的组卷/管理端写 `qb_paper` 之前，这条要么补成校验、要么在 `structure_json` 契约里明写"部分分只用于渲染、不参与任何求和"，否则成绩单的三级分（题/节/卷）会对不上总分。

---

## 十二、需要你点头才能动的动作（关卡）

| 动作 | 影响 |
|---|---|
| 执行 4.1 的 DDL（建 2 表 + 给 `qb_user_answer_record` 加 1 列） | 改 dev 库结构，不可逆（**已消耗，2026-10-24**：用户当场授权"现在就授权，段 4 一路执行到底"，授权范围**只到段 4 的这三条**，段 5 的任何结构动作要重新授权。三条全 `OK`，实测列数/索引/`ordinal_position` 见 4.1 的段 4 更正） |
| 改 `QbQuestion.java` / `QbQuestionMapper.java` / `QbQuestionService(Impl)` | 现有文件重写（它们本来就是坏的，见 2.2.1） |
| `PracticeModule/pom.xml` 加 `CommonJwt` 依赖 + 启动类加 `@ComponentScan` | 动构建与扫描范围 |
| 灌 1 套样例真题（含 2 个 part、听力音频与原文） | 往 `qb_paper`、`qb_question` 写测试数据（**已消耗，2026-10-24，按裁定一改写为"只写了 `qb_paper`"**：实际灌了 **4 套**（P1 4 题 / P2 2 题 / P3 3 题 / P4 1 题且 `status=0`），`qb_question` 一行没写也没改——样例卷只引用现有 7 道题的 `questionId`。写库脚本在仓库外 `seg4/Seg4Seed.java`，实测自增 id 是 **8/9/10/11**（不是 1..4，因为前面的 db-it 已消耗过自增值），所以测试与端到端一律按 `subjectId/questionCount/status` 定位、**绝不写死 id**） |
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
