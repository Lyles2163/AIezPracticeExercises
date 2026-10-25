# 段 5：套卷考试状态机（`exam/*` 六接口 + 成绩单）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 `qb_exam_attempt` 的草稿→交卷状态机与 `exam/start`、`exam/question`、`exam/draft`、`exam/submit`、`exam/attempt/page`、`exam/attempt/detail` 六个接口，产出截图②（答题页 + 成绩单 + 全解析）所需的全部后端数据。

**Architecture:** 三段式。① **纯逻辑层**（`ExamAnswers` 编解码、`ExamDeadline` 懒判、`ExamGrading` 判分与三级汇总）不碰 DB，表驱动单测覆盖；② **落库内核** `ExamFinalizer.finalize`（判分 → 展开答题记录 → 错题本联动 → 回写 attempt 并置 `answered_json=NULL`）是 `submit` 与 `start(forceNew)` 与超时归档**共用**的唯一出口，幂等靠一条条件更新；③ **组装出口** `ExamResultVO` 只有一条路径——刚交完的与从库里重建的必须走同一个装配函数，这样"刷新页面不丢解析"不是额外功能而是不变量。

**Tech Stack:** Java 21、Spring Boot 3.2.4、MyBatis-Plus（`BaseMapper` + 注解 SQL，无 XML、无分页插件）、Jackson、Lombok、JUnit 5 + Mockito、MySQL 9.1.0（`json` 列、`decimal(6,2)`、`sql_mode=STRICT_TRANS_TABLES`）。

**Spec:** `docs/superpowers/specs/2026-10-23-practice-exam-api-design.md`（§4.4 状态机、§5.6 接口与交卷事务、§5.7 错误矩阵、§6.3/6.4 流程、§7.1 统计口径、§9.1 测试、§9.3 验收）

**前一段:** `docs/superpowers/plans/2026-10-23-practice-04-ddl-paper.md`（表已建、样例卷 4 套已在库里、`paper/page` 与 `paper/detail` 已上线）

## Global Constraints

以下每一条都是**全局**要求，每个任务都隐含包含（值逐字来自 spec 与本仓库实测）：

- **零 DDL**。本段不建表、不加列、不改索引。`qb_exam_attempt` 与 `qb_user_answer_record.attempt_id` 在段 4 已建好（17 列 / 10 列，实测见下）。任何"再加一列方便"的冲动都要先停下来问用户。
- **Java 21 / Spring Boot 3.2.4**；测试跑法固定：`"$MVN" -o -pl PracticeModule -am test`（默认排除 `db-it`），db-it 加 `-Dsurefire.excludedGroups=`，且**必须**同时带 `-Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`（否则 `-am` 会把过滤器套到 `CommonJwt` 上以 `No tests matching pattern` 直接 BUILD FAILURE——段 4 实测）。
- **不注入 `@GlobalTransactional`**（单库本地事务，spec 2.3）；`@Transactional` 只加在写路径（`start`/`draft`/`submit`），读路径（`question`/`attempt/page`/`attempt/detail`）不加。
- **零 N+1**：一批题的取题、判分、选项键、记录回读都必须在循环外批量完成，测试用 `times(1)` 钉住。
- **空 `IN` 必挡**：`Collection` 为空时先发 SQL 就是 MySQL 语法错 → 兜底 handler 的"服务器繁忙"（对客户端错误说谎）。所有批量查询前先判空。
- **答题态零泄漏**：`exam/question` 的响应字节必须过 `PracticeMasker.FORBIDDEN_IN_ANSWER_STATE`（`answerJson`/`answer_json`/`analysis`/`isCorrect`/`is_correct`/`correctAnswerJson`）。**成绩单类响应（`exam/submit`、`exam/attempt/detail`）允许带真值**（spec §5.4），这是刻意区分，不是漏网。
- **永不读 `qb_question_option.is_correct`**：判分只读 `qb_question.answer_json`（spec §5.4 与段 2 裁定）。本段新增的选项查询只许选 `option_key`。
- **写进 `json` 列的值必须先按 MySQL 的严格度校验**（段 2 实测教训，roadmap 第 16 条）：Jackson 的 `readTree` 容忍 `"A" "B"` 与 `1e999`，MySQL 的 `JSON_VALID` 不认。`answered_json` 与 `user_answer_json` 都是 `json` 列。
- **`decimal` 比对一律 `compareTo`**，不用 `equals`（`14.0` 与 `14.00` 是同一个钱数）。
- **统计口径（spec 7.1）**：`qb_user_subject_stat` **只累加 `answer_mode IN (1,3)`**。考试交卷**不碰这张表**，只动错题本；本段要有一条测试锁死这件事。
- **错误码**：`40000` 参数/格式非法、`40100` 身份缺失、`40300` 越权、`40400` 不存在、`40900` 冲突（下架/已提交/数据异常）、`41001` 考试超时、`41002` 已提交还想改。枚举 `PracticeErrorCode` 里 `EXAM_EXPIRED`/`EXAM_SUBMITTED` 段 1 就建好了，**不需要新增枚举值**。
- **`git add` 只许逐条 pathspec**（工作树与另一个会话共享；`a749499` 那次宽 `git add` 卷进过别人的文件），**提交前先跑 `git log --oneline origin/dev..dev` 看清要推什么**。
- **不派子代理**（用户规则 2026-10-24：主线实现 + 主线自查，报告里明写"未做独立审查轮次"）。

## 运行环境与命令（照段 4，已实测）

```bash
MVN="/c/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
export JAVA_HOME="/c/Users/Administrator/.jdks/ms-21.0.11"

# 单测
"$MVN" -o -pl PracticeModule -am test
# 单跑一个类（含 db-it）
"$MVN" -o -pl PracticeModule -am test -Dtest=ExamFlowDbIT -Dsurefire.excludedGroups= \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
# 改完代码要让 8102 跑到新代码：先 install 再 spring-boot:run
"$MVN" -o -DskipTests install
nohup "$MVN" -o -pl PracticeModule spring-boot:run > "$TMP/practice-seg5.log" 2>&1 &
```

取证目录（仓库外，**任何 `.sql` 与脚本都不进仓库**）：`C:/Users/Administrator/.qoder-cn/tmp/F--AAAJAVA-code-ezPracticeExercises/seg5/`

MySQL 探针（本机没有 `mysql` 客户端）：

```bash
JAR="/c/Users/Administrator/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar"
"$JAVA_HOME/bin/java" -cp ".:$JAR" Seg5State.java      # 单文件源码模式，不要先 javac
```

## 已实测的事实（段 5 的每条设计都锚在这里）

1. **`qb_user_answer_record.user_answer_json` 是 `json NOT NULL`，`attempt_id` 可空，`answer_mode` 是 `tinyint NOT NULL DEFAULT 1`**（`information_schema` 实跑，2026-10-25）。直接后果：**未答的题不能写记录行**（写 NULL 会 1364），交卷时未答题只进 `unanswered_count`，不落行。
2. **`qb_exam_attempt` 17 列**：`score`/`correct_count`/`wrong_count`/`unanswered_count`/`submit_at`/`duration_used_ms`/`answered_json` 全部可空，`status` `tinyint NOT NULL DEFAULT 1`，`started_at`/`deadline_at` **NOT NULL 且无默认值**（段 4 实测：STRICT 下漏 `started_at` 报 1364）。
3. **唯一键 `uk_attempt_user_paper_seq(user_id,paper_id,seq_no)` 是 unique**（`non_unique=0`），报错消息**带表名前缀**（`Duplicate entry ... for key 'qb_exam_attempt.uk_attempt_user_paper_seq'`，段 4 实测）→ 捕获异常时判据要按这个子串写。
4. **样例卷 4 套在库里**：id **8**（科目 1，4 题，`total_score=30.00`，含 `qid 5` 的 `type=5` 简答题与听力 `originalText`）、id 9（科目 1，2 题）、id 10（科目 2，3 题）、id 11（科目 1，1 题，`status=0` 禁用）。**这是验收数据，测试与端到端一行都不许动**（段 4 的 `Seg4Residue` 每次都要复核 `qb_paper=4`）。
5. **`qb_exam_attempt` 当前 0 行、`attempt_id IS NOT NULL` 全库 0 行**——段 5 是第一个写入者，起点干净。
6. **`json` 列回读是 MySQL 规范化后的形态**（冒号后带空格），不是写入的原串（段 4 两起实测）。凡对 `answered_json` 做字符串改写或匹配都必须按规范化形态。
7. **同一个 `SqlSession` 里"裸 JDBC 写 → MyBatis 读"读到的是缓存旧行**（段 4 两次发作）：本段 db-it 若在一条用例里既裸写又走 mapper，**必须 `session.clearCache()`**（`PaperSqlDbIT.flushCache()` 是现成写法）。
8. **网关链路照旧可用**：8100 路由 `/practice/**` → 8102，`/practice/**` 不在白名单，token 必须 `Authorization: Bearer <token>`（裸 token 网关 401），`X-User-Name` 由网关注入。账号 `test918`（`user_id=10`）。**PracticeModule 的日志是 UTF-8**（段 4 实测：`iconv -f GBK` 会在中文行后整段截断，看着像"启动卡住"）。
9. **`AnswerGrader.grade(int type, String answerJson, String userAnswerJson, List<String> optionKeys)`** 返回 `GradeResult(boolean correct, boolean needSelfJudge)`；`type=5` 不判内容只验语法（`AnswerGrader.java:32-45`）。**`optionKeys` 只有 `type==1` 需要**（`AnswerServiceImpl.java:66-68` 就是这么传的）。
10. **`PracticeMasker.toQuestionVO(QbQuestion, List<QbQuestionOption>)`** 已经把 `answerJson`/`analysis`/`isCorrect` 挡在门外；`QuestionVO` 里**预留了 `index` 与 `score` 两个字段**，注释明写"段 5 从 `structure_json` 填入"（`QuestionVO.java:24-27`）。
11. **`PracticeErrorCode` 已有 `EXAM_EXPIRED(41001)` 与 `EXAM_SUBMITTED(41002)`**（段 1 建的），`PracticeException(PracticeErrorCode, msg)` 是构造形态（不是 spec §5.7 里写的 `(msg, code)`——按代码为准）。
12. **`PracticeProperties` 当前只有 `identity.cacheTtlSeconds`**（`config/PracticeProperties.java`），`application.yaml:43-45` 只有 `practice.identity.*`。`practice.exam.grace-seconds` **本段要加**。
13. **`AnswerStatus.selfJudgeable(row)` 要求 `answer_mode IN (1,3)`**（`AnswerStatus.java:38-45`），`selectLatestByQuestions` **不带 mode 过滤**（`QbUserAnswerRecordMapper.java:26-38` 的注释是刻意的）。所以考试展开出的记录行会**成为该题"最近一次作答"显示进练习侧**，`is_correct=-1` 的行在练习侧显示"待自评"但 `pendingRecordId=null`（点不了自评）——这是既有口径的必然推论，见裁定一。
14. **绿灯基线**：全量单测 **218**、四个 db-it **26**（`PaperSqlDbIT` 10 条）。本段所有"新增几条"的预估都从这两个数起。

## 六条裁定（写代码前先定，代码注释里要能引用到）

**裁定一：考试简答题的"待自评"——落 `-1`，不计分、不开自评口子。**
`type=5` 交卷时判不了分。选：展开的落库行写 `is_correct=-1`（**保住用户的作答原文**，`user_answer_json` NOT NULL，不写就没地方存了），成绩单该题 `isCorrect=null` + `needSelfJudge=true`，**不计入 `score`/`correct_count`/`wrong_count`，也不计入 `unanswered_count`**（它"答了但没判"）。`self-judge` 的 `answer_mode IN (1,3)` 闸门**不放开**——放开等于让考试作答经自评写进 `qb_user_subject_stat`，直接破坏 spec 7.1。
被否掉的两个方案：① 交卷时不落行、只标待自评 → 用户写的简答原文在 `answered_json` 置 NULL 后**永久丢失**；② 放开自评 → 要么污染科目统计（违 7.1），要么把 `applyOutcome` 拆成"错题本 / 统计"两半再按 mode 分支，那是给段 2 的落库内核动手术，回归风险与本段收益不成比例。
**代价必须明写**：考试简答**本期没有自评入口**，`attempt/detail` 里它永远是"待自评"。这是产品缺口不是技术债，已进接缝表与自查第 11 条。连带事实（事实 13）：这条 `-1` 行会成为该题在**练习侧**的"最近一次作答"，显示"待自评"且无可点的自评按钮。

**裁定二：并发新建 attempt 撞唯一键 → 重读草稿并返回它，不报"服务器繁忙"。**
`seq_no = MAX(seq_no)+1` 有竞态。撞 `uk_attempt_user_paper_seq` 时：另一个请求刚建好同一次草稿，**用户要的东⻄已经存在**，所以正确的行为是把那条草稿返回（等价于双击的第二次点击拿到第一次建的那次），而不是抛错。捕获 `DuplicateKeyException` 后按 `(userId, paperId, status=1)` 重读；读到就返回，读不到（对方回滚了）才 `40900`。

**裁定三：`forceNew` 与超时归档 = "归档即判分"，走与 `submit` 完全相同的那个出口。**
spec §4.4/§6.4 只说"旧草稿归档为 3"，没说归档要不要判分。**本段定为要判分**，理由有三：① 保持"`status ∈ {2,3}` ⇒ 有分数快照"这条不变式，否则 `attempt/detail` 会读到 `score=null` 的终态行而必须再编一套形态；② 段 4 裁定三已把卡片的 `submitCount` 定义为"终态 attempt 数"，一条不判分的归档行会让 `submitCount` 虚增成"提交过但没有成绩"；③ 复用同一个出口 = 少一条路径，幂等闸门也只需一条。
实现上就是：`start(forceNew=true)` 或 `start` 发现旧草稿已过期时，先 `finalizer.finalizeAttempt(oldAttempt, structure, paper, 3, now)`（判分、展开、回写、置 NULL），**再**新建。用户没点"交卷"却被判了分——但他点的是"重新开始"，语义上就是放弃那次作答。

**裁定四：交卷幂等靠一条条件更新，且"刚交完"与"重建"共用同一个装配函数。**
`UPDATE qb_exam_attempt SET status=?, submit_at=?, ... WHERE id=? AND user_id=? AND status=1`：回 1 行是真交卷，回 0 行是"已经交过了"。**不靠先查后判**（check-then-act 在并发下会双重计分）。回 0 行时**不重复展开、不重复判分**，直接按 `attemptId` 从库里重建 `ExamResultVO`（事实：`answered_json` 已是 NULL，答案在展开出的记录行里）。两条路径最后都调 `ExamResults.toVO(...)`，所以"刷新页面不丢解析"是这条不变量的副产品，不是另一个功能。

**裁定五：`answered_json` 的 key 是**十进制题号字符串**，value 是"整份单值 JSON"。**
`{"1":"A","4":["光合作用"],"5":"我的作答"}`。存草稿时**每个 value 都必须过与 `AnswerGrader` 同级的严格校验**（事实 6/16：Jackson 宽、MySQL 严）。这段校验与 `AnswerGrader` 里的看着重复，但是**两处不同时机**（一处判分前、一处存草稿前），且判分那份的入参含选项键集合，抽公共会把两个契约搅在一起——所以刻意不合并，注释要写清这件事，别被将来的人当重复代码删掉。

**裁定六：`accuracy` 定义 = `correct_count / (correct_count + wrong_count)`，2 位 HALF_UP，分母 0 给 `0.0`。**
成绩单里 `unanswered_count` 与待自评的都不进分母——"未答"不是"答错"，待自评不是"答对/答错"，把它们算进分母会让一张没做完的卷子正确率虚低。spec 的 `ExamResultVO` 只写了字段名没写口径，这条要回写进 spec §5.6 的带日期更正。

## 文件结构（谁负责什么）

| 文件 | 动作 | 职责（一句话） |
|---|---|---|
| `config/PracticeProperties.java` | 改 | 加 `exam.graceSeconds`（默认 60） |
| `src/main/resources/application.yaml` | 改 | `practice.exam.grace-seconds: 60` |
| `service/ExamAnswers.java` | 新 | `answered_json` ↔ `Map<Long,String>` 的唯一编解码 + value 严格校验（裁定五） |
| `service/ExamDeadline.java` | 新 | 纯函数：`expired(deadlineAt, nowMillis, graceSeconds)`（§6.4 的三处共用判据） |
| `service/ExamGrading.java` | 新 | 纯判分内核：`structure` + 答案 map + 题目包 → 逐题结果 + 三级汇总；**不碰 DB** |
| `service/ExamResults.java` | 新 | `PaperGrade` + attempt + `PaperStructure` → `ExamResultVO`（唯一的装配出口，裁定四） |
| `service/PaperLoad.java` | 新 | `loadVisible(paperMapper, userId, paperId)`：40000/40400/40900/40300 四道门，`paper/detail` 与 `exam/*` 共用 |
| `service/PaperSkeleton.java` | 新 | `toPartVOs(PaperStructure, boolean originalTextVisible)`：从 `PaperServiceImpl` 抽出来，`paper/detail` 与 `exam/start` 共用 |
| `service/ExamService.java` + `service/impl/ExamServiceImpl.java` | 新 | 六个用例：`start`/`question`/`draft`/`submit`/`attemptPage`/`attemptDetail`；写终态只经由 `ExamFinalizer.finalizeAttempt`（方法名带后缀是刻意的：`finalize` 会与 `Object.finalize()` 同名重载，读代码的人要多想一秒） |
| `mapper/QbExamAttemptMapper.java` | 改 | 5 条新查询 + `finalizeIfDraft` 条件更新 + `archiveDraft` |
| `mapper/AttemptPageRow.java` | 新 | `attempt/page` 的投影行 |
| `mapper/QbUserAnswerRecordMapper.java` | 改 | `selectByAttempt(userId, attemptId)`：重建成绩单用 |
| `mapper/QbQuestionOptionMapper.java` | 改 | `selectOptionKeysByQuestions(ids)`：**只选 `question_id, option_key`**（零 N+1 + 不碰真值列） |
| `mapper/QuestionOptionKeyRow.java` | 新 | 上面那条的投影行 |
| `pojo/ExamStartReq.java` / `ExamDraftReq.java` / `ExamSubmitReq.java` | 新 | 三个请求体 |
| `pojo/vo/ExamStartVO.java` / `ExamQuestionVO.java` / `ExamDraftVO.java` / `ExamResultVO.java` / `ExamAttemptItemVO.java` | 新 | 五个响应体 |
| `controller/PracticeExamController.java` | 新 | `POST /practice/exam/start` 等六个 |
| `test/…/ExamAnswersTest.java` | 新 | 编解码 + 严格校验反例 |
| `test/…/ExamDeadlineTest.java` | 新 | 宽限边界（正好到点、差 1 秒、grace=0） |
| `test/…/ExamGradingTest.java` | 新 | 判分与三级汇总 + 裁定一/六的口径 |
| `test/…/ExamServiceTest.java` | 新 | `start`/`question`/`draft`：复用草稿、新建、forceNew 归档、超时、越权、幂等闸门 |
| `test/…/ExamSubmitTest.java` | 新 | 交卷：展开行数、错题本联动、**统计不变**（7.1）、二次提交零写入（`times(1)` 断言）、成绩单形状 |
| `test/…/PracticeExamControllerTest.java` | 新 | 六个接口的透传与错误码不加工 |
| `test/…/AnswerStateResponseGuardTest.java` | 改 | 加 `exam/question` 的真字节用例 + **伪阳性**（Task 5.6） |
| `test/…/mapper/ExamFlowDbIT.java` | 新 | 真库：状态机全链路、条件更新幂等、唯一键竞态、`json` 列形态、展开行与 `attempt_id` |
| `test/…/PaperServiceTest.java` | 改（若重构需要） | `PaperLoad`/`PaperSkeleton` 抽出后**断言一个字都不改**，只改装配 |

---

## 关卡状态（本段的授权）

| 关卡 | 状态 |
|---|---|
| DDL | **不需要，本段一条都不执行。** 段 4 的授权原话是「现在就授权，段 4 一路执行到底」，范围明写只到那三条，**不延续到本段**。 |
| 往 `qb_paper`/`qb_question` 写数据 | **不写**。样例卷 4 行是验收数据；`qb_question` 一行不动（段 4 裁定一仍生效）。 |
| 往 `qb_exam_attempt` / `qb_user_answer_record` 写业务数据 | 本段的**正常交付**就是写它们（走业务代码，不走脚本）。db-it 用合成用户 `user_id=990005`（段 4 用到 990004），**每条用例前后 purge**；端到端在 `test918`（`user_id=10`）名下留真实增量，收尾**给用户清理 SQL，不代执行**。 |
| 改段 2/段 3/段 4 的既有文件 | 允许且**必须**：`PaperServiceImpl` 要抽 `PaperLoad`/`PaperSkeleton`（否则 `exam/start` 会复制一遍四道门），`application.yaml`/`PracticeProperties` 要加配置。**判据**：既有测试的断言一个字不改就能绿；改了断言就是行为变了，要停下来重新裁定。 |
| `pom.xml` | **不动。** |
| 用户需要点头的 | 只有两件：① 裁定了裁定一（考试简答无自评入口）是否符合产品预期；② 端到端之后要不要执行那几条清理 SQL。 |

---

## Task 5.1：配置项 + `ExamAnswers`（`answered_json` 编解码，MySQL 严格度）

**Files:**
- Modify: `PracticeModule/src/main/java/org/leon/practicemodule/config/PracticeProperties.java`
- Modify: `PracticeModule/src/main/resources/application.yaml`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/ExamAnswers.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/ExamAnswersTest.java`

**Interfaces:**
- Consumes: 无（纯新增）
- Produces:
  - `PracticeProperties.Exam.getGraceSeconds() : long`（默认 60）
  - `static Map<Long,String> ExamAnswers.decode(String answeredJson)`
  - `static String ExamAnswers.encode(Map<Long,String> answers)`
  - `static void ExamAnswers.requireValuesValid(Map<Long,String> answers)`（非法 value 抛 `PracticeException(40000)`）
  - `static int ExamAnswers.countOf(String answeredJson)`（草稿的已答数 = key 数；段 4 的 `PaperCards.answeredJsonKeyCount` 口径一致，这里必须与它同结果）

- [x] **Step 1：写失败测试（红：类不存在）**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * answered_json 是 {"<questionId>":<整份 JSON 答案>}。
 * 这个类型的边界只有两处值得测：key 的往返（数字↔字符串）与 value 的严格度（Jackson 宽、MySQL 严）。
 */
class ExamAnswersTest {

    @Test
    @DisplayName("NULL 与空串都当没答过，不炸")
    void decodesEmpty() {
        assertEquals(Map.of(), ExamAnswers.decode(null));
        assertEquals(Map.of(), ExamAnswers.decode(""));
        assertEquals(Map.of(), ExamAnswers.decode("  "));
        assertEquals(0, ExamAnswers.countOf(null));
    }

    @Test
    @DisplayName("编解码往返：key 是十进制题号字符串，value 保持 JSON 结构")
    void roundTrip() {
        Map<Long, String> answers = new LinkedHashMap<>();
        answers.put(1L, "\"A\"");
        answers.put(4L, "[\"光合作用\"]");
        answers.put(5L, "我的简答原文");

        String json = ExamAnswers.encode(answers);
        Map<Long, String> back = ExamAnswers.decode(json);

        assertEquals(3, back.size());
        assertEquals("\"A\"", back.get(1L));
        assertEquals("[\"光合作用\"]", back.get(4L));
        assertEquals("我的简答原文", back.get(5L));
    }

    @Test
    @DisplayName("MySQL 规范化形态照单收下（冒号后带空格），countOf 与段 4 的 answeredJsonKeyCount 同结果")
    void decodesNormalizedForm() {
        String normalized = "{ \"1\" : \"A\", \"6\" : \"B\" }";
        assertEquals(2, ExamAnswers.decode(normalized).size());
        assertEquals(2, ExamAnswers.countOf(normalized));
        assertEquals(2, PaperCards.answeredJsonKeyCount(normalized),
                "两处必须是同一个口径，否则卡片的已答数与答题页的对不上");
    }

    @Test
    @DisplayName("非对象、脏数据一律 40900，不当成空 map 悄悄放过")
    void rejectsDirtyData() {
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> ExamAnswers.decode("[1,2]")).errorCode());
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> ExamAnswers.decode("{\"1\":")).errorCode());
    }

    @Test
    @DisplayName("裁定五：value 必须是整份单值 JSON——尾随内容与 Infinity 都报 40000")
    void rejectsValuesMysqlWouldRefuse() {
        Map<Long, String> tail = Map.of(1L, "\"A\" \"B\"");
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> ExamAnswers.requireValuesValid(tail)).errorCode());
        Map<Long, String> inf = Map.of(1L, "1e999");
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> ExamAnswers.requireValuesValid(inf)).errorCode());
        // 空 value 会被 json 列拒（不是合法 JSON），同样 40000
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> ExamAnswers.requireValuesValid(Map.of(1L, "  "))).errorCode());
        assertDoesNotThrow(() -> ExamAnswers.requireValuesValid(Map.of(
                1L, "\"A\"", 2L, "[\"A\",\"B\"]", 3L, "true", 4L, "[\"\"]", 5L, "我的作答")));
    }

    @Test
    @DisplayName("encode 拒绝非正题号；空 map 编成 {}")
    void encodeGuardsKeys() {
        assertEquals("{}", ExamAnswers.encode(Map.of()));
        PracticeException e = assertThrows(PracticeException.class,
                () -> ExamAnswers.encode(Map.of(0L, "\"B\"")));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode(), "0 不是合法题号");
        assertThrows(PracticeException.class, () -> ExamAnswers.encode(Map.of(-1L, "\"B\"")));
    }
}
```

- [x] **Step 2：跑一次确认红**

```bash
"$MVN" -o -pl PracticeModule -am test -Dtest=ExamAnswersTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

期望 `BUILD FAILURE`，报错是 `找不到符号 ExamAnswers`（不是断言失败——红必须是"类不存在"那种红）。

- [x] **Step 3：写 `ExamAnswers`**

```java
package org.leon.practicemodule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code qb_exam_attempt.answered_json} 的唯一编解码出口。
 *
 * <p>形态是 {@code {"<questionId>": <整份 JSON 答案>}}：key 用字符串（JSON 对象没有数字 key），
 * value 直接放 JSON 文档本身而不是它的字符串形式，于是
 * {@code {"4":["光合作用"]}} 在 MySQL 里就是一个真数组，取出来不用二次解析。
 *
 * <p><b>为什么这里又写一遍严格校验，不复用 AnswerGrader 的那份</b>（roadmap 第 16 条的老坑）：
 * Jackson 的 readTree 容忍尾随内容（{@code "A" "B"} 只取第一个）并把 1e999 读成 Infinity，
 * 而 MySQL 的 json 列两者都不认 → 落库时抛 DataIntegrityViolation → 接口回 code=0（谎）。
 * 但判分那份校验的契约里含"选项键集合"这种只有 type=1 才有的入参，抽公共会把两个时机搅在一起：
 * 存草稿时**根本不判分**，只是不能写进库里炸。所以两处分开，各自的注释指向这条理由。
 *
 * <p>脏数据（decode 失败）报 40900 而不是"当空 map"：那种形态只有两种来源——有人手改过库，
 * 或者来了个我们不认识的写方。静默吞掉会让用户的整场作答凭空消失。
 */
public final class ExamAnswers {

    private static final Logger log = LoggerFactory.getLogger(ExamAnswers.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExamAnswers() {
    }

    /** NULL / 空串 = 从没存过草稿，回空 map（不是 null，调用方可以直接迭代） */
    public static Map<Long, String> decode(String answeredJson) {
        if (answeredJson == null || answeredJson.isBlank()) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(answeredJson);
        } catch (Exception e) {
            log.error("answered_json 解析失败，按数据异常处理：{}", firstLine(e.getMessage()));
            throw new PracticeException(PracticeErrorCode.CONFLICT, "作答数据异常，请重新进入考试");
        }
        if (root == null || !root.isObject()) {
            log.error("answered_json 不是 JSON 对象：{}", firstLine(answeredJson));
            throw new PracticeException(PracticeErrorCode.CONFLICT, "作答数据异常，请重新进入考试");
        }
        Map<Long, String> out = new LinkedHashMap<>();
        var it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            long questionId;
            try {
                questionId = Long.parseLong(e.getKey().trim());
            } catch (NumberFormatException ex) {
                log.error("answered_json 的 key 不是题号：{}", e.getKey());
                throw new PracticeException(PracticeErrorCode.CONFLICT, "作答数据异常，请重新进入考试");
            }
            out.put(questionId, MAPPER.writeValueAsString(e.getValue()));
        }
        return Map.copyOf(out);
    }

    /**
     * 存草稿的唯一入口：先验 value（裁定五），再编成 JSON 文档。
     * 空 map 编成 {@code "{}"} 而不是 NULL——"存了一次草稿、一道没答"与"从没存过"
     * 在草稿语义上是同一件事，但让 answered_json 保持"写过就是对象"能让 decode 少一条分支。
     */
    public static String encode(Map<Long, String> answers) {
        requireValuesValid(answers);
        ObjectNode node = MAPPER.createObjectNode();
        for (Map.Entry<Long, String> e : answers.entrySet()) {
            Long id = e.getKey();
            if (id == null || id <= 0L) {
                throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "答案的题号不合法");
            }
            node.set(String.valueOf(id), readSingleValue(e.getValue()));
        }
        return node.toString();
    }

    /** 草稿的已答数 = key 数。与段 4 的 {@code PaperCards.answeredJsonKeyCount} 必须同口径 */
    public static int countOf(String answeredJson) {
        return decode(answeredJson).size();
    }

    public static void requireValuesValid(Map<Long, String> answers) {
        if (answers == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "答案不能为空");
        }
        for (Map.Entry<Long, String> e : answers.entrySet()) {
            if (e.getKey() == null || e.getKey() <= 0L) {
                throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "答案的题号不合法");
            }
            readSingleValue(e.getValue());
        }
    }

    /**
     * 一段"整份单值 + 有限数值"的 JSON。判据与 AnswerGrader.parse 同源：
     * 尾随内容、空白、非有限浮点全部 40000——它们是 MySQL 会拒的三类。
     */
    private static JsonNode readSingleValue(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "答案不能为空");
        }
        JsonNode node;
        try {
            node = MAPPER.reader().readValue(raw, JsonNode.class);
        } catch (Exception e) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "答案格式与题型不匹配");
        }
        if (node == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "答案格式与题型不匹配");
        }
        if (node.isNumber() && !node.isIntegralNumber() && !Double.isFinite(node.doubleValue())) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "答案格式与题型不匹配");
        }
        return node;
    }

    private static String firstLine(String msg) {
        if (msg == null) {
            return "";
        }
        int nl = msg.indexOf('\n');
        return nl < 0 ? msg : msg.substring(0, nl);
    }
}
```

**三处执行时必须当场验的假设**（别当它们已经成立）：

1. `MAPPER.writeValueAsString(StringNode)` 回的是**带引号的 JSON 字面量**（`"\"A\""`），所以 `decode` 交回给调用方的 value 形态与 `encode` 收进去的一致——往返测试（Step 1 第三条）就是钉这件事。若实测 `decode` 交出的是 `"A"`（不带引号），那是 `toString()` 与 `writeValueAsString` 的差别，**改实现不是改测试**，并在计划这里记下实测值。
2. `MAPPER.reader().readValue("A\" \"B\"", JsonNode.class)` 这类尾随形态到底抛不抛——段 2 实测过 Jackson 的 `readTree` 只取第一个值，所以判据要落在校验里；若实测这条不抛，就补一条"读完后剩余字符必须只有空白"的手工检查（`JsonParser` 的 `getTokenLocation()`/再读一次）。**测不出来就等于没挡**，务必用 Step 1 那条 `rejectsValuesMysqlWouldRefuse` 实测。
3. `var it = root.fields()` 在本仓库 Jackson 版本上的类型是 `Iterator<Map.Entry<String,JsonNode>>`（需要 `import java.util.Iterator;` 还是 `var` 就够，按编译结果定）。

- [x] **Step 4：跑测试确认绿**

```bash
"$MVN" -o -pl PracticeModule -am test -Dtest=ExamAnswersTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

期望 `Tests run: 6, Failures: 0, Errors: 0`。

- [x] **Step 5：加配置项与它的失败测试**

`application.yaml` 的 `practice:` 块下加一行（缩进两层，与 `identity` 同级）：

```yaml
practice:
  identity:
    cache-ttl-seconds: 1800
  exam:
    grace-seconds: 60
```

`PracticeProperties.java` 加嵌套类：

```java
    private final Exam exam = new Exam();

    @Data
    public static class Exam {
        /**
         * 超时宽限秒数，吸收网络与倒计时误差（spec 8）。
         * 只有 ExamDeadline 读它：判据集中在一处，三个入口共用同一次检查才不会互相打架。
         */
        private long graceSeconds = 60L;
    }
```

测试（追加进 `ExamAnswersTest` 所在包的新类，或直接放进 `PracticePropertiesTest`——本仓库此前没有这个类的测试，**不新开**：配置绑定值由 Task 5.2 的 `ExamDeadlineTest` 用显式入参覆盖，yaml 这条由 Task 5.9 的端到端实测确认"到点还能提交"或"到点报 41001"的行为与 60 一致。**理由：给 `@ConfigurationProperties` 的默认值写单测是空转**，它测的是 Spring 而不是我们的规则。）

- [x] **Step 6：全量绿 + 报告改动**

```bash
"$MVN" -o -pl PracticeModule -am test
git status --short
```

期望 **224/224**（218 + `ExamAnswersTest` 6，数字按实跑报）。**本任务收尾：`git add -- <本任务的 3 个文件>` + `git commit` + `git push`**（用户规则：每个完成的任务提交并推送，只用显式 pathspec，先跑 `git log --oneline origin/dev..dev` 看清要推什么，绝不 `git add -A`）。

---

## Task 5.2：`ExamDeadline` 纯判据 + 把 `PaperLoad` / `PaperSkeleton` 从段 4 的实现里抽出来

**Files:**
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/ExamDeadline.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/PaperLoad.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/PaperSkeleton.java`
- Modify: `PracticeModule/src/main/java/org/leon/practicemodule/service/impl/PaperServiceImpl.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/ExamDeadlineTest.java`
- Test（既有）: `PracticeModule/src/test/java/org/leon/practicemodule/service/PaperServiceTest.java` —— **断言一个字都不许改**

**Interfaces:**
- Consumes: `QbPaperMapper`、`PaperStructure.Part/Section/QuestionRef`、`PaperDetailVO.PartVO`
- Produces:
  - `static boolean ExamDeadline.expired(java.util.Date deadlineAt, long nowMillis, long graceSeconds)`
  - `static QbPaper PaperLoad.loadVisible(QbPaperMapper mapper, Long userId, Long paperId)`
  - `static List<PaperDetailVO.PartVO> PaperSkeleton.toPartVOs(PaperStructure structure, boolean originalTextVisible)`
  - `static boolean PaperSkeleton.originalTextVisible(long submitCount)`（裁定二的唯一表达式，两个出口都用它）

- [x] **Step 1：写 `ExamDeadlineTest`（红：类不存在）**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §6.4 的三处入口共用这一句判据，所以边界必须钉死：宽限期内不算过期，
 * 过完宽限才算。start 用它决定"归档还是复用"，draft 用它拒绝，submit 用它决定终态是 2 还是 3。
 */
class ExamDeadlineTest {

    private static final long D = 1_000_000_000_000L;

    @Test
    @DisplayName("deadline 未到 → 未过期")
    void notExpiredBeforeDeadline() {
        assertFalse(ExamDeadline.expired(new Date(D), D - 1L, 60L));
        assertFalse(ExamDeadline.expired(new Date(D), D, 60L), "正好到点：now > deadline+grace 才算过期");
    }

    @Test
    @DisplayName("宽限期内不算过期，宽限之外算（判据是 > 不是 >=）")
    void graceBoundary() {
        assertFalse(ExamDeadline.expired(new Date(D), D + 59_999L, 60L));
        assertFalse(ExamDeadline.expired(new Date(D), D + 60_000L, 60L),
                "grace=60 的语义是「再加 60 秒」，等于 60 秒那一刻仍未过期");
        assertTrue(ExamDeadline.expired(new Date(D), D + 60_001L, 60L));
    }

    @Test
    @DisplayName("grace=0 退化为严格到点")
    void zeroGrace() {
        assertTrue(ExamDeadline.expired(new Date(D), D + 1L, 0L));
        assertFalse(ExamDeadline.expired(new Date(D), D, 0L));
    }

    @Test
    @DisplayName("deadline 为 NULL 不算过期：列是 NOT NULL，走到这里说明数据不对，但不许让读接口 500")
    void nullDeadlineIsNotExpired() {
        assertFalse(ExamDeadline.expired(null, Long.MAX_VALUE, 0L));
    }
}
```

- [x] **Step 2：跑一次确认红**

```bash
"$MVN" -o -pl PracticeModule -am test -Dtest=ExamDeadlineTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

期望编译失败：`找不到符号 ExamDeadline`。

- [x] **Step 3：写 `ExamDeadline`**

```java
package org.leon.practicemodule.service;

import java.util.Date;

/**
 * 超时判据（spec 6.4）。整段只有一句 {@code now > deadline + grace}，
 * 但它被 start / draft / submit 三处共用——写三遍就会漂成三种语义（一处 >= 一处加 grace 一处不加），
 * 于是"卡着点交卷成功、下次 start 却把同一份草稿判成过期"这类鬼故事。所以收成静态方法。
 */
public final class ExamDeadline {

    private ExamDeadline() {
    }

    /**
     * @param deadlineAt   attempt 的截止时间；NULL 视为未过期（列是 NOT NULL，NULL 只可能是脏数据，
     *                     读接口不该因此炸）
     * @param nowMillis    服务端当前毫秒（不信前端时钟，spec 6.3 的倒计时只是显示用）
     * @param graceSeconds practice.exam.grace-seconds
     */
    public static boolean expired(Date deadlineAt, long nowMillis, long graceSeconds) {
        if (deadlineAt == null) {
            return false;
        }
        return nowMillis > deadlineAt.getTime() + graceSeconds * 1_000L;
    }
}
```

- [x] **Step 4：跑 `ExamDeadlineTest` 确认绿**

同上命令，期望 `Tests run: 4, Failures: 0`。

- [x] **Step 5：抽 `PaperLoad`（行为不变，位置变了）**

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.pojo.QbPaper;

/**
 * 试卷可见性的四道门，`paper/detail`（段 4）与 `exam/*`（段 5）共用。
 *
 * <p>抽出来的理由不是"复用代码"，是**防止两处漂**：段 4 把 40900 定成"试卷已下架"、
 * 40300 定成"无权访问该试卷"，如果段 5 的 start 再写一遍，将来加一条"只有 owner 能开考"
 * 之类的规则时一定会漏掉另一边，于是同一个资源在两个接口给出两个码。
 *
 * <p>{@code PUBLIC_OWNER = 1} 与列表 SQL 的 {@code owner_id IN (1, 当前用户)} 同口径
 * （spec 4.1 的 owner_id 注释）。显式判空挡的是"将来有人把列改成可空"，不是防 NPE——列本身 NOT NULL DEFAULT 1。
 */
public final class PaperLoad {

    /** 1 表示公共试卷 */
    public static final long PUBLIC_OWNER = 1L;

    private PaperLoad() {
    }

    public static QbPaper loadVisible(QbPaperMapper mapper, Long userId, Long paperId) {
        if (paperId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbPaper paper = mapper.selectById(paperId);
        if (paper == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "试卷不存在");
        }
        if (paper.getStatus() == null || paper.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "试卷已下架");
        }
        if (paper.getOwnerId() != null && paper.getOwnerId() != PUBLIC_OWNER
                && !paper.getOwnerId().equals(userId)) {
            throw new PracticeException(PracticeErrorCode.FORBIDDEN, "无权访问该试卷");
        }
        return paper;
    }
}
```

（**`originalTextVisible` 不在这里**——它在 Step 6 的 `PaperSkeleton` 里，见执行记录的偏差 1：判据与 `toPartVOs` 的入参是一对，放一起调用方只需一个 import。）

`PaperServiceImpl.detail` 改为调用它（**删掉类内的 `PUBLIC_OWNER` 常量与那段四道门的内联代码**）：

```java
        QbPaper paper = PaperLoad.loadVisible(paperMapper, userId, paperId);
        PaperStructure structure = PaperStructureParser.parse(paper.getStructureJson());
        PaperAttempts.Progress progress = PaperAttempts
                .of(attemptMapper, recordMapper, userId, List.of(paperId))
                .getOrDefault(paperId, PaperAttempts.Progress.NONE);
        boolean originalTextVisible = PaperLoad.originalTextVisible(progress.submitCount());
```

- [x] **Step 6：抽 `PaperSkeleton`（把 `toPartVOs` 整体搬过去，一行不改）**

`PaperServiceImpl` 里的 `private static List<PaperDetailVO.PartVO> toPartVOs(...)` 整段移动到新建的 `PaperSkeleton`，签名改为 `public static`，`originalTextVisible ? section.originalText() : null` 那行原样保留（它旁边的注释也一起搬）。`PaperServiceImpl` 里改成 `PaperSkeleton.toPartVOs(structure, originalTextVisible)`，并删掉不再需要的 import（`PaperDetailVO` 仍要，返回类型用）。

搬而不是重写的原因：段 4 那条"三层逐字段复制"的注释讲的是**同一个约束**（内存形态与出网形态必须是两个类型），注释跟着代码走才不会漂。

- [x] **Step 7：跑段 4 的两个测试类，确认行为未变**

```bash
"$MVN" -o -pl PracticeModule -am test -Dtest='PaperServiceTest+AnswerStateResponseGuardTest+PracticePaperControllerTest' -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

期望 `Tests run: 14`、`7`、`6` 全绿。**这一步是重构的判据**：这三类的断言若需要改才能过，说明抽出来的东西改变了行为，那就不是重构——停下来重新裁定，别把断言改了蒙过去。

- [x] **Step 8：全量绿 + 报告改动**

期望 **228/228**（218 + `ExamAnswersTest` 6 + `ExamDeadlineTest` 4，数字按实跑）。产物：三个新类 + 两个既有文件瘦身 + 两份新测试。

---

## Task 5.3：`ExamGrading` 判分内核（纯逻辑，裁定一/六的落点）

**Files:**
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/ExamGrading.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/mapper/QuestionOptionKeyRow.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/mapper/AttemptRecordRow.java`
- Modify: `PracticeModule/src/main/java/org/leon/practicemodule/mapper/QbQuestionOptionMapper.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/ExamGradingTest.java`

**Interfaces:**
- Consumes: `PaperStructure`、`AnswerGrader.grade(...)`、`ExamAnswers.decode/encode`
- Produces:
  - `record ExamGrading.Brief(long questionId, Integer type, String answerJson, String analysis, List<String> optionKeys)`
  - `record ExamGrading.Item(int index, Long questionId, Integer type, String userAnswerJson, Boolean correct, boolean needSelfJudge, BigDecimal scoreGot, BigDecimal fullScore, String correctAnswerJson, String analysis)`
  - `record ExamGrading.SectionResult(String name, BigDecimal scoreGot, BigDecimal fullScore, int correctCount, int pendingCount, int unansweredCount)`
  - `record ExamGrading.PaperGrade(List<Item> items, List<SectionResult> sections, BigDecimal score, BigDecimal fullScore, int correctCount, int wrongCount, int unansweredCount, int pendingCount, BigDecimal accuracy)`
  - `static PaperGrade ExamGrading.grade(PaperStructure structure, Map<Long,String> answers, Map<Long,Brief> briefs)`（**判分**，走 `AnswerGrader`）
  - `static PaperGrade ExamGrading.fromStored(PaperStructure structure, List<AttemptRecordRow> rows, Map<Long,Brief> briefs)`（**重建**，不判分——`is_correct` 已是真相）
  - `QuestionOptionKeyRow(long questionId, String optionKey)`；`QbQuestionOptionMapper.selectOptionKeysByQuestions(Collection<Long> ids) : List<QuestionOptionKeyRow>`
  - `AttemptRecordRow(Long questionId, String userAnswerJson, Integer isCorrect, Integer durationMs)`

- [x] **Step 1：写失败测试**

这份测试用**样例卷 P1 的真实结构**（两个 part、三个 section、四道题 1/6/2/5，`qid 5` 是 `type=5` 简答），所以裁定一/六的口径在真数据形状上被测：

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.mapper.AttemptRecordRow;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 判分内核的两条出口（grade / fromStored）必须给出**同一个形状**——成绩单只有一条装配路径（裁定四）。
 * 所以每个形状断言都写两遍，一遍真判分、一遍从存好的行重建。
 */
class ExamGradingTest {

    private static final AnswerGrader GRADER = new AnswerGrader();

    /** 样例卷 P1 的结构：I(14.00,25min)/A[1(7.00),6(7.00)] + II(16.00,40min)/A[2(10.00)] B[5(6.00)] */
    private static PaperStructure p1() {
        return PaperStructureParser.parse("""
                {"parts":[
                  {"roman":"I","name":"Listening Comprehension","cardLabel":"听力部分","score":14.00,"minutes":25,
                   "sections":[{"label":"Section A","subTitle":"短新闻","direction":"d","audioUrl":"a.mp3",
                     "originalText":"原文","questions":[{"index":1,"questionId":1,"score":7.00},
                                                         {"index":2,"questionId":6,"score":7.00}]}]},
                  {"roman":"II","name":"Reading and Writing","cardLabel":"阅读部分","score":16.00,"minutes":40,
                   "sections":[{"label":"Section A","subTitle":"多项选择","direction":null,"audioUrl":null,
                     "originalText":null,"questions":[{"index":3,"questionId":2,"score":10.00}]},
                    {"label":"Section B","subTitle":"简答题","direction":null,"audioUrl":null,
                     "originalText":null,"questions":[{"index":4,"questionId":5,"score":6.00}]}]}]}
                """);
    }

    /** qid1 单选答案 A、qid6 单选答案 C、qid2 多选 AB、qid5 简答（不判分） */
    private static Map<Long, ExamGrading.Brief> briefs() {
        Map<Long, ExamGrading.Brief> m = new LinkedHashMap<>();
        m.put(1L, new ExamGrading.Brief(1L, 1, "\"A\"", "A 对", List.of("A", "B", "C", "D")));
        m.put(6L, new ExamGrading.Brief(6L, 1, "\"C\"", "C 对", List.of("A", "B", "C")));
        m.put(2L, new ExamGrading.Brief(2L, 2, "[\"A\",\"B\"]", "AB 对", List.of()));
        m.put(5L, new ExamGrading.Brief(5L, 5, "\"参考答案\"", "简答解析", List.of()));
        return Map.copyOf(m);
    }

    @Test
    @DisplayName("全对：score=30.00、correct=4、wrong=0、unanswered=0、pending=1（简答不计分）")
    void allAnswered() {
        Map<Long, String> answers = new LinkedHashMap<>();
        answers.put(1L, "\"A\"");
        answers.put(6L, "\"C\"");
        answers.put(2L, "[\"A\",\"B\"]");
        answers.put(5L, "\"我的简答\"");

        ExamGrading.PaperGrade g = ExamGrading.grade(p1(), answers, briefs(), GRADER);

        assertEquals(0, new BigDecimal("30.00").compareTo(g.score()), "score=" + g.score());
        assertEquals(0, new BigDecimal("30.00").compareTo(g.fullScore()));
        assertEquals(3, g.correctCount(), "type=5 不进 correct_count（裁定一）");
        assertEquals(0, g.wrongCount());
        assertEquals(0, g.unansweredCount());
        assertEquals(1, g.pendingCount());
        assertEquals(1.0, g.accuracy().doubleValue(), 1e-9,
                "accuracy=correct/(correct+wrong)=3/3（裁定六：分母不含未答与待自评）");
        assertEquals(4, g.items().size());
        assertEquals(List.of(1, 2, 3, 4), g.items().stream().map(ExamGrading.Item::index).toList());
        assertNull(g.items().get(3).correct(), "待自评题的 isCorrect 必须是 null 而不是 false");
        assertTrue(g.items().get(3).needSelfJudge());
        assertEquals(0, new BigDecimal("6.00").compareTo(g.items().get(3).fullScore()),
                "fullScore 仍是这道题的卷面分值（判不了也不抹掉）");
        assertEquals(0, BigDecimal.ZERO.compareTo(g.items().get(3).scoreGot()),
                "待自评题不计分，scoreGot=0");
    }

    @Test
    @DisplayName("三级汇总：section 与 part 的 scoreGot 只对已判分的题求和")
    void sectionRollup() {
        Map<Long, String> answers = Map.of(1L, "\"B\"", 2L, "[\"A\",\"B\"]", 5L, "\"x\"");
        ExamGrading.PaperGrade g = ExamGrading.grade(p1(), answers, briefs(), GRADER);

        assertEquals(3, g.sections().size(), "三个 section：I/A、II/A、II/B");
        // I/A：qid1 错(0) + qid6 未答 → scoreGot 0，fullScore 14.00，unanswered 1
        ExamGrading.SectionResult listening = g.sections().get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(listening.scoreGot()));
        assertEquals(0, new BigDecimal("14.00").compareTo(listening.fullScore()));
        assertEquals(1, listening.unansweredCount());
        assertEquals(0, listening.correctCount());
        // II/A：多选对 → 10.00
        assertEquals(0, new BigDecimal("10.00").compareTo(g.sections().get(1).scoreGot()));
        // II/B：简答待自评 → 0 分、pending 1、未答 0
        assertEquals(0, BigDecimal.ZERO.compareTo(g.sections().get(2).scoreGot()));
        assertEquals(1, g.sections().get(2).pendingCount());
        assertEquals(0, g.sections().get(2).unansweredCount());

        assertEquals(1, g.correctCount());
        assertEquals(1, g.wrongCount());
        assertEquals(1, g.unansweredCount());
        assertEquals(1, g.pendingCount());
        assertEquals(0, new BigDecimal("10.00").compareTo(g.score()));
        assertEquals(0.50, g.accuracy().doubleValue(), 1e-9, "1/(1+1)=0.50，未答与待自评都不进分母");
    }

    @Test
    @DisplayName("一道没答：score 0、unanswered 4、accuracy 0.0（分母为 0 的保护）")
    void nothingAnswered() {
        ExamGrading.PaperGrade g = ExamGrading.grade(p1(), Map.of(), briefs(), GRADER);
        assertEquals(0, BigDecimal.ZERO.compareTo(g.score()));
        assertEquals(4, g.unansweredCount());
        assertEquals(0, g.correctCount());
        assertEquals(0, g.wrongCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(g.accuracy()),
                "分母 correct+wrong=0 → 0.0，不能 NaN 也不能除零炸");
        assertEquals(4, g.items().size(), "未答的题也要出现在成绩单的 questions 里（截图②要铺满整卷）");
        assertNull(g.items().get(0).userAnswerJson());
    }

    @Test
    @DisplayName("fromStored：按落库的 is_correct（-1/0/1）重建，结果与判分路径同形状")
    void rebuildMatchesGrade() {
        Map<Long, String> answers = new LinkedHashMap<>();
        answers.put(1L, "\"A\"");
        answers.put(6L, "\"C\"");
        answers.put(2L, "[\"A\",\"B\"]");
        answers.put(5L, "\"我的简答\"");
        ExamGrading.PaperGrade graded = ExamGrading.grade(p1(), answers, briefs(), GRADER);

        List<AttemptRecordRow> rows = List.of(
                new AttemptRecordRow(1L, "\"A\"", 1, 300),
                new AttemptRecordRow(6L, "\"C\"", 1, 300),
                new AttemptRecordRow(2L, "[\"A\",\"B\"]", 1, 300),
                new AttemptRecordRow(5L, "\"我的简答\"", -1, 300));
        ExamGrading.PaperGrade rebuilt = ExamGrading.fromStored(p1(), rows, briefs());

        assertEquals(graded.correctCount(), rebuilt.correctCount());
        assertEquals(graded.wrongCount(), rebuilt.wrongCount());
        assertEquals(graded.unansweredCount(), rebuilt.unansweredCount());
        assertEquals(graded.pendingCount(), rebuilt.pendingCount());
        assertEquals(0, graded.score().compareTo(rebuilt.score()));
        assertEquals(graded.items().size(), rebuilt.items().size());
        for (int i = 0; i < graded.items().size(); i++) {
            assertEquals(graded.items().get(i).correct(), rebuilt.items().get(i).correct());
            assertEquals(graded.items().get(i).needSelfJudge(), rebuilt.items().get(i).needSelfJudge());
        }
    }

    @Test
    @DisplayName("题目在卷面上但实体查不到（题被删了）→ 40400，不是 NPE")
    void missingBriefIsNotFound() {
        Map<Long, ExamGrading.Brief> partial = Map.of(1L,
                new ExamGrading.Brief(1L, 1, "\"A\"", "A 对", List.of("A", "B")));
        PracticeException e = assertThrows(PracticeException.class, () -> ExamGrading.grade(
                p1(), Map.of(1L, "\"A\""), partial, GRADER));
        assertEquals(org.leon.practicemodule.exception.PracticeErrorCode.NOT_FOUND, e.errorCode());
    }
}
```

- [x] **Step 2：跑一次确认红**

```bash
"$MVN" -o -pl PracticeModule -am test -Dtest=ExamGradingTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

期望编译失败：`找不到符号 ExamGrading` / `找不到符号 AttemptRecordRow`。

- [x] **Step 3：写两个投影记录与批量选项键查询**

`mapper/QuestionOptionKeyRow.java`：

```java
package org.leon.practicemodule.mapper;

/**
 * 一批题的选项键：**只选 question_id 与 option_key**，`is_correct` 一个字节都不读（spec 5.4）。
 * 判分需要的字母集合是它，而单题版的 selectOptionKeys(questionId) 在交卷时会变成 N+1。
 */
public record QuestionOptionKeyRow(Long questionId, String optionKey) {
}
```

`mapper/AttemptRecordRow.java`：

```java
package org.leon.practicemodule.mapper;

/**
 * 一次考试展开出的答题行（重建成绩单用）。
 * 不带 record id：前端在成绩单页不需要按行操作，`self-judge` 也不接受 mode=2 的行（裁定一）。
 */
public record AttemptRecordRow(Long questionId, String userAnswerJson,
                               Integer isCorrect, Integer durationMs) {
}
```

`QbQuestionOptionMapper` 追加：

```java
    /**
     * 一批题的选项键，交卷时一次取齐（零 N+1）。
     * IN 的判空在调用方：本模块的纪律是"空集合先发 SQL = MySQL 语法错 = 兜底谎报服务器繁忙"。
     */
    @Select("<script>SELECT question_id AS questionId, option_key AS optionKey"
            + " FROM qb_question_option WHERE question_id IN"
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + " ORDER BY question_id, sort_order</script>")
    List<QuestionOptionKeyRow> selectOptionKeysByQuestions(@Param("ids") Collection<Long> ids);
```

`QbUserAnswerRecordMapper` 追加：

```java
    /** 一次 attempt 展开出的全部答题行（终态重建成绩单的唯一真相源，`answered_json` 此时已是 NULL） */
    @Select("<script>SELECT r.question_id AS questionId, r.user_answer_json AS userAnswerJson,"
            + " r.is_correct AS isCorrect, r.duration_ms AS durationMs"
            + " FROM qb_user_answer_record r"
            + " WHERE r.user_id = #{userId} AND r.attempt_id = #{attemptId}"
            + " ORDER BY r.id</script>")
    List<AttemptRecordRow> selectByAttempt(@Param("userId") Long userId,
                                           @Param("attemptId") Long attemptId);
```

- [x] **Step 4：写 `ExamGrading`**

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.AttemptRecordRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 判分与三级汇总（题 → section → 卷），**不含任何 SQL**。
 *
 * <p>两条出口给出**同一个** {@link PaperGrade} 形状：
 * {@link #grade} 在交卷那一刻判分，{@link #fromStored} 在刷新/重进时按已落库的 is_correct 重建。
 * 成绩单只有一条装配路径（裁定四），所以"刚交完看到的"与"刷新后看到的"必然一致——
 * 这不是额外功能，是把两条路径捏成一个类型的后果。
 *
 * <p>判分本身一律外包给 {@link AnswerGrader}：它已经是 5 个题型与 MySQL 严格度的唯一口径
 * （段 2 的类缺陷就是在"自己再写一遍判分"的地方漏的），这里只负责"哪些题、什么顺序、各值多少分"。
 *
 * <p><b>裁定一在这里落地</b>：{@code type=5} 的题 {@code needSelfJudge=true} →
 * {@code correct} 为 null、{@code scoreGot} 为 0、进 {@code pendingCount}，
 * **不进** correct/wrong/unanswered，也不进 accuracy 的分母。
 */
public final class ExamGrading {

    /** is_correct 的三态哨兵（段 2 裁定一）。这里重复一次常量而不是 import 另一个包，
     *  是因为 AnswerServiceImpl 的那个是包级私有，跨包引用会牵出段 2 的测试。 */
    private static final int PENDING_SELF_JUDGE = -1;

    private ExamGrading() {
    }

    /** 一题的判分原料：来自 qb_question（批量取，零 N+1） */
    public record Brief(long questionId, Integer type, String answerJson, String analysis,
                        List<String> optionKeys) {
    }

    /** 成绩单的一题 */
    public record Item(int index, Long questionId, Integer type, String userAnswerJson,
                       Boolean correct, boolean needSelfJudge,
                       BigDecimal scoreGot, BigDecimal fullScore,
                       String correctAnswerJson, String analysis) {
    }

    /** 成绩单的一节（section 级汇总，spec 5.6 的 sections[]） */
    public record SectionResult(String name, BigDecimal scoreGot, BigDecimal fullScore,
                                int correctCount, int pendingCount, int unansweredCount) {
    }

    public record PaperGrade(List<Item> items, List<SectionResult> sections,
                             BigDecimal score, BigDecimal fullScore,
                             int correctCount, int wrongCount, int unansweredCount, int pendingCount,
                             BigDecimal accuracy) {
    }

    public static PaperGrade grade(PaperStructure structure, Map<Long, String> answers,
                                   Map<Long, Brief> briefs, AnswerGrader grader) {
        List<Item> items = new ArrayList<>();
        for (PaperStructure.QuestionRef ref : structure.questionsInOrder()) {
            Brief brief = requireBrief(briefs, ref.questionId());
            String userAnswer = answers.get(ref.questionId());
            if (userAnswer == null) {
                items.add(new Item(ref.index(), brief.questionId(), brief.type(), null,
                        null, false, BigDecimal.ZERO, ref.score(),
                        brief.answerJson(), brief.analysis()));
                continue;
            }
            AnswerGrader.GradeResult r = grader.grade(
                    brief.type(), brief.answerJson(), userAnswer, brief.optionKeys());
            BigDecimal got = r.needSelfJudge() || !r.correct()
                    ? BigDecimal.ZERO : ref.score();
            items.add(new Item(ref.index(), brief.questionId(), brief.type(), userAnswer,
                    r.needSelfJudge() ? null : r.correct(), r.needSelfJudge(),
                    got, ref.score(), brief.answerJson(), brief.analysis()));
        }
        return assemble(structure, items);
    }

    /** 从已落库的行重建。is_correct 的 -1/0/1 就是真相，不再判分——所以刷新不需要 answer_json 也在 */
    public static PaperGrade fromStored(PaperStructure structure, List<AttemptRecordRow> rows,
                                       Map<Long, Brief> briefs) {
        Map<Long, AttemptRecordRow> byQuestion = new HashMap<>();
        for (AttemptRecordRow row : rows) {
            byQuestion.put(row.questionId(), row);   // 同题多行时取后写的：一次交卷一题只有一行，见 Task 5.4
        }
        List<Item> items = new ArrayList<>();
        for (PaperStructure.QuestionRef ref : structure.questionsInOrder()) {
            Brief brief = requireBrief(briefs, ref.questionId());
            AttemptRecordRow row = byQuestion.get(ref.questionId());
            if (row == null) {
                items.add(new Item(ref.index(), brief.questionId(), brief.type(), null,
                        null, false, BigDecimal.ZERO, ref.score(),
                        brief.answerJson(), brief.analysis()));
                continue;
            }
            Integer isCorrect = row.isCorrect();
            boolean pending = isCorrect != null && isCorrect == PENDING_SELF_JUDGE;
            Boolean correct = isCorrect == null ? null : (pending ? null : isCorrect == 1);
            BigDecimal got = correct != null && correct ? ref.score() : BigDecimal.ZERO;
            items.add(new Item(ref.index(), brief.questionId(), brief.type(), row.userAnswerJson(),
                    correct, pending, got, ref.score(), brief.answerJson(), brief.analysis()));
        }
        return assemble(structure, items);
    }

    /**
     * 唯一的汇总出口：题 → section → 卷的所有计数与求和都在这里，
     * 两条路径共用，所以不存在"判分路径算了一种口径、重建路径算了另一种"的可能。
     */
    private static PaperGrade assemble(PaperStructure structure, List<Item> items) {
        Map<Integer, Item> byIndex = new HashMap<>();
        for (Item item : items) {
            byIndex.put(item.index(), item);
        }

        List<SectionResult> sections = new ArrayList<>();
        int correct = 0, wrong = 0, unanswered = 0, pending = 0;
        BigDecimal score = BigDecimal.ZERO;

        for (PaperStructure.Part part : structure.parts()) {
            for (PaperStructure.Section section : part.sections()) {
                BigDecimal sScore = BigDecimal.ZERO, sFull = BigDecimal.ZERO;
                int sCorrect = 0, sPending = 0, sUnanswered = 0;
                for (PaperStructure.QuestionRef ref : section.questions()) {
                    Item item = byIndex.get(ref.index());
                    sFull = sFull.add(ref.score());
                    sScore = sScore.add(item.scoreGot());
                    score = score.add(item.scoreGot());        // 卷级求和：漏掉这行 score 会永远是 0
                    if (item.userAnswerJson() == null) {
                        sUnanswered++;
                    } else if (item.needSelfJudge()) {
                        sPending++;
                    } else if (Boolean.TRUE.equals(item.correct())) {
                        sCorrect++;
                    } else {
                        wrong++;
                    }
                }
                sections.add(new SectionResult(section.label(), sScore, sFull,
                        sCorrect, sPending, sUnanswered));
                correct += sCorrect;
                pending += sPending;
                unanswered += sUnanswered;
            }
        }

        // fullScore 用 Σ 题目分值而不是 qb_paper.total_score：规则 3 已保证两者相等（段 4 校验），
        // 但成绩单是逐题算出来的，取卷面列会在"题目被改了分"时给出两个对不上的数。
        BigDecimal questionScoreSum = BigDecimal.ZERO;
        for (Item item : items) {
            questionScoreSum = questionScoreSum.add(item.fullScore());
        }

        return new PaperGrade(List.copyOf(items), List.copyOf(sections),
                score.setScale(2, RoundingMode.HALF_UP), questionScoreSum.setScale(2, RoundingMode.HALF_UP),
                correct, wrong, unanswered, pending, accuracy(correct, wrong));
    }

    /** 裁定六：分母只有"判过分的答案"。0/0 → 0.0，不是 NaN 也不是异常 */
    private static BigDecimal accuracy(int correct, int wrong) {
        int graded = correct + wrong;
        if (graded == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(correct)
                .divide(BigDecimal.valueOf(graded), 2, RoundingMode.HALF_UP);
    }

    private static Brief requireBrief(Map<Long, Brief> briefs, Long questionId) {
        Brief brief = briefs.get(questionId);
        if (brief == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在");
        }
        return brief;
    }
}
```

- [x] **Step 5：跑测试确认绿**

期望 `Tests run: 5, Failures: 0`。`allAnswered` 的 `score=30.00` 与 `sectionRollup` 的 `score=10.00` 这两条断言是**卷级求和唯一的守门人**——上面那行 `score = score.add(item.scoreGot())` 一旦漏掉，红的是它们而不是编译错误，这正是把它们写进测试的理由。

- [x] **Step 6：全量绿 + 报告改动**

```bash
"$MVN" -o -pl PracticeModule -am test
```

期望 **233**（228 + `ExamGradingTest` 5，数字按实跑）。产物：`ExamGrading` + 两个投影记录 + 两条新 mapper 查询 + 一份测试；`QbQuestionOptionMapper` 的 `is_correct` 检索仍应只在注释里（自查第 3 条）。


---

## Task 5.4：`ExamResultVO` 与 `ExamResults` 装配出口（裁定四的后半边：两条路径同一个形状）

**Files:**
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/pojo/vo/ExamResultVO.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/ExamResults.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/ExamResultsTest.java`

**Interfaces:**
- Consumes: `QbExamAttempt`、`QbPaper`、`ExamGrading.PaperGrade`、`PaperStructure`
- Produces:
  - `ExamResultVO`（见下）
  - `static ExamResultVO ExamResults.toVO(QbExamAttempt attempt, QbPaper paper, ExamGrading.PaperGrade grade)`
  - `static double ExamResults.progressPercentOf(int answeredCount, int questionCount)` —— 与段 4 的 `PaperCards.progressPercent` 同口径，**直接调它，不再写一遍**

- [x] **Step 1：写失败测试**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 成绩单是"交卷后一次给全"（spec 5.6），也是刷新后 attempt/detail 的同一份内容，
 * 所以装配必须只有一个函数、且每个字段的来源都能一句话说清。
 */
class ExamResultsTest {

    private static QbExamAttempt attempt() {
        QbExamAttempt a = new QbExamAttempt();
        a.setId(500L);
        a.setPaperId(8L);
        a.setSubjectId(1L);
        a.setSeqNo(2);
        a.setStatus(2);
        a.setSubmitAt(new Date(1_700_000_000_000L));
        a.setDurationUsedMs(120_000);
        return a;
    }

    private static QbPaper paper() {
        QbPaper p = new QbPaper();
        p.setId(8L);
        p.setTitle("2020年07月大学英语四级真题第1套（样例）");
        p.setDurationMinutes(65);
        p.setQuestionCount(4);
        p.setTotalScore(new BigDecimal("30.00"));
        return p;
    }

    @Test
    @DisplayName("成绩单逐题铺满：index/questionId/type/三个答案位/两个分值位都在")
    void resultCarriesEveryQuestion() {
        ExamGrading.PaperGrade grade = ExamGrading.grade(
                ExamFixtures.p1Structure(), ExamFixtures.allRight(), ExamFixtures.briefs(),
                new AnswerGrader());

        ExamResultVO vo = ExamResults.toVO(attempt(), paper(), grade);

        assertEquals(500L, vo.getAttemptId());
        assertEquals(2, vo.getSeqNo());
        assertEquals(2, vo.getStatus());
        assertEquals(8L, vo.getPaperId());
        assertEquals("2020年07月大学英语四级真题第1套（样例）", vo.getTitle());
        assertEquals(4, vo.getQuestions().size(), "未答的题也必须在（答题卡网格要铺满整卷）");
        assertEquals(1, vo.getQuestions().get(0).getIndex());
        assertEquals(0, new BigDecimal("7.00").compareTo(vo.getQuestions().get(0).getFullScore()));
        assertEquals(3, vo.getCorrectCount());
        assertEquals(1, vo.getPendingSelfJudgeCount(), "裁定一：待自评单独一位，不进 correct/wrong/unanswered");
        assertEquals(0, vo.getUnansweredCount());
        assertEquals(0, new BigDecimal("30.00").compareTo(vo.getScore()));
        assertEquals(0, new BigDecimal("30.00").compareTo(vo.getFullScore()));
        assertEquals(1.0, vo.getAccuracy().doubleValue(), 1e-9);
        assertEquals(120_000, vo.getDurationUsedMs());
        assertEquals(1_700_000_000_000L, vo.getSubmitAt(), "submitAt 是 epoch 毫秒（段 3 的既有口径）");
        assertEquals(3, vo.getSections().size());
        assertEquals("Section A", vo.getSections().get(0).getName());
    }

    @Test
    @DisplayName("status=1 的草稿不能出成绩单：调用方给什么状态就照写，判据在服务层不在这里")
    void draftAttemptStillAssembles() {
        QbExamAttempt draft = attempt();
        draft.setStatus(1);
        draft.setSubmitAt(null);
        ExamResultVO vo = ExamResults.toVO(draft, paper(),
                ExamGrading.grade(ExamFixtures.p1Structure(), ExamFixtures.allRight(),
                        ExamFixtures.briefs(), new AnswerGrader()));
        assertEquals(1, vo.getStatus());
        assertNull(vo.getSubmitAt(), "没交过就没有 submitAt，不能给 0");
    }

    @Test
    @DisplayName("questionCount=0 的脏数据：progressPercent 走 0.0 而不是除零")
    void zeroQuestionCountGuard() {
        assertEquals(0.0, ExamResults.progressPercentOf(0, 0), 1e-9);
        assertEquals(50.0, ExamResults.progressPercentOf(2, 4), 1e-9);
    }
}
```

`ExamFixtures` 是本段共用的测试夹具类（新建，见 Step 3），三个样例卷结构、briefs 与几组答案 map 都在里面——**四个测试类都要用它**，所以每处用到的结构都在这里定义一次，别在各类里复制。

- [x] **Step 2：跑一次确认红**

```bash
"$MVN" -o -pl PracticeModule -am test -Dtest=ExamResultsTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

期望编译失败：`找不到符号 ExamResults` / `ExamResultVO` / `ExamFixtures`。

- [x] **Step 3：写 `ExamFixtures` 与 `ExamResultVO`**

`test/.../service/ExamFixtures.java`：把 Task 5.3 Step 1 里那份 P1 结构字符串、`briefs()`（qid 1/6/2/5）与答案 map 抽成静态方法，签名固定。**这个类必须是 `public`**——`AnswerStateResponseGuardTest` 在 `controller` 包、`PracticeExamControllerTest` 亦然，包私有的夹具类它们用不到（这条是实测约束，不是风格选择）。

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbQuestion;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** 段 5 测试夹具：样例卷 P1 的真实结构 + 它的判分原料 + 三种实体工厂。多处复用，只在这里定义一次。 */
public final class ExamFixtures {

    /** P1 的 structure_json 原文：`p1Structure()` 与 `paperP1()` 共用这一份，别复制两遍 */
    public static final String P1_JSON = """
            {"parts":[
              {"roman":"I","name":"Listening Comprehension","cardLabel":"听力部分","score":14.00,"minutes":25,
               "sections":[{"label":"Section A","subTitle":"短新闻","direction":"d","audioUrl":"a.mp3",
                 "originalText":"News Report One（样例原文）","questions":[
                   {"index":1,"questionId":1,"score":7.00},{"index":2,"questionId":6,"score":7.00}]}]},
              {"roman":"II","name":"Reading and Writing","cardLabel":"阅读部分","score":16.00,"minutes":40,
               "sections":[{"label":"Section A","subTitle":"多项选择","direction":null,"audioUrl":null,
                 "originalText":null,"questions":[{"index":3,"questionId":2,"score":10.00}]},
                {"label":"Section B","subTitle":"简答题","direction":null,"audioUrl":null,
                 "originalText":null,"questions":[{"index":4,"questionId":5,"score":6.00}]}]}]}
            """;

    public static final long PAPER_ID = 8L;
    public static final long ATTEMPT_ID = 500L;

    private ExamFixtures() {
    }

    /** 与库里 qb_paper id=8 的 structure_json 同形态（两个 part、三个 section、四道题 1/6/2/5） */
    public static PaperStructure p1Structure() {
        return PaperStructureParser.parse(P1_JSON);
    }

    public static Map<Long, ExamGrading.Brief> briefs() {
        return Map.of(
                1L, new ExamGrading.Brief(1L, 1, "\"A\"", "A 对", List.of("A", "B", "C", "D")),
                6L, new ExamGrading.Brief(6L, 1, "\"C\"", "C 对", List.of("A", "B", "C")),
                2L, new ExamGrading.Brief(2L, 2, "[\"A\",\"B\"]", "AB 对", List.of()),
                5L, new ExamGrading.Brief(5L, 5, "\"参考答案\"", "简答解析", List.of()));
    }

    public static Map<Long, String> allRight() {
        return Map.of(1L, "\"A\"", 6L, "\"C\"", 2L, "[\"A\",\"B\"]", 5L, "\"我的简答\"");
    }

    /** 启用态（status=1）的题目实体，只填判分与卷面校验要用的列 */
    public static QbQuestion question(long id, int type, String answerJson) {
        return question(id, type, answerJson, 1);
    }

    /** 下架题（status=0）：`requireEnabled` 分支的反例 */
    public static QbQuestion disabled(long id, int type) {
        return question(id, type, "\"x\"", 0);
    }

    private static QbQuestion question(long id, int type, String answerJson, int status) {
        QbQuestion q = new QbQuestion();
        q.setId(id);
        q.setSubjectId(1L);
        q.setType(type);
        q.setAnswerJson(answerJson);
        q.setAnalysis("解析");
        q.setStatus(status);
        return q;
    }

    /** 样例卷 P1 的 qb_paper 行（id=8、科目 1、公开卷、4 题 30 分 65 分钟） */
    public static QbPaper paperP1() {
        QbPaper p = new QbPaper();
        p.setId(PAPER_ID);
        p.setSubjectId(1L);
        p.setOwnerId(1L);
        p.setTitle("2020年07月大学英语四级真题第1套（样例）");
        p.setDurationMinutes(65);
        p.setTotalScore(new BigDecimal("30.00"));
        p.setQuestionCount(4);
        p.setStatus(1);
        p.setStructureJson(P1_JSON);
        return p;
    }

    /**
     * 一条**进行中**（status=1）的草稿，attemptId 固定 500，deadline 设在将来。
     * 需要"过期""已提交"的用例自己改 `deadlineAt`/`status`——正因为默认值是"正常在考"，
     * 每条用例只需说明它偏离了哪一项，读测试时才不用倒推夹具。
     */
    public static QbExamAttempt draftAttempt(long userId, long paperId, String answeredJson) {
        long now = System.currentTimeMillis();
        QbExamAttempt a = new QbExamAttempt();
        a.setId(ATTEMPT_ID);
        a.setUserId(userId);
        a.setPaperId(paperId);
        a.setSubjectId(1L);
        a.setSeqNo(1);
        a.setStatus(1);
        a.setStartedAt(new Date(now - 60_000L));
        a.setDeadlineAt(new Date(now + 65 * 60_000L));
        a.setAnsweredJson(answeredJson);
        return a;
    }

    /** 只含一道单选题的最小卷面：用来验"全卷没有单选题就不发第二条 SQL" */
    public static PaperStructure singleBlankStructure() {
        return PaperStructureParser.parse("""
                {"parts":[{"roman":"I","name":"n","cardLabel":"c","score":1.00,"minutes":1,
                 "sections":[{"label":"A","subTitle":null,"direction":null,"audioUrl":null,
                   "originalText":null,"questions":[{"index":1,"questionId":1,"score":1.00}]}]}]}
                """);
    }
}
```

`pojo/vo/ExamResultVO.java`（spec §5.6 的 `ExamResultVO` 加两个字段，见裁定一/六）：

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 成绩单（spec 5.6）：`exam/submit` 的返回，也是 `exam/attempt/detail` 的返回——同一个类型，
 * 所以"刷新页面不丢解析"不是功能而是结果。
 *
 * <p>这份响应**允许带真值**（`correctAnswerJson`/`analysis`/`isCorrect`），因为它只在交卷后出现
 * （spec 5.4）。字节扫描的白名单里必须有它，否则守卫会把自己的成绩单判成泄漏。
 *
 * <p>{@code pendingSelfJudgeCount} 是段 5 加的第 1 个字段：考试里的简答题判不了分
 * （裁定一），既不是对也不是错更不是未答，四个计数里必须有一个地方放它，否则
 * {@code correct+wrong+unanswered != questionCount} 这个用户一眼能看出来的等式会破。
 */
@Data
public class ExamResultVO {

    private Long attemptId;
    private Integer seqNo;
    private Integer status;
    private Long paperId;
    private String title;

    private BigDecimal score;
    private BigDecimal fullScore;
    private Integer correctCount;
    private Integer wrongCount;
    private Integer unansweredCount;
    private Integer pendingSelfJudgeCount;
    private BigDecimal accuracy;

    private Integer durationUsedMs;
    /** epoch 毫秒（段 3 口径）；草稿态为 null */
    private Long submitAt;

    private List<SectionVO> sections;
    private List<QuestionVO> questions;

    @Data
    public static class SectionVO {
        private String name;
        private BigDecimal scoreGot;
        private BigDecimal fullScore;
        private Integer correctCount;
        private Integer pendingCount;
        private Integer unansweredCount;
    }

    /**
     * 成绩单里的一题：卷面骨架 + 用户答案 + 真值 + 解析。
     * `isCorrect` 用包装 Boolean——**null 就是"待自评"**（裁定一），不是 false。
     */
    @Data
    public static class QuestionVO {
        private Integer index;
        private Long questionId;
        private Integer type;
        private String userAnswerJson;
        private String correctAnswerJson;
        private Boolean isCorrect;
        private Boolean needSelfJudge;
        private BigDecimal scoreGot;
        private BigDecimal fullScore;
        private String analysis;
    }
}
```

- [x] **Step 4：写 `ExamResults`**

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.vo.ExamResultVO;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 成绩单的唯一装配出口（裁定四）。
 *
 * <p>它的存在理由：`submit` 刚刚判完分、`attempt/detail` 从库里重建、`start(forceNew)` 归档旧草稿
 * ——三处都要给用户同一份东西。写成三个装配函数就一定会漂（一个漏了 pendingSelfJudgeCount、
 * 一个把 submitAt 格式化成了字符串）。这里只做字段搬运，**不判分、不查库、不做业务判断**。
 */
public final class ExamResults {

    private ExamResults() {
    }

    public static ExamResultVO toVO(QbExamAttempt attempt, QbPaper paper, ExamGrading.PaperGrade grade) {
        ExamResultVO vo = new ExamResultVO();
        vo.setAttemptId(attempt.getId());
        vo.setSeqNo(attempt.getSeqNo());
        vo.setStatus(attempt.getStatus());
        vo.setPaperId(attempt.getPaperId());
        vo.setTitle(paper == null ? null : paper.getTitle());
        vo.setScore(grade.score());
        vo.setFullScore(grade.fullScore());
        vo.setCorrectCount(grade.correctCount());
        vo.setWrongCount(grade.wrongCount());
        vo.setUnansweredCount(grade.unansweredCount());
        vo.setPendingSelfJudgeCount(grade.pendingCount());
        vo.setAccuracy(grade.accuracy());
        vo.setDurationUsedMs(attempt.getDurationUsedMs());
        vo.setSubmitAt(millis(attempt.getSubmitAt()));

        List<ExamResultVO.SectionVO> sections = new ArrayList<>(grade.sections().size());
        for (ExamGrading.SectionResult s : grade.sections()) {
            ExamResultVO.SectionVO sv = new ExamResultVO.SectionVO();
            sv.setName(s.name());
            sv.setScoreGot(s.scoreGot());
            sv.setFullScore(s.fullScore());
            sv.setCorrectCount(s.correctCount());
            sv.setPendingCount(s.pendingCount());
            sv.setUnansweredCount(s.unansweredCount());
            sections.add(sv);
        }
        vo.setSections(List.copyOf(sections));

        List<ExamResultVO.QuestionVO> questions = new ArrayList<>(grade.items().size());
        for (ExamGrading.Item i : grade.items()) {
            ExamResultVO.QuestionVO qv = new ExamResultVO.QuestionVO();
            qv.setIndex(i.index());
            qv.setQuestionId(i.questionId());
            qv.setType(i.type());
            qv.setUserAnswerJson(i.userAnswerJson());
            qv.setCorrectAnswerJson(i.correctAnswerJson());
            qv.setIsCorrect(i.correct());
            qv.setNeedSelfJudge(i.needSelfJudge());
            qv.setScoreGot(i.scoreGot());
            qv.setFullScore(i.fullScore());
            qv.setAnalysis(i.analysis());
            questions.add(qv);
        }
        vo.setQuestions(List.copyOf(questions));
        return vo;
    }

    /** 进度百分比复用段 4 的口径（BigDecimal 2 位 HALF_UP 后转 double），两处必须一致 */
    public static double progressPercentOf(int answeredCount, int questionCount) {
        return PaperCards.progressPercent(answeredCount, questionCount);
    }

    private static Long millis(Date date) {
        return date == null ? null : date.getTime();
    }
}
```

- [x] **Step 5：跑测试确认绿**

期望 `Tests run: 3, Failures: 0`。

- [x] **Step 6：全量绿 + 报告改动**

期望 **236**（233 + 3，按实跑报）。产物：`ExamResultVO`、`ExamResults`、`ExamFixtures`（测试夹具）。

---

## Task 5.5：attempt 状态机数据层 + `ExamBriefs` + `ExamFinalizer`（唯一写终态的地方）

**Files:**
- Modify: `PracticeModule/src/main/java/org/leon/practicemodule/mapper/QbExamAttemptMapper.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/mapper/AttemptPageRow.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/ExamBriefs.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/ExamFinalizer.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/mapper/ExamFlowDbIT.java`（本段真库测试的地主，Task 5.6–5.9 继续往里加用例）
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/ExamBriefsTest.java`

**Interfaces:**
- Consumes: `QbPaperMapper`/`QbQuestionMapper`/`QbQuestionOptionMapper`/`QbUserAnswerRecordMapper`/`QbUserWrongMapper`、`ExamGrading`、`ExamAnswers`、`PaperStructureParser`
- Produces:
  - `QbExamAttemptMapper.selectDraftByUserAndPaper(Long userId, Long paperId) : QbExamAttempt`
  - `QbExamAttemptMapper.selectMaxSeqNo(Long userId, Long paperId) : int`
  - `QbExamAttemptMapper.finalizeIfDraft(QbExamAttempt attempt) : int`（**幂等闸门**）
  - `QbExamAttemptMapper.countForPaper(Long userId, Long paperId) : long`
  - `QbExamAttemptMapper.selectAttemptPage(Long userId, Long paperId, long offset, long pageSize) : List<AttemptPageRow>`
  - `Map<Long, QbQuestion> ExamBriefs.loadQuestions(PaperStructure structure)`（一次批量取整卷题目，空集合不发 SQL）
  - `Map<Long, ExamGrading.Brief> ExamBriefs.load(PaperStructure structure, boolean requireEnabled)`（判分原料：type/answer_json/analysis + `type==1` 的选项键；`requireEnabled=true` 时缺题 `40400`、下架 `40900`）
  - `ExamFinalizer.Outcome ExamFinalizer.finalizeAttempt(QbExamAttempt draft, PaperStructure structure, QbPaper paper, int terminalStatus, long nowMillis)`（判分要卷面结构、错题本要 `subject_id`，所以比"最小签名"多两个参数）
  - `record ExamFinalizer.Outcome(QbExamAttempt attempt, ExamGrading.PaperGrade grade, boolean justFinalized)`

- [x] **Step 1：写 db-it 的失败用例（真库先红）**

`ExamFlowDbIT` 沿用 `PaperSqlDbIT` 的样板（`UnpooledDataSource` + `MybatisSqlSessionFactoryBuilder` + `session.clearCache()`），合成用户 **`user_id=990005`**，`@Tag("db-it")`，每条用例前 purge `qb_exam_attempt` + `qb_user_answer_record` 的 990005 行，**`qb_paper` 一行都不许动**（跑完用探针复核 `qb_paper=4`）。

```java
    @Test
    @DisplayName("草稿→终态的条件更新是幂等闸门：第二次更新回 0 行，分数不会被重复写")
    void finalizeIfDraftIsTheIdempotencyGate() throws SQLException {
        long paperId = seededP1Id();
        long attemptId = insertAttempt(paperId, 1, 1, "{\"1\":\"A\"}");

        QbExamAttempt a = attemptMapper.selectById(attemptId);
        a.setStatus(2);
        a.setSubmitAt(new Date());
        a.setDurationUsedMs(60_000);
        a.setScore(new BigDecimal("7.00"));
        a.setCorrectCount(1);
        a.setWrongCount(0);
        a.setUnansweredCount(3);
        assertEquals(1, attemptMapper.finalizeIfDraft(a), "草稿必须能被推成终态");
        flushCache();

        assertEquals(2, attemptMapper.selectById(attemptId).getStatus());
        assertEquals(1, attemptMapper.finalizeIfDraft(a), "同一行再推一次必须回 0——这就是幂等闸门");
        flushCache();

        assertNull(attemptMapper.selectById(attemptId).getAnsweredJson(),
                "交卷后 answered_json 必须置 NULL（段 4 已证的不变式：map 与展开行不能同时是真相）");
    }

    @Test
    @DisplayName("uk_attempt_user_paper_seq 拦住并发双击的第二次 insert（消息带表名前缀）")
    void uniqueKeyBlocksDuplicateSeqNo() throws SQLException {
        long paperId = seededP1Id();
        insertAttempt(paperId, 1, 1, null);
        SQLException dup = assertThrows(SQLException.class,
                () -> insertAttempt(paperId, 1, 1, null));
        assertTrue(dup.getMessage().contains("uk_attempt_user_paper_seq"), dup.getMessage());
        assertEquals(1, attemptMapper.selectMaxSeqNo(TEST_USER, paperId), "seq 只到 1");
    }

    @Test
    @DisplayName("selectMaxSeqNo 与 selectDraftByUserAndPaper：终态不算草稿、MAX 跟着 id 走")
    void draftLookup() throws SQLException {
        long paperId = seededP1Id();
        assertEquals(0, attemptMapper.selectMaxSeqNo(TEST_USER, paperId), "一张卷子都没答过");
        assertNull(attemptMapper.selectDraftByUserAndPaper(TEST_USER, paperId));

        insertAttempt(paperId, 1, 2, null);            // 已提交，不算草稿
        assertNull(attemptMapper.selectDraftByUserAndPaper(TEST_USER, paperId));
        assertEquals(1, attemptMapper.selectMaxSeqNo(TEST_USER, paperId));

        long draftId = insertAttempt(paperId, 2, 1, "{\"1\":\"A\"}");
        assertEquals(draftId, attemptMapper.selectDraftByUserAndPaper(TEST_USER, paperId).getId());
        assertEquals(2, attemptMapper.selectMaxSeqNo(TEST_USER, paperId));
    }
```

- [x] **Step 2：跑一次确认红**

```bash
"$MVN" -o -pl PracticeModule -am test -Dtest=ExamFlowDbIT -Dsurefire.excludedGroups= -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

期望编译失败：`找不到符号 finalizeIfDraft` / `selectDraftByUserAndPaper` / `selectMaxSeqNo`。

- [x] **Step 3：写 mapper 层的五条查询与投影行**

```java
    /**
     * 该用户该卷的当前草稿。业务上最多一条（status=1 的行只由 start 建，交卷即终态），
     * 但 `ORDER BY id DESC LIMIT 1` 不是多余的：万一有人手改库留下两条草稿，
     * 返回最新那条也符合"继续考试"的语义，而 selectOne 会直接抛 TooManyResultsException。
     */
    @Select("SELECT * FROM qb_exam_attempt WHERE user_id = #{userId} AND paper_id = #{paperId}"
            + " AND status = 1 ORDER BY id DESC LIMIT 1")
    QbExamAttempt selectDraftByUserAndPaper(@Param("userId") Long userId,
                                            @Param("paperId") Long paperId);

    /** 第几次作答 = MAX(seq_no)+1。没有行时回 0，调用方 +1 得 1 */
    @Select("SELECT COALESCE(MAX(seq_no), 0) FROM qb_exam_attempt"
            + " WHERE user_id = #{userId} AND paper_id = #{paperId}")
    int selectMaxSeqNo(@Param("userId") Long userId, @Param("paperId") Long paperId);

    /**
     * 幂等闸门（裁定四）：**只有草稿能被推成终态**，一条 UPDATE 同时写完分数快照并把
     * answered_json 置 NULL。回 0 行就是"已经交过了"，不靠先查后判——那是 check-then-act，
     * 并发双击会双重计分。
     */
    @Update("UPDATE qb_exam_attempt SET status = #{status}, submit_at = #{submitAt},"
            + " duration_used_ms = #{durationUsedMs}, score = #{score},"
            + " correct_count = #{correctCount}, wrong_count = #{wrongCount},"
            + " unanswered_count = #{unansweredCount}, answered_json = NULL"
            + " WHERE id = #{id} AND user_id = #{userId} AND status = 1")
    int finalizeIfDraft(QbExamAttempt attempt);

    @Select("SELECT COUNT(*) FROM qb_exam_attempt WHERE user_id = #{userId} AND paper_id = #{paperId}")
    long countForPaper(@Param("userId") Long userId, @Param("paperId") Long paperId);

    /** 某张卷子的历次作答（`已提交次数`点进去的那页）。按 id 倒序：新一次在前，与 MAX(id) 口径一致 */
    @Select("SELECT a.id AS attemptId, a.seq_no AS seqNo, a.status AS status, a.score AS score,"
            + " a.submit_at AS submitAt, a.duration_used_ms AS durationUsedMs,"
            + " a.correct_count AS correctCount, a.wrong_count AS wrongCount,"
            + " a.unanswered_count AS unansweredCount"
            + " FROM qb_exam_attempt a WHERE a.user_id = #{userId} AND a.paper_id = #{paperId}"
            + " ORDER BY a.id DESC LIMIT #{offset}, #{pageSize}")
    List<AttemptPageRow> selectAttemptPage(@Param("userId") Long userId, @Param("paperId") Long paperId,
                                           @Param("offset") long offset, @Param("pageSize") long pageSize);
```

**这条查询故意不选"待自评数"**：`qb_exam_attempt` 只有 17 列（事实 2），库里没有 `pending_count` 这么一列，写出来就是 `Unknown column`。待自评数由服务层用 `question_count - correct - wrong - unanswered` 算（Task 5.9 Step 4），**join `qb_paper` 反而多一张表**，减法不会漂。

`mapper/AttemptPageRow.java`（按上面的正确形态，不含占位列）：

```java
package org.leon.practicemodule.mapper;

import java.math.BigDecimal;
import java.util.Date;

/** attempt/page 的一行。**score/correctCount/… 在 status=1 或 3-未判分时可为 NULL**，所以全用包装类型。 */
public record AttemptPageRow(Long attemptId, Integer seqNo, Integer status, BigDecimal score,
                             Date submitAt, Integer durationUsedMs,
                             Integer correctCount, Integer wrongCount, Integer unansweredCount) {
}
```

- [x] **Step 4：写 `ExamBriefs`（批量取判分原料，零 N+1）**

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QuestionOptionKeyRow;
import org.leon.practicemodule.pojo.QbQuestion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张卷子的判分原料：一次取题目、一次取选项键（**零 N+1**）。
 *
 * <p>选项键只给 `type==1` 的题取（其余题型的判分不需要字母集合，段 2 就是这么传的），
 * 且只 `SELECT question_id, option_key`——`is_correct` 一个字节都不读（spec 5.4）。
 *
 * <p>做成 bean 而不是静态工具，是因为它要两个 mapper；`ExamFinalizer`、`exam/question` 的
 * 校验与 `attempt/detail` 的重建都调它，判分路径与重建路径因此共用同一份取数逻辑。
 *
 * <p>{@code requireEnabled} 是**两条时机的差异**，不是开关式的"以后可能用"：
 * 开考与交卷时卷面必须完整（题被删 → 40400，题下架 → 40900，与 §5.7 的口径一致）；
 * 而看已交卷的成绩单时**题目可能已经下架**，这时候必须照样能看——所以重建路径传 false。
 * 把这条差异写成注释而不是两个方法，是因为它只差一次 status 判断，分成两个方法一定会有一个忘了同步。
 */
@Component
public class ExamBriefs {

    private final QbQuestionMapper questionMapper;
    private final QbQuestionOptionMapper optionMapper;

    public ExamBriefs(QbQuestionMapper questionMapper, QbQuestionOptionMapper optionMapper) {
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
    }

    /** 卷面涉及的全部题目（去重后一次批量取）。空 ids 挡在前面，不发 SQL */
    public Map<Long, QbQuestion> loadQuestions(PaperStructure structure) {
        List<Long> ids = structure.questionsInOrder().stream()
                .map(PaperStructure.QuestionRef::questionId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, QbQuestion> out = new LinkedHashMap<>();
        for (QbQuestion q : questionMapper.selectBatchIds(ids)) {
            out.put(q.getId(), q);
        }
        return Map.copyOf(out);
    }

    public Map<Long, ExamGrading.Brief> load(PaperStructure structure, boolean requireEnabled) {
        Map<Long, QbQuestion> questions = loadQuestions(structure);
        List<Long> ids = structure.questionsInOrder().stream()
                .map(PaperStructure.QuestionRef::questionId).distinct().toList();
        if (requireEnabled) {
            for (Long id : ids) {
                QbQuestion q = questions.get(id);
                if (q == null) {
                    throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在");
                }
                if (q.getStatus() == null || q.getStatus() != 1) {
                    throw new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架");
                }
            }
        }
        // 只有启用状态的单选题需要字母集合：判分用得到；重建时这道题可能已经下架，取不到也不报错
        List<Long> singleChoice = new ArrayList<>();
        for (Long id : ids) {
            QbQuestion q = questions.get(id);
            if (q != null && q.getType() != null && q.getType() == 1) {
                singleChoice.add(id);
            }
        }
        Map<Long, List<String>> keysByQuestion = new HashMap<>();
        if (!singleChoice.isEmpty()) {
            for (QuestionOptionKeyRow row : optionMapper.selectOptionKeysByQuestions(singleChoice)) {
                keysByQuestion.computeIfAbsent(row.questionId(), k -> new ArrayList<>())
                        .add(row.optionKey());
            }
        }
        Map<Long, ExamGrading.Brief> out = new LinkedHashMap<>();
        for (Map.Entry<Long, QbQuestion> e : questions.entrySet()) {
            QbQuestion q = e.getValue();
            out.put(e.getKey(), new ExamGrading.Brief(q.getId(), q.getType(), q.getAnswerJson(),
                    q.getAnalysis(), List.copyOf(keysByQuestion.getOrDefault(e.getKey(), List.of()))));
        }
        return Map.copyOf(out);
    }
}
```

`Brief` 一次性构造到位（选项键先算再建），不留"建完再改 record 分量"的第二遍。SQL 条数上限 2，由 Step 5 的 `times(1)` 钉住。

- [x] **Step 5：写 `ExamBriefsTest`**

```java
    @Test
    @DisplayName("两次批量：题目一次、选项键一次；没有单选题时不发第二条")
    void loadsInTwoQueries() {
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.question(6L, 1, "\"C\""),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]"), ExamFixtures.question(5L, 5, "\"参考答案\"")));
        when(optionMapper.selectOptionKeysByQuestions(any())).thenReturn(List.of(
                new QuestionOptionKeyRow(1L, "A"), new QuestionOptionKeyRow(1L, "B"),
                new QuestionOptionKeyRow(6L, "A"), new QuestionOptionKeyRow(6L, "C")));

        Map<Long, ExamGrading.Brief> loaded = briefs.load(ExamFixtures.p1Structure(), true);

        assertEquals(4, loaded.size());
        assertEquals(List.of("A", "B"), loaded.get(1L).optionKeys());
        assertEquals(List.of("A", "C"), loaded.get(6L).optionKeys());
        assertEquals(List.of(), loaded.get(2L).optionKeys(), "多选题不需要选项键集合");
        verify(questionMapper, times(1)).selectBatchIds(any());
        verify(optionMapper, times(1)).selectOptionKeysByQuestions(any());

        reset(questionMapper, optionMapper);
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(ExamFixtures.question(1L, 2, "[\"A\"]")));
        briefs.load(ExamFixtures.singleBlankStructure(), true);
        // 全卷没有单选题就不该发第二条 SQL：
        verifyNoInteractions(optionMapper);
    }

    @Test
    @DisplayName("requireEnabled 的两条分支：缺题 40400、下架 40900；false 时下架放过")
    void enabledGateDiffersByCaller() {
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.disabled(6L, 1),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]"), ExamFixtures.question(5L, 5, "\"参考答案\"")));
        when(optionMapper.selectOptionKeysByQuestions(any())).thenReturn(List.of(
                new QuestionOptionKeyRow(1L, "A"), new QuestionOptionKeyRow(6L, "B")));

        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> briefs.load(ExamFixtures.p1Structure(), true)).errorCode());
        // 成绩单重建路径：题下架了也必须能看到已交卷的成绩，所以同一条数据在 false 下要放过
        assertEquals(4, briefs.load(ExamFixtures.p1Structure(), false).size());

        reset(questionMapper, optionMapper);
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.question(6L, 1, "\"C\""),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]")));
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> briefs.load(ExamFixtures.p1Structure(), true)).errorCode(), "qid 5 查不到 = 题被删了");
    }

    @Test
    @DisplayName("空 IN 挡在前面：卷面零题时一条 SQL 都不发")
    void emptyStructureShortCircuits() {
        assertEquals(Map.of(), briefs.loadQuestions(new PaperStructure(List.of())));
        verifyNoInteractions(questionMapper, optionMapper);
    }
```

- [x] **Step 6：写 `ExamFinalizer`**

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.mapper.QbExamAttemptMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * **整段唯一把 attempt 推成终态的地方。** `submit`、`start(forceNew)`、`start` 发现的过期草稿
 * 三条路径全部走它（裁定三），所以"终态 ⇒ 有分数快照 ⇒ answered_json 已置 NULL"这条不变式
 * 只需要在一处维护。
 *
 * <p>交卷事务的八步（spec 5.6）里，第 7 步"**不更新 qb_user_subject_stat**"是刻意的
 * （spec 7.1：刷一套 25 题的真题会按题量权重压弯科目正确率）。这里只动错题本，
 * 一次都不碰统计 mapper——ExamSubmitTest 用 `verifyNoInteractions(statMapper)` 钉住这件事。
 *
 * <p>未答的题**不落记录行**：`qb_user_answer_record.user_answer_json` 是 `json NOT NULL`
 * （事实 1），写 NULL 会被 1364 拒。未答只进 `unanswered_count`。
 */
@Service
public class ExamFinalizer {

    private static final int ANSWER_MODE_EXAM = 2;
    private static final int PENDING_SELF_JUDGE = -1;

    private final QbExamAttemptMapper attemptMapper;
    private final QbUserAnswerRecordMapper recordMapper;
    private final QbUserWrongMapper wrongMapper;
    private final ExamBriefs briefs;
    private final AnswerGrader grader;

    public ExamFinalizer(QbExamAttemptMapper attemptMapper,
                         QbUserAnswerRecordMapper recordMapper,
                         QbUserWrongMapper wrongMapper,
                         ExamBriefs briefs, AnswerGrader grader) {
        this.attemptMapper = attemptMapper;
        this.recordMapper = recordMapper;
        this.wrongMapper = wrongMapper;
        this.briefs = briefs;
        this.grader = grader;
    }

    /** 交完之后的样子：attempt 是终态行（重读过），grade 是成绩单原料，justFinalized=false 表示"本来就已交过" */
    public record Outcome(QbExamAttempt attempt, ExamGrading.PaperGrade grade, boolean justFinalized) {
    }

    @Transactional
    public Outcome finalizeAttempt(QbExamAttempt draft, PaperStructure structure, QbPaper paper,
                            int terminalStatus, long nowMillis) {
        Map<Long, String> answers = ExamAnswers.decode(draft.getAnsweredJson());
        // 判分路径 requireEnabled=true：题被删/下架时交卷必须报错，不能算出一份错乱成绩
        Map<Long, ExamGrading.Brief> brief = briefs.load(structure, true);
        ExamGrading.PaperGrade grade = ExamGrading.grade(structure, answers, brief, grader);

        QbExamAttempt terminal = terminalRow(draft, terminalStatus, grade, nowMillis);
        int updated = attemptMapper.finalizeIfDraft(terminal);
        if (updated == 0) {
            // 幂等闸门关上：有人先交了（并发双击、或前端重试）。不重复展开、不重复计分。
            QbExamAttempt existing = attemptMapper.selectById(draft.getId());
            Map<Long, ExamGrading.Brief> stored = briefs.load(structure, false);
            return new Outcome(existing,
                    ExamGrading.fromStored(structure,
                            recordMapper.selectByAttempt(existing.getUserId(), existing.getId()), stored),
                    false);
        }
        expandRecords(draft, terminal, grade);
        linkWrongBook(draft.getUserId(), paper, grade);
        QbExamAttempt done = attemptMapper.selectById(draft.getId());
        return new Outcome(done, grade, true);
    }

    /** 把已答题展开成 qb_user_answer_record 行（attempt_id 非空、answer_mode=2、用时均摊） */
    private void expandRecords(QbExamAttempt draft, QbExamAttempt terminal,
                               ExamGrading.PaperGrade grade) {
        List<ExamGrading.Item> answeredItems = new ArrayList<>();
        for (ExamGrading.Item item : grade.items()) {
            if (item.userAnswerJson() != null) {
                answeredItems.add(item);
            }
        }
        // 均摊的分母是"已答题数"，而时长取的是刚算出来的终态行（draft 上的 duration_used_ms 还是 null）
        Integer per = perQuestionMillis(terminal.getDurationUsedMs(), answeredItems.size());
        // 一条一条 insert 是**故意的**：本模块没有批量 insert 的自定义 SQL，
        // 而一次交卷最多几十题，几十条 INSERT 在同一事务里比拼一条多值 INSERT 更清楚，
        // 也更重要的是——不引入"批量拼 SQL"的注入面。ExamSubmitTest 钉住"行数 = 已答题数"。
        for (ExamGrading.Item item : answeredItems) {
            QbUserAnswerRecord row = new QbUserAnswerRecord();
            row.setUserId(draft.getUserId());
            row.setSubjectId(draft.getSubjectId());
            row.setAttemptId(draft.getId());
            row.setQuestionId(item.questionId());
            row.setUserAnswerJson(item.userAnswerJson());
            row.setIsCorrect(item.needSelfJudge() ? PENDING_SELF_JUDGE
                    : (Boolean.TRUE.equals(item.correct()) ? 1 : 0));
            row.setDurationMs(per);
            row.setAnswerMode(ANSWER_MODE_EXAM);
            recordMapper.insert(row);
        }
    }

    /** 错题本联动（spec 5.6 第 6 步）；**不碰 qb_user_subject_stat**（第 7 步、spec 7.1） */
    private void linkWrongBook(Long userId, QbPaper paper, ExamGrading.PaperGrade grade) {
        for (ExamGrading.Item item : grade.items()) {
            if (item.needSelfJudge() || item.userAnswerJson() == null) {
                continue;   // 待自评与未答都不进错题本（段 2 的同一口径）
            }
            if (Boolean.TRUE.equals(item.correct())) {
                wrongMapper.masterIfUnmastered(userId, item.questionId());
            } else {
                wrongMapper.upsertWrong(userId, paper.getSubjectId(), item.questionId());
            }
        }
    }

    /** 整场用时均摊到已答题（spec 11：考试里没有逐题计时）。已答题 0 道时给 NULL，不给 0 */
    private static Integer perQuestionMillis(Integer used, int answered) {
        if (used == null || answered <= 0) {
            return null;
        }
        return (int) (used.longValue() / answered);
    }

    private static QbExamAttempt terminalRow(QbExamAttempt draft, int status,
                                             ExamGrading.PaperGrade grade, long nowMillis) {
        QbExamAttempt row = new QbExamAttempt();
        row.setId(draft.getId());
        row.setUserId(draft.getUserId());
        row.setStatus(status);
        row.setSubmitAt(new Date(nowMillis));
        row.setDurationUsedMs((int) Math.max(0L, nowMillis - draft.getStartedAt().getTime()));
        row.setScore(grade.score());
        row.setCorrectCount(grade.correctCount());
        row.setWrongCount(grade.wrongCount());
        row.setUnansweredCount(grade.unansweredCount());
        return row;
    }
}
```

**`expandRecords` 的第二个参数是 `terminal`（终态行）而不是 `draft`**：均摊用时的数据只在 `terminalRow` 里才算得出来（草稿行的 `duration_used_ms` 永远是 null），传错对象不会编译失败、只会让每一行的 `duration_ms` 静默变成 null。所以 Task 5.9 的 `ExamSubmitTest` 里必须有 `assertNotNull(rows.get(0).getDurationMs())` 这一条——它是这段代码唯一的防线（`ExamFinalizer` 不进 db-it，db-it 只测 SQL）。

- [x] **Step 7：跑 db-it 确认绿**

```bash
"$MVN" -o -pl PracticeModule -am test -Dtest=ExamFlowDbIT -Dsurefire.excludedGroups= -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

期望 `Tests run: 3, Failures: 0`。**跑完必须复核**：`qb_paper` 仍 4 行、`user_id=990005` 两张表 0 行（`Seg5Residue.java`，照 `Seg4Residue` 抄）。

- [x] **Step 8：全量绿 + 报告改动**

期望 **239**（236 + `ExamBriefsTest` 3，按实跑报）。产物：五条 attempt 查询 + `AttemptPageRow` + `ExamBriefs` + `ExamFinalizer` + `ExamFlowDbIT`。

---

## Task 5.6：`exam/start`（复用草稿 / 新建 / forceNew 归档 / 超时懒判 / 撞键重读）

**Files:**
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/pojo/ExamStartReq.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/pojo/vo/ExamStartVO.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/ExamService.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/impl/ExamServiceImpl.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/controller/PracticeExamController.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/ExamServiceTest.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/controller/PracticeExamControllerTest.java`

**Interfaces:**
- Consumes: `PaperLoad.loadVisible`、`PaperStructureParser.parse`、`PaperStructureValidator.validate`（裁定四：`exam/start` 是第二个调用者）、`ExamBriefs.load`、`ExamFinalizer.finalize`、`ExamAnswers`、`ExamDeadline.expired`、`PaperSkeleton.toPartVOs`、`QbExamAttemptMapper`
- Produces:
  - `ExamService.start(Long userId, Long paperId, Boolean forceNew) : ExamStartVO`
  - `ExamStartVO {attemptId, seqNo, serverNow, deadlineAt, remainingMs, durationMinutes, paperId, title, questionCount, answeredCount, progressPercent, totalScore, structure, answeredMap}`
  - `POST /practice/exam/start`（body `{paperId, forceNew?}`）

- [ ] **Step 1：写失败测试（service 层，mock mapper）**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.*;
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.vo.ExamStartVO;
import org.leon.practicemodule.service.impl.ExamServiceImpl;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * start 的五个分支：无草稿新建、复用草稿、forceNew 归档再新建、过期草稿归档再新建、撞唯一键重读。
 * 每个分支都要有"另一条路径没被走到"的反证（verifyNoInteractions 或 times(1)），
 * 否则"复用草稿"其实每次都新建、测试也照样绿。
 */
class ExamServiceTest {

    private static final long USER = 10L;
    private static final long PAPER = 8L;

    private QbPaperMapper paperMapper;
    private QbExamAttemptMapper attemptMapper;
    private QbUserAnswerRecordMapper recordMapper;
    private QbQuestionMapper questionMapper;
    private QbQuestionOptionMapper optionMapper;
    private QbUserWrongMapper wrongMapper;
    private ExamBriefs briefs;
    private ExamFinalizer finalizer;
    private ExamServiceImpl service;

    @BeforeEach
    void setUp() {
        paperMapper = mock(QbPaperMapper.class);
        attemptMapper = mock(QbExamAttemptMapper.class);
        recordMapper = mock(QbUserAnswerRecordMapper.class);
        questionMapper = mock(QbQuestionMapper.class);
        optionMapper = mock(QbQuestionOptionMapper.class);
        wrongMapper = mock(QbUserWrongMapper.class);
        briefs = new ExamBriefs(questionMapper, optionMapper);
        finalizer = new ExamFinalizer(attemptMapper, recordMapper, wrongMapper, briefs, new AnswerGrader());
        service = new ExamServiceImpl(paperMapper, attemptMapper, recordMapper,
                briefs, finalizer, new PracticeProperties());
        when(paperMapper.selectById(PAPER)).thenReturn(ExamFixtures.paperP1());
        stubQuestions();
    }

    /** 判分原料：qid 1/6/2/5 的 type 与 answer_json、type=1 的选项键 */
    private void stubQuestions() {
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.question(6L, 1, "\"C\""),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]"), ExamFixtures.question(5L, 5, "\"参考答案\"")));
        when(optionMapper.selectOptionKeysByQuestions(any())).thenReturn(List.of(
                new QuestionOptionKeyRow(1L, "A"), new QuestionOptionKeyRow(1L, "B"),
                new QuestionOptionKeyRow(6L, "A"), new QuestionOptionKeyRow(6L, "C")));
    }

    /** 本类所有用例共用的一条草稿：attemptId 固定 500，只有 seq_no 与草稿内容随用例变 */
    private static QbExamAttempt draft(int seqNo, String answeredJson) {
        QbExamAttempt attempt = ExamFixtures.draftAttempt(USER, PAPER, answeredJson);
        attempt.setSeqNo(seqNo);
        return attempt;
    }

    /** 归档/提交之后库里那条行：status 由调用方给（2 已提交 / 3 超时归档），id 与草稿一致 */
    private static QbExamAttempt terminal(QbExamAttempt from, int status) {
        QbExamAttempt attempt = ExamFixtures.draftAttempt(from.getUserId(), from.getPaperId(), null);
        attempt.setStatus(status);
        attempt.setSubmitAt(new Date());
        attempt.setDurationUsedMs(60_000);
        attempt.setScore(new BigDecimal("7.00"));
        attempt.setCorrectCount(1);
        attempt.setWrongCount(0);
        attempt.setUnansweredCount(3);
        return attempt;
    }

    @Test
    @DisplayName("没有草稿：新建 seq_no=1，deadline = now + 65 分钟，answeredMap 空")
    void createsFirstAttempt() {
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER)).thenReturn(null);
        when(attemptMapper.selectMaxSeqNo(USER, PAPER)).thenReturn(0);

        ExamStartVO vo = service.start(USER, PAPER, false);

        assertEquals(1, vo.getSeqNo());
        assertEquals(0L, vo.getAnsweredCount());
        assertEquals(4, vo.getQuestionCount());
        assertEquals(0.0, vo.getProgressPercent(), 1e-9);
        assertTrue(vo.getAnsweredMap().isEmpty());
        assertEquals(65, vo.getDurationMinutes());
        assertEquals(2, vo.getStructure().size(), "卷面骨架两层 part 都要在（答题卡与 Part 标题用）");
        assertEquals(25L, vo.getStructure().get(0).getMinutes());
        assertNull(vo.getStructure().get(0).getSections().get(0).getOriginalText(),
                "考试中不许看见听力原文（裁定二在 start 的响应里同样成立）");
        assertNotNull(vo.getDeadlineAt());
        assertEquals(65 * 60_000L, vo.getDeadlineAt() - vo.getServerNow(),
                "deadline 必须由服务端算，前端只信 deadlineAt-serverNow");
        assertEquals(1, vo.getRemainingMs() > 0 ? 1 : 0);
        verify(attemptMapper, times(1)).insert(any());
    }

    @Test
    @DisplayName("已有未过期草稿：复用同一个 attemptId，一次都不 insert（9.3 的刷新不丢草稿）")
    void reusesActiveDraft() {
        QbExamAttempt draft = draft(1, "{\"1\":\"A\",\"2\":\"[\\\"A\\\",\\\"B\\\"]\"}");
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER)).thenReturn(draft);

        ExamStartVO vo = service.start(USER, PAPER, false);

        assertEquals(draft.getId(), vo.getAttemptId());
        assertEquals(1, vo.getSeqNo());
        assertEquals(2, vo.getAnsweredCount());
        assertEquals(50.0, vo.getProgressPercent(), 1e-9);
        assertEquals("\"A\"", vo.getAnsweredMap().get("1"),
                "key 是十进制题号字符串（裁定五），前端按 questionId 查");
        verify(attemptMapper, never()).insert(any());
        // 复用草稿这条路径绝不能把任何行推成终态，否则"刷新页面"会悄悄交卷
        verify(attemptMapper, never()).finalizeIfDraft(any());
    }

    @Test
    @DisplayName("过期草稿（非 forceNew）：先归档判分再新建，seq_no 递增")
    void expiredDraftIsArchivedThenNewOneCreated() {
        QbExamAttempt stale = draft(1, "{\"1\":\"A\"}");
        stale.setDeadlineAt(new Date(System.currentTimeMillis() - 120_000L));   // 过期 2 分钟 > grace 60
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER)).thenReturn(stale);
        when(attemptMapper.selectMaxSeqNo(USER, PAPER)).thenReturn(1);
        when(attemptMapper.selectById(stale.getId())).thenReturn(terminal(stale, 3));

        ExamStartVO vo = service.start(USER, PAPER, false);

        assertEquals(2, vo.getSeqNo(), "归档旧草稿后新建，seq_no 必须是 2");
        assertNotEquals(stale.getId(), vo.getAttemptId());
        verify(attemptMapper, times(1)).finalizeIfDraft(any());
        verify(recordMapper, times(1)).insert(any());   // 归档即判分：qid1 那道已答的题展开成 1 行（裁定三）
        verify(attemptMapper, times(1)).insert(any());
    }

    @Test
    @DisplayName("forceNew：未过期的草稿也归档为 3，然后新建")
    void forceNewArchivesEvenWhenActive() {
        QbExamAttempt draft = draft(1, "{\"1\":\"A\"}");
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER)).thenReturn(draft);
        when(attemptMapper.selectMaxSeqNo(USER, PAPER)).thenReturn(1);
        when(attemptMapper.selectById(draft.getId())).thenReturn(terminal(draft, 3));

        ExamStartVO vo = service.start(USER, PAPER, true);

        assertEquals(2, vo.getSeqNo());
        verify(attemptMapper, times(1)).finalizeIfDraft(any());
    }

    @Test
    @DisplayName("撞唯一键（并发双击）：重读草稿并返回它，不报"服务器繁忙"（裁定二）")
    void duplicateKeyReturnsTheOtherRequestDraft() {
        QbExamAttempt other = draft(1, null);
        when(attemptMapper.selectDraftByUserAndPaper(USER, PAPER))
                .thenReturn(null)      // 第一次查：没有
                .thenReturn(other);    // 撞键后重读：别人建好了
        when(attemptMapper.selectMaxSeqNo(USER, PAPER)).thenReturn(0);
        doThrow(new DuplicateKeyException("uk_attempt_user_paper_seq")).when(attemptMapper).insert(any());

        ExamStartVO vo = service.start(USER, PAPER, false);

        assertEquals(other.getId(), vo.getAttemptId(), "第二次点击必须拿到第一次建的那次，而不是报错");
    }

    @Test
    @DisplayName("下架卷 40900、不存在 40400、他人私有卷 40300、paperId null 40000——四道门来自 PaperLoad")
    void guardsAreSharedWithPaperDetail() {
        when(paperMapper.selectById(999L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.start(USER, 999L, false)).errorCode());

        QbPaper disabled = ExamFixtures.paperP1();
        disabled.setStatus(0);
        when(paperMapper.selectById(11L)).thenReturn(disabled);
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.start(USER, 11L, false)).errorCode());

        QbPaper foreign = ExamFixtures.paperP1();
        foreign.setOwnerId(999L);
        when(paperMapper.selectById(12L)).thenReturn(foreign);
        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> service.start(USER, 12L, false)).errorCode());

        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.start(USER, null, false)).errorCode());
        verify(attemptMapper, never()).insert(any());
    }

    @Test
    @DisplayName("卷面结构坏了（人手改过 structure_json）：40000 且不建 attempt")
    void corruptStructureRejectsBeforeInsert() {
        QbPaper broken = ExamFixtures.paperP1();
        broken.setStructureJson("{\"parts\":[]}");
        when(paperMapper.selectById(13L)).thenReturn(broken);
        when(paperMapper.selectById(PAPER)).thenReturn(broken);

        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.start(USER, PAPER, false)).errorCode());
        verify(attemptMapper, never()).insert(any());
    }
}
```

**这个测试类的三条约定（写实现前先看，否则会照着错误的构造器签名写）**：

1. **本段不引 `Clock`**。服务层在一次请求里取**一次** `now = System.currentTimeMillis()` 并把它往下传（判过期、算 `deadline_at`、算 `duration_used_ms` 共用同一个 `now`），过期用例靠把 `deadline_at` 直接设到过去来触发（`expiredDraftIsArchivedThenNewOneCreated` 就是这么做的）。留一个没人用的 `Clock` 抽象是负担。
2. `ExamServiceImpl` 的构造器是 **6 参**：`(paperMapper, attemptMapper, recordMapper, briefs, finalizer, props)`。`questionMapper`/`optionMapper`/`wrongMapper` 只用来组装真实的 `ExamBriefs` 与 `ExamFinalizer`，不是 service 的依赖。
3. `start` 会走 `PaperStructureValidator.validate(...)`，而校验需要判分原料（规则 2/4 要题目的 type 与答案），所以 `reusesActiveDraft` **不能**断言 `verifyNoInteractions(optionMapper)`——它对 qid 1/6 一定发一条批量查询。这条用例的反证只留"零 insert、零 finalizeIfDraft"。

- [ ] **Step 2：跑一次确认红**

```bash
"$MVN" -o -pl PracticeModule -am test -Dtest=ExamServiceTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

期望编译失败：`找不到符号 ExamService` / `ExamServiceImpl` / `ExamStartVO`。

- [ ] **Step 3：写请求体与响应体**

```java
package org.leon.practicemodule.pojo;

import lombok.Data;

/** `POST /practice/exam/start` 的 body。只有两个字段，别加第三个"以后可能用"。 */
@Data
public class ExamStartReq {
    private Long paperId;
    /** true = 放弃当前草稿（归档并判分）另起一次；缺省 false = 续做 */
    private Boolean forceNew;
}
```

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 开考响应（spec 5.6）。三件事一起给，前端就不需要再打第二个请求：
 * ① 倒计时原料（serverNow / deadlineAt / remainingMs，只信服务端）；
 * ② 卷面骨架（答题卡网格与 Part 标题）；
 * ③ 已存草稿（刷新后接着写）。
 *
 * <p>{@code answeredMap} 的 key 是**十进制题号字符串**（裁定五）：JSON 对象没有数字 key，
 * 在这里把它原样交给前端比"转成 Long 再指望 Jackson 写出数字 key"诚实。
 */
@Data
public class ExamStartVO {

    private Long attemptId;
    private Integer seqNo;
    private Long paperId;
    private String title;

    /** epoch 毫秒 */
    private long serverNow;
    private long deadlineAt;
    private long remainingMs;
    private Integer durationMinutes;

    private Integer questionCount;
    private Integer answeredCount;
    private double progressPercent;
    private BigDecimal totalScore;

    private List<PaperDetailVO.PartVO> structure;
    private Map<String, String> answeredMap;
}
```

- [ ] **Step 4：写 `ExamService` 接口与 `ExamServiceImpl` 的 `start`**

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.ExamStartVO;

/** 段 5 的六个用例。写路径三条（start/draft/submit）、读路径三条（question/attempt 两页）。 */
public interface ExamService {

    /**
     * 开考或续考。复用草稿的唯一判据是"该用户该卷有 status=1 的行且没过期"，
     * 不是"客户端带了 attemptId"——所以刷新页面天然回到那一次。
     */
    ExamStartVO start(Long userId, Long paperId, Boolean forceNew);
}
```

`ExamServiceImpl`（本任务只写 `start` 与它的私有辅助，`question`/`draft`/`submit` 由 Task 5.7–5.9 逐个扩）：

```java
package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.config.PracticeProperties;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbExamAttemptMapper;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.vo.ExamStartVO;
import org.leon.practicemodule.service.ExamAnswers;
import org.leon.practicemodule.service.ExamBriefs;
import org.leon.practicemodule.service.ExamDeadline;
import org.leon.practicemodule.service.ExamFinalizer;
import org.leon.practicemodule.service.ExamResults;
import org.leon.practicemodule.service.ExamService;
import org.leon.practicemodule.service.PaperLoad;
import org.leon.practicemodule.service.PaperSkeleton;
import org.leon.practicemodule.service.PaperStructure;
import org.leon.practicemodule.service.PaperStructureParser;
import org.leon.practicemodule.service.PaperStructureValidator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 套卷考试的状态机（spec 4.4）。
 *
 * <p>一次请求里只取**一次** `now`（`long now = System.currentTimeMillis()`）并往下传：
 * 判过期、算 deadline_at、算 duration_used_ms 若各取一次，就会出现在"边界那一毫秒"上
 * 三个结论互相矛盾的结果（判过期说没过期、used 却为负）。这一条不值得为它引一个 Clock bean。
 */
@Service
public class ExamServiceImpl implements ExamService {

    /** 终态：2 已提交、3 超时或放弃自动归档（spec 4.4） */
    private static final int STATUS_SUBMITTED = 2;
    private static final int STATUS_ARCHIVED = 3;

    private final QbPaperMapper paperMapper;
    private final QbExamAttemptMapper attemptMapper;
    private final QbUserAnswerRecordMapper recordMapper;
    private final ExamBriefs briefs;
    private final ExamFinalizer finalizer;
    private final PracticeProperties props;

    public ExamServiceImpl(QbPaperMapper paperMapper,
                           QbExamAttemptMapper attemptMapper,
                           QbUserAnswerRecordMapper recordMapper,
                           ExamBriefs briefs, ExamFinalizer finalizer, PracticeProperties props) {
        this.paperMapper = paperMapper;
        this.attemptMapper = attemptMapper;
        this.recordMapper = recordMapper;
        this.briefs = briefs;
        this.finalizer = finalizer;
        this.props = props;
    }

    @Override
    @Transactional
    public ExamStartVO start(Long userId, Long paperId, Boolean forceNew) {
        QbPaper paper = PaperLoad.loadVisible(paperMapper, userId, paperId);
        PaperStructure structure = PaperStructureParser.parse(paper.getStructureJson());
        // 裁定四：exam/start 是 validateStructure 的第二个调用者（第一个是段 4 的 db-it）。
        // 也就是说"卷面在开考时仍然自洽"这件事在这里才真的对用户生效——题目被删/被下架、
        // 分值和题数被手改过，都该在点进考试的那一刻报 40000/40900，而不是交卷时算出错乱成绩。
        PaperStructureValidator.validate(structure, paper.getQuestionCount(),
                paper.getTotalScore(), paper.getDurationMinutes(), paper.getSubjectId(),
                validationBriefs(structure));

        long now = System.currentTimeMillis();
        QbExamAttempt draft = attemptMapper.selectDraftByUserAndPaper(userId, paperId);

        if (draft != null && (Boolean.TRUE.equals(forceNew)
                || ExamDeadline.expired(draft.getDeadlineAt(), now, graceSeconds()))) {
            // 裁定三：归档即判分，走与 submit 完全相同的那个出口
            finalizer.finalizeAttempt(draft, structure, paper, STATUS_ARCHIVED, now);
            draft = null;
        }
        if (draft != null) {
            return toStartVO(paper, structure, draft, now);
        }

        QbExamAttempt created = createAttempt(userId, paper, now);
        return toStartVO(paper, structure, created, now);
    }

    /** 新建一次草稿。撞唯一键（并发双击）时重读别人建好的那条返回（裁定二） */
    private QbExamAttempt createAttempt(Long userId, QbPaper paper, long now) {
        int seqNo = attemptMapper.selectMaxSeqNo(userId, paper.getId()) + 1;
        QbExamAttempt attempt = new QbExamAttempt();
        attempt.setUserId(userId);
        attempt.setPaperId(paper.getId());
        attempt.setSubjectId(paper.getSubjectId());
        attempt.setSeqNo(seqNo);
        attempt.setStatus(1);
        attempt.setStartedAt(new Date(now));
        attempt.setDeadlineAt(new Date(now + paper.getDurationMinutes() * 60_000L));
        try {
            attemptMapper.insert(attempt);
        } catch (DuplicateKeyException e) {
            QbExamAttempt raced = attemptMapper.selectDraftByUserAndPaper(userId, paper.getId());
            if (raced == null) {
                // 对方回滚了：这一刻确实没有可用草稿，别再退回去新建（会二次撞键）
                throw new PracticeException(PracticeErrorCode.CONFLICT, "该试卷正在被打开，请重试");
            }
            return raced;
        }
        return attempt;
    }

    /**
     * 响应的唯一装配点。**草稿的 originalText 恒 null**（裁定二：submitCount>0 才给原文，
     * 而"正在考、还没交"的那次作答不是 submitCount——所以这里传 false 就对了）。
     */
    private ExamStartVO toStartVO(QbPaper paper, PaperStructure structure,
                                  QbExamAttempt attempt, long now) {
        Map<String, String> answeredMap = new LinkedHashMap<>();
        for (Map.Entry<Long, String> e : ExamAnswers.decode(attempt.getAnsweredJson()).entrySet()) {
            answeredMap.put(String.valueOf(e.getKey()), e.getValue());
        }
        int questionCount = paper.getQuestionCount() == null ? 0 : paper.getQuestionCount();

        ExamStartVO vo = new ExamStartVO();
        vo.setAttemptId(attempt.getId());
        vo.setSeqNo(attempt.getSeqNo());
        vo.setPaperId(paper.getId());
        vo.setTitle(paper.getTitle());
        vo.setServerNow(now);
        vo.setDeadlineAt(attempt.getDeadlineAt().getTime());
        vo.setRemainingMs(Math.max(0L, attempt.getDeadlineAt().getTime() - now));
        vo.setDurationMinutes(paper.getDurationMinutes());
        vo.setQuestionCount(questionCount);
        vo.setAnsweredCount(answeredMap.size());
        vo.setProgressPercent(ExamResults.progressPercentOf(answeredMap.size(), questionCount));
        vo.setTotalScore(paper.getTotalScore());
        vo.setStructure(PaperSkeleton.toPartVOs(structure, false));
        vo.setAnsweredMap(Map.copyOf(answeredMap));
        return vo;
    }

    /**
     * 卷面自洽校验要的只是 `(id, subjectId, status)`，但后面判分还要整卷原料，
     * 所以这里复用 `ExamBriefs.loadQuestions` 那一条批量查询，而不是 service 自己再发一条
     * `selectBatchIds`（同一个 mapper、同一次请求、同一批 id——那就是本段要防的重复 SQL 形态）。
     * 空卷面由 `loadQuestions` 挡在前面，一条 SQL 都不发。
     */
    private Map<Long, PaperStructureValidator.QuestionBrief> validationBriefs(PaperStructure structure) {
        Map<Long, PaperStructureValidator.QuestionBrief> out = new LinkedHashMap<>();
        for (QbQuestion q : briefs.loadQuestions(structure).values()) {
            out.put(q.getId(), new PaperStructureValidator.QuestionBrief(
                    q.getId(), q.getSubjectId(), q.getStatus()));
        }
        return Map.copyOf(out);
    }

    private long graceSeconds() {
        return props.getExam().getGraceSeconds();
    }
}
```

**`start` 的 SQL 条数与错误码分界（这段的判据，别在实现时改掉）**：一次 `start` 只有 `selectDraftByUserAndPaper`（+ 新建时 `selectMaxSeqNo` 与 `insert`）和 `loadQuestions` 那**一条** `selectBatchIds`；`toStartVO` 走 `PaperSkeleton.toPartVOs(structure, false)`，不需要判分原料，所以**不发选项键那条 SQL**（那条只在 `ExamFinalizer`/`submit` 的 `briefs.load` 里发）。错误码同理分界：**卷面里的题不存在或已下架，在 `start` 是 `40000`**（`PaperStructureValidator` 规则 2 就是 `BAD_REQUEST`，它先跑），**在 `submit`/归档才是 `40400`/`40900`**（`briefs.load(structure, true)` 抛）。两条路径给不同码不是不一致，而是"开考时卷面被人改坏 = 请求不合法"与"交卷时题没了 = 状态冲突"的语义差别；Task 5.6 的 `corruptStructureRejectsBeforeInsert` 与 Task 5.9 的 `submitErrorCodes` 各自钉住一边。

- [ ] **Step 5：跑 service 测试确认绿**

期望 `Tests run: 7, Failures: 0`（`reusesActiveDraft` 与 `duplicateKeyReturnsTheOtherRequestDraft` 是本任务的牙，它们红就是分支写错了）。

- [ ] **Step 6：写控制器与它的失败测试**

```java
package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.ExamStartReq;
import org.leon.practicemodule.pojo.vo.ExamStartVO;
import org.leon.practicemodule.service.ExamService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 考试接口。与 `paper/*` 同一套链路：`/practice/**` 已被网关路由且不在白名单，
 * `X-User-Name` 由网关从 JWT 注入，前端不传也传不了（直连 8102 缺这个头是 40100）。
 */
@RestController
@RequestMapping("/practice/exam")
public class PracticeExamController {

    private final ExamService examService;
    private final UserIdentityService userIdentityService;

    public PracticeExamController(ExamService examService, UserIdentityService userIdentityService) {
        this.examService = examService;
        this.userIdentityService = userIdentityService;
    }

    /** 开考/续考。`forceNew` 缺省即"续做"，前端只有"重新开始"按钮会显式传 true。 */
    @PostMapping("/start")
    public Result<ExamStartVO> start(@RequestHeader("X-User-Name") String userName,
                                     @RequestBody(required = false) ExamStartReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        ExamStartReq body = req == null ? new ExamStartReq() : req;
        return Result.success(examService.start(userId, body.getPaperId(), body.getForceNew()));
    }
}
```

`@RequestBody(required = false)` 是刻意的：空 body 的 `POST /practice/exam/start` 应当落到服务层的 `paperId == null → 40000`，而不是被 Spring 的 `HttpMessageNotReadableException` 抢先——虽然两者最终都是 40000（handler 已收 `HttpMessageNotReadableException`），但**判据要落在我们自己那条**，测试才测得到服务层的门。控制器测试：

```java
    @Test
    @DisplayName("start：body 只有 paperId 与 forceNew，userId 由 X-User-Name 换")
    void startPassesThrough() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        ExamStartVO vo = new ExamStartVO();
        vo.setAttemptId(900L);
        when(examService.start(10L, 8L, true)).thenReturn(vo);
        ExamStartReq req = new ExamStartReq();
        req.setPaperId(8L);
        req.setForceNew(true);

        Result<ExamStartVO> r = controller.start("alice", req);

        assertEquals(1, r.getCode());
        assertEquals(900L, r.getData().getAttemptId());
        verify(examService).start(10L, 8L, true);
    }

    @Test
    @DisplayName("空 body：paperId 原样给 null，交给 Service 报 40000（控制器不兜）")
    void emptyBodyGoesToService() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(examService.start(10L, null, null)).thenThrow(
                new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法"));
        assertThrows(PracticeException.class, () -> controller.start("alice", null));
        verify(examService).start(10L, null, null);
    }
```

- [ ] **Step 7：跑控制器测试 + 全量**

```bash
"$MVN" -o -pl PracticeModule -am test -Dtest=PracticeExamControllerTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
"$MVN" -o -pl PracticeModule -am test
```

期望 `Tests run: 2`、全量 **247**（238 + `ExamServiceTest` 7 + 控制器 2，按实跑报）。

---

## Task 5.7：`exam/question`（脱敏单题 + 守卫清单 + 伪阳性）

**Files:**
- Modify: `ExamService` / `ExamServiceImpl` / `PracticeExamController`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/pojo/vo/ExamQuestionVO.java`
- Test: `ExamServiceTest`（追加）、`PracticeExamControllerTest`（追加）、`AnswerStateResponseGuardTest`（追加 + 伪阳性）

**Interfaces:**
- Consumes: `QbExamAttemptMapper.selectById`、`PracticeMasker.toQuestionVO`、`QbQuestionOptionMapper.selectDisplayOptions`、`ExamAnswers.decode`、`PaperStructure`（取 `index`/`score`）
- Produces:
  - `ExamService.question(Long userId, Long attemptId, Long questionId) : ExamQuestionVO`
  - `ExamQuestionVO {attemptId, index, questionId, score, question(QuestionVO), userAnswerJson}`
  - `GET /practice/exam/question?attemptId=&questionId=`

- [ ] **Step 1：追加失败测试**

```java
    @Test
    @DisplayName("取题：脱敏单题 + 卷面 index 与 score + 这道题的草稿答案")
    void questionIsMaskedAndCarriesScore() {
        QbExamAttempt draft = draft(1, "{\"1\":\"A\"}");
        when(attemptMapper.selectById(500L)).thenReturn(draft);

        ExamQuestionVO vo = service.question(USER, 500L, 1L);

        assertEquals(1, vo.getIndex());
        assertEquals(0, new BigDecimal("7.00").compareTo(vo.getScore()));
        assertEquals("A 对", vo.getQuestion().getTypeName(), "题型名由 PracticeMasker 给（既有口径）");
        assertEquals("\"A\"", vo.getUserAnswerJson(), "草稿答案回给用户自己的数据，允许外泄");
        assertNull(vo.getQuestion().getOptions().get(0).getIsCorrect() == null ? null : "x",
                "遮罩后的选项不该带 isCorrect 这个字段本身");
    }

    @Test
    @DisplayName("越权：B 用户拿 A 的 attemptId → 40300，且不查题目")
    void otherUsersAttemptIsForbidden() {
        QbExamAttempt mine = draft(1, null);
        mine.setUserId(999L);
        when(attemptMapper.selectById(501L)).thenReturn(mine);

        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> service.question(USER, 501L, 1L)).errorCode());
        verifyNoInteractions(questionMapper);
    }

    @Test
    @DisplayName("题不在卷面上 → 40000（不是 40400：题存在，但不属于这张卷子）")
    void questionNotOnPaper() {
        QbExamAttempt draft = draft(1, null);
        when(attemptMapper.selectById(500L)).thenReturn(draft);
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.question(USER, 500L, 99L)).errorCode());
    }

    @Test
    @DisplayName("已提交的 attempt 不能再取题 → 41002")
    void terminalAttemptRejectsQuestion() {
        QbExamAttempt done = draft(1, null);
        done.setStatus(2);
        when(attemptMapper.selectById(502L)).thenReturn(done);
        assertEquals(PracticeErrorCode.EXAM_SUBMITTED, assertThrows(PracticeException.class,
                () -> service.question(USER, 502L, 1L)).errorCode());
    }

    @Test
    @DisplayName("过期但仍是草稿：读题不拒（到点只禁写草稿，读无副作用），但 remainingMs=0")
    void expiredDraftCanStillRead() {
        QbExamAttempt stale = draft(1, null);
        stale.setDeadlineAt(new Date(System.currentTimeMillis() - 10_000L));
        when(attemptMapper.selectById(503L)).thenReturn(stale);
        ExamQuestionVO vo = service.question(USER, 503L, 1L);
        assertEquals(1, vo.getIndex(), "过期了也该能翻题目看已存的草稿，否则用户连自己要交什么都看不见");
    }

    @Test
    @DisplayName("attempt 不存在 → 40400；questionId 缺失 → 40000")
    void notFoundAndBadParam() {
        when(attemptMapper.selectById(777L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.question(USER, 777L, 1L)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.question(USER, 500L, null)).errorCode());
    }
```

**第三条断言里那句 `assertNull(...getIsCorrect() == null ? null : "x")` 是我写坏的一条**：`QuestionVO.OptionVO` 本来就没有 `isCorrect` 字段（段 2 定死的类型），这个表达式编译不过。**正确做法是删掉这一行**——"选项不带真值"这件事由 Step 4 的守卫（真字节）与段 2 的 `PracticeMaskerTest` 双重覆盖，在 service 测试里再写一遍是空转。

- [ ] **Step 2：跑一次确认红**

期望编译失败：`找不到符号 question(...)` / `ExamQuestionVO`。

- [ ] **Step 3：实现 `question`**

```java
    /** 成绩单之外的读题路径。不加 @Transactional：一条 SELECT 都不写。 */
    @Override
    public ExamQuestionVO question(Long userId, Long attemptId, Long questionId) {
        if (attemptId == null || questionId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbExamAttempt attempt = loadOwnAttempt(userId, attemptId);
        if (attempt.getStatus() == null || attempt.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.EXAM_SUBMITTED, "该次作答已提交");
        }
        QbPaper paper = PaperLoad.loadVisible(paperMapper, userId, attempt.getPaperId());
        PaperStructure structure = PaperStructureParser.parse(paper.getStructureJson());
        PaperStructure.QuestionRef ref = findRef(structure, questionId);   // 不在卷面上 → 40000

        QbQuestion question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在");
        }
        if (question.getStatus() == null || question.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架");
        }
        List<QbQuestionOption> options =
                question.getType() != null && (question.getType() == 1 || question.getType() == 2)
                        ? optionMapper.selectDisplayOptions(questionId)   // 既有查询就不选 is_correct
                        : List.of();

        QuestionVO masked = masker.toQuestionVO(question, options);
        masked.setIndex(ref.index());
        masked.setScore(ref.score());      // QuestionVO.score 预留字段，段 3 恒 null，这里第一次填上

        ExamQuestionVO vo = new ExamQuestionVO();
        vo.setAttemptId(attempt.getId());
        vo.setQuestionId(questionId);
        vo.setIndex(ref.index());
        vo.setScore(ref.score());
        vo.setQuestion(masked);
        vo.setUserAnswerJson(ExamAnswers.decode(attempt.getAnsweredJson()).get(questionId));
        return vo;
    }

    /** attempt 归属判据：不存在 40400，不是本人 40300（spec 5.7 的两码必须在同一处分支上分开） */
    private QbExamAttempt loadOwnAttempt(Long userId, Long attemptId) {
        QbExamAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "作答不存在");
        }
        if (!userId.equals(attempt.getUserId())) {
            throw new PracticeException(PracticeErrorCode.FORBIDDEN, "无权操作该作答");
        }
        return attempt;
    }

    private static PaperStructure.QuestionRef findRef(PaperStructure structure, Long questionId) {
        for (PaperStructure.QuestionRef ref : structure.questionsInOrder()) {
            if (ref.questionId().equals(questionId)) {
                return ref;
            }
        }
        throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "该题不在这张卷子里");
    }
```

**本任务把 `ExamServiceImpl` 的构造器从 6 参加到 9 参**：追加 `QbQuestionMapper questionMapper`、`QbQuestionOptionMapper optionMapper`、`PracticeMasker masker`（`@Component`，段 2 已有，`toQuestionVO(QbQuestion, List<QbQuestionOption>)` 是既有的唯一脱敏出口）。**同一次改动里必须把三处装配点一起换掉**，否则编译不过：`ExamServiceTest.setUp()`（`questionMapper`/`optionMapper` 两个 mock 字段本来就有，只是之前只喂给 `ExamBriefs`，现在也传给 service；再加 `mock(PracticeMasker.class)` 不如用**真实的** `new PracticeMasker()`——脱敏口径是要被测的，mock 会把守卫测成空转）、Task 5.9 的 `ExamSubmitTest`、以及 `AnswerStateResponseGuardTest` 里新增的那条装配。

**注意这里取题用的是"一次单题查询"而不是 `ExamBriefs`**：`exam/question` 一次只要一道题，走批量取整卷原料（四条 SQL 换一道题）是浪费。两条路径各查各的，但**都不选真值列**——`selectDisplayOptions` 是段 3 已有的、不选 `is_correct` 的那条查询。

- [ ] **Step 4：控制器 + 守卫用例 + **伪阳性（缺这段等于没做）****

控制器追加：

```java
    /** 逐题取题：只给脱敏正文 + 卷面序号与分值。终态 attempt 报 41002。 */
    @GetMapping("/question")
    public Result<ExamQuestionVO> question(@RequestHeader("X-User-Name") String userName,
                                           @RequestParam Long attemptId,
                                           @RequestParam Long questionId) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(examService.question(userId, attemptId, questionId));
    }
```

`AnswerStateResponseGuardTest` 追加（真字节；装配沿用该类写法：真控制器 + 真服务 + mock mapper）。

**装配这一处要动三行，别漏**：`wireRealServicesOverMockMappers()` 里已有 `paperMapper`/`attemptMapper`/`questionMapper`/`optionMapper`/`wrongMapper`/`recordMapper` 六个 mock，本任务在此基础上新建真实协作对象并挂进 `standaloneSetup`：

```java
        ExamBriefs examBriefs = new ExamBriefs(questionMapper, optionMapper);
        ExamService examService = new ExamServiceImpl(paperMapper, attemptMapper, recordMapper,
                questionMapper, optionMapper, examBriefs,
                new ExamFinalizer(attemptMapper, recordMapper, wrongMapper, examBriefs, new AnswerGrader()),
                new PracticeProperties(), new PracticeMasker());
        mockMvc = MockMvcBuilders
                .standaloneSetup(/* 既有四个控制器 */,
                        new PracticeExamController(examService, identity))
                .setControllerAdvice(new PracticeExceptionHandler())
                .build();
```

`new PracticeMasker()` 而不是 mock：脱敏口径本身就是被测对象，mock 掉它这条守卫就是空转（该类连注释都写了"不注入任何 bean"）。`ExamFixtures` 是 `public`（Task 5.4 已定），本类在 `controller` 包，靠 import 用。

```java
    @Test
    @DisplayName("exam/question 的响应体干净：选项不带 isCorrect，题干不带答案与解析")
    void examQuestionResponseIsClean() throws Exception {
        // 用带真值的实体喂它：答案与解析一旦漏到响应里，值哨兵就会现形
        when(paperMapper.selectById(8L)).thenReturn(ExamFixtures.paperP1());
        when(attemptMapper.selectById(500L)).thenReturn(ExamFixtures.draftAttempt(10L, 8L, "{\"1\":\"A\"}"));
        when(questionMapper.selectById(1L)).thenReturn(rawQuestion(1L));   // 类内既有：带 ANSWER_SENTINEL
        when(optionMapper.selectDisplayOptions(1L)).thenReturn(List.of(
                option("A", "选项甲", 1), option("B", "选项乙", 0)));

        String json = body(get("/practice/exam/question?attemptId=500&questionId=1"));

        assertNoAnswerFields(json, "\"选项甲\"");
        assertTrue(json.contains("\"index\":1"), "卷面序号没进响应：" + json);
        assertTrue(json.contains("\"score\":7.00") || json.contains("\"score\":7.0"),
                "这道题在卷面上的分值必须给前端（答题页要显示 7 分）：" + json);
        assertTrue(json.contains("\"userAnswerJson\""), "自己的草稿答案必须回显：" + json);
    }
```

**伪阳性（必须做，两次）**：
1. 临时把 `question()` 里的 `masked` 换成直接把 `QbQuestion` 实体塞进 `ExamQuestionVO.question`（改类型不行就先加一个 `private String analysis` 到 `QuestionVO` 并 `masked.setAnalysis(ANSWER_SENTINEL)`）→ 守卫必须红并打印 `响应体泄漏了 analysis`；
2. 临时给 `QuestionVO.OptionVO` 加回 `private Integer isCorrect;` 并在 `PracticeMasker.toOptions` 里 `ov.setIsCorrect(option.getIsCorrect())` → 守卫必须红并打印 `响应体泄漏了 isCorrect`。

两次都把完整报文记进执行记录，然后回滚，`grep -c isCorrect` 在 `QuestionVO.java` 必须为 0，再跑全量。

- [ ] **Step 5：全量绿 + 报告改动**

期望 `AnswerStateResponseGuardTest` 从 7 条到 **8** 条，全量 **254**（按实跑报）。

---

## Task 5.8：`exam/draft`（整包覆盖 + 终态 41002 + 超时 41001）

**Files:**
- Modify: `ExamService` / `ExamServiceImpl` / `PracticeExamController`
- Create: `pojo/ExamDraftReq.java`、`pojo/vo/ExamDraftVO.java`
- Test: `ExamServiceTest`（追加 5 条）、`PracticeExamControllerTest`（追加 2 条）

**Interfaces:**
- Produces:
  - `ExamService.draft(Long userId, Long attemptId, Map<String,String> answers) : ExamDraftVO`
  - `ExamDraftVO {savedAt, answeredCount, progressPercent}`
  - `POST /practice/exam/draft`（body `{attemptId, answers}`）

- [ ] **Step 1：追加失败测试**

```java
    @Test
    @DisplayName("存草稿：整包覆盖 answered_json，返回已答数与进度")
    void draftOverwritesWholeMap() {
        QbExamAttempt draft = draft(1, "{\"1\":\"A\"}");
        when(attemptMapper.selectById(500L)).thenReturn(draft);
        when(attemptMapper.updateById(any())).thenReturn(1);

        ExamDraftVO vo = service.draft(USER, 500L, Map.of("2", "[\"A\",\"B\"]", "5", "\"我的简答\""));

        assertEquals(2, vo.getAnsweredCount(), "整包覆盖：旧的 qid1 不在了，新 map 有 2 个 key");
        assertEquals(50.0, vo.getProgressPercent(), 1e-9);
        ArgumentCaptor<QbExamAttempt> cap = ArgumentCaptor.forClass(QbExamAttempt.class);
        verify(attemptMapper).updateById(cap.capture());
        assertEquals(500L, cap.getValue().getId());
        Map<Long, String> saved = ExamAnswers.decode(cap.getValue().getAnsweredJson());
        assertEquals(2, saved.size());
        assertEquals("[\"A\",\"B\"]", saved.get(2L));
        assertNull(saved.get(1L), "整包覆盖的语义就是"这次没带的等于删掉"");
    }

    @Test
    @DisplayName("卷面之外的题号 → 40000，且不写库（草稿里不许长出这张卷子没有的答案）")
    void draftRejectsQuestionsOffPaper() {
        QbExamAttempt draft = draft(1, null);
        when(attemptMapper.selectById(500L)).thenReturn(draft);
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.draft(USER, 500L, Map.of("99", "\"A\""))).errorCode());
        verify(attemptMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("非法 value（尾随内容 / Infinity / 空串）→ 40000，且不写库")
    void draftRejectsMysqlUnsafeValues() {
        QbExamAttempt draft = draft(1, null);
        when(attemptMapper.selectById(500L)).thenReturn(draft);
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.draft(USER, 500L, Map.of("1", "\"A\" \"B\""))).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.draft(USER, 500L, Map.of("1", "1e999"))).errorCode());
        verify(attemptMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("已提交 → 41002；越权 → 40300；不存在 → 40400")
    void draftErrorCodes() {
        QbExamAttempt submitted = draft(1, null);
        submitted.setStatus(2);
        when(attemptMapper.selectById(502L)).thenReturn(submitted);
        assertEquals(PracticeErrorCode.EXAM_SUBMITTED, assertThrows(PracticeException.class,
                () -> service.draft(USER, 502L, Map.of("1", "\"A\""))).errorCode());

        QbExamAttempt foreign = draft(1, null);
        foreign.setUserId(999L);
        when(attemptMapper.selectById(504L)).thenReturn(foreign);
        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> service.draft(USER, 504L, Map.of("1", "\"A\""))).errorCode());

        when(attemptMapper.selectById(777L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.draft(USER, 777L, Map.of("1", "\"A\""))).errorCode());
        verify(attemptMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("超时 → 41001，且**不写** answered_json（§6.4：draft 拒绝，submit 才收）")
    void expiredDraftIsRejected() {
        QbExamAttempt stale = draft(1, null);
        stale.setDeadlineAt(new Date(System.currentTimeMillis() - 120_000L));
        when(attemptMapper.selectById(503L)).thenReturn(stale);

        assertEquals(PracticeErrorCode.EXAM_EXPIRED, assertThrows(PracticeException.class,
                () -> service.draft(USER, 503L, Map.of("1", "\"A\""))).errorCode());
        verify(attemptMapper, never()).updateById(any());
    }
```

- [ ] **Step 2：跑一次确认红**（`找不到符号 draft`）

- [ ] **Step 3：实现 `draft`**

```java
    @Override
    @Transactional
    public ExamDraftVO draft(Long userId, Long attemptId, Map<String, String> answers) {
        if (attemptId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbExamAttempt attempt = loadOwnAttempt(userId, attemptId);
        if (attempt.getStatus() == null || attempt.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.EXAM_SUBMITTED, "该次作答已提交");
        }
        long now = System.currentTimeMillis();
        if (ExamDeadline.expired(attempt.getDeadlineAt(), now, graceSeconds())) {
            // 拒绝写草稿但**不归档**：§6.4 说得很清楚，到点之后只有 submit 能收这次作答。
            // 在这里顺手归档会把用户"看一眼屏幕发现超时"变成"被判了分"，那是两种产品事实。
            throw new PracticeException(PracticeErrorCode.EXAM_EXPIRED, "考试已结束，请交卷");
        }
        Map<Long, String> normalized = toLongKeyed(answers);     // 题号字符串 → Long，非法 40000
        requireKeysOnPaper(normalized.keySet(), attempt.getPaperId());
        String encoded = ExamAnswers.encode(normalized);          // value 严格校验（裁定五）

        QbExamAttempt patch = new QbExamAttempt();
        patch.setId(attempt.getId());
        patch.setAnsweredJson(encoded);
        attemptMapper.updateById(patch);

        int questionCount = questionCountOf(attempt.getPaperId());
        ExamDraftVO vo = new ExamDraftVO();
        vo.setSavedAt(now);
        vo.setAnsweredCount(normalized.size());
        vo.setProgressPercent(ExamResults.progressPercentOf(normalized.size(), questionCount));
        return vo;
    }
```

**两处执行时按实测定的细节**：
1. `attemptMapper.updateById(patch)` 走 MyBatis-Plus 的非空字段更新，因此只会写 `answered_json` 一列——`status`/`deadline_at` 不在 patch 里就不会被动。**这条要在 db-it 里验**（Task 5.9 Step 3 追加一条"存草稿不碰 deadline_at"），因为它是框架行为不是我们的 SQL。
2. `questionCountOf(paperId)` 会再查一次 `qb_paper`（`paperMapper.selectById`）。同一请求里 `requireKeysOnPaper` 也要结构——**两个 helper 共用一次查询**：实现时把它们合并成一次 `loadVisible` + `parse`，然后两处传引用。别留两条相同的 SELECT（自查第 4 条会抓它）。

- [ ] **Step 4：请求体、控制器与测试**

```java
@Data
public class ExamDraftReq {
    private Long attemptId;
    /** {"<questionId 的十进制字符串>": "<整份 JSON 答案>"}，与 answered_json 同形态（裁定五） */
    private Map<String, String> answers;
}
```

```java
    /** 存草稿：整包覆盖。前端每 30 秒自动存一次 + "保存草稿"按钮，都打这里。 */
    @PostMapping("/draft")
    public Result<ExamDraftVO> draft(@RequestHeader("X-User-Name") String userName,
                                     @RequestBody(required = false) ExamDraftReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        ExamDraftReq body = req == null ? new ExamDraftReq() : req;
        return Result.success(examService.draft(userId, body.getAttemptId(), body.getAnswers()));
    }
```

`answers` 为 `null` 时的口径：**当作空 map**（整包覆盖 → 清空草稿），因为"我删光了所有答案再保存"与"传空 map"是同一件事；`ExamServiceTest` 追加一条 `nullBecomesEmptyDraft` 钉住它（断言 `answeredCount=0` 且 `updateById` 被调用）。

- [ ] **Step 5：全量绿 + 报告改动**

期望 **263** 左右（`ExamServiceTest` +6、控制器 +2，按实跑报，**数字对不上就逐类核对来源，不许改断言凑数**）。

---

## Task 5.9：`exam/submit` + `exam/attempt/page` + `exam/attempt/detail`（成绩单与历次作答）

**Files:**
- Modify: `ExamService` / `ExamServiceImpl` / `PracticeExamController`
- Create: `pojo/ExamSubmitReq.java`、`pojo/vo/ExamAttemptItemVO.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/ExamSubmitTest.java`（新类，与 `ExamServiceTest` 分开：本任务要断的是**写库次数与统计不变**，夹具与 mock 清单不同）
- Test: `ExamServiceTest`（追加 `attemptPage` / `attemptDetail` 用例）、`PracticeExamControllerTest`（追加 3 条）
- Test: `ExamFlowDbIT`（追加真库全链路 3 条）

**Interfaces:**
- Consumes: `ExamFinalizer.finalize`、`ExamResults.toVO`、`PaperLoad`、`PracticePages.of`、`QbExamAttemptMapper.countForPaper/selectAttemptPage`
- Produces:
  - `ExamService.submit(Long userId, Long attemptId) : ExamResultVO`
  - `ExamService.attemptPage(Long userId, Long paperId, Long pageNum, Long pageSize) : PageVO<ExamAttemptItemVO>`
  - `ExamService.attemptDetail(Long userId, Long attemptId) : ExamResultVO`
  - `POST /practice/exam/submit`（body `{attemptId}`）、`GET /practice/exam/attempt/page`、`GET /practice/exam/attempt/detail`

- [ ] **Step 1：写交卷的失败测试（本段最要紧的一份）**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.leon.practicemodule.mapper.*;
import org.leon.practicemodule.pojo.QbExamAttempt;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;
import org.leon.practicemodule.pojo.vo.ExamResultVO;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 交卷的四条不变式，每条都用**调用次数**而不是返回值来断：
 * ① 展开的行数 = 已答题数（未答不落行，事实 1）；
 * ② `qb_user_subject_stat` 一次都不碰（spec 7.1）；
 * ③ 二次提交零写入（幂等闸门 + 不重复展开）；
 * ④ 待自评的简答落 `is_correct=-1`（裁定一）。
 */
class ExamSubmitTest {

    private static final long USER = 10L;

    private QbPaperMapper paperMapper;
    private QbExamAttemptMapper attemptMapper;
    private QbUserAnswerRecordMapper recordMapper;
    private QbUserWrongMapper wrongMapper;
    private QbQuestionMapper questionMapper;
    private QbQuestionOptionMapper optionMapper;
    private QbUserSubjectStatMapper statMapper;
    private ExamServiceImpl service;

    @BeforeEach
    void setUp() {
        paperMapper = mock(QbPaperMapper.class);
        attemptMapper = mock(QbExamAttemptMapper.class);
        recordMapper = mock(QbUserAnswerRecordMapper.class);
        wrongMapper = mock(QbUserWrongMapper.class);
        questionMapper = mock(QbQuestionMapper.class);
        optionMapper = mock(QbQuestionOptionMapper.class);
        statMapper = mock(QbUserSubjectStatMapper.class);
        ExamBriefs briefs = new ExamBriefs(questionMapper, optionMapper);
        // Task 5.7 之后的 9 参构造器（questionMapper/optionMapper/masker 是 exam/question 带进来的）。
        // statMapper 不是任何类的依赖，它 mock 出来只为了 verifyNoInteractions：
        // 将来有人把统计 mapper 接进交卷路径，这条会红。
        service = new ExamServiceImpl(paperMapper, attemptMapper, recordMapper,
                questionMapper, optionMapper, briefs,
                new ExamFinalizer(attemptMapper, recordMapper, wrongMapper, briefs, new AnswerGrader()),
                new PracticeProperties(), new PracticeMasker());        when(paperMapper.selectById(8L)).thenReturn(ExamFixtures.paperP1());
        stubQuestions();
    }

    private void stubQuestions() {
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.question(6L, 1, "\"C\""),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]"), ExamFixtures.question(5L, 5, "\"参考答案\"")));
        when(optionMapper.selectOptionKeysByQuestions(any())).thenReturn(List.of(
                new QuestionOptionKeyRow(1L, "A"), new QuestionOptionKeyRow(1L, "B"),
                new QuestionOptionKeyRow(6L, "A"), new QuestionOptionKeyRow(6L, "C")));
    }

    /**
     * `finalizeIfDraft` 之后 `selectById` 该读到的那一行。
     * 必须另造一个对象：直接改传进来的 `draft` 会让"闸门写入的快照"和"内存里的草稿"变成同一个东西，
     * 测试就再也分不清成绩单到底是读库来的还是就地算的。
     */
    private static QbExamAttempt submitted(QbExamAttempt draft) {
        QbExamAttempt done = ExamFixtures.draftAttempt(draft.getUserId(), draft.getPaperId(), null);
        done.setStatus(2);
        done.setSubmitAt(new Date());
        done.setDurationUsedMs(60_000);
        done.setScore(new BigDecimal("17.00"));
        done.setCorrectCount(2);
        done.setWrongCount(0);
        done.setUnansweredCount(1);
        return done;
    }

    @Test
    @DisplayName("交卷：3 行展开（未答的 qid6 不落行）、错题本联动、统计一次不碰")
    void submitExpandsOnlyAnswered() {
        QbExamAttempt draft = ExamFixtures.draftAttempt(USER, 8L,
                "{\"1\":\"A\",\"2\":\"[\\\"A\\\",\\\"B\\\"]\",\"5\":\"\\\"我的简答\\\"\"}");
        when(attemptMapper.selectById(500L)).thenReturn(draft, submitted(draft));   // 先读草稿，归档后重读终态
        when(attemptMapper.finalizeIfDraft(any())).thenReturn(1);

        ExamResultVO vo = service.submit(USER, 500L);

        ArgumentCaptor<QbUserAnswerRecord> cap = ArgumentCaptor.forClass(QbUserAnswerRecord.class);
        verify(recordMapper, times(3)).insert(cap.capture());
        List<QbUserAnswerRecord> rows = cap.getAllValues();
        assertEquals(1, attemptIdOf(rows), "三行都挂同一个 attempt_id（distinct 只该有 1 个）");
        assertTrue(rows.stream().allMatch(r -> r.getAnswerMode() == 2), "answer_mode=2（考试）");
        assertEquals(1, rows.stream().filter(r -> r.getIsCorrect() == -1).count(),
                "裁定一：qid5 简答落 -1 待自评");
        assertEquals(0, rows.stream().filter(r -> r.getQuestionId() == 6L).count(),
                "未答的题不落行：user_answer_json 是 json NOT NULL");
        assertNotNull(rows.get(0).getDurationMs(),
                "均摊用时必须落库——expandRecords 若把 draft 而不是 terminal 传进去，这里静默变 null");

        verify(wrongMapper, times(2)).masterIfUnmastered(eq(USER), any());
        // spec 7.1：考试作答绝不进科目统计
        verifyNoInteractions(statMapper);
        verify(attemptMapper, times(1)).finalizeIfDraft(any());

        assertEquals(3, vo.getCorrectCount() + vo.getWrongCount() + vo.getPendingSelfJudgeCount());
        assertEquals(1, vo.getUnansweredCount(), "qid6 未答：四个计数加起来必须等于卷面题数");
        assertEquals(4, vo.getQuestions().size(), "成绩单要铺满整卷，未答的也在");
        // qid1 对(7.00) + qid2 多选对(10.00) + qid5 待自评(0) = 17.00，qid6 未答(0)
        assertEquals(0, new BigDecimal("17.00").compareTo(vo.getScore()));
    }

    @Test
    @DisplayName("连点两次提交：闸门回 0 行 → 零 insert、零错题本写入，成绩单从库里重建")
    void secondSubmitWritesNothing() {
        QbExamAttempt draft = ExamFixtures.draftAttempt(USER, 8L, "{\"1\":\"A\"}");
        QbExamAttempt submitted = submitted(draft);
        submitted.setScore(new BigDecimal("7.00"));
        submitted.setCorrectCount(1);
        submitted.setUnansweredCount(3);
        // 第一次读到草稿（所以走闸门），第二次读到终态（闸门回 0 之后的重读）
        when(attemptMapper.selectById(500L)).thenReturn(draft, submitted);
        when(attemptMapper.finalizeIfDraft(any())).thenReturn(0);      // 闸门关上：别人先交了
        when(recordMapper.selectByAttempt(USER, 500L)).thenReturn(List.of(
                new AttemptRecordRow(1L, "\"A\"", 1, 20_000)));

        ExamResultVO vo = service.submit(USER, 500L);

        verify(recordMapper, never()).insert(any());
        verifyNoInteractions(wrongMapper);
        verifyNoInteractions(statMapper);
        assertEquals(2, vo.getStatus());
        assertEquals(1, vo.getCorrectCount(), "重建出来的计数来自库里的行，不是重新判分");
        assertEquals("\"A\"", vo.getQuestions().get(0).getUserAnswerJson());
        assertEquals(4, vo.getQuestions().size());
    }

    @Test
    @DisplayName("越权 / 不存在 / 卷面下架题：40300 / 40400 / 40900，且零写入")
    void submitErrorCodes() {
        QbExamAttempt foreign = ExamFixtures.draftAttempt(999L, 8L, null);
        when(attemptMapper.selectById(501L)).thenReturn(foreign);
        assertEquals(PracticeErrorCode.FORBIDDEN, assertThrows(PracticeException.class,
                () -> service.submit(USER, 501L)).errorCode());

        when(attemptMapper.selectById(777L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.submit(USER, 777L)).errorCode());

        QbPaper withDisabled = ExamFixtures.paperP1();
        when(paperMapper.selectById(8L)).thenReturn(withDisabled);
        when(attemptMapper.selectById(502L)).thenReturn(ExamFixtures.draftAttempt(USER, 8L, "{\"1\":\"A\"}"));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
                ExamFixtures.question(1L, 1, "\"A\""), ExamFixtures.disabled(6L, 1),
                ExamFixtures.question(2L, 2, "[\"A\",\"B\"]"), ExamFixtures.question(5L, 5, "\"x\"")));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.submit(USER, 502L)).errorCode());
        verify(attemptMapper, never()).finalizeIfDraft(any());
    }

    @Test
    @DisplayName("过期交卷：终态是 3 不是 2，且仍给成绩（§6.4）")
    void expiredSubmitArchivesAsThree() {
        QbExamAttempt stale = ExamFixtures.draftAttempt(USER, 8L, "{\"1\":\"A\"}");
        stale.setStartedAt(new Date(System.currentTimeMillis() - 70 * 60_000L));
        stale.setDeadlineAt(new Date(System.currentTimeMillis() - 120_000L));
        when(attemptMapper.selectById(503L)).thenReturn(stale);
        when(attemptMapper.finalizeIfDraft(any())).thenReturn(1);

        service.submit(USER, 503L);

        ArgumentCaptor<QbExamAttempt> cap = ArgumentCaptor.forClass(QbExamAttempt.class);
        verify(attemptMapper).finalizeIfDraft(cap.capture());
        assertEquals(3, cap.getValue().getStatus(), "超时提交仍判分，但状态是 3");
    }

    private static long attemptIdOf(List<QbUserAnswerRecord> rows) {
        return rows.stream().map(QbUserAnswerRecord::getAttemptId).distinct().count();
    }
}
```

**这三条是这个测试类的判据，实现时不要动摇**：
1. `statMapper` 不是任何类的依赖，它 mock 出来只为被 `verifyNoInteractions` 证明"没被调用"。真正的防线还有两层：`ExamFlowDbIT.examRecordsLeaveSubjectStatUntouched`（真库计数不变）与端到端的前后快照（Task 5.10）。**如果实现里 `ExamFinalizer` 或 `ExamServiceImpl` 出现了 `QbUserSubjectStatMapper`，那就是把 spec 7.1 写坏了**，这三层会同时红。
2. `submitExpandsOnlyAnswered` 的 `score=17.00` 是算出来的：qid1 单选对 7.00 + qid2 多选对 10.00 + qid5 简答待自评 0 + qid6 未答 0。四个计数 `2+0+1+1=4` 必须等于卷面题数——这条等式是裁定一存在的理由（少了 `pendingSelfJudgeCount`，用户一眼就能看出成绩单对不上）。
3. **`submit`/归档走 `briefs.load(structure, true)`，成绩单重建走 `load(structure, false)`**。这不是可选风格：`PaperStructureValidator` 的规则 2 只查"存在 + 属于本科目 + `status==1`"，但它**只在 `start` 调用**；交卷时必须自己守住"题还在、还启用"，否则 `answer_json` 取不到就静默判 0 分。而重建路径（`attempt/detail`、闸门关上后的 `submit`）必须**容忍题目已下架**——已交卷的成绩单不能因为运营下架一道题就看不见。同一个方法、一条 boolean、两种时机，`ExamBriefsTest.enabledGateDiffersByCaller` 钉住两边。

- [ ] **Step 2：跑一次确认红**（`找不到符号 submit`）

- [ ] **Step 3：实现 `submit`**

```java
    @Override
    @Transactional
    public ExamResultVO submit(Long userId, Long attemptId) {
        if (attemptId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbExamAttempt attempt = loadOwnAttempt(userId, attemptId);
        QbPaper paper = PaperLoad.loadVisible(paperMapper, userId, attempt.getPaperId());
        PaperStructure structure = PaperStructureParser.parse(paper.getStructureJson());

        if (attempt.getStatus() != null && attempt.getStatus() != 1) {
            // 已经是终态：不再走闸门，直接重建（这条分支的存在让"双击"第二次几乎零成本）
            return rebuildResult(attempt, paper, structure);
        }
        long now = System.currentTimeMillis();
        int terminal = ExamDeadline.expired(attempt.getDeadlineAt(), now, graceSeconds())
                ? STATUS_ARCHIVED : STATUS_SUBMITTED;      // §6.4：超时提交仍判分，状态给 3
        if (attempt.getStartedAt() == null) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "作答数据异常，请重新进入考试");
        }
        ExamFinalizer.Outcome outcome = finalizer.finalizeAttempt(attempt, structure, paper, terminal, now);
        return ExamResults.toVO(outcome.attempt(), paper, outcome.grade());
    }

    /** 终态成绩单的重建路径：attempt/detail 与"闸门已关上"的 submit 共用（裁定四） */
    private ExamResultVO rebuildResult(QbExamAttempt attempt, QbPaper paper, PaperStructure structure) {
        Map<Long, ExamGrading.Brief> brief = briefs.load(structure, false);
        List<AttemptRecordRow> rows = recordMapper.selectByAttempt(attempt.getUserId(), attempt.getId());
        return ExamResults.toVO(attempt, paper, ExamGrading.fromStored(structure, rows, brief));
    }
```

- [ ] **Step 4：`attempt/page` 与 `attempt/detail`**

```java
    @Override
    public PageVO<ExamAttemptItemVO> attemptPage(Long userId, Long paperId,
                                                Long pageNum, Long pageSize) {
        if (paperId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        PracticePages.PageParam page = PracticePages.of(pageNum, pageSize);
        QbPaper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "试卷不存在");
        }
        long total = attemptMapper.countForPaper(userId, paperId);
        if (total == 0L) {
            return PageVO.of(List.of(), 0L, page.pageNum(), page.pageSize());
        }
        List<AttemptPageRow> rows = attemptMapper.selectAttemptPage(userId, paperId,
                page.offset(), page.pageSize());
        int questionCount = paper.getQuestionCount() == null ? 0 : paper.getQuestionCount();
        List<ExamAttemptItemVO> list = new ArrayList<>(rows.size());
        for (AttemptPageRow row : rows) {
            ExamAttemptItemVO vo = new ExamAttemptItemVO();
            vo.setAttemptId(row.attemptId());
            vo.setSeqNo(row.seqNo());
            vo.setStatus(row.status());
            vo.setScore(row.score());
            vo.setSubmitAt(row.submitAt() == null ? null : row.submitAt().getTime());
            vo.setDurationUsedMs(row.durationUsedMs());
            vo.setCorrectCount(row.correctCount());
            vo.setWrongCount(row.wrongCount());
            vo.setUnansweredCount(row.unansweredCount());
            // 待自评数不存列（qb_exam_attempt 只有 17 列，段 4 实测），用减法：
            // 三个快照计数都是可空的，草稿态全 NULL，所以先判空再减，不给 0 假装"没有待自评"。
            if (row.correctCount() != null || row.wrongCount() != null || row.unansweredCount() != null) {
                vo.setPendingSelfJudgeCount(questionCount - nz(row.correctCount())
                        - nz(row.wrongCount()) - nz(row.unansweredCount()));
            }
            list.add(vo);
        }
        return PageVO.of(list, total, page.pageNum(), page.pageSize());
    }

    @Override
    public ExamResultVO attemptDetail(Long userId, Long attemptId) {
        if (attemptId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbExamAttempt attempt = loadOwnAttempt(userId, attemptId);
        if (attempt.getStatus() == null || attempt.getStatus() == 1) {
            throw new PracticeException(PracticeErrorCode.EXAM_SUBMITTED, "该次作答尚未提交");
        }
        QbPaper paper = paperMapper.selectById(attempt.getPaperId());
        if (paper == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "试卷不存在");
        }
        return rebuildResult(attempt, paper, PaperStructureParser.parse(paper.getStructureJson()));
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
```

**`attemptPage` 的两条口径决定（都要有测试）**：① 它**不过 `PaperLoad` 的 status/owner 四道门**，因为"看我自己的历次作答"与"这张卷子还在不在架"是两件事——下架卷的历史成绩必须还能看（否则用户交完卷下架就丢成绩），所以这里只查 `paper == null → 40400`；② `questionCount` 减法得到的 `pendingSelfJudgeCount` 在**草稿行为 NULL**（`correctCount()` 等为 null 时不给值），因为草稿没有快照。

- [ ] **Step 5：控制器三条 + 测试**

```java
    /** 交卷：幂等（连点两次只判一次分）。成绩单同时是 attempt/detail 的内容。 */
    @PostMapping("/submit")
    public Result<ExamResultVO> submit(@RequestHeader("X-User-Name") String userName,
                                      @RequestBody(required = false) ExamSubmitReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        ExamSubmitReq body = req == null ? new ExamSubmitReq() : req;
        return Result.success(examService.submit(userId, body.getAttemptId()));
    }

    /** 某张卷子的历次作答（卡片上"已提交次数"点进去的页） */
    @GetMapping("/attempt/page")
    public Result<PageVO<ExamAttemptItemVO>> attemptPage(@RequestHeader("X-User-Name") String userName,
                                                         @RequestParam Long paperId,
                                                         @RequestParam(required = false) Long pageNum,
                                                         @RequestParam(required = false) Long pageSize) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(examService.attemptPage(userId, paperId, pageNum, pageSize));
    }

    /** 成绩单详情：刷新不丢解析（与 submit 同一个 ExamResultVO） */
    @GetMapping("/attempt/detail")
    public Result<ExamResultVO> attemptDetail(@RequestHeader("X-User-Name") String userName,
                                              @RequestParam Long attemptId) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(examService.attemptDetail(userId, attemptId));
    }
```

`ExamServiceTest` 追加四条（`attemptPage` 的减法、草稿行不给 pending、`attemptDetail` 的 41002、零 N+1）：

```java
    @Test
    @DisplayName("attempt/page：pendingSelfJudgeCount 用减法；草稿行的三个快照为 NULL 就不给值")
    void attemptPagePendingBySubtraction() {
        when(attemptMapper.countForPaper(USER, 8L)).thenReturn(2L);
        when(attemptMapper.selectAttemptPage(eq(USER), eq(8L), anyLong(), anyLong())).thenReturn(List.of(
                new AttemptPageRow(500L, 2, 2, new BigDecimal("17.00"), new Date(), 60_000, 2, 0, 1),
                new AttemptPageRow(499L, 1, 1, null, null, null, null, null, null)));

        PageVO<ExamAttemptItemVO> vo = service.attemptPage(USER, 8L, 1L, 20L);

        assertEquals(1, vo.getList().get(0).getPendingSelfJudgeCount(),
                "卷面 4 题 = 2 对 + 0 错 + 1 未答 → 减法得 1 个待自评");
        assertNull(vo.getList().get(1).getPendingSelfJudgeCount(), "草稿没有成绩快照，减法无意义");
        verify(attemptMapper, times(1)).countForPaper(eq(USER), eq(8L));
        verify(attemptMapper, times(1)).selectAttemptPage(eq(USER), eq(8L), anyLong(), anyLong());
    }
```

- [ ] **Step 6：db-it 追加真库全链路三条**

```java
    @Test
    @DisplayName("真库全链路：start 建草稿 → draft 存两题 → submit 展开两行 + 快照 + answered_json 置 NULL")
    void fullFlowOnRealData() throws SQLException {
        long paperId = seededP1Id();
        long attemptId = insertAttempt(paperId, 1, 1, null);   // 裸写：模拟刚 start 完
        flushCache();

        // 存草稿：走真服务的 map 编码，再按 UPDATE 落库（同 session 裸写 → 必须 clearCache）
        String answered = ExamAnswers.encode(Map.of(1L, "\"A\"", 5L, "\"我的简答\""));
        try (PreparedStatement ps = session.getConnection().prepareStatement(
                "UPDATE qb_exam_attempt SET answered_json = ? WHERE id = ?")) {
            ps.setString(1, answered);
            ps.setLong(2, attemptId);
            assertEquals(1, ps.executeUpdate());
        }
        flushCache();

        QbExamAttempt draft = attemptMapper.selectById(attemptId);
        assertEquals(2, ExamAnswers.countOf(draft.getAnsweredJson()));

        QbExamAttempt row = new QbExamAttempt();
        row.setId(attemptId);
        row.setUserId(TEST_USER);
        row.setStatus(2);
        row.setSubmitAt(new Date());
        row.setDurationUsedMs(60_000);
        row.setScore(new BigDecimal("7.00"));
        row.setCorrectCount(1);
        row.setWrongCount(0);
        row.setUnansweredCount(2);
        assertEquals(1, attemptMapper.finalizeIfDraft(row));
        flushCache();

        assertNull(attemptMapper.selectById(attemptId).getAnsweredJson(),
                "交卷后 map 与展开行不能同时是真相（段 4 已立的不变式，这里在段 5 的真实写入上复验）");

        int inserted = insertExamRecords(attemptId, List.of(1L, 5L));
        flushCache();
        List<AttemptRecordRow> rows = recordMapper.selectByAttempt(TEST_USER, attemptId);
        assertEquals(inserted, rows.size(), "写进行数与读回行数必须相等（selectByAttempt 就是按 attempt_id 查的）");
        assertEquals(1, rows.stream().filter(r -> r.isCorrect() == -1).count(),
                "简答在真库里就是 -1");
    }
```

再补两条：

```java
    @Test
    @DisplayName("展开出的考试记录行不改科目统计（spec 7.1 在真库上成立）")
    void examRecordsLeaveSubjectStatUntouched() throws SQLException { /* 查 qb_user_subject_stat 前后 COUNT 与 total_count 不变 */ }

    @Test
    @DisplayName("考试记录会出现在练习侧的"最近一次作答"里（事实 13 的活体记录）")
    void examRecordIsTheLatestAnswerForPracticeSide() throws SQLException { /* selectLatestByQuestions 回的是 mode=2 那行；AnswerStatus.selfJudgeable=false */ }
```

第二条不是新功能而是一条**已知副作用的取证**：练习侧的答题卡会把考试的作答当"上次作答"显示，且因为 `selfJudgeable` 要 `mode IN (1,3)`，`pendingRecordId` 为 null。写进测试是为了让段 5 之后如果有人改 `selectLatestByQuestions` 加 mode 过滤，这条会红并逼他重新读裁定一。

- [ ] **Step 7：全量绿 + 报告改动**

```bash
"$MVN" -o -pl PracticeModule -am test
"$MVN" -o -pl PracticeModule -am test -Dtest='ExamFlowDbIT+PaperSqlDbIT+AnswerFlowDbIT+QbQuestionMapperDbIT+QuestionReadSqlDbIT' -Dsurefire.excludedGroups= -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

期望全量单测约 **275**、五个 db-it 一起跑约 **33**（26 + `ExamFlowDbIT` 6，按实跑报，对不上就逐类核对来源）。跑完 `Seg5Residue`：`qb_paper=4`、`user_id=990005` 两张表 0、`qb_exam_attempt` 只剩端到端账号的行（此时应仍为 0，因为 db-it 用合成用户）。

---

## Task 5.10：端到端取证（六个接口经网关 + 段 4/段 3 回归）+ 文档回写 + 记忆同步

**全程走网关 8100，不留直连 8102 的口子**（直连只用于一条负例）。响应一律落 `seg5/resp-*.json`，屏幕只打 ASCII。

- [ ] **Step 1：重启 8102（口诀见段 4 的 Task 4.7）**

```bash
netstat -ano | grep -a ":8102" | grep -a LISTENING          # 先看现在的持有者是谁
"$MVN" -o -DskipTests install
nohup "$MVN" -o -pl PracticeModule spring-boot:run > "$TMP/practice-seg5.log" 2>&1 &
# 判据三条（用 UTF-8 读，别 iconv -f GBK）：
python -c "print([l for l in open('practice-seg5.log',encoding='utf-8',errors='replace') if 'Tomcat started' in l or 'Started PracticeModuleApplication' in l or 'already in use' in l])"
netstat -ano | grep -a ":8102" | grep -a LISTENING          # 监听 PID 必须 == 日志里的 with PID
```

- [ ] **Step 2：端到端矩阵（`seg5/e2e5.py`）**

正向（新接口 + 回归），每条都要落盘并打 ASCII 摘要：

| # | 调用 | 期望 |
|---|---|---|
| 01 | `subject/list` | `code=1` len=2（段 3 回归） |
| 02 | `paper/page?subjectId=1` | `code=1` total=2（段 4 回归） |
| 03 | `POST exam/start {paperId:P1}` | `code=1`，`seqNo=1`、`deadlineAt-serverNow == 65*60_000`、`structure` 2 parts、`answeredMap` 空、`remainingMs>0` |
| 04 | `POST exam/start` **再来一次（不带 forceNew）** | **同一个 `attemptId`**、`seqNo=1`（9.3 的"刷新重进拿同一 attemptId"，本段最硬的一条） |
| 05 | `GET exam/question?attemptId=&questionId=<卷面首题>` | `code=1`、`index=1`、`score` 有值、**响应里没有 `isCorrect`/`answerJson`/`analysis`** |
| 06 | `POST exam/draft` 存两题（含 `qid5` 简答） | `code=1`、`answeredCount=2`、`progressPercent=50.0` |
| 07 | `POST exam/start`（不 forceNew） | `answeredCount=2`、`answeredMap` 里有那两题（草稿真的在库里） |
| 08 | `POST exam/draft` 带**卷面外**题号 | `code=40000`，且 `progressPercent` 不变（没写进去） |
| 09 | `POST exam/draft` 带非法 value（`1e999`） | `code=40000`（MySQL 严格度在活体上成立） |
| 10 | `POST exam/submit` | `code=1`、`status=2`、`correct+wrong+unanswered+pending == questionCount`、`score` 与 `fullScore` 都是两位小数形态、`questions` 长度 = 4、简答那题 `isCorrect` 是 `null` 且 `needSelfJudge=true` |
| 11 | `POST exam/submit` **连点第二次** | `code=1`、**成绩与第一次逐字相同**、`qb_user_answer_record` 行数不变（库里复核，见 Step 4） |
| 12 | `GET exam/attempt/detail?attemptId=` | 与 #10 的 `data` **完全相同**（"刷新不丢解析"的活体证据） |
| 13 | `GET exam/attempt/page?paperId=P1` | `code=1`、`total=1`、`pendingSelfJudgeCount=1`、`submitAt` 是 epoch 毫秒 |
| 14 | `GET paper/detail?paperId=P1`（交卷之后） | `code=1` 且 **`originalText` 非 null**（段 4 裁定二被段 5 的真实 attempt 点亮——这是两条裁定之间唯一的活体接线，必须有） |
| 15 | `paper/page?subjectId=1` | `submitCount=1`、`progressPercent=50.0`（卡片活起来） |
| 16 | `question/current?subjectId=1&mode=1` | `code=1`（练习侧回归，`attempt_id` 与 mode=2 的行不影响断点） |
| 17 | `wrong/page?subjectId=1` | `code=1`（考试错题进错题本，`subject_id` 来自 attempt 冗余列） |

负例：

| 调用 | 期望 |
|---|---|
| `POST exam/start` 缺 `paperId` | `40000` |
| `POST exam/start` 用不存在的 paperId | `40400` |
| `POST exam/start` 用 P4（禁用卷 id） | `40900` |
| `GET exam/question` 缺 `attemptId` | `40000`（绑定层） |
| `POST exam/submit` 用**他人的** attemptId | **无法端到端触发**（库里没有别的用户的 attempt），由 `ExamSubmitTest` 覆盖；在自查第 11 条明写这个缺口。**不许为了造它往库里插别人的行** |
| `GET exam/attempt/detail?attemptId=<草稿>` | `41002` |
| `POST exam/draft` 用已提交的 attemptId | `41002` |
| `POST exam/draft` 用**过期**草稿 attemptId | `41001`（做法：把 `qb_exam_attempt` 的 `deadline_at` 改到过去——**只改 test918 自己的行**，改完立刻在 Step 4 复核并留原值） |
| `GET exam/attempt/page` 缺 `paperId` | `40000` |
| `GET exam/attempt/page?paperId=999999` | `40400` |
| 任一 exam 接口不带 token | **HTTP 401**（网关） |
| 直连 `127.0.0.1:8102/practice/exam/attempt/page?paperId=8` 无 `X-User-Name` | `40100` |

超时那条要单独跑一次**过期后 submit**（#18）：`code=1`、`status=3`、仍给成绩（§6.4 + 裁定三）。跑完把 `deadline_at` 改回原值或删掉该行——**不许把过期状态的行留在库里影响后续段的起点**。

- [ ] **Step 3：字节扫描（`seg5/scan5.py`）**

清单照段 4，**一个字都不许加**；白名单在段 4 的三条之上加**本段的真值出口**：`exam/submit`、`exam/attempt/detail`（成绩单允许带 `correctAnswerJson`/`analysis`/`isCorrect`，spec 5.4）。另外三条专属断言：
- `resp-*-exam-question-*` 里必须**不含** `isCorrect`/`answerJson`/`analysis`，且必须含 `userAnswerJson` 与 `score`（值哨兵 `ANSWER_SENTINEL_GUARD` 不在其中）。
- `resp-*-attempt-detail` 里必须**含** `correctAnswerJson`（反向锚点：证明扫描没把成绩单误吞，也证明 #12 的"逐字相同"不是两个空响应）。
- `resp-*-paper-detail`（交卷后）里 `originalText` 至少有一节非 null（#14 的机器版本）。

- [ ] **Step 4：库里取证（`seg5/Seg5State.java`，只打 ASCII 数字）**

- `SELECT COUNT(*) FROM qb_paper` → **4**（样例卷没被动过）
- `SELECT SUM(status=1) FROM qb_paper` → 3
- `SELECT COUNT(*) FROM qb_exam_attempt` → 端到端留下的行数（写清楚，并给出按 `user_id=10` 的明细）
- `SELECT COUNT(*) FROM qb_user_answer_record WHERE answer_mode=2` → 已答题展开的行数（与 #10 的成绩单对得上）
- `SELECT COUNT(*) FROM qb_user_answer_record WHERE attempt_id IS NULL AND answer_mode=2` → **0**（`mode=2` 的行**必须**都挂 attempt，这是段 4 加那列的意义）
- `SELECT COUNT(*) FROM qb_user_answer_record WHERE is_correct=-1 AND answer_mode=2` → ≥1（简答待自评在真库里就是 -1）
- **`qb_user_subject_stat` 前后两次快照对比 → 完全不变**（spec 7.1 的活体证据，交一次卷 + 一题练习：只有练习那次让它变）
- `SELECT COUNT(*) FROM qb_exam_attempt WHERE user_id=990005` → 0（db-it 零残留）
- 连点两次 submit 后：`SELECT COUNT(*) FROM qb_user_answer_record WHERE attempt_id=<id>` 两次相同（幂等的库侧证据）

- [ ] **Step 5：文档回写（写成带日期的"更正"，不覆盖历史）**

- **spec §5.6**：`ExamResultVO` 补两个字段并明写口径——`pendingSelfJudgeCount`（裁定一：四个计数之和必须等于 `question_count`，原表少这一位）、`accuracy = correct/(correct+wrong)`（裁定六：未答与待自评都不进分母，分母 0 给 `0.0`）；`exam/question` 的 `data` 写清是 `{attemptId,index,questionId,score,question,userAnswerJson}`；`attempt/page` 的 `pendingSelfJudgeCount` 由减法算出（库里没这列，段 4 实测 17 列）且草稿行为 NULL。
- **spec §4.4**：状态机图旁补一句"归档即判分"（裁定三），并写清"3 的两条来源：超时提交与开考时放弃旧草稿，**两条都有分数快照**"。
- **spec §6.4**：补实测行为——`draft` 在过期时**只拒绝不归档**（归档发生在下一次 `start` 或 `submit`），并把这条判据指到 `ExamDeadline`。
- **spec §11**：新增两行接缝——"考试简答题本期无自评入口（裁定一）"、"考试记录会成为该题练习侧的最近一次作答且 `pendingRecordId=null`（事实 13，`ExamFlowDbIT` 有取证用例）"。
- **spec §12 / §9.3**：勾掉 9.3 里"刷新重进同一 attemptId"、"交卷后新增 `question_count` 行、`answered_json` 为 NULL、`submitCount` 变 2"（**实测按真实数字更正**：样例卷 P1 是 4 题，不是 `question_count` 那一列的 65；二次提交让 `submitCount` 变 2）、"连点两次提交成绩与行数不变"、"考试中 `originalText` 为 null"后半边（`attempt/detail` 里非 null 仍要等它自己的路径，实测由 `paper/detail` 交卷后非 null 覆盖，`attempt/detail` 不含原文——明写这件事）。
- **roadmap**：段 5 状态 → 已完成；§3 新增第 **21** 条（**四个计数之和 = `question_count` 是成绩单的唯一自洽判据，`is_correct=-1` 必须有一位独立计数**——否则 `correct+wrong+unanswered` 永远少 1，而用户一眼能看出来）与第 **22** 条（**"未答不落行"是 `user_answer_json` 是 `json NOT NULL` 逼出来的，不是设计偏好**：任何"给未答题写一行空答案"的想法在真库上是 1364）；运行环境表 PracticeModule 行更新到段 5 的实例。
- **段 4 计划的接缝表**：把"`qb_exam_attempt` 本段零写入 → 段 5"与"考试简答题的待自评 → 段 5（必须裁定）"两条标为已接住，写上裁定一与实测数字。

- [ ] **Step 6：记忆同步**

`practice-exam-initiative.md`（段 5 完成 + 裁定一~六 + 提交号 + 端到端留下的增量与清理 SQL + `40300`/`attempt/detail 原文`两处缺口）、`local-service-topology.md`（8102 新 PID）、`MEMORY.md` 索引行（17 接口全部可用）。

- [ ] **Step 7：提交并推送（逐条 pathspec）**

```bash
git status --short                       # 只应有 PracticeModule/** 与三个 docs 文件
git log --oneline origin/dev..dev        # 看清要推什么：本会话的提交之外不该有别人的
git add -- <逐个列出本次的 17~20 个文件>
git commit -m "段5：套卷考试状态机与成绩单（exam/* 六接口）…" -- PracticeModule docs
git push origin dev
```

提交语要点：幂等靠条件更新、归档即判分、考试简答落 -1、统计不受考试（7.1）。

---

## Task 5.11：主线自查（本轮不做独立审查）

用户规则（2026-10-24）：实现与审查都在主线，**不派子代理**。报告里要明确写"未做独立审查轮次"。

每条都要给文件:行号，不接受"我看过了"：

- [ ] **1. 契约一致**：六个接口的路径/入参/字段与 spec §5.6 对过；四处偏差（`ExamResultVO` 多 `pendingSelfJudgeCount`、`accuracy` 口径、`attempt/page` 的 pending 由减法、`exam/question` 回 `userAnswerJson`）都在 spec 里写成带日期更正。
- [ ] **2. 答题态零泄漏**：`exam/question` 的响应字节过守卫（含伪阳性两次实测的证据）；`ExamSubmitTest` 与守卫类里没有把 `exam/submit`/`attempt/detail` 当答题态扫（它们**允许**带真值，误扫会假阳性）。
- [ ] **3. 永不读真值列**：`QbQuestionOptionMapper` 新增的 `selectOptionKeysByQuestions` 只选 `question_id, option_key`；`grep -n is_correct mapper/QbQuestionOptionMapper.java` 仍只在注释里。
- [ ] **4. 零 N+1**：`ExamBriefs.load` 两条 SQL 在循环外，`ExamBriefsTest` 用 `times(1)` 钉住；`ExamFinalizer.expandRecords` 的逐题 `insert` 是**写入**不是查询，注释里已写明为什么不用多值 INSERT——自查时要确认这条理由仍然成立（"几十题、同事务、不引入拼接 SQL"），不要因为"看起来像 N+1"就改掉它。
- [ ] **5. 空 IN / 空集合**：`ExamBriefs.load` 先挡空 ids；`selectOptionKeysByQuestions` 只在 `singleChoice` 非空时发；`attemptPage` 的 `total=0` 短路。
- [ ] **6. 幂等**：`finalizeIfDraft` 是唯一的闸门，`ExamSubmitTest.secondSubmitWritesNothing` + `ExamFlowDbIT.finalizeIfDraftIsTheIdempotencyGate`（真库）+ 端到端 #11/#12 三层都在。
- [ ] **7. 统计口径**：`verifyNoInteractions(statMapper)` 与 `examRecordsLeaveSubjectStatUntouched`（真库）+ 端到端 Step 4 的前后快照，三层锁死 spec 7.1。
- [ ] **8. 状态机**：草稿→2、草稿→3（超时 submit）、草稿→3（start forceNew / 过期）、终态不可回退（条件更新回 0）四条转换都有测试；`answered_json` 在两条终态路径上都被置 NULL。
- [ ] **9. 测试数与绿灯**：贴全量 `Tests run:` 与五个 db-it 一起跑的那一行（基线 218 / 26，按实跑）。
- [ ] **10. 没越界 / 秘密**：`git status --short` 不含 `AIModule/**`、`CommonJwt/**`、`GatewayModule/**`、任何 `pom.xml`；新增行 `password|secret|123456|bearer|token=` 零命中；`grace-seconds: 60` 是唯一新增配置。
- [ ] **11. 未覆盖的缺口要明写**（不许省略）：`40300`（他人 attempt）只有单测无端到端（库里没有别人的 attempt，插一条就是往验收数据加行）；`attempt/detail` 不含听力原文（原文只在 `paper/detail` 交卷后可见，spec 那句话的"非 null"半边按此更正）；**考试简答题没有自评入口**（裁定一），成绩单里它永远是"待自评"；样例卷题量 4/2/3/1，65 格网格与"25 题压弯正确率"的真实压力无法用现有数据复现；`forceNew` 的归档判分会给一次"用户从没点过交卷"的作答打上成绩（裁定三的代价，已明写）。

---

## 与其他段的接缝（本段留下的、后续必须接住的口子）

| 接缝 | 归谁 | 具体待办 |
|---|---|---|
| 考试简答题的自评 | **用户裁定 / 将来产品** | 裁定一关掉了 mode=2 的自评入口。若要做：`self-judge` 要按 mode 分支决定"写不写科目统计"，那等于给段 2 的 `applyOutcome` 动手术，要单独一段并补回归。 |
| 考试记录影响练习侧"上次作答" | 已取证，不修 | `selectLatestByQuestions` 不带 mode 过滤（段 3 裁定二），交卷后练习页会把考试答案当"上次作答"。`ExamFlowDbIT` 里那条取证用例是这条行为的守门人。 |
| `pendingSelfJudgeCount` 由减法算 | 若将来加列需重新裁定 | 库里没有这一列（段 4 实测 17 列）。减法依赖"三个快照计数都可空"这一形态；若有人加 `pending_count` 列，要同时改 `attempt/page` 与成绩单，并重新过 9.3 的"四个计数之和 = question_count"。 |
| 超时懒判没有后台任务 | 段 5 已按 spec 实现 | 过期草稿在下一次 `start`/`submit` 才归档；**没人再点进来就一直躺在 status=1**。若要"到点自动交"，那是新的调度子系统（spec 明说本期不做），不要偷偷加 `@Scheduled`。 |
| `attempt/detail` 不含原文 | 前端 | 原文走 `paper/detail`（交卷后可见）。若产品要成绩单页也显示原文，那是 `ExamResultVO` 的契约追加，要另写守卫用例与测试。 |
| 端到端在 `test918` 名下留了真实数据 | 用户 | 清理 SQL 在 Task 5.10 Step 4 之后由执行者写出，**不代执行**；样例卷 4 行保留。 |

---

## 执行记录（执行时逐项填，命令与输出一起留）

**填写规则**：每项贴**真实命令输出**（可截断）。控制台有 GBK 风险，中文从落盘文件读。**红灯必须贴出真实报错**，证明确实先红后绿。

### Task 5.1（完成，2026-10-25）

**先红后绿**（红灯是真的"类不存在"，不是断言失败）：

```
[ERROR] /F:/.../PracticeModule/src/test/java/org/leon/practicemodule/service/ExamAnswersTest.java:[22,32] 找不到符号
  符号:   类 ExamAnswers
  位置: 类 org.leon.practicemodule.service.ExamAnswersTest
```

（javac 的中文提示经管道出来是 GBK 乱码，认行号与符号名即可——roadmap 里那条老坑又踩了一次，不影响判读。）

绿灯与回归：

```
[INFO] Tests run: 6,  Failures: 0  -- ExamAnswersTest        （Task 5.1 自己的）
[INFO] Tests run: 39, Failures: 0  -- AnswerGraderTest       （改了段 2 的类，专门回跑）
[INFO] Tests run: 224, Failures: 0, Errors: 0  -- 全量单测（基线 218 + 6）
[INFO] Tests run: 26,  Failures: 0            -- 四个 db-it 全跑（AnswerGrader 被动过，真库那条必须复核）
```

**计划里"三处执行时必须当场验的假设"的实测结果**：

1. **成立且已改用更简的写法**：`JsonNode.toString()` 与 `writeValueAsString(node)` 输出同形（都是带引号的 JSON 字面量），且前者不抛检查异常。`decode` 用 `toString()`，注释写明这条等价性。往返测试（`roundTrip`）钉住它。
2. **不成立——而且是一条真缺陷**：计划原本写 `MAPPER.reader().readValue(raw, JsonNode.class)`，实测它对尾随内容**不抛**（`"A" "B"` 静静收下），于是 `rejectsValuesMysqlWouldRefuse` 第一次跑红在 `ExamAnswersTest:70`：`Expected PracticeException to be thrown, but nothing was thrown`。段 2 的判据（`getFactory().createParser` + `readTree(parser)` + `parser.nextToken() != null` 就拒）才是能用的那一套。**计划里"补一条手工检查"的备选方案没采纳，而是直接复用了段 2 的实现——见下面的偏差。**
3. **成立**：`var it = root.fields()` 在本仓库 Jackson 版本上编译通过，`Map.Entry<String, JsonNode>` 显式声明也通过，不需要额外 `import java.util.Iterator`。

**对计划的三处偏差（都是计划错、不是实现将就）**：

- **偏差 1（改了段 2 的文件，计划 Files 块没列）**：`AnswerGrader.parse` → `static JsonNode parseSingleValue`（同包内可见），`private final ObjectMapper objectMapper` → `private static final ObjectMapper MAPPER`，类内 6 处调用点同步；`ExamAnswers` 直接调它。理由：计划原文写"两处分开、各写一遍严格校验"，它给的理由是"判分那份的契约含选项键集合"——那条理由对 `grade(...)` 成立，对 `parse(...)` 不成立，而 `parse` 才是这里要复用的东西。roadmap 第 16 条记着这个坑**发作过两次**，两份"MySQL 严格度"校验正是它描述的形态。`AnswerGraderTest` 39 条与 `AnswerFlowDbIT` 3 条断言一字未改就绿，行为没变。
- **偏差 2**：`decode` 用 `node.toString()` 而非 `writeValueAsString`（假设 1 的实测结果）。
- **偏差 3**：计划的 `roundTrip` 与 `rejectsValuesMysqlWouldRefuse` 里把简答题的 value 写成裸文本（`"我的简答原文"`）。那是错的——裸文本既不是合法 JSON，`parseSingleValue` 会拒（MySQL 也拒），这正是段 2 定死的口径（简答必须是 JSON 字符串字面量）。测试改为 `"\"我的简答原文\""`，断言强度不变（仍验往返）。

**基线更正**：计划事实 14 写的 218/26 中，218 是**本任务开始前**的数，跑完是 **224**。26 条 db-it 未变。

**提交**：`git add -- <5 个文件 + 本计划>` 显式 pathspec，`git commit`（本段每任务一提交一推送，用户 2026-10-25 裁定；共享工作树在 Task 5.1 首次尝试时被别人的回滚吞过一次未提交改动，见 memory）。

### Task 5.2（完成，2026-10-25）

先红（"类不存在"那种红）：

```
[ERROR] /F:/.../ExamDeadlineTest.java:[21,21] 找不到符号  符号: 类 ExamDeadline
```

**重构的判据兑现了**（Step 7 的三个类，断言一字未改）：

```
Tests run: 14, Failures: 0 -- PaperServiceTest               （原样绿）
Tests run: 7,  Failures: 0 -- AnswerStateResponseGuardTest    （真字节脱敏，原样绿）
Tests run: 6,  Failures: 0 -- PracticePaperControllerTest      （原样绿）
Tests run: 228, Failures: 0 -- 全量单测（224 + ExamDeadlineTest 4）
Tests run: 13, Failures: 0 -- PaperSqlDbIT 10 + AnswerFlowDbIT 3（真库跑过抽出来的 PaperLoad/PaperSkeleton）
```

`ExamDeadlineTest` 4 条里的边界三条按计划成立：`grace=60` 时 `now == D+60_000` **不算**过期（判据是 `>` 不是 `>=`），`D+60_001` 才算；`deadlineAt == null` 不算过期。

**对计划的两处偏差**：

- **偏差 1（计划自相矛盾，按 Interfaces 块定）**：`originalTextVisible` 落在 **`PaperSkeleton`**。计划 Task 5.2 的 Interfaces 块写 `PaperSkeleton.originalTextVisible`，而 Step 5 的代码清单里把它抄进了 `PaperLoad` ——两处不一致。按 Interfaces 块执行：它是"出网形态该不该带这一段原文"的判据，与 `toPartVOs` 的 `originalTextVisible` 参数是一对，放在一起调用方只需一个 import。`PaperLoad` 只有 `loadVisible` 与 `PUBLIC_OWNER`。Task 5.6 的 `toStartVO` 传 `false`（草稿态永远不给原文），不经过这个方法。
- **偏差 2**：`PaperDetailVO.PartVO/SectionVO/QuestionRefVO` **留在 `pojo/vo/PaperDetailVO` 里没搬**。计划原本想让 `PaperSkeleton` 拥有这三个类型，但段 4 的 `PracticePaperControllerTest:95-99` 与 `PaperServiceTest:273` 直接按 `PaperDetailVO.PartVO` 写死了——搬类型就得改那些引用，而 Step 7 的判据是"既有测试一个字不改"。所以 `PaperSkeleton.toPartVOs` 返回 `List<PaperDetailVO.PartVO>`，`ExamStartVO.structure` 也用同一个类型（这是刻意的复用：答题卡骨架在两个接口必须长一样，多一个类型就多一份会漂的形态）。

`PaperServiceImpl` 因此少了 2 个 import（异常类）、多了 2 个（`PaperLoad`/`PaperSkeleton`），类内 `PUBLIC_OWNER` 常量与私有 `toPartVOs` 删除。

### Task 5.3（完成，2026-10-25）

先红：`ExamGradingTest.java:[7,38] 找不到符号`（`AttemptRecordRow`）与 `[41,41] ExamGrading`，两类都是"类不存在"。

绿灯（一次就过，含两条真库用例）：

```
Tests run: 233, Failures: 0 -- 全量单测（228 + ExamGradingTest 5）
Tests run: 10, Failures: 0 -- QuestionReadSqlDbIT（9 + 本任务新增的批量选项键那条）
```

`allAnswered` / `sectionRollup` / `nothingAnswered` / `rebuildMatchesGrade` / `missingBriefIsNotFound` 五条一次全绿，`assemble` 里那行 `score = score.add(item.scoreGot())` 的守门断言成立。

**对计划的五处偏差**：

1. **`allAnswered` 的期望 `score` 从 30.00 改成 24.00**（与 Task 5.9 那条 23.00→17.00 同一类错，也是我算错）。裁定一说明简答题 `scoreGot=0`，所以"全对"的卷子拿不到那道简答的 6.00：`7.00+7.00+10.00=24.00`，而 `fullScore` 仍是 30.00——两者之差恰好就是待自评那题的分值，断言里把这个关系写进注释了。**这是本段第 2 处"计划里的算术与裁定不一致"，Task 5.4~5.9 写测试前先把分值表算一遍。**
2. **计划 Files 块漏了 `QbUserAnswerRecordMapper.java`**（Step 3/4 明明往它上面加 `selectByAttempt`）。实际改了。
3. **新增一条真库用例** `optionKeysComeBackInOneBatch`（计划没给这条 `<foreach> IN` 查询任何真库覆盖）。理由是段 3 立过的纪律：动态 SQL 的形态问题（空 IN、别名、漏括号）mock 结构上看不见，只有真库抓得到。钉三件事：qid 1 回 `A,B,C,D`、只回请求的那两道题、投影列不为 null。
4. **`ORDER BY question_id, sort_order, id`**（计划少 `, id`）。`selectDisplayOptions` 就是 `sort_order, id`——段 3 实测过同科目内 `sort_order` 会重复，选项表同理不能只按 `sort_order` 排，否则字母顺序不确定，判分虽然不受影响（用集合判断）但 `optionKeys` 的测试断言会不稳定。
5. **`selectByAttempt` 不套 `<script>`**（计划的清单里套了）。这条没有动态片段，包一层只会让下一个人以为里面有。另外测试里的 `org.leon.practicemodule.exception.PracticeErrorCode` 全限定名改成了 import。

**基线更新**：单测 **233**，db-it **27**（`QuestionReadSqlDbIT` 10、`PaperSqlDbIT` 10、`QbQuestionMapperDbIT` 4、`AnswerFlowDbIT` 3）。

### Task 5.4（完成，2026-10-25）

红灯（用"把两个新类临时移出编译路径"逼出来的真实报错，之后原样移回）：

```
[ERROR] /F:/.../ExamResultsTest.java:[6,39] 找不到符号   （import ...pojo.vo.ExamResultVO）
[INFO] BUILD FAILURE
```

绿灯：`Tests run: 236, Failures: 0`（233 + `ExamResultsTest` 3），与计划预估一致。

**对计划的偏差**：

1. **又一处 `score` 算术错**：计划写 `assertEquals(30.00, vo.getScore())`，但 `allRight()` 含那道简答题，裁定一下它 `scoreGot=0`，所以正确值是 **24.00**（`fullScore` 才是 30.00）。这是同一段代码里第 3 次出现"计划把待自评题算进得分"（5.3 的 30.00 与这里执行时抓到；5.9 的 23.00 在计划自查时发现并已改 17.00）。**规律：只要卷面含 `type=5`，`score` 恒等于 `fullScore - 简答题分值`**——成绩单里 `score` 与 `fullScore` 之差就是"待自评吃掉的分数"，测试注释这样写了，端到端也按这条核对。
2. `ExamResults` **只有 `toVO` + `progressPercentOf` + `millis`**，计划正文没写第二装配函数，草稿态响应另有 `ExamDraftVO`（Task 5.8），不共用本类——所以"装配出口唯一"这条承诺的范围是**成绩单**，不含草稿进度。
3. 夹具类的方法名从计划的 `singleBlankStructure()` 改成 **`oneQuestionStructure()`**：原名读起来像"一道填空题"，实际是"只含一道题的最小卷面"（配合"该题不是单选"的桩来验第二条 SQL 不发）。Task 5.5 的 `ExamBriefsTest` 按新名调用。
4. `ExamFixtures` 实测需要 **`public`**（`AnswerStateResponseGuardTest`/`PracticeExamControllerTest` 在 `controller` 包），计划初稿写成包私有；同时补了 `question(long,int,String)`／`disabled(long,int)`／`paperP1()`／`draftAttempt(...)` 四个工厂，`PAPER_ID`/`ATTEMPT_ID` 两个常量，都是 Task 5.5~5.9 的用例要先用它的形状。
5. `attempt()` 夹具改为**基于 `ExamFixtures.draftAttempt` 再改状态**，而不是另写一份 `QbExamAttempt` 装配——两份"草稿长什么样"迟早会漂。

### Task 5.5（完成，2026-10-25）

**绿灯与真库取证**：

```
Tests run: 239, Failures: 0 -- 全量单测（236 + ExamBriefsTest 3）
Tests run: 3,  Failures: 0 -- ExamFlowDbIT（真库，1.45s）
qb_paper=4 attempt_990005=0 record_990005=0 attempt_ALL=0 question=7   （Seg5Residue 探针）
```

样例卷 4 行未动、合成用户零残留、`qb_exam_attempt` 全表仍是 0 行（此刻还没有业务写入者）、`qb_question` 仍 7 行。

**没做到的一件事（明写，不掩盖）**：本任务**没有单独跑一次"先红"**。数据层五条查询与它们的测试是一遍写完的，第一次 `mvn -Dtest=ExamFlowDbIT` 确实是红的，但红的原因是**我的 import 缺了 `org.apache.ibatis.annotations.Update`**（`找不到符号 Update`，`QbExamAttemptMapper.java:67`），不是"被测行为还不存在"那种红。补上 import 后一次绿。Task 5.1/5.2/5.3/5.4 都留了真实的红灯输出，这四条是纪律做到位；5.5 这一条没做到，代价是"finalizeIfDraft 的 0/1 语义"少了一次独立反证——它现在由 db-it 的三条断言（回 1、再回 0、越权回 0）在真库上钉住，仍然可复核。

**对计划的偏差**：

1. `insertAttempt` 用**真时间戳**（`started_at = now-60s`、`deadline_at = now+60min`），计划给的是一段写死的字面量（`'2026-10-24 09:00:00'`）。写死的日期会让段 5 之后所有"过期草稿"的用例依赖当天日期，Task 5.9 的 db-it 全链路要在同一条用例里造过期行，必须能自由设 `deadline_at`。
2. `finalizeAttempt` 的实现签名与计划一致（五个参数），但计划正文的 `expandRecords` 那一版把 `draft` 传进均摊函数，我按执行记录里的更正版传 `terminal`（`duration_ms` 才不会静默为 null）。
3. 三条 db-it 用例在计划的三条之上多钉了四件事：`finalizeIfDraft` 带 `user_id` 时越权回 0 行、`countForPaper` 把草稿也算进 total、`selectAttemptPage` 的 `ORDER BY id DESC` 用 `seq_no` 序列 `[3,2,1]` 钉住、草稿行的 `score` 必须是 null（这条是 `AttemptPageRow` 全用包装类型的理由）。原计划的三条用例只覆盖了闸门、唯一键与草稿查询。
4. db-it 样板（open/purge/flushCache/insertAttempt）这里是**第五次重复**。段 3/段 4/段 5 的接缝表都记着这件事，阻力是"MySQL 口令从哪来"与用户"秘密只进 gitignored 本地文件"的规则相冲（`db-it` 里 root 空口令已经是明文，抽取会把它复制第六遍）。本段仍不动，段 5 结束后这条仍然挂在接缝表上。

### Task 5.6（待执行）

### Task 5.7（待执行）

### Task 5.8（待执行）

### Task 5.9（待执行）

### Task 5.10 端到端与文档回写（待执行）

- [ ] 旧 PID 与新 PID + `Tomcat started` / `Started ... in` 两行日志（UTF-8 读）
- [ ] 登录 `token chars = <n>`（只记长度）
- [ ] 正向 17 条逐条 `code` 与关键数字（`seqNo`、`deadlineAt-serverNow`、`answeredCount`、`progressPercent`、`status`、四个计数、`originalText` 交卷前后形态）
- [ ] **#04 的 `attemptId` 与 #03 相同**（9.3 的第一硬判据）
- [ ] **#11 与 #10 的成绩逐字相同** + 库里行数相同（幂等）
- [ ] **#12 与 #10 的 `data` 完全相同**（刷新不丢解析）
- [ ] **#14 `paper/detail` 交卷后 `originalText` 非 null**（段 4 裁定二的活体闭环）
- [ ] 负例矩阵逐条 `code`；`no-token` 记 HTTP 状态码
- [ ] 扫描 `scanned=<n> leaks=0` + 三条专属断言结论
- [ ] 库里取证每一个数字（含 `qb_user_subject_stat` 前后不变）
- [ ] 清理 SQL 与"未代执行"的说明

### 伪阳性（缺这段就等于没做）

- [ ] Task 5.7 Step 4：泄漏 `analysis` 与 `isCorrect` 两次实测的失败报文（完整响应体），回滚后 `grep -c` 为 0 + 全量绿

---

## 自检（对本计划本身的复核）

- **spec 覆盖**：§4.4 状态机 → Task 5.6/5.8/5.9（四条转换 + 归档即判分）；§5.6 的六个 exam 接口 → 5.6/5.7/5.8/5.9；§5.6 的交卷事务八步 → `ExamFinalizer`（5.5）+ `ExamResults`（5.4）；§5.7 错误矩阵 → 5.6（40000/40400/40900/40300）、5.8（41001/41002）、5.9（40300/40400/41002）+ 5.10 的负例矩阵；§6.3/6.4 → `ExamDeadline`（5.2）与三处入口；§7.1 → 5.9 Step 1 的 `verifyNoInteractions(statMapper)` + 真库 + 端到端三层；§9.1 的 3/4/5/8 四条 → 5.9/5.8/5.6/5.9；§9.3 的六条考试项 → 5.10。**本段不覆盖**：`exam/attempt/detail` 显示原文（spec 那句话按此更正）、40300 端到端。
- **占位符扫描**：全文没有 TBD/TODO/"类似的"。写计划时的自查一共揪出 **14 处我自己写错的地方**，本文件现在已经是**改好的版本**（不是"标注了待改"），执行时按现状写就行：Task 5.1 `encodeGuardsKeys` 的伪断言、Task 5.3 的三元式断言与 `assemble` 漏累加 `score`、Task 5.5 的 `selectMaxSeqNo` 废 `@Param`、`selectAttemptPage` 里的占位列、`ExamBriefs` 的错 import 与"建完再改 record 分量"、`ExamBriefsTest` 的旧单参 `load(...)`、`ExamFinalizer.expandRecords` 把 `draft` 而不是 `terminal` 传给均摊（会让 `duration_ms` 静默变 null）、Task 5.6 的 `finalizerMapperProbe()` 占位、`verify(...), "msg"` 括号错、`Clock` 多余参数、`reuse` 死参数、`validationBriefs` 里那条与 `ExamBriefs` 重复的 `selectBatchIds`、Task 5.7 的 `getIsCorrect()` 空转断言、Task 5.9 的 `countForPage`/`getAnswerModeOrIsCorrect()`/`score=23.00`（真值 17.00）与 10 参构造器。**其中四条改的是断言强度以外的东西（语法/签名），三条是会让测试空转的假断言，一条（17.00）是我算错**——这份清单说明"计划里的代码块必须逐块编译级复核"，别当新发现。
- **类型一致**：`ExamFinalizer.finalizeAttempt(QbExamAttempt, PaperStructure, QbPaper, int, long)` 五参数在 Task 5.5 定义、5.6/5.9 两处调用逐字一致；`ExamGrading.PaperGrade` 的九个分量在 `ExamResults`、`ExamSubmitTest` 里用法相同；`ExamAnswers` 的 map key 类型在服务层是 `Long`、在响应体 `answeredMap` 是 `String`（裁定五的边界，`ExamServiceImpl.toStartVO` 是唯一转换点）；`attemptId`/`paperId`/`questionId` 一律 `Long`（进 JSON 不溢出）；`progressPercent` 沿用段 4 的 `double`；`score`/`fullScore`/`accuracy` 一律 `BigDecimal`（两位小数出网）。
- **与本仓库既有代码的一致性**：`PracticeMasker`/`AnswerGrader` 是 bean 注入（`@Service`，实测 `PracticeMasker.java:20`），`PaperLoad`/`PaperSkeleton`/`ExamAnswers`/`ExamDeadline`/`ExamGrading`/`ExamResults` 是静态工具（无状态、可单测）——与段 2/段 4 的分工口径一致；`ExamBriefs` 做成 bean 因为它要两个 mapper。

## 执行方式

**主线执行，不派子代理**（用户规则），逐任务 TDD：红 → 绿 → 报告 → **`git add -- <显式 pathspec>` + `git commit` + `git push`**（用户规则：每个完成的任务提交并推送；工作树与另一个会话共享，宽 `git add` 卷进过别人的文件）。Task 5.10 只在段尾提交文档回写与端到端证据。**本段无 DDL 动作**，不需要新的库授权。
