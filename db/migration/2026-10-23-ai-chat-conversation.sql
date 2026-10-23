-- 2026-10-23 仿 ChatGPT 对话子系统建表：ai_conversation + ai_message
--
-- 目标库：本地 MySQL 9.1.0（localhost:3306）的 ezpracticeexercises，root 空密码。
--
-- 执行方式：本仓库没有 Flyway/Liquibase 之类的迁移工具，下面的 DDL 由人工通过 jshell +
--   JDBC（mysql-connector-j 9.7.0）逐条执行，2026-10-23 已在该库跑通两条 CREATE TABLE，
--   表、索引、列注释都跟 information_schema 逐项核对过，证据见
--   .superpowers/sdd/2026-10-23-ai-chat-conversation/task-2-report.md。
--
-- 执行前提（pre-check 实测确认）：执行时库里没有任何 ai_ 前缀的表。
--
-- 重复执行：两条语句有意不写 IF NOT EXISTS，所以重跑会在表已存在时直接失败并报
--   MySQL 错误 1050（Table 'ai_conversation' already exists）。这是刻意为之，目的是
--   让重复执行必然报错，而不是静默跳过导致库与文件悄悄漂移。真要重建时先手工 DROP，
--   并查 information_schema.TABLES 确认后再跑。

-- 仿 ChatGPT 对话子系统：2 张表。无物理外键，一致性在应用层。
CREATE TABLE ai_conversation (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_name       VARCHAR(64)  NOT NULL COMMENT '网关注入的 X-User-Name，源自 JWT 的 username claim',
  title           VARCHAR(120) NOT NULL DEFAULT '新对话',
  model_key       VARCHAR(64)  NOT NULL DEFAULT 'deepseek-chat',
  effort          VARCHAR(16)  NOT NULL DEFAULT 'Medium' COMMENT 'Low/Medium/High/Extra/Max，只映射服务端参数',
  last_message_id BIGINT UNSIGNED NULL,
  last_preview    VARCHAR(160) NOT NULL DEFAULT '' COMMENT '列表预览，写入时截断好',
  message_count   INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'user + 当前生效 assistant 条数',
  status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0软删',
  create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_list (user_name, status, update_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 会话';

CREATE TABLE ai_message (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  conversation_id     BIGINT UNSIGNED NOT NULL,
  role                VARCHAR(16)  NOT NULL COMMENT 'user / assistant',
  content             MEDIUMTEXT   NOT NULL COMMENT 'Markdown 源文',
  status              VARCHAR(16)  NOT NULL DEFAULT 'done' COMMENT 'streaming/done/failed/truncated/superseded',
  stop_reason         VARCHAR(24)  NULL COMMENT 'done/truncated 时的收尾原因',
  model_key           VARCHAR(64)  NULL COMMENT 'assistant 实际使用的上游模型',
  effort              VARCHAR(16)  NULL COMMENT '本条回答生效时的档位',
  prompt_tokens       INT UNSIGNED NULL,
  completion_tokens   INT UNSIGNED NULL,
  reply_to_message_id BIGINT UNSIGNED NULL COMMENT 'assistant 指向被回答的那条 user 消息',
  client_msg_id       VARCHAR(40)  NULL COMMENT '前端生成的幂等键',
  error_msg           VARCHAR(255) NULL,
  create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_conv_client (conversation_id, client_msg_id),
  KEY idx_conv_page (conversation_id, id),
  KEY idx_reply_to (reply_to_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 消息';
