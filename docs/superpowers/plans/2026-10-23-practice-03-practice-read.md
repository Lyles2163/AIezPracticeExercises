# 段 3：题库读侧（题目列表/断点取题/错题本/收藏本）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:executing-plans 在本会话里逐任务执行本计划（主线实现 + 自查，**不派子代理**）。步骤用 `- [ ]` 语法跟踪。**不执行 `git add`/`git commit`**——每个任务的收尾是"改动留在工作树 + 报告 diff + 等用户提交"（用户规则 2026-10-24：你写到 dev 上让我来提交）。

**Goal:** 补齐阶段一剩余的 6 个接口——`GET /practice/question/page`、`GET /practice/question/current`、`GET /practice/wrong/page`、`POST /practice/wrong/master`、`GET /practice/favorite/page`、`POST /practice/favorite/toggle`——并把脱敏守卫从"VO 的进程内序列化"升级为"真实响应体断言"，使段 3 结束时"科目顺序刷题 + 逐题判分 + 错题本 + 收藏本 + 统计"是一个可直接交付的产品状态。

**Architecture:** 读侧全部走"**分页计数 + 一页行 + 批量补齐**"的三段式：一次 `COUNT(*)` 定 total，一次带 `LIMIT offset, size` 的查询定本页行，再用**一条** `IN (...)` 批量查询把"最近作答""位置序号""是否收藏"补齐——绝不在循环里查库。裸 `LIMIT` 而不是 MyBatis-Plus 分页插件：本模块未配置 `PaginationInnerInterceptor`（`config/` 下只有 `RedisConfig` 与 `PracticeProperties`），引入它会让所有 BaseMapper 查询都被改写 SQL，风险面远大于两条手写 SQL。写侧只有两处且都是幂等友好的单语句：错题置已掌握复用段 2 的 `masterIfUnmastered`（条件更新），收藏 toggle 走 `DELETE → 0 行才 INSERT ... ON DUPLICATE KEY UPDATE id=id → 回读存在性`。取题出口仍然只有一个：`PracticeMasker` 是答题态唯一复制器，本段不新增第二个复制器，所以守卫测试守的那份清单不会散落。

**Tech Stack:** Java 21 / Spring Boot 3.2.4 / MyBatis-Plus 3.5.17（注解式 `@Select` + `<script>` foreach）/ MySQL 9.1.0 / Jackson / JUnit 5 + Mockito + standalone MockMvc（`spring-test`，随 starter-test 已在依赖内）。

**Spec:** `docs/superpowers/specs/2026-10-23-practice-exam-api-design.md`（§5.1 通用约定、§5.2 阶段一清单、§5.4 脱敏、§5.7 子码、§6.1/§6.2 流程、§7.1/§7.3 口径、§9.1.2 守卫、§9.3 验收清单）
**Roadmap:** `docs/superpowers/plans/2026-10-23-practice-00-roadmap.md`（全局约束与实测修正全部隐含适用，本文件不重复）
**上游已交付:** 段 1（`Result`/实体/子码/身份/`subject/list`）、段 2（`AnswerGrader`/`PracticeMasker`/`answer/submit`/`answer/self-judge`）

---

## 全局约束（每个任务都隐含包含）

- **本段零 DDL。**不建表、不加列、不改任何 `qb_*` 结构；`qb_paper`/`qb_exam_attempt`/`attempt_id` 是段 4 的关卡动作，需要用户逐次明确授权。
- **零 pom 改动。**不新增依赖、不引入 `PaginationInnerInterceptor`、不引 `spring-boot-starter-validation`（校验在 Service 显式做）。
- **PracticeModule 对 `qb_question`/`qb_question_option`/`qb_subject`/`users_dev` 只读**；写只允许落在 `qb_user_wrong`/`qb_user_favorite`（本段）与 `qb_user_answer_record`/`qb_user_subject_stat`（段 2 已交付，本段不新增写入点）。
- **后端永不读 `qb_question_option.is_correct`**（spec 5.5）。本段新增的选项查询 SQL **不选这一列**，实体对应字段留在 null，并有 db-it 断言这一点。
- **身份只从 `X-User-Name` 换**，经 `UserIdentityService.resolveUserId`；参数/请求体里的任何 `userId` 一律不看。
- **答题态响应不得出现答案真值**（spec 5.4）：字段名清单是 `PracticeMasker.FORBIDDEN_IN_ANSWER_STATE`，本段把它跑在**真实响应字节**上。
- **分页不外泄 `IPage`**，统一 `PageVO{pageNum,pageSize,total,pages,list}`；时间字段一律 epoch 毫秒 `Long`。
- 注释、日志、`@DisplayName`、commit message 用中文；标识符用英文。
- **绝不把 JWT 密钥、数据库口令复制进仓库任何文件、命令回显或日志**；秘密只走 gitignored 的 `local.properties` + `${VAR:}` 占位。
- 提交只由**用户**执行。本计划的所有任务收尾都只把改动留在工作树并报告 `git status --short` / `git diff --stat`，**不 `git add`、不 `git commit`**（用户会自己挑选暂存范围；工作树当前还有另一会话的 `AIModule/**` 未提交改动）。**禁止** `git add -A`、`git stash`、`git reset --hard`、`--no-verify`、`git push`。
- **不在仓库里留临时文件**。端到端脚本、探针程序、响应快照一律写到仓库外：`C:/Users/Administrator/.qoder-cn/tmp/F--AAAJAVA-code-ezPracticeExercises/seg3/`（段 1/段 2 的探针与快照就在隔壁的 `seg2/`）。
- **永不 `@SpringBootTest`**（会去拉 Nacos/Redis/Seata）；**永不 `-Dgroups=...`**，db-it 的运行形式是 `-Dtest=<类名> -Dsurefire.excludedGroups=` 外加两个 `-Dsurefire.failIf*=false`，且**不能只看 BUILD SUCCESS**，必须确认 `Tests run:` 非 0。

---

## 前置：环境与关卡

```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
MVN="/c/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
# 全量单测（db-it 默认被 surefire 排除）
"$MVN" -o -pl PracticeModule -am test 2>&1 | tail -25
# 指定的单个测试类
"$MVN" -o -pl PracticeModule -am test -Dtest=AnswerStatusTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
# 真库集成测试（需要本机 MySQL 9.1.0 在跑，root 空口令）
"$MVN" -o -pl PracticeModule -am test -Dtest=QuestionReadSqlDbIT -Dsurefire.excludedGroups= \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```

**两个关卡，执行前向用户明示（不阻塞，与段 2 同性质）：**

1. **真库写测试**：`QuestionReadSqlDbIT` 往 dev 库的 `qb_user_answer_record`/`qb_user_wrong`/`qb_user_favorite` 写入合成 `user_id=990002` 的行（远高于真实 id，不撞任何现存用户；段 2 用的是 990001），`@BeforeAll`/`@AfterAll` 各清一次。**只读表 `qb_question`/`qb_subject` 不动。**
2. **端到端会往 `test918`（id=10）名下写真实收藏与错题状态**。这是接口正常产物；Task 3.7 的执行记录附清理 SQL 供用户自行执行（本计划不代执行删除）。

**段 3 不产生任何 DDL，也不需要重启 Nacos/网关以外的服务**（除 Task 3.7 需要重启 8102 装载新代码）。

---

## 前置：本段实测事实（2026-10-24，仓库外 JDBC 探针，dev 库 `ezpracticeexercises`）

写这些不是为了好看——每条都有对应的测试把它钉住，或者它就是下面某条裁定的证据。

1. **索引已就位，无需加索引**：
   - `qb_question`：`idx_subject_status(subject_id,status)`、`idx_owner_subject_status_sort(subject_id,status,sort_order)`；
   - `qb_user_answer_record`：`idx_user_question_time(user_id,question_id,created_at)`、`idx_user_subject_time`、`idx_user_subject_correct`；
   - `qb_user_wrong`：`uk_user_question(user_id,question_id)` + `idx_user_subject_status_time(user_id,subject_id,status,last_wrong_at)`；
   - `qb_user_favorite`：`uk_user_question(user_id,question_id)` + `idx_user_subject_time(user_id,subject_id,created_at)`。
   本段的三类查询（科目内计数/分页、按 `(user_id,question_id)` 批量补齐、按 `(user_id,subject_id,status)` 分页）全部落在这些前缀上。
2. **`qb_question.sort_order` 在同一科目内不唯一**：科目 1 的 qid 1 与 qid 6 都是 `sort_order=1`。这是裁定二的直接证据。
3. **科目 1 有 4 道启用题，科目 2 有 3 道**（与段 1 db-it 的 `countEnabledQuestionRows` 一致）。按 `(sort_order,id)` 的位置序号实测：qid 1 → 1，qid 6 → 2，qid 5 → 4。
4. **`is_correct` 三态已在真库出现**：`test918`（id=10）名下有一条 `id=22`、`question_id=5`、`is_correct=-1` 的待自评行（段 2 端到端留下），本段的 `answerStatus=3` 断言直接拿它做真库锚点。**（2026-10-24 更新：Task 3.7 的端到端已把这条行自评回填成 `is_correct=1`，全库 `-1` 行数归零；重跑该流程要先答一次简答题重新造行。）**
5. **种子收藏属于幽灵用户**：`qb_user_favorite` 现有 4 行分属 `user_id=2/3`（`users_dev` 里不存在这两个 id）。所以收藏分页的真库断言必须用合成用户，不能对种子行做数量假设。
6. **题目标题长度 10~21 字符**，含换行的题干存在 → `titleBrief` 必须折叠空白（裁定六）。
7. **`qb_user_answer_record` 除主键外无唯一键**（段 2 已记）→ 同题多行是常态，"最近一次"只能用 `MAX(id)` 定义，不能用 `SELECT ... LIMIT 1` 的隐式顺序。

---

## 六条裁定（执行前请逐条过目；前两条与 spec 原文不同）

**裁定一（对 spec §5.2 契约的追加，需要用户点头）：`answerStatus` 增加 `3`=待自评，且 `question/current` 返回 `pendingRecordId`。**
spec 只定义了 `0/1/2`，而段 2 落地的是 `is_correct` 三态（`-1/0/1`）。 roadmap §3.10 明文要求段 3 为 `-1` 显式裁定映射。三个候选里：并入 `2`（答错）会让简答题答一次就在答题卡上显示"错"（与 7.1 之后"自评时才统计"矛盾）；并入 `0`（未作答）会让断点反复把同一题当新题推给用户，且**刷新页面后用户永远拿不回 `recordId`，那段 2 的 self-judge 接口在真实前端里不可达**——这是段 2 留下的洞，只有本段能补。故定 `3`，并给 `current` 加 `pendingRecordId`（仅当该记录可自评时才非空，见裁定三）。

**裁定二（推翻 spec §6.2 的算法，有可证明的反例）：断点续做改用 `NOT EXISTS`，不用 `MAX(sort_order)` 反推。**
§6.2 的 SQL 是"`MAX(q.sort_order)` 之后取 `sort_order > 该值` 的最小 `sort_order` 一题"。实测事实 2 说明 `sort_order` 在科目内会重复：用户只答过 qid 6（`sort_order=1`）时 `MAX=1`，`sort_order > 1` 直接**跳过未作答的 qid 1**。Task 3.1 的 db-it 里有一条对照实验把这个跳过钉成会红的断言（同时钉住"若哪天旧算法也能给出 qid 1，说明证据失效，测试要重写"）。`NOT EXISTS` 与排序键 `(sort_order,id)` 天然对齐，不存在同类漏洞。

**裁定三：`pendingRecordId` 只给"练习类"的待自评行。**
段 2 的自评闸门只接受 `answer_mode IN (1,3)`，考试简答（`mode=2`）走 self-judge 必然 `40000`。所以 `answerStatus=3` 而记录来自考试时，`pendingRecordId` 回 `null`——给前端一个注定失败的 recordId 比不给更糟。这条判据落在 `AnswerStatus.selfJudgeable`，单测覆盖四种 mode（1/2/3/null）。

**裁定四：`index` 是位置序号，不是 `sort_order`。**
因为事实 2，把 `sort_order` 当"第几题"会显示两个"第 1 题"。位置序号 = 本科目启用题里按 `(sort_order,id)` 排到第几位，用一条批量子查询算出（Task 3.1），`question/page` 则直接由 `offset + 行内序号` 得到（同一条 SQL 内顺序稳定，省一次查询）。这也让答题卡序号在题目被下架后自动重排——这是我们要的：下架题在任何列表里都不出现（裁定五）。

**裁定五：列表一律只含 `status=1` 的题；`toggle` 的"取消收藏"不要求题目启用。**
三个列表页（题目/错题/收藏）都 `JOIN qb_question ... AND q.status=1`，语义统一为"下架题全站不可见"，但**不删**已存在的错题/收藏行——重新上架即恢复。而 `favorite/toggle` 的删除分支**不查题目状态**：否则下架题的收藏在页面上消失后用户永远删不掉它，数据脏死在库里。新增分支仍要求 `status=1`（否则 `40900`，与 submit 同口径）。

**裁定六：分页越界报 `40000` 不静默夹取；`titleBrief` 折叠空白后按 20 字符截断。**
`pageSize>100` 若被静默夹到 100，前端以为"我要了 500 条"，翻页游标就此错位且不报错——这类偏差最难查。`pageNum` 另设 `1_000_000` 硬上限，理由写进代码注释：`(pageNum-1)*pageSize` 溢出成负数会直接落进 `LIMIT` 变成库错 → `code=0`。`titleBrief` 的 20 是按事实 6 选的（最长标题 21，正好有一条会被截断，单测拿它当真数据锚点）。

> **与 spec §8 的一处偏差（记录，不返工）**：`practice.page.default-size/max-size` 不落进 `PracticeProperties`，改为 `PracticePages` 里的常量。理由：注入 bean 后单测要 new 服务反而更绕，而 20/100 两个值的变更概率极低。`identity.cache-ttl-seconds`（段 1）与 `exam.grace-seconds`（段 5）保持配置绑定，那两个是真需要调的。

---

## 本段文件结构

| 文件 | 责任 | 动作 |
|---|---|---|
| `.../mapper/QuestionPageRow.java` | 一页题目行的投影（`questionId,type,difficulty,tagsJson,sortOrder`） | Create |
| `.../mapper/QuestionPositionRow.java` | 位置序号投影（`questionId,posNo`） | Create |
| `.../mapper/LatestAnswerRow.java` | 每题最近一次作答投影（含 `isCorrect,answerMode`，状态在 Java 侧映射） | Create |
| `.../mapper/WrongPageRow.java` | 错题一页投影 | Create |
| `.../mapper/FavoritePageRow.java` | 收藏一页投影 | Create |
| `.../mapper/QbQuestionMapper.java` | + `countEnabledBySubject` / `selectEnabledPage` / `selectPositions` / `selectFirstUnansweredQuestionId` | Modify |
| `.../mapper/QbUserAnswerRecordMapper.java` | + `selectLatestByQuestions`（一条 `IN` 批量，零 N+1） | Modify |
| `.../mapper/QbUserWrongMapper.java` | + `selectWrongPage` / `countWrong` / `selectSmartCandidateQuestionId`（写侧复用段 2 的 `masterIfUnmastered`） | Modify |
| `.../mapper/QbUserFavoriteMapper.java` | + `selectFavoritePage` / `countFavorites` / `selectFavoritedQuestionIds` / `deleteByUserAndQuestion` / `insertIgnoreDuplicate` / `countByUserAndQuestion` | Modify |
| `.../mapper/QbQuestionOptionMapper.java` | + `selectDisplayOptions`（**不选 `is_correct`**） | Modify |
| `.../service/PracticePages.java` | 分页入参归一与越界裁定（纯静态） | Create |
| `.../service/AnswerStatus.java` | `answerStatus` 口径与可自评判据（纯静态，裁定一/三） | Create |
| `.../service/PracticeMasker.java` | + `titleBrief` 静态助手、`parseTags` 转 public | Modify |
| `.../service/LatestAnswers.java` | 两个批量补齐助手（最近作答 / 位置序号），空集合绝不进 `IN ()` | Create |
| `.../service/SubjectGuards.java` | 科目存在且启用的校验（第 3 处出现时从两个服务里抽出） | Create |
| `.../pojo/vo/QuestionItemVO.java` | 题目一页的行 | Create |
| `.../pojo/vo/QuestionCurrentVO.java` | 取题响应（`finished` + 嵌套脱敏 `question` + 用户自身数据） | Create |
| `.../pojo/vo/WrongItemVO.java` `FavoriteItemVO.java` `FavoriteToggleVO.java` | 错题/收藏行与 toggle 结果 | Create |
| `.../pojo/WrongMasterReq.java` `FavoriteToggleReq.java` | 两个 POST 的请求体 | Create |
| `.../service/QuestionReadService.java` + `impl/QuestionReadServiceImpl.java` | 题目分页与取题 | Create |
| `.../service/WrongBookService.java` + `impl/WrongBookServiceImpl.java` | 错题分页与置已掌握 | Create |
| `.../service/FavoriteService.java` + `impl/FavoriteServiceImpl.java` | 收藏分页与 toggle | Create |
| `.../controller/PracticeQuestionController.java` `PracticeWrongController.java` `PracticeFavoriteController.java` | 6 个端点 | Create |
| 测试：`QuestionReadSqlDbIT` `PracticePagesTest` `AnswerStatusTest` `PracticeMaskerTest`(扩) `LatestAnswersTest` `QuestionReadServiceTest` `SubjectGuardsTest` `WrongBookServiceTest` `FavoriteServiceTest` `PracticeQuestionControllerTest` `PracticeWrongControllerTest` `PracticeFavoriteControllerTest` `AnswerStateResponseGuardTest` | 见各任务 | Create/Modify |

**本段不碰**：任何 DDL、`qb_paper`/`qb_exam_attempt`/`ExamService`（段 4/段 5）、`AnswerGrader` 的判分口径、`CommonJwt`、AIModule、网关配置（`/practice/**` 路由段 1 已通）。

## Task 3.1：读侧 SQL 层（六个查询 + 投影行 + 真库取证）

**Files:**
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/mapper/QuestionPageRow.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/mapper/QuestionPositionRow.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/mapper/LatestAnswerRow.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/mapper/WrongPageRow.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/mapper/FavoritePageRow.java`
- Modify: `.../mapper/QbQuestionMapper.java`（+4 方法）
- Modify: `.../mapper/QbUserAnswerRecordMapper.java`（+1 方法）
- Modify: `.../mapper/QbUserWrongMapper.java`（+3 方法）
- Modify: `.../mapper/QbUserFavoriteMapper.java`（+6 方法）
- Modify: `.../mapper/QbQuestionOptionMapper.java`（+1 方法）
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/mapper/QuestionReadSqlDbIT.java`

**Interfaces:**
- Consumes: 段 1/段 2 的实体与 `QbUserWrongMapper.masterIfUnmastered` / `selectByUserAndQuestion`（本段不新写错题置已掌握的 SQL）。
- Produces（段 3 后续任务与段 5 都按这些签名调用）:
  - `long QbQuestionMapper.countEnabledBySubject(Long subjectId)`
  - `List<QuestionPageRow> QbQuestionMapper.selectEnabledPage(Long subjectId, long offset, long pageSize)`
  - `List<QuestionPositionRow> QbQuestionMapper.selectPositions(Collection<Long> ids)`
  - `Long QbQuestionMapper.selectFirstUnansweredQuestionId(Long subjectId, Long userId)`
  - `List<LatestAnswerRow> QbUserAnswerRecordMapper.selectLatestByQuestions(Long userId, Collection<Long> ids)`
  - `record QuestionPageRow(Long questionId, Integer type, Integer difficulty, String tagsJson, Integer sortOrder)`
  - `record QuestionPositionRow(Long questionId, long posNo)`
  - `record LatestAnswerRow(Long recordId, Long questionId, String userAnswerJson, Integer isCorrect, Integer answerMode)`
  - `record WrongPageRow(Long questionId, Integer wrongCount, Date lastWrongAt, Integer status, Integer type, String title)`
  - `record FavoritePageRow(Long questionId, Date createdAt, Integer type, String title)`

**为什么投影行放 `mapper/` 而不是 `pojo/vo/`**：行是 SQL 的形状（`Date`、原始 `tagsJson`、`posNo`），VO 是 API 的形状（epoch 毫秒、`List<String> tags`、`index`）。混为一谈就没有地方放"epoch 毫秒换算"和 `titleBrief` 截断这些必须能被单测的逻辑——`StatCountRow` 已是同一先例。

- [x] **Step 1：写五个投影行**

`QuestionPageRow.java`：

```java
package org.leon.practicemodule.mapper;

/** selectEnabledPage 的投影行。tagsJson 保持原始串，解析在 PracticeMasker（唯一的解析出口）。 */
public record QuestionPageRow(Long questionId, Integer type, Integer difficulty,
                              String tagsJson, Integer sortOrder) {
}
```

`QuestionPositionRow.java`：

```java
package org.leon.practicemodule.mapper;

/** selectPositions 的投影行：posNo 就是 API 里的 index（裁定四）。列名用 pos_no，避开 index/position 这两个 MySQL 关键词。 */
public record QuestionPositionRow(Long questionId, long posNo) {
}
```

`LatestAnswerRow.java`：

```java
package org.leon.practicemodule.mapper;

/**
 * selectLatestByQuestions 的投影行：每题"最近一次作答"的原始形态。
 * 刻意把 isCorrect / answerMode 原样带出来，answerStatus 的三态映射交给 AnswerStatus——
 * 映射规则是 Java 逻辑，必须能在不连库的单测里逐个值验。
 */
public record LatestAnswerRow(Long recordId, Long questionId, String userAnswerJson,
                              Integer isCorrect, Integer answerMode) {
}
```

`WrongPageRow.java`：

```java
package org.leon.practicemodule.mapper;

import java.util.Date;

/** selectWrongPage 的投影行。lastWrongAt 保持 Date，epoch 毫秒换算在服务里做。 */
public record WrongPageRow(Long questionId, Integer wrongCount, Date lastWrongAt,
                           Integer status, Integer type, String title) {
}
```

`FavoritePageRow.java`：

```java
package org.leon.practicemodule.mapper;

import java.util.Date;

/** selectFavoritePage 的投影行 */
public record FavoritePageRow(Long questionId, Date createdAt, Integer type, String title) {
}
```

- [x] **Step 2：`QbQuestionMapper` 增四个查询**

在 `QbQuestionMapper` 里补 import（`org.apache.ibatis.annotations.Select`、`Param`、`java.util.Collection`、`java.util.List`）并追加：

```java
    /** 科目内启用题总数：分页 total 与"这道科目还剩题吗"共用，走 idx_subject_status */
    @Select("SELECT COUNT(*) FROM qb_question WHERE subject_id = #{subjectId} AND status = 1")
    long countEnabledBySubject(@Param("subjectId") Long subjectId);

    /**
     * 题目列表的一页。排序键必须是 (sort_order, id)：实测同科目内 sort_order 会重复
     * （科目 1 的 qid 1 与 qid 6 都是 1），只按 sort_order 排会让翻页在并列行之间
     * 返回重复题或漏题。裸 LIMIT 不用分页插件——本模块没有 PaginationInnerInterceptor。
     */
    @Select("SELECT id AS questionId, type, difficulty, tags_json AS tagsJson, sort_order AS sortOrder "
            + "FROM qb_question WHERE subject_id = #{subjectId} AND status = 1 "
            + "ORDER BY sort_order, id LIMIT #{offset}, #{pageSize}")
    List<QuestionPageRow> selectEnabledPage(@Param("subjectId") Long subjectId,
                                            @Param("offset") long offset,
                                            @Param("pageSize") long pageSize);

    /**
     * 一批题各自在本科目内的位置序号（裁定四的 index）。
     * 关联子查询每行一次，调用方一批最多 100 题，且内层命中 idx_owner_subject_status_sort。
     * 注意 <script> 里 '<' 必须写成 &lt;，否则 XML 解析阶段就炸（不是 SQL 语法错）。
     */
    @Select("<script>SELECT q.id AS questionId, "
            + "(SELECT COUNT(*) FROM qb_question b WHERE b.subject_id = q.subject_id AND b.status = 1 "
            + "AND (b.sort_order &lt; q.sort_order OR (b.sort_order = q.sort_order AND b.id &lt;= q.id))) AS posNo "
            + "FROM qb_question q WHERE q.id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<QuestionPositionRow> selectPositions(@Param("ids") Collection<Long> ids);

    /**
     * 顺序练习的下一题：本科目启用中、且该用户没有练习类作答记录（answer_mode IN (1,3)）的第一题。
     *
     * 不用 spec §6.2 的 MAX(sort_order) 反推——实测证伪（裁定二）：只答过 qid 6 时 MAX=1，
     * "sort_order > 1" 会跳过未作答的 qid 1。QuestionReadSqlDbIT 里有新旧对照实验。
     * mode=2（考试）的记录不算进度：7.1 已决定考试不吃科目统计，同理也不该消耗练习进度，
     * 否则刷完一套 25 题的卷子回来这科的断点会被整段跳掉。
     */
    @Select("SELECT q.id FROM qb_question q "
            + "WHERE q.subject_id = #{subjectId} AND q.status = 1 "
            + "AND NOT EXISTS (SELECT 1 FROM qb_user_answer_record r WHERE r.user_id = #{userId} "
            + "AND r.subject_id = #{subjectId} AND r.question_id = q.id AND r.answer_mode IN (1,3)) "
            + "ORDER BY q.sort_order, q.id LIMIT 1")
    Long selectFirstUnansweredQuestionId(@Param("subjectId") Long subjectId,
                                         @Param("userId") Long userId);
```

- [x] **Step 3：`QbUserAnswerRecordMapper` 增一条批量"最近作答"**

```java
    /**
     * 一批题各自的最近一次作答：一题一行，取 MAX(id)。
     * 用 id 不用 created_at——实测该表同秒并发写入无法靠时间区分，而 id 是 AUTO_INCREMENT。
     * 不带 mode 过滤（与上面断点 SQL 的差别是刻意的，见裁定二）：answerStatus 的语义是
     * "我最近一次对这道题的作答"，考试作答同样是真作答；是否可自评另由 AnswerStatus 判。
     */
    @Select("<script>SELECT r.id AS recordId, r.question_id AS questionId, "
            + "r.user_answer_json AS userAnswerJson, r.is_correct AS isCorrect, r.answer_mode AS answerMode "
            + "FROM qb_user_answer_record r "
            + "JOIN (SELECT question_id, MAX(id) AS mid FROM qb_user_answer_record "
            + "WHERE user_id = #{userId} AND question_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "GROUP BY question_id) t ON t.mid = r.id</script>")
    List<LatestAnswerRow> selectLatestByQuestions(@Param("userId") Long userId,
                                                  @Param("ids") Collection<Long> ids);
```

- [x] **Step 4：`QbUserWrongMapper` / `QbUserFavoriteMapper` / `QbQuestionOptionMapper`**

`QbUserWrongMapper` 追加（写侧的 `masterIfUnmastered` 与 `selectByUserAndQuestion` 段 2 已有，本段直接复用，**不再新写一条 UPDATE**）：

```java
    @Select("<script>SELECT COUNT(*) FROM qb_user_wrong w JOIN qb_question q ON q.id = w.question_id "
            + "WHERE w.user_id = #{userId} AND w.subject_id = #{subjectId} AND q.status = 1 "
            + "<if test='status != null'>AND w.status = #{status}</if></script>")
    long countWrong(@Param("userId") Long userId, @Param("subjectId") Long subjectId,
                    @Param("status") Integer status);

    /** 错题本按"最近犯错时间"倒序（最新踩坑的排前面），并列用 id 兜底保证翻页稳定 */
    @Select("<script>SELECT w.question_id AS questionId, w.wrong_count AS wrongCount, "
            + "w.last_wrong_at AS lastWrongAt, w.status AS status, q.type AS type, q.title AS title "
            + "FROM qb_user_wrong w JOIN qb_question q ON q.id = w.question_id "
            + "WHERE w.user_id = #{userId} AND w.subject_id = #{subjectId} AND q.status = 1 "
            + "<if test='status != null'>AND w.status = #{status}</if> "
            + "ORDER BY w.last_wrong_at DESC, w.id DESC LIMIT #{offset}, #{pageSize}</script>")
    List<WrongPageRow> selectWrongPage(@Param("userId") Long userId, @Param("subjectId") Long subjectId,
                                       @Param("status") Integer status,
                                       @Param("offset") long offset, @Param("pageSize") long pageSize);

    /**
     * 智能练习（mode=3）的优先题：本科目未掌握错题里 last_wrong_at 最早的一题——
     * "最早"就是最久没被复习过的那道，比"最新错的"更符合"先补旧账"的直觉。
     * 无候选返回 null，由调用方回落到顺序断点。
     */
    @Select("SELECT w.question_id FROM qb_user_wrong w JOIN qb_question q ON q.id = w.question_id "
            + "WHERE w.user_id = #{userId} AND w.subject_id = #{subjectId} AND w.status = 1 AND q.status = 1 "
            + "ORDER BY w.last_wrong_at, w.id LIMIT 1")
    Long selectSmartCandidateQuestionId(@Param("userId") Long userId, @Param("subjectId") Long subjectId);
```

`QbUserFavoriteMapper`（补 `@Mapper`/`BaseMapper` 之外需要的 `Delete`/`Insert`/`Select`/`Param`/`Collection`/`List` import）：

```java
    @Select("SELECT COUNT(*) FROM qb_user_favorite f JOIN qb_question q ON q.id = f.question_id "
            + "WHERE f.user_id = #{userId} AND f.subject_id = #{subjectId} AND q.status = 1")
    long countFavorites(@Param("userId") Long userId, @Param("subjectId") Long subjectId);

    /** 收藏本按收藏时间倒序（刚收藏的排前面），并列用 id 兜底 */
    @Select("SELECT f.question_id AS questionId, f.created_at AS createdAt, q.type AS type, q.title AS title "
            + "FROM qb_user_favorite f JOIN qb_question q ON q.id = f.question_id "
            + "WHERE f.user_id = #{userId} AND f.subject_id = #{subjectId} AND q.status = 1 "
            + "ORDER BY f.created_at DESC, f.id DESC LIMIT #{offset}, #{pageSize}")
    List<FavoritePageRow> selectFavoritePage(@Param("userId") Long userId, @Param("subjectId") Long subjectId,
                                             @Param("offset") long offset, @Param("pageSize") long pageSize);

    /** 题目列表的 favorited 位：一条 IN 批量，零 N+1 */
    @Select("<script>SELECT question_id FROM qb_user_favorite WHERE user_id = #{userId} AND question_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    List<Long> selectFavoritedQuestionIds(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);

    @Delete("DELETE FROM qb_user_favorite WHERE user_id = #{userId} AND question_id = #{questionId}")
    int deleteByUserAndQuestion(@Param("userId") Long userId, @Param("questionId") Long questionId);

    /**
     * 并发双击的兜底：uk_user_question 已存在时 `id = id` 是合法的空更新，
     * 不报错、不重复建行——toggle 因此不需要 catch DuplicateKeyException。
     */
    @Insert("INSERT INTO qb_user_favorite (user_id, subject_id, question_id, created_at) "
            + "VALUES (#{userId}, #{subjectId}, #{questionId}, NOW()) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertIgnoreDuplicate(@Param("userId") Long userId, @Param("subjectId") Long subjectId,
                              @Param("questionId") Long questionId);

    @Select("SELECT COUNT(*) FROM qb_user_favorite WHERE user_id = #{userId} AND question_id = #{questionId}")
    long countByUserAndQuestion(@Param("userId") Long userId, @Param("questionId") Long questionId);
```

`QbQuestionOptionMapper` 追加——**这条 SQL 里没有 `is_correct`**（spec 5.5 的字面要求；Step 6 的 `displayOptionsCarryNoCorrectness` 会断言实体的 `isCorrect` 确实是 null）：

```java
    /**
     * 取题响应要展示的选项：只选 option_key/content/sort_order，**不选 is_correct**（spec 5.5）。
     * 实体里 isCorrect 因此是 null——这不是遗漏，是"后端永不读真值"这条约束的可证形态。
     */
    @Select("SELECT id, question_id, option_key, content, sort_order FROM qb_question_option "
            + "WHERE question_id = #{questionId} ORDER BY sort_order, id")
    List<QbQuestionOption> selectDisplayOptions(@Param("questionId") Long questionId);
```

- [x] **Step 5：编译**

Run: `"$MVN" -o -pl PracticeModule -am -q -DskipTests compile`
Expected: `BUILD SUCCESS`（安静模式无输出即成功）。若报 `找不到符号 Select`，是 import 漏了。

- [x] **Step 6：写真库取证测试 `QuestionReadSqlDbIT`**

这是本任务的**唯一硬证据**：`LIMIT offset,size` 的参数绑定、record 构造器投影、`&lt;` 转义、`MAX(id)` 语义、新旧断点对照、`is_correct` 不被读出，全都只有真库能证。

```java
package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 读侧 SQL 的真库语义（段 3 唯一连库的测试）。
 * 合成用户 user_id=990002，只写 qb_user_answer_record / qb_user_wrong / qb_user_favorite，
 * 题库表只读；前后各清一次，跑完不留痕。
 */
@Tag("db-it")
@DisplayName("读侧 SQL 的真库语义")
class QuestionReadSqlDbIT {

    private static final long TEST_USER = 990002L;
    /** 语文：4 道启用题，且 qid 1 与 qid 6 的 sort_order 并列（实测 2026-10-24） */
    private static final long SUBJECT_1 = 1L;

    private static SqlSession session;
    private static QbQuestionMapper questionMapper;
    private static QbUserAnswerRecordMapper recordMapper;
    private static QbUserWrongMapper wrongMapper;
    private static QbUserFavoriteMapper favoriteMapper;
    private static QbQuestionOptionMapper optionMapper;

    @BeforeAll
    static void open() throws SQLException {
        DataSource ds = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                "jdbc:mysql://localhost:3306/ezpracticeexercises?useSSL=false"
                        + "&allowPublicKeyRetrieval=true&characterEncoding=utf8",
                "root", "");
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setEnvironment(new Environment("db-it", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.setJdbcTypeForNull(JdbcType.NULL);
        cfg.addMapper(QbQuestionMapper.class);
        cfg.addMapper(QbUserAnswerRecordMapper.class);
        cfg.addMapper(QbUserWrongMapper.class);
        cfg.addMapper(QbUserFavoriteMapper.class);
        cfg.addMapper(QbQuestionOptionMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(cfg).openSession(true);
        questionMapper = session.getMapper(QbQuestionMapper.class);
        recordMapper = session.getMapper(QbUserAnswerRecordMapper.class);
        wrongMapper = session.getMapper(QbUserWrongMapper.class);
        favoriteMapper = session.getMapper(QbUserFavoriteMapper.class);
        optionMapper = session.getMapper(QbQuestionOptionMapper.class);
        purge();
    }

    @AfterAll
    static void close() throws SQLException {
        if (session != null) {
            purge();
            session.close();
        }
    }

    private static void purge() throws SQLException {
        for (String table : List.of("qb_user_answer_record", "qb_user_wrong", "qb_user_favorite")) {
            try (PreparedStatement ps = session.getConnection()
                    .prepareStatement("DELETE FROM " + table + " WHERE user_id = ?")) {
                ps.setLong(1, TEST_USER);
                ps.executeUpdate();
            }
        }
    }

    /** 写一条练习记录。user_answer_json 是 MySQL json 列，必须整份合法 JSON（段 2 实测教训） */
    private static void insertRecord(long questionId, int isCorrect, int mode) {
        QbUserAnswerRecord r = new QbUserAnswerRecord();
        r.setUserId(TEST_USER);
        r.setSubjectId(SUBJECT_1);
        r.setQuestionId(questionId);
        r.setUserAnswerJson("\"探针作答\"");
        r.setIsCorrect(isCorrect);
        r.setDurationMs(1000);
        r.setAnswerMode(mode);
        recordMapper.insert(r);
    }

    private static Long queryLong(String sql, long param) throws SQLException {
        try (PreparedStatement ps = session.getConnection().prepareStatement(sql)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null;
            }
        }
    }

    private static Map<Long, Long> positionsOf(List<Long> ids) {
        Map<Long, Long> map = new HashMap<>();
        for (QuestionPositionRow row : questionMapper.selectPositions(ids)) {
            map.put(row.questionId(), row.posNo());
        }
        return map;
    }

    @Test
    @DisplayName("锚点：科目 1 有 4 道启用题，qid 1 与 qid 6 的 sort_order 并列")
    void duplicateSortOrderIsReal() throws SQLException {
        assertEquals(4L, questionMapper.countEnabledBySubject(SUBJECT_1));
        try (PreparedStatement ps = session.getConnection().prepareStatement(
                "SELECT id, sort_order FROM qb_question WHERE subject_id = ? AND status = 1"
                        + " ORDER BY sort_order, id")) {
            ps.setLong(1, SUBJECT_1);
            Map<Integer, List<Long>> bySort = new java.util.TreeMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bySort.computeIfAbsent(rs.getInt(2), k -> new ArrayList<>()).add(rs.getLong(1));
                }
            }
            assertTrue(bySort.values().stream().anyMatch(ids -> ids.size() > 1),
                    "实测 sort_order 有重复才有本段的对照实验；哪天数据变了，裁定二的证据要重新取：" + bySort);
        }
    }

    @Test
    @DisplayName("位置序号：四道启用题各得 1..4 且互不相同（裁定四）")
    void positionsAreDistinctAndDense() {
        List<Long> ids = questionMapper.selectEnabledPage(SUBJECT_1, 0, 100)
                .stream().map(QuestionPageRow::questionId).toList();
        assertEquals(4, ids.size());
        Map<Long, Long> pos = positionsOf(ids);
        assertEquals(4, pos.size(), "有一道题没算出位置序号，子查询写错了");
        assertEquals(List.of(1L, 2L, 3L, 4L),
                ids.stream().map(id -> pos.get(id)).sorted().toList());
        // qid 1 排第一；与它 sort_order 并列的题排第二，不是并列重号
        assertEquals(1L, pos.get(1L));
    }

    @Test
    @DisplayName("分页：LIMIT offset,size 参数绑定可用，两页不重不漏（并列 sort_order 下）")
    void pagingIsStable() {
        List<Long> first = questionMapper.selectEnabledPage(SUBJECT_1, 0, 2)
                .stream().map(QuestionPageRow::questionId).toList();
        List<Long> second = questionMapper.selectEnabledPage(SUBJECT_1, 2, 2)
                .stream().map(QuestionPageRow::questionId).toList();
        assertEquals(2, first.size());
        assertEquals(2, second.size());
        List<Long> all = new ArrayList<>(first);
        all.addAll(second);
        assertEquals(4, all.size());
        assertEquals(4, all.stream().distinct().count(), "翻页出现重复题——排序没有全序");
        assertTrue(questionMapper.selectEnabledPage(SUBJECT_1, 4, 2).isEmpty(), "越界页必须是空列表");
    }

    @Test
    @DisplayName("对照实验：spec §6.2 的 MAX(sort_order) 会漏题，NOT EXISTS 不漏（裁定二证据）")
    void breakpointSkipsNothingWhereOldAlgorithmSkips() throws SQLException {
        insertRecord(6L, 0, 1);   // 只答过 qid 6，而 qid 1 与它 sort_order 并列且同样未作答

        // 旧算法原样搬过来：MAX(sort_order) 之后取"sort_order 更大"的最小 id
        Long oldNext = queryLong(
                "SELECT MIN(id) FROM qb_question WHERE subject_id = ? AND status = 1 AND sort_order > ("
                        + " SELECT COALESCE(MAX(q.sort_order), -1) FROM qb_user_answer_record r"
                        + " JOIN qb_question q ON q.id = r.question_id"
                        + " WHERE r.user_id = ? AND r.subject_id = ? AND r.answer_mode IN (1,3))",
                SUBJECT_1);
        // 旧算法要两个参数，上面这条只用第一个，第二个参数用第二条查
        Long oldMax = queryLong("SELECT COALESCE(MAX(q.sort_order), -1) FROM qb_user_answer_record r"
                + " JOIN qb_question q ON q.id = r.question_id WHERE r.user_id = ? AND r.answer_mode IN (1,3)",
                TEST_USER);
        assertNotNull(oldMax, "MAX 至少该是 -1 而不是 null");

        Long newNext = questionMapper.selectFirstUnansweredQuestionId(SUBJECT_1, TEST_USER);

        assertEquals(1L, newNext, "NOT EXISTS 必须给出未作答的 qid 1");
        assertNotEquals(1L, oldNext,
                "旧算法若也给出 qid 1，本条对照实验就没有证据了——数据变了要重取（见 duplicateSortOrderIsReal）");
    }

    @Test
    @DisplayName("考试记录不消耗练习进度：mode=2 的作答不影响断点")
    void examRecordsDoNotConsumePracticeProgress() {
        insertRecord(1L, 1, 2);   // 考试里答对过 qid 1
        assertEquals(1L, questionMapper.selectFirstUnansweredQuestionId(SUBJECT_1, TEST_USER),
                "7.1 的口径：考试不算练习进度，qid 1 仍该被推到");

        insertRecord(1L, 1, 1);   // 练习里也答过了
        assertNotEquals(1L, questionMapper.selectFirstUnansweredQuestionId(SUBJECT_1, TEST_USER),
                "练习答过之后 qid 1 才退出断点");
    }

    @Test
    @DisplayName("最近一次作答：一题一行取 MAX(id)，-1 与 mode 原样带出")
    void latestRecordPerQuestion() {
        insertRecord(2L, 1, 1);   // 先对
        insertRecord(2L, 0, 1);   // 后错 —— 这才算"最近一次"
        insertRecord(5L, -1, 1);  // 待自评（与真库里 id=22 那行同形态）
        insertRecord(5L, -1, 2);  // 考试简答的待自评：mode 必须是 2 才能被 selfJudgeable 挡掉

        List<LatestAnswerRow> rows = recordMapper.selectLatestByQuestions(
                TEST_USER, List.of(2L, 5L, 1L));
        assertEquals(2, rows.size(), "qid 1 没有任何记录，不该出现在结果里");
        Map<Long, LatestAnswerRow> byQuestion = new HashMap<>();
        for (LatestAnswerRow row : rows) {
            byQuestion.put(row.questionId(), row);
        }
        assertEquals(0, byQuestion.get(2L).isCorrect(), "取的是 MAX(id) 那条，不是最早那条");
        assertEquals(2, byQuestion.get(5L).isCorrect(), "两条 -1 里取 id 最大的一条");
        assertEquals(2, byQuestion.get(5L).answerMode());
        assertNotNull(byQuestion.get(2L).userAnswerJson());
        assertNotNull(byQuestion.get(2L).recordId());
    }

    @Test
    @DisplayName("空 IN 不能打到 MySQL：短路在服务里，这里只验有值时的行为")
    void wrongAndFavoritePagesWithFilter() {
        wrongMapper.upsertWrong(TEST_USER, SUBJECT_1, 1L);
        wrongMapper.upsertWrong(TEST_USER, SUBJECT_1, 1L);
        wrongMapper.upsertWrong(TEST_USER, SUBJECT_1, 2L);
        wrongMapper.masterIfUnmastered(TEST_USER, 2L);   // qid 2 置已掌握

        assertEquals(2L, wrongMapper.countWrong(TEST_USER, SUBJECT_1, null));
        assertEquals(1L, wrongMapper.countWrong(TEST_USER, SUBJECT_1, 1));
        assertEquals(1L, wrongMapper.countWrong(TEST_USER, SUBJECT_1, 2));

        List<WrongPageRow> all = wrongMapper.selectWrongPage(TEST_USER, SUBJECT_1, null, 0, 10);
        assertEquals(2, all.size());
        assertEquals(2, all.get(0).wrongCount(), "ON DUPLICATE KEY 的增量要在读侧看得见");
        assertNotNull(all.get(0).lastWrongAt());
        assertNotNull(all.get(0).title(), "JOIN qb_question 的列没带出来，titleBrief 就没料可用");

        assertEquals(1L, wrongMapper.selectWrongPage(TEST_USER, SUBJECT_1, 1, 0, 10).size());
        assertEquals(1L, wrongMapper.selectSmartCandidateQuestionId(TEST_USER, SUBJECT_1),
                "未掌握只剩 qid 1");
    }

    @Test
    @DisplayName("收藏：toggle 三步在真库的形态（删除→空插入→回读）")
    void favoriteUpsertDeleteAndReadBack() {
        assertEquals(0, favoriteMapper.deleteByUserAndQuestion(TEST_USER, 1L), "起点必须没行");
        assertEquals(1, favoriteMapper.insertIgnoreDuplicate(TEST_USER, SUBJECT_1, 1L));
        assertEquals(1, favoriteMapper.countByUserAndQuestion(TEST_USER, 1L));
        assertEquals(0, favoriteMapper.insertIgnoreDuplicate(TEST_USER, SUBJECT_1, 1L),
                "并发双击：ON DUPLICATE KEY UPDATE id=id 必须既不报错也不建行");
        assertEquals(1L, favoriteMapper.countFavorites(TEST_USER, SUBJECT_1));
        assertEquals(List.of(1L), favoriteMapper.selectFavoritedQuestionIds(TEST_USER, List.of(1L, 2L)));

        favoriteMapper.insertIgnoreDuplicate(TEST_USER, SUBJECT_1, 2L);
        List<FavoritePageRow> page = favoriteMapper.selectFavoritePage(TEST_USER, SUBJECT_1, 0, 10);
        assertEquals(2, page.size());
        assertNotNull(page.get(0).createdAt());
        assertNotNull(page.get(0).title());

        assertEquals(1, favoriteMapper.deleteByUserAndQuestion(TEST_USER, 2L));
        assertEquals(0, favoriteMapper.deleteByUserAndQuestion(TEST_USER, 2L), "再删是空操作，不抛异常");
    }

    @Test
    @DisplayName("选项查询不读 is_correct（spec 5.5 的字面形态）")
    void displayOptionsCarryNoCorrectness() {
        List<QbQuestionOption> options = optionMapper.selectDisplayOptions(1L);
        assertEquals(4, options.size(), "qid 1 的选项集实测是 A,B,C,D");
        for (QbQuestionOption o : options) {
            assertNull(o.getIsCorrect(), "SQL 里多了 is_correct——后端读真值这条红线被踩了");
            assertNotNull(o.getOptionKey());
        }
    }
}
```

> **Step 6 里 `breakpointSkipsNothingWhereOldAlgorithmSkips` 的 `oldNext` 查询只绑了一个参数**：SQL 里 `subject_id = ?` 与 `r.user_id = ?`、`r.subject_id = ?` 共三个占位符，上面只传一个。执行时把它改成完整三参数形式（照 `queryLong` 复制一个 `queryLong3(String sql, long a, long b, long c)`），**不要**为了让测试通过而放宽断言。这条测试的价值全在 `oldNext != 1` 上。

- [x] **Step 7：跑 db-it**

Run:
```bash
"$MVN" -o -pl PracticeModule -am test -Dtest=QuestionReadSqlDbIT -Dsurefire.excludedGroups= \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -25
```
Expected: `Tests run: 8, Failures: 0, Errors: 0`。**若 `Tests run: 0` 是假绿灯**——命令漏了 `-Dsurefire.excludedGroups=`。

- [x] **Step 8：全量回归（确认没把段 1/段 2 的单测碰红）**

Run: `"$MVN" -o -pl PracticeModule -am test 2>&1 | tail -25`
Expected: `Tests run: 101, Failures: 0`（段 2 基线 101；本步只新增被排除的 db-it，单测数不变）。

- [x] **Step 9：残留检查**

Run: 再跑一次 Step 7，然后确认合成用户零行：
```bash
"$MVN" -o -pl PracticeModule -am -q compile 2>&1 | tail -3
```
（残留检查用仓库外的 JDBC 探针脚本，形如 `SELECT COUNT(*) FROM qb_user_favorite WHERE user_id=990002`，三张表都应为 0。）

- [x] **Step 10：报告改动，停在提交前**

```bash
git status --short -- PracticeModule docs/superpowers/plans/2026-10-23-practice-03-practice-read.md
git diff --stat -- PracticeModule   # 本任务全是新增文件，diff --stat 可能显示为空，改用 git status 的 ?? 行
```
把这两条的输出抄进执行记录，**到此为止**：不 `git add`、不 `git commit`，等用户自己暂存提交。建议的提交信息：`段3 Task3.1 读侧 SQL：位置序号、批量最近作答与 NOT EXISTS 断点（含新旧算法对照取证）`

---

## Task 3.2：三个纯口径助手（分页 / 答题状态 / 标题摘要）

这三个都不碰库、不注入 bean，单测直接 `new`/静态调用。它们是本段所有服务共用的词汇表，先钉死再写端点，免得后面每个任务各写一套边界处理。

**Files:**
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/PracticePages.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/AnswerStatus.java`
- Modify: `PracticeModule/src/main/java/org/leon/practicemodule/service/PracticeMasker.java`（+`titleBrief`，`parseTags` 转 public）
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/PracticePagesTest.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/AnswerStatusTest.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/PracticeMaskerTest.java`（追加 4 个用例）

**Interfaces:**
- Consumes: `LatestAnswerRow`（Task 3.1）
- Produces:
  - `PracticePages.of(Long pageNum, Long pageSize) -> PracticePages.PageParam(long pageNum, long pageSize, long offset)`
  - `AnswerStatus.of(LatestAnswerRow row) -> int`（`UNANSWERED/CORRECT/WRONG/PENDING_SELF_JUDGE = 0/1/2/3`）
  - `AnswerStatus.selfJudgeable(LatestAnswerRow row) -> boolean`
  - `PracticeMasker.titleBrief(String title) -> String`（public static）
  - `PracticeMasker.parseTags(String tagsJson) -> List<String>`（由 private 转 public，行为不变）

- [x] **Step 1：写失败测试 `PracticePagesTest`**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("分页入参归一（裁定六）")
class PracticePagesTest {

    @Test
    @DisplayName("null 走默认：pageNum=1、pageSize=20、offset=0")
    void defaults() {
        PracticePages.PageParam p = PracticePages.of(null, null);
        assertEquals(1L, p.pageNum());
        assertEquals(20L, p.pageSize());
        assertEquals(0L, p.offset());
    }

    @Test
    @DisplayName("offset 由页码与页长算出")
    void offset() {
        assertEquals(40L, PracticePages.of(3L, 20L).offset());
        assertEquals(1L, PracticePages.of(2L, 1L).offset());
    }

    @Test
    @DisplayName("pageSize=100 合法，101 报 40000——不静默夹取")
    void sizeBoundary() {
        assertEquals(100L, PracticePages.of(1L, 100L).pageSize());
        PracticeException e = assertThrows(PracticeException.class,
                () -> PracticePages.of(1L, 101L));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    @Test
    @DisplayName("0 与负数的页码/页长都报 40000")
    void nonPositiveRejected() {
        assertThrows(PracticeException.class, () -> PracticePages.of(0L, 20L));
        assertThrows(PracticeException.class, () -> PracticePages.of(-1L, 20L));
        assertThrows(PracticeException.class, () -> PracticePages.of(1L, 0L));
        assertThrows(PracticeException.class, () -> PracticePages.of(1L, -5L));
    }

    @Test
    @DisplayName("天文数字的 pageNum 报 40000 而不是溢出成负 offset")
    void overflowGuarded() {
        // (Long.MAX_VALUE-1)*100 会溢出为负数，负数进 LIMIT 是库错 -> code=0（在说谎）
        assertEquals(PracticeErrorCode.BAD_REQUEST,
                assertThrows(PracticeException.class,
                        () -> PracticePages.of(Long.MAX_VALUE, 100L)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST,
                assertThrows(PracticeException.class,
                        () -> PracticePages.of(PracticePages.MAX_PAGE_NUM + 1, 100L)).errorCode());
        assertTrue(PracticePages.of(PracticePages.MAX_PAGE_NUM, 100L).offset() > 0,
                "上限内必须给出正 offset");
    }
}
```

- [x] **Step 2：跑测试确认失败**

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=PracticePagesTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -15`
Expected: 编译失败 `找不到符号 PracticePages`。

- [x] **Step 3：实现 `PracticePages`**

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

/**
 * 分页入参归一（spec 5.1：pageNum 从 1 起，pageSize 默认 20、上限 100）。
 *
 * 越界一律 40000，不静默夹到 100：静默夹会让前端以为"我要了 500 条"，
 * 实际只拿到 100 条，翻页游标就此错位且不报错——这类偏差最难查。
 * 值写成常量而不是 spec §8 里的 practice.page.* 配置：注入 bean 后单测要 new 服务反而更绕，
 * 而 20/100 的变更概率极低（段 3 计划裁定六后面记了这处偏差）。
 */
public final class PracticePages {

    public static final long DEFAULT_SIZE = 20L;
    public static final long MAX_SIZE = 100L;
    /** 挡住 (pageNum-1)*pageSize 溢出成负数：pageSize 已 <=100，这里封顶后 offset <= 1e8 */
    public static final long MAX_PAGE_NUM = 1_000_000L;

    private PracticePages() {
    }

    public static PageParam of(Long pageNum, Long pageSize) {
        long num = pageNum == null ? 1L : pageNum;
        long size = pageSize == null ? DEFAULT_SIZE : pageSize;
        if (num < 1 || num > MAX_PAGE_NUM) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "页码不合法");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "每页条数只能是 1 到 100");
        }
        return new PageParam(num, size, (num - 1) * size);
    }

    public record PageParam(long pageNum, long pageSize, long offset) {
    }
}
```

- [x] **Step 4：跑测试确认通过**

Run: 同 Step 2。Expected: `Tests run: 5, Failures: 0`。

- [x] **Step 5：写失败测试 `AnswerStatusTest`**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leon.practicemodule.mapper.LatestAnswerRow;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("answerStatus 三态映射与可自评判据（裁定一/三）")
class AnswerStatusTest {

    private LatestAnswerRow row(Integer isCorrect, Integer mode) {
        return new LatestAnswerRow(7L, 1L, "\"A\"", isCorrect, mode);
    }

    @ParameterizedTest(name = "is_correct={0} -> answerStatus={1}")
    @CsvSource({
            "1, 1",      // 答对
            "0, 2",      // 答错
            "-1, 3",     // 待自评：既不是对也不是错
    })
    void mapping(int isCorrect, int expected) {
        assertEquals(expected, AnswerStatus.of(row(isCorrect, 1)));
    }

    @Test
    @DisplayName("无记录 / is_correct 为 null 都算未作答，不抛 NPE")
    void unanswered() {
        assertEquals(AnswerStatus.UNANSWERED, AnswerStatus.of(null));
        assertEquals(AnswerStatus.UNANSWERED, AnswerStatus.of(row(null, 1)));
    }

    @Test
    @DisplayName("只有练习类的待自评可自评：mode=2 的考试简答不给 recordId")
    void selfJudgeGate() {
        assertTrue(AnswerStatus.selfJudgeable(row(-1, 1)));
        assertTrue(AnswerStatus.selfJudgeable(row(-1, 3)));
        assertFalse(AnswerStatus.selfJudgeable(row(-1, 2)), "段 2 的闸门只收 mode 1/3，回 id 就是回一个必然 40000 的按钮");
        assertFalse(AnswerStatus.selfJudgeable(row(-1, null)));
        assertFalse(AnswerStatus.selfJudgeable(row(1, 1)), "已判过的没有自评这回事");
        assertFalse(AnswerStatus.selfJudgeable(null));
    }

    @Test
    @DisplayName("四个常量就是 API 的取值范围，写死防止将来随手加第五个态")
    void constants() {
        assertEquals(0, AnswerStatus.UNANSWERED);
        assertEquals(1, AnswerStatus.CORRECT);
        assertEquals(2, AnswerStatus.WRONG);
        assertEquals(3, AnswerStatus.PENDING_SELF_JUDGE);
    }
}
```

- [x] **Step 6：实现 `AnswerStatus`**

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.mapper.LatestAnswerRow;

/**
 * answerStatus 口径（spec 5.2 的 0/1/2 + 裁定一新增 3）。
 *
 * 三态映射是段 2 的 -1 哨兵逼出来的：is_correct=-1 既不是"对"也不是"错"。
 * 并入 2 会让简答题答一次就在答题卡上显示"错"（与"自评时才统计"矛盾）；
 * 并入 0 会让断点反复推同一题，而且刷新页面后用户永远拿不回 recordId。
 */
public final class AnswerStatus {

    public static final int UNANSWERED = 0;
    public static final int CORRECT = 1;
    public static final int WRONG = 2;
    public static final int PENDING_SELF_JUDGE = 3;

    private AnswerStatus() {
    }

    public static int of(LatestAnswerRow row) {
        if (row == null) {
            return UNANSWERED;
        }
        Integer isCorrect = row.isCorrect();
        if (isCorrect == null) {
            // 列是 NOT NULL，真出现说明 SQL 少选了列；宁可显示未作答，也不让取题接口 500
            return UNANSWERED;
        }
        if (isCorrect == -1) {
            return PENDING_SELF_JUDGE;
        }
        return isCorrect == 1 ? CORRECT : WRONG;
    }

    /** 待自评 + 练习类作答，才允许把 recordId 交给前端去点"自评" */
    public static boolean selfJudgeable(LatestAnswerRow row) {
        if (of(row) != PENDING_SELF_JUDGE) {
            return false;
        }
        Integer mode = row.answerMode();
        return mode != null && (mode == 1 || mode == 3);
    }
}
```

- [x] **Step 7：扩展 `PracticeMaskerTest`（titleBrief + parseTags 转 public）**

在既有 `PracticeMaskerTest` 类内追加三个用例（不动已有六个）：

```java
    @Test
    @DisplayName("titleBrief：null 原样、空白折叠、20 字符内不动、超长截成 20")
    void titleBriefCases() {
        assertNull(PracticeMasker.titleBrief(null));
        assertEquals("a b", PracticeMasker.titleBrief("  a \t\n b  "));
        String twenty = "一二三四五六七八九十一二三四五六七八九十一二";   // 20 字
        assertEquals(twenty, PracticeMasker.titleBrief(twenty), "刚好 20 字不该被砍");
        String twentyOne = twenty + "三";
        String brief = PracticeMasker.titleBrief(twentyOne);
        assertEquals(20, brief.length(), "截断后总长仍是 20，含省略号");
        assertEquals("…", brief.substring(19));
        assertEquals(twenty.substring(0, 19), brief.substring(0, 19));
    }

    @Test
    @DisplayName("titleBrief：种子题干实测最长 21 字，确实有一条会被截断")
    void titleBriefHitsRealData() {
        // 事实 6：标题长度 10~21。若全部短于 20，上面的截断分支永远不会在真实数据上走到
        String real = "下列句子中，加点的词语意思相同的一项是";   // 实测来自 qb_question，20 字
        assertEquals(20, real.length(), "锚点标题长度变了，本用例要重取样本");
        assertEquals(real, PracticeMasker.titleBrief(real));
    }

    @Test
    @DisplayName("parseTags 转 public static 后行为不变：合法/空白/坏 JSON")
    void parseTagsStillSafe() {
        assertEquals(List.of("语法", "时态"), PracticeMasker.parseTags("[\"语法\",\"时态\"]"));
        assertEquals(List.of(), PracticeMasker.parseTags(null));
        assertEquals(List.of(), PracticeMasker.parseTags("[]"));
        assertEquals(List.of(), PracticeMasker.parseTags("{坏"));
    }
```

- [x] **Step 8：跑测试确认失败**

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=PracticeMaskerTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -15`
Expected: 编译失败（`titleBrief` / public `parseTags` 不存在）。

- [x] **Step 9：改 `PracticeMasker`**

把 `private List<String> parseTags(String tagsJson)` 改为 `public static List<String>`（同时把方法体里的 `objectMapper` 换成一个 `private static final ObjectMapper MAPPER = new ObjectMapper();`，新增常量放在 `TYPE_NAMES` 上方），并在类尾追加：

```java
    /** 列表页摘要长度：实测种子标题 10~21 字符，20 既铺得开又能逼出一例真截断 */
    public static final int TITLE_BRIEF_MAX = 20;

    /**
     * 列表页标题摘要（spec 5.2 的 titleBrief）。
     * 折叠空白是必须的：种子数据里有带换行的题干，直接截断会把换行送进卡片的 JSON 串。
     */
    public static String titleBrief(String title) {
        if (title == null) {
            return null;
        }
        String flat = title.replaceAll("\\s+", " ").trim();
        if (flat.length() <= TITLE_BRIEF_MAX) {
            return flat;
        }
        return flat.substring(0, TITLE_BRIEF_MAX - 1) + "…";
    }
```

> `toQuestionVO` 里对 `parseTags` 的调用同步改成 `parseTags(question.getTagsJson())`（静态调用，实例字段 `objectMapper` 若因此不再被使用就删掉，别留死字段）。

- [x] **Step 10：全绿 + 报告改动，停在提交前**

Run: `"$MVN" -o -pl PracticeModule -am test 2>&1 | tail -20`
Expected: `Tests run: 114, Failures: 0`（段 2 基线 101 + `PracticePagesTest` 5 + `AnswerStatusTest` 5（含参数化 3 次调用）+ `PracticeMaskerTest` 新增 3）。数字对不上时以"全绿"为准，并把真实值记进执行记录。

Run: `git status --short -- PracticeModule` → 输出抄进执行记录。**到此为止，不 `git add`/`git commit`。**建议用户提交的分组与信息：

> `段3 Task3.2 三个纯口径助手：分页越界 40000、answerStatus 三态映射、titleBrief 折叠截断`

---

## Task 3.3：`GET /practice/question/page` + 响应体守卫 harness

本任务交付第一个读接口，同时把 spec §9.1.2 要求的"守卫从进程内 VO 序列化升级为真实响应体断言"这套 harness 搭起来——后面三个任务各往里加一个 GET，而不是各写一套。

**Files:**
- Create: `.../pojo/vo/QuestionItemVO.java`
- Create: `.../service/LatestAnswers.java`（两个批量补齐助手，三个服务共用）
- Create: `.../service/QuestionReadService.java`
- Create: `.../service/impl/QuestionReadServiceImpl.java`
- Create: `.../controller/PracticeQuestionController.java`
- Test: `.../service/LatestAnswersTest.java`
- Test: `.../service/QuestionReadServiceTest.java`
- Test: `.../controller/PracticeQuestionControllerTest.java`
- Test: `.../controller/AnswerStateResponseGuardTest.java`

**Interfaces:**
- Consumes: `QuestionPageRow` / `QuestionPositionRow` / `LatestAnswerRow` / `QbUserFavoriteMapper.selectFavoritedQuestionIds` / `PracticePages` / `AnswerStatus`（Task 3.1、3.2）
- Produces:
  - `PageVO<QuestionItemVO> QuestionReadService.page(Long userId, Long subjectId, Long pageNum, Long pageSize)`
  - `QuestionItemVO{Integer index, Long questionId, Integer type, Integer difficulty, List<String> tags, Integer answerStatus, boolean favorited}`
  - `static Map<Long,LatestAnswerRow> LatestAnswers.byQuestion(QbUserAnswerRecordMapper, Long userId, Collection<Long> ids)`
  - `static Map<Long,Long> LatestAnswers.positions(QbQuestionMapper, Collection<Long> ids)`
  - `QuestionReadServiceImpl` 构造器：`(QbQuestionMapper, QbUserAnswerRecordMapper, QbUserFavoriteMapper, QbSubjectMapper)` —— Task 3.4 会追加三个依赖，届时用测试里的 `newImpl()` 工厂集中改一处。

- [x] **Step 1：写 VO 与服务接口**

`pojo/vo/QuestionItemVO.java`：

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.util.List;

/**
 * 题目列表的一行（spec 5.2）。刻意没有 title：答题卡是一屏数字格子，
 * 少一个字段就少一条可能把题干带出去的通道。
 */
@Data
public class QuestionItemVO {

    private Integer index;
    private Long questionId;
    private Integer type;
    private Integer difficulty;
    private List<String> tags;
    /** 0未作答 1最近答对 2最近答错 3待自评（裁定一） */
    private Integer answerStatus;
    private boolean favorited;
}
```

`service/QuestionReadService.java`：

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.QuestionItemVO;

/** 题库读侧（段 3）。current 在 Task 3.4 追加。 */
public interface QuestionReadService {

    PageVO<QuestionItemVO> page(Long userId, Long subjectId, Long pageNum, Long pageSize);
}
```

- [x] **Step 2：写失败测试 `LatestAnswersTest`（空 IN 短路是唯一硬要求）**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QuestionPositionRow;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@DisplayName("批量补齐：空集合绝不进 IN ()")
class LatestAnswersTest {

    private final QbUserAnswerRecordMapper recordMapper = mock(QbUserAnswerRecordMapper.class);
    private final QbQuestionMapper questionMapper = mock(QbQuestionMapper.class);

    @Test
    @DisplayName("ids 为空/null：返回空 Map 且一次库都不访")
    void emptyIdsShortCircuit() {
        // 空 IN 会生成 `WHERE question_id IN ()`，MySQL 直接语法错 -> code=0（在说谎）
        assertTrue(LatestAnswers.byQuestion(recordMapper, 1L, List.of()).isEmpty());
        assertTrue(LatestAnswers.byQuestion(recordMapper, 1L, null).isEmpty());
        assertTrue(LatestAnswers.positions(questionMapper, List.of()).isEmpty());
        assertTrue(LatestAnswers.positions(questionMapper, null).isEmpty());
        verifyNoInteractions(recordMapper, questionMapper);
    }

    @Test
    @DisplayName("有值：一次查询拿全，按 questionId 建索引；重复行以结果集后者为准")
    void buildsMaps() {
        when(recordMapper.selectLatestByQuestions(anyLong(), any()))
                .thenReturn(List.of(new LatestAnswerRow(9L, 2L, "\"A\"", 1, 1)));
        when(questionMapper.selectPositions(any()))
                .thenReturn(List.of(new QuestionPositionRow(2L, 3L)));

        assertEquals(1, LatestAnswers.byQuestion(recordMapper, 1L, List.of(2L, 3L)).size());
        assertEquals(3L, LatestAnswers.positions(questionMapper, List.of(2L)).get(2L));
        verify(recordMapper, times(1)).selectLatestByQuestions(anyLong(), any());
        verify(questionMapper, times(1)).selectPositions(any());
    }
}
```

- [x] **Step 3：跑测试确认失败**

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=LatestAnswersTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -15`
Expected: 编译失败 `找不到符号 LatestAnswers`。

- [x] **Step 4：实现 `LatestAnswers`**

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QuestionPositionRow;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 分页行的批量补齐：一题一行状态 + 一题一个位置序号，各一条 SQL，零 N+1。
 *
 * 为什么是静态工具而不是 bean：它无状态、只有两个方法，做成 bean 会让三个服务构造器
 * 各多一个参数，而单测里传 mock 的 mapper 反而更直观。
 */
public final class LatestAnswers {

    private LatestAnswers() {
    }

    public static Map<Long, LatestAnswerRow> byQuestion(QbUserAnswerRecordMapper mapper,
                                                        Long userId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, LatestAnswerRow> map = new HashMap<>();
        for (LatestAnswerRow row : mapper.selectLatestByQuestions(userId, ids)) {
            map.put(row.questionId(), row);
        }
        return map;
    }

    /** questionId -> 本科目内位置序号（裁定四）。缺行表示该题已下架，调用方按 null 处理 */
    public static Map<Long, Long> positions(QbQuestionMapper mapper, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> map = new HashMap<>();
        for (QuestionPositionRow row : mapper.selectPositions(ids)) {
            map.put(row.questionId(), row.posNo());
        }
        return map;
    }
}
```

- [x] **Step 5：跑测试确认通过**

Run: 同 Step 3。Expected: `Tests run: 2, Failures: 0`。

- [x] **Step 6：写失败测试 `QuestionReadServiceTest`**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserFavoriteMapper;
import org.leon.practicemodule.mapper.QuestionPageRow;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.QuestionItemVO;
import org.leon.practicemodule.service.impl.QuestionReadServiceImpl;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("题目分页：三段式查询与 answerStatus/favorited 补齐")
class QuestionReadServiceTest {

    private static final long USER = 10L;

    private final QbQuestionMapper questionMapper = mock(QbQuestionMapper.class);
    private final QbUserAnswerRecordMapper recordMapper = mock(QbUserAnswerRecordMapper.class);
    private final QbUserFavoriteMapper favoriteMapper = mock(QbUserFavoriteMapper.class);
    private final QbSubjectMapper subjectMapper = mock(QbSubjectMapper.class);

    private final QuestionReadService service =
            new QuestionReadServiceImpl(questionMapper, recordMapper, favoriteMapper, subjectMapper);

    private QbSubject subject(int status) {
        QbSubject s = new QbSubject();
        s.setId(1L);
        s.setStatus(status);
        return s;
    }

    private void subjectExists() {
        when(subjectMapper.selectById(1L)).thenReturn(subject(1));
    }

    private QuestionPageRow row(long id, int type) {
        return new QuestionPageRow(id, type, 2, "[\"语法\"]", (int) id);
    }

    @Test
    @DisplayName("subjectId 为 null 是参数问题 40000；科目不存在 40400；已停用 40900")
    void subjectGuards() {
        // 正常链路下缺参在控制器层就转成 40000 了，服务里的 null 分支是防御性的第二道
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.page(USER, null, null, null)).errorCode());
        when(subjectMapper.selectById(1L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, null, null)).errorCode());
        when(subjectMapper.selectById(1L)).thenReturn(subject(0));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, null, null)).errorCode());
        verifyNoInteractions(questionMapper, recordMapper, favoriteMapper);
    }

    @Test
    @DisplayName("pageSize=101 报 40000，且发生在任何查询之前")
    void pageParamRejectedFirst() {
        subjectExists();
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, 1L, 101L)).errorCode());
        verify(questionMapper, never()).countEnabledBySubject(anyLong());
    }

    @Test
    @DisplayName("total=0：空壳照回，不查分页行")
    void emptySubject() {
        subjectExists();
        when(questionMapper.countEnabledBySubject(1L)).thenReturn(0L);
        PageVO<QuestionItemVO> page = service.page(USER, 1L, 1L, null);
        assertEquals(0L, page.getTotal());
        assertEquals(0L, page.getPages());
        assertTrue(page.getList().isEmpty());
        verify(questionMapper, never()).selectEnabledPage(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(recordMapper, favoriteMapper);
    }

    @Test
    @DisplayName("越界页：total 照回真值，list 空，不查分页行")
    void beyondLastPage() {
        subjectExists();
        when(questionMapper.countEnabledBySubject(1L)).thenReturn(4L);
        PageVO<QuestionItemVO> page = service.page(USER, 1L, 9L, null);
        assertEquals(4L, page.getTotal());
        assertEquals(2L, page.getPages());
        assertTrue(page.getList().isEmpty());
        verify(questionMapper, never()).selectEnabledPage(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("正常页：index=offset+行位，answerStatus 与 favorited 各一条批量查询补齐")
    void normalPage() {
        subjectExists();
        when(questionMapper.countEnabledBySubject(1L)).thenReturn(4L);
        when(questionMapper.selectEnabledPage(1L, 2, 2)).thenReturn(List.of(row(5, 5), row(2, 1)));
        when(recordMapper.selectLatestByQuestions(eq(USER), any()))
                .thenReturn(List.of(new LatestAnswerRow(22L, 5L, "\"我的作答\"", -1, 1)));
        when(favoriteMapper.selectFavoritedQuestionIds(eq(USER), any())).thenReturn(List.of(2L));

        PageVO<QuestionItemVO> page = service.page(USER, 1L, 2L, 2L);

        assertEquals(4L, page.getTotal());
        assertEquals(2L, page.getPages());
        assertEquals(2L, page.getPageNum());
        assertEquals(2, page.getList().size());
        QuestionItemVO first = page.getList().get(0);
        assertEquals(3, first.getIndex(), "offset 2 起第一行 → 第 3 题");
        assertEquals(5L, first.getQuestionId());
        assertEquals(AnswerStatus.PENDING_SELF_JUDGE, first.getAnswerStatus());
        assertEquals(List.of("语法"), first.getTags());
        assertFalse(first.isFavorited());
        QuestionItemVO second = page.getList().get(1);
        assertEquals(4, second.getIndex());
        assertEquals(AnswerStatus.UNANSWERED, second.getAnswerStatus(), "没记录就是未作答");
        assertTrue(second.isFavorited());

        // 三段式：1 次计数 + 1 次分页 + 各 1 次批量补齐，共 4 条 SQL，没有循环查询
        verify(questionMapper, times(1)).countEnabledBySubject(1L);
        verify(questionMapper, times(1)).selectEnabledPage(anyLong(), anyLong(), anyLong());
        verify(recordMapper, times(1)).selectLatestByQuestions(anyLong(), any());
        verify(favoriteMapper, times(1)).selectFavoritedQuestionIds(anyLong(), any());
    }

    @Test
    @DisplayName("计数与取行之间被并发下架：空行集不打 IN ()")
    void concurrentShrinkDoesNotReachIn() {
        subjectExists();
        when(questionMapper.countEnabledBySubject(1L)).thenReturn(4L);
        when(questionMapper.selectEnabledPage(1L, 0, 20)).thenReturn(List.of());
        PageVO<QuestionItemVO> page = service.page(USER, 1L, 1L, 20L);
        assertTrue(page.getList().isEmpty());
        verifyNoInteractions(recordMapper, favoriteMapper);
    }

    @Test
    @DisplayName("answerStatus 四种取值都在同一行上映射正确")
    void allStatuses() {
        subjectExists();
        when(questionMapper.countEnabledBySubject(1L)).thenReturn(4L);
        when(questionMapper.selectEnabledPage(1L, 0, 20)).thenReturn(
                List.of(row(1, 1), row(2, 2), row(3, 3), row(4, 4)));
        when(recordMapper.selectLatestByQuestions(eq(USER), any())).thenReturn(List.of(
                new LatestAnswerRow(1L, 1L, "\"A\"", 1, 1),
                new LatestAnswerRow(2L, 2L, "\"[\\\"A\\\"]\"", 0, 1),
                new LatestAnswerRow(3L, 3L, "\"true\"", -1, 1)));
        when(favoriteMapper.selectFavoritedQuestionIds(eq(USER), any())).thenReturn(List.of());

        List<QuestionItemVO> items = service.page(USER, 1L, 1L, 20L).getList();
        assertEquals(AnswerStatus.CORRECT, items.get(0).getAnswerStatus());
        assertEquals(AnswerStatus.WRONG, items.get(1).getAnswerStatus());
        assertEquals(AnswerStatus.PENDING_SELF_JUDGE, items.get(2).getAnswerStatus());
        assertEquals(AnswerStatus.UNANSWERED, items.get(3).getAnswerStatus());
    }
}
```

- [x] **Step 7：跑测试确认失败**（`QuestionReadServiceImpl` 还不存在，编译失败即红灯）

- [x] **Step 8：实现 `QuestionReadServiceImpl`（本任务只放 page）**

```java
package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserFavoriteMapper;
import org.leon.practicemodule.mapper.QuestionPageRow;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.QuestionItemVO;
import org.leon.practicemodule.service.AnswerStatus;
import org.leon.practicemodule.service.LatestAnswers;
import org.leon.practicemodule.service.PracticeMasker;
import org.leon.practicemodule.service.PracticePages;
import org.leon.practicemodule.service.QuestionReadService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 题库读侧。三段式：计数 → 一页行 → 批量补齐，任何列表都不在循环里查库。
 * pageSize 上限 100，所以每次批量补齐的 IN 列表最多 100 项。
 */
@Service
public class QuestionReadServiceImpl implements QuestionReadService {

    private final QbQuestionMapper questionMapper;
    private final QbUserAnswerRecordMapper recordMapper;
    private final QbUserFavoriteMapper favoriteMapper;
    private final QbSubjectMapper subjectMapper;

    public QuestionReadServiceImpl(QbQuestionMapper questionMapper,
                                   QbUserAnswerRecordMapper recordMapper,
                                   QbUserFavoriteMapper favoriteMapper,
                                   QbSubjectMapper subjectMapper) {
        this.questionMapper = questionMapper;
        this.recordMapper = recordMapper;
        this.favoriteMapper = favoriteMapper;
        this.subjectMapper = subjectMapper;
    }

    @Override
    public PageVO<QuestionItemVO> page(Long userId, Long subjectId, Long pageNum, Long pageSize) {
        PracticePages.PageParam param = PracticePages.of(pageNum, pageSize);
        requireEnabledSubject(subjectId);

        long total = questionMapper.countEnabledBySubject(subjectId);
        if (total == 0 || param.offset() >= total) {
            // 越界页不回空 total：前端要拿 total 算总页数，这里给真值才有意义
            return PageVO.of(List.of(), total, param.pageNum(), param.pageSize());
        }
        List<QuestionPageRow> rows =
                questionMapper.selectEnabledPage(subjectId, param.offset(), param.pageSize());
        return PageVO.of(toItems(userId, param.offset(), rows), total, param.pageNum(), param.pageSize());
    }

    private List<QuestionItemVO> toItems(Long userId, long offset, List<QuestionPageRow> rows) {
        List<Long> ids = rows.stream().map(QuestionPageRow::questionId).toList();
        Map<Long, LatestAnswerRow> latest = LatestAnswers.byQuestion(recordMapper, userId, ids);
        Set<Long> favorited = ids.isEmpty()
                ? Set.of()
                : new HashSet<>(favoriteMapper.selectFavoritedQuestionIds(userId, ids));

        List<QuestionItemVO> items = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            QuestionPageRow row = rows.get(i);
            QuestionItemVO item = new QuestionItemVO();
            // 页内序号可直接算：本页顺序就是 (sort_order,id) 全序，offset 上限 1e8 落在 int 内
            item.setIndex((int) (offset + i + 1));
            item.setQuestionId(row.questionId());
            item.setType(row.type());
            item.setDifficulty(row.difficulty());
            item.setTags(PracticeMasker.parseTags(row.tagsJson()));
            item.setAnswerStatus(AnswerStatus.of(latest.get(row.questionId())));
            item.setFavorited(favorited.contains(row.questionId()));
            items.add(item);
        }
        return items;
    }

    void requireEnabledSubject(Long subjectId) {
        if (subjectId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbSubject subject = subjectMapper.selectById(subjectId);
        if (subject == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "科目不存在");
        }
        if (subject.getStatus() == null || subject.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "科目已停用");
        }
    }
}
```

- [x] **Step 9：跑测试确认通过**

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=QuestionReadServiceTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -15`
Expected: `Tests run: 7, Failures: 0`。

- [x] **Step 10：控制器 + 控制器切片测试**

`controller/PracticeQuestionController.java`：

```java
package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.QuestionItemVO;
import org.leon.practicemodule.service.QuestionReadService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题库读接口。身份只从 X-User-Name 换（网关注入），query 里的 userId 一概不看。
 * subjectId 是必填 query：缺参数会抛 MissingServletRequestParameterException，
 * 由 PracticeExceptionHandler 统一转 40000。
 */
@RestController
@RequestMapping("/practice/question")
public class PracticeQuestionController {

    private final QuestionReadService questionReadService;
    private final UserIdentityService userIdentityService;

    public PracticeQuestionController(QuestionReadService questionReadService,
                                      UserIdentityService userIdentityService) {
        this.questionReadService = questionReadService;
        this.userIdentityService = userIdentityService;
    }

    @GetMapping("/page")
    public Result<PageVO<QuestionItemVO>> page(@RequestHeader("X-User-Name") String userName,
                                               @RequestParam Long subjectId,
                                               @RequestParam(required = false) Long pageNum,
                                               @RequestParam(required = false) Long pageSize) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(questionReadService.page(userId, subjectId, pageNum, pageSize));
    }
}
```

`controller/PracticeQuestionControllerTest.java`（照段 2 的 `PracticeAnswerControllerTest` 写法：控制器只做身份换取与 `Result` 包装）：

```java
package org.leon.practicemodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.QuestionItemVO;
import org.leon.practicemodule.service.QuestionReadService;
import org.leon.practicemodule.service.UserIdentityService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PracticeQuestionControllerTest {

    private final QuestionReadService readService = mock(QuestionReadService.class);
    private final UserIdentityService identity = mock(UserIdentityService.class);
    private final PracticeQuestionController controller = new PracticeQuestionController(readService, identity);

    @Test
    @DisplayName("page：身份换取后交给 Service，结果包 Result")
    void pageWrapsInResult() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(readService.page(eq(10L), eq(1L), any(), any()))
                .thenReturn(PageVO.of(List.of(), 0L, 1L, 20L));

        Result<PageVO<QuestionItemVO>> r = controller.page("alice", 1L, null, null);

        assertEquals(1, r.getCode());
        assertEquals(0L, r.getData().getTotal());
    }

    @Test
    @DisplayName("身份解析失败向上抛给 advice，控制器不吞")
    void propagatesIdentityFailure() {
        when(identity.resolveUserId("ghost")).thenThrow(
                new PracticeException(PracticeErrorCode.IDENTITY_MISSING, "用户身份不存在，请重新登录"));
        assertEquals(PracticeErrorCode.IDENTITY_MISSING,
                assertThrows(PracticeException.class, () -> controller.page("ghost", 1L, null, null)).errorCode());
        verify(readService, never()).page(anyLong(), any(), any(), any());
    }
}
```

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=PracticeQuestionControllerTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -15`
Expected: `Tests run: 2, Failures: 0`。

- [x] **Step 11：写响应体守卫 harness（含非空转对照）**

这条测试的价值在于它扫的是**字节**，而且走的是真服务 + 真脱敏器 + mock 库——把复制器写错它就红，把 VO 留空它也不会绿。

```java
package org.leon.practicemodule.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserFavoriteMapper;
import org.leon.practicemodule.mapper.QuestionPageRow;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.service.PracticeMasker;
import org.leon.practicemodule.service.QuestionReadService;
import org.leon.practicemodule.service.UserIdentityService;
import org.leon.practicemodule.service.impl.QuestionReadServiceImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.leon.practicemodule.exception.PracticeExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 脱敏守卫跑在真实响应体上（spec 9.1.2 段 3 升级，段 2 只跑到 VO 的进程内序列化）。
 *
 * 组装方式：真控制器 + 真服务 + 真 PracticeMasker + mock 的 mapper。
 * 为什么不用 @SpringBootTest：那会去拉 Nacos/Redis/Seata；standalone MockMvc
 * 已经把"控制器 → advice → Jackson → 字节"这一段全走一遍，够守卫用。
 *
 * 读响应用 getContentAsByteArray + UTF-8 手工解码：MockHttpServletResponse 的默认
 * 字符集是 ISO-8859-1，直接 getContentAsString() 会把中文读成问号，正向锚点就会假失败。
 */
@DisplayName("答题态响应体不含任何答案字段")
class AnswerStateResponseGuardTest {

    private static final String ANSWER_SENTINEL = "ANSWER_SENTINEL_GUARD";
    private static final String ANALYSIS_SENTINEL = "ANALYSIS_SENTINEL_GUARD";

    private QbQuestionMapper questionMapper;
    private QbUserAnswerRecordMapper recordMapper;
    private QbUserFavoriteMapper favoriteMapper;
    private QbSubjectMapper subjectMapper;
    private MockMvc mockMvc;

    private static String body(MvcResult result) throws Exception {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @BeforeEach
    void wireRealServicesOverMockMappers() {
        questionMapper = mock(QbQuestionMapper.class);
        recordMapper = mock(QbUserAnswerRecordMapper.class);
        favoriteMapper = mock(QbUserFavoriteMapper.class);
        subjectMapper = mock(QbSubjectMapper.class);
        UserIdentityService identity = mock(UserIdentityService.class);
        when(identity.resolveUserId("alice")).thenReturn(10L);

        QbSubject subject = new QbSubject();
        subject.setId(1L);
        subject.setStatus(1);
        when(subjectMapper.selectById(1L)).thenReturn(subject);

        QuestionReadService readService = new QuestionReadServiceImpl(
                questionMapper, recordMapper, favoriteMapper, subjectMapper);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PracticeQuestionController(readService, identity))
                .setControllerAdvice(new PracticeExceptionHandler())
                .build();
    }

    /** 每个答题态接口都跑这一套。清单只有一份，来自 PracticeMasker */
    private void assertNoAnswerFields(String responseBody, String anchor) {
        assertTrue(responseBody.contains(anchor),
                "响应体里没有本接口自己的字段 [" + anchor + "]，说明没打到真接口，负向断言是空转：" + responseBody);
        for (String forbidden : PracticeMasker.FORBIDDEN_IN_ANSWER_STATE) {
            assertFalse(responseBody.contains(forbidden), "响应体泄漏了 " + forbidden + "：" + responseBody);
        }
        assertFalse(responseBody.contains(ANSWER_SENTINEL), "答案内容换了字段名仍外泄：" + responseBody);
        assertFalse(responseBody.contains(ANALYSIS_SENTINEL), "解析内容换了字段名仍外泄：" + responseBody);
    }

    private MvcResult get(String path) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .header("X-User-Name", "alice"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
    }

    @Test
    @DisplayName("question/page 的响应体干净")
    void questionPageResponseIsClean() throws Exception {
        when(questionMapper.countEnabledBySubject(1L)).thenReturn(1L);
        when(questionMapper.selectEnabledPage(eq(1L), anyLong(), anyLong()))
                .thenReturn(List.of(new QuestionPageRow(1L, 1, 2, "[\"语法\"]", 1)));
        String json = body(get("/practice/question/page?subjectId=1"));
        assertNoAnswerFields(json, "\"favorited\"");
    }

    @Test
    @DisplayName("缺 subjectId → 40000，而不是兜底的服务器繁忙")
    void missingParamIsClientError() throws Exception {
        String json = body(get("/practice/question/page"));
        assertTrue(json.contains("\"code\":40000"), "缺参应转 40000：" + json);
    }

    @Test
    @DisplayName("非空转对照：真泄漏时这套断言必须红")
    void guardCatchesRawEntityResponse() throws Exception {
        MockMvc leaky = MockMvcBuilders.standaloneSetup(new LeakController())
                .setControllerAdvice(new PracticeExceptionHandler()).build();
        String json = new String(leaky.perform(MockMvcRequestBuilders.get("/leak"))
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(json.contains("answerJson") && json.contains(ANALYSIS_SENTINEL),
                "把实体直接返回都抓不到，说明这套扫描是永远绿的空断言");
        for (String forbidden : PracticeMasker.FORBIDDEN_IN_ANSWER_STATE) {
            assertTrue(json.contains(forbidden) || !forbidden.equals("answerJson"),
                    "对照接口至少该带着 answerJson：" + json);
        }
    }

    /** 只用于对照实验的"错误示范"：把实体当响应直接吐出去 */
    @RestController
    static class LeakController {
        @GetMapping("/leak")
        public Result<QbQuestion> leak() {
            QbQuestion q = new QbQuestion();
            q.setId(1L);
            q.setSubjectId(1L);
            q.setType(1);
            q.setTitle("题干");
            q.setStatus(1);
            q.setAnswerJson("\"" + ANSWER_SENTINEL + "\"");
            q.setAnalysis(ANALYSIS_SENTINEL);
            return Result.success(q);
        }
    }
}
```

> 对照实验里那句 `assertTrue(json.contains(forbidden) || !forbidden.equals("answerJson"))` 是刻意只钉 `answerJson` 一个名的（其余名字在实体上未必都在）。执行时若觉得绕，可替换为更直接的写法：`assertTrue(json.contains("answerJson"))` + `assertTrue(json.contains(ANALYSIS_SENTINEL))`，两条足够表达"扫描有牙"。

- [x] **Step 12：跑守卫 + 全量回归**

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=AnswerStateResponseGuardTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20`
Expected: `Tests run: 3, Failures: 0`。**必须先看到红灯一次**：临时把 `PracticeMasker.toQuestionVO` 里加一行 `vo.setTitle(question.getAnalysis())` 之类的改动无关紧要——真正要验的是把 `LeakController` 的断言跑红的那套扫描，也能把 `question/page` 跑红。做法：临时让 `QuestionItemVO` 多一个 `answerJson` 字段并在 `toItems` 里塞 `ANSWER_SENTINEL`，跑本类应当失败；确认后删掉这段临时改动。**把这条伪阳性的执行输出记进执行记录**（没有它，守卫是不是有牙就只是嘴上说说）。

Run: `"$MVN" -o -pl PracticeModule -am test 2>&1 | tail -20`
Expected: 全绿，比 Task 3.2 结束时多 `LatestAnswersTest` 2 + `QuestionReadServiceTest` 7 + 控制器 2 + 守卫 3 = 14 条。

- [x] **Step 13：报告改动，停在提交前**

Run: `git status --short -- PracticeModule`
Expected: 只出现本任务的九个文件——

```
?? .../pojo/vo/QuestionItemVO.java
?? .../service/LatestAnswers.java
?? .../service/QuestionReadService.java
?? .../service/impl/QuestionReadServiceImpl.java
?? .../controller/PracticeQuestionController.java
?? .../service/LatestAnswersTest.java
?? .../service/QuestionReadServiceTest.java
?? .../controller/PracticeQuestionControllerTest.java
?? .../controller/AnswerStateResponseGuardTest.java
```

**到此为止：不 `git add`、不 `git commit`。**建议提交信息：`段3 Task3.3 题目分页接口与响应体脱敏守卫harness（含伪阳性对照）`。若 `git status` 里冒出 `AIModule/**`，那是另一会话的改动，别动它。

---

## Task 3.4：`GET /practice/question/current`（断点续做 / 智能练习 / 指定题）

本任务同时补段 2 留下的洞：刷新页面后前端必须能拿回 `pendingRecordId`，否则 self-judge 在真实前端里不可达（裁定一）。

**Files:**
- Create: `.../pojo/vo/QuestionCurrentVO.java`
- Modify: `.../service/QuestionReadService.java`（+`current`）
- Modify: `.../service/impl/QuestionReadServiceImpl.java`（构造器 +3 依赖：`QbUserWrongMapper`、`QbQuestionOptionMapper`、`PracticeMasker`）
- Modify: `.../controller/PracticeQuestionController.java`（+`/current`）
- Test: `.../service/QuestionReadServiceTest.java`（追加 9 个用例，并把构造改为工厂）
- Test: `.../controller/PracticeQuestionControllerTest.java`（追加 1 个）
- Test: `.../controller/AnswerStateResponseGuardTest.java`（追加 current 一条，wire 真取题路径）

**Interfaces:**
- Consumes: `selectFirstUnansweredQuestionId` / `selectSmartCandidateQuestionId` / `selectDisplayOptions` / `LatestAnswers` / `AnswerStatus` / `PracticeMasker.toQuestionVO`
- Produces:
  - `QuestionCurrentVO QuestionReadService.current(Long userId, Long subjectId, Integer mode, Long questionId)`
  - `QuestionCurrentVO{boolean finished, QuestionVO question, Integer answerStatus, String lastUserAnswerJson, Long pendingRecordId}`
  - `QuestionReadServiceImpl` 构造器（7 参，段 5 的 `ExamServiceImpl` 也用同一个 masker 出口）：
    `(QbQuestionMapper, QbUserAnswerRecordMapper, QbUserFavoriteMapper, QbSubjectMapper, QbUserWrongMapper, QbQuestionOptionMapper, PracticeMasker)`

**响应形状为什么嵌套而不是平铺（对 spec §5.2 的一处形态偏差）**：spec 写的是"脱敏单题 + `index,answerStatus,lastUserAnswerJson?`"平铺。平铺要在 `QuestionVO` 上加三个字段，而 `QuestionVO` 同时被段 5 的 `exam/question` 用——那里既没有 `answerStatus` 也没有 `finished`。更关键的是 `finished:true` 时压根没有题可平铺。所以定 `data = {finished, question, answerStatus, lastUserAnswerJson, pendingRecordId}`：`PracticeMasker` 仍是唯一复制器（本段不出现第二个），`index` 留在 `question.index`（段 2 的注释就写着"段 3 填入"）。

- [x] **Step 1：写 VO 与接口方法**

`pojo/vo/QuestionCurrentVO.java`：

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/**
 * 取题响应（spec 5.2 的"脱敏单题"）。
 * finished=true 时 question 为 null（本科目已刷完），其余字段也全为 null——
 * 前端据此显示"这一科已经刷完，要不要看错题本"。
 */
@Data
public class QuestionCurrentVO {

    private boolean finished;
    /** 唯一出口 PracticeMasker 的产物；答题态的所有题目字段都在它身上 */
    private QuestionVO question;
    /** 0未作答 1最近答对 2最近答错 3待自评（裁定一） */
    private Integer answerStatus;
    /** 我自己上次答了什么（spec 5.4 明确允许：不是真值） */
    private String lastUserAnswerJson;
    /** 仅"待自评 + 练习类作答"才非空（裁定三）：给了注定 40000 的 recordId 比不给更糟 */
    private Long pendingRecordId;
}
```

`QuestionReadService` 追加：

```java
    /**
     * 取一道题。二选一：subjectId+mode（1顺序 3智能）走断点/优先题，questionId 指定题。
     * 两个都给或都不给都是 40000——歧义参数不该由后端猜。
     */
    QuestionCurrentVO current(Long userId, Long subjectId, Integer mode, Long questionId);
```

- [x] **Step 2：把测试构造改成工厂并写失败测试**

在 `QuestionReadServiceTest` 里，把原来的 `private final QuestionReadService service = new QuestionReadServiceImpl(...)` 一行替换为：

```java
    private final QbUserWrongMapper wrongMapper = mock(QbUserWrongMapper.class);
    private final QbQuestionOptionMapper optionMapper = mock(QbQuestionOptionMapper.class);
    private final QuestionReadService service = newImpl();

    private QuestionReadServiceImpl newImpl() {
        return new QuestionReadServiceImpl(questionMapper, recordMapper, favoriteMapper, subjectMapper,
                wrongMapper, optionMapper, new PracticeMasker());
    }

    private QbQuestion question(long id, int status) {
        QbQuestion q = new QbQuestion();
        q.setId(id);
        q.setSubjectId(1L);
        q.setType(1);
        q.setTitle("题干" + id);
        q.setDifficulty(2);
        q.setStatus(status);
        q.setAnswerJson("\"A\"");
        q.setAnalysis("解析，绝不能外泄");
        q.setTagsJson("[\"语法\"]");
        return q;
    }

    private QbQuestionOption option(String key, int isCorrect) {
        QbQuestionOption o = new QbQuestionOption();
        o.setQuestionId(1L);
        o.setOptionKey(key);
        o.setContent("选项" + key);
        o.setIsCorrect(isCorrect);
        return o;
    }
```

追加九个用例：

```java
    @Test
    @DisplayName("current：参数二选一，都给或都不给都是 40000，且零查询")
    void currentParamExclusive() {
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.current(USER, null, null, null)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.current(USER, 1L, 1, 1L)).errorCode());
        verifyNoInteractions(questionMapper, recordMapper, favoriteMapper, subjectMapper, wrongMapper, optionMapper);
    }

    @Test
    @DisplayName("current：mode 只认 1 与 3；2（考试）在逐题取题里是 40000")
    void currentModeRejected() {
        when(subjectMapper.selectById(1L)).thenReturn(subject(1));
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.current(USER, 1L, 2, null)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.current(USER, 1L, null, null)).errorCode());
        verify(questionMapper, never()).selectFirstUnansweredQuestionId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("current：指定题不存在 40400，已下架 40900")
    void currentQuestionGuards() {
        when(questionMapper.selectById(9L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.current(USER, null, null, 9L)).errorCode());
        when(questionMapper.selectById(9L)).thenReturn(question(9, 0));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.current(USER, null, null, 9L)).errorCode());
    }

    @Test
    @DisplayName("current：指定题走脱敏出口，index 由位置子查询给，answerStatus 与上次作答同步带回")
    void currentByQuestionId() {
        when(questionMapper.selectById(1L)).thenReturn(question(1, 1));
        when(optionMapper.selectDisplayOptions(1L)).thenReturn(List.of(option("A", 1), option("B", 0)));
        when(questionMapper.selectPositions(any())).thenReturn(List.of(new QuestionPositionRow(1L, 2L)));
        when(recordMapper.selectLatestByQuestions(eq(USER), any()))
                .thenReturn(List.of(new LatestAnswerRow(77L, 1L, "\"C\"", 0, 1)));

        QuestionCurrentVO vo = service.current(USER, null, null, 1L);

        assertFalse(vo.isFinished());
        assertEquals(1L, vo.getQuestion().getQuestionId());
        assertEquals("题干1", vo.getQuestion().getTitle());
        assertEquals("单选题", vo.getQuestion().getTypeName());
        assertEquals(2, vo.getQuestion().getOptions().size());
        assertEquals(2, vo.getQuestion().getIndex(), "index 是位置序号 2，不是 sort_order");
        assertEquals(AnswerStatus.WRONG, vo.getAnswerStatus());
        assertEquals("\"C\"", vo.getLastUserAnswerJson());
        assertNull(vo.getPendingRecordId(), "已判过的记录没有自评这回事");
        assertEquals("A", vo.getQuestion().getOptions().get(0).getOptionKey());
    }

    @Test
    @DisplayName("current：待自评 + 练习记录才回 pendingRecordId；考试的 -1 不回")
    void pendingRecordIdGate() {
        when(questionMapper.selectById(5L)).thenReturn(question(5, 1));
        when(questionMapper.selectPositions(any())).thenReturn(List.of(new QuestionPositionRow(5L, 4L)));
        when(recordMapper.selectLatestByQuestions(eq(USER), any()))
                .thenReturn(List.of(new LatestAnswerRow(22L, 5L, "\"我的作答\"", -1, 1)));

        QuestionCurrentVO practice = service.current(USER, null, null, 5L);
        assertEquals(AnswerStatus.PENDING_SELF_JUDGE, practice.getAnswerStatus());
        assertEquals(22L, practice.getPendingRecordId(), "刷新页面后能继续自评，全靠这个 id");

        when(recordMapper.selectLatestByQuestions(eq(USER), any()))
                .thenReturn(List.of(new LatestAnswerRow(23L, 5L, "\"我的作答\"", -1, 2)));
        QuestionCurrentVO exam = service.current(USER, null, null, 5L);
        assertEquals(AnswerStatus.PENDING_SELF_JUDGE, exam.getAnswerStatus());
        assertNull(exam.getPendingRecordId(), "mode=2 走 self-judge 必然 40000，不能回按钮");
    }

    @Test
    @DisplayName("current mode=1：走 NOT EXISTS 断点，绝不查错题表")
    void sequentialBreakpoint() {
        when(subjectMapper.selectById(1L)).thenReturn(subject(1));
        when(questionMapper.selectFirstUnansweredQuestionId(1L, USER)).thenReturn(2L);
        when(questionMapper.selectById(2L)).thenReturn(question(2, 1));
        when(questionMapper.selectPositions(any())).thenReturn(List.of(new QuestionPositionRow(2L, 3L)));

        QuestionCurrentVO vo = service.current(USER, 1L, 1, null);

        assertEquals(2L, vo.getQuestion().getQuestionId());
        assertEquals(3, vo.getQuestion().getIndex());
        verify(wrongMapper, never()).selectSmartCandidateQuestionId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("current mode=3：未掌握错题优先，有候选就不看断点")
    void smartPrefersWrongBook() {
        when(subjectMapper.selectById(1L)).thenReturn(subject(1));
        when(wrongMapper.selectSmartCandidateQuestionId(USER, 1L)).thenReturn(1L);
        when(questionMapper.selectById(1L)).thenReturn(question(1, 1));

        assertEquals(1L, service.current(USER, 1L, 3, null).getQuestion().getQuestionId());
        verify(questionMapper, never()).selectFirstUnansweredQuestionId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("current mode=3：没有未掌握错题时回落到顺序断点")
    void smartFallsBackToBreakpoint() {
        when(subjectMapper.selectById(1L)).thenReturn(subject(1));
        when(wrongMapper.selectSmartCandidateQuestionId(USER, 1L)).thenReturn(null);
        when(questionMapper.selectFirstUnansweredQuestionId(1L, USER)).thenReturn(6L);
        when(questionMapper.selectById(6L)).thenReturn(question(6, 1));

        assertEquals(6L, service.current(USER, 1L, 3, null).getQuestion().getQuestionId());
    }

    @Test
    @DisplayName("current：刷完了回 finished=true，question 为 null，且一个脱敏器都不走")
    void finished() {
        when(subjectMapper.selectById(1L)).thenReturn(subject(1));
        when(questionMapper.selectFirstUnansweredQuestionId(1L, USER)).thenReturn(null);

        QuestionCurrentVO vo = service.current(USER, 1L, 1, null);

        assertTrue(vo.isFinished());
        assertNull(vo.getQuestion());
        assertNull(vo.getAnswerStatus());
        assertNull(vo.getPendingRecordId());
        verifyNoInteractions(optionMapper);
        verify(questionMapper, never()).selectById(any());
    }
```

补 import：`org.leon.practicemodule.mapper.*`（`QbUserWrongMapper`、`QbQuestionOptionMapper`、`QuestionPositionRow`）、`org.leon.practicemodule.pojo.QbQuestion`、`org.leon.practicemodule.pojo.QbQuestionOption`、`org.leon.practicemodule.pojo.vo.QuestionCurrentVO`。

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=QuestionReadServiceTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -15`
Expected: 编译失败（`current` 与 7 参构造器不存在）。

- [x] **Step 3：实现 `current`**

`QuestionReadServiceImpl` 加字段与构造参数（`QbUserWrongMapper wrongMapper`、`QbQuestionOptionMapper optionMapper`、`PracticeMasker masker`），并追加：

```java
    @Override
    public QuestionCurrentVO current(Long userId, Long subjectId, Integer mode, Long questionId) {
        if (subjectId == null && questionId == null) {
            throw badRequest("subjectId 与 questionId 至少给一个");
        }
        if (subjectId != null && questionId != null) {
            throw badRequest("subjectId 与 questionId 只能给一个");
        }
        if (questionId != null) {
            return buildCurrent(userId, loadVisibleQuestion(questionId));
        }

        requireEnabledSubject(subjectId);
        if (mode == null || (mode != 1 && mode != 3)) {
            throw badRequest("答题模式只能是 1顺序 或 3智能练习");
        }
        Long nextId = mode == 3 ? wrongMapper.selectSmartCandidateQuestionId(userId, subjectId) : null;
        if (nextId == null) {
            // 智能练习没有未掌握错题就退化成顺序，与 spec 截图里"继续刷题"的直觉一致
            nextId = questionMapper.selectFirstUnansweredQuestionId(subjectId, userId);
        }
        if (nextId == null) {
            QuestionCurrentVO done = new QuestionCurrentVO();
            done.setFinished(true);
            return done;
        }
        return buildCurrent(userId, loadVisibleQuestion(nextId));
    }

    private QuestionCurrentVO buildCurrent(Long userId, QbQuestion question) {
        QuestionVO vo = masker.toQuestionVO(question, optionMapper.selectDisplayOptions(question.getId()));
        Long pos = LatestAnswers.positions(questionMapper, List.of(question.getId())).get(question.getId());
        vo.setIndex(pos == null ? null : pos.intValue());

        LatestAnswerRow latest = LatestAnswers.byQuestion(
                recordMapper, userId, List.of(question.getId())).get(question.getId());
        QuestionCurrentVO out = new QuestionCurrentVO();
        out.setQuestion(vo);
        out.setAnswerStatus(AnswerStatus.of(latest));
        out.setLastUserAnswerJson(latest == null ? null : latest.userAnswerJson());
        out.setPendingRecordId(AnswerStatus.selfJudgeable(latest) ? latest.recordId() : null);
        return out;
    }

    private QbQuestion loadVisibleQuestion(Long questionId) {
        QbQuestion question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在");
        }
        if (question.getStatus() == null || question.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架");
        }
        return question;
    }

    private static PracticeException badRequest(String msg) {
        return new PracticeException(PracticeErrorCode.BAD_REQUEST, msg);
    }
```

> `page` 里的两处 `throw new PracticeException(PracticeErrorCode.BAD_REQUEST, ...)` 换用 `badRequest(...)`，与段 2 的 `AnswerServiceImpl` 保持同一写法。`requireEnabledSubject` 从 package-private 保持原样——`WrongBookServiceImpl` / `FavoriteServiceImpl` 各调自己的 `subjectMapper`，不共享（三个服务共用一个校验器 bean 是过度设计，一行 `selectById` 判两次状态而已）。

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=QuestionReadServiceTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -15`
Expected: `Tests run: 16, Failures: 0`（Task 3.3 的 7 + 本任务 9）。

- [x] **Step 4：控制器加 `/current`**

```java
    @GetMapping("/current")
    public Result<QuestionCurrentVO> current(@RequestHeader("X-User-Name") String userName,
                                             @RequestParam(required = false) Long subjectId,
                                             @RequestParam(required = false) Integer mode,
                                             @RequestParam(required = false) Long questionId) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(questionReadService.current(userId, subjectId, mode, questionId));
    }
```

`PracticeQuestionControllerTest` 追加：

```java
    @Test
    @DisplayName("current：三种入参都原样透传给 Service")
    void currentPassesThrough() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        QuestionCurrentVO vo = new QuestionCurrentVO();
        vo.setFinished(true);
        when(readService.current(eq(10L), eq(1L), eq(1), isNull())).thenReturn(vo);

        assertEquals(1, controller.current("alice", 1L, 1, null).getCode());
        verify(readService).current(10L, 1L, 1, null);
    }
```

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=PracticeQuestionControllerTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -15`
Expected: `Tests run: 3, Failures: 0`。

- [x] **Step 5：守卫 harness 加 `question/current`（本段最有牙的一条）**

取题路径是"实体 → 真 `PracticeMasker` → 字节"全活的一条，所以它才真能抓住复制器写错。`AnswerStateResponseGuardTest` 追加：

```java
    @Test
    @DisplayName("question/current 的响应体：选项不带 isCorrect，题干不带答案与解析")
    void questionCurrentResponseIsClean() throws Exception {
        QbQuestion q = new QbQuestion();
        q.setId(1L);
        q.setSubjectId(1L);
        q.setType(1);
        q.setTitle("题干甲");
        q.setDifficulty(2);
        q.setStatus(1);
        q.setAnswerJson("\"" + ANSWER_SENTINEL + "\"");
        q.setAnalysis(ANALYSIS_SENTINEL);
        q.setTagsJson("[\"语法\"]");
        when(questionMapper.selectById(1L)).thenReturn(q);
        when(optionMapper.selectDisplayOptions(1L)).thenReturn(List.of(
                option("A", "选项甲", 1), option("B", "选项乙", 0)));
        when(questionMapper.selectPositions(any()))
                .thenReturn(List.of(new QuestionPositionRow(1L, 1L)));
        when(recordMapper.selectLatestByQuestions(eq(10L), any()))
                .thenReturn(List.of(new LatestAnswerRow(22L, 1L, "\"我的上次作答\"", -1, 1)));

        String json = body(get("/practice/question/current?questionId=1"));

        assertNoAnswerFields(json, "\"题干甲\"");
        assertTrue(json.contains("\"index\":1"), "index 没填进响应：" + json);
        assertTrue(json.contains("\"pendingRecordId\":22"), "待自评的 recordId 没带回，自评入口会不可达：" + json);
        assertTrue(json.contains("我的上次作答"), "lastUserAnswerJson 是允许的用户自身数据：" + json);
    }
```

其中 `option(...)` 是本类新增的私有小工厂（`mock` 的 `optionMapper` 要在 `wireRealServicesOverMockMappers()` 里加进去并传给 `QuestionReadServiceImpl` 的 7 参构造器；`wrongMapper` 同样 `mock` 后传入）：

```java
    private static QbQuestionOption option(String key, String content, int isCorrect) {
        QbQuestionOption o = new QbQuestionOption();
        o.setOptionKey(key);
        o.setContent(content);
        o.setIsCorrect(isCorrect);
        return o;
    }
```

> 这条测试的 `isCorrect` 哨兵是关键：`QuestionVO.OptionVO` 只要多一个 `isCorrect` 字段并被 `toOptions` 赋值，响应字节里就会出现 `"isCorrect"`，守卫立刻红。
>
> **执行时先做一次伪阳性验证**：临时给 `QuestionVO.OptionVO` 加 `private Integer isCorrect;`，在 `PracticeMasker.toOptions` 里加 `ov.setIsCorrect(option.getIsCorrect());`，跑本类**应当失败**（报"响应体泄漏了 isCorrect"）；确认失败后回滚这两处临时改动，把失败输出粘进执行记录。没有这一步，"守卫有牙"只是嘴上说说。

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=AnswerStateResponseGuardTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20`
Expected: `Tests run: 4, Failures: 0`。

- [x] **Step 6：全量回归 + 报告改动，停在提交前**

Run: `"$MVN" -o -pl PracticeModule -am test 2>&1 | tail -20` → 全绿。
Run: `git status --short -- PracticeModule` → 应只见七个文件：`QuestionCurrentVO.java`、`QuestionReadService.java`、`QuestionReadServiceImpl.java`、`PracticeQuestionController.java`、`QuestionReadServiceTest.java`、`PracticeQuestionControllerTest.java`、`AnswerStateResponseGuardTest.java`。

**到此为止：不 `git add`、不 `git commit`。**建议提交信息：`段3 Task3.4 取题接口：NOT EXISTS 断点、智能练习优先错题、pendingRecordId 补上自评回捞入口`。

---

## Task 3.5：`GET /practice/wrong/page` + `POST /practice/wrong/master`

**Files:**
- Create: `.../pojo/vo/WrongItemVO.java`
- Create: `.../pojo/WrongMasterReq.java`
- Create: `.../service/SubjectGuards.java`（第 3 处需要同一份科目校验时才抽——规则三次的重构时机）
- Create: `.../service/WrongBookService.java` + `.../service/impl/WrongBookServiceImpl.java`
- Create: `.../controller/PracticeWrongController.java`
- Modify: `.../service/impl/QuestionReadServiceImpl.java`（`requireEnabledSubject` 改为委托 `SubjectGuards`）
- Test: `.../service/SubjectGuardsTest.java`
- Test: `.../service/WrongBookServiceTest.java`
- Test: `.../controller/PracticeWrongControllerTest.java`
- Test: `.../controller/AnswerStateResponseGuardTest.java`（追加 wrong/page）

**Interfaces:**
- Consumes: `countWrong` / `selectWrongPage` / `masterIfUnmastered`（段 2）/ `selectByUserAndQuestion`（段 2）/ `LatestAnswers` / `PracticeMasker.titleBrief`
- Produces:
  - `static void SubjectGuards.requireEnabled(QbSubjectMapper mapper, Long subjectId)`
  - `PageVO<WrongItemVO> WrongBookService.page(Long userId, Long subjectId, Integer status, Long pageNum, Long pageSize)`
  - `boolean WrongBookService.master(Long userId, Long questionId)`
  - `WrongItemVO{Long questionId, Integer index, Integer type, String titleBrief, Integer wrongCount, Long lastWrongAt, Integer status, String lastUserAnswerJson}`

**两条口径先说死：**
1. **`status` 可选**（`null` 全部 / `1` 未掌握 / `2` 已掌握），其它值 `40000`。截图的错题本有"未掌握/已掌握"两个页签，`null` 对应"全部"。
2. **`master` 的幂等靠条件更新，不靠查后再改**：`UPDATE ... WHERE status = 1` 返回 1 行是"刚置上"，返回 0 行时才回读一次区分"早就掌握了"（幂等回 `true`）与"根本没这条错题"（`40400`）。并发双击两次只有第一次真改，两次都回 `true`——正是前端想要的。

- [x] **Step 1：写 VO、请求体与 `SubjectGuards`**

`pojo/vo/WrongItemVO.java`：

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/** 错题本的一行（spec 5.2）。lastWrongAt 是 epoch 毫秒（spec 5.1） */
@Data
public class WrongItemVO {

    private Long questionId;
    private Integer index;
    private Integer type;
    private String titleBrief;
    private Integer wrongCount;
    private Long lastWrongAt;
    /** 1未掌握 2已掌握 */
    private Integer status;
    private String lastUserAnswerJson;
}
```

`pojo/WrongMasterReq.java`：

```java
package org.leon.practicemodule.pojo;

import lombok.Data;

/** 错题置已掌握。只有 questionId：subjectId 从错题行来，不给前端传的机会 */
@Data
public class WrongMasterReq {

    private Long questionId;
}
```

`service/SubjectGuards.java`：

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.pojo.QbSubject;

/**
 * 科目存在且启用的校验。第 3 个读接口出现时才抽出来（规则三次），
 * 之前两处各写一遍是可以的，三遍就不再是巧合而是复制。
 */
public final class SubjectGuards {

    private SubjectGuards() {
    }

    public static void requireEnabled(QbSubjectMapper mapper, Long subjectId) {
        if (subjectId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        QbSubject subject = mapper.selectById(subjectId);
        if (subject == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "科目不存在");
        }
        if (subject.getStatus() == null || subject.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "科目已停用");
        }
    }
}
```

`QuestionReadServiceImpl` 里把 `requireEnabledSubject(subjectId)` 的两处调用改成 `SubjectGuards.requireEnabled(subjectMapper, subjectId)`，并删除该私有方法（三个测试用例的期望值不变：null→40000、缺失→40400、停用→40900）。

`service/SubjectGuardsTest.java`：

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.pojo.QbSubject;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("科目校验：null 40000 / 不存在 40400 / 停用 40900")
class SubjectGuardsTest {

    private final QbSubjectMapper mapper = mock(QbSubjectMapper.class);

    private QbSubject subject(int status) {
        QbSubject s = new QbSubject();
        s.setId(1L);
        s.setStatus(status);
        return s;
    }

    @Test
    @DisplayName("三种失败与一种通过")
    void cases() {
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireEnabled(mapper, null)).errorCode());
        when(mapper.selectById(1L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireEnabled(mapper, 1L)).errorCode());
        when(mapper.selectById(1L)).thenReturn(subject(0));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireEnabled(mapper, 1L)).errorCode());
        when(mapper.selectById(1L)).thenReturn(subject(null));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> SubjectGuards.requireEnabled(mapper, 1L)).errorCode());
        when(mapper.selectById(1L)).thenReturn(subject(1));
        assertDoesNotThrow(() -> SubjectGuards.requireEnabled(mapper, 1L));
    }

    @Test
    @DisplayName("status 为 null 的科目按停用处理（列有默认值但别赌）")
    void nullStatusIsConflict() {
        when(mapper.selectById(1L)).thenReturn(subject(0));
        assertThrows(PracticeException.class, () -> SubjectGuards.requireEnabled(mapper, 1L));
    }
}
```

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=SubjectGuardsTest,QuestionReadServiceTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -15`
Expected: 全绿（重构不留红——`QuestionReadServiceTest` 的 16 条照旧通过）。

- [x] **Step 2：写失败测试 `WrongBookServiceTest`**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.mapper.QuestionPositionRow;
import org.leon.practicemodule.mapper.WrongPageRow;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.QbUserWrong;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.WrongItemVO;
import org.leon.practicemodule.service.impl.WrongBookServiceImpl;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("错题本：分页补齐与置已掌握的幂等")
class WrongBookServiceTest {

    private static final long USER = 10L;

    private final QbUserWrongMapper wrongMapper = mock(QbUserWrongMapper.class);
    private final QbQuestionMapper questionMapper = mock(QbQuestionMapper.class);
    private final QbUserAnswerRecordMapper recordMapper = mock(QbUserAnswerRecordMapper.class);
    private final QbSubjectMapper subjectMapper = mock(QbSubjectMapper.class);

    private final WrongBookService service =
            new WrongBookServiceImpl(wrongMapper, questionMapper, recordMapper, subjectMapper);

    private void subjectExists() {
        QbSubject s = new QbSubject();
        s.setId(1L);
        s.setStatus(1);
        when(subjectMapper.selectById(1L)).thenReturn(s);
    }

    private WrongPageRow row(long qid, int wrongCount, int status) {
        // 标题刻意 21 字：截断分支必须在真数据形态上走一次（实测种子最长 21）
        return new WrongPageRow(qid, wrongCount, new Date(1_700_000_000_000L + qid), status,
                1, "题".repeat(21));
    }

    @Test
    @DisplayName("status 只认 null/1/2，其它值 40000 且零查询")
    void statusFilterValidated() {
        subjectExists();
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, 3, null, null)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, 0, null, null)).errorCode());
        verifyNoInteractions(wrongMapper, questionMapper, recordMapper);
    }

    @Test
    @DisplayName("科目校验在分页之前")
    void subjectGuardFirst() {
        when(subjectMapper.selectById(9L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.page(USER, 9L, null, null, null)).errorCode());
        verifyNoInteractions(wrongMapper);
    }

    @Test
    @DisplayName("total=0 与越界页都只查一次计数")
    void emptyPages() {
        subjectExists();
        when(wrongMapper.countWrong(eq(USER), eq(1L), isNull())).thenReturn(0L);
        PageVO<WrongItemVO> page = service.page(USER, 1L, null, 1L, 20L);
        assertEquals(0L, page.getTotal());
        assertTrue(page.getList().isEmpty());

        when(wrongMapper.countWrong(eq(USER), eq(1L), eq(1))).thenReturn(3L);
        PageVO<WrongItemVO> beyond = service.page(USER, 1L, 1, 5L, 20L);
        assertEquals(3L, beyond.getTotal());
        assertTrue(beyond.getList().isEmpty());
        verify(wrongMapper, times(2)).countWrong(anyLong(), anyLong(), any());
        verify(wrongMapper, never()).selectWrongPage(anyLong(), anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("一行错题：index 来自批量位置、titleBrief 已截断、lastWrongAt 是 epoch 毫秒")
    void normalPage() {
        subjectExists();
        when(wrongMapper.countWrong(eq(USER), eq(1L), isNull())).thenReturn(2L);
        when(wrongMapper.selectWrongPage(eq(USER), eq(1L), isNull(), eq(0L), eq(20L)))
                .thenReturn(List.of(row(1, 3, 1), row(6, 1, 1)));
        when(questionMapper.selectPositions(any())).thenReturn(
                List.of(new QuestionPositionRow(1L, 1L), new QuestionPositionRow(6L, 2L)));
        when(recordMapper.selectLatestByQuestions(eq(USER), any()))
                .thenReturn(List.of(new LatestAnswerRow(50L, 1L, "\"C\"", 0, 1)));

        PageVO<WrongItemVO> page = service.page(USER, 1L, null, 1L, 20L);

        WrongItemVO first = page.getList().get(0);
        assertEquals(1L, first.getQuestionId());
        assertEquals(1, first.getIndex());
        assertEquals(3, first.getWrongCount());
        assertEquals(1, first.getStatus());
        assertEquals(20, first.getTitleBrief().length(), "21 字标题必须被截成 20：spec 的卡片一行放不下");        assertEquals(1_700_000_000_001L, first.getLastWrongAt());
        assertEquals("\"C\"", first.getLastUserAnswerJson());

        WrongItemVO second = page.getList().get(1);
        assertEquals(2, second.getIndex());
        assertNull(second.getLastUserAnswerJson(), "qid 6 没有记录，补齐不能编造");

        verify(questionMapper, times(1)).selectPositions(any());
        verify(recordMapper, times(1)).selectLatestByQuestions(anyLong(), any());
    }

    @Test
    @DisplayName("题目在计数后被下架：位置缺行，index 回 null 而不是崩")
    void missingPositionRowTolerated() {
        subjectExists();
        when(wrongMapper.countWrong(eq(USER), eq(1L), isNull())).thenReturn(1L);
        when(wrongMapper.selectWrongPage(eq(USER), eq(1L), isNull(), eq(0L), eq(20L)))
                .thenReturn(List.of(row(1, 1, 1)));
        when(questionMapper.selectPositions(any())).thenReturn(List.of());

        assertNull(service.page(USER, 1L, null, 1L, 20L).getList().get(0).getIndex());
    }

    @Test
    @DisplayName("master：条件更新命中 1 行就回 true，不再回读")
    void masterHits() {
        when(wrongMapper.masterIfUnmastered(USER, 1L)).thenReturn(1);
        assertTrue(service.master(USER, 1L));
        verify(wrongMapper, never()).selectByUserAndQuestion(anyLong(), anyLong());
    }

    @Test
    @DisplayName("master：0 行时区分已掌握（幂等 true）与无记录（40400）")
    void masterMisses() {
        when(wrongMapper.masterIfUnmastered(USER, 1L)).thenReturn(0);
        QbUserWrong wrong = new QbUserWrong();
        wrong.setStatus(2);
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(wrong);
        assertTrue(service.master(USER, 1L), "第二次点已掌握必须还是 true");

        when(wrongMapper.selectByUserAndQuestion(USER, 2L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.master(USER, 2L)).errorCode());

        when(wrongMapper.selectByUserAndQuestion(USER, 3L)).thenReturn(new QbUserWrong());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.master(USER, 3L)).errorCode(),
                "行存在但 status 不是 1/2（脏数据）：报 40000，不假装成功");
    }

    @Test
    @DisplayName("master：questionId 缺失 40000，零写库")
    void masterParam() {
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.master(USER, null)).errorCode());
        verifyNoInteractions(wrongMapper);
    }
}
```

- [x] **Step 3：跑测试确认失败**（`WrongBookServiceImpl` 不存在 → 编译失败）

- [x] **Step 4：实现 `WrongBookService` 与 impl**

`service/WrongBookService.java`：

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.WrongItemVO;

public interface WrongBookService {

    /** status：null 全部、1 未掌握、2 已掌握 */
    PageVO<WrongItemVO> page(Long userId, Long subjectId, Integer status, Long pageNum, Long pageSize);

    /** 置为已掌握。幂等：重复调用一律 true */
    boolean master(Long userId, Long questionId);
}
```

`service/impl/WrongBookServiceImpl.java`：

```java
package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.mapper.WrongPageRow;
import org.leon.practicemodule.pojo.QbUserWrong;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.WrongItemVO;
import org.leon.practicemodule.service.LatestAnswers;
import org.leon.practicemodule.service.PracticeMasker;
import org.leon.practicemodule.service.PracticePages;
import org.leon.practicemodule.service.SubjectGuards;
import org.leon.practicemodule.service.WrongBookService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 错题本读侧与"已掌握"写。
 * 答对/答错的错题行写入是段 2 的 applyOutcome；本类只多一个动作：把未掌握置为已掌握，
 * 用的还是段 2 那条条件更新——所以错题本的写口径全仓只有一份 SQL。
 * 错题行不因题目下架而删（裁定五）：重新上架就恢复，页面上看不见而已。
 */
@Service
public class WrongBookServiceImpl implements WrongBookService {

    private final QbUserWrongMapper wrongMapper;
    private final QbQuestionMapper questionMapper;
    private final QbUserAnswerRecordMapper recordMapper;
    private final QbSubjectMapper subjectMapper;

    public WrongBookServiceImpl(QbUserWrongMapper wrongMapper,
                                QbQuestionMapper questionMapper,
                                QbUserAnswerRecordMapper recordMapper,
                                QbSubjectMapper subjectMapper) {
        this.wrongMapper = wrongMapper;
        this.questionMapper = questionMapper;
        this.recordMapper = recordMapper;
        this.subjectMapper = subjectMapper;
    }

    @Override
    public PageVO<WrongItemVO> page(Long userId, Long subjectId, Integer status, Long pageNum, Long pageSize) {
        PracticePages.PageParam param = PracticePages.of(pageNum, pageSize);
        if (status != null && status != 1 && status != 2) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "掌握状态只能是 1未掌握 或 2已掌握");
        }
        SubjectGuards.requireEnabled(subjectMapper, subjectId);

        long total = wrongMapper.countWrong(userId, subjectId, status);
        if (total == 0 || param.offset() >= total) {
            return PageVO.of(List.of(), total, param.pageNum(), param.pageSize());
        }
        List<WrongPageRow> rows =
                wrongMapper.selectWrongPage(userId, subjectId, status, param.offset(), param.pageSize());
        List<Long> ids = rows.stream().map(WrongPageRow::questionId).toList();
        Map<Long, Long> positions = LatestAnswers.positions(questionMapper, ids);
        Map<Long, LatestAnswerRow> latest = LatestAnswers.byQuestion(recordMapper, userId, ids);

        List<WrongItemVO> items = new ArrayList<>(rows.size());
        for (WrongPageRow row : rows) {
            WrongItemVO item = new WrongItemVO();
            item.setQuestionId(row.questionId());
            Long pos = positions.get(row.questionId());
            item.setIndex(pos == null ? null : pos.intValue());
            item.setType(row.type());
            item.setTitleBrief(PracticeMasker.titleBrief(row.title()));
            item.setWrongCount(row.wrongCount());
            item.setLastWrongAt(row.lastWrongAt() == null ? null : row.lastWrongAt().getTime());
            item.setStatus(row.status());
            LatestAnswerRow last = latest.get(row.questionId());
            item.setLastUserAnswerJson(last == null ? null : last.userAnswerJson());
            items.add(item);
        }
        return PageVO.of(items, total, param.pageNum(), param.pageSize());
    }

    @Override
    public boolean master(Long userId, Long questionId) {
        if (questionId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        if (wrongMapper.masterIfUnmastered(userId, questionId) == 1) {
            return true;
        }
        QbUserWrong row = wrongMapper.selectByUserAndQuestion(userId, questionId);
        if (row == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "错题记录不存在");
        }
        if (row.getStatus() != null && row.getStatus() == 2) {
            return true;   // 已经掌握过：幂等
        }
        // 行在、状态既不是 1 也不是 2 —— 库里出现了我们没定义过的值，别假装成功
        throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "错题状态数据异常");
    }
}
```

> 注意 `master` 的第三段分支：`masterIfUnmastered` 命中 0 行且行存在、`status` 又不是 2 时（实测种子行只有 1/2，这条走的是脏数据），报 `40000` 而不是 `true`。测试 `masterMisses` 的第三个 case 钉住它。

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=WrongBookServiceTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -15`
Expected: `Tests run: 8, Failures: 0`。

- [x] **Step 5：控制器 + 切片测试 + 守卫**

`controller/PracticeWrongController.java`：

```java
package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.WrongMasterReq;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.WrongItemVO;
import org.leon.practicemodule.service.SubjectService;
import org.leon.practicemodule.service.UserIdentityService;
import org.leon.practicemodule.service.WrongBookService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 错题本。GET 供浏览器直打与前端分页，POST 只改掌握状态 */
@RestController
@RequestMapping("/practice/wrong")
public class PracticeWrongController {

    private final WrongBookService wrongBookService;
    private final UserIdentityService userIdentityService;

    public PracticeWrongController(WrongBookService wrongBookService,
                                   UserIdentityService userIdentityService) {
        this.wrongBookService = wrongBookService;
        this.userIdentityService = userIdentityService;
    }

    @GetMapping("/page")
    public Result<PageVO<WrongItemVO>> page(@RequestHeader("X-User-Name") String userName,
                                            @RequestParam Long subjectId,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) Long pageNum,
                                            @RequestParam(required = false) Long pageSize) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(wrongBookService.page(userId, subjectId, status, pageNum, pageSize));
    }

    @PostMapping("/master")
    public Result<Boolean> master(@RequestHeader("X-User-Name") String userName,
                                  @RequestBody WrongMasterReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(wrongBookService.master(userId, req.getQuestionId()));
    }
}
```

> 那行 `import org.leon.practicemodule.service.SubjectService;` 是多余的，执行时删掉（本控制器不用它）——写在这里只为提醒：**别顺手把 `subject/list` 塞进错题控制器**。

`controller/PracticeWrongControllerTest.java`：

```java
package org.leon.practicemodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.WrongMasterReq;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.pojo.vo.WrongItemVO;
import org.leon.practicemodule.service.UserIdentityService;
import org.leon.practicemodule.service.WrongBookService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PracticeWrongControllerTest {

    private final WrongBookService wrongBookService = mock(WrongBookService.class);
    private final UserIdentityService identity = mock(UserIdentityService.class);
    private final PracticeWrongController controller = new PracticeWrongController(wrongBookService, identity);

    @Test
    @DisplayName("page：status 缺省透传 null")
    void pagePassesThrough() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(wrongBookService.page(eq(10L), eq(1L), isNull(), any(), any()))
                .thenReturn(PageVO.of(List.of(), 0L, 1L, 20L));
        Result<PageVO<WrongItemVO>> r = controller.page("alice", 1L, null, null, null);
        assertEquals(1, r.getCode());
        verify(wrongBookService).page(10L, 1L, null, null, null);
    }

    @Test
    @DisplayName("master：只取 questionId，返回裸 boolean")
    void masterReturnsFlag() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(wrongBookService.master(10L, 3L)).thenReturn(true);
        WrongMasterReq req = new WrongMasterReq();
        req.setQuestionId(3L);
        Result<Boolean> r = controller.master("alice", req);
        assertEquals(1, r.getCode());
        assertTrue(r.getData());
    }

    @Test
    @DisplayName("请求体为空对象：questionId null 交给 Service 判 40000，控制器不兜")
    void masterEmptyBody() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(wrongBookService.master(10L, null)).thenThrow(
                new org.leon.practicemodule.exception.PracticeException(
                        org.leon.practicemodule.exception.PracticeErrorCode.BAD_REQUEST, "请求参数不合法"));
        assertEquals(org.leon.practicemodule.exception.PracticeErrorCode.BAD_REQUEST,
                assertThrows(org.leon.practicemodule.exception.PracticeException.class,
                        () -> controller.master("alice", new WrongMasterReq())).errorCode());
    }
}
```

`AnswerStateResponseGuardTest` 先做三处扩展（为 wrong/favorite 两个控制器铺路），再追加一条用例。

**（a）类字段区加一个 mock**（`favoriteMapper`/`recordMapper`/`questionMapper`/`subjectMapper` 在 Task 3.3 已有，`optionMapper` 在 Task 3.4 已有）：

```java
    private QbUserWrongMapper wrongMapper;
```

**（b）`wireRealServicesOverMockMappers()` 里补 mock 与控制器注册**（`optionMapper` 是 Task 3.4 加的，这里假定已在）：

```java
        wrongMapper = mock(QbUserWrongMapper.class);
        favoriteMapper = mock(QbUserFavoriteMapper.class);
        ...
        WrongBookService wrongBookService =
                new WrongBookServiceImpl(wrongMapper, questionMapper, recordMapper, subjectMapper);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PracticeQuestionController(readService, identity),
                        new PracticeWrongController(wrongBookService, identity))
                .setControllerAdvice(new PracticeExceptionHandler())
                .build();
```

**（c）新增一个私有小工厂，供各用例造启用中的科目**（原来那个 `QbSubject` 是在 wire 方法里就地 new 的，现在多个用例都要用）：

```java
    private static QbSubject subject(int status) {
        QbSubject s = new QbSubject();
        s.setId(1L);
        s.setStatus(status);
        return s;
    }
```

追加的用例：

```java
    @Test
    @DisplayName("wrong/page 的响应体干净，且 titleBrief 真的铺进了响应")
    void wrongPageResponseIsClean() throws Exception {
        when(subjectMapper.selectById(1L)).thenReturn(subject(1));
        when(wrongMapper.countWrong(eq(10L), eq(1L), isNull())).thenReturn(1L);
        when(wrongMapper.selectWrongPage(eq(10L), eq(1L), isNull(), anyLong(), anyLong()))
                .thenReturn(List.of(new WrongPageRow(1L, 2, new Date(1_700_000_000_000L), 1, 1,
                        "题干甲 " + ANALYSIS_SENTINEL)));
        when(questionMapper.selectPositions(any()))
                .thenReturn(List.of(new QuestionPositionRow(1L, 1L)));

        String json = body(get("/practice/wrong/page?subjectId=1"));

        assertNoAnswerFields(json, "\"titleBrief\"");
        assertFalse(json.contains(ANALYSIS_SENTINEL),
                "题干里夹着解析文本时被 titleBrief 一起带出：这是数据问题不是脱敏问题，但守卫照样该红");
    }
```

> `wrongPageResponseIsClean` 的第二个断言是一次**已知的真实风险**：如果谁把解析文字写进题干，`titleBrief` 会照抄。执行时先跑一次 `PracticeMaskerTest` 之外的这条——若它红，说明种子题干里真混了答案文本，那要单开一条数据问题报给用户，而不是放宽守卫。（实测种子标题里没有 `ANALYSIS_SENTINEL`，本条应当绿。）

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=PracticeWrongControllerTest,AnswerStateResponseGuardTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20`
Expected: `Tests run: 3 + 5`，全绿。

- [x] **Step 6：全量回归 + 报告改动，停在提交前**

Run: `"$MVN" -o -pl PracticeModule -am test 2>&1 | tail -20` → 全绿。

Run: `git status --short -- PracticeModule` → 应只见十一个文件（`WrongMasterReq`、`WrongItemVO`、`SubjectGuards`、`WrongBookService`、`WrongBookServiceImpl`、`QuestionReadServiceImpl`、`PracticeWrongController` + 四个测试类）。

**到此为止：不 `git add`、不 `git commit`。**建议提交信息：`段3 Task3.5 错题本：分页补齐、titleBrief 截断与已掌握的条件更新幂等`。

---

## Task 3.6：`GET /practice/favorite/page` + `POST /practice/favorite/toggle`

**Files:**
- Create: `.../pojo/vo/FavoriteItemVO.java` `.../pojo/vo/FavoriteToggleVO.java`
- Create: `.../pojo/FavoriteToggleReq.java`
- Create: `.../service/FavoriteService.java` + `.../service/impl/FavoriteServiceImpl.java`
- Create: `.../controller/PracticeFavoriteController.java`
- Test: `.../service/FavoriteServiceTest.java`
- Test: `.../controller/PracticeFavoriteControllerTest.java`
- Test: `.../controller/AnswerStateResponseGuardTest.java`（追加 favorite/page）

**Interfaces:**
- Consumes: `countFavorites` / `selectFavoritePage` / `deleteByUserAndQuestion` / `insertIgnoreDuplicate` / `countByUserAndQuestion` / `QbQuestionMapper.selectById` / `LatestAnswers.positions` / `PracticeMasker.titleBrief` / `SubjectGuards.requireEnabled`
- Produces:
  - `PageVO<FavoriteItemVO> FavoriteService.page(Long userId, Long subjectId, Long pageNum, Long pageSize)`
  - `boolean FavoriteService.toggle(Long userId, Long subjectId, Long questionId)`
  - `FavoriteItemVO{Long questionId, Integer index, Integer type, String titleBrief, Long createdAt}`
  - `FavoriteToggleVO{boolean favorited}`

**toggle 的三步与为什么这样写（裁定五）**：`DELETE` 命中 >0 行 → 已经取消，**不查题目状态**（否则下架题的收藏在页面上消失后永远删不掉，脏死在库里）；命中 0 行 → 走新增，此时题目必须存在且 `status=1`（否则 `40400` / `40900`），`subjectId` 必须与题目一致（否则 `40000`，与段 2 的 submit 同口径）；插入用 `ON DUPLICATE KEY UPDATE id=id` 吃掉并发双击的重复键，然后**回读存在性**决定回 `true` 还是 `false`——回读而不是硬编 `true`，是为了在极端并发下也如实汇报。

- [x] **Step 1：写 VO、请求体与服务接口**

`pojo/vo/FavoriteItemVO.java`：

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/** 收藏本的一行（spec 5.2）。createdAt 是 epoch 毫秒 */
@Data
public class FavoriteItemVO {

    private Long questionId;
    private Integer index;
    private Integer type;
    private String titleBrief;
    private Long createdAt;
}
```

`pojo/vo/FavoriteToggleVO.java`：

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/** toggle 的结果：以后状态为准，而不是"我猜你想加还是想删" */
@Data
public class FavoriteToggleVO {

    private boolean favorited;
}
```

`pojo/FavoriteToggleReq.java`：

```java
package org.leon.practicemodule.pojo;

import lombok.Data;

/** 收藏开关。subjectId 只用来核对归属，收藏行里的 subject_id 取题目自己的，不给前端传错的机会 */
@Data
public class FavoriteToggleReq {

    private Long subjectId;
    private Long questionId;
}
```

`service/FavoriteService.java`：

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.FavoriteItemVO;
import org.leon.practicemodule.pojo.vo.PageVO;

public interface FavoriteService {

    PageVO<FavoriteItemVO> page(Long userId, Long subjectId, Long pageNum, Long pageSize);

    /** 开或关，返回最终状态 */
    boolean toggle(Long userId, Long subjectId, Long questionId);
}
```

- [x] **Step 2：写失败测试 `FavoriteServiceTest`**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.FavoritePageRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserFavoriteMapper;
import org.leon.practicemodule.mapper.QuestionPositionRow;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.vo.FavoriteItemVO;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.service.impl.FavoriteServiceImpl;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("收藏本：分页与 toggle 的三步走")
class FavoriteServiceTest {

    private static final long USER = 10L;

    private final QbUserFavoriteMapper favoriteMapper = mock(QbUserFavoriteMapper.class);
    private final QbQuestionMapper questionMapper = mock(QbQuestionMapper.class);
    private final QbSubjectMapper subjectMapper = mock(QbSubjectMapper.class);

    private final FavoriteService service =
            new FavoriteServiceImpl(favoriteMapper, questionMapper, subjectMapper);

    private void subjectExists() {
        QbSubject s = new QbSubject();
        s.setId(1L);
        s.setStatus(1);
        when(subjectMapper.selectById(1L)).thenReturn(s);
    }

    private QbQuestion question(int status) {
        QbQuestion q = new QbQuestion();
        q.setId(1L);
        q.setSubjectId(1L);
        q.setType(1);
        q.setTitle("题".repeat(21));
        q.setStatus(status);
        return q;
    }

    @Test
    @DisplayName("page：科目缺失 40400、停用 40900、pageSize=0 40000，全部零列表查询")
    void pageGuards() {
        when(subjectMapper.selectById(9L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.page(USER, 9L, null, null)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.page(USER, 1L, 1L, 0L)).errorCode());
        verify(favoriteMapper, never()).countFavorites(anyLong(), anyLong());
    }

    @Test
    @DisplayName("page：total=0 与越界页都不查分页行")
    void emptyPages() {
        subjectExists();
        when(favoriteMapper.countFavorites(USER, 1L)).thenReturn(0L);
        PageVO<FavoriteItemVO> page = service.page(USER, 1L, 1L, 20L);
        assertTrue(page.getList().isEmpty());
        verify(favoriteMapper, never()).selectFavoritePage(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("page：index 来自位置批量、titleBrief 截断、createdAt 是 epoch 毫秒")
    void normalPage() {
        subjectExists();
        when(favoriteMapper.countFavorites(USER, 1L)).thenReturn(2L);
        when(favoriteMapper.selectFavoritePage(eq(USER), eq(1L), eq(0L), eq(20L))).thenReturn(
                List.of(new FavoritePageRow(1L, new Date(1_700_000_000_000L), 1, "题".repeat(21)),
                        new FavoritePageRow(6L, new Date(1_600_000_000_000L), 1, "短标题")));
        when(questionMapper.selectPositions(any())).thenReturn(
                List.of(new QuestionPositionRow(1L, 1L), new QuestionPositionRow(6L, 2L)));

        PageVO<FavoriteItemVO> page = service.page(USER, 1L, 1L, 20L);

        assertEquals(2L, page.getTotal());
        assertEquals(2L, page.getPages());
        FavoriteItemVO first = page.getList().get(0);
        assertEquals(1L, first.getQuestionId());
        assertEquals(1, first.getIndex());
        assertEquals(20, first.getTitleBrief().length());
        assertEquals(1_700_000_000_000L, first.getCreatedAt());
        assertEquals("短标题", page.getList().get(1).getTitleBrief());
        assertEquals(1_600_000_000_000L, page.getList().get(1).getCreatedAt());
        verify(questionMapper, times(1)).selectPositions(any());
    }

    @Test
    @DisplayName("toggle：先删；删掉了就回 false，且不查题目状态（裁定五）")
    void removeDoesNotCheckQuestionStatus() {
        when(favoriteMapper.deleteByUserAndQuestion(USER, 1L)).thenReturn(1);
        assertFalse(service.toggle(USER, 1L, 1L));
        verifyNoInteractions(questionMapper, subjectMapper);
        verify(favoriteMapper, never()).insertIgnoreDuplicate(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("toggle：新增分支的题目状态与归属都在插入之前")
    void addChecksBeforeInsert() {
        when(favoriteMapper.deleteByUserAndQuestion(USER, 1L)).thenReturn(0);
        when(questionMapper.selectById(1L)).thenReturn(null);
        assertEquals(PracticeErrorCode.NOT_FOUND, assertThrows(PracticeException.class,
                () -> service.toggle(USER, 1L, 1L)).errorCode());

        when(questionMapper.selectById(1L)).thenReturn(question(0));
        assertEquals(PracticeErrorCode.CONFLICT, assertThrows(PracticeException.class,
                () -> service.toggle(USER, 1L, 1L)).errorCode());

        QbQuestion other = question(1);
        other.setSubjectId(2L);
        when(questionMapper.selectById(1L)).thenReturn(other);
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.toggle(USER, 1L, 1L)).errorCode());

        verify(favoriteMapper, never()).insertIgnoreDuplicate(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("toggle：插入用题目自己的 subjectId，回读存在才报 true")
    void addThenReadBack() {
        when(favoriteMapper.deleteByUserAndQuestion(USER, 1L)).thenReturn(0);
        when(questionMapper.selectById(1L)).thenReturn(question(1));
        when(favoriteMapper.insertIgnoreDuplicate(USER, 1L, 1L)).thenReturn(1);
        when(favoriteMapper.countByUserAndQuestion(USER, 1L)).thenReturn(1L);

        assertTrue(service.toggle(USER, 1L, 1L));
        verify(favoriteMapper).insertIgnoreDuplicate(USER, 1L, 1L);
    }

    @Test
    @DisplayName("toggle：并发下刚插完就被别人删掉，回 false 而不是硬编 true")
    void addRacedToZero() {
        when(favoriteMapper.deleteByUserAndQuestion(USER, 1L)).thenReturn(0);
        when(questionMapper.selectById(1L)).thenReturn(question(1));
        when(favoriteMapper.countByUserAndQuestion(USER, 1L)).thenReturn(0L);

        assertFalse(service.toggle(USER, 1L, 1L), "如实汇报：这一次点击没落下收藏");
    }

    @Test
    @DisplayName("toggle：参数缺失 40000，零写库")
    void paramRequired() {
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.toggle(USER, null, 1L)).errorCode());
        assertEquals(PracticeErrorCode.BAD_REQUEST, assertThrows(PracticeException.class,
                () -> service.toggle(USER, 1L, null)).errorCode());
        verifyNoInteractions(favoriteMapper, questionMapper, subjectMapper);
    }
}
```

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=FavoriteServiceTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -15`
Expected: 编译失败（`FavoriteServiceImpl` 不存在）。

- [x] **Step 3：实现 `FavoriteService` 与 impl**

`service/impl/FavoriteServiceImpl.java`：

```java
package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.FavoritePageRow;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserFavoriteMapper;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.vo.FavoriteItemVO;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.service.FavoriteService;
import org.leon.practicemodule.service.LatestAnswers;
import org.leon.practicemodule.service.PracticeMasker;
import org.leon.practicemodule.service.PracticePages;
import org.leon.practicemodule.service.SubjectGuards;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 收藏本。写只有一个动作：开关。行只在 qb_user_favorite 上，不联动错题或统计。
 * uk_user_question(user_id, question_id) 实测存在，所以"一人一题一收藏"由数据库保证，
 * 代码不需要先查后插（那是并发下会出双行的 check-then-act）。
 */
@Service
public class FavoriteServiceImpl implements FavoriteService {

    private final QbUserFavoriteMapper favoriteMapper;
    private final QbQuestionMapper questionMapper;
    private final QbSubjectMapper subjectMapper;

    public FavoriteServiceImpl(QbUserFavoriteMapper favoriteMapper,
                               QbQuestionMapper questionMapper,
                               QbSubjectMapper subjectMapper) {
        this.favoriteMapper = favoriteMapper;
        this.questionMapper = questionMapper;
        this.subjectMapper = subjectMapper;
    }

    @Override
    public PageVO<FavoriteItemVO> page(Long userId, Long subjectId, Long pageNum, Long pageSize) {
        PracticePages.PageParam param = PracticePages.of(pageNum, pageSize);
        SubjectGuards.requireEnabled(subjectMapper, subjectId);

        long total = favoriteMapper.countFavorites(userId, subjectId);
        if (total == 0 || param.offset() >= total) {
            return PageVO.of(List.of(), total, param.pageNum(), param.pageSize());
        }
        List<FavoritePageRow> rows =
                favoriteMapper.selectFavoritePage(userId, subjectId, param.offset(), param.pageSize());
        List<Long> ids = rows.stream().map(FavoritePageRow::questionId).toList();
        Map<Long, Long> positions = LatestAnswers.positions(questionMapper, ids);

        List<FavoriteItemVO> items = new ArrayList<>(rows.size());
        for (FavoritePageRow row : rows) {
            FavoriteItemVO item = new FavoriteItemVO();
            item.setQuestionId(row.questionId());
            Long pos = positions.get(row.questionId());
            item.setIndex(pos == null ? null : pos.intValue());
            item.setType(row.type());
            item.setTitleBrief(PracticeMasker.titleBrief(row.title()));
            item.setCreatedAt(row.createdAt() == null ? null : row.createdAt().getTime());
            items.add(item);
        }
        return PageVO.of(items, total, param.pageNum(), param.pageSize());
    }

    @Override
    @Transactional
    public boolean toggle(Long userId, Long subjectId, Long questionId) {
        if (subjectId == null || questionId == null) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请求参数不合法");
        }
        if (favoriteMapper.deleteByUserAndQuestion(userId, questionId) > 0) {
            // 取消收藏不核对题目状态（裁定五）：下架题的收藏若还要 status=1 才能删，就永远删不掉
            return false;
        }
        QbQuestion question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在");
        }
        if (question.getStatus() == null || question.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架");
        }
        if (!subjectId.equals(question.getSubjectId())) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "题目不属于该科目");
        }
        favoriteMapper.insertIgnoreDuplicate(userId, question.getSubjectId(), questionId);
        // 回读而不是硬编 true：并发下这一下可能没落下任何行
        return favoriteMapper.countByUserAndQuestion(userId, questionId) > 0;
    }
}
```

Run: 同 Step 2。Expected: `Tests run: 8, Failures: 0`。

- [x] **Step 4：控制器 + 切片测试**

`controller/PracticeFavoriteController.java`：

```java
package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.FavoriteToggleReq;
import org.leon.practicemodule.pojo.vo.FavoriteItemVO;
import org.leon.practicemodule.pojo.vo.FavoriteToggleVO;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.service.FavoriteService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 收藏本。toggle 回的是"最终状态"，前端直接绑星号图标，不用再自己取反 */
@RestController
@RequestMapping("/practice/favorite")
public class PracticeFavoriteController {

    private final FavoriteService favoriteService;
    private final UserIdentityService userIdentityService;

    public PracticeFavoriteController(FavoriteService favoriteService,
                                      UserIdentityService userIdentityService) {
        this.favoriteService = favoriteService;
        this.userIdentityService = userIdentityService;
    }

    @GetMapping("/page")
    public Result<PageVO<FavoriteItemVO>> page(@RequestHeader("X-User-Name") String userName,
                                               @RequestParam Long subjectId,
                                               @RequestParam(required = false) Long pageNum,
                                               @RequestParam(required = false) Long pageSize) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(favoriteService.page(userId, subjectId, pageNum, pageSize));
    }

    @PostMapping("/toggle")
    public Result<FavoriteToggleVO> toggle(@RequestHeader("X-User-Name") String userName,
                                           @RequestBody FavoriteToggleReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        FavoriteToggleVO vo = new FavoriteToggleVO();
        vo.setFavorited(favoriteService.toggle(userId, req.getSubjectId(), req.getQuestionId()));
        return Result.success(vo);
    }
}
```

`controller/PracticeFavoriteControllerTest.java`：

```java
package org.leon.practicemodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.pojo.FavoriteToggleReq;
import org.leon.practicemodule.pojo.vo.FavoriteItemVO;
import org.leon.practicemodule.pojo.vo.FavoriteToggleVO;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.service.FavoriteService;
import org.leon.practicemodule.service.UserIdentityService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PracticeFavoriteControllerTest {

    private final FavoriteService favoriteService = mock(FavoriteService.class);
    private final UserIdentityService identity = mock(UserIdentityService.class);
    private final PracticeFavoriteController controller = new PracticeFavoriteController(favoriteService, identity);

    @Test
    @DisplayName("page：身份换取后透传")
    void pagePassesThrough() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(favoriteService.page(eq(10L), eq(1L), any(), any()))
                .thenReturn(PageVO.of(List.of(), 0L, 1L, 20L));
        Result<PageVO<FavoriteItemVO>> r = controller.page("alice", 1L, null, null);
        assertEquals(1, r.getCode());
    }

    @Test
    @DisplayName("toggle：Service 的布尔包成 favorited 字段")
    void toggleWrapsFlag() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(favoriteService.toggle(10L, 1L, 2L)).thenReturn(true);
        FavoriteToggleReq req = new FavoriteToggleReq();
        req.setSubjectId(1L);
        req.setQuestionId(2L);

        Result<FavoriteToggleVO> r = controller.toggle("alice", req);
        assertEquals(1, r.getCode());
        assertTrue(r.getData().isFavorited());
    }

    @Test
    @DisplayName("toggle：Service 报 40900 时控制器不吞")
    void togglePropagates() {
        when(identity.resolveUserId("alice")).thenReturn(10L);
        when(favoriteService.toggle(10L, 1L, 2L)).thenThrow(
                new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架"));
        FavoriteToggleReq req = new FavoriteToggleReq();
        req.setSubjectId(1L);
        req.setQuestionId(2L);
        assertEquals(PracticeErrorCode.CONFLICT,
                assertThrows(PracticeException.class, () -> controller.toggle("alice", req)).errorCode());
    }
}
```

- [x] **Step 5：守卫 harness 加 favorite/page**

在 `wireRealServicesOverMockMappers()` 里把 `PracticeFavoriteController` 也注册进去（`new PracticeFavoriteController(new FavoriteServiceImpl(favoriteMapper, questionMapper, subjectMapper), identity)`），并追加：

```java
    @Test
    @DisplayName("favorite/page 的响应体干净")
    void favoritePageResponseIsClean() throws Exception {
        when(subjectMapper.selectById(1L)).thenReturn(subject(1));
        when(favoriteMapper.countFavorites(10L, 1L)).thenReturn(1L);
        when(favoriteMapper.selectFavoritePage(eq(10L), eq(1L), anyLong(), anyLong()))
                .thenReturn(List.of(new FavoritePageRow(1L, new Date(1_700_000_000_000L), 1, "题干甲")));
        when(questionMapper.selectPositions(any())).thenReturn(List.of(new QuestionPositionRow(1L, 1L)));

        String json = body(get("/practice/favorite/page?subjectId=1"));

        assertNoAnswerFields(json, "\"titleBrief\"");
        assertTrue(json.contains("题干甲"), "列表要能认出是哪道题：" + json);
        assertTrue(json.contains("1700000000000"), "createdAt 必须是 epoch 毫秒：" + json);
    }
```

Run: `"$MVN" -o -pl PracticeModule -am test -Dtest=AnswerStateResponseGuardTest,FavoriteServiceTest,PracticeFavoriteControllerTest -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20`
Expected: 三条类合计 `Tests run: 6 + 8 + 3`，全绿。

> **四个答题态 GET 现在都进了守卫清单**（`question/page`、`question/current`、`wrong/page`、`favorite/page`）。段 5 的 `exam/question`、`paper/detail` 直接往这个类里加用例，别再新开一个测试类。

- [x] **Step 6：全量回归 + 报告改动，停在提交前**

Run: `"$MVN" -o -pl PracticeModule -am test 2>&1 | tail -20` → 全绿；把最终数字记进执行记录（段 2 基线 101，本段预计 150 上下）。

Run: `git status --short -- PracticeModule` → 应只见九个文件（`FavoriteToggleReq`、`FavoriteItemVO`、`FavoriteToggleVO`、`FavoriteService`、`FavoriteServiceImpl`、`PracticeFavoriteController` + 三个测试类）。

**到此为止：不 `git add`、不 `git commit`。**建议提交信息：`段3 Task3.6 收藏本：分页与 toggle 的删除优先三步走（取消不核对题目状态）`。

---

## Task 3.7：端到端取证（阶段一 9 个接口全通）+ 文档回写

这一步是段 3 的"产品可用"证明：单测与 db-it 说的是代码对不对，端到端说的是**网关链路真跑起来是不是这个样子**。全程走 8100，不留直连 8102 的口子（spec §9.2）。

**Files:**
- Modify: `docs/superpowers/plans/2026-10-23-practice-03-practice-read.md`（执行记录）
- Modify: `docs/superpowers/specs/2026-10-23-practice-exam-api-design.md`（§5.2 契约追加、§6.2 断点更正、§9.1.2 守卫状态——都带日期写成"段 3 更正"，不做静默改写）
- Modify: `docs/superpowers/plans/2026-10-23-practice-00-roadmap.md`（§3.10 的"段 3 必须裁定"关闭、段 3 状态、环境行）
- 仓库外：`C:/Users/Administrator/.qoder-cn/tmp/F--AAAJAVA-code-ezPracticeExercises/seg3/`（探针、驱动脚本、响应快照；**不往仓库里放任何临时文件**）

**Interfaces:**
- Consumes: 全部六个新接口 + 段 1 的 `subject/list` + 段 2 的 `submit`/`self-judge`
- Produces: 执行记录里的取证清单（每条一个响应文件名）

- [x] **Step 1：重启 PracticeModule 8102（装载新代码）**

段 2 踩过的两个坑，照做别绕：

```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
MVN="/c/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
TMP=/c/Users/Administrator/.qoder-cn/tmp/F--AAAJAVA-code-ezPracticeExercises/seg3
mkdir -p "$TMP"

# 1) 先找到"真正占着 8102 的那个 PID"：只认日志里的 `with PID <n>`，
#    无锚定的 grep ":8102" 会把 Maven 父进程当成应用进程误杀（段 2 真杀错过一次）
PID=$(grep -oE "with PID [0-9]+" "$TMP"/practice*.log | tail -1 | grep -oE "[0-9]+")
echo "practice app pid = $PID"
taskkill //PID "$PID" //F

# 2) 聚合模块不能直接 spring-boot:run（段 2 实测 BUILD FAILURE），先 install 再单模块跑
"$MVN" -o -q -DskipTests install
"$MVN" -o -pl PracticeModule spring-boot:run > "$TMP/practice-seg3.log" 2>&1 &
```

等 `Tomcat started on port 8102` 出现再继续：`grep -m1 "Tomcat started on port" "$TMP/practice-seg3.log"`。
**注意**：`TaskStop` 只杀 Maven 包装进程，不杀 forked JVM——下次启动会以 `BUILD FAILURE / Process terminated exit code 1` 失败，症状是端口被占。

- [x] **Step 2：登录拿 token（段 2 的 token 已过期，24h 时效）**

```bash
BASE=http://127.0.0.1:8100
CAP=$(curl -s $BASE/auth/captcha)
KEY=$(echo "$CAP" | python -c "import sys,json;print(json.load(sys.stdin)['data']['captchaKey'])")
# 验证码明文在 Redis db0（gateway-access-topology 记录过），也可从 AuthModule 日志里读
CODE=$(redis-cli -n 0 --no-raw GET "captcha:$KEY" 2>/dev/null | tr -d '"')
echo "captcha code len = ${#CODE}"
curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"test918\",\"password\":\"<用户提供的口令>\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}" \
  | python -c "import sys,json;d=json.load(sys.stdin);open('$TMP/token.txt','w').write(d['data']['token'] if d.get('code')==1 else '')"
TOKEN=$(cat "$TMP/token.txt"); echo "token chars = ${#TOKEN}"
```

**口令不进仓库、不进日志**：需要它时向用户要一次，只写进这个仓库外的临时目录。**绝不 `echo $TOKEN`**，只打印长度。若 `redis-cli` 不在 PATH，用 `Seg3Probe` 那条路（仓库外 JDBC/RESP 探针）或从 `auth.log` 里读 AuthModule 打的明文验证码。

- [x] **Step 3：驱动脚本（响应落文件，控制台只打 ASCII）**

段 2 实测：把 UTF-8 的 JSON 直接管道给控制台工具会变成 GBK 乱码，所以**响应一律 `-o` 落文件**，屏幕只看 `code` 与计数。

```bash
call() {  # call <序号-名称> <method> <path> [json-body]
  local name=$1 method=$2 path=$3 body=$4
  if [ -n "$body" ]; then
    curl -s -X "$method" "$BASE$path" -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' -d "$body" -o "$TMP/resp-$name.json"
  else
    curl -s -X "$method" "$BASE$path" -H "Authorization: Bearer $TOKEN" -o "$TMP/resp-$name.json"
  fi
  python - "$name" "$TMP/resp-$name.json" <<'PY'
import json,sys
name, path = sys.argv[1], sys.argv[2]
raw = open(path, encoding="utf-8").read()
try:
    d = json.loads(raw)
except Exception:
    print(name, "NOT-JSON", raw[:80].encode("ascii", "replace").decode()); sys.exit(1)
data = d.get("data")
hint = ""
if isinstance(data, dict):
    hint = "keys=%d" % len(data)
    if "total" in data: hint += " total=%s size=%s" % (data["total"], len(data.get("list") or []))
    if "finished" in data: hint += " finished=%s" % data["finished"]
    if "favorited" in data: hint += " favorited=%s" % data["favorited"]
elif isinstance(data, list):
    hint = "len=%d" % len(data)
else:
    hint = "data=%s" % data
print("%-26s code=%s %s" % (name, d.get("code"), hint.encode("ascii", "replace").decode()))
PY
}
```

- [x] **Step 4：9 个阶段一接口按真实用户动线跑一遍**

```bash
call 01-subject-list      GET  "/practice/subject/list"
call 02-question-page     GET  "/practice/question/page?subjectId=1&pageNum=1&pageSize=20"
call 03-current-mode1     GET  "/practice/question/current?subjectId=1&mode=1"
call 04-current-mode3     GET  "/practice/question/current?subjectId=1&mode=3"
call 05-current-pending   GET  "/practice/question/current?questionId=5"
call 06-wrong-page        GET  "/practice/wrong/page?subjectId=1&status=1&pageNum=1&pageSize=20"
call 07-favorite-page     GET  "/practice/favorite/page?subjectId=1&pageNum=1&pageSize=20"
```

然后三段"只有真链路能证"的动线：

**(a) 断点真的会前进**（证明裁定二的 NOT EXISTS 在活服务上生效）：

```bash
QID=$(python -c "import json;d=json.load(open('$TMP/resp-03-current-mode1.json',encoding='utf-8'));print(d['data']['question']['questionId'])")
IDX=$(python -c "import json;d=json.load(open('$TMP/resp-03-current-mode1.json',encoding='utf-8'));print(d['data']['question']['index'])")
echo "next qid=$QID index=$IDX"
BODY=$(python -c "import json;print(json.dumps({'subjectId':1,'questionId':$QID,'userAnswerJson':'\"A\"','durationMs':1200,'mode':1},ensure_ascii=False))")
call 08-submit            POST "/practice/answer/submit" "$BODY"
call 09-current-mode1-2   GET  "/practice/question/current?subjectId=1&mode=1"
# 09 的 questionId 必须 != $QID，且 index == IDX+1（或被刷完 → finished=true）
```

> `08` 的 `userAnswerJson` 要按 `03` 响应里的 `type` 给：`type=1 → '"A"'`、`type=2 → '["A","C"]'`（整串作为 JSON 字符串提交）、`type=3 → 'true'`、`type=4 → '["关键词"]'`、`type=5 → '"我的作答"'`。给错题型是段 2 已裁定的 `40000`——若真的报 40000，把这条当成一条附取证记进执行记录（它证明判分口径在活服务上照样生效），换个该题型的合法答案再往下跑。

**(b) 自评回捞：从 `question/current` 拿 `pendingRecordId`，不看 submit 响应也能自评**（裁定一的落点，也是段 2 那个洞的封口）：

```bash
PR=$(python -c "import json;d=json.load(open('$TMP/resp-05-current-pending.json',encoding='utf-8'));print(d['data'].get('pendingRecordId'))")
echo "pendingRecordId = $PR"      # 真库里 qid 5 那条 id=22 的 -1 行（实测事实 4）
BODY=$(python -c "import json;print(json.dumps({'subjectId':1,'questionId':5,'recordId':$PR,'isCorrect':True}))")
call 10-self-judge        POST "/practice/answer/self-judge" "$BODY"
call 11-self-judge-again  POST "/practice/answer/self-judge" "$BODY"   # 必须 code=40900
call 12-current-pending-2 GET  "/practice/question/current?questionId=5"  # pendingRecordId 应变 null
```

**(c) 收藏 toggle 三次 → `true,false,true`（spec §9.3 验收清单第 3 条）**：

```bash
for i in 1 2 3; do
  call "13-favorite-toggle-$i" POST "/practice/favorite/toggle" \
    "{\"subjectId\":1,\"questionId\":1}"
done
# 逐次读 favorited，期望序列 true,false,true
python -c "
import json
seq=[json.load(open('$TMP/resp-13-favorite-toggle-%d.json'%i,encoding='utf-8'))['data']['favorited'] for i in (1,2,3)]
print('toggle seq =', seq)
assert seq==[True,False,True], seq"
call 14-favorite-page     GET  "/practice/favorite/page?subjectId=1"
```

**（d）错题置已掌握与它的幂等（spec §9.3 第 2 条的读侧半边）**：

```bash
WQ=$(python -c "import json;d=json.load(open('$TMP/resp-06-wrong-page.json',encoding='utf-8'));l=d['data']['list'];print(l[0]['questionId'] if l else '')")
if [ -n "$WQ" ]; then
  call 15-wrong-master    POST "/practice/wrong/master" "{\"questionId\":$WQ}"
  call 16-wrong-master-2  POST "/practice/wrong/master" "{\"questionId\":$WQ}"   # 两次都 true
  call 17-wrong-page-2    GET  "/practice/wrong/page?subjectId=1&status=2"
else
  echo "no wrong row for this user — 先答错一题再造行（用 (a) 的 submit 传一个错答案）"
fi
```

- [x] **Step 5：错误矩阵与越权/绕网关的负例**

```bash
call 20-no-token          GET  "/practice/question/page?subjectId=1"   # 用不带 Authorization 的裸 curl
curl -s -o "$TMP/resp-20-no-token.json" -w "HTTP %{http_code}\n" "$BASE/practice/question/page?subjectId=1"
call 21-missing-param     GET  "/practice/question/page"                       # 40000
call 22-pagesize-101      GET  "/practice/question/page?subjectId=1&pageSize=101"  # 40000，不夹取
call 23-pagenum-0         GET  "/practice/question/page?subjectId=1&pageNum=0"     # 40000
call 24-subject-missing   GET  "/practice/question/page?subjectId=999999"          # 40400
call 25-mode-exam         GET  "/practice/question/current?subjectId=1&mode=2"     # 40000
call 26-both-params       GET  "/practice/question/current?subjectId=1&mode=1&questionId=1"  # 40000
call 27-question-missing  GET  "/practice/question/current?questionId=999999"      # 40400
call 28-status-3          GET  "/practice/wrong/page?subjectId=1&status=3"         # 40000
call 29-master-missing    POST "/practice/wrong/master" '{"questionId":999999}'    # 40400
call 30-toggle-offline    POST "/practice/favorite/toggle" '{"subjectId":1,"questionId":999999}'  # 40400
# 绕过网关直连 8102：没有 X-User-Name → 40100（证明身份只从头部来）
curl -s -o "$TMP/resp-31-direct.json" -w "direct HTTP %{http_code}\n" \
  "http://127.0.0.1:8102/practice/question/page?subjectId=1"
python -c "import json;print('direct code =', json.load(open('$TMP/resp-31-direct.json',encoding='utf-8'))['code'])"
```

期望：`20-no-token` 是 **HTTP 401 + `{"code":401,...}`**（网关回的，不是 `Result`）；`31-direct` 是 `code=40100`。

- [x] **Step 6：响应字节扫描（脱敏的最终证据）**

```bash
cd "$TMP"
for f in resp-0*.json resp-1*.json resp-2*.json; do
  for bad in answerJson answer_json analysis isCorrect is_correct correctAnswerJson; do
    if grep -q "$bad" "$f"; then echo "LEAK $f contains $bad"; fi
  done
done
echo "scan done"   # 期望：一行 LEAK 都没有
```

**这道扫描必须只命中负例以外的文件**：`submit`/`self-judge` 的响应（段 2）本来就允许带真值，所以 Step 4 的 `08/10/11` 若被扫到 `analysis` 是正常的——执行记录里要写清"哪些响应按 spec 5.4 允许带真值"，别为了绿而漏掉真问题。**本段的六个新接口都不允许带**，其中 `resp-05/09/12`（取题）与 `resp-02/06/07/14/17`（列表）必须零命中。

- [x] **Step 7：库里状态取证 + 清理 SQL（交用户执行，本计划不代删）**

仓库外探针 `Seg3StateProbe.java`（照 `seg2/Seg2Evidence.java` 的写法，classpath 用段 2 记下的 Windows 形态 `-cp "target/classes;C:/Users/Administrator/.m2/repository/..."`）打印：
`test918(id=10)` 名下的 `qb_user_favorite` 行数与 `question_id`、`qb_user_wrong` 的 `question_id/status/wrong_count`、`qb_user_answer_record` 的 `question_id/is_correct/answer_mode` 末 10 行、`qb_user_subject_stat` 的 `total/correct/wrong`。

取证完在 `docs/superpowers/plans/...-03-...md` 的执行记录里附一条**给用户自行执行**的清理语句（本计划不跑）：

```sql
-- 交用户在 dev 库自行执行（段 3 端到端在 test918 名下新增的行）
DELETE FROM qb_user_favorite WHERE user_id = 10;
-- 错题与作答行是段 2 已授权留下的真实使用痕迹，默认不清；要清由用户决定范围
```

- [x] **Step 8：文档回写（写成带日期的"更正"，不做静默改写）**

1. `docs/superpowers/specs/2026-10-23-practice-exam-api-design.md`
   - §5.2 `answerStatus` 那一句追加 `3`，并给 `question/current` 的 `data` 补 `pendingRecordId` 与 `finished`，注明"2026-10-24 段 3 更正（裁定一/三/形态偏差）"。
   - §6.2 的 `MAX(sort_order)` SQL 旁加一段"段 3 证伪与替换"引用块，附 db-it 的对照实验结论（qid 6 已答、qid 1 未答 → 旧算法跳过 qid 1）。**保留原文**，在其下追加更正，不要覆盖历史。
   - §9.1.2 标注"段 3 已升级到真实响应体（`AnswerStateResponseGuardTest`，四个答题态 GET）"。
2. `docs/superpowers/plans/2026-10-23-practice-00-roadmap.md`
   - §3.10 里"段 3 必须为 `-1` 显式裁定映射"关闭为已完成，指向本计划裁定一。
   - 段 3 状态改"已完成"，测试基线数字更正为实测值。
   - 环境行补记：8102 需按 `with PID` 精确重启、token 24h 时效、验证码取自 Redis db0。
3. 项目记忆 `practice-exam-initiative.md` + `MEMORY.md` 索引行：段 3 完成、两条裁定（`answerStatus=3` + `pendingRecordId`、断点改 `NOT EXISTS`）、端到端取证与未做独立审查的说明。
4. 本计划：勾掉所有 `- [ ]`，把"执行记录"写实（每条命令的关键输出、测试数字、伪阳性输出、端到端响应文件名清单）。

- [x] **Step 9：报告改动，停在提交前**

`git status --short -- PracticeModule docs` → 输出抄进执行记录；**不 `git add`、不 `git commit`**。建议用户按三段提交：`读侧接口`、`端到端取证`、`文档回写`。

---

## Task 3.8：主线自查（本轮不做独立审查）

用户规则（2026-10-24）：**实现与审查都在本会话主线完成，不派子代理**。所以这一段是自查清单，不是派发审查；报告里要**明确写出"未做独立审查轮次"**，让结论的可信边界清楚。若用户当下明确要求派审查代理，那一次要求优先。

自查按"先看证据再看代码"的顺序，每条都要给出文件与行号，不接受"我看过了"：

- [x] **1. 契约一致**：六个接口的方法名/前缀与 spec §5.2 逐行对齐；有偏差的三处（`answerStatus=3`、`pendingRecordId`、`current` 的嵌套形态）都在 spec 里以带日期更正写明。
- [x] **2. 零泄漏**：`grep -rn "answerJson\|analysis\|isCorrect" PracticeModule/src/main/java/org/leon/practicemodule/controller/` 只在 `AnswerSubmitVO` 一类（判分响应）里出现，控制器与读侧 VO 一处不剩。
- [x] **3. 永不读真值**：`grep -rn "is_correct" PracticeModule/src/main/java/org/leon/practicemodule/mapper/QbQuestionOptionMapper.java` 只应命中注释，`selectDisplayOptions` 的列清单里没有它。
- [x] **4. 零 N+1**：三个分页服务里每条 mapper 调用都在循环外；`QuestionReadServiceTest`/`WrongBookServiceTest` 的 `times(1)` 断言在。
- [x] **5. 空 IN**：所有带 `<foreach>` 的查询都经过 `LatestAnswers` 或在服务里先判空；`LatestAnswersTest` 的 `verifyNoInteractions` 在。
- [x] **6. 幂等**：`master` 走条件更新 + 回读分流；`toggle` 走 DELETE 优先 + `ON DUPLICATE KEY UPDATE id=id` + 回读；两者都有并发双击的用例。
- [x] **7. 三态没漏**：`AnswerStatus` 是唯一映射点；`grep -rn "is_correct" PracticeModule/src/main` 的每处命中都对应一个已裁定的语义。
- [x] **8. 测试数与绿灯**：贴出 `mvn -o -pl PracticeModule -am test` 的 `Tests run:` 行与 db-it 的那一行（`Tests run: 8`）。
- [x] **9. 没越界**：`git diff --name-only` 不含 `AIModule/**`、`CommonJwt/**`、`GatewayModule/**`、任何 `pom.xml`、任何 `.sql`/DDL。
- [x] **10. 秘密**：`git diff -- PracticeModule docs | grep -iE "password|secret|token|bearer"` 的命中只有"从用户处索取/不打印长度以外内容"这类说明文字，没有任何真实值。

---

## 与其他段的接缝（本段留下的、后续必须接住的口子）

| 接缝 | 归谁 | 具体待办 |
|---|---|---|
| 考试取题 `GET /practice/exam/question` 要 `score` 与卷面 `index` | 段 5 | **不要复用 `buildCurrent`**：考试的 index 来自 `structure_json` 的卷面题号，不是科目内位置序号；`score` 来自卷面。它只需要 `PracticeMasker.toQuestionVO` + 两个字段，`answerStatus`/`lastUserAnswerJson` 在考试态无意义。 |
| 考试简答题若也落成 `is_correct=-1` | 段 5 | 段 2 的 `selfJudge` 只收 `answer_mode IN (1,3)`（已实测 40000），所以考试的待自评**现在无处可判**。段 5 必须三选一：考试简答题不计分、成绩单加"待自评"展示位、或放开自评闸门（要同时决定统计口径）。spec §5.6 的 `ExamResultVO` 目前没有这个字段，属 spec 空洞。 |
| `answerStatus` 用全部记录、断点只用练习记录 | 产品/段 5 | 可见后果：考完一卷再回刷题页，答题卡上那些题显示"对/错"但断点仍会把它们依次推来。这是 7.1 口径的直接推物，不是 bug。若产品不接受，改法是给 `answerStatus` 也加 mode 过滤（一处 SQL，但要重新取证）。 |
| `wrong/page`/`favorite/page` 的 `subjectId` 必填 | 产品 | 想要"全部科目"页签就把参数放宽为可选（去掉一次科目校验 + SQL 去掉一个 `AND`），属向后兼容的放宽，本段不预做。 |
| `question/page` 不含 `title` | 产品/前端 | 按 spec §5.2 原样。若答题卡要点格子就浮层显示题干，需要追加 `titleBrief` 字段——那是契约追加，要另写测试与守卫用例。 |
| 两个 db-it 各自重复 `open()/purge()`（`AnswerFlowDbIT`、`QuestionReadSqlDbIT`） | 用户裁定 | 抽 `PracticeDbTestSupport` 会牵出"db-it 的 MySQL 口令从哪来"（现在写死 `root`/空串在测试代码里，属本机 dev 默认，不是秘密，但值得在抽取时一并决定）。段 2 就挂起此项，本段仍不动。 |
| `log-impl: StdOutImpl` 把用户答案 echo 到 stdout | 用户裁定 | 8102 的日志里能看到 `user_answer_json` 原文。要不要关掉 StdOutImpl 由用户定，本段不碰 `application.yaml`。 |

---

## 执行记录（执行时逐项填，命令与输出一起留）

**填写规则**：每一项都要贴**真实命令输出**（可截断），不许写"通过"了事。控制台有 GBK 乱码风险，中文一律从落盘文件里读，不 `cat` 到屏幕。

### 环境与测试基线

- `"$MVN" -o -pl PracticeModule -am test` 的 `Tests run:` 行（起点 101，逐任务递增至最终值）
  **Task 3.1 后（2026-10-24）**：`Tests run: 101, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`。db-it 默认被排除，本任务单测数不变。
  **Task 3.2 后**：**115/115 全绿**（`PracticePagesTest` 5 + `AnswerStatusTest` 6（参数化 3 次调用 + 3 个用例）+ `PracticeMaskerTest` 6→9）。计划里预估的 114 少数了一条——`AnswerStatusTest` 有 6 次调用而不是 5。红灯阶段先实测到编译失败（`程序包PracticePages不存在` / `找不到符号`），实现后转绿，符合 TDD 顺序。
  另外两处执行期纠偏（都是"计划里的假设抵不过实测"）：
  1. `titleBrief` 的空白折叠理由改了：实测七道题干**没有换行也没有连续空格**（`seg3/TitleProbe.java`，长度 10~21 码点），所以折叠是对将来录题数据的防御，不是修现有 bug——注释与实现都按这个说法写。
  2. 真库锚点换成实测题干：qid 4 是"植物通过____作用将光能转化为化学能。"（正好 20 码点，走不截断分支），qid 6 是"这是用户 alice 的私有题，正确的是？"（21 码点，逼出截断分支）。计划里我自己编的那条 19 字锚点根本不会截断，测试第一次跑就把它抓出来了。
- `-Dtest=QuestionReadSqlDbIT -Dsurefire.excludedGroups=` 的 `Tests run:` 行（预估 8）与残留探针输出（三张表 `user_id=990002` 均 0 行）
  **实测 9/9 全绿**：`Tests run: 9, Failures: 0, Errors: 0, Time elapsed: 0.860 s`——计划里写的"期望 8"是我数错了方法数，实际 9 个用例，全部保留。
  残留（仓库外 `seg3/Seg3ResidueProbe.java`）：`user=990002` 的 `qb_user_wrong` / `qb_user_answer_record` / `qb_user_favorite` / `qb_user_subject_stat` **均 0 行**；段 2 的 `user=990001` 四条也均 0 行；`user=10 qb_user_favorite residue=0`。

**一处实测纠偏（计划的原假设错了，值得记下来）**：计划写 `insertIgnoreDuplicate` 第二次调用"回 0（空更新）"，实测**回 1**。原因是 Connector/J 默认 `useAffectedRows=false`，`UPDATE` / `ON DUPLICATE KEY UPDATE` 回的是 **found rows** 而非 affected rows，`id = id` 这种"什么都没改"的语句照样回 1。影响面：**任何"看返回值判断是否真的新建了行"的写法都不可信**。Task 3.6 的 `toggle` 因此不看插入返回值、回读 `countByUserAndQuestion`——这条实现决定现在有了实测依据，落点在 db-it 的 `favoriteUpsertDeleteAndReadBack` 注释。对照：`deleteByUserAndQuestion` 第二次确实回 0（`DELETE` 无 found-rows 语义），该断言照旧成立。

### 伪阳性（守卫有牙的证明，缺这两段就等于没做）

- Task 3.3 Step 12：**已做，实测到红灯**。给 `QuestionItemVO` 临时加 `private String answerJson;` 并在 `toItems` 里 `item.setAnswerJson(row.tagsJson())`，跑 `AnswerStateResponseGuardTest`：
  `Tests run: 3, Failures: 1` → `响应体泄漏了 answerJson：{"code":1,"msg":"success","data":{...,"list":[{"index":1,"questionId":1,"type":1,"difficulty":2,"tags":["语法"],"answerStatus":0,"favorited":false,"answerJson":"[\"语法\"]"}]}}`
  回滚后 `grep -c answerJson` 在 VO 与 impl 都是 0，全量 **129/129 绿**（Task 3.2 的 115 + `LatestAnswersTest` 2 + `QuestionReadServiceTest` 7 + 控制器 2 + 守卫 3）。
  顺带记两条执行期纠偏：① 计划预估 `beyondLastPage` 的 `pages=2` 是错的，`PageVO` 按 (total+size-1)/size 算，4 题 / 每页 20 → **1 页**，断言已按真值改；② 守卫的 `question/page` 用例额外钉了 `"answerStatus":0` 与 `"index":1` 两个正向锚点，防止"响应是空壳但负向断言全绿"。
- Task 3.4 Step 5：**已做，实测到红灯**。给 `QuestionVO.OptionVO` 临时加 `private Integer isCorrect;` 并在 `PracticeMasker.toOptions` 里 `ov.setIsCorrect(option.getIsCorrect())`，跑守卫：
  `Tests run: 4, Failures: 1` → `响应体泄漏了 isCorrect：{"code":1,...,"options":[{"optionKey":"A","content":"选项甲","isCorrect":1},{"optionKey":"B","content":"选项乙","isCorrect":0}],...,"answerStatus":3,"lastUserAnswerJson":"\"我的上次作答\"","pendingRecordId":22}`
  这份字节快照本身就是证据：脱敏器一旦多复制一个真值列，守卫当场红，且报文里能直接看出是哪一列。回滚后 `grep -rn TEMP PracticeModule/src` 无残留，全量 **140/140 绿**（129 + `QuestionReadServiceTest` 9 + 控制器 1 + 守卫 1）。

### 对照实验（裁定二的证据）

- `breakpointSkipsNothingWhereOldAlgorithmSkips` 通过时 `oldNext` / `newNext` 的实际值（`oldNext` 应非 1、`newNext` 应为 1）
  **真库实测（合成用户 990003，跑完即删，仓库外 `seg3/BreakCmp.java`）**：科目 1 的启用题与 sort_order 为 `qid 1 → 1`、`qid 6 → 1`、`qid 2 → 2`、`qid 5 → 3`（并列就发生在 1 与 6）；只答过 qid 6 时 `oldMax = 1` → **`oldNext = 2`（跳过未作答的 qid 1）**，而 **`newNext = 1`**。这不是"算法不够优"而是**可证明的漏题**：用户会永远刷不到 qid 1。db-it 同时钉住 `assertEquals(1L, newNext)`、`assertNotEquals(1L, oldNext)` 与 `assertEquals(1L, oldMax)`（后者钉住前提数据）。


**Task 3.6 后：全量 165/165 绿**（153 + `FavoriteServiceTest` 8 + `PracticeFavoriteControllerTest` 3 + 守卫 `favorite/page` 1）。四个答题态 GET 现在都在同一份守卫清单里跑真实字节。
一条执行期设计更正（计划原文会让守卫出现假阳性，已按实现改）：`wrong/page` / `favorite/page` 的用例**不把 `ANALYSIS_SENTINEL` 塞进 `title`**。`titleBrief` 的原料就是 `title`，而题干本身是允许外泄的字段——把哨兵写进题干等于"谁在题干里打了这几个字谁违规"，那是假阳性而不是防线。值级哨兵仍然有效，位置在 `question/current` 的选项（`isCorrect` 那条伪阳性已取证）与 `answerJson`/`analysis` 两个真值列上。
`toggle` 的实现按 Task 3.1 的实测结论写：插入返回值一律不看，只认 `countByUserAndQuestion` 的回读。

**Task 3.5 后：全量 153/153 绿**（140 + `SubjectGuardsTest` 1 + `WrongBookServiceTest` 8 + `PracticeWrongControllerTest` 3 + 守卫 `wrong/page` 1）。
`SubjectGuards` 按计划在第 3 处出现时才抽出，`QuestionReadServiceImpl.requireEnabledSubject` 改成一行委托，`QuestionReadServiceTest` 的 16 条不留红——这正是"规则三次"的时机判断生效的证据。
执行期一次编译红灯值得记：守卫类里新用 `isNull()` 时忘了 `import static org.mockito.ArgumentMatchers.isNull`（这个类的 mockito 静态导入是逐条列的，不像别的测试用 `.*`），报"找不到符号"而不是"方法不存在"，容易误判成 mapper 缺方法。

### 端到端（Task 3.7，2026-10-24 实测）

**环境**：8102 先按 `grep -oh "with PID [0-9]*"` 取到持有进程 15368 并 `taskkill`，再 `mvn -o -DskipTests install`（聚合模块 8 个全 SUCCESS，含另一会话未提交的 AIModule）+ `mvn -o -pl PracticeModule spring-boot:run`；新实例 **PID 23372**，日志 `Tomcat started` + `nacos registry ... 192.168.75.10:8102 register finished` + `Started PracticeModuleApplication in 5.349 seconds`。
坑位复现：后台包装把这次启动报成 `exit code 1`，但 forked JVM 正常在监听——正是段 2 记过的"Maven 父进程与应用 JVM 生命周期不一致"的镜像症状（那次是杀不掉，这次是误报失败）。判断依据只看 `netstat` 的 8102 持有者与日志里的 `Started`，不看包装层退出码。
**登录**：验证码明文走最小 RESP 客户端从 Redis db0 的 `auth:captcha:<key>` 取（本机没有 `redis-cli`），`test918` 登录 `code=1`，`token chars = 219`（**token 本体不进日志、不进仓库**）。

**阶段一 9 个接口全部 `code=1`**（响应快照都在仓库外 `seg3/resp-*.json`，屏幕只打 ASCII）：

| # | 调用 | 结果 |
|---|---|---|
| 01 | `subject/list` | `code=1 len=2` |
| 02 | `question/page?subjectId=1` | `code=1 total=4 listLen=4` |
| 03 | `question/current?subjectId=1&mode=1` | `code=1 finished=true`（科目 1 四道题段 2 已刷完，断点正确回结束态） |
| 04 | `question/current?subjectId=2&mode=1` | `code=1 qid=7 index=2 ansStatus=0` |
| 05 | `answer/submit`（qid 7 故意答错） | `code=1 isCorrect=false recordId=38` |
| 06 | `question/current?subjectId=2&mode=1` | `finished=true` —— **断点确实前进了** |
| 07/14 | `wrong/page?subjectId=2&status=1` | 答错后 `total=1`，置掌握后 `total=0` |
| 08 | `question/page?subjectId=2` | `total=3` |
| 09 | `question/current?questionId=7`（模拟刷新重进） | `ansStatus=2` |
| 10 | `question/current?subjectId=2&mode=3` | `qid=7`（未掌握错题优先于新题） |
| 11/12 | `wrong/master` 连点两次 | `code=1 data=true`、`code=1 data=true`（幂等） |
| 13 | `wrong/page?subjectId=2&status=2` | `total=1` |
| 15 | `question/current?...&mode=3`（已掌握后） | `finished=true`（错题清空后回落断点，断点也刷完） |
| 16 | `question/current?questionId=5` | `ansStatus=3 pending=22` —— **裁定一的活体证据** |
| 17 | `answer/self-judge`（recordId=22） | `code=1`（只回 `statDelta`） |
| 18 | 同参再提一次 | **`code=40900`**（条件更新幂等闸门） |
| 19 | `question/current?questionId=5` | `ansStatus=1 pending=None`（自评后不再待评） |
| 20×4 | `favorite/toggle`（qid 1）四次 | **`[true,false,true,false]`**（验收清单要求的前三次即 `true,false,true`） |
| 21 | `favorite/page?subjectId=1` | `total=1`（第三次点击后的状态） |

**负例矩阵全部按 §5.7 落位**：缺 `subjectId` → `40000`；`pageSize=101`/`pageSize=0`/`pageNum=0` → `40000`；`subjectId=999999`（三个列表都是）→ `40400`；`current?mode=2` → `40000`；`subjectId`+`questionId` 同时给 → `40000`；两个都不给 → `40000`；`questionId=999999` → `40400`；`wrong/page?status=3` → `40000`；`wrong/master` 未知题 → `40400`；`favorite/toggle` 未知题 → `40400`；**不带 token 经网关 → HTTP 401 + `{"code":401}`**；**绕过网关直连 8102（无 `X-User-Name`）→ `40100`**。

**响应字节扫描**（`seg3/scan3.py`）：`scanned=42 leaks=0`。跳过的是 `submit`/`self-judge`/登录/验证码四类——前两类按 §5.4 允许带真值，后两类不属于本模块。`lastUserAnswerJson` 含子串 `AnswerJson`（大写 A），与禁止项 `answerJson` 大小写敏感不冲突，已核对。

**库里状态**（`seg3/Seg3State.java`，只打数字）：
- `qb_user_favorite` `user_id=10` **0 行**（第四次点击落在关闭态，与接口回报一致；全程从未超过 1 行，`uk_user_question` 兜住并发双击）
- `qb_user_wrong` `user_id=10`：`qid=1 count=3 status=2`（段 2 留的）+ `qid=7 count=1 status=2`（本段新增并置掌握）
- `qb_user_answer_record` 末 12 行：`id=38 subj=2 qid=7 isCorrect=0 mode=1` 是本段产生；**`id=22 subj=1 qid=5` 从 `-1` 变 `1`**，即自评回填生效
- `qb_user_subject_stat`：科目 1 `total=11 correct=8 wrong=3`、科目 2 `total=3 correct=2 wrong=1`——科目 2 的 +1 来自练习作答，科目 1 的 `correct` +1 来自自评那一刻（§5.3 推论在活链路上成立）
- 全库 `is_correct=-1` 的行数：**0**。原来当锚点的那条 `id=22` 已被本次自评消费掉，**要重跑 (b) 自评回捞流程必须先新答一次简答题**（`submit` 一个 `type=5` 的题即可重新产生待自评行）。

**给用户的清理 SQL（本计划不代执行）**：端到端在 `test918(id=10)` 名下留下的真实使用痕迹是 `qb_user_answer_record` 的 `id=38`、`qb_user_wrong` 的 `qid=7` 行、以及科目 2 的统计增量；收藏表当前 0 行无需清理。要抹掉本次端到端：

```sql
DELETE FROM qb_user_answer_record WHERE id = 38;
DELETE FROM qb_user_wrong WHERE user_id = 10 AND question_id = 7;
UPDATE qb_user_subject_stat SET total_count = total_count - 1, wrong_count = wrong_count - 1
 WHERE user_id = 10 AND subject_id = 2;
-- 自评回填无法只回滚统计（它会同时改记录行、统计与错题），一般不清
```

**收尾复跑（2026-10-24 11:26，本段最后一次取证）**：单测 `Tests run: 165, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`；三个 db-it 一起 `Tests run: 16`（`AnswerFlowDbIT` 3 + `QbQuestionMapperDbIT` 4 + `QuestionReadSqlDbIT` 9）；仓库外 `seg3/Seg3Residue3.java` 复测 990001/990002/990003 × 四张表 **全部 residue=0**，全库 `is_correct=-1` **0 行**；8102 仍在监听（`netstat` 的持有者 PID 23372，与启动日志的 `with PID` 一致），六个 GET 经 8100 复跑全部 `http=200 code=1`——`subject/list` len=2、`question/page?subjectId=1` total=4、`current?subjectId=2&mode=1` finished=True、`current?questionId=5` ansStatus=1（自评后不再待评）、`wrong/page?subjectId=1&status=2` total=1、`favorite/page?subjectId=1` total=0（与第四次 toggle 落在关闭态一致）。
一个自己踩到的一次性坑：冒烟时把 `Authorization` 写成裸 token 会被网关回 `401`，必须是 `Bearer <token>`——这不是回归，是网关鉴权在正常工作；顺带解码该 JWT 的 `exp` 距当时还有 85388 秒，可排除过期。（注：token 明文只存在于仓库外 `seg3/token.txt`。）


- 重启 8102 用的 PID 与 `Tomcat started on port 8102` 的时间点 → 见上"环境"段：旧持有者 15368 已杀，新实例 **PID 23372**，日志三条锚点（`Tomcat started` / nacos register / `Started ... 5.349 seconds`）都贴了。
- 登录：`token chars = 219`（只记长度，token 本体在仓库外 `seg3/token.txt`，不入日志不入仓库）。
- Step 4 全部响应文件的 `code` 汇总 → 上表 21 行正例全部 `code=1`；逐条原始响应落盘在 `seg3/resp-*.json`（42 个文件），驱动脚本 `seg3/e2e3b.py` 的 stdout 只有 ASCII。
- (a) 断点前进：科目 2 起始 `qid=7 index=2` → 作答 `recordId=38` → 同一调用变 `finished=true`（表第 04/05/06 行）。
- (b) 自评回捞：`pendingRecordId=22` → 第一次 `self-judge` `code=1` → 第二次 **`code=40900`** → 之后 `ansStatus=1 pending=None`（表 16~19 行）。库里同步证据：`id=22` 的 `is_correct` 由 `-1` 变 `1`。
- (c) toggle 四次：`seq = [true,false,true,false]`；第四次后 `qb_user_favorite` 里 `user_id=10` 为 **0 行**（与接口回报一致）。验收清单要求的前三次即 `[true,false,true]`，那时 `favorite/page` 的 `total=1`（表 20/21 行）。
- (d) 错题 `master` 两次都 `true`，`status=2` 页 `total=1` 查得到该题；`status=1` 页随之 `total=0`（表 11~14 行）。
- Step 5 负例矩阵逐条的 `code` → 见上"负例矩阵"段，13 条全部按 §5.7 落位；`20-no-token` 记的是 **HTTP 401**（网关返回 `{"code":401}`），不是 `Result.code`。
- Step 6 扫描：`scanned=42 leaks=0`，`LEAK` 行为空。按 §5.4 允许带真值、因此被扫描器跳过的响应类别是 `answer/submit`、`answer/self-judge`、登录与验证码四类（后两类不属本模块）；`resp-05-submit-wrong.json` 与 `resp-17-self-judge.json` 就是那两类样本。
- Step 7 库里取证输出与给用户的清理 SQL → 见上"库里状态"段与其后的 SQL 块。

### 文档回写（2026-10-24 已做，全部写成带日期的"更正"，不覆盖历史）

- `docs/superpowers/specs/2026-10-23-practice-exam-api-design.md`
  - **§5.2**：在 `answerStatus` 那句之后追加"段 3 更正"四条——`answerStatus=3`、`current` 的 `data` 改嵌套 `question` 并加 `pendingRecordId`/`finished`、`pendingRecordId` 仅 `answer_mode IN (1,3)`、`index` 是位置序号；另记 `subjectId` 必填、`wrong/page.status` 可选、`pageSize>100` 报 `40000` 不夹取。
  - **§6.2**：保留原 `MAX(sort_order)` SQL 原文，在其下追加"段 3 更正（上述算法作废并证伪）"，附实测位次数据、对照结论（旧算法给 qid 2、新算法给 qid 1）、替换后的 `NOT EXISTS` SQL，并写明它与 5.2 的 `answerStatus` **故意**口径不同（考试不消耗练习进度，但考试作答是"最近一次作答"）。
  - **§9.1.2**：追加"段 3 已升级"，写清 `AnswerStateResponseGuardTest` 的组装方式（真控制器 + 真服务 + 真 masker + mock mapper）、覆盖的四个 GET、两处非空转证据与两次伪阳性报文，以及"`titleBrief` 不拿题干做值哨兵"的边界理由。
  - **§9.3**：阶段一相关的三条验收项勾成 `[x]` 并附实测摘要（9 接口 `code=1` + 42 响应零泄漏；错题流转含 `wrong/master` 连点两次；toggle 四次 `[true,false,true,false]` 且表里 ≤1 行）。
- `docs/superpowers/plans/2026-10-23-practice-00-roadmap.md`
  - 段 3 状态：`待执行` → **`已完成`（2026-10-24，165 单测 + 9 db-it，端到端取证，§6.2 断点算法已证伪并替换）**。
  - §3 第 10 条（roadmap 的全局实测修正）：把"段 3 必须裁定 `-1` 映射"关闭为已裁定，并保留"段 5 仍须决定考试侧如何表达待自评"这条未闭合项。
  - 新增第 **17** 条（`sort_order` 科目内不唯一，连带分页必须有 `id` 兜底）与第 **18** 条（`ON DUPLICATE KEY UPDATE` 返回值不可当判据，Connector/J `useAffectedRows=false`）。
  - 运行环境表：PracticeModule 行更新为段 3 结束时的实例与重启口诀（`install` → `-pl PracticeModule spring-boot:run`；只认日志 `with PID`）。
- 项目记忆：`practice-exam-initiative.md`（段 3 完成、两条裁定、两条实测纠偏、"未做独立审查"）与 `MEMORY.md` 索引行；`local-service-topology.md` 补 `spring-boot:run` 后台包装可能报 exit 1 而 forked JVM 仍在监听的坑位。

### 自查与审查状态（Task 3.8 十条逐条结论，均带文件:行号）

1. **契约一致**：控制器端点恰好 9 个，与 spec §5.2 一一对上——`/practice/subject/list`（`PracticeSubjectController.java:31`）、`/practice/question/page`（`PracticeQuestionController.java:33`）、`/practice/question/current`（同文件 `:46`）、`/practice/answer/submit`、`/practice/answer/self-judge`（`PracticeAnswerController.java:32/39`）、`/practice/wrong/page`、`/practice/wrong/master`（`PracticeWrongController.java:31/41`）、`/practice/favorite/page`、`/practice/favorite/toggle`（`PracticeFavoriteController.java:32/41`）。与原文不同的三处（`answerStatus=3`、`pendingRecordId`、`current` 的嵌套形态）都已在 spec 里写成带日期的更正。
2. **零泄漏**：控制器包 `grep -rnE "answerJson|analysis|isCorrect" controller/` **零命中**；`pojo/vo/` 下大小写敏感检索只有两处——`AnswerSubmitVO.java:12/13/14`（判分响应，§5.4 明确允许带 `isCorrect`/`correctAnswerJson`/`analysis`）与 `QuestionVO.java:10` 的一行**注释**。读侧四个 VO（`QuestionItemVO`/`QuestionCurrentVO`/`WrongItemVO`/`FavoriteItemVO`）一个真值字段都没有。诚实标注一个边界：`QuestionCurrentVO.java:23` 与 `WrongItemVO.java:20` 的 `lastUserAnswerJson` 在**大小写不敏感**检索里会命中，因为它含子串 `AnswerJson`（大写 A）——那是用户自己的作答原文，是裁定允许外泄的字段，守卫清单按大小写敏感判定，与 MySQL 侧的禁止字段口径一致。
3. **永不读真值**：`QbQuestionOptionMapper` 的 `is_correct` 只出现在注释，两条 SQL 的列清单是 `option_key` 与 `id, question_id, option_key, content, sort_order`；真库断言 `displayOptionsCarryNoCorrectness` 实测实体 `isCorrect == null`。选项实体也没有被 `BaseMapper` 的通用查询整体读过（全仓只有 `selectOptionKeys` 与 `selectDisplayOptions` 两处调用：`AnswerServiceImpl.java:67`、`QuestionReadServiceImpl.java:139`）。
4. **零 N+1**：三个分页服务每条补齐查询都在循环外，`times(1)` 断言共 9 处（`QuestionReadServiceTest` 的 `:166-169`、`WrongBookServiceTest.java:119-120`、`FavoriteServiceTest.java:102`、`LatestAnswersTest.java:44-45`）。
5. **空 `IN`**：唯一实现点 `LatestAnswers`（两方法先判空集合再访库），`LatestAnswersTest.java:31` 用 `verifyNoInteractions` 钉住"一次库都不访"；服务侧还有三条"空行集不打 `IN ()`"用例——`QuestionReadServiceTest.concurrentShrinkDoesNotReachIn`（`:174`，计数与取行之间被并发下架，`:180` 断零交互）、`emptySubject`（`:115`）、`beyondLastPage`（`:128`），后两条各自钉住"total 照回真值但不查分页行"。
6. **幂等**：`wrong/master` 走 `masterIfUnmastered` 条件更新 + 回读分流（`WrongBookServiceImpl.master`），单测覆盖命中/已掌握/无记录/脏状态四路；`toggle` 走 DELETE 优先 + `insertIgnoreDuplicate` + 回读（`FavoriteServiceImpl.toggle`），单测覆盖"删掉就回 false 且不查题目状态"。网关实跑：master 连点两次都 `true`；toggle 四次 `[true,false,true,false]`，`qb_user_favorite` 里 `user_id=10` 始终 ≤1 行。
7. **三态没漏**：`AnswerStatus` 是唯一的 `-1 → 3` 映射点；全模块 `is_correct` 的命中只有——幂等条件更新（`QbUserAnswerRecordMapper.java:20-21`）、批量最近作答（同文件 `:33`）、两个实体的列映射、`PracticeMasker` 的禁止字段清单、以及注释。没有第二处各自解释三态的地方。
8. **绿灯数字**（本段收尾实跑）：单测 `Tests run: 165, Failures: 0, Errors: 0, Skipped: 0`；三个 db-it 一起跑 `Tests run: 16`（`QbQuestionMapperDbIT` 4 + `QuestionReadSqlDbIT` 9 + `AnswerFlowDbIT` 3）。合成用户 990001/990002/990003 三张表零残留。
9. **没越界**：本段改动只落在 `PracticeModule/{main,test}` 与三个 practice 文档。工作树里另有 `AIModule/**` 与两个 ai-chat 文档被改动、且数十个 AIModule 文件已在索引里——那是另一会话的成果，我没有 `git add`/`commit`/回滚任何一项。**需要用户注意**：本计划文件目前状态是 `AM`（已被那次 broad `git add` 顺手加进索引）。我没有动它，用户提交前可自行决定 `git restore --staged` 与否。
10. **秘密**：只扫本段新增行（`git diff | grep '^+'` + 未跟踪新文件全文），`password|secret|123456|bearer|token=` **零命中**。工作树里唯一含 `test918 / 123456` 的那行是段 1 之前就提交过的 roadmap 既有内容（dev 应用测试账号，不是 JWT 密钥也不是数据库口令），本段没有把它复制到任何新文件。驱动脚本在仓库外，口令走环境变量，日志与文档只记 `token chars = 219`。

**审查状态：本轮未做独立审查轮次**（用户规则 2026-10-24：不派子代理，主线实现 + 自查）。上面十条都是我自己对自己代码的判断，可信边界到此为止；段 2 那种独立审查曾抓到过我闭眼修不住的类缺陷（Jackson 宽松解析 vs `JSON_VALID`），若用户想要同等级别的复核，需要显式提出。

---

## 自检（对本计划本身的复核）

- **spec 覆盖**：§5.2 阶段一 9 个接口 → 段 1（`subject/list`）+ 段 2（`submit`/`self-judge`）+ 本段 Task 3.3/3.4/3.5/3.6 六个，全覆盖；§6.2 断点 → Task 3.1/3.4；§5.4 脱敏与 §9.1.2 守卫升级 → 各任务的 `AnswerStateResponseGuardTest`；§9.3 中属于阶段一的三条（答题态零答案字段、错题状态流转、收藏 toggle 三次）→ Task 3.7 Step 4/6；§7.1 的读侧后果 → 接缝表第 3 行；§5.7 错误矩阵里本段能触发的四类（40000/40100/40400/40900）→ Task 3.7 Step 5。
- **无占位**：全文没有"待补/TBD/适当校验"。唯一需要用户当下提供的值是 `test918` 的登录口令（Task 3.7 Step 2 明写"向用户要一次，只写进仓库外目录"）。
- **类型一致**：`selectEnabledPage(subjectId, offset, pageSize)`、`selectWrongPage(userId, subjectId, status, offset, pageSize)`、`selectFavoritePage(userId, subjectId, offset, pageSize)` 的签名与 3.3/3.5/3.6 调用处逐一对过；`LatestAnswers.byQuestion` / `positions` 同；`QuestionReadServiceImpl` 的 4 参（Task 3.3）与 7 参（Task 3.4）构造器都给出对应的测试工厂改动；`AnswerStatus.PENDING_SELF_JUDGE = 3` 与 VO 注释、Task 3.1 db-it 断言一致；`PageVO.of(list, total, pageNum, pageSize)` 四处调用参数顺序一致；`FavoriteToggleVO.isFavorited()`（原始 boolean → Lombok `isXxx`）与段 2 记过的访问器规则一致。
- **两处已知返工点**（诚实标注，执行时别当新发现）：Task 3.4 改 `QuestionReadServiceImpl` 构造器与测试工厂；Task 3.5 把 `requireEnabledSubject` 抽成 `SubjectGuards`。都是"第 3 次出现才抽"的时机判断。

---

## 执行选择（本计划怎么落地）

按用户规则（2026-10-24）：**主线执行，不派子代理；写完留在工作树，由用户提交**。所以只有一条路：

**用 superpowers:executing-plans 在本会话逐任务执行**——每个 Task 跑完红灯→绿灯→报告改动，停下来让用户过目（需要时由用户提交），再进下一个 Task。Task 3.7 需要两样外部条件：MySQL/网关/8102 在跑，以及用户给一次 `test918` 的口令。
