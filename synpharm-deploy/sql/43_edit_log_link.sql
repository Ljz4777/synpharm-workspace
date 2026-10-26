-- ===========================================================================
-- 43_edit_log_link.sql —— 补上「生成日志 → 候选」的关联键（阶段 D 实施时发现）
-- ===========================================================================
-- 适用范围：synpharm_core
-- 幂等：全部走 information_schema 守卫，可重复执行
-- 执行账号：**必须 root**
--
-- 问题：41_generation.sql 建 generation_candidate_log 时没有存 temp_ref，
-- 而 S5~S7 写日志时候选还没落库（没有 id），于是 candidate_id 永远是 NULL。
-- 结果：诊断页能看到「哪个分子被筛掉了、为什么」，却点不到对应候选。
--
-- 修法：加 temp_ref 列；S8 落库后按 (run_id, temp_ref) 回填 candidate_id。
-- 注意：本修复只对**此后新跑的批次**生效。此前写入的历史日志没有 temp_ref，
-- 无法安全回填（同一 run 内 SMILES 可能重复，按内容猜测会错配），故保持 NULL。
-- ===========================================================================

USE synpharm_core;

DROP PROCEDURE IF EXISTS sp_add_column_if_missing;
DELIMITER $$
CREATE PROCEDURE sp_add_column_if_missing(IN tbl VARCHAR(64), IN col VARCHAR(64), IN col_ddl TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
        SET @ddl = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN ', col_ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- ---------------------------------------------------------------------------
-- 1. temp_ref
-- ---------------------------------------------------------------------------
CALL sp_add_column_if_missing('generation_candidate_log', 'temp_ref',
    'temp_ref VARCHAR(64) NULL COMMENT ''本批内临时编号；S8 落库后按它回填 candidate_id''');

-- ---------------------------------------------------------------------------
-- 2. 回填索引：(run_id, temp_ref) 是回填 UPDATE 的检索路径
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_log_temp_ref_index;
DELIMITER $$
CREATE PROCEDURE sp_log_temp_ref_index()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'generation_candidate_log'
                     AND INDEX_NAME = 'idx_run_temp_ref') THEN
        ALTER TABLE generation_candidate_log ADD KEY idx_run_temp_ref (run_id, temp_ref);
    END IF;
END$$
DELIMITER ;
CALL sp_log_temp_ref_index();
DROP PROCEDURE sp_log_temp_ref_index;

DROP PROCEDURE IF EXISTS sp_add_column_if_missing;

-- ---------------------------------------------------------------------------
-- 3. 验收查询
-- ---------------------------------------------------------------------------
-- 3.1 新列
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA='synpharm_core' AND TABLE_NAME='generation_candidate_log'
--    AND COLUMN_NAME='temp_ref';
--
-- 3.2 回填效果（跑过一次生成后：KEPT 的行应有 candidate_id）
-- SELECT stage, outcome, COUNT(*) AS n, SUM(candidate_id IS NOT NULL) AS linked
--   FROM synpharm_core.generation_candidate_log GROUP BY stage, outcome;
