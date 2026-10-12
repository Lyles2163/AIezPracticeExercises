-- 2026-10-12 注册手机验证码改造：users_dev 增加 phone
--
-- 执行前提（已用 SHOW CREATE TABLE users_dev 实测确认，2026-10-12）：
--   1. 库中不存在 phone 列；
--   2. 不存在名为 uk_users_dev_phone 的索引。
--   MySQL 的 ADD COLUMN / ADD UNIQUE KEY 都不支持 IF NOT EXISTS，重复执行会直接报错，
--   所以重跑前先查 information_schema.COLUMNS 与 STATISTICS。
--
-- 为什么可以安全加唯一索引：新增列的 11 行存量数据全为 NULL，
-- 而 MySQL 的唯一索引允许多行 NULL（spec D9）。
--
-- 注意：username 早已有唯一索引 uk_users_username，本文件不动它。

ALTER TABLE users_dev
    ADD COLUMN phone VARCHAR(20) NULL AFTER last_login_ip,
    ADD UNIQUE KEY uk_users_dev_phone (phone);
