-- =============================================================================
-- 阶段 4 收尾（cutover）：从单体库 synpharm 删除预测域 10 张旧表
-- 日期：2026-10-15
--
-- 这是阶段 4 里**唯一破坏性**的一步，执行前提（缺一不可）：
--   1. synpharm-predict 服务已就绪并通过端到端验证（含读到的确实是 synpharm_predict）；
--   2. 网关已把 5 个前缀指向 predict，并已用「只在 synpharm_predict 插入的探针行」
--      证明流量确实落在新服务上；
--   3. 单体侧预测域代码已删除（本轮删了 86 个类 + 3 个 mapper XML + SystemController），
--      因此不会再有进程读写 synpharm 侧的这批表。
--
-- 与 2026-10-15-phase4-split-predict-schema.sql 的分工：
--   那一份是**非破坏性**的准备（把表复制到 synpharm_predict，synpharm 侧原样保留）；
--   本份是破坏性的收尾（synpharm 侧不再需要，删掉以免留下两份可写副本）。
--
-- 为什么必须先删 fk_rpt_session（第 1 节）：
--   synpharm.design_report 上残留一条外键指向 synpharm.agent_session。它是**旧版 DDL
--   建立后从未重建**的存量表带过来的 —— 34_design_report.sql 早已删掉该外键，但 DDL
--   改动只影响全新部署（全新库里没有它，见下）。两者当下同在 synpharm 所以合法，
--   一旦要 DROP agent_session，这条外键就会挡住（ERROR 3730）。
--   删掉它是安全的：设计域已由 synpharm_design 承载，单体也早已没有设计域代码。
--
-- 全新部署（数据卷为空）**不需要**本脚本：sql/03_~10_、26_~29_ 已全部指向
-- synpharm_predict，synpharm 里不会再建这批表。
-- 幂等：全部使用 DROP ... IF EXISTS。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 先断开指向 agent_session 的存量外键（否则第 2 节删不掉 agent_session）
-- -----------------------------------------------------------------------------
SET @fk_exists := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = 'synpharm' AND TABLE_NAME = 'design_report'
      AND CONSTRAINT_NAME = 'fk_rpt_session' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @ddl := IF(@fk_exists > 0,
    'ALTER TABLE synpharm.design_report DROP FOREIGN KEY fk_rpt_session',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 2. 删除 synpharm 侧 10 张旧表
--    按依赖倒序：先删引用方，再删被引用方（再叠加关外键检查兜底）。
-- -----------------------------------------------------------------------------
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS synpharm.tool_invocation_log;
DROP TABLE IF EXISTS synpharm.agent_report;
DROP TABLE IF EXISTS synpharm.agent_step;
DROP TABLE IF EXISTS synpharm.agent_session;
DROP TABLE IF EXISTS synpharm.ddi_supported_drug;
DROP TABLE IF EXISTS synpharm.batch_task_item;
DROP TABLE IF EXISTS synpharm.batch_task;
DROP TABLE IF EXISTS synpharm.user_favorite;
DROP TABLE IF EXISTS synpharm.predict_result;
DROP TABLE IF EXISTS synpharm.predict_task;

SET FOREIGN_KEY_CHECKS = 1;

-- -----------------------------------------------------------------------------
-- 3. 核验清单（括号内为期望值）
-- -----------------------------------------------------------------------------
-- SELECT COUNT(*) FROM information_schema.tables
--   WHERE table_schema='synpharm'
--     AND table_name IN ('predict_task','predict_result','user_favorite','batch_task',
--                        'batch_task_item','ddi_supported_drug','agent_session',
--                        'agent_step','agent_report','tool_invocation_log');        -- 0
-- SELECT COUNT(*) FROM information_schema.tables
--   WHERE table_schema='synpharm_predict';                                          -- 10
-- SELECT COUNT(*) FROM synpharm_predict.ddi_supported_drug;                         -- 1323
-- SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
--   WHERE CONSTRAINT_NAME='fk_rpt_session';                                         -- 0
-- SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
--   WHERE REFERENCED_TABLE_NAME IS NOT NULL
--     AND TABLE_SCHEMA<>REFERENCED_TABLE_SCHEMA;                                    -- 0（全库无跨 schema 外键）
