# 段 2：判分内核、脱敏出口与作答落库 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务执行本计划。步骤用 `- [ ]` 语法跟踪。

**Goal:** 落地 `AnswerGrader`（五题型唯一判分口径）、`PracticeMasker`（答题态唯一出口 + 脱敏守卫测试）、`AnswerService`（逐题落库 + 错题本/科目统计联动）与 `POST /practice/answer/submit`、`POST /practice/answer/self-judge` 两个接口，并把"答错两次 `wrong_count=2`、答对置已掌握、已掌握再错回退"从文档约定变成会红的测试。

**Architecture:** 判分与脱敏是两条只读单方向的内核——`AnswerGrader` 只认 `qb_question.answer_json`，`PracticeMasker` 只做白名单复制，两者都不注入任何 bean，单测直接 `new`。写路径收在 `AnswerServiceImpl` 一个类里：一次 `@Transactional` 内完成"记录行 + 错题本 upsert + 统计 upsert"，统计与错题本的累加用 MySQL `ON DUPLICATE KEY UPDATE` 原子完成（三张表的唯一键已实测：`qb_user_wrong.uk_user_question`、`qb_user_subject_stat.uk_user_subject`）。简答题不自评不判分，靠 `is_correct = -1` 哨兵与条件更新保证自评恰好生效一次。

**Tech Stack:** Java 21 / Spring Boot 3.2.4 / MyBatis-Plus 3.5.17 / MySQL 9.1.0（`STRICT_TRANS_TABLES`）/ Jackson（`spring-boot-starter-web` 自带，不加新依赖）/ JUnit 5 + Mockito。

**Spec:** `docs/superpowers/specs/2026-10-23-practice-exam-api-design.md`（§5.3 判分、§5.4 脱敏、§5.7 子码、§6.1 流程、§7.1/§7.3 口径、§9.1 测试）
**Roadmap:** `docs/superpowers/plans/2026-10-23-practice-00-roadmap.md`（全局约束与 15 条实测修正全部隐含适用，本文件不重复）

---

## 本段文件结构

| 文件 | 责任 | 动作 |
|---|---|---|
| `.../service/AnswerGrader.java` | 五题型判分唯一口径（纯对象） | Create |
| `.../pojo/vo/QuestionVO.java` | 答题态脱敏 VO（嵌套 `OptionVO`） | Create |
| `.../service/PracticeMasker.java` | 实体 → VO 白名单复制 | Create |
| `.../pojo/AnswerSubmitReq.java` `.../pojo/SelfJudgeReq.java` | 请求体（手写 pojo，不引 validation） | Create |
| `.../pojo/vo/StatDeltaVO.java` `AnswerSubmitVO.java` `SelfJudgeVO.java` | 响应 VO | Create |
| `.../mapper/QbUserAnswerRecordMapper.java` | + `judgeIfPending` 条件更新 | Modify |
| `.../mapper/QbUserWrongMapper.java` | + `upsertWrong` / `masterIfUnmastered` / `selectByUserAndQuestion` | Modify |
| `.../mapper/QbUserSubjectStatMapper.java` | + `upsertAccumulate` / `selectByUserAndSubject` | Modify |
| `.../mapper/QbQuestionOptionMapper.java` | + `selectOptionKeys`（**不读 `is_correct`**） | Modify |
| `.../service/AnswerService.java` `.../impl/AnswerServiceImpl.java` | 作答落库与联动 | Create |
| `.../controller/PracticeAnswerController.java` | 两个 POST | Create |
| 测试：`AnswerGraderTest` `PracticeMaskerTest` `AnswerServiceTest` `PracticeAnswerControllerTest` `AnswerFlowDbIT` | 见各任务 | Create |

**本段不碰**：任何 DDL、`qb_paper`/`qb_exam_attempt`（段 4）、`QuestionService`/`WrongBookService`/`FavoriteService`（段 3）、`ExamService`（段 5）、AIModule 与 `CommonJwt` 的文件。

---

## 前置：环境与关卡

```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
MVN="/c/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
```

**本段无 DDL、无 pom 改动。**两个关卡：

1. **真库写测试**：`AnswerFlowDbIT` 会往 dev 库的 `qb_user_wrong`/`qb_user_answer_record`/`qb_user_subject_stat` 写入合成 `user_id=990001` 的行（远高于真实 id，不撞任何现存用户），测试前后各清一次。这是段 2 唯一写 dev 库的动作，执行 Task 2.3 前向用户明示。
2. **端到端会往 test918(id=10) 名下写真实作答数据**（错题 1 行、统计 1 行、若干记录行）。这是接口的正常产物；如需清理，执行记录里附一条 `DELETE ... WHERE user_id=10` 的 SQL 供用户自行执行。

**本段实测事实（2026-10-23，仓库外 JDBC 探针）**：

- `qb_user_wrong` 唯一键 `uk_user_question(user_id, question_id)`；`qb_user_subject_stat` 唯一键 `uk_user_subject(user_id, subject_id)`；`qb_user_answer_record` **除主键外无唯一键**（所以只 insert，不做 upsert）。
- `qb_user_answer_record.is_correct` 是**有符号** `tinyint NOT NULL` → `-1` 在 `STRICT_TRANS_TABLES` 下可写（裁定一的前提）。
- `qb_question.type` / `status` / `answer_json` 三个列均 `NOT NULL` → `AnswerGrader` 拆箱 `type` 不会 NPE，判分器不需要为 null 题型兜底。

**两条编译器逼出来的实测修正（Task 2.4 执行时确立，段 3/段 5 直接沿用）**：

1. **MyBatis-Plus 3.5.17 的 `BaseMapper` 同时有 `insert(T)` 与 `insert(Collection<T>)`** —— mock 里写 `insert(any())` 报"对 insert 的引用不明确"，必须写成 `insert(any(QbUserAnswerRecord.class))`。将来批量 insert 上线时这条歧义会更常见。
2. **Lombok 对原始 `boolean` 生成 `isXxx()`，对包装 `Boolean` 生成 `getXxx()`。** `AnswerSubmitVO.isCorrect`（Boolean，可为 null）取 `getIsCorrect()`，而 `needSelfJudge`/`wrongMastered`（原始 boolean）取 `isNeedSelfJudge()`/`isWrongMastered()`。同一个 VO 里两种访问器名，写测试时容易踩。
- 题目种子（id/科目/题型/答案）：`1 语文 1 "A"`、`2 语文 2 ["A","C"]`、`3 数学 3 true`、`4 数学 4 ["光合作用"]`、`5 语文 5 "参考答案：要点一；要点二"`、`6 语文 1 "B"`、`7 数学 1 "C"`；题型 1/2 的选项集都是 `A,B,C,D`。`test918(id=10)` 在三张用户表中零行，端到端是干净起点。

**命令模板**（同段 1，`-Dtest=` 必须带两个 `failIf` 开关）：

```bash
"$MVN" -pl PracticeModule -am test -Dtest=<类名> \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```

---

## 段 2 的两个设计裁定（与 spec 的偏差，执行前需用户知情）

### 裁定一：简答"待自评"用 `is_correct = -1` 哨兵，取代 spec 的"暂记 0"

spec §5.3 写的是"`type=5` 暂记 0"。**该写法不可实现**，与它自己后一句（"统计与错题本要跳过未回填的记录，错题联动发生在 `self-judge` 那一刻"）直接矛盾：自评"错"回填后 `is_correct` 也是 0，与"未自评"不可区分——跳过逻辑会把已判错的记录永久漏计，不跳过则会重复计数。

**替代方案**：`submit` 对 `type=5` 写 `is_correct = -1`（待自评），`self-judge` 用条件更新回填：

```sql
UPDATE qb_user_answer_record SET is_correct = #{isCorrect}
 WHERE id = #{recordId} AND user_id = #{userId} AND is_correct = -1
```

影响行数为 0 即"已自评过"，幂等闸门不用加列、不用加表，并发重试下天然正确。

**代价三条，必须留痕**：① `is_correct` 变成三态（`-1` 待自评 / `0` 错 / `1` 对），任何未来的读者都要知道 `-1` 不是"错"；② 段 3 的 `answerStatus`（0/1/2）无法表达"待自评"，段 3 必须显式裁定映射（见文末"边界"）；③ 该值写进共享表，将来 AIModule 若读作答记录做个性化，必须同样理解三态。

### 裁定二：`statDelta` 是"更新后的累计快照"，不是增量

spec §5.2 字段名叫 `statDelta` 但未定义语义。定为**更新后的累计绝对值** + `accuracy`（复用段 1 `SubjectServiceImpl.accuracy` 的两位小数口径，保证与 `subject/list` 的 `myAccuracy` 是同一个数）。理由：前端一次调用即可渲染"本科目共答 X 题"，且不依赖本地累加状态；字段名沿用 spec，不改契约。

---

### Task 2.1: `AnswerGrader` —— 五题型判分内核

**Files:**
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/AnswerGrader.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/AnswerGraderTest.java`

**Interfaces:**
- Consumes: `PracticeException` / `PracticeErrorCode.BAD_REQUEST`（段 1）
- Produces: `AnswerGrader.grade(int type, String answerJson, String userAnswerJson, List<String> optionKeys)` → `GradeResult(boolean correct, boolean needSelfJudge)`；格式与题型不符一律 `PracticeException(40000)`

- [x] **Step 1: 先写测试（表驱动，32 个用例）**

创建 `AnswerGraderTest.java`：

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 判分口径的唯一定义处（spec 5.3）。表驱动——每一行就是一个口径决定，
 * 改判分规则必须先改这张表。所有 answerJson 都是 qb_question.answer_json 的实测形态。
 */
class AnswerGraderTest {

    private final AnswerGrader grader = new AnswerGrader();
    private static final List<String> ABCD = List.of("A", "B", "C", "D");

    static Stream<Arguments> singleChoiceCases() {
        return Stream.of(
                Arguments.of("\"A\"", "\"A\"", true),
                Arguments.of("\"A\"", "\" a \"", true),
                Arguments.of("\"A\"", "\"B\"", false));
    }

    /** 非法来源：不在选项集内 / 非单选格式 / 空串 —— 全部 40000 不落库 */
    static Stream<Arguments> singleChoiceIllegalCases() {
        return Stream.of(
                Arguments.of("\"E\""),
                Arguments.of("\"Ａ\""),
                Arguments.of("[\"A\"]"),
                Arguments.of("\"\""));
    }

    static Stream<Arguments> multiChoiceCases() {
        return Stream.of(
                Arguments.of("[\"A\",\"C\"]", "[\"A\",\"C\"]", true),
                Arguments.of("[\"A\",\"C\"]", "[\"C\",\"A\"]", true),
                Arguments.of("[\"A\",\"C\"]", "[\"A\"]", false),
                Arguments.of("[\"A\",\"C\"]", "[\"A\",\"A\",\"C\"]", true),
                Arguments.of("[\"A\",\"C\"]", "[\"a\",\"c\"]", true),
                Arguments.of("[\"A\",\"C\"]", "[]", false));
    }

    static Stream<Arguments> judgeCases() {
        return Stream.of(
                Arguments.of("true", "true", true),
                Arguments.of("true", "\"true\"", true),
                Arguments.of("true", "\"TRUE\"", true),
                Arguments.of("true", "false", false));
    }

    static Stream<Arguments> blankCases() {
        return Stream.of(
                Arguments.of("[\"光合作用\"]", "[\"光合作用\"]", true),
                Arguments.of("[\"光合作用\"]", "[\" 光合作用 \"]", true),
                Arguments.of("[\"ABC\"]", "[\"ＡＢＣ\"]", true),
                Arguments.of("[\"ABC\"]", "[\"abc\"]", true),
                Arguments.of("[[\"光合作用\",\"叶绿体\"]]", "[\"叶绿体\"]", true),
                Arguments.of("[\"甲\",\"乙\"]", "[\"甲\"]", false));
    }

    @ParameterizedTest(name = "单选 {0} vs {1} -> {2}")
    @MethodSource("singleChoiceCases")
    void singleChoice(String answerJson, String userAnswerJson, boolean expected) {
        assertEquals(expected, grader.grade(1, answerJson, userAnswerJson, ABCD).correct());
    }

    @ParameterizedTest(name = "单选非法 {0} -> 40000")
    @MethodSource("singleChoiceIllegalCases")
    void singleChoiceRejects(String userAnswerJson) {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(1, "\"A\"", userAnswerJson, ABCD));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    @ParameterizedTest(name = "多选 {0} vs {1} -> {2}")
    @MethodSource("multiChoiceCases")
    void multiChoice(String answerJson, String userAnswerJson, boolean expected) {
        assertEquals(expected, grader.grade(2, answerJson, userAnswerJson, ABCD).correct());
    }

    @Test
    @DisplayName("多选非数组 -> 40000")
    void multiChoiceRejectsNonArray() {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(2, "[\"A\",\"C\"]", "\"A\"", ABCD));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    @ParameterizedTest(name = "判断 {0} vs {1} -> {2}")
    @MethodSource("judgeCases")
    void judge(String answerJson, String userAnswerJson, boolean expected) {
        assertEquals(expected, grader.grade(3, answerJson, userAnswerJson, List.of()).correct());
    }

    @ParameterizedTest(name = "判断非法 {0} -> 40000")
    @MethodSource("judgeRejectsCases")
    void judgeRejects(String userAnswerJson) {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(3, "true", userAnswerJson, List.of()));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    static Stream<Arguments> judgeRejectsCases() {
        return Stream.of(Arguments.of("\"yes\""), Arguments.of("1"));
    }

    @ParameterizedTest(name = "填空 {0} vs {1} -> {2}")
    @MethodSource("blankCases")
    void blank(String answerJson, String userAnswerJson, boolean expected) {
        assertEquals(expected, grader.grade(4, answerJson, userAnswerJson, List.of()).correct());
    }

    @ParameterizedTest(name = "填空非法 {0} -> 40000")
    @MethodSource("blankRejectsCases")
    void blankRejects(String userAnswerJson) {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(4, "[\"光合作用\"]", userAnswerJson, List.of()));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    static Stream<Arguments> blankRejectsCases() {
        return Stream.of(Arguments.of("\"光合作用\""), Arguments.of("[123]"));
    }

    static Stream<Arguments> shortAnswerCases() {
        return Stream.of(
                Arguments.of("\"参考答案：要点一\"", "\"我的作答\""));
    }

    /**
     * 落地版新增（本计划初稿在这里写的是"坏 JSON 也不该炸"，前提被端到端推翻）：
     * 简答不判内容，但答案必须是合法 JSON——user_answer_json 是 MySQL json 列，
     * 非法文本写库会被驱动层拒成 code=0，所以必须在判分器就挡成 40000。
     */
    static Stream<Arguments> shortAnswerRejectsCases() {
        return Stream.of(Arguments.of("我的作答"), Arguments.of("[\"坏 JSON"));
    }

    @ParameterizedTest(name = "简答非法 JSON {0} -> 40000")
    @MethodSource("shortAnswerRejectsCases")
    void shortAnswerRejectsIllegalJson(String userAnswerJson) {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(5, "\"参考答案：要点一\"", userAnswerJson, List.of()));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    /**
     * 多值文档：Jackson 的 readTree 只取第一个值并容忍尾随内容，MySQL 的 json 列却整份校验。
     * 首值恰好题型合法时（实测 code=0 的四条），裸串写库必炸 DataIntegrityViolation。
     * 每题的"正确答案"都按题型给合法值，保证失败归因在用户答案而不是题库。
     */
    static Stream<Arguments> multiValueOrNonFiniteCases() {
        return Stream.of(
                Arguments.of(1, "\"A\"", "\"A\" \"B\""),
                Arguments.of(2, "[\"A\",\"C\"]", "[\"A\",\"C\"] [\"B\"]"),
                Arguments.of(3, "true", "true true"),
                Arguments.of(4, "[\"x\"]", "[\"x\"] [\"y\"]"),
                Arguments.of(5, "\"参考答案\"", "\"文本\" \"文本\""),
                Arguments.of(5, "\"参考答案\"", "1e999"));
    }

    @ParameterizedTest(name = "题型 {0}：用户答案 {2} -> 40000")
    @MethodSource("multiValueOrNonFiniteCases")
    void multiValueOrNonFiniteRejected(int type, String answerJson, String userAnswerJson) {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(type, answerJson, userAnswerJson, ABCD));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    @ParameterizedTest(name = "简答 {1} -> 不判分等自评")
    @MethodSource("shortAnswerCases")
    void shortAnswer(String answerJson, String userAnswerJson) {
        AnswerGrader.GradeResult r = grader.grade(5, answerJson, userAnswerJson, List.of());
        assertFalse(r.correct(), "简答不能自动判对");
        assertTrue(r.needSelfJudge());
    }

    @ParameterizedTest(name = "未知题型 {0} -> 40000")
    @MethodSource("unknownTypeCases")
    void unknownType(int type) {
        PracticeException e = assertThrows(PracticeException.class,
                () -> grader.grade(type, "\"A\"", "\"A\"", ABCD));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    static Stream<Arguments> unknownTypeCases() {
        return Stream.of(Arguments.of(0), Arguments.of(9));
    }
}
```

- [x] **Step 2: 跑测试，确认全部因"找不到符号 AnswerGrader"失败**

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=AnswerGraderTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```
Expected: 编译失败（`找不到符号: 类 AnswerGrader`）。若通过，说明类已存在，停下核对。

- [x] **Step 3: 实现 `AnswerGrader`**

```java
package org.leon.practicemodule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 两个阶段唯一的判分口径，只认 qb_question.answer_json（spec 5.3）。
 * 永不读 qb_question_option.is_correct——它与 answer_json 是同一事实的两份拷贝，
 * 认了后者，前者就是无人校验的死数据（spec 5.5）。
 *
 * 不注入任何 bean，单测直接 new；答案格式与题型不符一律 40000，不落库。
 */
@Service
public class AnswerGrader {

    /** correct=是否答对；needSelfJudge=简答题不自动判分，等 self-judge 回填 */
    public record GradeResult(boolean correct, boolean needSelfJudge) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    public GradeResult grade(int type, String answerJson, String userAnswerJson, List<String> optionKeys) {
        return switch (type) {
            case 1 -> new GradeResult(gradeSingle(answerJson, userAnswerJson, optionKeys), false);
            case 2 -> new GradeResult(gradeMulti(answerJson, userAnswerJson), false);
            case 3 -> new GradeResult(gradeJudge(answerJson, userAnswerJson), false);
            case 4 -> new GradeResult(gradeBlank(answerJson, userAnswerJson), false);
            case 5 -> {
                // 落地版（审查后）：简答不判内容，但必须过 parse 的"整份单值 + 有限数值"校验，
                // 否则裸文本/多值串会被写进 MySQL json 列，报 DataIntegrityViolation -> code=0
                parse(userAnswerJson);
                yield new GradeResult(false, true);
            }
            default -> throw malformed("未知题型 " + type + "，无法判分");
        };
    }

    private boolean gradeSingle(String answerJson, String userAnswerJson, List<String> optionKeys) {
        String correct = readText(answerJson).trim();
        String user = readText(userAnswerJson).trim();
        if (!optionKeys.isEmpty() && !optionKeys.contains(user.toUpperCase(Locale.ROOT))) {
            throw malformed("答案不在选项范围内");
        }
        return correct.equalsIgnoreCase(user);
    }

    private boolean gradeMulti(String answerJson, String userAnswerJson) {
        Set<String> correct = letterSet(readArray(answerJson));
        Set<String> user = letterSet(readArray(userAnswerJson));
        return !user.isEmpty() && correct.equals(user);
    }

    private boolean gradeJudge(String answerJson, String userAnswerJson) {
        return readBool(answerJson) == readBool(userAnswerJson);
    }

    private boolean gradeBlank(String answerJson, String userAnswerJson) {
        JsonNode correct = parse(answerJson);
        JsonNode user = parse(userAnswerJson);
        if (!correct.isArray() || !user.isArray()) {
            throw malformed("答案格式与题型不匹配");
        }
        if (correct.size() != user.size()) {
            return false;
        }
        for (int i = 0; i < correct.size(); i++) {
            JsonNode userSlot = user.get(i);
            if (!userSlot.isTextual()) {
                throw malformed("答案格式与题型不匹配");
            }
            if (!slotHit(correct.get(i), normalize(userSlot.textValue()))) {
                return false;
            }
        }
        return true;
    }

    /** 正确答案某一空可以是单串，也可以是"任一命中"数组（spec 5.3 type=4） */
    private boolean slotHit(JsonNode correctSlot, String normalizedUser) {
        if (correctSlot.isTextual()) {
            return normalize(correctSlot.textValue()).equals(normalizedUser);
        }
        if (correctSlot.isArray()) {
            for (JsonNode alt : correctSlot) {
                if (alt.isTextual() && normalize(alt.textValue()).equals(normalizedUser)) {
                    return true;
                }
            }
            return false;
        }
        throw malformed("题库答案格式异常");
    }

    private Set<String> letterSet(List<String> letters) {
        Set<String> set = new TreeSet<>();
        for (String letter : letters) {
            set.add(letter.trim().toUpperCase(Locale.ROOT));
        }
        return set;
    }

    private String readText(String json) {
        JsonNode node = parse(json);
        if (!node.isTextual()) {
            throw malformed("答案格式与题型不匹配");
        }
        return node.textValue();
    }

    private boolean readBool(String json) {
        JsonNode node = parse(json);
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            String t = node.textValue().trim();
            if ("true".equalsIgnoreCase(t)) {
                return true;
            }
            if ("false".equalsIgnoreCase(t)) {
                return false;
            }
        }
        throw malformed("答案格式与题型不匹配");
    }

    private List<String> readArray(String json) {
        JsonNode node = parse(json);
        if (!node.isArray()) {
            throw malformed("答案格式与题型不匹配");
        }
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw malformed("答案格式与题型不匹配");
            }
            out.add(item.textValue());
        }
        return out;
    }

    /**
     * 必须是"整份只有一个值"的合法 JSON（落地版，含审查后的收紧）。
     * Jackson 的 readTree 默认容忍尾随内容——`"A" "B"` 只取第一个值就返回；
     * 而 qb_user_answer_record.user_answer_json 是 MySQL json 列，它校验整份文档，
     * 首值题型合法的尾随串写库会报 DataIntegrityViolation（端到端实测 code=0）。
     * 非有限数值同理：Jackson 把 1e999 读成 Infinity，MySQL JSON_VALID 返回 0。
     * 本计划初稿这里用的是宽松的 readTree(String)，那正是被端到端推翻的地方。
     */
    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            throw malformed("答案不能为空");
        }
        try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
            JsonNode node = objectMapper.readTree(parser);
            if (node == null || node.isMissingNode()) {
                throw malformed("答案不是合法 JSON");
            }
            if (parser.nextToken() != null) {
                throw malformed("答案不是合法 JSON");
            }
            requireFinite(node);
            return node;
        } catch (PracticeException e) {
            throw e;
        } catch (Exception e) {
            // answer_json 是 json 列，解析失败只可能来自请求侧的 user_answer_json
            throw malformed("答案不是合法 JSON");
        }
    }

    private static void requireFinite(JsonNode node) {
        if (node.isNumber() && !node.isIntegralNumber() && !Double.isFinite(node.doubleValue())) {
            throw malformed("答案含非法数值");
        }
        for (JsonNode child : node) {
            requireFinite(child);
        }
    }

    /** 去首尾空白 + 全角转半角 + 忽略大小写，只用于填空题（spec 5.3 type=4） */
    static String normalize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char ch : raw.toCharArray()) {
            if (ch >= '\uFF01' && ch <= '\uFF5E') {
                sb.append((char) (ch - 0xFEE0));
            } else if (ch == '\u3000') {
                sb.append(' ');
            } else {
                sb.append(ch);
            }
        }
        return sb.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static PracticeException malformed(String msg) {
        return new PracticeException(PracticeErrorCode.BAD_REQUEST, msg);
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: 同 Step 2
Expected: `Tests run: 32, Failures: 0`

- [x] **Step 5: 提交**

```bash
git add PracticeModule/src/main/java/org/leon/practicemodule/service/AnswerGrader.java \
        PracticeModule/src/test/java/org/leon/practicemodule/service/AnswerGraderTest.java
git commit -m "判分内核 AnswerGrader：五题型表驱动，只认 answer_json，格式不符报 40000" -- \
        PracticeModule/src/main/java/org/leon/practicemodule/service/AnswerGrader.java \
        PracticeModule/src/test/java/org/leon/practicemodule/service/AnswerGraderTest.java
git show --stat --oneline HEAD
```
Expected: 恰好 2 个文件。

---

### Task 2.2: `QuestionVO` + `PracticeMasker` + 脱敏守卫测试

**Files:**
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/pojo/vo/QuestionVO.java`
- Create: `PracticeModule/src/main/java/org/leon/practicemodule/service/PracticeMasker.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/PracticeMaskerTest.java`

**Interfaces:**
- Consumes: `QbQuestion` / `QbQuestionOption`（段 1 实体）
- Produces: `PracticeMasker.toQuestionVO(QbQuestion, List<QbQuestionOption>)` → `QuestionVO`。段 3、段 5 的所有取题响应都必须经它，禁止直接返回实体。

- [x] **Step 1: 先写守卫测试（本段最有价值的一条）**

创建 `PracticeMaskerTest.java`：

```java
package org.leon.practicemodule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.vo.QuestionVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 脱敏守卫（spec 5.4/9.1.2）：答题态响应按 F12 不该看到答案。
 * 本类建立"禁止子串清单"，段 3 的取题接口、段 5 的 exam/question 直接扩这份清单。
 */
class PracticeMaskerTest {

    private final PracticeMasker masker = new PracticeMasker();

    private QbQuestion question(int type) {
        QbQuestion q = new QbQuestion();
        q.setId(1L);
        q.setSubjectId(2L);
        q.setType(type);
        q.setTitle("题干");
        q.setDifficulty(2);
        q.setStatus(1);
        q.setAnswerJson("\"A\"");
        q.setAnalysis("这是解析，绝不能出现在答题态");
        q.setTagsJson("[\"语法\",\"时态\"]");
        return q;
    }

    /** 重载：允许 null 题型（实体字段是 Integer，防御路径要能测） */
    private QbQuestion question(Integer type) {
        QbQuestion q = question(1);
        q.setType(type);
        return q;
    }

    private QbQuestionOption option(String key, String content, int isCorrect) {
        QbQuestionOption o = new QbQuestionOption();
        o.setQuestionId(1L);
        o.setOptionKey(key);
        o.setContent(content);
        o.setIsCorrect(isCorrect);
        return o;
    }

    @Test
    @DisplayName("五种题型都有中文名")
    void allFiveTypesNamed() {
        assertEquals("单选题", masker.toQuestionVO(question(1), List.of()).getTypeName());
        assertEquals("多选题", masker.toQuestionVO(question(2), List.of()).getTypeName());
        assertEquals("判断题", masker.toQuestionVO(question(3), List.of()).getTypeName());
        assertEquals("填空题", masker.toQuestionVO(question(4), List.of()).getTypeName());
        assertEquals("简答题", masker.toQuestionVO(question(5), List.of()).getTypeName());
    }

    @Test
    @DisplayName("未知题型与 null 题型不炸，落成未知题型")
    void unknownTypeFallback() {
        assertEquals("未知题型", masker.toQuestionVO(question(9), List.of()).getTypeName());
        assertEquals("未知题型", masker.toQuestionVO(question((Integer) null), List.of()).getTypeName());
    }

    @Test
    @DisplayName("tags_json 合法/为空/坏 JSON 三种都不连累取题")
    void tagsParsing() {
        assertEquals(List.of("语法", "时态"), masker.toQuestionVO(question(1), List.of()).getTags());

        QbQuestion blank = question(1);
        blank.setTagsJson("  ");
        assertEquals(List.of(), masker.toQuestionVO(blank, List.of()).getTags());

        QbQuestion broken = question(1);
        broken.setTagsJson("{不是数组");
        assertEquals(List.of(), masker.toQuestionVO(broken, List.of()).getTags());
    }

    @Test
    @DisplayName("选项只复制标号与内容；无选项返回空列表而不是 null")
    void optionsCopied() {
        QuestionVO vo = masker.toQuestionVO(question(1),
                List.of(option("A", "选项甲", 1), option("B", "选项乙", 0)));
        assertEquals(2, vo.getOptions().size());
        assertEquals("A", vo.getOptions().get(0).getOptionKey());
        assertEquals("选项甲", vo.getOptions().get(0).getContent());
        assertEquals(List.of(), masker.toQuestionVO(question(3), List.of()).getOptions());
    }

    @Test
    @DisplayName("脱敏守卫：序列化后不得出现任何答案字段")
    void serializedQuestionNeverLeaksAnswers() throws Exception {
        QuestionVO vo = masker.toQuestionVO(question(1),
                List.of(option("A", "选项甲", 1), option("B", "选项乙", 0)));
        String json = new ObjectMapper().writeValueAsString(vo);

        // 正向锚点：扫描目标为空串时，下面的负向断言会全部空转
        assertTrue(json.contains("\"title\""), "扫描目标为空，负向断言无意义");
        assertTrue(json.contains("选项甲"));
        for (String forbidden : List.of("answerJson", "analysis", "isCorrect", "is_correct", "correctAnswerJson")) {
            assertFalse(json.contains(forbidden), "答题态泄漏了 " + forbidden + "：" + json);
        }
    }

    @Test
    @DisplayName("守卫非空转证明：直接序列化实体确实带着这些字段")
    void scannerWouldCatchRawEntity() throws Exception {
        String json = new ObjectMapper().writeValueAsString(question(1));
        assertTrue(json.contains("answerJson") && json.contains("analysis"),
                "实体序列化都不含这些键，说明扫描串写错了，守卫是一条永远绿的空断言");
    }
}
```

- [x] **Step 2: 跑测试确认失败（找不到 QuestionVO / PracticeMasker）**

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=PracticeMaskerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```
Expected: 编译失败。

- [x] **Step 3: 实现 `QuestionVO` 与 `PracticeMasker`**

`QuestionVO.java`：

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 答题态唯一的题目出口（spec 5.4 白名单）。
 * 白名单之外一律没有字段——注意这里根本没有 answerJson/analysis/isCorrect，
 * 有人想加就会撞上 PracticeMaskerTest 的守卫。
 */
@Data
public class QuestionVO {

    private Long questionId;
    private Long subjectId;
    private Integer type;
    private String typeName;
    private String title;
    private List<OptionVO> options;
    private Integer difficulty;
    private List<String> tags;
    /** 答题卡序号：段 3 顺序取题/段 5 卷面题号填入 */
    private Integer index;
    /** 卷面分值：段 5 从 structure_json 填入 */
    private BigDecimal score;

    @Data
    public static class OptionVO {
        private String optionKey;
        private String content;
    }
}
```

`PracticeMasker.java`：

```java
package org.leon.practicemodule.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.vo.QuestionVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 答题态唯一的出口（spec 5.4）。实体 → VO 逐字段白名单复制：
 * 不复制 answerJson/analysis，也绝不读 QbQuestionOption.isCorrect（spec 5.5）。
 * 不注入任何 bean；取选项与拼 answerStatus 都是调用方的事（段 3/段 5）。
 */
@Slf4j
@Service
public class PracticeMasker {

    private static final Map<Integer, String> TYPE_NAMES = Map.of(
            1, "单选题",
            2, "多选题",
            3, "判断题",
            4, "填空题",
            5, "简答题");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public QuestionVO toQuestionVO(QbQuestion question, List<QbQuestionOption> options) {
        QuestionVO vo = new QuestionVO();
        vo.setQuestionId(question.getId());
        vo.setSubjectId(question.getSubjectId());
        vo.setType(question.getType());
        vo.setTypeName(question.getType() == null
                ? "未知题型"
                : TYPE_NAMES.getOrDefault(question.getType(), "未知题型"));
        vo.setTitle(question.getTitle());
        vo.setDifficulty(question.getDifficulty());
        vo.setTags(parseTags(question.getTagsJson()));
        vo.setOptions(toOptions(options));
        return vo;
    }

    private List<QuestionVO.OptionVO> toOptions(List<QbQuestionOption> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        List<QuestionVO.OptionVO> out = new ArrayList<>(options.size());
        for (QbQuestionOption option : options) {
            QuestionVO.OptionVO ov = new QuestionVO.OptionVO();
            ov.setOptionKey(option.getOptionKey());
            ov.setContent(option.getContent());
            out.add(ov);
        }
        return out;
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            // 标签是展示性数据，解析失败不能连累取题；写入方是 AIModule，改坏它不需要刷题页陪葬
            log.warn("tags_json 解析失败，按无标签返回: {}", tagsJson);
            return List.of();
        }
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: 同 Step 2
Expected: `Tests run: 6, Failures: 0`

- [x] **Step 5: 提交**

```bash
git add PracticeModule/src/main/java/org/leon/practicemodule/pojo/vo/QuestionVO.java \
        PracticeModule/src/main/java/org/leon/practicemodule/service/PracticeMasker.java \
        PracticeModule/src/test/java/org/leon/practicemodule/service/PracticeMaskerTest.java
git commit -m "脱敏出口 PracticeMasker 与守卫测试：答题态序列化不含任何答案字段" -- \
        PracticeModule/src/main/java/org/leon/practicemodule/pojo/vo/QuestionVO.java \
        PracticeModule/src/main/java/org/leon/practicemodule/service/PracticeMasker.java \
        PracticeModule/src/test/java/org/leon/practicemodule/service/PracticeMaskerTest.java
git show --stat --oneline HEAD
```

---

### Task 2.3: 落库 SQL 与真库语义（`AnswerFlowDbIT`）

**Files:**
- Modify: `.../mapper/QbUserAnswerRecordMapper.java` `QbUserWrongMapper.java` `QbUserSubjectStatMapper.java` `QbQuestionOptionMapper.java`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/mapper/AnswerFlowDbIT.java`

**Interfaces:**
- Consumes: 四张表的唯一键（前置实测）、段 1 的 `UnpooledDataSource` 自建 SqlSession 模式
- Produces（Task 2.4/2.5 依赖的方法签名）：
  - `QbUserAnswerRecordMapper.judgeIfPending(Long recordId, Long userId, int isCorrect)` → int
  - `QbUserWrongMapper.upsertWrong(Long userId, Long subjectId, Long questionId)` → int
  - `QbUserWrongMapper.masterIfUnmastered(Long userId, Long questionId)` → int
  - `QbUserWrongMapper.selectByUserAndQuestion(Long userId, Long questionId)` → `QbUserWrong`
  - `QbUserSubjectStatMapper.upsertAccumulate(Long userId, Long subjectId, int correct, int wrong)` → int
  - `QbUserSubjectStatMapper.selectByUserAndSubject(Long userId, Long subjectId)` → `QbUserSubjectStat`
  - `QbQuestionOptionMapper.selectOptionKeys(Long questionId)` → `List<String>`

- [x] **Step 1: 加四个 mapper 方法**

`QbUserAnswerRecordMapper` 追加：

```java
    /**
     * 简答自评的幂等闸门：只有"待自评"（is_correct = -1）的行能被改写一次。
     * 返回 0 表示已自评过（或不是这个用户的行）——统计与错题本据此不再联动。
     */
    @Update("UPDATE qb_user_answer_record SET is_correct = #{isCorrect} "
            + "WHERE id = #{recordId} AND user_id = #{userId} AND is_correct = -1")
    int judgeIfPending(@Param("recordId") Long recordId,
                       @Param("userId") Long userId,
                       @Param("isCorrect") int isCorrect);
```

`QbUserWrongMapper` 追加：

```java
    /**
     * 答错：有行则计数 +1 且 status 置回 1（已掌握再答错要回退，spec 7.3），无行则新建。
     * uk_user_question(user_id, question_id) 保证全程只有一行——实测该唯一键存在。
     */
    @Insert("INSERT INTO qb_user_wrong (user_id, subject_id, question_id, wrong_count, last_wrong_at, status, created_at, updated_at) "
            + "VALUES (#{userId}, #{subjectId}, #{questionId}, 1, NOW(), 1, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE wrong_count = wrong_count + 1, last_wrong_at = NOW(), "
            + "status = 1, updated_at = NOW()")
    int upsertWrong(@Param("userId") Long userId,
                    @Param("subjectId") Long subjectId,
                    @Param("questionId") Long questionId);

    /** 答对：只把"未掌握"置为已掌握；从未错过的题不建行，已掌握的不动 */
    @Update("UPDATE qb_user_wrong SET status = 2, updated_at = NOW() "
            + "WHERE user_id = #{userId} AND question_id = #{questionId} AND status = 1")
    int masterIfUnmastered(@Param("userId") Long userId, @Param("questionId") Long questionId);

    @Select("SELECT id, user_id, subject_id, question_id, wrong_count, last_wrong_at, status, created_at, updated_at "
            + "FROM qb_user_wrong WHERE user_id = #{userId} AND question_id = #{questionId}")
    QbUserWrong selectByUserAndQuestion(@Param("userId") Long userId, @Param("questionId") Long questionId);
```

`QbUserSubjectStatMapper` 追加：

```java
    /** 逐题作答累加。只在 answer_mode IN (1,3) 调用（spec 7.1）——考试交卷不走这里 */
    @Insert("INSERT INTO qb_user_subject_stat (user_id, subject_id, total_count, correct_count, wrong_count, last_answer_at, created_at, updated_at) "
            + "VALUES (#{userId}, #{subjectId}, 1, #{correct}, #{wrong}, NOW(), NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE total_count = total_count + 1, "
            + "correct_count = correct_count + #{correct}, wrong_count = wrong_count + #{wrong}, "
            + "last_answer_at = NOW(), updated_at = NOW()")
    int upsertAccumulate(@Param("userId") Long userId,
                         @Param("subjectId") Long subjectId,
                         @Param("correct") int correct,
                         @Param("wrong") int wrong);

    @Select("SELECT id, user_id, subject_id, total_count, correct_count, wrong_count, last_answer_at, created_at, updated_at "
            + "FROM qb_user_subject_stat WHERE user_id = #{userId} AND subject_id = #{subjectId}")
    QbUserSubjectStat selectByUserAndSubject(@Param("userId") Long userId, @Param("subjectId") Long subjectId);
```

`QbQuestionOptionMapper` 追加：

```java
    /** 单选判分的"字母必须在选项集内"校验用。只读 option_key，不碰 is_correct（spec 5.5） */
    @Select("SELECT option_key FROM qb_question_option WHERE question_id = #{questionId} ORDER BY sort_order, id")
    List<String> selectOptionKeys(@Param("questionId") Long questionId);
```

需要的 import：`QbUserAnswerRecordMapper` 补 `org.apache.ibatis.annotations.Update`/`Param`；`QbUserWrongMapper` 补 `Insert`/`Select`/`Param`；`QbUserSubjectStatMapper` 补 `Insert`/`Param`（`Select` 已有）；`QbQuestionOptionMapper` 补 `Select`/`Param`/`java.util.List`。

- [x] **Step 2: 写真库语义测试（含合成用户与前后清理）**

创建 `AnswerFlowDbIT.java`：

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
import org.leon.practicemodule.pojo.QbUserAnswerRecord;
import org.leon.practicemodule.pojo.QbUserSubjectStat;
import org.leon.practicemodule.pojo.QbUserWrong;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 作答落库 SQL 的真库语义：ON DUPLICATE KEY UPDATE 的增量、掌握状态回退、
 * -1 哨兵在 STRICT 模式下可写、条件更新恰好生效一次。
 * 这些行为的正确性由 MySQL 保证，只有真库能证。
 *
 * 写入合成用户 user_id=990001（远高于真实 id），@BeforeAll/@AfterAll 各清一次，
 * 跑完库里不留痕。若中断留下残行，重新跑一次本类即可清掉。
 */
@Tag("db-it")
@DisplayName("作答落库 SQL 的真库语义")
class AnswerFlowDbIT {

    private static final long TEST_USER = 990001L;

    private static SqlSession session;
    private static QbUserWrongMapper wrongMapper;
    private static QbUserSubjectStatMapper statMapper;
    private static QbUserAnswerRecordMapper recordMapper;

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
        cfg.addMapper(QbUserWrongMapper.class);
        cfg.addMapper(QbUserSubjectStatMapper.class);
        cfg.addMapper(QbUserAnswerRecordMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession(true);
        wrongMapper = session.getMapper(QbUserWrongMapper.class);
        statMapper = session.getMapper(QbUserSubjectStatMapper.class);
        recordMapper = session.getMapper(QbUserAnswerRecordMapper.class);
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
        for (String table : List.of("qb_user_wrong", "qb_user_answer_record", "qb_user_subject_stat")) {
            try (PreparedStatement ps = session.getConnection()
                    .prepareStatement("DELETE FROM " + table + " WHERE user_id = ?")) {
                ps.setLong(1, TEST_USER);
                ps.executeUpdate();
            }
        }
    }

    private static long countRows(String table) throws SQLException {
        try (PreparedStatement ps = session.getConnection()
                .prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?")) {
            ps.setLong(1, TEST_USER);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    @DisplayName("答错累加 → 答对置已掌握 → 已掌握再答错回退（spec 7.3 与验收清单第 2 条）")
    void wrongBookLifecycle() throws SQLException {
        Long questionId = 1L;
        wrongMapper.upsertWrong(TEST_USER, 1L, questionId);
        wrongMapper.upsertWrong(TEST_USER, 1L, questionId);
        QbUserWrong row = wrongMapper.selectByUserAndQuestion(TEST_USER, questionId);
        assertEquals(2, row.getWrongCount(),
                "两次答错 wrong_count 必须是 2 —— ON DUPLICATE KEY UPDATE 没生效？");
        assertEquals(1, row.getStatus());

        assertEquals(1, wrongMapper.masterIfUnmastered(TEST_USER, questionId));
        assertEquals(2, wrongMapper.selectByUserAndQuestion(TEST_USER, questionId).getStatus());
        assertEquals(0, wrongMapper.masterIfUnmastered(TEST_USER, questionId),
                "已掌握再答对应是空更新");

        wrongMapper.upsertWrong(TEST_USER, 1L, questionId);
        row = wrongMapper.selectByUserAndQuestion(TEST_USER, questionId);
        assertEquals(3, row.getWrongCount());
        assertEquals(1, row.getStatus(), "已掌握后再答错必须回退为未掌握");
        assertEquals(1L, countRows("qb_user_wrong"), "uk_user_question 保证全程只有一行");
    }

    @Test
    @DisplayName("统计 upsert：一条 SQL 完成建行与增量")
    void statAccumulates() {
        statMapper.upsertAccumulate(TEST_USER, 7L, 1, 0);
        statMapper.upsertAccumulate(TEST_USER, 7L, 0, 1);
        statMapper.upsertAccumulate(TEST_USER, 7L, 0, 1);
        QbUserSubjectStat stat = statMapper.selectByUserAndSubject(TEST_USER, 7L);
        assertEquals(3, stat.getTotalCount());
        assertEquals(1, stat.getCorrectCount());
        assertEquals(2, stat.getWrongCount());
        assertNotNull(stat.getLastAnswerAt());
    }

    @Test
    @DisplayName("-1 待自评哨兵可写；条件更新恰好生效一次，且跨用户不可判")
    void pendingSentinelAndConditionalJudge() {
        QbUserAnswerRecord record = new QbUserAnswerRecord();
        record.setUserId(TEST_USER);
        record.setSubjectId(1L);
        record.setQuestionId(5L);
        record.setUserAnswerJson("\"我的作答\"");
        record.setIsCorrect(-1);
        record.setDurationMs(1000);
        record.setAnswerMode(1);
        recordMapper.insert(record);
        assertNotNull(record.getId(), "IdType.AUTO 必须把自增主键回填到实体");

        assertEquals(0, recordMapper.judgeIfPending(record.getId(), TEST_USER + 1, 1),
                "别人的记录不能被判");
        assertEquals(1, recordMapper.judgeIfPending(record.getId(), TEST_USER, 1));
        assertEquals(0, recordMapper.judgeIfPending(record.getId(), TEST_USER, 0),
                "第二次必须 0 —— 这就是自评的幂等闸门");
    }
}
```

- [x] **Step 3: 显式跑真库测试（这是本段唯一写 dev 库的动作，先告知用户）**

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=AnswerFlowDbIT -Dsurefire.excludedGroups= \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```
Expected: `Tests run: 3, Failures: 0`。若 `Tests run: 0` 是假绿灯——命令漏了 `-Dsurefire.excludedGroups=`。

- [x] **Step 4: 确认无残留**

```bash
# 用仓库外 JDBC 探针查一次三张表 user_id=990001 的行数，必须全 0
```
Expected: 三张表各 0 行。

- [x] **Step 5: 全量回归（db-it 默认排除，确认新 SQL 不影响既有 30 条）**

Run:
```bash
"$MVN" -pl PracticeModule -am test 2>&1 | grep -E "Tests run:|BUILD"
```
Expected: `Tests run: 68`（30 基线 + `AnswerGraderTest` 32 + `PracticeMaskerTest` 6），`BUILD SUCCESS`，且输出里**没有** `AnswerFlowDbIT`。数字不符先查测试是否被漏发现（`Tests run` 变小比失败更危险），再写进执行记录。

本任务不单独提交，随 Task 2.4/2.5 一起提交。

---

### Task 2.4: `POST /practice/answer/submit` —— 落库、联动与响应

**Files:**
- Create: `.../pojo/AnswerSubmitReq.java` `.../pojo/vo/StatDeltaVO.java` `.../pojo/vo/AnswerSubmitVO.java`
- Create: `.../service/AnswerService.java` `.../service/impl/AnswerServiceImpl.java`（先只实现 `submit`）
- Create: `.../controller/PracticeAnswerController.java`（先只挂 `/submit`）
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/service/AnswerServiceTest.java`（submit 部分 12 条）
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/controller/PracticeAnswerControllerTest.java`

**Interfaces:**
- Consumes: Task 2.1–2.3 的全部产出、`UserIdentityService`（段 1）
- Produces: `AnswerService.submit(Long userId, AnswerSubmitReq)` → `AnswerSubmitVO`；`/practice/answer/submit` 经网关可用

- [x] **Step 1: 写请求/响应 VO**

`AnswerSubmitReq.java`（`pojo/` 下，与实体同包）：

```java
package org.leon.practicemodule.pojo;

import lombok.Data;

/** 逐题作答请求体。校验在 Service 层显式做——项目未引 validation starter，@Valid 是空转的 */
@Data
public class AnswerSubmitReq {
    private Long subjectId;
    private Long questionId;
    /** 用户答案 JSON，形态与题型对应：单选 "A"、多选 ["A","C"]、判断 true、填空 ["x"]、简答 文本 */
    private String userAnswerJson;
    private Integer durationMs;
    /** 1刷题 3智能练习；2 是考试，走 exam/submit，不从这里进 */
    private Integer mode;
}
```

`StatDeltaVO.java`：

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/**
 * 提交后的累计快照（不是增量）：前端一次调用即可渲染"本科目共答 X 题、正确率 Y%"。
 * accuracy 复用 SubjectServiceImpl.accuracy 的两位小数口径，与 subject/list 的 myAccuracy 同源。
 */
@Data
public class StatDeltaVO {
    private Integer total;
    private Integer correct;
    private Integer wrong;
    private Double accuracy;
}
```

`AnswerSubmitVO.java`：

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/**
 * 判分响应——答案真值允许出现的两处之一（另一处是段 5 交卷后的成绩单，spec 5.4）。
 */
@Data
public class AnswerSubmitVO {
    private Long recordId;
    /** 简答待自评时为 null——不给前端一个假的 false */
    private Boolean isCorrect;
    private String correctAnswerJson;
    private String analysis;
    private boolean needSelfJudge;
    private StatDeltaVO statDelta;
    private Integer wrongCount;
    private boolean wrongMastered;
}
```

- [x] **Step 2: 写 submit 的失败测试（12 条）**

创建 `AnswerServiceTest.java`：

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserSubjectStatMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.pojo.AnswerSubmitReq;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;
import org.leon.practicemodule.pojo.QbUserSubjectStat;
import org.leon.practicemodule.pojo.QbUserWrong;
import org.leon.practicemodule.pojo.vo.AnswerSubmitVO;
import org.leon.practicemodule.service.impl.AnswerServiceImpl;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 作答落库的规则锁：7.1（只累加 1/3）、7.3（掌握回退）、-1 待自评哨兵、
 * 判分格式错不落库。判分器用真实实例——本类不测判分算法，但让判分走真链路。
 */
class AnswerServiceTest {

    private static final long USER = 10L;

    private final QbQuestionMapper questionMapper = mock(QbQuestionMapper.class);
    private final QbQuestionOptionMapper optionMapper = mock(QbQuestionOptionMapper.class);
    private final QbUserAnswerRecordMapper recordMapper = mock(QbUserAnswerRecordMapper.class);
    private final QbUserWrongMapper wrongMapper = mock(QbUserWrongMapper.class);
    private final QbUserSubjectStatMapper statMapper = mock(QbUserSubjectStatMapper.class);
    private final AnswerServiceImpl service = new AnswerServiceImpl(
            questionMapper, optionMapper, recordMapper, wrongMapper, statMapper, new AnswerGrader());

    @BeforeEach
    void stubInsertBackfillsId() {
        when(recordMapper.insert(any(QbUserAnswerRecord.class))).thenAnswer(inv -> {
            ((QbUserAnswerRecord) inv.getArgument(0)).setId(123L);
            return 1;
        });
    }

    private QbQuestion question(int type, String answerJson) {
        QbQuestion q = new QbQuestion();
        q.setId(1L);
        q.setSubjectId(1L);
        q.setType(type);
        q.setStatus(1);
        q.setAnswerJson(answerJson);
        q.setAnalysis("解析");
        return q;
    }

    private AnswerSubmitReq req(Integer mode) {
        AnswerSubmitReq r = new AnswerSubmitReq();
        r.setSubjectId(1L);
        r.setQuestionId(1L);
        r.setUserAnswerJson("\"A\"");
        r.setDurationMs(3000);
        r.setMode(mode);
        return r;
    }

    private void stubStat(Integer total, Integer correct, Integer wrong) {
        QbUserSubjectStat stat = new QbUserSubjectStat();
        stat.setTotalCount(total);
        stat.setCorrectCount(correct);
        stat.setWrongCount(wrong);
        stat.setLastAnswerAt(new Date());
        when(statMapper.selectByUserAndSubject(USER, 1L)).thenReturn(stat);
    }

    @Test
    @DisplayName("答对：写记录 + 掌握置位 + 统计累加 + 响应是更新后的累计快照")
    void correctAnswerWritesRecordAndAccumulatesStat() {
        when(questionMapper.selectById(1L)).thenReturn(question(1, "\"A\""));
        when(optionMapper.selectOptionKeys(1L)).thenReturn(List.of("A", "B", "C", "D"));
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(null);
        stubStat(1, 1, 0);

        AnswerSubmitVO vo = service.submit(USER, req(1));

        assertEquals(123L, vo.getRecordId());
        assertTrue(vo.getIsCorrect());
        assertFalse(vo.isNeedSelfJudge());
        assertEquals("解析", vo.getAnalysis());
        assertEquals(0, vo.getWrongCount());
        assertFalse(vo.isWrongMastered());
        assertEquals(1, vo.getStatDelta().getTotal());
        assertEquals(1.0, vo.getStatDelta().getAccuracy());

        ArgumentCaptor<QbUserAnswerRecord> captor = ArgumentCaptor.forClass(QbUserAnswerRecord.class);
        verify(recordMapper).insert(captor.capture());
        QbUserAnswerRecord written = captor.getValue();
        assertEquals(USER, written.getUserId());
        assertEquals(1L, written.getSubjectId());
        assertEquals(1L, written.getQuestionId());
        assertEquals(1, written.getIsCorrect());
        assertEquals(1, written.getAnswerMode());
        assertEquals(3000, written.getDurationMs());

        verify(wrongMapper).masterIfUnmastered(USER, 1L);
        verify(wrongMapper, never()).upsertWrong(anyLong(), anyLong(), anyLong());
        verify(statMapper).upsertAccumulate(USER, 1L, 1, 0);
    }

    @Test
    @DisplayName("答错：错题本 upsert，统计进错列")
    void wrongAnswerIncrementsWrongBook() {
        when(questionMapper.selectById(1L)).thenReturn(question(1, "\"A\""));
        when(optionMapper.selectOptionKeys(1L)).thenReturn(List.of("A", "B", "C", "D"));
        QbUserWrong row = new QbUserWrong();
        row.setWrongCount(1);
        row.setStatus(1);
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(row);
        stubStat(1, 0, 1);
        AnswerSubmitReq r = req(1);
        r.setUserAnswerJson("\"B\"");

        AnswerSubmitVO vo = service.submit(USER, r);

        assertFalse(vo.getIsCorrect());
        assertEquals(1, vo.getWrongCount());
        assertFalse(vo.isWrongMastered());
        assertEquals(1, vo.getStatDelta().getWrong());
        verify(wrongMapper).upsertWrong(USER, 1L, 1L);
        verify(wrongMapper, never()).masterIfUnmastered(anyLong(), anyLong());
        verify(statMapper).upsertAccumulate(USER, 1L, 0, 1);
    }

    @Test
    @DisplayName("答对未掌握错题 -> 置已掌握并在响应里体现")
    void correctAnswerOnUnmasteredWrongRowMastersIt() {
        when(questionMapper.selectById(1L)).thenReturn(question(1, "\"A\""));
        when(optionMapper.selectOptionKeys(1L)).thenReturn(List.of("A", "B", "C", "D"));
        QbUserWrong row = new QbUserWrong();
        row.setWrongCount(2);
        row.setStatus(2);
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(row);
        stubStat(3, 2, 1);

        AnswerSubmitVO vo = service.submit(USER, req(1));

        assertEquals(2, vo.getWrongCount());
        assertTrue(vo.isWrongMastered());
        verify(wrongMapper).masterIfUnmastered(USER, 1L);
    }

    @Test
    @DisplayName("简答：写 -1 待自评哨兵，不碰错题本与统计")
    void shortAnswerWritesPendingSentinelAndSkipsLinkage() {
        when(questionMapper.selectById(1L)).thenReturn(question(5, "\"参考答案：要点一\""));
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(null);
        when(statMapper.selectByUserAndSubject(USER, 1L)).thenReturn(null);

        AnswerSubmitVO vo = service.submit(USER, req(1));

        assertTrue(vo.isNeedSelfJudge());
        assertNull(vo.getIsCorrect(), "待自评不能给前端假的 false");
        assertEquals(0, vo.getStatDelta().getTotal());

        ArgumentCaptor<QbUserAnswerRecord> captor = ArgumentCaptor.forClass(QbUserAnswerRecord.class);
        verify(recordMapper).insert(captor.capture());
        assertEquals(-1, captor.getValue().getIsCorrect(), "待自评哨兵必须是 -1，不是 0");

        verify(wrongMapper, never()).upsertWrong(anyLong(), anyLong(), anyLong());
        verify(wrongMapper, never()).masterIfUnmastered(anyLong(), anyLong());
        verify(statMapper, never()).upsertAccumulate(anyLong(), anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("mode=3 智能练习同样累加统计（spec 7.1 的 IN (1,3)）")
    void smartPracticeModeAlsoAccumulatesStat() {
        when(questionMapper.selectById(1L)).thenReturn(question(1, "\"A\""));
        when(optionMapper.selectOptionKeys(1L)).thenReturn(List.of("A", "B", "C", "D"));
        when(wrongMapper.selectByUserAndQuestion(USER, 1L)).thenReturn(null);
        stubStat(1, 1, 0);

        service.submit(USER, req(3));

        verify(statMapper).upsertAccumulate(USER, 1L, 1, 0);
    }

    @Test
    @DisplayName("题目不存在 40400，不写任何行")
    void missingQuestionIs40400() {
        when(questionMapper.selectById(999L)).thenReturn(null);
        AnswerSubmitReq r = req(1);
        r.setQuestionId(999L);

        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, r));
        assertEquals(PracticeErrorCode.NOT_FOUND, e.errorCode());
        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
    }

    @Test
    @DisplayName("题目已下架 40900")
    void disabledQuestionIs40900() {
        QbQuestion q = question(1, "\"A\"");
        q.setStatus(0);
        when(questionMapper.selectById(1L)).thenReturn(q);

        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, req(1)));
        assertEquals(PracticeErrorCode.CONFLICT, e.errorCode());
        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
    }

    @Test
    @DisplayName("题目不属于该科目 40000")
    void subjectMismatchIs40000() {
        QbQuestion q = question(1, "\"A\"");
        q.setSubjectId(2L);
        when(questionMapper.selectById(1L)).thenReturn(q);

        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, req(1)));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    @Test
    @DisplayName("mode=2 是考试，答题接口拒绝（考试不吃科目统计，spec 7.1）")
    void examModeRejectedWith40000() {
        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, req(2)));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        verify(questionMapper, never()).selectById(any());
        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
        verifyNoInteractions(wrongMapper, statMapper);
    }

    @Test
    @DisplayName("空答案 40000，不查库")
    void blankAnswerRejectedWith40000() {
        AnswerSubmitReq r = req(1);
        r.setUserAnswerJson("   ");
        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, r));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        verifyNoInteractions(questionMapper);
    }

    @Test
    @DisplayName("负用时 40000")
    void negativeDurationRejectedWith40000() {
        AnswerSubmitReq r = req(1);
        r.setDurationMs(-1);
        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, r));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
    }

    @Test
    @DisplayName("答案格式与题型不符：判分器 40000 直接上抛，一行不写")
    void illegalAnswerFormatWritesNothing() {
        when(questionMapper.selectById(1L)).thenReturn(question(2, "[\"A\",\"C\"]"));
        // 多选传了字符串 "A"
        PracticeException e = assertThrows(PracticeException.class, () -> service.submit(USER, req(1)));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        verify(recordMapper, never()).insert(any(QbUserAnswerRecord.class));
        verifyNoInteractions(wrongMapper, statMapper);
    }
}
```

- [x] **Step 3: 跑测试确认失败（找不到 AnswerServiceImpl / AnswerService / 相关 VO）**

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=AnswerServiceTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```
Expected: 编译失败。

- [x] **Step 4: 实现 `AnswerService` / `AnswerServiceImpl`（submit 部分）与控制器**

`AnswerService.java`：

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.AnswerSubmitReq;
import org.leon.practicemodule.pojo.vo.AnswerSubmitVO;

public interface AnswerService {

    AnswerSubmitVO submit(Long userId, AnswerSubmitReq req);
}
```

`AnswerServiceImpl.java`：

```java
package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserSubjectStatMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.pojo.AnswerSubmitReq;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbUserAnswerRecord;
import org.leon.practicemodule.pojo.QbUserSubjectStat;
import org.leon.practicemodule.pojo.QbUserWrong;
import org.leon.practicemodule.pojo.vo.AnswerSubmitVO;
import org.leon.practicemodule.pojo.vo.StatDeltaVO;
import org.leon.practicemodule.service.AnswerGrader;
import org.leon.practicemodule.service.AnswerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 逐题作答的落库与联动，全部收在这一个类里：
 * 记录行 + 错题本 + 科目统计在一个本地事务内完成（单库，不加 @GlobalTransactional，spec 2.3）。
 * 累加口径（spec 7.1）：只有 answer_mode IN (1,3) 的作答进 qb_user_subject_stat，
 * mode=2（考试）在本接口直接 40000；段 5 的交卷自己落库、也不碰这张表。
 */
@Service
public class AnswerServiceImpl implements AnswerService {

    /** 简答"待自评"哨兵：is_correct 是有符号 tinyint，-1 不是"错"（段 2 裁定一） */
    static final int PENDING_SELF_JUDGE = -1;

    private final QbQuestionMapper questionMapper;
    private final QbQuestionOptionMapper optionMapper;
    private final QbUserAnswerRecordMapper recordMapper;
    private final QbUserWrongMapper wrongMapper;
    private final QbUserSubjectStatMapper statMapper;
    private final AnswerGrader grader;

    public AnswerServiceImpl(QbQuestionMapper questionMapper,
                             QbQuestionOptionMapper optionMapper,
                             QbUserAnswerRecordMapper recordMapper,
                             QbUserWrongMapper wrongMapper,
                             QbUserSubjectStatMapper statMapper,
                             AnswerGrader grader) {
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
        this.recordMapper = recordMapper;
        this.wrongMapper = wrongMapper;
        this.statMapper = statMapper;
        this.grader = grader;
    }

    @Override
    @Transactional
    public AnswerSubmitVO submit(Long userId, AnswerSubmitReq req) {
        validateSubmit(req);
        QbQuestion question = loadEnabledQuestion(req.getQuestionId(), req.getSubjectId());

        List<String> optionKeys = question.getType() == 1
                ? optionMapper.selectOptionKeys(question.getId())
                : List.of();
        AnswerGrader.GradeResult result = grader.grade(
                question.getType(), question.getAnswerJson(), req.getUserAnswerJson(), optionKeys);

        QbUserAnswerRecord record = new QbUserAnswerRecord();
        record.setUserId(userId);
        record.setSubjectId(question.getSubjectId());
        record.setQuestionId(question.getId());
        record.setUserAnswerJson(req.getUserAnswerJson());
        record.setIsCorrect(result.needSelfJudge() ? PENDING_SELF_JUDGE : (result.correct() ? 1 : 0));
        record.setDurationMs(req.getDurationMs());
        record.setAnswerMode(req.getMode());
        recordMapper.insert(record);

        if (!result.needSelfJudge()) {
            applyOutcome(userId, question.getSubjectId(), question.getId(), result.correct());
        }

        Outcome outcome = readOutcome(userId, question.getSubjectId(), question.getId());
        AnswerSubmitVO vo = new AnswerSubmitVO();
        vo.setRecordId(record.getId());
        vo.setIsCorrect(result.needSelfJudge() ? null : result.correct());
        vo.setCorrectAnswerJson(question.getAnswerJson());
        vo.setAnalysis(question.getAnalysis());
        vo.setNeedSelfJudge(result.needSelfJudge());
        vo.setStatDelta(outcome.statDelta());
        vo.setWrongCount(outcome.wrongCount());
        vo.setWrongMastered(outcome.wrongMastered());
        return vo;
    }

    /** 联动：错题本 upsert + 统计累加。简答待自评时调用方跳过本方法 */
    void applyOutcome(Long userId, Long subjectId, Long questionId, boolean correct) {
        if (correct) {
            wrongMapper.masterIfUnmastered(userId, questionId);
        } else {
            wrongMapper.upsertWrong(userId, subjectId, questionId);
        }
        statMapper.upsertAccumulate(userId, subjectId, correct ? 1 : 0, correct ? 0 : 1);
    }

    /**
     * 读回错题与统计的当前状态，组装响应。
     * 不靠内存推算计数：upsert 是原子累加，读回才是并发下唯一正确的绝对值。
     */
    Outcome readOutcome(Long userId, Long subjectId, Long questionId) {
        QbUserWrong wrong = wrongMapper.selectByUserAndQuestion(userId, questionId);
        QbUserSubjectStat stat = statMapper.selectByUserAndSubject(userId, subjectId);
        StatDeltaVO delta = new StatDeltaVO();
        delta.setTotal(stat == null ? 0 : nz(stat.getTotalCount()));
        delta.setCorrect(stat == null ? 0 : nz(stat.getCorrectCount()));
        delta.setWrong(stat == null ? 0 : nz(stat.getWrongCount()));
        delta.setAccuracy(SubjectServiceImpl.accuracy(delta.getCorrect(), delta.getTotal()));
        return new Outcome(
                wrong == null ? 0 : nz(wrong.getWrongCount()),
                wrong != null && wrong.getStatus() != null && wrong.getStatus() == 2,
                delta);
    }

    private void validateSubmit(AnswerSubmitReq req) {
        if (req.getSubjectId() == null || req.getQuestionId() == null) {
            throw badRequest("请求参数不合法");
        }
        if (req.getMode() == null || (req.getMode() != 1 && req.getMode() != 3)) {
            throw badRequest("答题模式只能是 1刷题 或 3智能练习");
        }
        if (!StringUtils.hasText(req.getUserAnswerJson())) {
            throw badRequest("答案不能为空");
        }
        if (req.getDurationMs() != null && req.getDurationMs() < 0) {
            throw badRequest("单题用时不能为负");
        }
    }

    private QbQuestion loadEnabledQuestion(Long questionId, Long subjectId) {
        QbQuestion question = questionMapper.selectById(questionId);
        if (question == null) {
            throw notFound("题目不存在");
        }
        if (question.getStatus() == null || question.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架");
        }
        if (!question.getSubjectId().equals(subjectId)) {
            throw badRequest("题目不属于该科目");
        }
        return question;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static PracticeException badRequest(String msg) {
        return new PracticeException(PracticeErrorCode.BAD_REQUEST, msg);
    }

    private static PracticeException notFound(String msg) {
        return new PracticeException(PracticeErrorCode.NOT_FOUND, msg);
    }

    /** 响应组装的中间结果，不用 VO 兼职内部结构 */
    record Outcome(Integer wrongCount, boolean wrongMastered, StatDeltaVO statDelta) {
    }
}
```

`PracticeAnswerController.java`（本任务先只挂 `/submit`，Task 2.5 补 `/self-judge`）：

```java
package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.AnswerSubmitReq;
import org.leon.practicemodule.pojo.vo.AnswerSubmitVO;
import org.leon.practicemodule.service.AnswerService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 作答接口。身份只从 X-User-Name 换取——请求体/参数里的任何 userId 都不看。
 */
@RestController
@RequestMapping("/practice/answer")
public class PracticeAnswerController {

    private final AnswerService answerService;
    private final UserIdentityService userIdentityService;

    public PracticeAnswerController(AnswerService answerService,
                                    UserIdentityService userIdentityService) {
        this.answerService = answerService;
        this.userIdentityService = userIdentityService;
    }

    @PostMapping("/submit")
    public Result<AnswerSubmitVO> submit(@RequestHeader("X-User-Name") String userName,
                                         @RequestBody AnswerSubmitReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(answerService.submit(userId, req));
    }
}
```

- [x] **Step 5: 跑测试确认通过（12 条）**

Run: 同 Step 3
Expected: `Tests run: 12, Failures: 0`

- [x] **Step 6: 补控制器测试**

创建 `PracticeAnswerControllerTest.java`：

```java
package org.leon.practicemodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.pojo.AnswerSubmitReq;
import org.leon.practicemodule.pojo.vo.AnswerSubmitVO;
import org.leon.practicemodule.service.AnswerService;
import org.leon.practicemodule.service.UserIdentityService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PracticeAnswerControllerTest {

    private final AnswerService answerService = mock(AnswerService.class);
    private final UserIdentityService identity = mock(UserIdentityService.class);
    private final PracticeAnswerController controller =
            new PracticeAnswerController(answerService, identity);

    @Test
    @DisplayName("submit：身份换取后交给 Service，结果包 Result")
    void submitWrapsInResult() {
        when(identity.resolveUserId("alice")).thenReturn(2L);
        AnswerSubmitVO vo = new AnswerSubmitVO();
        vo.setRecordId(7L);
        when(answerService.submit(eq(2L), any())).thenReturn(vo);

        Result<AnswerSubmitVO> r = controller.submit("alice", new AnswerSubmitReq());

        assertEquals(1, r.getCode());
        assertEquals(7L, r.getData().getRecordId());
    }

    @Test
    @DisplayName("身份解析失败向上抛给 advice，控制器不吞")
    void propagatesIdentityFailure() {
        when(identity.resolveUserId("ghost"))
                .thenThrow(new PracticeException(PracticeErrorCode.IDENTITY_MISSING, "用户身份不存在，请重新登录"));

        PracticeException e = assertThrows(PracticeException.class,
                () -> controller.submit("ghost", new AnswerSubmitReq()));
        assertEquals(PracticeErrorCode.IDENTITY_MISSING, e.errorCode());
        verify(answerService, never()).submit(anyLong(), any());
    }
}
```

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=PracticeAnswerControllerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```
Expected: `Tests run: 2, Failures: 0`

本任务不单独提交，随 Task 2.5 一起。

---

### Task 2.5: `POST /practice/answer/self-judge` —— 自评与幂等闸门

**Files:**
- Create: `.../pojo/SelfJudgeReq.java` `.../pojo/vo/SelfJudgeVO.java`
- Modify: `.../service/AnswerService.java` `.../impl/AnswerServiceImpl.java`（加 `selfJudge`）
- Modify: `.../controller/PracticeAnswerController.java`（挂第二个 POST）
- Test: `AnswerServiceTest.java`（追加 6 条）`PracticeAnswerControllerTest.java`（追加 1 条）

**Interfaces:**
- Consumes: Task 2.3 的 `judgeIfPending`、Task 2.4 的 `applyOutcome`/`readOutcome`
- Produces: `AnswerService.selfJudge(Long userId, SelfJudgeReq)` → `SelfJudgeVO`；`/practice/answer/self-judge` 经网关可用

- [x] **Step 1: 写请求/响应 VO 与 6 条失败测试**

`SelfJudgeReq.java`：

```java
package org.leon.practicemodule.pojo;

import lombok.Data;

/** 简答自评请求体。recordId 来自 submit 的响应；isCorrect 必填（true=对 / false=错） */
@Data
public class SelfJudgeReq {
    private Long subjectId;
    private Long questionId;
    private Long recordId;
    private Boolean isCorrect;
}
```

`SelfJudgeVO.java`：

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

@Data
public class SelfJudgeVO {
    private StatDeltaVO statDelta;
}
```

在 `AnswerServiceTest` 追加：

```java
    private QbUserAnswerRecord pendingRecord() {
        QbUserAnswerRecord record = new QbUserAnswerRecord();
        record.setId(55L);
        record.setUserId(USER);
        record.setSubjectId(1L);
        record.setQuestionId(5L);
        record.setIsCorrect(-1);
        record.setAnswerMode(1);
        return record;
    }

    private SelfJudgeReq judgeReq(boolean correct) {
        SelfJudgeReq r = new SelfJudgeReq();
        r.setSubjectId(1L);
        r.setQuestionId(5L);
        r.setRecordId(55L);
        r.setIsCorrect(correct);
        return r;
    }

    @Test
    @DisplayName("自评成功：条件更新生效后统计与错题本才联动")
    void selfJudgeSuccessAccumulatesStat() {
        when(recordMapper.selectById(55L)).thenReturn(pendingRecord());
        when(questionMapper.selectById(5L)).thenReturn(question(5, "\"参考答案\""));
        when(recordMapper.judgeIfPending(55L, USER, 1)).thenReturn(1);
        when(wrongMapper.selectByUserAndQuestion(USER, 5L)).thenReturn(null);
        stubStat(1, 1, 0);

        SelfJudgeVO vo = service.selfJudge(USER, judgeReq(true));

        assertEquals(1, vo.getStatDelta().getTotal());
        verify(statMapper).upsertAccumulate(USER, 1L, 1, 0);
        verify(wrongMapper, never()).upsertWrong(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("自评第二次：条件更新 0 行 -> 40900，统计一次都不重复累加")
    void secondSelfJudgeIsConflict40900() {
        when(recordMapper.selectById(55L)).thenReturn(pendingRecord());
        when(questionMapper.selectById(5L)).thenReturn(question(5, "\"参考答案\""));
        when(recordMapper.judgeIfPending(55L, USER, 1)).thenReturn(0);

        PracticeException e = assertThrows(PracticeException.class,
                () -> service.selfJudge(USER, judgeReq(true)));
        assertEquals(PracticeErrorCode.CONFLICT, e.errorCode());
        verify(statMapper, never()).upsertAccumulate(anyLong(), anyLong(), anyInt(), anyInt());
        verify(wrongMapper, never()).upsertWrong(anyLong(), anyLong(), anyLong());
        verify(wrongMapper, never()).masterIfUnmastered(anyLong(), anyLong());
    }

    @Test
    @DisplayName("自评判错：错题本按答错规则 upsert")
    void selfJudgeWrongUpsertsWrongBook() {
        when(recordMapper.selectById(55L)).thenReturn(pendingRecord());
        when(questionMapper.selectById(5L)).thenReturn(question(5, "\"参考答案\""));
        when(recordMapper.judgeIfPending(55L, USER, 0)).thenReturn(1);
        QbUserWrong row = new QbUserWrong();
        row.setWrongCount(1);
        row.setStatus(1);
        when(wrongMapper.selectByUserAndQuestion(USER, 5L)).thenReturn(row);
        stubStat(1, 0, 1);

        SelfJudgeVO vo = service.selfJudge(USER, judgeReq(false));

        assertEquals(1, vo.getStatDelta().getWrong());
        verify(wrongMapper).upsertWrong(USER, 1L, 5L);
        verify(statMapper).upsertAccumulate(USER, 1L, 0, 1);
    }

    @Test
    @DisplayName("自评别人的记录 40300，且不执行条件更新")
    void selfJudgeOthersRecordIs40300() {
        QbUserAnswerRecord record = pendingRecord();
        record.setUserId(99L);
        when(recordMapper.selectById(55L)).thenReturn(record);

        PracticeException e = assertThrows(PracticeException.class,
                () -> service.selfJudge(USER, judgeReq(true)));
        assertEquals(PracticeErrorCode.FORBIDDEN, e.errorCode());
        verify(recordMapper, never()).judgeIfPending(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("非简答题自评 40000")
    void selfJudgeNonShortAnswerIs40000() {
        when(recordMapper.selectById(55L)).thenReturn(pendingRecord());
        when(questionMapper.selectById(5L)).thenReturn(question(1, "\"A\""));

        PracticeException e = assertThrows(PracticeException.class,
                () -> service.selfJudge(USER, judgeReq(true)));
        assertEquals(PracticeErrorCode.BAD_REQUEST, e.errorCode());
        verify(recordMapper, never()).judgeIfPending(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("记录不存在 40400")
    void selfJudgeMissingRecordIs40400() {
        when(recordMapper.selectById(55L)).thenReturn(null);

        PracticeException e = assertThrows(PracticeException.class,
                () -> service.selfJudge(USER, judgeReq(true)));
        assertEquals(PracticeErrorCode.NOT_FOUND, e.errorCode());
    }
```

需要的 import 追加：`SelfJudgeReq`、`SelfJudgeVO`。

- [x] **Step 2: 跑测试确认 6 条失败（找不到 selfJudge）**

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=AnswerServiceTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```
Expected: 编译失败。

- [x] **Step 3: 实现 `selfJudge` 并挂端点**

`AnswerService` 追加：

```java
    SelfJudgeVO selfJudge(Long userId, SelfJudgeReq req);
```

`AnswerServiceImpl` 追加：

```java
    @Override
    @Transactional
    public SelfJudgeVO selfJudge(Long userId, SelfJudgeReq req) {
        if (req.getRecordId() == null || req.getQuestionId() == null
                || req.getSubjectId() == null || req.getIsCorrect() == null) {
            throw badRequest("请求参数不合法");
        }
        QbUserAnswerRecord record = recordMapper.selectById(req.getRecordId());
        if (record == null) {
            throw notFound("作答记录不存在");
        }
        if (!userId.equals(record.getUserId())) {
            throw new PracticeException(PracticeErrorCode.FORBIDDEN, "无权操作该作答");
        }
        Integer answerMode = record.getAnswerMode();
        if (answerMode == null || (answerMode != 1 && answerMode != 3)) {
            // 审查补的闸门：段 5 若把考试简答也落成 -1，这里不让它绕开 7.1 把整卷写进科目统计
            throw badRequest("仅逐题练习作答支持自评");
        }
        if (!record.getQuestionId().equals(req.getQuestionId())) {
            throw badRequest("记录与题目不匹配");
        }
        if (!record.getSubjectId().equals(req.getSubjectId())) {
            throw badRequest("记录与科目不匹配");
        }
        QbQuestion question = questionMapper.selectById(req.getQuestionId());
        if (question == null) {
            throw notFound("题目不存在");
        }
        if (question.getType() == null || question.getType() != 5) {
            throw badRequest("仅简答题支持自评");
        }

        int judged = recordMapper.judgeIfPending(req.getRecordId(), userId, req.getIsCorrect() ? 1 : 0);
        if (judged == 0) {
            // 条件更新是幂等闸门：并发或重试只有第一次生效，统计与错题本不会被重复计数
            throw new PracticeException(PracticeErrorCode.CONFLICT, "该记录已完成自评");
        }
        applyOutcome(userId, record.getSubjectId(), record.getQuestionId(), req.getIsCorrect());
        return new SelfJudgeVO(readOutcome(userId, record.getSubjectId(), record.getQuestionId()).statDelta());
    }
```

`PracticeAnswerController` 追加：

```java
    @PostMapping("/self-judge")
    public Result<SelfJudgeVO> selfJudge(@RequestHeader("X-User-Name") String userName,
                                         @RequestBody SelfJudgeReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(answerService.selfJudge(userId, req));
    }
```

`PracticeAnswerControllerTest` 追加：

```java
    @Test
    @DisplayName("self-judge：包 Result 返回 statDelta")
    void selfJudgeWrapsInResult() {
        when(identity.resolveUserId("alice")).thenReturn(2L);
        SelfJudgeVO vo = new SelfJudgeVO();
        StatDeltaVO delta = new StatDeltaVO();
        delta.setTotal(5);
        vo.setStatDelta(delta);
        when(answerService.selfJudge(eq(2L), any())).thenReturn(vo);

        Result<SelfJudgeVO> r = controller.selfJudge("alice", new SelfJudgeReq());

        assertEquals(1, r.getCode());
        assertEquals(5, r.getData().getStatDelta().getTotal());
    }
```

- [x] **Step 4: 跑测试确认全绿（18 + 3）**

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=AnswerServiceTest,PracticeAnswerControllerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```
Expected: `Tests run: 21, Failures: 0`

- [x] **Step 5: 全量回归 + 提交**

Run:
```bash
"$MVN" -pl PracticeModule -am test 2>&1 | grep -E "Tests run:|BUILD"
```
Expected: `Tests run: 89`（30 基线 + 32 + 6 + 18 + 3），`BUILD SUCCESS`，不含 `AnswerFlowDbIT`。数字不符先查漏发现再写执行记录。

```bash
git add PracticeModule/src/main/java/org/leon/practicemodule/pojo/AnswerSubmitReq.java \
        PracticeModule/src/main/java/org/leon/practicemodule/pojo/SelfJudgeReq.java \
        PracticeModule/src/main/java/org/leon/practicemodule/pojo/vo/StatDeltaVO.java \
        PracticeModule/src/main/java/org/leon/practicemodule/pojo/vo/AnswerSubmitVO.java \
        PracticeModule/src/main/java/org/leon/practicemodule/pojo/vo/SelfJudgeVO.java \
        PracticeModule/src/main/java/org/leon/practicemodule/service/AnswerService.java \
        PracticeModule/src/main/java/org/leon/practicemodule/service/impl/AnswerServiceImpl.java \
        PracticeModule/src/main/java/org/leon/practicemodule/controller/PracticeAnswerController.java \
        PracticeModule/src/main/java/org/leon/practicemodule/mapper/QbUserAnswerRecordMapper.java \
        PracticeModule/src/main/java/org/leon/practicemodule/mapper/QbUserWrongMapper.java \
        PracticeModule/src/main/java/org/leon/practicemodule/mapper/QbUserSubjectStatMapper.java \
        PracticeModule/src/main/java/org/leon/practicemodule/mapper/QbQuestionOptionMapper.java \
        PracticeModule/src/test/java/org/leon/practicemodule/service/AnswerServiceTest.java \
        PracticeModule/src/test/java/org/leon/practicemodule/controller/PracticeAnswerControllerTest.java \
        PracticeModule/src/test/java/org/leon/practicemodule/mapper/AnswerFlowDbIT.java
git commit -m "作答落库与自评：错题统计联动、-1 待自评哨兵、条件更新保证自评恰好一次" -- \
        PracticeModule/src/main/java/org/leon/practicemodule/pojo \
        PracticeModule/src/main/java/org/leon/practicemodule/pojo/vo \
        PracticeModule/src/main/java/org/leon/practicemodule/service \
        PracticeModule/src/main/java/org/leon/practicemodule/controller \
        PracticeModule/src/main/java/org/leon/practicemodule/mapper \
        PracticeModule/src/test/java/org/leon/practicemodule/service \
        PracticeModule/src/test/java/org/leon/practicemodule/controller \
        PracticeModule/src/test/java/org/leon/practicemodule/mapper
git show --stat --oneline HEAD
```
Expected: 文件清单恰好是上面 15 个，不含任何 `AIModule/` 路径。**注意 `pojo` 与 `pojo/vo` 的 pathspec 会带上段 3 之前已存在但未提交的同目录文件——提交前先 `git status --short` 确认没有并发会话的暂存文件。**

---

### Task 2.6: 走网关的端到端取证（段 2 验收）

**Files:**
- Modify: `docs/superpowers/plans/2026-10-23-practice-02-grader-masker.md`（"执行记录"小节）
- Create（仓库外）: `C:/Users/Administrator/.qoder-cn/tmp/F--AAAJAVA-code-ezPracticeExercises/Seg2Probe.java`（查询证据用探针）

**Interfaces:**
- Consumes: 网关 8100、AuthModule 8103、PracticeModule 8102、test918/123456
- Produces: 段 2 的验收证据与偏差记录

- [x] **Step 1: 构建并启动 PracticeModule**

```bash
"$MVN" -pl PracticeModule -am -DskipTests install
# 另开终端/后台：
"$MVN" -pl PracticeModule spring-boot:run
```
注意：**不能**用 `-pl PracticeModule -am spring-boot:run`（段 1 实测的坑：`-am` 会在聚合根跑 `spring-boot:run` 并失败）。
Expected: 日志 `Started PracticeModuleApplication`；`netstat -ano | grep ":8102"` 在听；Nacos 里 `PracticeModuleServer` healthy。

- [x] **Step 2: 取 token（同段 1 流程）**

```bash
CAP=$(curl -s http://127.0.0.1:8100/auth/captcha)
KEY=$(echo "$CAP" | grep -oE '"captchaKey":"[^"]+"' | cut -d'"' -f4)
CODE=$(redis-cli -n 0 GET "auth:captcha:$KEY")   # AuthModule 有意保留的明文验证码
RESP=$(curl -s -X POST http://127.0.0.1:8100/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"test918\",\"password\":\"123456\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}")
TOKEN=$(echo "$RESP" | grep -oE '"token":"[^"]+"' | cut -d'"' -f4)
echo "token 长度: ${#TOKEN}"   # 不回显 token 本体
```
Expected: `${#TOKEN}` 大于 100。（验证码 TTL 120s，Redis 里没有明文就说明 key 过期，重取。）

- [x] **Step 3: 正路径——逐条打题，每步断言一个验收点**

请求体写成文件再 `-d @file`，避免 shell 转义 `\"A\"` 这类嵌套 JSON：

```bash
TMP=/c/Users/Administrator/.qoder-cn/tmp/F--AAAJAVA-code-ezPracticeExercises/seg2
mkdir -p "$TMP"
post() { # $1=文件 $2=路径
  curl -s -X POST "http://127.0.0.1:8100/practice/answer/$2" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d @"$1" -o "$TMP/$2-$(date +%s%3N).json"
}
cat > "$TMP/q1-A.json" <<'EOF'
{"subjectId":1,"questionId":1,"userAnswerJson":"\"A\"","durationMs":3000,"mode":1}
EOF
post "$TMP/q1-A.json" submit
```

按顺序执行并在每步 `cat` 出响应文件核对（**响应文件用编辑器/Read 读，不要经控制台管道**——段 1 的 GBK 乱码教训）：

| # | 请求 | 期望 |
|---|---|---|
| 1 | qid=1 答 `"A"`（正确） | `isCorrect:true`，`statDelta.total` 增 1 |
| 2 | qid=1 答 `"B"`（错） | `isCorrect:false`，`wrongCount:1` |
| 3 | qid=1 再答 `"B"` | `wrongCount:2` ← **验收清单第 2 条** |
| 4 | qid=1 答 `"A"` | `wrongMastered:true` |
| 5 | qid=1 再答 `"B"` | `wrongCount:3` 且 `wrongMastered:false` ← **7.3 回退** |
| 6 | qid=2（多选）答 `["A","C"]` | `isCorrect:true` |
| 7 | qid=3（判断，subjectId=2）答 `true` | `isCorrect:true` |
| 8 | qid=4（填空，subjectId=2）答 `["光合作用"]` | `isCorrect:true` |
| 9 | qid=5（简答）答任意文本 | `needSelfJudge:true`、`isCorrect:null`，记下 `recordId` |
| 10 | self-judge recordId=`<Step9 的值>`，`isCorrect:true` | `statDelta.total` 再增 1 |
| 11 | 重发 Step 10 | `code:40900`，`statDelta` 总数不再变 ← **幂等闸门** |

- [x] **Step 4: 负路径（比正路径更有价值）**

```bash
# a) 无 token -> 网关直接 401
curl -s -o /dev/null -w "no-token HTTP %{http_code}\n" -X POST \
  http://127.0.0.1:8100/practice/answer/submit -H 'Content-Type: application/json' \
  -d '{"subjectId":1,"questionId":1,"userAnswerJson":"\"A\"","mode":1}'

# b) mode=2（考试作答走答题接口）-> code=40000
# c) 空答案 userAnswerJson:"  " -> 40000
# d) qid=999 不存在 -> 40400
# e) qid=1 配 subjectId=2 -> 40000（题目不属于该科目）
# f) qid=2 多选传字符串 "A" -> 40000（答案格式与题型不符，且库中记录行数不变）
# g) 对 qid=1 的记录（type=1）自评 -> 40000（仅简答题支持自评）
```
Expected: a) `HTTP 401`；b–g) HTTP 200 + 各自 `code`。**f/g 还要用探针确认记录行数没变。**

- [x] **Step 5: 库内取证**

`Seg2Probe.java`（仓库外、只读）打印：
- `qb_user_wrong WHERE user_id=10` → qid=1 一行、`wrong_count=3`、`status=1`（Step 5 末尾状态）
- `qb_user_subject_stat WHERE user_id=10` → 语文一行，`total_count/correct_count/wrong_count` 与响应里最后的 `statDelta` 完全一致
- `qb_user_answer_record WHERE user_id=10 ORDER BY id` → 10 行（Steps 1–5 共 5 行、Step 6–8 各 1 行、Step 9 简答 1 行、Step 10 自评不改行数）——**行数与 `is_correct` 序列逐条与请求序列对上**，其中简答行为 `is_correct=1`（自评回填后）或自评选定值。

- [x] **Step 6: 写执行记录并提交**

在本文件"执行记录"追加：日期、Step 1 启动与端口证据、Step 3 每条的实际 `code/isCorrect/wrongCount/statDelta`、Step 4 每条的实际输出、Step 5 探针输出、与计划的全部偏差（含测试数字与 `-1` 哨兵的实际表现）。

```bash
git add docs/superpowers/plans/2026-10-23-practice-02-grader-masker.md
git commit -m "段2执行记录：判分/脱敏/落库端到端取证，错题流转与自评幂等实测" -- \
        docs/superpowers/plans/2026-10-23-practice-02-grader-masker.md
git show --stat --oneline HEAD
```

---

## 段 2 完成定义

- [x] `"$MVN" -pl PracticeModule -am test` 全绿（计划 89 → 首跑 91 → 审查整改后 **101**：基线 30 + 本段 71），`db-it` 默认被排除
- [x] `AnswerFlowDbIT` 显式开启时 3 条全绿，跑完 dev 库无 `user_id=990001` 残留
- [x] `AnswerGraderTest` 32 个用例表驱动全绿；`PracticeMaskerTest` 守卫 + 非空转证明全绿
- [x] 经网关：答对/答错/简答自评 `code=1`；qid=1 错两次 `wrongCount=2`；答对 `wrongMastered=true`；再错回退为 `false` 且 `wrongCount=3`
- [x] 自评第二次 `40900` 且 `statDelta` 不变
- [x] 负路径：mode=2 / 空答案 / 跨科目 / 格式不符全部 `40000`；题不存在 `40400`；无 token HTTP 401
- [x] 执行记录已写，含所有偏差
- [x] **未动**：任何 DDL、`qb_question`/`qb_question_option` 的写、`qb_paper`/`qb_exam_attempt`、AIModule 与 `CommonJwt` 文件

---

## 边界与接缝（本段明确不做）

| 边界 | 接缝在哪 |
|---|---|
| `answerStatus` 对 `-1` 的映射 | 段 3 的 `question/page`/`question/current` 从最新一条记录推 `answerStatus`（0/1/2）。遇到 `is_correct=-1`（简答待自评）必须显式裁定：候选是把该题显示为"未作答"（可重做）或给前端新增一个"待自评"态。**本段只保证不把 -1 当错、不出现在 API 里** |
| **考试侧简答题如何得分**（审查 #6：spec 的洞，不是实现的洞） | `AnswerGrader` 对 `type=5` 永远 `correct=false / needSelfJudge=true`，而 spec 5.6 交卷第 4 步"逐题判分"与 `ExamResultVO.questions[].isCorrect` 都默认判分器能给结论。按现口径，套卷里只要出现简答题，交卷后必然 `is_correct=0`、进错题本、扣分——**永远不可能对**。段 4 灌样例题之前必须裁定：考试简答免判满分 / 交卷后再自评 / 样例卷不含简答题。**否则是隐式数据错误** |
| 自评只允许练习记录 | 审查后补的闸门：`selfJudge` 显式要求 `answer_mode IN (1,3)`。段 5 即使复用 `-1`，也无法借答题接口把整张卷子写进科目统计——7.1 不再只靠段 5 自觉 |
| 脱敏守卫的覆盖面 | 本段守卫扫的是 `QuestionVO` 的**进程内序列化**（段 2 还没有返回它的端点）。段 3 第一件事是把 `PracticeMasker.FORBIDDEN_IN_ANSWER_STATE` 这份清单打到**真实响应字节**上（standalone MockMvc + `setControllerAdvice(new PracticeExceptionHandler())`，不违反禁 `@SpringBootTest`），否则守卫保护的是类型而不是 API |
| 自评先看到参考答案 | `needSelfJudge=true` 时响应即含 `correctAnswerJson`/`analysis`，用户看完再给自己打分，"对"的统计意义因此打折。这是 spec 5.2 定的响应结构，**实现无错**，属产品裁定项（可改为自评响应里才给，需给 `SelfJudgeVO` 加两字段） |
| db-it 样板与口令重复 | `AnswerFlowDbIT` 与 `QbQuestionMapperDbIT` 各写一份 SqlSession 与 `root`/空口令；roadmap 提到的 `PracticeDbTestSupport` 实际并不存在。段 4/段 5 会再复制两遍，届时应抽出并把口令移出仓库文件 |
| `application.yaml` 的 `log-impl: StdOutImpl` | 段 2 是本模块第一次写用户内容，STDout 会把含 `user_answer_json` 的 SQL 连参数打进日志。既有配置、非本段引入，但上真实环境前必须换回 `Slf4jImpl` 并按需调级 |
| 自评后反悔 | 无撤销。改主意就重新作答一题再自评——记录留痕是刻意的 |
| 并发对同一题双提交 | 两次提交记两行、错题本累两次。与"答两次算两次"一致，不加去重 |
| `durationMs` 上限 | 只拦负值；上限属反机器人范畴，不在这里引入 |
| 简答进错题本 | 仅当自评判错时；判对不建行（与普通答对一致） |

---

## 执行记录

### 2026-10-23 段 2 执行完成

**测试基线**：`mvn -pl PracticeModule -am test` → **91/91 全绿**（段 1 的 30 + 本段 61），`AnswerFlowDbIT` 默认被排除。
`-Dtest=AnswerFlowDbIT -Dsurefire.excludedGroups=` → **3/3 全绿**（真库），跑完 `user_id=990001` 在三张表各 0 行（ResidueProbe 实测）。
计划里的 89 是写死的期望值，实际 91：`AnswerGraderTest` 因下述缺陷重写后为 33 条（原 32），`AnswerServiceTest` 多补了一条参数缺失守卫（18→19）。**没有"Tests run 变少"的漏发现。**

**提交**：`cc386aa`（判分器）、`2438eb3`（脱敏出口）、`3b5b5b8`（落库与自评，15 文件）、本条记录提交随后。逐文件 `git add` + pathspec 限定，`git show --stat` 核对未卷入并发会话的 AIModule 文件。

#### 端到端发现的真缺陷（唯一一处代码修正，价值最高）

首轮端到端第 9 步（简答题提交）返回 **`code=0`（服务器繁忙）**，服务端日志：
`DataIntegrityViolationException: INSERT INTO qb_user_answer_record`。

根因：`qb_user_answer_record.user_answer_json` 是 **MySQL `json` 列**，而 `AnswerGrader` 的 `case 5` 按计划"简答不判分也不解析答案体"，把用户原样文本直接写库。1–4 题型因为要判分必然先过 JSON 解析，所以只有简答题漏。
计划里那条测试（`shortAnswerCases` 第二条 `"\"not json"`，断言"坏格式也不该炸"）**前提是错的**——它保护的是一个会让接口 500 的行为。

修正：`AnswerGrader` 的 `case 5` 改为**只验语法、不比对内容**（`parse(userAnswerJson)` 后 `yield new GradeResult(false, true)`），非法 JSON 归为 40000；测试断言反转为两条 40000 反例。端到端复测：`n6-short-illegal-json` → `code=40000`，且不写库。

**教训（写给自己也写给段 5）**：单测把 mapper 全 mock 掉了，就看不见列类型约束——**凡写进 `json` 列的值必须有语法校验，且必须有端到端或真库测试兜底**。段 4/段 5 的 `qb_paper.structure_json` 与 `qb_exam_attempt.answered_json` 同理：写入前必须 `ObjectMapper.writeValueAsString` 产出合法 JSON，绝不能把裸文本塞进去。

#### 端到端取证（全程经网关 8100，账号 test918 / user_id=10）

前置：三个服务当时都未运行，本次自行启动 Gateway 8100、Auth 8103、PracticeModule 8102（日志各自 `Started ...Application`，8102 有 `✅ JWT 工具类初始化成功` 与 Nacos `healthy=true`）。登录用 Redis db0 的明文验证码（6 位码 `861857`，本次实测长度是 6 不是 4），拿到 219 字符 token。

| # | 场景 | 实测 |
|---|---|---|
| 1–5 | 同一题（qid=1 单选）对/错/错/对/错 | `wrongCount` 依次 `0→1→2→2→3`；第 4 步 `wrongMastered=true`，第 5 步回退 `false` ← **7.3 与验收清单第 2 条成立** |
| 6/7/8 | 多选 `["A","C"]` / 判断 `true` / 填空 `["光合作用"]` | 全部 `code=1 isCorrect=true` ← 五题型判分在真实链路上都跑过 |
| 9 | 简答 qid=5 | `code=1`、`isCorrect=null`、`needSelfJudge=true`，`statDelta.total` 仍为 6 ← **待自评不污染统计** |
| 10 | self-judge `isCorrect=true` | `code=1`，`statDelta.total` 6→**7**、acc 0.57 ← **错题本与统计的计数发生在自评那一刻**（spec 5.3） |
| 11 | self-judge 重发 | **`code=40900`**，`statDelta` 不再变化 ← **幂等闸门生效** |
| — | `mode=3` 智能练习补测 | `code=1`，`statDelta={total:8,correct:5,wrong:3,accuracy:0.63}` ← **7.1 的 IN (1,3) 端到端成立** |
| 负 | 无 token | **HTTP 401** `{"code":401,"message":"缺少或无效的授权头"}`（网关侧，非 `Result`） |
| 负 | `mode=2` | 40000（考试作答不走答题接口） |
| 负 | 空答案 / 跨科目 / 多选传字符串 | 均 40000 |
| 负 | qid=999 / 自评不存在记录 | 均 40400 |
| 负 | 对单选记录自评 | 40000（仅简答题支持自评） |

**交叉验证（裁定二）**：最后打 `GET /practice/subject/list` → `chinese myTotal=8 myCorrect=5 myAccuracy=0.63`、`math myTotal=2 myCorrect=2 myAccuracy=1.0`，与末次 `statDelta` 完全一致 → `statDelta` 确实是累计快照，且与段 1 的 `accuracy` 同一个数同一个口径。

**库内取证（`Seg2Evidence`，只读）**：
`qb_user_wrong` 仅 1 行 `question_id=1 wrong_count=3 status=1`（已掌握再错回退）；
`qb_user_subject_stat` 语文 `8/5/3`、数学 `2/2/0`；
`qb_user_answer_record` 共 10 行，`is_correct` 与请求序列逐条一致，其中简答行 id=17 为 **`is_correct=1`**（写入时为 `-1`，被条件更新恰好改一次）——**三态哨兵与幂等在真库上闭环**。

#### 与计划的偏差（全部为执行期实测所得）

1. **`AnswerGrader` case 5 增加 JSON 语法校验**：如上，属缺陷修正，非风格分歧。
2. **编译器逼出的三处写法修正**（不影响行为）：
   - MyBatis-Plus 3.5.17 的 `BaseMapper` 同时有 `insert(T)` 与 `insert(Collection<T>)`，`insert(any())` 报"引用不明确" → 必须 `insert(any(QbUserAnswerRecord.class))`；
   - Lombok 对原始 `boolean` 生成 `isXxx()`、对包装 `Boolean` 生成 `getXxx()`，同一个 `AnswerSubmitVO` 里两种都有 → 测试取值分别为 `isNeedSelfJudge()` / `getIsCorrect()`；
   - `PracticeMaskerTest` 需要 `question(Integer)` 重载才能测 null 题型；`SelfJudgeVO` 用无参构造 + setter（与仓库其余 VO 一致），不用计划里写的全参构造。
   以上已回写进本文件"前置：环境与关卡"的实测修正与代码块，段 3 直接照抄即可。
3. **测试条数 91 ≠ 计划 89**：见上。
4. **首轮端到端脚本自身写错**：bash heredoc 里 `"userAnswerJson":"A"` 少了内层转义，实际送出的是裸文本 `A`，导致 8 步全 40000（服务端行为是正确的，是我的测试写错了）。改为 Python 生成请求体后一次通过。教训：**接口契约里带"JSON 字符串"字段时，测试驱动必须显式编码两次**，别用 shell 拼。
5. **`TaskStop` 只杀 Maven 包装进程，不杀 forked JVM**：端口 8102 被旧 JVM 占住，重启表现为 `BUILD FAILURE / Process terminated exit code 1`，看起来像代码问题。按 PID `taskkill` 后才起来。改完代码要重启前先确认 8102 的持有者。
6. **写库动作**：本段确实写了两处 dev 库——`AnswerFlowDbIT` 的合成用户 990001（已清 0 行）与 test918 的真实作答数据（10 条记录 + 2 条统计 + 1 条错题）。开跑前把 test918 清回今日实测的零行基线（清掉了首轮失败留下的 1 记录 + 1 统计）。**如需复原**：`DELETE FROM qb_user_answer_record WHERE user_id=10; DELETE FROM qb_user_wrong WHERE user_id=10; DELETE FROM qb_user_subject_stat WHERE user_id=10;`
7. **未做端到端覆盖的分支**：`40900 题目已下架`（种子数据里无 `status=0` 的题）、`40300 越权操作他人记录`（只有一个账号），二者仅单测覆盖。40100 已由段 1 的"直连 8102 不带 `X-User-Name`"覆盖。别把这三条当成端到端已验。

**环境状态**：Gateway 8100 / Auth 8103 / PracticeModule 8102 三个进程仍在后台运行（日志在 `~/.qoder-cn/tmp/F--AAAJAVA-code-ezPracticeExercises/seg2/*.log`）。

### 2026-10-23 段 2 独立审查与整改（提交 `5f1f589`）

段 2 的代码全部由我一人写，故派了一个不带本会话历史的独立审查代理复核 `81ad6a3..92facb9`。审查实测复核了测试数（91 绿、db-it 默认排除）。**下列结论我都自己验证过，不是照单全收**：

**Critical #1「json 列写入类缺陷只修了一半」——成立，但范围与审查者给的不同。**
- 我的验证（项目实际解析到的 Jackson 2.15.4 + MySQL `JSON_VALID` 探针，均在仓库外）：Jackson 的 `readTree` 确实**只取第一个值并容忍尾随**，但审查者列举的用例有一半本来就拒：`"A" trailing`、`["A"] junk`、`["A","B"],` 全部 `JsonParseException`；真正放行的是**两个合法值相连**的形态——`"A" "B"`、`true false`、`{"a":1}{"b":2}`。`1e999` 被读成 `Infinity`。
- MySQL 侧实测 `JSON_VALID`：`"A" "B"`=0、`true false`=0、`{"a":1}{"b":2}`=0、`1e999`=0；**审查者标注"unverified"的孤立代理对 `"\ud800"` 实测为 1（MySQL 接受）**，所以没有给它加校验。
- 经网关重放的真实 `code=0` 输入（修复前）：单选 `"A" "B"`、多选 `["A","C"] ["B"]`、判断 `true true`、简答 `"文本" "文本"`、简答 `1e999` —— 五条全部复现。审查者说"五种题型全部成立"方向对，具体样例一半不准；`判断 true false` 之所以没炸，是因为首值不是文本被题型校验顺手挡住了。
- **修法选了"判分器收紧"而不是"改写规范化值"**：`parse` 改为显式 `JsonParser` 并在取值后断言 `nextToken()==null`（多值即 40000），再加 `requireFinite` 递归拒绝非有限数值。理由：让 `GradeResult` 带 canonical JSON 会改判分器契约，而收紧后剩下的接受集已是"整份单值 + 有限数值 + 题型合法"，MySQL 无进一步拒绝余地；且 canonical 序列化会把 `Infinity` 写成字符串 `"Infinity"`（MySQL 接受！）反而把坏数据洗成合法数据。
- 修复后重放：五条全部 `40000`（msg 分别为"答案不是合法 JSON"/"答案含非法数值"），正常路径 `code=1` 不变；**失败的插在统计里零增量**，顺带证明 `@Transactional` 回滚有效。
- 新增测试：`AnswerGraderTest` +6（多值/非有限，每题配与该题型匹配的正确答案，保证失败归因在用户答案）、`AnswerServiceTest` +1（多值不落库）。

**Important #2「selfJudge 缺 answer_mode 闸门」——成立，已修。** 先写了失败测试（`selfJudgeOnExamRecordIs40000`，修复前它返回的是 `CONFLICT` 而非 `40000`，证明确实没有闸门），再在归属校验之后加 `answer_mode IN (1,3)` 断言。这条把 7.1 从"段 5 自觉"变成代码约束。

**Important #3「三份文档仍写着暂记 0」——成立，已回写**：roadmap §3 第 10 条改为三态并新增第 16 条（json 列校验教训）；spec §5.3 表格行与后半段加"段 2 更正"注记（不覆盖原文，保留可追溯性）；spec §9.1.2 写明守卫清单常量与"段 3 必须升级为响应级"；本计划的 Task 2.1 `case 5`、`parse`、`shortAnswer*` 测试块与 Task 2.5 的 `selfJudge` 块全部换成落地版——审查者说得对：**执行记录里写的"已回写"当时并未真的回写，照抄初稿会把已知缺陷抄回去**。

**Important #4「单选选项集接线不可证伪」——成立但当前无 bug。** 补了两条测试：集外字母 `"D"`（选项只 A/B）→ 40000 且不落库；判断题 → `verifyNoInteractions(optionMapper)`。第二条修复前就是绿的，说明接线本来是对的，缺的只是锁。

**Important #5「守卫只扫 VO 不扫响应、只查 key 不查值」——成立（前半段是段 2 的固有边界）。** 已抽出 `PracticeMasker.FORBIDDEN_IN_ANSWER_STATE`（补上 `answer_json` 形态）供段 3/段 5 复用，并在守卫里加了值级哨兵（`ANSWER_SENTINEL`/`ANALYSIS_SENTINEL`），这样"改名照抄内容"也会变红。响应级 MockMvc 断言留给段 3（本段确实没有返回 `QuestionVO` 的端点），已写进接缝表首行。

**Minor 处理**：#8（"读回才是并发下唯一正确的绝对值"过强）已改注释——只有本事务 upsert 过的行是绝对值，没写过的行是快照下界。#13（文件尾换行）与 #7/#9/#10/#11 未动：#7 的 `type` 拆箱有实测 NOT NULL 背书，#9 是 spec 明确只给填空做全角归一，#10/#11 是题库数据异常，都不是本期缺陷；#14/#15/#16 与 #6 写进接缝表，其中 **#16（自评先看到参考答案）和 #6（考试简答永远不可能得分）是需要用户/产品裁定的**，不是我能替用户定的默认值。

**审查结论**：两条设计裁定都被独立复核为成立（审查者额外核实了 `is_correct`/`qb_user_answer_record` 目前全仓库只有 PracticeModule 引用，所以"共享表三态"是未来风险不是现实风险）。整改后 **101 单测绿 + 3 db-it 绿**。审查者判定"With fixes"，三个 must-fix 已全部闭合。

**新增测量坑（本段第二条）**：`netstat -ano | grep ":8102"` 的 `:8102` 没锚定，会匹配到含 18102 之类子串的行，`awk '{print $5}'` 就可能取到别的 PID；我据此杀进程时拿到的是 Maven 父进程 PID 而非应用 PID（应用日志里写的是另一个）。**杀端口持有者前先和启动日志里的 `with PID <n>` 对齐**，别只信 netstat 的第一行。