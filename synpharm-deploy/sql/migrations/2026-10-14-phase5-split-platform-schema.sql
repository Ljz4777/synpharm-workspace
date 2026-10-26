-- =============================================================================
-- 阶段 5：把 design_tool 从 synpharm 拆到 synpharm_platform
-- 日期：2026-10-14
--
-- 与阶段 3（file_asset → synpharm_asset）同构，但多一处**必须注意的差异**：
--
-- 阶段 3 时指向 file_asset 的 3 条外键全是**跨 schema** 的，2A 已经删过；
-- 而 design_tool 的两条外键是**同 schema** 的 —— design_tool 与 design_job /
-- evaluation_result 原先都住在 synpharm 里，同库外键完全合法，所以一直没被清理。
-- 一旦 design_tool 搬到 synpharm_platform，这两条就**变成跨 schema 外键**，必须删除：
--     fk_job_tool  on synpharm.design_job.tool_id
--     fk_er_tool   on synpharm.evaluation_result.source_tool_id
-- 这正是「拆分会把合法的同库约束变成违规的跨库约束」的典型例子 —— 拆分时要按
-- 「引用方的列是否还和引用对象同库」重新审视每一张表，而不是只看历史上是否已清过。
--
-- 全新部署（数据卷为空）**不需要**本脚本：32_design_tool.sql 已改为 USE synpharm_platform，
-- 37_design_seed_data.sql 的种子已写入 synpharm_platform.design_tool，
-- 且 33_/36_ 两个建表脚本里那两条外键本就标注为「永久不建」。
-- 本脚本只用于**已存在的库**。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 独立账号：synpharm_platform，最小授权（只给本 schema，不给 GRANT OPTION）
--    见 sql/00_create_accounts.sh（已放开该行），口令来自 .env 的 PLATFORM_DB_PASSWORD
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 2. 删掉两条即将变成跨 schema 的外键（先查后删）
-- -----------------------------------------------------------------------------
SELECT CONCAT('FK ', CONSTRAINT_NAME, ' on ', TABLE_SCHEMA, '.', TABLE_NAME, '.', COLUMN_NAME) AS before_drop
FROM information_schema.KEY_COLUMN_USAGE
WHERE REFERENCED_TABLE_NAME = 'design_tool';

ALTER TABLE synpharm.design_job         DROP FOREIGN KEY fk_job_tool;
ALTER TABLE synpharm.evaluation_result  DROP FOREIGN KEY fk_er_tool;

-- -----------------------------------------------------------------------------
-- 3. 把表建到新 schema（结构见 sql/32_design_tool.sql，此处不复制以免两处漂移）
--    执行顺序：先跑 32_design_tool.sql（已指向 synpharm_platform）再继续本脚本
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 4. 搬数据 + 删旧表
--    注：本表的种子数据也由 37_design_seed_data.sql 写入；用 SELECT * 搬是因为
--    新旧表由同一个 DDL 脚本创建，列顺序必然一致。
-- -----------------------------------------------------------------------------
INSERT INTO synpharm_platform.design_tool SELECT * FROM synpharm.design_tool;
DROP TABLE synpharm.design_tool;

-- -----------------------------------------------------------------------------
-- 5. 校验（都应返回 0 / 期望值）
-- -----------------------------------------------------------------------------
-- 5.1 全库不应再有指向 design_tool 的外键
SELECT CONCAT('FK_TO_DESIGN_TOOL_LEFT=', COUNT(*)) AS check_no_fk
FROM information_schema.KEY_COLUMN_USAGE
WHERE REFERENCED_TABLE_NAME = 'design_tool';

-- 5.2 旧表应已不存在于 synpharm
SELECT CONCAT('OLD_GONE=', COUNT(*)) AS check_old_gone
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'synpharm' AND TABLE_NAME = 'design_tool';

-- 5.3 新表应有数据（现网 12 行）
SELECT CONCAT('NEW_ROWS=', COUNT(*)) AS check_rows
FROM synpharm_platform.design_tool;

-- 5.4 全库跨 schema 外键必须为 0（拆分铁律）
SELECT CONCAT('ALL_FK_CROSS_SCHEMA=', COUNT(*)) AS check_cross_schema
FROM information_schema.KEY_COLUMN_USAGE
WHERE REFERENCED_TABLE_NAME IS NOT NULL AND REFERENCED_TABLE_SCHEMA <> TABLE_SCHEMA;

-- 5.5 引用方的列必须还在（只删外键，不删列）
SELECT CONCAT('TOOL_ID_COLUMNS_KEPT=', COUNT(*)) AS check_columns_kept
FROM information_schema.COLUMNS
WHERE COLUMN_NAME LIKE '%tool%' AND TABLE_SCHEMA IN ('synpharm', 'synpharm_design');
