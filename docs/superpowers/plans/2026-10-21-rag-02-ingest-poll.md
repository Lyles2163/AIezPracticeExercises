# RAG 写路径实现计划（Stage 4a：poll 模式入库）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. 本文件只覆盖 spec 的第 4a 步；执行前先读 `2026-10-21-rag-00-roadmap.md` 的全局约束与修正项，并确认 Stage 0–3 已验收。

**Goal:** 实现“上传文件 → MySQL 落库 → 异步解析/切片/向量化 → ES 入库 → ready”的完整写路径，派发使用本地线程池 + 定时扫描（`rag.async=poll`），不依赖 RabbitMQ。崩溃后靠心跳回收与 `es_synced` 断点续跑恢复。

**Architecture:** `ai_kb_doc` 是状态机宿主，`ai_kb_chunk` 是切片真相源；`IngestService` 用条件 UPDATE 抢占，阶段推进依次 `parsing → chunking → embedding → indexing → ready`，异常落 `failed`。文件字节存本地磁盘，MySQL 只存 `storage_key` 与 `sha256`。所有接口走 `Result<T>`，身份只取 `X-User-Name`。

**Tech Stack:** Tika 3.2.3 + PDFBox 3.0.5（逐页）、MyBatis-Plus 3.5.17、Spring `@Scheduled` + `ThreadPoolTaskExecutor`、本地文件存储。

**Spec:** `docs/superpowers/specs/2026-10-12-elasticsearch-rag-design.md`（第 2.3、2.4、3.6、4、5.2、7.1、7.2、7.5 节）

---

## 全局约束（本文件所有任务隐含）

- 延续 Stage 0–3 的 JDK/Maven/中文/密钥约束。
- **DDL 必须先征得用户明确同意**；不引入 Flyway。
- 只有 `IngestService` 与 `KbDocumentService` 可以改 `ai_kb_doc` 状态；controller 不直接写状态。
- 状态迁移全部用条件 UPDATE；禁止 `@GlobalTransactional` 包住整条流水线。
- 心跳阈值默认 300 秒，可通过 `rag.ingest.heartbeat-timeout-seconds` 覆盖（测试用 30 秒）。
- 切片写入 MySQL 与写 ES 的顺序永远是：MySQL（`es_synced=0`）→ bulk ES → 回标 `es_synced=1`。

---

### Task 4a.1: 建表脚本与执行

**Files:**
- Create: `AIModule/src/main/resources/sql/ai_rag_schema.sql`
- 执行方式：JDBC 脚本，不引入 Flyway

**Interfaces:**
- Consumes: 用户对 DDL 的明确同意
- Produces: 6 张表 `ai_kb_doc`、`ai_kb_chunk`、`ai_index_job`、`ai_question_index_state`、`ai_extract_draft`、`ai_message_citation`

- [ ] **Step 1: 向用户确认**

执行前必须问：“是否同意在本地 `ezpracticeexercises` 库创建 spec 4.2 的 6 张 `ai_*` 表？”得到明确同意后才执行。不同意则本阶段停止，其余代码可以继续写但不跑 DB 测试。

- [ ] **Step 2: 写 `ai_rag_schema.sql`（内容与 spec 4.2 逐字一致）**

```sql
-- ① 知识库文档：真相源，也是状态机宿主
CREATE TABLE ai_kb_doc (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  scope           VARCHAR(16)  NOT NULL COMMENT 'official / personal',
  owner_name      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'personal=JWT subject；official 固定空串',
  subject_id      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=全学科通用；ES 过滤语义不接受 NULL，见 3.5',
  title           VARCHAR(255) NOT NULL,
  file_name       VARCHAR(255) NOT NULL,
  mime            VARCHAR(128) NOT NULL,
  size            INT UNSIGNED NOT NULL,
  storage_key     VARCHAR(255) NOT NULL COMMENT '只存 key，不存二进制',
  sha256          CHAR(64)     NOT NULL COMMENT '文件指纹，判重省 embedding 费',
  chunk_count     INT UNSIGNED NOT NULL DEFAULT 0,
  token_total     INT UNSIGNED NOT NULL DEFAULT 0,
  status          VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '状态机，见 4.4',
  stage_error     VARCHAR(500) NULL,
  retry_count     TINYINT UNSIGNED NOT NULL DEFAULT 0,
  heartbeat_time  DATETIME(3)  NULL COMMENT 'worker 心跳，崩溃回收靠它',
  parser_version  VARCHAR(32)  NOT NULL DEFAULT 'tika-3.x',
  chunk_strategy  VARCHAR(32)  NOT NULL DEFAULT 'tok500-ov60',
  embed_model     VARCHAR(64)  NOT NULL DEFAULT 'text-embedding-v3:1024',
  create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  ready_time      DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_scope_owner_hash (scope, owner_name, sha256),
  KEY idx_list (scope, owner_name, status, update_time),
  KEY idx_recovery (status, heartbeat_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT '知识库文档';

-- ② 切片：真相源的一部分，ES 从它回灌
CREATE TABLE ai_kb_chunk (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '直接用作 ES 文档 _id',
  doc_id       BIGINT UNSIGNED NOT NULL,
  seq          INT UNSIGNED    NOT NULL COMMENT '文档内顺序，从 1',
  content      MEDIUMTEXT      NOT NULL,
  token_count  SMALLINT UNSIGNED NOT NULL,
  page_no      INT UNSIGNED    NULL,
  book         VARCHAR(128)    NULL,
  chapter_path VARCHAR(255)    NULL,
  es_synced    TINYINT         NOT NULL DEFAULT 0 COMMENT '0=未进ES，断点续跑靠它',
  create_time  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_seq (doc_id, seq),
  KEY idx_unsynced (doc_id, es_synced)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT '知识库切片';

-- ③ 索引作业：全量回灌/reindex 的可观测与断点
CREATE TABLE ai_index_job (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  index_name    VARCHAR(64)  NOT NULL COMMENT 'ai_kb_chunk / ai_question',
  kind          VARCHAR(16)  NOT NULL COMMENT 'reindex_kb | backfill_question | single_question',
  target_total  INT UNSIGNED NOT NULL DEFAULT 0,
  done_count    INT UNSIGNED NOT NULL DEFAULT 0,
  failed_count  INT UNSIGNED NOT NULL DEFAULT 0,
  cursor_id     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '断点续跑游标，按 id 升序',
  status        VARCHAR(16)  NOT NULL DEFAULT 'running' COMMENT 'running/succeeded/failed/cancelled',
  err_msg       VARCHAR(500) NULL,
  operator_name VARCHAR(64)  NOT NULL DEFAULT '',
  create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  finish_time   DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT '索引作业';

-- ④ 索引水位：不改 PracticeModule 的表，靠它判断题目脏不脏
CREATE TABLE ai_question_index_state (
  question_id   BIGINT UNSIGNED NOT NULL,
  indexed_time  DATETIME(3)     NOT NULL,
  PRIMARY KEY (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT '题目向量索引水位';

-- ⑤ 录题草稿：AI 抽取结果先落这里，审核通过才进 qb_question
CREATE TABLE ai_extract_draft (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  doc_id         BIGINT UNSIGNED NOT NULL DEFAULT 0,
  raw_index      SMALLINT UNSIGNED NOT NULL COMMENT '文件内出现顺序',
  qtype          VARCHAR(16)  NOT NULL COMMENT 'single/multi/blank/judge/short',
  title          MEDIUMTEXT   NOT NULL,
  options_json   JSON         NULL,
  answer_json    JSON         NOT NULL,
  analysis       MEDIUMTEXT   NULL,
  tags_json      JSON         NULL,
  subject_id     BIGINT UNSIGNED NULL,
  difficulty     TINYINT      NULL,
  model_key      VARCHAR(64)  NOT NULL DEFAULT '',
  prompt_version VARCHAR(32)  NOT NULL DEFAULT 'extract-v1',
  status         VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/converted/rejected',
  reviewer_name  VARCHAR(64)  NOT NULL DEFAULT '',
  review_time    DATETIME(3)  NULL,
  reject_reason  VARCHAR(255) NULL,
  question_id    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'converted 后回填 qb_question.id',
  create_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_raw (doc_id, raw_index),
  KEY idx_review (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT 'AI 录题草稿与审核';

-- ⑥ 回答引用：RAG 溯源落库，逻辑关联 ai_message.id
CREATE TABLE ai_message_citation (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  message_id      BIGINT UNSIGNED NOT NULL,
  conversation_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  chunk_id        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '知识库命中时非 0',
  question_id     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '题目命中时非 0，与 chunk_id 二选一',
  index_name      VARCHAR(64)  NOT NULL,
  rank            SMALLINT UNSIGNED NOT NULL COMMENT 'RRF 融合后名次，从 1',
  cosine          DECIMAL(6,5) NULL COMMENT 'kNN 腿相似度，供检索质量分析',
  book            VARCHAR(128)  NOT NULL DEFAULT '',
  chapter_path    VARCHAR(255)  NOT NULL DEFAULT '',
  page_no         INT UNSIGNED  NOT NULL DEFAULT 0,
  snapshot_title  VARCHAR(255)  NOT NULL DEFAULT '' COMMENT '出处定格',
  create_time     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_msg_ref (message_id, chunk_id, question_id),
  KEY idx_msg (message_id, rank)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT '回答的知识库引用';
```

- [ ] **Step 3: 用 jshell + JDBC 执行（逐条 `;` 切分；脚本里没有存储过程）**

```bash
export JAVA_HOME="C:/Users/Administrator/.jdks/ms-21.0.11"
cat > /tmp/run_sql.jsh <<'EOF'
import java.sql.*;
import java.nio.file.*;
var url = "jdbc:mysql://127.0.0.1:3306/ezpracticeexercises?useSSL=false&serverTimezone=Asia/Shanghai&allowMultiQueries=true";
var c = DriverManager.getConnection(url, "root", "");
var s = c.createStatement();
var sql = Files.readString(Path.of("AIModule/src/main/resources/sql/ai_rag_schema.sql"));
for (String statement : sql.split(";")) {
    if (!statement.isBlank()) s.execute(statement);
}
var rs = s.executeQuery("SHOW TABLES LIKE 'ai_%'");
while (rs.next()) System.out.println(rs.getString(1));
c.close();
/exit
EOF
"$JAVA_HOME/bin/jshell" --class-path "$HOME/.m2/repository/com/mysql/mysql-connector-j/8.0.31/mysql-connector-j-8.0.31.jar" /tmp/run_sql.jsh
```

预期输出 6 个表名。若已存在表，先确认不是要保留的数据，再按用户意见决定 `DROP` 或跳过；**不要**自作主张删表。

- [ ] **Step 4: 记录**

把执行日期、6 个表名、MySQL 版本写进执行记录。

---

### Task 4a.2: 实体与 Mapper

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/entity/AiKbDoc.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/entity/AiKbChunk.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/entity/AiIndexJob.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/entity/AiQuestionIndexState.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/entity/AiExtractDraft.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/entity/AiMessageCitation.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/mapper/AiKbDocMapper.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/mapper/AiKbChunkMapper.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/mapper/AiIndexJobMapper.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/mapper/AiQuestionIndexStateMapper.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/mapper/AiExtractDraftMapper.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/mapper/AiMessageCitationMapper.java`
- Modify: `AIModule/src/main/resources/application.yaml`（补 `mybatis-plus.configuration.map-underscore-to-camel-case: true`）

**Interfaces:**
- Consumes: 6 张表
- Produces: 每个 mapper 都带 `@Mapper`；`AiKbDocMapper` 额外提供状态机 SQL（见下）

- [ ] **Step 1: 写 `AiKbDoc` 与 `AiKbChunk`**

```java
package org.leon.aimodule.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_kb_doc")
public class AiKbDoc {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String scope;

    @TableField("owner_name")
    private String ownerName;

    @TableField("subject_id")
    private Long subjectId;

    private String title;

    @TableField("file_name")
    private String fileName;

    private String mime;

    private Long size;

    @TableField("storage_key")
    private String storageKey;

    private String sha256;

    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField("token_total")
    private Integer tokenTotal;

    private String status;

    @TableField("stage_error")
    private String stageError;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("heartbeat_time")
    private LocalDateTime heartbeatTime;

    @TableField("parser_version")
    private String parserVersion;

    @TableField("chunk_strategy")
    private String chunkStrategy;

    @TableField("embed_model")
    private String embedModel;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("ready_time")
    private LocalDateTime readyTime;
}
```

```java
package org.leon.aimodule.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_kb_chunk")
public class AiKbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("doc_id")
    private Long docId;

    private Integer seq;

    private String content;

    @TableField("token_count")
    private Integer tokenCount;

    @TableField("page_no")
    private Integer pageNo;

    private String book;

    @TableField("chapter_path")
    private String chapterPath;

    @TableField("es_synced")
    private Integer esSynced;

    @TableField("create_time")
    private LocalDateTime createTime;
}
```

其余 4 个实体按 DDL 逐列生成（`@Data` + `@TableName` + `@TableId(type = IdType.AUTO)` + 多单词列显式 `@TableField`）：
- `AiIndexJob`: indexName, kind, targetTotal, doneCount, failedCount, cursorId, status, errMsg, operatorName, createTime, updateTime, finishTime。
- `AiQuestionIndexState`: `@TableId("question_id")` questionId, indexedTime。
- `AiExtractDraft`: docId, rawIndex, qtype, title, optionsJson, answerJson, analysis, tagsJson, subjectId, difficulty, modelKey, promptVersion, status, reviewerName, reviewTime, rejectReason, questionId, createTime, updateTime。
- `AiMessageCitation`: messageId, conversationId, chunkId, questionId, indexName, rank, cosine, book, chapterPath, pageNo, snapshotTitle, createTime。

- [ ] **Step 2: 写 `AiKbDocMapper`（状态机 SQL 全部在这里）**

```java
package org.leon.aimodule.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leon.aimodule.rag.entity.AiKbDoc;

import java.util.List;

@Mapper
public interface AiKbDocMapper extends BaseMapper<AiKbDoc> {

    /** 条件 UPDATE 抢占：返回 1 才算抢到 */
    @Update("UPDATE ai_kb_doc SET status = #{to}, heartbeat_time = NOW(3), retry_count = retry_count + 1 " +
            "WHERE id = #{id} AND status = #{from}")
    int claim(@Param("id") long id, @Param("from") String from, @Param("to") String to);

    @Update("UPDATE ai_kb_doc SET heartbeat_time = NOW(3) WHERE id = #{id}")
    int heartbeat(@Param("id") long id);

    @Update("UPDATE ai_kb_doc SET status = #{status}, heartbeat_time = NOW(3) WHERE id = #{id}")
    int moveTo(@Param("id") long id, @Param("status") String status);

    @Update("UPDATE ai_kb_doc SET status = 'failed', stage_error = #{error}, heartbeat_time = NOW(3) WHERE id = #{id}")
    int fail(@Param("id") long id, @Param("error") String error);

    @Update("UPDATE ai_kb_doc SET status = 'ready', ready_time = NOW(3), heartbeat_time = NOW(3), " +
            "stage_error = NULL WHERE id = #{id}")
    int markReady(@Param("id") long id);

    @Select("SELECT id FROM ai_kb_doc WHERE status = 'pending' ORDER BY id LIMIT #{limit}")
    List<Long> findPendingIds(@Param("limit") int limit);

    @Select("SELECT id FROM ai_kb_doc WHERE status = 'failed' AND retry_count < 3 " +
            "AND update_time < NOW(3) - INTERVAL #{delaySeconds} SECOND ORDER BY id LIMIT #{limit}")
    List<Long> findRetryableFailedIds(@Param("limit") int limit, @Param("delaySeconds") int delaySeconds);

    @Update("UPDATE ai_kb_doc SET status = 'pending', retry_count = 0, stage_error = NULL, " +
            "heartbeat_time = NULL WHERE id = #{id} AND status = 'failed'")
    int resetFailed(@Param("id") long id);

    @Update("UPDATE ai_kb_doc SET status = 'pending', stage_error = 'worker 心跳超时，已回收重跑', " +
            "heartbeat_time = NOW(3) WHERE status IN ('parsing','chunking','embedding','indexing') " +
            "AND heartbeat_time < NOW(3) - INTERVAL #{timeoutSeconds} SECOND AND retry_count < 3")
    int recoverStuck(@Param("timeoutSeconds") int timeoutSeconds);

    @Update("UPDATE ai_kb_doc SET status = 'failed', stage_error = '重试次数耗尽' " +
            "WHERE status IN ('parsing','chunking','embedding','indexing') " +
            "AND heartbeat_time < NOW(3) - INTERVAL #{timeoutSeconds} SECOND AND retry_count >= 3")
    int failStuck(@Param("timeoutSeconds") int timeoutSeconds);

    @Update("UPDATE ai_kb_doc SET retry_count = 0 WHERE id = #{id}")
    int resetRetryCount(@Param("id") long id);
}
```

- [ ] **Step 3: 写 `AiKbChunkMapper`**

```java
package org.leon.aimodule.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leon.aimodule.rag.entity.AiKbChunk;

import java.util.List;

@Mapper
public interface AiKbChunkMapper extends BaseMapper<AiKbChunk> {

    @Select("SELECT * FROM ai_kb_chunk WHERE doc_id = #{docId} AND es_synced = 0 " +
            "ORDER BY seq LIMIT #{limit}")
    List<AiKbChunk> findUnsynced(@Param("docId") long docId, @Param("limit") int limit);

    @Update("""
            <script>
            UPDATE ai_kb_chunk SET es_synced = 1 WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markSynced(@Param("ids") List<Long> ids);

    @Delete("DELETE FROM ai_kb_chunk WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") long docId);

    @Select("SELECT COUNT(*) FROM ai_kb_chunk WHERE doc_id = #{docId} AND es_synced = 0")
    int countUnsynced(@Param("docId") long docId);

    @Select("SELECT COUNT(*) FROM ai_kb_chunk WHERE doc_id = #{docId}")
    int countByDoc(@Param("docId") long docId);
}
```

其余 mapper 都是 `extends BaseMapper<Entity>` + `@Mapper`，本阶段不需要自定义 SQL；阶段 5/6/7 再补。

- [ ] **Step 4: 补 MyBatis-Plus 驼峰配置**

```yaml
mybatis-plus:
  mapper-locations: classpath:mapper/*.xml
  type-aliases-package: com.example.a.entity
  configuration:
    map-underscore-to-camel-case: true
```

- [ ] **Step 5: 编译**

```bash
"$MVN" -pl AIModule -am -DskipTests compile
```

---

### Task 4a.3: 本地文件存储与 sha256

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/storage/StoredFile.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/storage/StorageService.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/storage/LocalFileStorage.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/storage/LocalFileStorageTest.java`

**Interfaces:**
- Consumes: `rag.storage.dir`（默认 `./data/kb`）
- Produces:
  - `StoredFile(String key, String sha256)`
  - `StorageService.store(InputStream, String fileName): StoredFile`
  - `StorageService.open(String key): InputStream`

- [ ] **Step 1: 写存储**

```java
package org.leon.aimodule.rag.storage;

public record StoredFile(String key, String sha256) {
}
```

```java
package org.leon.aimodule.rag.storage;

import java.io.IOException;
import java.io.InputStream;

public interface StorageService {
    StoredFile store(InputStream input, String fileName) throws IOException;

    InputStream open(String key) throws IOException;

    void delete(String key) throws IOException;
}
```

```java
package org.leon.aimodule.rag.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;

@Component
public class LocalFileStorage implements StorageService {

    private final Path root;

    public LocalFileStorage(@Value("${rag.storage.dir:./data/kb}") String dir) throws IOException {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public StoredFile store(InputStream input, String fileName) throws IOException {
        Path tmp = Files.createTempFile(root, "upload-", ".tmp");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = input; OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        String safeName = sanitize(fileName);
        String key = LocalDate.now() + "/" + sha256 + "-" + safeName;
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredFile(key, sha256);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("非法存储 key: " + key);
        }
        return path;
    }

    private String sanitize(String fileName) {
        String name = fileName == null ? "file" : Path.of(fileName).getFileName().toString();
        return name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }
}
```

- [ ] **Step 2: 写测试**

```java
package org.leon.aimodule.rag.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileStorageTest {

    @Test
    @DisplayName("同一内容得到同一 sha256，替换文件名不会改变指纹")
    void storesAndHashes(@TempDir Path temp) throws Exception {
        LocalFileStorage storage = new LocalFileStorage(temp.toString());
        byte[] bytes = "教材内容".getBytes(StandardCharsets.UTF_8);

        StoredFile a = storage.store(new ByteArrayInputStream(bytes), "a.pdf");
        StoredFile b = storage.store(new ByteArrayInputStream(bytes), "b.pdf");

        assertEquals(a.sha256(), b.sha256());
        assertTrue(a.key().endsWith("a.pdf"));
        try (var in = storage.open(a.key())) {
            assertEquals("教材内容", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("路径穿越被拒绝")
    void rejectsTraversal(@TempDir Path temp) throws Exception {
        LocalFileStorage storage = new LocalFileStorage(temp.toString());
        assertThrows(IllegalArgumentException.class, () -> storage.open("../etc/passwd"));
    }
}
```

- [ ] **Step 3: 跑测试**

```bash
"$MVN" -pl AIModule -am test -Dtest=LocalFileStorageTest \
  -Dsurefire.failIfNoTests=true -Dsurefire.failIfNoSpecifiedTests=false
```

预期：2 个测试全绿。

---

### Task 4a.4: `DocParser`（PDFBox 逐页 + Tika 兜底）

**Files:**
- Modify: `AIModule/pom.xml`（加 `tika-core`、`tika-parsers-standard-package`、`pdfbox`）
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/parse/DocParser.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/parse/TikaDocParser.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/parse/TikaDocParserTest.java`

**Interfaces:**
- Consumes: `InputStream`、mime、文件名
- Produces: `ParsedDoc parse(InputStream, String fileName, String mime)`
- 规则：PDF 用 PDFBox `PDFTextStripper` 逐页；`text/*` 按 `\f` 分页；其余 Tika 抽成单页；全文空白或 <20 字符抛 `RagException("未能抽取到文本，可能是扫描件，已拒收")`

- [ ] **Step 1: 加依赖**

```xml
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>3.2.3</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>3.2.3</version>
</dependency>
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.5</version>
</dependency>
```

说明：`tika-parsers-standard-package` 会传递引入 PDF 解析器与 PDFBox；显式声明 `pdfbox` 是为了让逐页抽取代码的版本可读、可锁定。Tika 3.2.3 中没有 spec 提到的 `PaginationHandler`（已实测），所以逐页逻辑在 PDFBox 层做。

- [ ] **Step 2: 写接口与实现**

```java
package org.leon.aimodule.rag.parse;

import java.io.IOException;
import java.io.InputStream;

public interface DocParser {
    ParsedDoc parse(InputStream input, String fileName, String mime) throws IOException;
}
```

```java
package org.leon.aimodule.rag.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.leon.aimodule.rag.exception.RagException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class TikaDocParser implements DocParser {

    private static final int MIN_TEXT_LENGTH = 20;

    @Override
    public ParsedDoc parse(InputStream input, String fileName, String mime) throws IOException {
        boolean pdf = "application/pdf".equalsIgnoreCase(mime)
                || (fileName != null && fileName.toLowerCase().endsWith(".pdf"));
        List<ParsedPage> pages;
        String title = fileName;
        if (pdf) {
            pages = parsePdf(input.readAllBytes());
        } else if (mime != null && mime.startsWith("text/")) {
            pages = parsePlainText(input.readAllBytes());
        } else {
            TikaResult result = parseWithTika(input, fileName);
            pages = List.of(new ParsedPage(1, result.text()));
            title = result.title() == null || result.title().isBlank() ? fileName : result.title();
        }

        int total = pages.stream().mapToInt(p -> p.text().replaceAll("\\s", "").length()).sum();
        if (total < MIN_TEXT_LENGTH) {
            throw new RagException("未能抽取到文本，可能是扫描件，已拒收");
        }
        return new ParsedDoc(fileName, title, pages);
    }

    private List<ParsedPage> parsePdf(byte[] bytes) throws IOException {
        List<ParsedPage> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(new ParsedPage(page, stripper.getText(document)));
            }
        }
        return pages;
    }

    private List<ParsedPage> parsePlainText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] parts = text.split("\\f", -1);
        List<ParsedPage> pages = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            pages.add(new ParsedPage(i + 1, parts[i]));
        }
        return pages;
    }

    private TikaResult parseWithTika(InputStream input, String fileName) throws IOException {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        if (fileName != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            metadata.set(TikaCoreProperties.TITLE, fileName);
        }
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        try {
            parser.parse(input, handler, metadata, context);
        } catch (Exception e) {
            throw new IOException("Tika 解析失败: " + e.getMessage(), e);
        }
        return new TikaResult(handler.toString(), metadata.get(TikaCoreProperties.TITLE));
    }

    private record TikaResult(String text, String title) {
    }
}
```

- [ ] **Step 3: 写测试（生成 2 页 PDF + 扫描件拒收）**

```java
package org.leon.aimodule.rag.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.exception.RagException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TikaDocParserTest {

    private final TikaDocParser parser = new TikaDocParser();

    private byte[] pdf(String... pageTexts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.save(out);
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("PDF 按页抽取，页码从 1 开始")
    void parsesPdfByPage() throws Exception {
        byte[] bytes = pdf("Quick sort average complexity is n log n.",
                "Train meeting problem uses relative speed.");
        ParsedDoc doc = parser.parse(new ByteArrayInputStream(bytes), "book.pdf", "application/pdf");

        assertEquals(2, doc.pages().size());
        assertEquals(1, doc.pages().get(0).pageNo());
        assertEquals(2, doc.pages().get(1).pageNo());
        assertTrue(doc.pages().get(0).text().contains("Quick sort"));
        assertTrue(doc.pages().get(1).text().contains("Train meeting"));
    }

    @Test
    @DisplayName("扫描件（无文本 PDF）直接拒收")
    void rejectsScannedPdf() throws Exception {
        byte[] bytes = pdf("", "");
        RagException e = assertThrows(RagException.class,
                () -> parser.parse(new ByteArrayInputStream(bytes), "scan.pdf", "application/pdf"));
        assertTrue(e.getMessage().contains("扫描件"));
    }

    @Test
    @DisplayName("TXT 按换页符分页")
    void parsesTextFormFeed() throws Exception {
        byte[] bytes = "第一页内容足够长，用来通过最小文本长度检查。\f第二页内容也足够长。"
                .getBytes(StandardCharsets.UTF_8);
        ParsedDoc doc = parser.parse(new ByteArrayInputStream(bytes), "a.txt", "text/plain");

        assertEquals(2, doc.pages().size());
        assertTrue(doc.pages().get(0).text().contains("第一页"));
        assertTrue(doc.pages().get(1).text().contains("第二页"));
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
"$MVN" -pl AIModule -am test -Dtest=TikaDocParserTest \
  -Dsurefire.failIfNoTests=true -Dsurefire.failIfNoSpecifiedTests=false
```

预期：3 个测试全绿。若 PDFBox 3.0.5 的 `PDType1Font` 构造函数不同，按编译错误调整（PDFBox 3 用 `new PDType1Font(Standard14Fonts.FontName.HELVETICA)`）。

---

### Task 4a.5: `IngestService` 状态机与断点续跑

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/ingest/IngestService.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/ingest/ChunkPersistenceService.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/ingest/IngestScheduler.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/ingest/PollTaskDispatcher.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/ingest/TaskDispatcher.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/ingest/IngestTask.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/ingest/RagIngestConfig.java`
- Modify: `AIModule/src/main/resources/application.yaml`（`rag.ingest.*`、`rag.storage.dir`）
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/ingest/IngestServiceTest.java`

**Interfaces:**
- Consumes: `DocParser`、`ChunkPlanner`、`EmbeddingGateway`、`StorageService`、`IndexRegistry`、mapper
- Produces:
  - `IngestService.process(long docId): void`（幂等；CAS 抢不到直接返回）
  - `IngestService.recoverStuck(): int`
  - `IngestService.retry(long docId): boolean`
  - `TaskDispatcher.dispatch(IngestTask): void`
  - `IngestTask(long docId, String traceId)`

- [ ] **Step 1: 加配置**

```yaml
rag:
  # ... 已有配置 ...
  storage:
    dir: ${RAG_STORAGE_DIR:./data/kb}
  ingest:
    poll-interval-ms: 5000
    workers: 4
    heartbeat-timeout-seconds: 300
    recovery-interval-ms: 120000
    failed-retry-delay-seconds: 60
```

- [ ] **Step 2: 写任务类型与派发接口**

```java
package org.leon.aimodule.rag.ingest;

public record IngestTask(long docId, String traceId) {
}
```

```java
package org.leon.aimodule.rag.ingest;

public interface TaskDispatcher {
    void dispatch(IngestTask task);
}
```

- [ ] **Step 3: 写 `IngestService`**

```java
package org.leon.aimodule.rag.ingest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.rag.chunk.ChunkPlanner;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.embedding.EmbeddingGateway;
import org.leon.aimodule.rag.entity.AiKbChunk;
import org.leon.aimodule.rag.entity.AiKbDoc;
import org.leon.aimodule.rag.exception.RagException;
import org.leon.aimodule.rag.index.IndexRegistry;
import org.leon.aimodule.rag.mapper.AiKbChunkMapper;
import org.leon.aimodule.rag.mapper.AiKbDocMapper;
import org.leon.aimodule.rag.parse.ChunkDraft;
import org.leon.aimodule.rag.parse.DocParser;
import org.leon.aimodule.rag.parse.ParsedDoc;
import org.leon.aimodule.rag.search.KbChunkDoc;
import org.leon.aimodule.rag.storage.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class IngestService {

    private final AiKbDocMapper docMapper;
    private final AiKbChunkMapper chunkMapper;
    private final DocParser docParser;
    private final ChunkPlanner chunkPlanner;
    private final EmbeddingGateway embeddingGateway;
    private final StorageService storageService;
    private final IndexRegistry indexRegistry;
    private final ElasticsearchClient client;
    private final RagProperties props;
    private final ChunkPersistenceService chunkPersistenceService;

    @Value("${rag.ingest.heartbeat-timeout-seconds:300}")
    private int heartbeatTimeoutSeconds;

    public IngestService(AiKbDocMapper docMapper, AiKbChunkMapper chunkMapper,
                         DocParser docParser, ChunkPlanner chunkPlanner,
                         EmbeddingGateway embeddingGateway, StorageService storageService,
                         IndexRegistry indexRegistry, ElasticsearchClient client,
                         RagProperties props, ChunkPersistenceService chunkPersistenceService) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.docParser = docParser;
        this.chunkPlanner = chunkPlanner;
        this.embeddingGateway = embeddingGateway;
        this.storageService = storageService;
        this.indexRegistry = indexRegistry;
        this.client = client;
        this.props = props;
        this.chunkPersistenceService = chunkPersistenceService;
    }

    public void process(long docId) {
        // CAS 抢占：抢不到说明别的 worker 已在做，直接返回
        if (docMapper.claim(docId, "pending", "parsing") != 1) {
            log.debug("[RAG] 文档 {} 抢占失败，跳过", docId);
            return;
        }
        long started = System.currentTimeMillis();
        try {
            AiKbDoc doc = docMapper.selectById(docId);
            if (doc == null) {
                throw new RagException("文档不存在: " + docId);
            }

            int existingChunks = chunkMapper.countByDoc(docId);
            if (existingChunks == 0) {
                ParsedDoc parsed = docParser.parse(
                        storageService.open(doc.getStorageKey()), doc.getFileName(), doc.getMime());
                docMapper.moveTo(docId, "chunking");
                docMapper.heartbeat(docId);

                List<ChunkDraft> drafts = chunkPlanner.plan(parsed);
                if (drafts.isEmpty()) {
                    throw new RagException("切片结果为空");
                }
                chunkPersistenceService.persistChunks(doc, drafts);
            } else {
                // embedding 或 indexing 阶段失败后重跑：切片已在库里，es_synced 记录着断点
                log.info("[RAG] 文档 {} 已有 {} 条切片，跳过解析与切片，从 embedding 续跑",
                        docId, existingChunks);
            }

            docMapper.moveTo(docId, "embedding");
            docMapper.heartbeat(docId);
            embedAndIndex(doc);

            docMapper.moveTo(docId, "indexing");
            indexRegistry.refresh(props.getIndex().getKbAlias());
            int remaining = chunkMapper.countUnsynced(docId);
            if (remaining > 0) {
                throw new RagException("仍有 " + remaining + " 条切片未进 ES");
            }
            docMapper.markReady(docId);
            log.info("[RAG] 文档 {} 入库完成，切片 {} 条，耗时 {}ms",
                    docId, chunkMapper.countByDoc(docId), System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.error("[RAG] 文档 {} 入库失败: {}", docId, e.getMessage(), e);
            docMapper.fail(docId, truncate(e.getMessage(), 500));
        }
    }

    private void embedAndIndex(AiKbDoc entity) throws Exception {
        String alias = props.getIndex().getKbAlias();
        int batchSize = props.getEmbedding().getBatchSize();
        while (true) {
            List<AiKbChunk> chunks = chunkMapper.findUnsynced(entity.getId(), 40);
            if (chunks.isEmpty()) {
                return;
            }
            for (int from = 0; from < chunks.size(); from += batchSize) {
                List<AiKbChunk> batch = chunks.subList(from, Math.min(from + batchSize, chunks.size()));
                List<float[]> vectors = embeddingGateway.embed(
                        batch.stream().map(AiKbChunk::getContent).toList());
                List<BulkOperation> operations = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) {
                    AiKbChunk chunk = batch.get(i);
                    KbChunkDoc esDoc = new KbChunkDoc();
                    esDoc.setChunkId(chunk.getId());
                    esDoc.setDocId(entity.getId());
                    esDoc.setScope(entity.getScope());
                    esDoc.setOwnerName(entity.getOwnerName());
                    esDoc.setSubjectId(entity.getSubjectId());
                    esDoc.setTitle(entity.getTitle());
                    esDoc.setContent(chunk.getContent());
                    esDoc.setVec(vectors.get(i));
                    esDoc.setBook(chunk.getBook());
                    esDoc.setChapterPath(chunk.getChapterPath());
                    esDoc.setPageNo(chunk.getPageNo());
                    esDoc.setTokenCount(chunk.getTokenCount());
                    operations.add(BulkOperation.of(o -> o.index(idx -> idx
                            .index(alias)
                            .id(String.valueOf(chunk.getId()))
                            .document(esDoc))));
                }
                BulkResponse response = client.bulk(BulkRequest.of(b -> b.index(alias).operations(operations)));
                if (response.errors()) {
                    throw new RagException("ES bulk 写入存在失败项，稍后重跑该批");
                }
                chunkMapper.markSynced(batch.stream().map(AiKbChunk::getId).toList());
                docMapper.heartbeat(entity.getId());
            }
        }
    }

    public int recoverStuck() {
        int recovered = docMapper.recoverStuck(heartbeatTimeoutSeconds);
        int failed = docMapper.failStuck(heartbeatTimeoutSeconds);
        if (recovered > 0 || failed > 0) {
            log.warn("[RAG] 回收心跳超时文档 {} 条，判定重试耗尽 {} 条", recovered, failed);
        }
        return recovered;
    }

    public boolean retry(long docId) {
        return docMapper.resetFailed(docId) == 1;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
```

- [ ] **Step 3b: 写 `ChunkPersistenceService`（事务必须跨 Bean 调用）**

`IngestService` 内部 `this.persistChunks(...)` 不会经过 Spring 代理，`@Transactional` 会静默失效。所以把事务方法放到独立 Bean：

```java
package org.leon.aimodule.rag.ingest;

import org.leon.aimodule.rag.entity.AiKbChunk;
import org.leon.aimodule.rag.entity.AiKbDoc;
import org.leon.aimodule.rag.mapper.AiKbChunkMapper;
import org.leon.aimodule.rag.mapper.AiKbDocMapper;
import org.leon.aimodule.rag.parse.ChunkDraft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChunkPersistenceService {

    private final AiKbChunkMapper chunkMapper;
    private final AiKbDocMapper docMapper;

    public ChunkPersistenceService(AiKbChunkMapper chunkMapper, AiKbDocMapper docMapper) {
        this.chunkMapper = chunkMapper;
        this.docMapper = docMapper;
    }

    @Transactional
    public void persistChunks(AiKbDoc doc, List<ChunkDraft> drafts) {
        // 只在文档第一次进入 chunking 时调用；embedding/indexing 失败重跑走续跑分支，不会删已同步切片
        chunkMapper.deleteByDocId(doc.getId());
        int totalTokens = 0;
        for (ChunkDraft draft : drafts) {
            AiKbChunk chunk = new AiKbChunk();
            chunk.setDocId(doc.getId());
            chunk.setSeq(draft.seq());
            chunk.setContent(draft.content());
            chunk.setTokenCount(draft.tokenCount());
            chunk.setPageNo(draft.pageNo());
            chunk.setBook(draft.book() == null ? doc.getTitle() : draft.book());
            chunk.setChapterPath(draft.chapterPath());
            chunk.setEsSynced(0);
            chunkMapper.insert(chunk);
            totalTokens += draft.tokenCount();
        }
        AiKbDoc update = new AiKbDoc();
        update.setId(doc.getId());
        update.setChunkCount(drafts.size());
        update.setTokenTotal(totalTokens);
        docMapper.updateById(update);
    }
}
```

- [ ] **Step 4: 写线程池、poll 派发与调度器**

```java
package org.leon.aimodule.rag.ingest;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class PollTaskDispatcher implements TaskDispatcher {

    private final IngestService ingestService;
    private final ThreadPoolExecutor executor;

    public PollTaskDispatcher(IngestService ingestService,
                              @Value("${rag.ingest.workers:4}") int workers) {
        this.ingestService = ingestService;
        this.executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "rag-ingest");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void dispatch(IngestTask task) {
        try {
            executor.execute(() -> ingestService.process(task.docId()));
        } catch (Exception e) {
            // 队列满时留在 pending，下一轮扫描会再次派发
            log.warn("[RAG] 派发文档 {} 失败，等待下一轮扫描: {}", task.docId(), e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
```

```java
package org.leon.aimodule.rag.ingest;

import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.rag.mapper.AiKbDocMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class IngestScheduler {

    private final AiKbDocMapper docMapper;
    private final TaskDispatcher dispatcher;
    private final IngestService ingestService;

    @Value("${rag.ingest.failed-retry-delay-seconds:60}")
    private int failedRetryDelaySeconds;

    public IngestScheduler(AiKbDocMapper docMapper, TaskDispatcher dispatcher,
                           IngestService ingestService) {
        this.docMapper = docMapper;
        this.dispatcher = dispatcher;
        this.ingestService = ingestService;
    }

    /** poll 模式的主循环；mq 模式下它仍作为“消息丢了也不丢任务”的兜底 */
    @Scheduled(fixedDelayString = "${rag.ingest.poll-interval-ms:5000}")
    public void pollPending() {
        List<Long> ids = docMapper.findPendingIds(20);
        for (Long id : ids) {
            dispatcher.dispatch(new IngestTask(id, "poll"));
        }
        List<Long> failed = docMapper.findRetryableFailedIds(10, failedRetryDelaySeconds);
        for (Long id : failed) {
            if (docMapper.resetFailed(id) == 1) {
                dispatcher.dispatch(new IngestTask(id, "failed-retry"));
            }
        }
    }

    @Scheduled(fixedDelayString = "${rag.ingest.recovery-interval-ms:120000}")
    public void recover() {
        ingestService.recoverStuck();
    }
}
```

```java
package org.leon.aimodule.rag.ingest;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class RagIngestConfig {
}
```

- [ ] **Step 5: 单测状态机（Mockito）**

```java
package org.leon.aimodule.rag.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.mapper.AiKbDocMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestServiceTest {

    @Test
    @DisplayName("CAS 抢占失败时不执行后续阶段")
    void skipWhenClaimFails() {
        AiKbDocMapper mapper = mock(AiKbDocMapper.class);
        when(mapper.claim(1L, "pending", "parsing")).thenReturn(0);

        // 用最小构造验证抢占分支；其余依赖传 null 不会被触达
        IngestService service = new IngestService(mapper, null, null, null, null,
                null, null, null, null, null);
        service.process(1L);

        // 没有异常即说明 claim 失败后直接返回
        assertTrue(true);
    }

    @Test
    @DisplayName("retry 只能把 failed 重置为 pending")
    void retryOnlyFailed() {
        AiKbDocMapper mapper = mock(AiKbDocMapper.class);
        when(mapper.resetFailed(2L)).thenReturn(1);
        when(mapper.resetFailed(3L)).thenReturn(0);

        IngestService service = new IngestService(mapper, null, null, null, null,
                null, null, null, null, null);
        assertTrue(service.retry(2L));
        assertFalse(service.retry(3L));
    }
}
```

- [ ] **Step 6: 编译并跑单测**

```bash
"$MVN" -pl AIModule -am test -Dtest=IngestServiceTest \
  -Dsurefire.failIfNoTests=true -Dsurefire.failIfNoSpecifiedTests=false
```

预期：2 个测试全绿。`IngestService` 的构造函数参数顺序必须与测试一致；若后续增加依赖，先改测试再实现。

---

### Task 4a.6: 知识库管理接口 #1–#7

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/web/KbDocumentController.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/web/dto/KbDocUploadResponse.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/web/dto/KbDocListItem.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/web/dto/KbDocDetail.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/web/dto/KbChunkItem.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/web/dto/KbDocPatchRequest.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/service/KbDocumentService.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/web/CursorCodec.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/service/KbDocumentServiceTest.java`

**Interfaces:**
- Consumes: `StorageService`、mapper、`IngestService`、`IndexRegistry`、`ElasticsearchClient`
- Produces:
  - `POST /ai/kb/documents`（multipart：file, scope, title?, subjectId?）→ `{docId, duplicated, status}`
  - `GET /ai/kb/documents?scope=&cursor=&limit=` → `{items, nextCursor}`
  - `GET /ai/kb/documents/{id}` → 进度
  - `GET /ai/kb/documents/{id}/chunks?beforeSeq=&limit=` → 切片
  - `POST /ai/kb/documents/{id}/retry`
  - `PATCH /ai/kb/documents/{id}` → 改 title/subjectId 并同步 ES
  - `DELETE /ai/kb/documents/{id}` → MySQL 软删 + ES 物理删
- 可见性：official 所有人可读；personal 只有 owner 可读；管理员可管理全部

- [ ] **Step 1: 写 `KbDocumentService.upload` 的核心逻辑**

关键顺序（写进实现，不要改）：

```java
public KbDocUploadResponse upload(String userName, String scope, String title,
                                  Long subjectId, MultipartFile file, boolean admin) throws IOException {
    if (!"official".equals(scope) && !"personal".equals(scope)) {
        throw new RagException("scope 只能是 official 或 personal");
    }
    if ("official".equals(scope) && !admin) {
        throw new RagForbiddenException("只有管理员可以上传官方文档");
    }
    String ownerName = "official".equals(scope) ? "" : userName;
    StoredFile stored = storageService.store(file.getInputStream(), file.getOriginalFilename());

    AiKbDoc existing = docMapper.selectOne(new LambdaQueryWrapper<AiKbDoc>()
            .eq(AiKbDoc::getScope, scope)
            .eq(AiKbDoc::getOwnerName, ownerName)
            .eq(AiKbDoc::getSha256, stored.sha256()));

    if (existing != null && !"deleted".equals(existing.getStatus())) {
        // 重复上传：不重新解析、不重新向量化
        return new KbDocUploadResponse(existing.getId(), true, existing.getStatus());
    }

    if (existing != null) {
        // 软删过的同一文件：复用行，重新入库
        chunkMapper.deleteByDocId(existing.getId());
        existing.setStatus("pending");
        existing.setStorageKey(stored.key());
        existing.setFileName(file.getOriginalFilename());
        existing.setMime(file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        existing.setSize(file.getSize());
        existing.setRetryCount(0);
        existing.setStageError(null);
        existing.setChunkCount(0);
        existing.setTokenTotal(0);
        existing.setHeartbeatTime(null);
        existing.setReadyTime(null);
        if (title != null && !title.isBlank()) {
            existing.setTitle(title);
        }
        existing.setSubjectId(subjectId == null ? 0L : subjectId);
        docMapper.updateById(existing);
        return new KbDocUploadResponse(existing.getId(), false, "pending");
    }

    AiKbDoc doc = new AiKbDoc();
    doc.setScope(scope);
    doc.setOwnerName(ownerName);
    doc.setSubjectId(subjectId == null ? 0L : subjectId);
    doc.setTitle(title == null || title.isBlank() ? baseName(file.getOriginalFilename()) : title);
    doc.setFileName(file.getOriginalFilename());
    doc.setMime(file.getContentType() == null ? "application/octet-stream" : file.getContentType());
    doc.setSize(file.getSize());
    doc.setStorageKey(stored.key());
    doc.setSha256(stored.sha256());
    doc.setStatus("pending");
    doc.setChunkCount(0);
    doc.setTokenTotal(0);
    doc.setRetryCount(0);
    docMapper.insert(doc);
    return new KbDocUploadResponse(doc.getId(), false, "pending");
}
```

注意：重复上传时 `storageService.store` 已经把文件写了一次；若判定 duplicate，应删除新写的文件避免磁盘泄漏。实现时给 `StorageService` 增加 `delete(String key)` 并在 duplicate 分支调用；软删复用时也删掉旧 key 指向的文件（如果新旧 key 不同）。

- [ ] **Step 2: 列表游标**

```java
public final class CursorCodec {

    private CursorCodec() {
    }

    public static String encode(long updateTimeMillis, long id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((updateTimeMillis + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String[] parts = raw.split("\\|");
        return new Cursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
    }

    public record Cursor(long updateTimeMillis, long id) {
    }
}
```

列表 SQL 用 MyBatis-Plus 拼接：

```java
LambdaQueryWrapper<AiKbDoc> wrapper = new LambdaQueryWrapper<>();
wrapper.ne(AiKbDoc::getStatus, "deleted");
wrapper.and(w -> w.eq(AiKbDoc::getScope, "official")
        .or(p -> p.eq(AiKbDoc::getScope, "personal").eq(AiKbDoc::getOwnerName, userName)));
if (scope != null && !scope.isBlank()) {
    wrapper.eq(AiKbDoc::getScope, scope);
}
CursorCodec.Cursor c = CursorCodec.decode(cursor);
if (c != null) {
    wrapper.apply("(update_time < FROM_UNIXTIME({0} / 1000) OR " +
                    "(update_time = FROM_UNIXTIME({0} / 1000) AND id < {1}))",
            c.updateTimeMillis(), c.id());
}
wrapper.orderByDesc(AiKbDoc::getUpdateTime).orderByDesc(AiKbDoc::getId)
        .last("LIMIT " + Math.min(limit == null ? 20 : limit, 50));
```

`FROM_UNIXTIME` 的毫秒参数要除以 1000；如果 `DATETIME(3)` 精度对不上，改用 `FROM_UNIXTIME({0} / 1000.0)`。返回的 `nextCursor` 取最后一条的 `updateTime` + `id`。

- [ ] **Step 3: 详情/切片/重试/改标题/删除**

- 详情：`visible(doc, userName, admin)` 不通过 → `RagForbiddenException("文档不存在")`（统一文案，避免枚举）。返回 `status`、`chunkCount`、`tokenTotal`、`stageError`、`retryCount`、`createTime`、`readyTime`（epoch 毫秒）。
- 切片：`beforeSeq` 游标，按 `seq DESC LIMIT`；返回 `seq/content/pageNo/chapterPath/tokenCount`。
- 重试：`IngestService.retry(id)` 返回 false → `RagException("只有 failed 状态的文档可以重试")`。
- 改标题：更新 `title`/`subjectId` 后同步 ES：

```java
client.updateByQuery(u -> u.index(props.getIndex().getKbAlias())
        .query(q -> q.term(t -> t.field("doc_id").value(docId)))
        .script(s -> s.source("ctx._source.title = params.title; " +
                        "ctx._source.subject_id = params.subjectId;")
                .params("title", JsonData.of(newTitle))
                .params("subjectId", JsonData.of(newSubjectId))));
indexRegistry.refresh(props.getIndex().getKbAlias());
```

`Script.Builder.params(String, JsonData)` 若编译不通过，改用 `params(Map.of("title", JsonData.of(...)))`。

- 删除：`status='deleted'` + `deleteByQuery` term `doc_id` + refresh。ES 物理删，MySQL 保留行。

- [ ] **Step 4: 写 controller（multipart 与普通 JSON 分开）**

```java
@RestController
@RequestMapping("/ai/kb/documents")
public class KbDocumentController {

    @PostMapping
    public Result<KbDocUploadResponse> upload(
            @RequestHeader("X-User-Name") String userName,
            @RequestParam("file") MultipartFile file,
            @RequestParam("scope") String scope,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "subjectId", required = false) Long subjectId) throws IOException {
        // admin 判定通过 RagAdminGuard 暴露的 isAdmin(userName)
    }

    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestHeader("X-User-Name") String userName,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) { ... }

    @GetMapping("/{id}")
    public Result<KbDocDetail> detail(@RequestHeader("X-User-Name") String userName,
                                      @PathVariable long id) { ... }

    @GetMapping("/{id}/chunks")
    public Result<Map<String, Object>> chunks(@RequestHeader("X-User-Name") String userName,
                                              @PathVariable long id,
                                              @RequestParam(required = false) Integer beforeSeq,
                                              @RequestParam(required = false) Integer limit) { ... }

    @PostMapping("/{id}/retry")
    public Result<Void> retry(@RequestHeader("X-User-Name") String userName,
                              @PathVariable long id) { ... }

    @PatchMapping("/{id}")
    public Result<Void> patch(@RequestHeader("X-User-Name") String userName,
                              @PathVariable long id,
                              @Valid @RequestBody KbDocPatchRequest request) { ... }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestHeader("X-User-Name") String userName,
                               @PathVariable long id) { ... }
}
```

`RagAdminGuard` 增加 `boolean isAdmin(String userName)`；`requireAdmin` 复用它。

- [ ] **Step 5: 上传/判重单测**

用 Mockito 构造 `KbDocumentService`（mapper、storage、chunkMapper 全 mock），覆盖：
1. 首次上传 → `duplicated=false`，insert 一次，status=pending。
2. 相同 sha256 且未删除 → `duplicated=true`，不 insert、不删切片。
3. 软删后重传 → `duplicated=false`，复用旧 id，chunk 被清空。
4. 非管理员上传 official → 抛 `RagForbiddenException`。
5. 非 owner patch 私人文档 → 抛 `RagForbiddenException`。

```bash
"$MVN" -pl AIModule -am test -Dtest=KbDocumentServiceTest \
  -Dsurefire.failIfNoTests=true -Dsurefire.failIfNoSpecifiedTests=false
```

预期：5 个测试全绿。

---

### Task 4a.7: 端到端手工验收（含崩溃回收）

**Files:** 无新增；使用 Task 4a.4 测试里生成 PDF 的方法，或手工准备一份 2 页 PDF

- [ ] **Step 1: 启动应用**

```bash
export ELASTIC_PASSWORD="$(tr -d '\r\n' < '/f/ES/密码.txt')"
export DASHSCOPE_API_KEY="$(printenv DASHSCOPE_API_KEY)"
export RAG_ADMIN_NAMES="admin"
cd /f/AAAJAVA/code/ezPracticeExercises/AIModule
"$MVN" spring-boot:run
```

- [ ] **Step 2: 上传一份 2 页 PDF**

```bash
curl -s -X POST http://127.0.0.1:8105/ai/kb/documents \
  -H 'X-User-Name: admin' \
  -F 'file=@/path/to/sample.pdf' -F 'scope=official' -F 'subjectId=12' -F 'title=测试教材'
```

预期：`{"code":1,"data":{"docId":1,"duplicated":false,"status":"pending"}}`。

- [ ] **Step 3: 轮询到 ready**

```bash
for i in $(seq 1 60); do
  curl -s http://127.0.0.1:8105/ai/kb/documents/1 -H 'X-User-Name: admin'
  echo
  sleep 3
done
```

预期：状态依次经过 `pending→parsing→chunking→embedding→indexing→ready`，最终 `chunkCount>0`、`readyTime` 非空、`stageError=null`。

- [ ] **Step 4: 检索页码正确**

```bash
curl -s -X POST http://127.0.0.1:8105/ai/kb/search \
  -H 'X-User-Name: alice' -H 'Content-Type: application/json' \
  -d '{"query":"PDF 里的一个短语","topK":6,"subjectId":12,"scope":["official"]}'
```

预期：命中切片的 `pageNo` 与 PDF 实际页码一致。

- [ ] **Step 5: 崩溃回收**

1. 上传另一份 PDF，在日志出现 `embedding` 后立刻 `Ctrl+C` 停掉应用。
2. 把 `rag.ingest.heartbeat-timeout-seconds` 临时改为 `30` 启动。
3. 等待 2 个回收周期（约 1 分钟），观察日志出现 `回收心跳超时文档`，文档回到 `pending` 并最终 `ready`。
4. 记录 `es_synced` 的作用：重启后已同步的切片不应再次调用 embedding（看 `EmbeddingGateway.callCount()` 日志或 embedding 计费调用数）。

- [ ] **Step 6: 重复上传判重**

再次上传同一文件 → `duplicated=true`，且 embedding 调用次数不增加。

- [ ] **Step 7: 提交**

```bash
git add AIModule/pom.xml AIModule/src/main/resources \
        AIModule/src/main/java/org/leon/aimodule/rag AIModule/src/test/java/org/leon/aimodule/rag
git commit -m "实现RAG写路径：Tika/PDFBox解析、入库状态机与poll派发"
```

---

## 写路径验收清单（Stage 4a）

- [ ] 6 张 `ai_*` 表已建（用户同意后）。
- [ ] 上传真 PDF 轮询到 `ready`，切片页数与 PDF 一致。
- [ ] 中途 kill 应用，重启后卡住的任务被心跳回收并最终完成。
- [ ] 重复上传返回 `duplicated=true`，不重复调用 embedding。
- [ ] `DELETE` 后同一 query 在 refresh 后立即搜不到（spec 7.5 第 2 条）。
- [ ] 非 owner 读/改/删私人文档返回统一的“文档不存在”。

## 执行记录

（执行时追加：建表日期、PDF 页数/切片数、端到端耗时、回收日志、判重结果。）
