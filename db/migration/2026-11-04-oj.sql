-- 2026-11-04 在线 OJ 判题建表：oj_problem + oj_testcase + oj_submission
--
-- 目标库：本地 MySQL（localhost:3306）的 ezpracticeexercises。
--
-- 执行方式：与 2026-11-01-rag-document-chunk.sql 一致 —— 本仓库没有 Flyway/Liquibase，
--   DDL 由人工逐条执行。
--
-- 重复执行：三条 CREATE TABLE 有意不写 IF NOT EXISTS，重跑会因表已存在报 MySQL 1050。
--   这是刻意为之：避免库与文件悄悄漂移。要重建先手工 DROP。
--
-- ============================ 为什么是三张独立表 ============================
--
-- 现有 qb_question 存的是选择题答案（answer_json 存 "A" / ["A","C"]），
-- 编程题需要「题目描述 + 多组测试用例 + 每次提交记录」，结构与选择题差太远，
-- 硬塞进 qb_question 会让 AnswerGrader 的题型分支出现第 6 种形态，
-- 而它当前 1~5 的判分口径是"题库存量数据"，不适合被编程题改写。
--
--   oj_problem    一道编程题一行
--   oj_testcase   一个测试用例一行（is_sample=1 用户可见，=0 隐藏）
--   oj_submission 一次提交一行，判题结果回写到这一行
--
-- 判题链路：提交接口插入 PENDING → 投递 RabbitMQ(只投 submissionId)
--   → 消费者 markRunning → Docker 沙箱逐用例判 → markFinished 落终态。
--   MQ 消息里不放源码：源码可能几十 KB，而数据库才是事实之源。
--
-- 与既有约定保持一致：无物理外键，一致性在应用层；utf8mb4 / utf8mb4_0900_ai_ci。

-- 编程题本身。language 不放在这里：同一道题可被任意语言解答，语言跟着提交走。
CREATE TABLE oj_problem (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  subject_id      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '所属科目，0=通用',
  title           VARCHAR(128) NOT NULL COMMENT '题目标题',
  description     MEDIUMTEXT   NOT NULL COMMENT '题目描述(Markdown)',
  template_code   MEDIUMTEXT   NULL     COMMENT '初始代码模板',
  difficulty      TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1简单 2中等 3困难',
  time_limit_ms   INT UNSIGNED NOT NULL DEFAULT 2000 COMMENT '单用例时间限制(毫秒)',
  memory_limit_mb INT UNSIGNED NOT NULL DEFAULT 256  COMMENT '内存限制(MB)',
  status          TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1启用 0下架',
  create_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_subject_status (subject_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OJ 编程题';

-- 测试用例。(problem_id, seq) 唯一：同一题内重复插入同序号必然撞键，而不是静默重复。
CREATE TABLE oj_testcase (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  problem_id      BIGINT UNSIGNED NOT NULL COMMENT 'oj_problem.id，无物理外键',
  seq             INT UNSIGNED NOT NULL COMMENT '用例顺序，从1开始',
  input_data      MEDIUMTEXT NULL COMMENT '喂给标准输入(stdin)的内容',
  expected_output MEDIUMTEXT NOT NULL COMMENT '期望的标准输出(stdout)',
  is_sample       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '1=示例(用户可见) 0=隐藏用例',
  create_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_problem_seq (problem_id, seq),
  KEY idx_problem_sample (problem_id, is_sample, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OJ 测试用例';

-- 提交记录。status 是字符串枚举而非 tinyint：判题状态未来可能增删，
-- 字符串让库里的历史数据自解释，排查线上问题时不用对着数字查表。
CREATE TABLE oj_submission (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id        BIGINT UNSIGNED NOT NULL,
  problem_id     BIGINT UNSIGNED NOT NULL,
  language       VARCHAR(16) NOT NULL DEFAULT 'java' COMMENT 'java/python3/cpp',
  source_code    MEDIUMTEXT NOT NULL COMMENT '提交的源码',
  status         VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                 COMMENT 'PENDING排队 RUNNING判题中 AC WA TLE MLE CE RE SE',
  score          INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '通过用例数',
  total          INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '用例总数',
  time_used_ms   INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最大单用例耗时',
  memory_used_kb INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最大单用例内存，demo 阶段先留 0',
  error_msg      TEXT NULL COMMENT 'CE/RE 的详细错误，给用户看',
  create_time    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_user_problem (user_id, problem_id, id),
  KEY idx_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OJ 提交记录';
