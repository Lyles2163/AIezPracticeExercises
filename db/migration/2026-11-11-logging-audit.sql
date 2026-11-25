-- 2026-11-11 日志审计：接口使用记录、AI 接口调用记录、AI Token 用量明细
-- 目标库：本地 MySQL 的 ezpracticeexercises。
-- 重复执行会因表已存在报 MySQL 1050，属于刻意行为。

CREATE TABLE sys_api_call_log (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  trace_id         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '链路追踪 ID，与网关/前端 traceId 对齐',
  span_id          VARCHAR(64)  NULL     COMMENT '本次调用的跨度 ID',
  user_id          BIGINT UNSIGNED NULL     COMMENT 'X-User-Id，网关注入；未登录请求为 NULL',
  user_name        VARCHAR(64)  NULL     COMMENT 'X-User-Name，网关注入；未登录请求为 NULL',
  source_service   VARCHAR(64)  NOT NULL COMMENT '请求来源微服务：gateway/user/practice/ai/…',
  target_service   VARCHAR(64)  NOT NULL COMMENT '被调用微服务',
  http_method      VARCHAR(10)  NOT NULL COMMENT 'GET/POST/PUT/DELETE/…',
  request_uri      VARCHAR(512) NOT NULL COMMENT '请求完整路径（已脱敏）',
  handler_method   VARCHAR(256) NULL     COMMENT '执行的 Controller 方法全限定名',
  remote_addr      VARCHAR(64)  NULL     COMMENT '客户端 IP（经 X-Forwarded-For 回溯）',
  user_agent       VARCHAR(256) NULL     COMMENT '客户端 UA',
  request_size     INT UNSIGNED NULL     COMMENT '请求体字节数',
  response_size    INT UNSIGNED NULL     COMMENT '响应体字节数',
  status_code      SMALLINT UNSIGNED NOT NULL COMMENT 'HTTP 状态码',
  error_code       VARCHAR(32)  NULL     COMMENT '业务错误码（如有）',
  error_msg        VARCHAR(512) NULL     COMMENT '异常简要信息（已脱敏）',
  cost_ms          INT UNSIGNED NOT NULL COMMENT '接口耗时(毫秒)',
  create_time      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_time (user_id, create_time),
  KEY idx_service_time (target_service, create_time),
  KEY idx_status_time (status_code, create_time),
  KEY idx_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='API 接口使用记录';

CREATE TABLE sys_ai_call_log (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  trace_id          VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '链路追踪 ID',
  user_id           BIGINT UNSIGNED NULL     COMMENT '调用者 ID',
  user_name         VARCHAR(64)  NULL     COMMENT '调用者姓名/账号',
  conversation_id   BIGINT UNSIGNED NULL     COMMENT '所属会话 ID（对话场景）',
  message_id        BIGINT UNSIGNED NULL     COMMENT '所属消息 ID（对话场景）',
  service_type      VARCHAR(16)  NOT NULL COMMENT 'CHAT/IMAGE/RAG/…，与 ai_user_model_provider.service_types 对齐',
  provider          VARCHAR(64)  NULL     COMMENT '服务商名称（如 deepseek/dashscope/自建）',
  model_key         VARCHAR(64)  NULL     COMMENT '使用的模型 key',
  upstream_model    VARCHAR(128) NULL     COMMENT '上游实际模型名',
  prompt_tokens     INT UNSIGNED NULL     COMMENT '输入 token 数',
  completion_tokens INT UNSIGNED NULL     COMMENT '输出 token 数',
  reasoning_tokens  INT UNSIGNED NULL     COMMENT '推理 token 数（completion 的拆分，不累加）',
  total_tokens      INT UNSIGNED NULL     COMMENT '总 token 数（prompt + completion）',
  cost_ms           INT UNSIGNED NOT NULL COMMENT '调用耗时(毫秒)',
  status            VARCHAR(16)  NOT NULL COMMENT 'done/failed/truncated',
  error_msg         VARCHAR(255) NULL     COMMENT '失败原因（已脱敏）',
  create_time       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_service (user_id, service_type, create_time),
  KEY idx_model_time (model_key, create_time),
  KEY idx_conversation (conversation_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 接口调用记录';

CREATE TABLE sys_ai_usage_log (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id           BIGINT UNSIGNED NOT NULL COMMENT '调用者 ID',
  user_name         VARCHAR(64)  NOT NULL,
  service_type      VARCHAR(16)  NOT NULL COMMENT 'CHAT/IMAGE/RAG',
  model_key         VARCHAR(64)  NOT NULL,
  prompt_tokens     INT UNSIGNED NOT NULL DEFAULT 0,
  completion_tokens INT UNSIGNED NOT NULL DEFAULT 0,
  total_tokens      INT UNSIGNED NOT NULL DEFAULT 0,
  stat_date         DATE         NOT NULL COMMENT '统计日期（按天分区键）',
  create_time       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_model_date (user_id, model_key, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI Token 用量明细';
