# 段 1：PracticeModule 地基与身份链路 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务执行本计划。步骤用 `- [ ]` 语法跟踪。

**Goal:** 让 `PracticeModule` 具备写业务代码的前提——能编译进统一返回体、实体与真实表一致、有统一的异常与子码、能把网关注入的 `X-User-Name` 换成 `user_id`，并以 `GET /practice/subject/list` 作为第一个走通网关的真实接口。

**Architecture:** 六个任务，前四个是地基（依赖与扫描、实体重生成、异常与子码、身份解析），第五个把地基串成一个可读接口，第六个做端到端取证。`UserIdentityService` 是身份的唯一入口，后续所有段都从它拿 `userId`；如果将来改判为"网关注入 `X-User-Id`"（roadmap 第 3 节第 6 条），只需替换它一个方法体。

**Tech Stack:** Java 21 / Spring Boot 3.2.4 / MyBatis-Plus 3.5.17（`com.baomidou.mybatisplus.spring.service.IService`，不是 `extension.service`）/ MySQL 9.1.0 / Spring Data Redis（`StringRedisTemplate`）/ JUnit 5 + Mockito。

**Spec:** `docs/superpowers/specs/2026-10-23-practice-exam-api-design.md`
**Roadmap:** `docs/superpowers/plans/2026-10-23-practice-00-roadmap.md`（全局约束与 15 条实测修正全部隐含适用，本文件不重复）

---

## 本段文件结构

| 文件 | 责任 | 动作 |
|---|---|---|
| `PracticeModule/pom.xml` | 引入 `CommonJwt`、补 surefire 分组 | Modify |
| `PracticeModule/src/main/java/org/leon/practicemodule/PracticeModuleApplication.java` | 组件扫描覆盖 `org.leon.commonjwt` | Modify |
| `.../pojo/QbQuestion.java` 等 7 个实体 | 与真实列一致 | Modify/Create |
| `.../mapper/QbQuestionMapper.xml`（在 `src/main/java` 下） | 死文件 | **Delete** |
| `.../exception/PracticeException.java` `PracticeErrorCode.java` `PracticeExceptionHandler.java` | 业务异常与子码 | Create |
| `.../service/UserIdentityService.java` `.../service/impl/UserIdentityServiceImpl.java` | `X-User-Name` → `user_id` + Redis 缓存 | Create |
| `.../mapper/UsersReadMapper.java` | 只读查 `users_dev`，不建实体 | Create |
| `.../pojo/vo/PageVO.java` | 统一分页壳 | Create |
| `.../config/PracticeProperties.java` | `practice.identity.*` | Create |
| `.../service/SubjectService.java` `.../impl/SubjectServiceImpl.java` | 科目列表 + 我的进度 | Create |
| `.../pojo/vo/SubjectItemVO.java` | 科目卡片 | Create |
| `.../controller/PracticeSubjectController.java` | `GET /practice/subject/list` | Create |

**本段不碰**：`AnswerGrader`、`PracticeMasker`（段 2）、`qb_paper`/`qb_exam_attempt`（段 4）、任何 DDL。

---

## 前置：环境与关卡

```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
MVN="/c/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
```

**已获授权的段 1 动作**（roadmap 第 6 节）：改 `PracticeModule/pom.xml`、改启动类、重写 `QbQuestion` 三件套、删 `src/main/java/.../QbQuestionMapper.xml`。
**仍需单独点头的**：往 `users_dev` 插测试账号（Task 1.6 才用到，届时再问）。

---

### Task 1.1: 打通 `Result` —— CommonJwt 依赖、组件扫描、surefire 分组、删死文件

**Files:**
- Modify: `PracticeModule/pom.xml`
- Modify: `PracticeModule/src/main/java/org/leon/practicemodule/PracticeModuleApplication.java`
- Delete: `PracticeModule/src/main/java/org/leon/practicemodule/mapper/QbQuestionMapper.xml`
- Test: `PracticeModule/src/test/java/org/leon/practicemodule/ResultSmokeTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `org.leon.commonjwt.pojo.Result<T>` 在 PracticeModule 内可编译可注入；surefire 属性 `surefire.excludedGroups=db-it` 生效

- [ ] **Step 1: 先确认现状是坏的**

Run:
```bash
"$MVN" -pl PracticeModule -am -q compile 2>&1 | tail -20
```
Expected: 编译**成功**（因为现在没有任何代码引用 `Result`）。这一步是为了留下"改动前可编译"的基线，写进执行记录。

- [ ] **Step 2: 写失败测试（引用 `Result` 即编译失败）**

创建 `PracticeModule/src/test/java/org/leon/practicemodule/ResultSmokeTest.java`：

```java
package org.leon.practicemodule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本段第一个任务的验收锚点：PracticeModule 能否使用 CommonJwt 的统一返回体。
 * 不启动 Spring 容器——只证明依赖已进 classpath 且 Result 的码值约定成立。
 */
class ResultSmokeTest {

    @Test
    @DisplayName("success 的 code 必须是 1，前端 request.ts 以 code!==1 判错")
    void successCodeIsOne() {
        Result<String> r = Result.success("ok");
        assertEquals(1, r.getCode());
        assertEquals("ok", r.getData());
    }

    @Test
    @DisplayName("error(String) 把码写成 0，因此业务子码必须用 error(int,String)")
    void errorWithCodeKeepsSubCode() {
        Result<Void> plain = Result.error("普通失败");
        assertEquals(0, plain.getCode());

        Result<Void> sub = Result.error(40100, "用户身份不存在");
        assertEquals(40100, sub.getCode());
        assertEquals("用户身份不存在", sub.getMsg());
    }
}
```

- [ ] **Step 3: 跑测试，确认因"找不到符号"而失败**

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=ResultSmokeTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -30
```
Expected: 编译失败，报 `程序包org.leon.commonjwt.pojo不存在` 或 `找不到符号 Result`。若这一步就通过了，说明 CommonJwt 已在 classpath，跳过 Step 4 并记录偏差。

- [ ] **Step 4: 加依赖与扫描**

`PracticeModule/pom.xml` 的 `<dependencies>` 内、`lombok` 依赖之后插入（照 `UserModule/pom.xml:103-107`）：

```xml
        <!-- 统一返回体 Result 与 JwtUtil -->
        <dependency>
            <groupId>org.leon</groupId>
            <artifactId>CommonJwt</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>
```

同文件 `<properties>` 块（现在只有 `<java.version>21</java.version>`）补两件事——属性与 surefire 插件，照 `AIModule/pom.xml:32,149-155`：

```xml
    <properties>
        <java.version>21</java.version>
        <surefire.excludedGroups>db-it</surefire.excludedGroups>
    </properties>
```

```xml
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <excludedGroups>${surefire.excludedGroups}</excludedGroups>
                </configuration>
            </plugin>
        </plugins>
    </build>
```

`PracticeModuleApplication.java` 整文件替换为：

```java
package org.leon.practicemodule;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan({"org.leon.practicemodule", "org.leon.commonjwt"})
@MapperScan("org.leon.practicemodule.mapper")
public class PracticeModuleApplication {

    public static void main(String[] args) {
        SpringApplication.run(PracticeModuleApplication.class, args);
    }
}
```

> 若原文件的 `@MapperScan` import 路径与上面不同（MyBatis-Plus 3.5.17 有可能是 `com.baomidou.mybatisplus.spring.annotation` 之类），**保留原文件里那一句不动**，只加 `@ComponentScan`。改动能编译即可，别顺手换 import。

删除死文件：
```bash
git rm PracticeModule/src/main/java/org/leon/practicemodule/mapper/QbQuestionMapper.xml
```

- [ ] **Step 5: 跑测试确认通过**

Run: 同 Step 3 命令
Expected: `Tests run: 2, Failures: 0` 且 `BUILD SUCCESS`

- [ ] **Step 6: 确认 surefire 分组真的生效**

Run:
```bash
"$MVN" -pl PracticeModule -am test 2>&1 | grep -E "Tests run|BUILD"
```
Expected: 全模块单测跑完且 `BUILD SUCCESS`；后续段加的 `@Tag("db-it")` 测试不会出现在这里（Task 1.2 会验证）。

- [ ] **Step 7: 提交**

```bash
git add PracticeModule/pom.xml \
        PracticeModule/src/main/java/org/leon/practicemodule/PracticeModuleApplication.java \
        PracticeModule/src/test/java/org/leon/practicemodule/ResultSmokeTest.java
git commit -m "PracticeModule 接入统一返回体：补 CommonJwt 依赖与组件扫描，删除不可用的 mapper 死文件"
```

---

### Task 1.2: 实体与真实列对齐（修掉 `owner_id` 导致的 `Unknown column`）

**Files:**
- Modify: `PracticeModule/src/main/java/org/leon/practicemodule/pojo/QbQuestion.java`
- Create: `.../pojo/QbSubject.java` `QbQuestionOption.java` `QbUserAnswerRecord.java` `QbUserWrong.java` `QbUserFavorite.java` `QbUserSubjectStat.java`
- Create: `.../mapper/` 对应 6 个 `extends BaseMapper<T>` 接口
- Create: `.../mapper/UsersReadMapper.java`（只读，不建 users_dev 实体）
- Test: `.../src/test/java/.../pojo/QbEntityColumnTest.java`（纯单测）
- Test: `.../src/test/java/.../mapper/QbQuestionMapperDbIT.java`（`@Tag("db-it")`）

**Interfaces:**
- Consumes: Task 1.1 的 surefire 分组
- Produces: `QbQuestion`（12 列，无 `owner_id`）、`QbSubject`、`QbQuestionOption`、`QbUserAnswerRecord`、`QbUserWrong`、`QbUserFavorite`、`QbUserSubjectStat` 七个实体；`UsersReadMapper.selectIdByUsername(String):Long`

**真实列（照此写，勿凭记忆）：**

| 实体 | 表 | 列（顺序即建表顺序） |
|---|---|---|
| `QbSubject` | `qb_subject` | `id, name, owner_id, subject_key, icon, description, sort_order, status, created_at, updated_at` |
| `QbQuestion` | `qb_question` | `id, subject_id, type, title, answer_json, analysis, difficulty, tags_json, status, sort_order, created_at, updated_at` ← **无 `owner_id`** |
| `QbQuestionOption` | `qb_question_option` | `id, question_id, option_key, content, is_correct, sort_order, created_at, updated_at` |
| `QbUserAnswerRecord` | `qb_user_answer_record` | `id, user_id, subject_id, question_id, user_answer_json, is_correct, duration_ms, answer_mode, created_at` |
| `QbUserWrong` | `qb_user_wrong` | `id, user_id, subject_id, question_id, wrong_count, last_wrong_at, status, created_at, updated_at` |
| `QbUserFavorite` | `qb_user_favorite` | `id, user_id, subject_id, question_id, created_at` |
| `QbUserSubjectStat` | `qb_user_subject_stat` | `id, user_id, subject_id, total_count, correct_count, wrong_count, last_answer_at, created_at, updated_at` |

Java 字段一律 **camelCase** + `@TableField("snake_case")`，与 `map-underscore-to-camel-case: true` 一致。`json` 列用 `String` 承载（解析交给段 2 的工具类）——不配 `JacksonTypeHandler`，避免每张表都要 `autoResultMap = true`。`datetime` 用 `java.util.Date`。

- [ ] **Step 1: 写失败测试（断言实体字段与真实列一一对应）**

创建 `PracticeModule/src/test/java/org/leon/practicemodule/pojo/QbEntityColumnTest.java`：

```java
package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实体字段必须与真实列严格一致。
 * 本仓库已经因为 MyBatisX 生成的 QbQuestion 带着已被删掉的 owner_id 而整体不可用，
 * 所以用反射把"列名集合"钉死，而不是靠肉眼比对。
 */
class QbEntityColumnTest {

    private static Set<String> columnsOf(Class<?> entity) {
        Set<String> cols = new LinkedHashSet<>();
        for (Field f : entity.getDeclaredFields()) {
            TableId id = f.getAnnotation(TableId.class);
            if (id != null && !id.value().isEmpty()) {
                cols.add(id.value());
                continue;
            }
            TableField tf = f.getAnnotation(TableField.class);
            if (tf != null && !tf.value().isEmpty() && tf.exist()) {
                cols.add(tf.value());
            }
        }
        return cols;
    }

    private static void assertColumns(Class<?> entity, String... expected) {
        Set<String> actual = columnsOf(entity);
        assertEquals(Set.of(expected), actual, entity.getSimpleName() + " 的列集合不符");
        assertEquals(expected.length, actual.size(),
                entity.getSimpleName() + " 列数不符（有重复列名）: " + actual);
    }

    @Test
    @DisplayName("qb_question 没有 owner_id 列，实体也不得有")
    void qbQuestionColumns() {
        assertColumns(QbQuestion.class, "id", "subject_id", "type", "title", "answer_json",
                "analysis", "difficulty", "tags_json", "status", "sort_order",
                "created_at", "updated_at");
        assertFalse(columnsOf(QbQuestion.class).contains("owner_id"),
                "owner_id 已从 qb_question 删除，保留会让 BaseMapper 生成 Unknown column 的 SQL");
    }

    @Test
    @DisplayName("其余六张表的列集合钉死")
    void otherTables() {
        assertColumns(QbSubject.class, "id", "name", "owner_id", "subject_key", "icon",
                "description", "sort_order", "status", "created_at", "updated_at");
        assertColumns(QbQuestionOption.class, "id", "question_id", "option_key", "content",
                "is_correct", "sort_order", "created_at", "updated_at");
        assertColumns(QbUserAnswerRecord.class, "id", "user_id", "subject_id", "question_id",
                "user_answer_json", "is_correct", "duration_ms", "answer_mode", "created_at");
        assertColumns(QbUserWrong.class, "id", "user_id", "subject_id", "question_id",
                "wrong_count", "last_wrong_at", "status", "created_at", "updated_at");
        assertColumns(QbUserFavorite.class, "id", "user_id", "subject_id", "question_id", "created_at");
        assertColumns(QbUserSubjectStat.class, "id", "user_id", "subject_id", "total_count",
                "correct_count", "wrong_count", "last_answer_at", "created_at", "updated_at");
    }

    @Test
    @DisplayName("json 列一律用 String 承载，不引入 TypeHandler")
    void jsonColumnsAreString() {
        assertEquals(String.class, field("QbQuestion", "answerJson").getType());
        assertEquals(String.class, field("QbQuestion", "tagsJson").getType());
        assertEquals(String.class, field("QbUserAnswerRecord", "userAnswerJson").getType());
    }

    private static Field field(String simpleName, String fieldName) {
        try {
            Class<?> c = Class.forName("org.leon.practicemodule.pojo." + simpleName);
            Field f = c.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            throw new AssertionError(simpleName + "." + fieldName + " 不存在", e);
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=QbEntityColumnTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -30
```
Expected: 编译失败（六个新实体类还不存在）。

- [ ] **Step 3: 重写 `QbQuestion`，新建六个实体**

`QbQuestion.java` 整文件替换（其余六个同构，按上表列名照写；**不要**保留 MyBatisX 生成的 `equals`/`hashCode`/`toString` 巨块，用 `@Data` 即可）：

```java
package org.leon.practicemodule.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 题库-题目表。
 * 注意：本表没有 owner_id 列（公共/个人题库的区分只在 qb_subject 上），
 * PracticeModule 对本题库表只读。json 列以 String 承载，解析在 AnswerGrader 一侧。
 */
@Data
@TableName("qb_question")
public class QbQuestion implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("subject_id")
    private Long subjectId;

    /** 题型：1单选 2多选 3判断 4填空 5简答 */
    @TableField("type")
    private Integer type;

    @TableField("title")
    private String title;

    /** 答案 JSON：单选 "A"，多选 ["A","C"]，判断 true，填空 ["关键词"]，简答 文本 */
    @TableField("answer_json")
    private String answerJson;

    @TableField("analysis")
    private String analysis;

    /** 难度：1简单 2中等 3困难 */
    @TableField("difficulty")
    private Integer difficulty;

    @TableField("tags_json")
    private String tagsJson;

    /** 状态：1启用 0禁用 */
    @TableField("status")
    private Integer status;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}
```

- [ ] **Step 4: 七个 mapper + 只读的 `UsersReadMapper`**

六个新 mapper 与 `QbQuestionMapper` 同构，逐个长这样（换类名与实体）：

```java
package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.leon.practicemodule.pojo.QbSubject;

@Mapper
public interface QbSubjectMapper extends BaseMapper<QbSubject> {
}
```

`UsersReadMapper` 不建实体——PracticeModule 只需要 `users_dev` 的 `id`，建一个 20 字段的实体是负担：

```java
package org.leon.practicemodule.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 只读访问 users_dev。本模块不拥有用户数据，唯一需要的能力是把
 * 网关注入的 username 换成 user_id，因此不建实体、不引入更新删除。
 */
@Mapper
public interface UsersReadMapper {

    @Select("SELECT id FROM users_dev WHERE username = #{username}")
    Long selectIdByUsername(@Param("username") String username);
}
```

同时把 `QbQuestionMapper.java` 补上 `@Mapper`（保留 `extends BaseMapper<QbQuestion>` 与原注释头）。

- [ ] **Step 5: 跑单测确认通过**

Run: 同 Step 2 命令
Expected: `Tests run: 3, Failures: 0`

- [ ] **Step 6: 写一条真库集成测试，证明 `Unknown column` 已消失**

创建 `PracticeModule/src/test/java/org/leon/practicemodule/mapper/QbQuestionMapperDbIT.java`：

```java
package org.leon.practicemodule.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import org.leon.practicemodule.pojo.QbQuestion;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真库探针：证明重生成后的实体能被 BaseMapper 正常查询。
 * 改动前这条必然报 Unknown column 'owner_id'，这是 Task 1.2 唯一的硬证据。
 * 自建 SqlSession，不用 @SpringBootTest（那会去拉 Nacos/Redis/Seata）。
 *
 * 关键点：BaseMapper 需要 MyBatis-Plus 自己的 MybatisConfiguration +
 * MybatisSqlSessionFactoryBuilder。用原生 Configuration/SqlSessionFactoryBuilder
 * 会静默丢掉 MP 的 SQL 注入，selectList 直接报"方法不存在"。
 */
@Tag("db-it")
@DisplayName("qb_question 实体与真实列一致（真库）")
class QbQuestionMapperDbIT {

    private static SqlSession session;
    private static QbQuestionMapper questionMapper;
    private static UsersReadMapper usersReadMapper;

    @BeforeAll
    static void open() {
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
        cfg.addMapper(UsersReadMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession();
        questionMapper = session.getMapper(QbQuestionMapper.class);
        usersReadMapper = session.getMapper(UsersReadMapper.class);
    }

    @AfterAll
    static void close() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    @DisplayName("selectList 不再报 Unknown column 'owner_id'")
    void selectListWorks() {
        List<QbQuestion> rows = questionMapper.selectList(
                new QueryWrapper<QbQuestion>().eq("status", 1).orderByAsc("sort_order"));
        assertNotNull(rows);
        assertFalse(rows.isEmpty(), "种子数据里 qb_question 有 7 行，全空说明连错库");
        assertNotNull(rows.get(0).getAnswerJson());
        assertInstanceOf(String.class, rows.get(0).getAnswerJson(),
                "json 列必须以 String 承载，解析留给段 2 的 AnswerGrader");
    }

    @Test
    @DisplayName("users_dev 只读取 id：种子数据里的 user_id=2 能换到 username")
    void usersReadMapperWorks() {
        assertNotNull(usersReadMapper.selectIdByUsername("alice"),
                "users_dev 里应有 username=alice 的行；若为 null 请核对种子数据并写进执行记录");
        assertNull(usersReadMapper.selectIdByUsername("__no_such_user__"));
    }
}
```

> Step 6 的第二个测试依赖 `users_dev` 里存在 `username='alice'`——种子数据里 `qb_user_answer_record.user_id` 有 2 和 3，但**用户名与 id 的对应关系尚未实测**。跑之前先执行 `SELECT id,username FROM users_dev;`（用仓库外 JDBC 探针）确认真实用户名，把断言里的 `alice` 换成实际值，并把这份 id↔username 对照写进执行记录——段 2 的判分测试要用它。

- [ ] **Step 7: 跑集成测试（默认被 surefire 排除，显式开启）**

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=QbQuestionMapperDbIT \
  -Dgroups=db-it -Dsurefire.excludedGroups= \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -30
```
Expected: `Tests run: 2, Failures: 0`。若报 `Unknown column 'owner_id'`，说明 Step 3 的实体没改干净。

再确认默认跑不到它：
```bash
"$MVN" -pl PracticeModule -am test 2>&1 | grep -E "Tests run|QbQuestionMapperDbIT"
```
Expected: 输出里**没有** `QbQuestionMapperDbIT`。

- [ ] **Step 8: 提交**

```bash
git add PracticeModule/src/main/java/org/leon/practicemodule/pojo \
        PracticeModule/src/main/java/org/leon/practicemodule/mapper \
        PracticeModule/src/test/java/org/leon/practicemodule/pojo \
        PracticeModule/src/test/java/org/leon/practicemodule/mapper
git commit -m "PracticeModule 实体与真实表列对齐，修掉 qb_question 已不存在的 owner_id"
```

---

### Task 1.3: 业务异常与子码

**Files:**
- Create: `.../exception/PracticeErrorCode.java` `PracticeException.java` `PracticeExceptionHandler.java`
- Test: `.../src/test/java/.../exception/PracticeExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `org.leon.commonjwt.pojo.Result`
- Produces: `new PracticeException(PracticeErrorCode.X, msg)`、`PracticeErrorCode.X.code():int`；`PracticeExceptionHandler.toResult(PracticeException):Result<Void>`

- [ ] **Step 1: 写失败测试**

```java
package org.leon.practicemodule.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 子码是前端分支的唯一依据，且必须与 spec 5.7 的错误矩阵逐项一致。
 * 直接 new handler 调用，不起 MVC——@RestControllerAdvice 的接线由 Task 1.5 的端到端覆盖。
 */
class PracticeExceptionHandlerTest {

    private final PracticeExceptionHandler handler = new PracticeExceptionHandler();

    @Test
    @DisplayName("spec 5.7 的七个子码逐项对齐")
    void codesMatchSpec() {
        assertEquals(40000, PracticeErrorCode.BAD_REQUEST.code());
        assertEquals(40100, PracticeErrorCode.IDENTITY_MISSING.code());
        assertEquals(40300, PracticeErrorCode.FORBIDDEN.code());
        assertEquals(40400, PracticeErrorCode.NOT_FOUND.code());
        assertEquals(40900, PracticeErrorCode.CONFLICT.code());
        assertEquals(41001, PracticeErrorCode.EXAM_EXPIRED.code());
        assertEquals(41002, PracticeErrorCode.EXAM_SUBMITTED.code());
    }

    @Test
    @DisplayName("业务异常转成 HTTP 200 + Result{code:子码}，不是 HTTP 4xx")
    void practiceExceptionBecomesResult() {
        Result<Void> r = handler.handlePracticeException(
                new PracticeException(PracticeErrorCode.EXAM_EXPIRED, "考试已结束，请交卷"));
        assertEquals(41001, r.getCode());
        assertEquals("考试已结束，请交卷", r.getMsg());
        assertNull(r.getData());
    }

    @Test
    @DisplayName("未预期异常一律 0 码 + 不泄漏内部 message")
    void unexpectedExceptionIsGeneric() {
        Result<Void> r = handler.handleUnexpected(new IllegalStateException("jdbc url 内部信息"));
        assertEquals(0, r.getCode());
        assertFalse(r.getMsg().contains("jdbc"), "不得把内部异常信息透给前端");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=PracticeExceptionHandlerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```
Expected: 编译失败（三个类不存在）。

- [ ] **Step 3: 实现三个类**

```java
package org.leon.practicemodule.exception;

/**
 * 业务子码。走 Result.error(int,String)，HTTP 状态仍是 200——
 * 前端 request.ts 以 code!==1 判错，未鉴权由网关直接回 HTTP 401，不经过这里。
 */
public enum PracticeErrorCode {
    BAD_REQUEST(40000),
    IDENTITY_MISSING(40100),
    FORBIDDEN(40300),
    NOT_FOUND(40400),
    CONFLICT(40900),
    EXAM_EXPIRED(41001),
    EXAM_SUBMITTED(41002);

    private final int code;

    PracticeErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
```

```java
package org.leon.practicemodule.exception;

/**
 * 刷题子系统的业务异常。只带一句给用户看的 msg，内部细节走日志。
 */
public class PracticeException extends RuntimeException {

    private final PracticeErrorCode errorCode;

    public PracticeException(PracticeErrorCode errorCode, String msg) {
        super(msg);
        this.errorCode = errorCode;
    }

    public PracticeErrorCode errorCode() {
        return errorCode;
    }
}
```

```java
package org.leon.practicemodule.exception;

import lombok.extern.slf4j.Slf4j;
import org.leon.commonjwt.pojo.Result;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 按包作用域收口，不扩 AIModule 的 AiExceptionHandler.basePackages（它的映射有测试依赖）。
 * 一律 HTTP 200 + Result{code:子码}，与仓库现有约定一致。
 */
@Slf4j
@RestControllerAdvice(basePackages = "org.leon.practicemodule")
public class PracticeExceptionHandler {

    @ExceptionHandler(PracticeException.class)
    public Result<Void> handlePracticeException(PracticeException e) {
        log.warn("业务异常 code={} msg={}", e.errorCode().code(), e.getMessage());
        return Result.error(e.errorCode().code(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return Result.error(0, "服务器繁忙，请稍后重试");
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2 命令
Expected: `Tests run: 3, Failures: 0`

- [ ] **Step 5: 提交**

```bash
git add PracticeModule/src/main/java/org/leon/practicemodule/exception \
        PracticeModule/src/test/java/org/leon/practicemodule/exception
git commit -m "PracticeModule 业务异常与子码：按包作用域的 advice，HTTP 200 承载 Result 子码"
```

---

### Task 1.4: 身份解析 `X-User-Name` → `user_id`

**Files:**
- Create: `.../config/PracticeProperties.java`
- Create: `.../service/UserIdentityService.java` `.../service/impl/UserIdentityServiceImpl.java`
- Modify: `PracticeModule/src/main/resources/application.yaml`（只加 `practice.identity.*`）
- Test: `.../src/test/java/.../service/UserIdentityServiceTest.java`

**Interfaces:**
- Consumes: `UsersReadMapper.selectIdByUsername(String):Long`（Task 1.2）、`StringRedisTemplate`、`PracticeErrorCode.IDENTITY_MISSING`
- Produces: `Long UserIdentityService.resolveUserId(String userName)` —— 空/查不到抛 `PracticeException(IDENTITY_MISSING, ...)`；缓存 key `qb:uid:{userName}`，TTL 取 `practice.identity.cache-ttl-seconds`

- [ ] **Step 1: 写失败测试**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.practicemodule.config.PracticeProperties;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.UsersReadMapper;
import org.leon.practicemodule.service.impl.UserIdentityServiceImpl;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserIdentityServiceTest {

    @Mock UsersReadMapper usersReadMapper;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private PracticeProperties props;
    private UserIdentityServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new PracticeProperties();
        props.getIdentity().setCacheTtlSeconds(1800L);
        service = new UserIdentityServiceImpl(usersReadMapper, redis, props);
    }

    @Test
    @DisplayName("缓存命中就不查库")
    void cacheHitSkipsDb() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("qb:uid:alice")).thenReturn("2");

        assertEquals(2L, service.resolveUserId("alice"));
        verifyNoInteractions(usersReadMapper);
    }

    @Test
    @DisplayName("缓存未命中则查库并回填，TTL 取配置")
    void cacheMissLoadsAndCaches() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("qb:uid:bob")).thenReturn(null);
        when(usersReadMapper.selectIdByUsername("bob")).thenReturn(3L);

        assertEquals(3L, service.resolveUserId("bob"));
        verify(valueOps).set(eq("qb:uid:bob"), eq("3"), eq(Duration.ofSeconds(1800)));
    }

    @Test
    @DisplayName("用户名查不到 → 40100，且不写缓存（防止把不存在的人缓存住）")
    void unknownUserThrowsIdentityMissing() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(usersReadMapper.selectIdByUsername("ghost")).thenReturn(null);

        PracticeException e = assertThrows(PracticeException.class,
                () -> service.resolveUserId("ghost"));
        assertEquals(org.leon.practicemodule.exception.PracticeErrorCode.IDENTITY_MISSING,
                e.errorCode());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("空用户名直接 40100，不碰 Redis 也不查库")
    void blankUserNameThrows() {
        assertThrows(PracticeException.class, () -> service.resolveUserId(null));
        assertThrows(PracticeException.class, () -> service.resolveUserId("  "));
        verifyNoInteractions(usersReadMapper, redis);
    }

    @Test
    @DisplayName("Redis 抖动不能阻断取题：降级查库")
    void redisFailureDegradesToDb() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("connection refused"));
        when(usersReadMapper.selectIdByUsername("alice")).thenReturn(2L);

        assertEquals(2L, service.resolveUserId("alice"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=UserIdentityServiceTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```
Expected: 编译失败（`PracticeProperties` 与 `UserIdentityServiceImpl` 不存在）。

- [ ] **Step 3: 实现配置类与服务**

```java
package org.leon.practicemodule.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 绑定 application.yaml 的 practice.* 前缀。
 * 用 @ConfigurationProperties 而非一堆 @Value：拼错属性名会在启动期暴露而不是静默取默认值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "practice")
public class PracticeProperties {

    private final Identity identity = new Identity();

    @Data
    public static class Identity {
        /** qb:uid:{userName} 的存活秒数 */
        private long cacheTtlSeconds = 1800L;
    }
}
```

```java
package org.leon.practicemodule.service;

public interface UserIdentityService {

    /**
     * 把网关注入的 X-User-Name 换成分布式主键 user_id。
     *
     * @throws org.leon.practicemodule.exception.PracticeException 用户名为空或库中无此人（40100）
     */
    Long resolveUserId(String userName);
}
```

```java
package org.leon.practicemodule.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.leon.practicemodule.config.PracticeProperties;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.mapper.UsersReadMapper;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 本模块唯一的身份入口。
 *
 * 为什么不靠 token 里的 userId：网关只往下游注入 X-User-Name，且 Authorization 头被网关
 * 删掉了，下游拿不到 token。改网关注入 X-User-Id 是更彻底的方案，但会动 GatewayModule
 * 与 CommonJwt，本期把代价收在这一个类里——将来换方案只改 resolveUserId 的方法体。
 *
 * Redis 只用于这一件事（roadmap 7.2）。缓存故障时降级查库，不阻断取题。
 */
@Slf4j
@Service
public class UserIdentityServiceImpl implements UserIdentityService {

    private static final String CACHE_PREFIX = "qb:uid:";

    private final UsersReadMapper usersReadMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final PracticeProperties props;

    public UserIdentityServiceImpl(UsersReadMapper usersReadMapper,
                                   StringRedisTemplate stringRedisTemplate,
                                   PracticeProperties props) {
        this.usersReadMapper = usersReadMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.props = props;
    }

    @Override
    public Long resolveUserId(String userName) {
        if (!StringUtils.hasText(userName)) {
            throw new PracticeException(PracticeErrorCode.IDENTITY_MISSING, "用户身份不存在，请重新登录");
        }
        String key = CACHE_PREFIX + userName;
        Long cached = readCache(key, userName);
        if (cached != null) {
            return cached;
        }

        Long userId = usersReadMapper.selectIdByUsername(userName);
        if (userId == null) {
            log.warn("X-User-Name 在 users_dev 中不存在: {}", userName);
            throw new PracticeException(PracticeErrorCode.IDENTITY_MISSING, "用户身份不存在，请重新登录");
        }
        writeCache(key, userId, userName);
        return userId;
    }

    private Long readCache(String key, String userName) {
        try {
            String v = stringRedisTemplate.opsForValue().get(key);
            return v == null ? null : Long.valueOf(v);
        } catch (Exception e) {
            log.warn("身份缓存读取失败，降级查库 user={} err={}", userName, e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, Long userId, String userName) {
        try {
            stringRedisTemplate.opsForValue().set(key, String.valueOf(userId),
                    Duration.ofSeconds(props.getIdentity().getCacheTtlSeconds()));
        } catch (Exception e) {
            log.warn("身份缓存写入失败 user={} err={}", userName, e.getMessage());
        }
    }
}
```

`application.yaml` 在 `mybatis-plus:` 之前追加：

```yaml
practice:
  identity:
    cache-ttl-seconds: 1800
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 3 命令
Expected: `Tests run: 5, Failures: 0`

注意 `redisFailureDegradesToDb` 这条：它要求 `readCache` 的 try 块**包住 `opsForValue()` 本身**（不只是 `.get()`）。若实现写成 `stringRedisTemplate.opsForValue().get(key)` 之外再取模板，Mockito 的 `when(redis.opsForValue()).thenThrow(...)` 会漏出异常。

- [ ] **Step 5: 提交**

```bash
git add PracticeModule/src/main/java/org/leon/practicemodule/config \
        PracticeModule/src/main/java/org/leon/practicemodule/service \
        PracticeModule/src/main/resources/application.yaml \
        PracticeModule/src/test/java/org/leon/practicemodule/service
git commit -m "PracticeModule 身份链路：X-User-Name 查库换 user_id 并缓存，Redis 故障降级"
```

---

### Task 1.5: `PageVO` 与第一个真实接口 `GET /practice/subject/list`

**Files:**
- Create: `.../pojo/vo/PageVO.java` `.../pojo/vo/SubjectItemVO.java`
- Create: `.../service/SubjectService.java` `.../service/impl/SubjectServiceImpl.java`
- Create: `.../controller/PracticeSubjectController.java`
- Create: `.../mapper/QbSubjectMapper.java` 的统计查询（`@Select`，不建 XML）
- Test: `.../src/test/java/.../service/SubjectServiceTest.java` `.../controller/PracticeSubjectControllerTest.java`

**Interfaces:**
- Consumes: `UserIdentityService.resolveUserId`、`QbSubjectMapper`、`QbQuestionMapper`、`QbUserSubjectStatMapper`、`Result`
- Produces: `PageVO.of(List<T>, long total, long pageNum, long pageSize):PageVO<T>`；`SubjectService.listSubjects(Long userId):List<SubjectItemVO>`；`GET /practice/subject/list`

- [ ] **Step 1: 写失败测试（服务层：科目 + 我的进度 + 题目数聚合）**

```java
package org.leon.practicemodule.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserSubjectStatMapper;
import org.leon.practicemodule.mapper.StatCountRow;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.QbUserSubjectStat;
import org.leon.practicemodule.pojo.vo.SubjectItemVO;
import org.leon.practicemodule.service.impl.SubjectServiceImpl;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubjectServiceTest {

    @Mock QbSubjectMapper subjectMapper;
    @Mock QbUserSubjectStatMapper statMapper;

    private SubjectServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SubjectServiceImpl(subjectMapper, statMapper);
    }

    private QbSubject subject(long id, String name, String key) {
        QbSubject s = new QbSubject();
        s.setId(id);
        s.setName(name);
        s.setSubjectKey(key);
        s.setSortOrder((int) id);
        s.setStatus(1);
        return s;
    }

    @Test
    @DisplayName("未登录用户（userId 为 null）也返回科目，我的进度全为 0")
    void worksWithoutStat() {
        // 本接口要求鉴权，userId 由控制器传入；这里覆盖"有科目但没有任何统计"
        when(subjectMapper.selectEnabledSubjects()).thenReturn(List.of(subject(1, "语文", "chinese")));
        when(statMapper.selectByUserAndSubjects(eq(2L), anyCollection())).thenReturn(List.of());
        when(subjectMapper.countEnabledQuestionRows(anyCollection())).thenReturn(List.of());

        List<SubjectItemVO> list = service.listSubjects(2L);

        assertEquals(1, list.size());
        assertEquals(0, list.get(0).getQuestionCount());
        assertEquals(0, list.get(0).getMyTotal());
        assertEquals(0.0, list.get(0).getMyAccuracy());
    }

    @Test
    @DisplayName("正确率按 2 位小数返回，未答题时为 0 而不是 NaN")
    void accuracyRounded() {
        when(subjectMapper.selectEnabledSubjects()).thenReturn(List.of(subject(1, "语文", "chinese")));
        QbUserSubjectStat stat = new QbUserSubjectStat();
        stat.setSubjectId(1L);
        stat.setTotalCount(3);
        stat.setCorrectCount(2);
        stat.setWrongCount(1);
        stat.setLastAnswerAt(new Date(1_700_000_000_000L));
        when(statMapper.selectByUserAndSubjects(eq(2L), anyCollection())).thenReturn(List.of(stat));
        when(subjectMapper.countEnabledQuestionRows(anyCollection()))
                .thenReturn(List.of(new StatCountRow(1L, 10)));

        SubjectItemVO vo = service.listSubjects(2L).get(0);

        assertEquals(10, vo.getQuestionCount());
        assertEquals(3, vo.getMyTotal());
        assertEquals(0.67, vo.getMyAccuracy());
        assertEquals(1_700_000_000_000L, vo.getLastAnswerAt());
    }

    @Test
    @DisplayName("两次查询取聚合，不在循环里查库（N+1 守卫）")
    void noPerSubjectQuery() {
        when(subjectMapper.selectEnabledSubjects()).thenReturn(
                List.of(subject(1, "语文", "chinese"), subject(2, "数学", "math")));
        when(statMapper.selectByUserAndSubjects(anyLong(), anyCollection())).thenReturn(List.of());
        when(subjectMapper.countEnabledQuestionRows(anyCollection())).thenReturn(List.of());

        service.listSubjects(2L);

        verify(subjectMapper, times(1)).selectEnabledSubjects();
        verify(statMapper, times(1)).selectByUserAndSubjects(anyLong(), anyCollection());
        verify(subjectMapper, times(1)).countEnabledQuestionRows(anyCollection());
        verifyNoMoreInteractions(subjectMapper, statMapper);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=SubjectServiceTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```
Expected: 编译失败。

- [ ] **Step 3: 实现 VO、mapper 查询与服务**

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

import java.util.List;

/**
 * 统一分页壳。不外泄 MyBatis-Plus IPage：它的 records/size/current 语义
 * 与前端约定的 pageNum/pageSize/total/pages 不一致，且字段名会变。
 */
@Data
public class PageVO<T> {

    private long pageNum;
    private long pageSize;
    private long total;
    private long pages;
    private List<T> list;

    public static <T> PageVO<T> of(List<T> list, long total, long pageNum, long pageSize) {
        PageVO<T> vo = new PageVO<>();
        vo.setList(list == null ? List.of() : list);
        vo.setTotal(total);
        vo.setPageNum(pageNum);
        vo.setPageSize(pageSize);
        vo.setPages(pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize);
        return vo;
    }
}
```

```java
package org.leon.practicemodule.pojo.vo;

import lombok.Data;

/** 科目卡片：题库入口页的一格 */
@Data
public class SubjectItemVO {
    private Long id;
    private String name;
    private String subjectKey;
    private String icon;
    private String description;
    private long questionCount;
    private long myTotal;
    private long myCorrect;
    /** 0~1，两位小数；未答题时 0 */
    private double myAccuracy;
    /** epoch 毫秒，未答题时 null */
    private Long lastAnswerAt;
}
```

```java
package org.leon.practicemodule.mapper;

/** countEnabledQuestionRows 的投影行：subject_id + 题数 */
public record StatCountRow(Long subjectId, long cnt) {
}
```

`QbSubjectMapper` 在 `extends BaseMapper<QbSubject>` 之外加两个注解查询（不建 XML，两句话不值得一个 XML 文件）：

```java
    @Select("SELECT id, name, owner_id, subject_key, icon, description, sort_order, status "
            + "FROM qb_subject WHERE status = 1 ORDER BY sort_order ASC, id ASC")
    List<QbSubject> selectEnabledSubjects();

    @Select("<script>SELECT subject_id AS subjectId, COUNT(*) AS cnt FROM qb_question "
            + "WHERE status = 1 AND subject_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "GROUP BY subject_id</script>")
    List<StatCountRow> countEnabledQuestionRows(@Param("ids") Collection<Long> ids);
```

`QbUserSubjectStatMapper` 加：

```java
    @Select("<script>SELECT id, user_id, subject_id, total_count, correct_count, wrong_count, "
            + "last_answer_at, created_at, updated_at FROM qb_user_subject_stat "
            + "WHERE user_id = #{userId} AND subject_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<QbUserSubjectStat> selectByUserAndSubjects(@Param("userId") Long userId,
                                                    @Param("ids") Collection<Long> ids);
```

> mapper 只吐 `List<StatCountRow>`，`toMap` 是服务的职责——把聚合语义放进 mapper 会让"返回 Map 还是 List"这件事在每个查询上重新吵一次。

```java
package org.leon.practicemodule.service;

import org.leon.practicemodule.pojo.vo.SubjectItemVO;

import java.util.List;

public interface SubjectService {

    /** 启用中的科目 + 当前用户在每个科目下的进度 */
    List<SubjectItemVO> listSubjects(Long userId);
}
```

```java
package org.leon.practicemodule.service.impl;

import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserSubjectStatMapper;
import org.leon.practicemodule.mapper.StatCountRow;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.QbUserSubjectStat;
import org.leon.practicemodule.pojo.vo.SubjectItemVO;
import org.leon.practicemodule.service.SubjectService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定三次查询：科目列表、我的统计、题数聚合。
 * 绝不在科目循环里查库——题库入口页一屏十几格，N+1 在这个接口最容易复现。
 */
@Service
public class SubjectServiceImpl implements SubjectService {

    private final QbSubjectMapper subjectMapper;
    private final QbUserSubjectStatMapper statMapper;

    public SubjectServiceImpl(QbSubjectMapper subjectMapper, QbUserSubjectStatMapper statMapper) {
        this.subjectMapper = subjectMapper;
        this.statMapper = statMapper;
    }

    @Override
    public List<SubjectItemVO> listSubjects(Long userId) {
        List<QbSubject> subjects = subjectMapper.selectEnabledSubjects();
        if (subjects.isEmpty()) {
            return List.of();
        }
        List<Long> ids = subjects.stream().map(QbSubject::getId).toList();

        Map<Long, QbUserSubjectStat> statBySubject = new HashMap<>();
        for (QbUserSubjectStat s : statMapper.selectByUserAndSubjects(userId, ids)) {
            statBySubject.put(s.getSubjectId(), s);
        }

        Map<Long, Long> countBySubject = new HashMap<>();
        for (StatCountRow row : subjectMapper.countEnabledQuestionRows(ids)) {
            countBySubject.put(row.subjectId(), row.cnt());
        }

        return subjects.stream().map(s -> toVo(s, statBySubject.get(s.getId()),
                countBySubject.getOrDefault(s.getId(), 0L))).toList();
    }

    private SubjectItemVO toVo(QbSubject s, QbUserSubjectStat stat, long questionCount) {
        SubjectItemVO vo = new SubjectItemVO();
        vo.setId(s.getId());
        vo.setName(s.getName());
        vo.setSubjectKey(s.getSubjectKey());
        vo.setIcon(s.getIcon());
        vo.setDescription(s.getDescription());
        vo.setQuestionCount(questionCount);
        if (stat == null) {
            vo.setMyTotal(0);
            vo.setMyCorrect(0);
            vo.setMyAccuracy(0.0);
            vo.setLastAnswerAt(null);
            return vo;
        }
        vo.setMyTotal(stat.getTotalCount() == null ? 0 : stat.getTotalCount());
        vo.setMyCorrect(stat.getCorrectCount() == null ? 0 : stat.getCorrectCount());
        vo.setMyAccuracy(accuracy(vo.getMyCorrect(), vo.getMyTotal()));
        vo.setLastAnswerAt(stat.getLastAnswerAt() == null ? null : stat.getLastAnswerAt().getTime());
        return vo;
    }

    /** 除零守卫：total=0 时返回 0 而不是 NaN —— NaN 序列化进 JSON 会直接抛异常 */
    static double accuracy(long correct, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(correct)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
```

```java
package org.leon.practicemodule.controller;

import lombok.extern.slf4j.Slf4j;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.pojo.vo.SubjectItemVO;
import org.leon.practicemodule.service.SubjectService;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/practice/subject")
public class PracticeSubjectController {

    private final SubjectService subjectService;
    private final UserIdentityService userIdentityService;

    public PracticeSubjectController(SubjectService subjectService,
                                     UserIdentityService userIdentityService) {
        this.subjectService = subjectService;
        this.userIdentityService = userIdentityService;
    }

    /**
     * 题库入口页的科目卡片列表。
     * userId 只从 X-User-Name 换取，绝不接受请求参数——那是越权入口。
     */
    @GetMapping("/list")
    public Result<List<SubjectItemVO>> list(@RequestHeader("X-User-Name") String userName) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(subjectService.listSubjects(userId));
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2 命令
Expected: `Tests run: 3, Failures: 0`

`noPerSubjectQuery` 这条是本任务真正的价值所在——它把"不要在循环里查库"从口头约定变成会红的测试。

- [ ] **Step 5: 补控制器层测试（不启动 Spring，直接 new）**

```java
package org.leon.practicemodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.pojo.vo.SubjectItemVO;
import org.leon.practicemodule.service.SubjectService;
import org.leon.practicemodule.service.UserIdentityService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PracticeSubjectControllerTest {

    private final SubjectService subjectService = mock(SubjectService.class);
    private final UserIdentityService identity = mock(UserIdentityService.class);
    private final PracticeSubjectController controller =
            new PracticeSubjectController(subjectService, identity);

    @Test
    @DisplayName("返回 Result 包裹，code=1")
    void wrapsInResult() {
        when(identity.resolveUserId("alice")).thenReturn(2L);
        SubjectItemVO vo = new SubjectItemVO();
        vo.setId(1L);
        when(subjectService.listSubjects(2L)).thenReturn(List.of(vo));

        Result<List<SubjectItemVO>> r = controller.list("alice");

        assertEquals(1, r.getCode());
        assertEquals(1, r.getData().size());
        assertEquals(1L, r.getData().get(0).getId());
    }

    @Test
    @DisplayName("身份解析失败时异常向上抛给 advice，控制器不吞")
    void propagatesIdentityFailure() {
        when(identity.resolveUserId(eq("ghost")))
                .thenThrow(new PracticeException(PracticeErrorCode.IDENTITY_MISSING, "用户身份不存在，请重新登录"));

        PracticeException e = assertThrows(PracticeException.class, () -> controller.list("ghost"));
        assertEquals(PracticeErrorCode.IDENTITY_MISSING, e.errorCode());
        verify(subjectService, never()).listSubjects(anyLong());
    }
}
```

Run:
```bash
"$MVN" -pl PracticeModule -am test -Dtest=PracticeSubjectControllerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -20
```
Expected: `Tests run: 2, Failures: 0`

- [ ] **Step 6: 全量回归**

Run:
```bash
"$MVN" -pl PracticeModule -am test 2>&1 | grep -E "Tests run:|BUILD"
```
Expected: `BUILD SUCCESS`，本段累计单测全绿（`ResultSmokeTest` 2 + `QbEntityColumnTest` 3 + `PracticeExceptionHandlerTest` 3 + `UserIdentityServiceTest` 5 + `SubjectServiceTest` 3 + `PracticeSubjectControllerTest` 2 = 18），且不含 `db-it`。

- [ ] **Step 7: 提交**

```bash
git add PracticeModule/src/main/java/org/leon/practicemodule/pojo/vo \
        PracticeModule/src/main/java/org/leon/practicemodule/mapper \
        PracticeModule/src/main/java/org/leon/practicemodule/service \
        PracticeModule/src/main/java/org/leon/practicemodule/controller \
        PracticeModule/src/test/java/org/leon/practicemodule/service \
        PracticeModule/src/test/java/org/leon/practicemodule/controller
git commit -m "打通刷题科目列表接口：统一分页壳、三次查询聚合与身份换取"
```

---

### Task 1.6: 走网关的端到端取证（段 1 验收）

**Files:**
- Create: `docs/superpowers/plans/` 内本文件的"执行记录"小节（追加，不新建文件）
- Create: `F:\AAAJAVA\mq-installers\TokenProbe.java`（**仓库外**，一次性脚本）

**Interfaces:**
- Consumes: 全部前序任务、网关 8100、AuthModule 8103、Nacos
- Produces: 一条可复制粘贴的取 token + 打 `/practice/subject/list` 的命令序列，以及段 2–5 沿用的验收脚本模板

- [ ] **Step 1: 确认 PracticeModule 能启动并注册**

启动 PracticeModule（IDE 里跑 `PracticeModuleApplication`，或）：
```bash
"$MVN" -pl PracticeModule -am spring-boot:run 2>&1 | grep -E "Started PracticeModule|JWT Secret|nacos registry"
```
Expected: 日志出现 `Started PracticeModuleApplication`，且 `JWT 工具类初始化成功` —— 这条来自 `JwtUtil.init()`，证明 CommonJwt 的 bean 真的被扫进来了（Task 1.1 的 `@ComponentScan` 生效）。

```bash
netstat -ano | grep ":8102" | head -2
curl -s http://127.0.0.1:8102/practice/tetsgateway; echo
```
Expected: 8102 在听；直连返回 `gateway is ok.`

- [ ] **Step 2: 需要一个真实账号（关卡，此处停下问用户）**

`users_dev` 里没有已知密码的账号。两条路，**必须由用户选**：
- 用户直接给一个现有账号的用户名与明文密码；
- 用户同意后，用仓库外的 JDBC 脚本往 `users_dev` 插一条测试行，密码用 `BCryptPasswordEncoder` 现算（登录侧是 `passwordEngine.matches` 对 `password_hash`）。

不得为了让验收通过而绕过验证码或伪造 token——那等于把本段要证明的东西直接假设掉。

- [ ] **Step 3: 取 token**

```bash
CAP=$(curl -s http://127.0.0.1:8100/auth/captcha)
KEY=$(echo "$CAP" | grep -oE '"captchaKey":"[^"]+"' | cut -d'"' -f4)
# 图片字段是 imageBase64（实测），把它解码存成 png 后用眼睛读 4 位数字；
# CaptchaServiceImpl 也会把明文打进 AuthModule 日志，读日志更快。
CODE=<从日志或图片读到的 4 位数字>
RESP=$(curl -s -X POST http://127.0.0.1:8100/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}")
echo "$RESP"
TOKEN=$(echo "$RESP" | grep -oE '"token":"[^"]+"' | cut -d'"' -f4)
```
Expected: `{"code":1,...}` 且能取到 `token`。验证码 TTL 120s，取到后要快。

- [ ] **Step 4: 打第一个真实接口**

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8100/practice/subject/list; echo
```
Expected: `{"code":1,"msg":"success","data":[{"id":1,"name":"语文","subjectKey":"chinese","questionCount":4,"myTotal":...,"myAccuracy":0.67,...},{...数学...}]}`

- [ ] **Step 5: 三条负路径（比正路径更有价值）**

```bash
# a) 无 token → 网关直接 401，不经过 PracticeModule
curl -s -o /dev/null -w "no-token HTTP %{http_code}\n" http://127.0.0.1:8100/practice/subject/list

# b) 越权入口探测：把 userId 塞进查询参数，必须被忽略（服务只认 X-User-Name）
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8100/practice/subject/list?userId=3" | head -c 200; echo

# c) 身份解析失败 → HTTP 200 + code=40100（不是 HTTP 401，也不是 500）
curl -s -o /dev/null -w "x HTTP %{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8100/practice/subject/list
```
Expected: a) `HTTP 401`；b) 返回的 `myTotal` 与不带参数时**完全一致**（说明参数被忽略）；c) 正常 200。

40100 分支要单独验：临时停掉 `users_dev` 里该用户不现实，改用 Redis 里塞一个指向不存在用户的缓存项也无法（key 由 username 决定）。**本段以代码走查 + `UserIdentityServiceTest.unknownUserThrowsIdentityMissing` 覆盖它**，端到端只验 401 与 200 两条。这条限制写进执行记录，别假装端到端测过了。

- [ ] **Step 6: 确认 Redis 缓存生效**

```bash
redis-cli -n 1 KEYS "qb:uid:*"
redis-cli -n 1 GET "qb:uid:$USER"
redis-cli -n 1 TTL "qb:uid:$USER"
```
Expected: key 存在、值是登录用户的 id、TTL 在 1700~1800 之间。

- [ ] **Step 7: 写执行记录并提交**

在本文件末尾"执行记录"追加：日期、Step 1 的 `JWT 工具类初始化成功` 原文、Step 4 的完整响应 JSON、Step 5 三条的实际输出、Step 6 的 TTL 实测值、`users_dev` 的 id↔username 对照表（Task 1.2 那条注释要求你确认的），以及所有与计划的偏差。

```bash
git add docs/superpowers/plans/2026-10-23-practice-01-foundation.md
git commit -m "段1验收记录：PracticeModule 首个接口经网关打通，身份缓存与四条负路径取证"
```

---

## 段 1 完成定义

- [ ] `"$MVN" -pl PracticeModule -am test` 全绿（18 条单测），且 `db-it` 默认被排除
- [ ] `QbQuestionMapperDbIT` 显式开启时通过 → `Unknown column 'owner_id'` 已消失
- [ ] `GET /practice/subject/list` 经网关 8100 返回 `code=1`，`myAccuracy` 为两位小数
- [ ] 无 token → HTTP 401；带 token → 200；`?userId=` 被忽略
- [ ] `redis-cli -n 1 GET qb:uid:{userName}` 有值且 TTL 接近 1800
- [ ] 启动日志出现 `JWT 工具类初始化成功`（证明 `@ComponentScan` 生效）
- [ ] 执行记录已写，含所有偏差与 `40100` 只由单测覆盖这条限制
- [ ] **未动**：任何 DDL、`qb_paper`/`qb_exam_attempt`、`AnswerGrader`、AIModule 的文件

---

## 执行记录

（Task 1.6 Step 7 起在此追加）
