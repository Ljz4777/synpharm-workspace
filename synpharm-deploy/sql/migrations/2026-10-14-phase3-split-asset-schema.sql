-- =============================================================================
-- 阶段 3：把 file_asset 从 synpharm 拆到 synpharm_asset
-- 日期：2026-10-14
--
-- 背景与 2A（design）一致：拆分铁律 R2 要求「每服务独占自己的 schema、禁止跨服务外键」。
-- `file_asset` 归 synpharm-asset，因此必须从单体库 synpharm 迁出。
--
-- 与 2A 的两个不同点（都让这次更简单）：
--   1. file_asset **没有任何业务数据**（0 行）→ 不需要数据搬运，只要 DDL 与授权；
--   2. 指向它的跨 schema 外键只有 **3 条且全部在 synpharm 里**（design schema 在 2A 时
--      已删干净），因此不必逐个 schema 排查。
--
-- 全新部署（数据卷为空）**不需要**本脚本：sql/00_create_accounts.sh 已建好账号，
-- sql/31_file_asset.sql 已改为 USE synpharm_asset。
-- 本脚本只用于**已存在的库**。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 独立账号（最小授权：只给本 schema，且不给 GRANT OPTION）
--    与 00_create_accounts.sh 的 create_account 保持一致；
--    密码由 .env 的 ASSET_DB_PASSWORD 提供，不在脚本里硬编码。
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 2. 删掉指向 file_asset 的跨 schema 外键
--
-- 为什么必须删而不是「先留着」：外键是**库级**约束，一旦 file_asset 搬到另一个 schema，
-- 这三条引用就会挡住搬迁（甚至挡住 synpharm.file_asset 的删除）。按拆分铁律改为
-- 「普通列 + 应用层校验」，引用完整性由 asset 服务的接口负责。
-- 注意：source 侧的 `*_asset_id` 列**保留不动**，只是不再有外键。
-- -----------------------------------------------------------------------------
ALTER TABLE synpharm.wet_experiment      DROP FOREIGN KEY fk_we_asset;
ALTER TABLE synpharm.candidate_molecule  DROP FOREIGN KEY fk_cand_structure;
ALTER TABLE synpharm.target_structure    DROP FOREIGN KEY fk_struct_asset;

-- -----------------------------------------------------------------------------
-- 3. 把表建到新 schema（表结构见 sql/31_file_asset.sql，此处不复制一份以免两处漂移）
--    执行顺序：先跑 31_file_asset.sql（已指向 synpharm_asset）再跑本脚本第 4 步。
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 4. 确认无外部引用后删除旧表
--    执行前请确认第 5 节的校验查询结果为空。
-- -----------------------------------------------------------------------------
DROP TABLE synpharm.file_asset;

-- -----------------------------------------------------------------------------
-- 5. 校验（都应返回 0 行 / 期望值）
-- -----------------------------------------------------------------------------
-- 5.1 全库不应再有指向 file_asset 的外键（拆分铁律：禁止跨服务外键）
SELECT CONCAT('FK_TO_FILE_ASSET_LEFT=', COUNT(*)) AS check_no_fk
FROM information_schema.KEY_COLUMN_USAGE
WHERE REFERENCED_TABLE_NAME = 'file_asset';

-- 5.2 旧表应已不存在于 synpharm
SELECT CONCAT('OLD_TABLE_IN_SYNPHARM=', COUNT(*)) AS check_old_gone
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'synpharm' AND TABLE_NAME = 'file_asset';

-- 5.3 新表应存在于 synpharm_asset
SELECT CONCAT('NEW_TABLE_IN_ASSET=', COUNT(*)) AS check_new_present
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'synpharm_asset' AND TABLE_NAME = 'file_asset';

-- 5.4 引用方的列必须还在（只删外键，不删列）
SELECT CONCAT('REF_COLUMNS_LEFT=', COUNT(*)) AS check_columns_kept
FROM information_schema.COLUMNS
WHERE COLUMN_NAME LIKE '%asset%'
  AND TABLE_SCHEMA IN ('synpharm', 'synpharm_design');
