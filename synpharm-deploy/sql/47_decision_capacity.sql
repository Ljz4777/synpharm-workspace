-- ===========================================================================
-- 47_decision_capacity.sql —— decision_record 补「名额」列（阶段 E10 前端实施时发现）
-- ===========================================================================
-- 适用范围：synpharm_core
-- 幂等：走 information_schema 守卫，可重复执行
-- 执行账号：**必须 root**
--
-- 问题：名额 = min(档位, 湿实验余额) 是 D5 算出来的**派生值**，当初只放在创建接口的
-- 响应里，没有落库。于是 GET /decisions/{id} 读历史记录时拿不到它 ——
-- 前端只知道「选中了 1 个」，说不出「名额是 1」还是「名额是 3 但只选出 1 个」。
--
-- 为什么不改成读取时重算：重算要用**当前**项目配置（decision_capacity_mode /
-- decision_capacity_value / 湿实验余额）。项目配置会变，历史决策的解释就被悄悄
-- 改写了 —— 这正是「确认即不可改」要防的事。
--
-- 所以：像 weights_snapshot / rule_version 一样，名额属于决策快照的一部分，落库。
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
-- 1. capacity：本次实际名额（档位与湿实验余额取小，且至少 1）
-- ---------------------------------------------------------------------------
CALL sp_add_column_if_missing('decision_record', 'capacity',
    'capacity INT NULL COMMENT ''本次实际名额 = min(档位, 湿实验余额)；落库以保证历史可解释''');

DROP PROCEDURE sp_add_column_if_missing;

-- ---------------------------------------------------------------------------
-- 2. 验收查询
-- ---------------------------------------------------------------------------
-- 2.1 新列
-- SELECT COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA='synpharm_core' AND TABLE_NAME='decision_record' AND COLUMN_NAME='capacity';
--
-- 2.2 名额与选中的关系（capacity 为空的是本列上线之前的旧记录，无法回溯）
-- SELECT decision_no, status, capacity, selected_count, backup_count, rejected_count
--   FROM synpharm_core.decision_record ORDER BY id DESC LIMIT 10;
