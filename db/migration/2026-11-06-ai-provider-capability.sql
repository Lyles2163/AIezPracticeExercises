-- AI 服务商能力扩展：供应商声明"能提供哪些服务"，模型声明"自己是什么模型"。
-- 两张表仍是全项目唯一的 AI 模型数据中心，不新增表。
-- 老数据（含升级前写入的行）一律落 CHAT，行为与升级前完全一致。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，本脚本只执行一次。

ALTER TABLE ai_user_model_provider
    ADD COLUMN service_types VARCHAR(64) NOT NULL DEFAULT 'CHAT'
        COMMENT '服务能力，CSV：CHAT,IMAGE,RAG' AFTER base_url;

ALTER TABLE ai_user_model
    ADD COLUMN model_type VARCHAR(32) NOT NULL DEFAULT 'CHAT'
        COMMENT '模型类型：CHAT/IMAGE/EMBEDDING/RERANK' AFTER display_name;
