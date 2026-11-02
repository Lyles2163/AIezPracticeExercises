-- 2026-11-01 RAG 知识库文档入库建表：rag_document + rag_chunk
--
-- 目标库：本地 MySQL（localhost:3306）的 ezpracticeexercises，root 空密码。
--
-- 执行方式：与 2026-10-23-ai-chat-conversation.sql 一致 —— 本仓库没有 Flyway/Liquibase，
--   DDL 由人工通过 JDBC 逐条执行。
--
-- 重复执行：两条 CREATE TABLE 有意不写 IF NOT EXISTS，重跑会在表已存在时直接失败并报
--   MySQL 错误 1050（Table 'rag_document' already exists）。这是刻意为之，避免库与文件
--   悄悄漂移。真要重建先手工 DROP，并查 information_schema.TABLES 确认后再跑。
--
-- ============================ 为什么是两张表 ============================
--
-- 存储分工的唯一原则：MySQL 是不可丢的事实之源，ES 是可随时重建的派生索引。
--
--   rag_document  文档级元数据（一个上传文件一行）
--   rag_chunk     切片级正文（一个 chunk 一行，ES 文档的 _id 就是这里的 id）
--
-- ES 侧 ai_kb_chunk 索引的 mapping 里早就有 chunk_id / doc_id 两个 long 字段
-- （见 AIModule/.../rag/index/IndexMappings.java），它们就是指回这两张表的外键 ——
-- 本次建表是把原作者设计里缺的 MySQL 那一半补上。
--
-- 不能只存 ES 的理由：
--   1. analyzer 一旦建索引就不可改（IndexMappings 注释已说明），cjk↔ik 切换、
--      向量维度变更都必须重建索引；届时若没有 MySQL 里的切片正文，就只能重新解析
--      原文件、并重新付一遍 embedding 的钱。
--   2. ES 不适合「我传了哪些文档、哪些还在处理中、失败了几个」这类业务查询与分页。
--
-- 向量（1024 维 float）刻意不进 MySQL：只有 ES 能做 kNN，存进 MySQL 既检索不了
-- 又白占空间（1024 维 float ≈ 4KB/条）。rag_chunk.content 存的是切片文本，
-- 重放 embedding 时读它就够，不需要把向量也落一份。
--
-- 写入顺序约定（由 RagDocumentService 保证，不靠数据库约束）：
--   先写 MySQL（事务）→ 再 embedding → 再写 ES → 回写 status/index_status。
-- 反过来先写 ES 再写 MySQL，一旦 MySQL 失败就会在 ES 里留下没有源的孤儿切片，难清理。
-- MySQL 成功而 ES 失败时，index_status 停在 pending/embedded 的切片就是待补清单，
-- 可据此重放，数据不会丢。
--
-- 与既有约定保持一致：无物理外键，一致性在应用层；utf8mb4 / utf8mb4_0900_ai_ci。

-- 文档级：一个上传的文件一行
CREATE TABLE rag_document (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  file_name    VARCHAR(255) NOT NULL COMMENT '上传时的原始文件名',
  title        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '展示标题，缺省取 file_name 去扩展名',
  book         VARCHAR(255) NULL COMMENT '书名/课程名，溯源展示用，对应 ES 的 book',
  scope        VARCHAR(16)  NOT NULL DEFAULT 'personal' COMMENT 'official/personal，对应 ES 权限过滤',
  owner_name   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT 'personal 时=上传者 X-User-Name；official 时空串',
  subject_id   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=全学科通用；>0=本学科，对应 ES 的 subject_id',
  source_path  VARCHAR(512) NULL COMMENT '原文件落盘路径/OSS key；只存引用，不存二进制',
  file_hash    CHAR(64)     NULL COMMENT 'SHA-256，用于去重与重复上传幂等',
  file_size    BIGINT UNSIGNED NULL COMMENT '字节数',
  mime_type    VARCHAR(64)  NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'pending'
               COMMENT 'pending/parsing/chunking/embedding/indexed/failed',
  chunk_count  INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '切片总数，indexed 时写入',
  token_count  INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '全文 token 总数，indexed 时写入',
  error_msg    VARCHAR(512) NULL COMMENT 'status=failed 时的原因',
  create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 同一归属下同一文件只允许一行：official 的 owner_name 恒为空串，所以两次上传同一份
  -- 官方文档会撞这条唯一键（正是要的去重）；alice 的个人库与官方库互不影响（owner 不同）。
  -- file_hash 为 NULL 时不参与约束（MySQL 唯一键允许多个 NULL），拿不到 hash 就不会误撞。
  UNIQUE KEY uk_scope_owner_hash (scope, owner_name, file_hash),
  KEY idx_owner_status (scope, owner_name, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG 知识库文档';

-- 切片级：文档的每个 chunk 一行。ES ai_kb_chunk 里对应文档的 _id = 本表 id
CREATE TABLE rag_chunk (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  doc_id        BIGINT UNSIGNED NOT NULL COMMENT '所属 rag_document.id，无物理外键',
  seq           INT UNSIGNED NOT NULL COMMENT '文档内顺序，从 1 开始',
  content       MEDIUMTEXT   NOT NULL COMMENT '切片正文。重建索引/重放 embedding 的唯一来源',
  token_count   INT UNSIGNED NOT NULL DEFAULT 0,
  page_no       INT UNSIGNED NULL COMMENT '主要溯源锚点，对应 ES 的 page_no',
  chapter_path  VARCHAR(255) NULL COMMENT '如「第3章 → 3.2 常见排序算法」，对应 ES 的 chapter_path',
  index_status  VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending/embedded/indexed/failed',
  error_msg     VARCHAR(512) NULL,
  create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- (doc_id, seq) 唯一：同一文档内重放切片不会产生第二条 seq=1，重跑必然撞键而不是静默重复
  UNIQUE KEY uk_doc_seq (doc_id, seq),
  KEY idx_doc_status (doc_id, index_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG 文档切片';
