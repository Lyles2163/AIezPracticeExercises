# 题目语义索引实现计划（Stage 5）

> **For agentic workers:** 本文件覆盖 spec 的第 5 步。前置条件：Stage 3 的 `RetrievalService` 与 `HybridQueryBuilder` 已验收；Stage 4a 的 6 张表已建。

**Goal:** 给 `qb_question` 建立 `ai_question` 向量索引，支持全量回灌、增量打脏、单题更新与自然语言语义搜题。`QuestionIndexService` 是唯一允许读写 `qb_question` 的类（spec 2.4 的边界）。

**Architecture:** `ai_question_index_state` 存水位；打脏靠 `LEFT JOIN` 水位表与 `qb_question.updated_at` 比较，不改 PracticeModule 的表。回灌作业写 `ai_index_job` 的 `cursor_id/done_count`，可断点续跑。检索复用 `RetrievalService` 的 BM25+kNN+RRF+权限过滤，只替换索引名与 BM25 字段（`title + analysis`）。

**Tech Stack:** 复用 Stage 0–4a；新增 `QbQuestionRow` 只读投影与 `QbQuestionMapper`。

**Spec:** `docs/superpowers/specs/2026-10-12-elasticsearch-rag-design.md`（第 3.4、5.3 #10、5.4、9.1 节）

---

### Task 5.0: 核实 `qb_question` 的真实数据语义（先做，不能跳）

**Files:** 无仓库改动；执行记录

- [ ] **Step 1: 征得用户同意后执行只读查询**

```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
cat > /tmp/probe_qb.jsh <<'EOF'
import java.sql.*;
var c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/ezpracticeexercises?useSSL=false&serverTimezone=Asia/Shanghai", "root", "");
var s = c.createStatement();
var rs = s.executeQuery("SELECT owner_id, COUNT(*) FROM qb_question GROUP BY owner_id ORDER BY owner_id LIMIT 30");
System.out.println("OWNER_DIST:");
while (rs.next()) System.out.println("  owner_id=" + rs.getObject(1) + " count=" + rs.getLong(2));
rs = s.executeQuery("SELECT COUNT(*) FROM qb_question WHERE subject_id IS NULL");
while (rs.next()) System.out.println("NULL_SUBJECT=" + rs.getLong(1));
rs = s.executeQuery("SELECT COUNT(*) FROM qb_question WHERE status = 1");
while (rs.next()) System.out.println("ENABLED=" + rs.getLong(1));
rs = s.executeQuery("SELECT COUNT(*) FROM qb_question");
while (rs.next()) System.out.println("TOTAL=" + rs.getLong(1));
c.close();
/exit
EOF
"$JAVA_HOME/bin/jshell" --class-path "$HOME/.m2/repository/com/mysql/mysql-connector-j/8.0.31/mysql-connector-j-8.0.31.jar" /tmp/probe_qb.jsh
```

- [ ] **Step 2: 判定规则**

- `owner_id=1` 占比与 `QbQuestion.java:31` 注释一致（公共题库）→ 按本计划执行。
- 若 `owner_id IS NULL` 或 `=0` 才是公共题库，改 `QuestionIndexService.scopeOf()` 一处即可；把查询结果与最终判定写进执行记录。
- `subject_id IS NULL` 的行在写 ES 时统一 `IFNULL(...,0)`，**绝不回写 `qb_question`**。
- 只索引 `status=1` 的题；`status=0` 的题如果已在 ES，单题更新时从 ES 删除。

---

### Task 5.1: `ai_question` 索引与文档模型

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/question/QuestionDoc.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/question/QuestionSearchItem.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/question/QuestionSearchResponse.java`
- Modify: `AIModule/src/main/java/org/leon/aimodule/rag/index/IndexMappings.java`（`question()` 已在 Stage 1 写好，核对字段）
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/question/QuestionDocTest.java`

**Interfaces:**
- Consumes: `IndexRegistry.ensureQuestionIndex()`
- Produces:
  - `QuestionDoc`：`question_id, scope, owner_name, subject_id, title, analysis, vec, tags, difficulty, qtype`
  - `QuestionSearchItem(long questionId, int rank, double cosine, String title, String qtype, Integer difficulty)`
  - `QuestionSearchResponse(int took, boolean empty, List<QuestionSearchItem> items)`

- [ ] **Step 1: 写文档模型**

```java
package org.leon.aimodule.rag.question;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuestionDoc {
    @JsonProperty("question_id") private Long questionId;
    private String scope;
    @JsonProperty("owner_name") private String ownerName;
    @JsonProperty("subject_id") private Long subjectId;
    private String title;
    private String analysis;
    private float[] vec;
    private List<String> tags;
    private Integer difficulty;
    private String qtype;
}
```

```java
package org.leon.aimodule.rag.question;

public record QuestionSearchItem(long questionId, int rank, double cosine,
                                 String title, String qtype, Integer difficulty) {
}
```

```java
package org.leon.aimodule.rag.question;

import java.util.List;

public record QuestionSearchResponse(int took, boolean empty, List<QuestionSearchItem> items) {
    public QuestionSearchResponse {
        items = List.copyOf(items);
    }
}
```

- [ ] **Step 2: 确认 `IndexMappings.question(analyzer)`**

Stage 1 已生成：`question_id/scope/owner_name/subject_id/title/analysis/vec/tags/difficulty/qtype`，`title` 与 `analysis` 都使用同一套 IK 分析器。`tags` 是 `keyword` 数组，`qtype` 是 `keyword`。不需要改 mapping；若 Stage 1 的实现与这段不一致，先改 `IndexMappings` 再继续。

- [ ] **Step 3: 启动时确保题目索引存在**

`IndexBootstrap` 已调用 `ensureQuestionIndex()`；本阶段不新增启动逻辑。

---

### Task 5.2: 只读投影与打脏 SQL

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/question/QbQuestionRow.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/question/QbQuestionMapper.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/question/QuestionQtype.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/question/QuestionQtypeTest.java`

**Interfaces:**
- Consumes: `qb_question`、`users_dev`、`ai_question_index_state`
- Produces:
  - `QbQuestionMapper.findDirty(long cursor, int limit): List<QbQuestionRow>`
  - `QbQuestionMapper.findById(long id): QbQuestionRow`
  - `QbQuestionMapper.countEnabled(): long`
  - `QuestionQtype.fromType(Integer): String`、`QuestionQtype.toType(String): int`

- [ ] **Step 1: 写行投影（不要 import PracticeModule 的实体）**

```java
package org.leon.aimodule.rag.question;

import lombok.Data;

import java.time.LocalDateTime;

/** AIModule 侧的只读投影：只取建索引需要的列，不修改 PracticeModule 的任何类。 */
@Data
public class QbQuestionRow {
    private Long id;
    private Long subjectId;
    private Long ownerId;
    private Integer type;
    private String title;
    private String analysis;
    private Integer difficulty;
    private String tagsJson;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String ownerName;
}
```

- [ ] **Step 2: 写 mapper（唯一碰 `qb_question` 的 mapper）**

```java
package org.leon.aimodule.rag.question;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QbQuestionMapper {

    @Select("""
            SELECT q.id, q.subject_id, q.owner_id, q.type, q.title, q.analysis, q.difficulty,
                   q.tags_json, q.status, q.created_at, q.updated_at,
                   IF(q.owner_id = 1, '', u.username) AS owner_name
              FROM qb_question q
              LEFT JOIN users_dev u ON u.id = q.owner_id
             WHERE q.id = #{id}
            """)
    QbQuestionRow findById(@Param("id") long id);

    /**
     * 打脏：水位表没有记录，或题目更新时间晚于水位时间。
     * 只取 status=1；游标按 id 升序，便于断点续跑。
     */
    @Select("""
            SELECT q.id, q.subject_id, q.owner_id, q.type, q.title, q.analysis, q.difficulty,
                   q.tags_json, q.status, q.created_at, q.updated_at,
                   IF(q.owner_id = 1, '', u.username) AS owner_name
              FROM qb_question q
              LEFT JOIN ai_question_index_state s ON s.question_id = q.id
              LEFT JOIN users_dev u ON u.id = q.owner_id
             WHERE q.status = 1
               AND q.id > #{cursor}
               AND (s.question_id IS NULL OR q.updated_at > s.indexed_time)
             ORDER BY q.id
             LIMIT #{limit}
            """)
    List<QbQuestionRow> findDirty(@Param("cursor") long cursor, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM qb_question WHERE status = 1")
    long countEnabled();
}
```

- [ ] **Step 3: 写题型映射**

```java
package org.leon.aimodule.rag.question;

public final class QuestionQtype {

    private QuestionQtype() {
    }

    /** qb_question.type：1单选 2多选 3判断 4填空 5简答 */
    public static String fromType(Integer type) {
        if (type == null) {
            return "short";
        }
        return switch (type) {
            case 1 -> "single";
            case 2 -> "multi";
            case 3 -> "judge";
            case 4 -> "blank";
            case 5 -> "short";
            default -> "short";
        };
    }

    public static int toType(String qtype) {
        if (qtype == null) {
            return 5;
        }
        return switch (qtype) {
            case "single" -> 1;
            case "multi" -> 2;
            case "judge" -> 3;
            case "blank" -> 4;
            case "short" -> 5;
            default -> 5;
        };
    }
}
```

- [ ] **Step 4: 单测**

```java
package org.leon.aimodule.rag.question;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuestionQtypeTest {

    @Test
    @DisplayName("题型双向映射")
    void mapsBothWays() {
        assertEquals("single", QuestionQtype.fromType(1));
        assertEquals("multi", QuestionQtype.fromType(2));
        assertEquals("judge", QuestionQtype.fromType(3));
        assertEquals("blank", QuestionQtype.fromType(4));
        assertEquals("short", QuestionQtype.fromType(5));
        assertEquals(1, QuestionQtype.toType("single"));
        assertEquals(5, QuestionQtype.toType(null));
        assertEquals(5, QuestionQtype.toType("unknown"));
    }
}
```

---

### Task 5.3: `QuestionIndexService`（索引/单题/回灌/检索）

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/question/QuestionIndexService.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/question/QuestionIndexStateMapper.java`
- Modify: `AIModule/src/main/java/org/leon/aimodule/rag/index/HybridQueryBuilder.java`（BM25 字段参数化）
- Modify: `AIModule/src/main/java/org/leon/aimodule/rag/search/RetrievalService.java`（增加 `searchQuestions`）
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/question/QuestionIndexServiceTest.java`

**Interfaces:**
- Consumes: `QbQuestionMapper`、`QuestionIndexStateMapper`、`EmbeddingGateway`、`ElasticsearchClient`、`IndexRegistry`、`RetrievalService`
- Produces:
  - `QuestionIndexService.indexQuestion(long questionId): void`（幂等 upsert；status != 1 时从 ES 删除）
  - `QuestionIndexService.backfill(long jobId): void`（可断点续跑）
  - `QuestionIndexService.createFromDraft(AiExtractDraft draft, long ownerId): long`（阶段 7 用，唯一写 `qb_question` 的入口）
  - `RetrievalService.searchQuestions(String query, SearchOptions options): QuestionSearchResponse`

- [ ] **Step 1: `QuestionIndexStateMapper`**

```java
package org.leon.aimodule.rag.question;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuestionIndexStateMapper {

    @Insert("""
            INSERT INTO ai_question_index_state(question_id, indexed_time)
            VALUES (#{questionId}, NOW(3))
            ON DUPLICATE KEY UPDATE indexed_time = NOW(3)
            """)
    int upsert(@Param("questionId") long questionId);

    @Select("SELECT COUNT(*) FROM ai_question_index_state")
    long count();
}
```

- [ ] **Step 2: 参数化 BM25 字段**

在 `HybridQueryBuilder` 增加：

```java
public static final List<String> KB_FIELDS = List.of("title^2", "content");
public static final List<String> QUESTION_FIELDS = List.of("title^2", "analysis");

public Query bm25Query(String queryText, List<Query> filters) {
    return bm25Query(queryText, filters, KB_FIELDS);
}

public Query bm25Query(String queryText, List<Query> filters, List<String> fields) {
    return Query.of(q -> q.bool(b -> b
            .must(m -> m.multiMatch(mm -> mm.query(queryText).fields(fields)))
            .filter(filters)));
}

public SearchRequest build(String index, String queryText, float[] vector,
                           List<Query> filters, SearchOptions options,
                           RagProperties.Search search, List<String> bm25Fields) { ... }
```

原有 `build(...)` 重载委托给新重载并传 `KB_FIELDS`，保证 Stage 3 的行为不变。

- [ ] **Step 3: 写 `RetrievalService.searchQuestions`**

```java
public QuestionSearchResponse searchQuestions(String query, SearchOptions options) throws IOException {
    float[] queryVector = embedQuery(query);
    List<Query> filters = PermissionFilters.build(
            options.scopes(), options.ownerName(), options.subjectId());
    SearchRequest request = queryBuilder.build(
            props.getIndex().getQuestionAlias(), query, queryVector, filters, options,
            props.getSearch(), HybridQueryBuilder.QUESTION_FIELDS);
    SearchResponse<QuestionDoc> response = client.search(request, QuestionDoc.class);

    List<QuestionSearchItem> items = new ArrayList<>();
    List<Hit<QuestionDoc>> hits = response.hits().hits();
    for (int i = 0; i < hits.size(); i++) {
        Hit<QuestionDoc> hit = hits.get(i);
        QuestionDoc doc = hit.source();
        if (doc == null) {
            continue;
        }
        items.add(new QuestionSearchItem(doc.getQuestionId(),
                hit.rank() != null ? hit.rank() : i + 1,
                VectorMath.cosine(queryVector, doc.getVec()),
                doc.getTitle(), doc.getQtype(), doc.getDifficulty()));
    }
    return new QuestionSearchResponse(response.took(), items.isEmpty(), items);
}
```

`QuestionDoc.vec` 必须被 `_source` 返回；默认 `_source` 包含全部字段，保持现状。

- [ ] **Step 4: 写 `QuestionIndexService`**

核心代码（完整实现时保留这些分支）：

```java
@Service
public class QuestionIndexService {

    private final QbQuestionMapper questionMapper;
    private final QuestionIndexStateMapper stateMapper;
    private final QbQuestionInsertMapper insertMapper;
    private final EmbeddingGateway embeddingGateway;
    private final ElasticsearchClient client;
    private final IndexRegistry indexRegistry;
    private final RagProperties props;
    private final ObjectMapper objectMapper;

    public void indexQuestion(long questionId) throws IOException {
        QbQuestionRow row = questionMapper.findById(questionId);
        if (row == null || row.getStatus() == null || row.getStatus() != 1) {
            client.delete(d -> d.index(props.getIndex().getQuestionAlias())
                    .id(String.valueOf(questionId)));
            return;
        }
        QuestionDoc doc = toDoc(row);
        float[] vector = embeddingGateway.embed(List.of(embeddingText(doc))).get(0);
        doc.setVec(vector);
        client.index(i -> i.index(props.getIndex().getQuestionAlias())
                .id(String.valueOf(questionId)).document(doc));
        stateMapper.upsert(questionId);
        indexRegistry.refresh(props.getIndex().getQuestionAlias());
    }

    public void backfill(long jobId) {
        // 从 ai_index_job.cursor_id 开始，每页 100；逐条 indexQuestion；
        // 每条成功 done_count+1、cursor_id=id；失败 failed_count+1 并继续；
        // 全部完成置 succeeded + finish_time
    }

    private QuestionDoc toDoc(QbQuestionRow row) {
        QuestionDoc doc = new QuestionDoc();
        doc.setQuestionId(row.getId());
        doc.setScope(row.getOwnerId() != null && row.getOwnerId() == 1 ? "official" : "personal");
        doc.setOwnerName(row.getOwnerName() == null ? "" : row.getOwnerName());
        doc.setSubjectId(row.getSubjectId() == null ? 0L : row.getSubjectId());
        doc.setTitle(row.getTitle() == null ? "" : row.getTitle());
        doc.setAnalysis(row.getAnalysis() == null ? "" : row.getAnalysis());
        doc.setTags(parseTags(row.getTagsJson()));
        doc.setDifficulty(row.getDifficulty());
        doc.setQtype(QuestionQtype.fromType(row.getType()));
        return doc;
    }

    private String embeddingText(QuestionDoc doc) {
        String text = doc.getTitle() + "\n" + doc.getAnalysis();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
```

`createFromDraft` 的 SQL 必须用 `QbQuestionInsertMapper`：

```java
@Mapper
public interface QbQuestionInsertMapper {

    @Insert("""
            INSERT INTO qb_question
              (subject_id, owner_id, type, title, answer_json, analysis, difficulty,
               tags_json, status, sort_order, created_at, updated_at)
            VALUES
              (#{subjectId}, #{ownerId}, #{type}, #{title}, #{answerJson}, #{analysis},
               #{difficulty}, #{tagsJson}, 1, 0, NOW(3), NOW(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(QbQuestionInsert row);
}
```

`QbQuestionInsert` 的 `answerJson/tagsJson` 用 `String`（合法 JSON 文本），MySQL JSON 列接受字符串写入。

```java
@Transactional
public long createFromDraft(AiExtractDraft draft, long ownerId) {
    QbQuestionInsert row = new QbQuestionInsert();
    row.setSubjectId(draft.getSubjectId() == null ? 0L : draft.getSubjectId());
    row.setOwnerId(ownerId);
    row.setType(QuestionQtype.toType(draft.getQtype()));
    row.setTitle(draft.getTitle());
    row.setAnswerJson(draft.getAnswerJson());
    row.setAnalysis(draft.getAnalysis());
    row.setDifficulty(draft.getDifficulty());
    row.setTagsJson(draft.getTagsJson());
    insertMapper.insert(row);
    return row.getId();
}
```

- [ ] **Step 5: 单测映射**

```java
class QuestionIndexServiceTest {

    @Test
    @DisplayName("owner_id=1 映射为 official 且 owner_name 为空")
    void officialMapping() {
        QbQuestionRow row = new QbQuestionRow();
        row.setId(9L); row.setOwnerId(1L); row.setOwnerName(""); row.setSubjectId(null);
        // 通过反射或包级方法调用 toDoc；实现时把 toDoc 设为 package-private 以便测试
        QuestionDoc doc = service.toDoc(row);
        assertEquals("official", doc.getScope());
        assertEquals("", doc.getOwnerName());
        assertEquals(0L, doc.getSubjectId());
    }

    @Test
    @DisplayName("个人题映射为 personal 并带 username")
    void personalMapping() {
        QbQuestionRow row = new QbQuestionRow();
        row.setId(10L); row.setOwnerId(7L); row.setOwnerName("alice"); row.setSubjectId(12L);
        QuestionDoc doc = service.toDoc(row);
        assertEquals("personal", doc.getScope());
        assertEquals("alice", doc.getOwnerName());
    }
}
```

- [ ] **Step 6: 编译与单测**

```bash
"$MVN" -pl AIModule -am test -Dtest=QuestionQtypeTest,QuestionIndexServiceTest \
  -Dsurefire.failIfNoTests=true -Dsurefire.failIfNoSpecifiedTests=false
```

---

### Task 5.4: 索引作业与对账接口 #11–#14

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/indexjob/IndexJobService.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/indexjob/IndexJobController.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/indexjob/IndexStatusResponse.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/indexjob/IndexJobServiceTest.java`

**Interfaces:**
- Consumes: `AiIndexJobMapper`、`QuestionIndexService`、`IndexRegistry`、`AiKbChunkMapper`
- Produces:
  - `POST /ai/admin/index/jobs` `{indexName, kind}` → `{jobId}`
  - `GET /ai/admin/index/jobs/{id}` → `{targetTotal,doneCount,failedCount,cursorId,status}`
  - `POST /ai/admin/index/aliases` `{alias, physical}` → 原子切别名
  - `GET /ai/admin/index/status` → `{kb:{mysql,es,diff}, question:{mysql,es,diff}}`

- [ ] **Step 1: 作业类型**

本阶段支持 `kind=backfill_question`。`kind=reindex_kb` 在后续需要时实现：从 `ai_kb_chunk` + `ai_kb_doc` 重建 `ai_kb_chunk_vN`，复用 `KbChunkDoc` 与 `EmbeddingGateway`，不重新解析文件。`single_question` 不是批量作业，直接调 `QuestionIndexService.indexQuestion`。

- [ ] **Step 2: 作业执行**

```java
public long start(String kind, String indexName, String operator) {
    if (!"backfill_question".equals(kind)) {
        throw new RagException("当前只支持 backfill_question");
    }
    AiIndexJob job = new AiIndexJob();
    job.setIndexName(indexName == null ? props.getIndex().getQuestionAlias() : indexName);
    job.setKind(kind);
    job.setTargetTotal(questionMapper.countEnabled());
    job.setStatus("running");
    job.setOperatorName(operator == null ? "" : operator);
    jobMapper.insert(job);
    executor.execute(() -> runBackfill(job.getId()));
    return job.getId();
}

private void runBackfill(long jobId) {
    AiIndexJob job = jobMapper.selectById(jobId);
    long cursor = job.getCursorId() == null ? 0 : job.getCursorId();
    int done = job.getDoneCount() == null ? 0 : job.getDoneCount();
    int failed = job.getFailedCount() == null ? 0 : job.getFailedCount();
    try {
        while (true) {
            List<QbQuestionRow> rows = questionMapper.findDirty(cursor, 100);
            if (rows.isEmpty()) {
                break;
            }
            for (QbQuestionRow row : rows) {
                try {
                    questionIndexService.indexQuestion(row.getId());
                    done++;
                } catch (Exception e) {
                    failed++;
                    log.warn("[RAG] 题目 {} 建索引失败: {}", row.getId(), e.getMessage());
                }
                cursor = row.getId();
            }
            // 每页更新断点，崩溃后从这里续跑
            AiIndexJob update = new AiIndexJob();
            update.setId(jobId);
            update.setCursorId(cursor);
            update.setDoneCount(done);
            update.setFailedCount(failed);
            jobMapper.updateById(update);
        }
        AiIndexJob finish = new AiIndexJob();
        finish.setId(jobId);
        finish.setStatus("succeeded");
        finish.setFinishTime(LocalDateTime.now());
        jobMapper.updateById(finish);
    } catch (Exception e) {
        AiIndexJob fail = new AiIndexJob();
        fail.setId(jobId);
        fail.setStatus("failed");
        fail.setErrMsg(truncate(e.getMessage(), 500));
        fail.setFinishTime(LocalDateTime.now());
        jobMapper.updateById(fail);
    }
}
```

`IndexJobController` 四个方法全部先 `adminGuard.requireAdmin(user)`。

- [ ] **Step 3: 对账**

```java
public IndexStatusResponse status() throws IOException {
    long kbMysql = chunkMapper.countAllActive();
    long kbEs = client.count(c -> c.index(props.getIndex().getKbAlias())).count();
    long qMysql = questionMapper.countEnabled();
    long qEs = client.count(c -> c.index(props.getIndex().getQuestionAlias())).count();
    return new IndexStatusResponse(
            new IndexStatusResponse.Comparison(kbMysql, kbEs, kbEs - kbMysql),
            new IndexStatusResponse.Comparison(qMysql, qEs, qEs - qMysql));
}
```

`countAllActive` 的 SQL：`SELECT COUNT(*) FROM ai_kb_chunk c JOIN ai_kb_doc d ON d.id=c.doc_id WHERE d.status <> 'deleted'`，加到 `AiKbChunkMapper`。

- [ ] **Step 4: 单测**

`IndexJobServiceTest` 用 mock mapper 断言：`start` 写入 running 作业；`runBackfill` 遇到空页置 `succeeded`；单题失败只增加 `failed_count` 不中断。

---

### Task 5.5: 语义搜题接口 #10 与验收

**Files:**
- Modify: `AIModule/src/main/java/org/leon/aimodule/rag/web/KbSearchController.java`（或新建 `QuestionSearchController`）
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/question/QuestionSearchControllerTest.java`

**Interfaces:**
- `POST /ai/questions/search` 请求体与 `/ai/kb/search` 同构，响应 `QuestionSearchResponse`

- [ ] **Step 1: 加接口**

```java
@PostMapping("/questions/search")
public Result<QuestionSearchResponse> searchQuestions(
        @RequestHeader("X-User-Name") String userName,
        @Valid @RequestBody RetrievalQuery query) throws IOException {
    return Result.success(retrievalService.searchQuestions(
            query.getQuery(), options(userName, query)));
}
```

权限 filter 与知识库完全一致：官方题 `owner_name=''`，个人题 `owner_name=当前用户`；`subject_id=0` 表示全学科通用。

- [ ] **Step 2: 集成测试（`es-it` + `db-it`）**

1. 用本地 MySQL 插入 3 道 `status=1` 的题（owner_id=1 官方、owner_id=alice 的个人、owner_id=bob 的个人）。
2. 用 `QuestionIndexService.indexQuestion` 逐题写入 `test_ai_question_v1`（测试里把 `RagProperties.Index.questionAlias` 指向测试别名）。
3. 用自然语言“快排的最坏时间复杂度”检索：
   - alice 能看到官方题和自己的题，看不到 bob 的题。
   - 返回的 `questionId`、`qtype`、`difficulty` 正确。
4. 把某题 `status` 改成 0 后再次 `indexQuestion`，断言 ES 里该题不存在。
5. `ai_question_index_state` 有对应水位行。

- [ ] **Step 3: 回灌验收**

```bash
curl -s -X POST http://127.0.0.1:8105/ai/admin/index/jobs \
  -H 'X-User-Name: admin' -H 'Content-Type: application/json' \
  -d '{"indexName":"ai_question","kind":"backfill_question"}'
# 轮询
curl -s http://127.0.0.1:8105/ai/admin/index/jobs/1 -H 'X-User-Name: admin'
# 对账
curl -s http://127.0.0.1:8105/ai/admin/index/status -H 'X-User-Name: admin'
```

验收标准：作业 `succeeded`、`doneCount == targetTotal`、对账 `diff=0`；再用自然语言搜到至少一道题。

- [ ] **Step 4: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/rag/question \
        AIModule/src/main/java/org/leon/aimodule/rag/indexjob \
        AIModule/src/main/java/org/leon/aimodule/rag/search \
        AIModule/src/main/java/org/leon/aimodule/rag/web \
        AIModule/src/test/java/org/leon/aimodule/rag
git commit -m "新增题目语义索引：水位打脏、可续跑回灌与语义搜题接口"
```

---

## 题目索引验收清单（Stage 5）

- [ ] `qb_question` 数据分布已核实，`owner_id=1` 语义与代码一致（或已按实际调整 `scopeOf`）。
- [ ] 全量回灌完成，`ai_question_index_state` 行数与 `status=1` 题目数一致。
- [ ] 对账接口 `diff=0`。
- [ ] 自然语言描述能搜到相似题；bob 的私人题不出现在 alice 的结果里。
- [ ] 题目 `status=0` 后从 ES 删除；`subject_id IS NULL` 写成 0。

## 执行记录

（执行时追加：owner_id 分布、null subject 数、回灌题数、Recall 样例、对账结果。）
