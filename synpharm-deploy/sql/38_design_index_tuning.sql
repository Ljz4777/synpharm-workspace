-- =============================================
-- AI 药物分子设计平台 · 索引调优（可选）
-- 版本：v1.0  依赖：36_ 已执行
-- 来源：docs/design/AI药物分子设计平台-详细设计书.md §3.5 ⑤、§3.7
--
-- 本脚本为**可选**：基础查询（排行榜、血缘、知识库子树、实测准确率）所需的索引
-- 已在 11_~36_ 中建立。此处仅补充「按 JSON 明细过滤」的函数索引与归档辅助索引，
-- 在数据量增长到需要时再执行即可。
--
-- 全部使用「存在性守卫」，可重复执行。
-- =============================================
USE synpharm_core;

DROP PROCEDURE IF EXISTS sp_design_index_tuning;

DELIMITER $$
CREATE PROCEDURE sp_design_index_tuning()
BEGIN
    DECLARE n INT DEFAULT 0;

    -- ① 按 PAINS/BRENK 命中的 SMARTS 过滤（detail_json 为数组，取首元素）
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'evaluation_result'
                AND COLUMN_NAME = 'risk_first_smarts');
    IF n = 0 THEN
        ALTER TABLE evaluation_result
            ADD COLUMN risk_first_smarts VARCHAR(120)
                GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$[0].smarts'))) VIRTUAL
                COMMENT '函数索引载体：风险明细首条 SMARTS',
            ADD KEY idx_risk_smarts (project_id, risk_first_smarts);
    END IF;

    -- ② 归档辅助：design_job 按完成时间清理（COMPLETED 早于 N 天）
    SET n := (SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'design_job'
                AND INDEX_NAME = 'idx_completed');
    IF n = 0 THEN
        ALTER TABLE design_job
            ADD KEY idx_completed (status, completed_at);
    END IF;

    -- ③ 归档辅助：evaluation_result 按 run 批量清理/搬迁
    SET n := (SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'evaluation_result'
                AND INDEX_NAME = 'idx_run_created');
    IF n = 0 THEN
        ALTER TABLE evaluation_result
            ADD KEY idx_run_created (run_id, created_at);
    END IF;

    -- ④ molecule_edit_log 按时间归档
    SET n := (SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'molecule_edit_log'
                AND INDEX_NAME = 'idx_created');
    IF n = 0 THEN
        ALTER TABLE molecule_edit_log
            ADD KEY idx_created (created_at);
    END IF;
END$$
DELIMITER ;

CALL sp_design_index_tuning();
DROP PROCEDURE sp_design_index_tuning;

-- 分区方案（§3.7：evaluation_result 达 1000 万行前必须落地）不在本脚本内 ——
-- MySQL 8 的分区要求分区键属于每个唯一键，与 uk_run_mol_metric 冲突，
-- 需改为「按 run_id 分区 + 去掉唯一键改由应用层幂等」或「历史数据搬 archive 表」，
-- 二者取舍见 docs/design/detailed/05-开放问题解决方案.md Q5。
