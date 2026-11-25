-- 2026-11-25 刷题模块文档上传导入建表：qb_import_batch + qb_import_batch_item
--
-- 目标库：本地 MySQL（localhost:3306）的 ezpracticeexercises。
--
-- 设计说明：
-- 1. 不改动 qb_subject / qb_question / qb_question_option / qb_paper 原有列，
--    避免破坏 QbEntityColumnTest 的 12 列约束及既有 SQL。
-- 2. 题目归属由所属 qb_subject.owner_id 决定（1=公共题库，其他=用户个人题库）；
--    本两张表仅负责记录文档上传导入历史、解析警告、关联套卷及批次-题目映射，
--    用于「按导入批次筛选题目」与「一键撤回整批导入」。
-- 3. 与既有 qb_* 表保持一致：无物理外键，utf8mb4 / utf8mb4_unicode_ci。

CREATE TABLE `qb_import_batch` (
  `id`             bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '导入批次ID',
  `owner_id`       bigint unsigned NOT NULL COMMENT '所属用户ID，逻辑关联 users_dev.id',
  `subject_id`     bigint unsigned NOT NULL COMMENT '落入的个人科目ID，逻辑关联 qb_subject.id',
  `paper_id`       bigint unsigned DEFAULT NULL COMMENT '若勾选同步打包套卷，记录生成的 qb_paper.id',
  `file_name`      varchar(255)    NOT NULL COMMENT '上传的原始文件名',
  `file_type`      varchar(16)     NOT NULL COMMENT '文件类型：md/txt/docx/doc',
  `file_size`      bigint unsigned NOT NULL DEFAULT '0' COMMENT '文件大小（字节）',
  `total_parsed`   int unsigned    NOT NULL DEFAULT '0' COMMENT '解析识别到的题目块总数',
  `imported_count` int unsigned    NOT NULL DEFAULT '0' COMMENT '成功入库题目数',
  `skipped_count`  int unsigned    NOT NULL DEFAULT '0' COMMENT '跳过的异常题目块数',
  `warnings_json`  json            DEFAULT NULL COMMENT '解析警告列表（JSON字符串数组）',
  `status`         varchar(16)     NOT NULL DEFAULT 'SUCCESS' COMMENT '批次状态：SUCCESS/PARTIAL/FAILED',
  `created_at`     datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_import_owner_time` (`owner_id`, `created_at`),
  KEY `idx_import_subject` (`subject_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题库-文档上传导入批次表';

CREATE TABLE `qb_import_batch_item` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `batch_id`    bigint unsigned NOT NULL COMMENT '导入批次ID，逻辑关联 qb_import_batch.id',
  `question_id` bigint unsigned NOT NULL COMMENT '生成的题目ID，逻辑关联 qb_question.id',
  `seq_no`      int unsigned    NOT NULL COMMENT '在本次导入文档中的顺序号，从1开始',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_question` (`batch_id`, `question_id`),
  KEY `idx_question` (`question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题库-导入批次与题目关联表';
