-- =============================================================================
-- 阶段 4：把预测域 10 张表从 synpharm 拆到 synpharm_predict
-- 日期：2026-10-15
--
-- 背景：拆分铁律 R2 要求「每服务独占自己的 schema、禁止跨服务外键」。预测域归
-- synpharm-predict，因此这 10 张表必须从单体库 synpharm 迁出：
--   predict_task / predict_result / user_favorite / batch_task / batch_task_item
--   / ddi_supported_drug / agent_session / agent_step / agent_report / tool_invocation_log
--
-- 与 2A(design) / 3(asset) / 5(platform) 的关键不同 —— **本次是非破坏性的**：
--   预测域是唯一「单体已有实现、且正在对外服务」的域。只要表还留在 synpharm，
--   单体就仍能提供 /api/predict/**。因此本脚本**只复制、不删除**：在 synpharm_predict
--   里建出同样的表并灌入当前数据，让待建的 predict 服务能先跑起来、接受端到端验证。
--   等验证通过、网关切流之后，再由 cutover 脚本删除 synpharm 侧的旧表与单体侧代码。
--
-- 外键（本域最省事的一点）：涉及的表间外键**全部在域内** ——
--   predict_result.task_id            -> predict_task
--   user_favorite.result_id           -> predict_result
--   batch_task_item.batch_id          -> batch_task
--   batch_task_item.result_id         -> predict_result
--   agent_step.session_id             -> agent_session
--   agent_report.session_id           -> agent_session
--   tool_invocation_log.session_id    -> agent_session
--   tool_invocation_log.step_id       -> agent_step
--   10 张表整体搬迁后仍在同一个 schema 内，所以这 8 条**全部保留**，无需降级为普通列。
--   这与阶段 5 的 design_tool 恰好相反：那 2 条是「同库时合法、一搬迁就违规」。
--
-- 阶段 1 已清掉的历史包袱：predict_task / predict_result / user_favorite / batch_task /
--   agent_session 原本各有 1 条指向 synpharm.sys_user 的外键，已在拆 auth 时删除
--   （见 2026-10-14-phase1-split-auth-schema.sql 第 2 节）。现在只剩 user_id 普通列，正确。
--
-- 迁移前置遗留（**属 cutover 阶段，不是本脚本的职责**）：
--   synpharm.design_report 上残留一条 fk_rpt_session -> synpharm.agent_session。
--   它来自「旧 DDL 建好后从未重建」的存量表：34_design_report.sql 早已删掉该外键，
--   但 DDL 改动只影响全新部署，存量表不会自动跟随。两者当下同在 synpharm 所以合法，
--   一旦 agent_session 迁走就会变成悬空引用并挡住删除，故 cutover 前必须先行删除。
--
-- 全新部署（数据卷为空）**不需要**本脚本：届时表的归属见 sql/03_~10_、26_~29_。
-- 本脚本只用于**已存在的库**。
-- 幂等：可重复执行（第 2 节先 DROP 目标表再重建，第 3 节按列重新复制）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 目标 schema 兜底
--    独立账号由 sql/00_create_accounts.sh 创建；口令取自 .env 的 PREDICT_DB_PASSWORD，
--    绝不写进源码。
-- -----------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS synpharm_predict
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 2. 在 synpharm_predict 建表
--
--    DDL 由 `mysqldump --no-data` 从 synpharm 逐表导出，保证与**线上存量表**逐字一致。
--    刻意不手抄 sql/03_ 等源脚本：存量表与源脚本可能已经漂移（例：存量的
--    synpharm.design_report 至今仍带着 fk_rpt_session，而源脚本早已没有它），
--    以线上为准才能保证 predict 服务读到的结构与单体当前读到的一模一样。
--
--    先关外键检查：mysqldump 按表名字母序输出，agent_report / agent_step 会排在
--    agent_session 之前，直接建会因外键指向不存在的表而失败。
-- -----------------------------------------------------------------------------
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS synpharm_predict.tool_invocation_log;
DROP TABLE IF EXISTS synpharm_predict.agent_report;
DROP TABLE IF EXISTS synpharm_predict.agent_step;
DROP TABLE IF EXISTS synpharm_predict.agent_session;
DROP TABLE IF EXISTS synpharm_predict.ddi_supported_drug;
DROP TABLE IF EXISTS synpharm_predict.batch_task_item;
DROP TABLE IF EXISTS synpharm_predict.batch_task;
DROP TABLE IF EXISTS synpharm_predict.user_favorite;
DROP TABLE IF EXISTS synpharm_predict.predict_result;
DROP TABLE IF EXISTS synpharm_predict.predict_task;

USE synpharm_predict;

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `predict_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ä»»åŠ¡ID',
  `task_no` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'ä»»åŠ¡ç¼–å·',
  `user_id` bigint NOT NULL COMMENT 'ç”¨æˆ·ID',
  `predict_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'é¢„æµ‹ç±»åž‹ï¼šppi-è›‹ç™½è´¨ç›¸äº’ä½œç”¨ dti-è¯ç‰©é¶ç‚¹ç›¸äº’ä½œç”¨ ddi-è¯ç‰©ç›¸äº’ä½œç”¨',
  `input_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'è¾“å…¥ç±»åž‹ï¼šsmiles/pdb/uniprot/csv',
  `input_value` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'è¾“å…¥å€¼',
  `file_url` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'ä¸Šä¼ æ–‡ä»¶URL',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pending' COMMENT 'çŠ¶æ€ï¼špending-å¾…å¤„ç† running-è¿›è¡Œä¸­ completed-å·²å®Œæˆ failed-å¤±è´¥ cancelled-å·²å–æ¶ˆ',
  `progress` int NOT NULL DEFAULT '0' COMMENT 'è¿›åº¦ç™¾åˆ†æ¯” 0-100',
  `error_message` text COLLATE utf8mb4_unicode_ci COMMENT 'é”™è¯¯ä¿¡æ¯',
  `ai_task_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'AIæœåŠ¡ä»»åŠ¡ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  `started_at` datetime DEFAULT NULL COMMENT 'å¼€å§‹æ—¶é—´',
  `completed_at` datetime DEFAULT NULL COMMENT 'å®Œæˆæ—¶é—´',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT 'é€»è¾‘åˆ é™¤',
  PRIMARY KEY (`id`),
  UNIQUE KEY `task_no` (`task_no`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_task_no` (`task_no`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_predict_type` (`predict_type`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='é¢„æµ‹ä»»åŠ¡è¡¨';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `predict_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ç»“æžœID',
  `result_no` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'ç»“æžœç¼–å·',
  `task_id` bigint NOT NULL COMMENT 'ä»»åŠ¡ID',
  `user_id` bigint NOT NULL COMMENT 'ç”¨æˆ·ID',
  `target_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'é¶ç‚¹ID',
  `target_name` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'é¶ç‚¹åç§°',
  `ligand_smiles` text COLLATE utf8mb4_unicode_ci COMMENT 'é…ä½“SMILES',
  `binding_affinity` decimal(10,4) DEFAULT NULL COMMENT 'ç»“åˆäº²å’ŒåŠ›',
  `confidence_score` decimal(5,4) DEFAULT NULL COMMENT 'ç½®ä¿¡åº¦ 0-1',
  `confidence_level` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'ç½®ä¿¡ç­‰çº§ï¼šhigh/medium/low',
  `prediction_data` json DEFAULT NULL COMMENT 'å®Œæ•´é¢„æµ‹æ•°æ®',
  `interactions` json DEFAULT NULL COMMENT 'ç›¸äº’ä½œç”¨è¯¦æƒ…JSON',
  `dataset_source` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'æ•°æ®é›†æ¥æº',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT 'é€»è¾‘åˆ é™¤',
  PRIMARY KEY (`id`),
  UNIQUE KEY `result_no` (`result_no`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_result_no` (`result_no`),
  KEY `idx_confidence` (`confidence_score`),
  KEY `idx_target_id` (`target_id`),
  KEY `idx_created_at` (`created_at`),
  CONSTRAINT `predict_result_ibfk_1` FOREIGN KEY (`task_id`) REFERENCES `predict_task` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='é¢„æµ‹ç»“æžœè¡¨';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_favorite` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'æ”¶è—ID',
  `user_id` bigint NOT NULL COMMENT 'ç”¨æˆ·ID',
  `result_id` bigint NOT NULL COMMENT 'ç»“æžœID',
  `note` text COLLATE utf8mb4_unicode_ci COMMENT 'å¤‡æ³¨',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT 'é€»è¾‘åˆ é™¤',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_result` (`user_id`,`result_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_result_id` (`result_id`),
  CONSTRAINT `user_favorite_ibfk_2` FOREIGN KEY (`result_id`) REFERENCES `predict_result` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ç”¨æˆ·æ”¶è—è¡¨';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `batch_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ä¸»é”®ID',
  `batch_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'å”¯ä¸€æ‰¹æ¬¡ID (UUID)',
  `user_id` bigint NOT NULL COMMENT 'å…³è”ç”¨æˆ·ID',
  `file_path` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'åŽŸå§‹CSVæ–‡ä»¶å­˜å‚¨è·¯å¾„',
  `total_count` int NOT NULL DEFAULT '0' COMMENT 'æ€»æ•°æ®æ¡æ•°',
  `success_count` int NOT NULL DEFAULT '0' COMMENT 'æˆåŠŸå¤„ç†æ¡æ•°',
  `fail_count` int NOT NULL DEFAULT '0' COMMENT 'å¤±è´¥æ¡æ•°',
  `progress` decimal(5,2) NOT NULL DEFAULT '0.00' COMMENT 'å½“å‰è¿›åº¦ (0.00-100.00)',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0:PENDING, 1:PROCESSING, 2:SUCCESS, 3:FAIL',
  `algo_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT 'ç®—æ³•ç±»åž‹ DTI/PPI/DDI',
  `result_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'ç»“æžœæ–‡ä»¶ä¸‹è½½åœ°å€',
  `error_msg` text COLLATE utf8mb4_unicode_ci COMMENT 'æ‰¹æ¬¡çº§é”™è¯¯ä¿¡æ¯',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'æ›´æ–°æ—¶é—´',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT 'é€»è¾‘åˆ é™¤ï¼š0æœªåˆ é™¤ 1å·²åˆ é™¤',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_id` (`batch_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='æ‰¹æ¬¡ä»»åŠ¡è¡¨';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `batch_task_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ä¸»é”®ID',
  `batch_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ‰¹æ¬¡IDï¼ˆbatch_task.batch_idï¼‰',
  `row_number` int NOT NULL COMMENT 'CSVè¡Œå·ï¼ˆä»Ž1å¼€å§‹ï¼‰',
  `input_value` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'è¯¥è¡ŒåŽŸå§‹è¾“å…¥',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0:PENDING, 1:PROCESSING, 2:SUCCESS, 3:FAIL',
  `result_id` bigint DEFAULT NULL COMMENT 'æˆåŠŸè¡Œå…³è”çš„é¢„æµ‹ç»“æžœIDï¼ˆpredict_result.idï¼‰',
  `error_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'å­—ç¬¦ä¸²é”™è¯¯ç ï¼ˆå¦‚ INVALID_SMILES / SEQUENCE_TOO_SHORTï¼‰',
  `error_message` text COLLATE utf8mb4_unicode_ci COMMENT 'è¡Œçº§é”™è¯¯ä¿¡æ¯',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'æ›´æ–°æ—¶é—´',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT 'é€»è¾‘åˆ é™¤ï¼š0æœªåˆ é™¤ 1å·²åˆ é™¤',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_row` (`batch_id`,`row_number`),
  KEY `idx_batch_id` (`batch_id`),
  KEY `idx_result_id` (`result_id`),
  CONSTRAINT `batch_task_item_ibfk_1` FOREIGN KEY (`batch_id`) REFERENCES `batch_task` (`batch_id`),
  CONSTRAINT `batch_task_item_ibfk_2` FOREIGN KEY (`result_id`) REFERENCES `predict_result` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='æ‰¹é‡ä»»åŠ¡æ˜Žç»†è¡¨';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ddi_supported_drug` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `drugbank_id` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'DrugBank IDï¼ŒDDI æ¨¡åž‹å›¾å†…èŠ‚ç‚¹æ ‡è¯†',
  `drug_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'è¯ç‰©åç§°',
  `smiles` varchar(1500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'åŽŸå§‹ SMILESï¼ˆå±•ç¤ºç”¨ï¼‰',
  `smiles_hash` char(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'è§„èŒƒåŒ– SMILES çš„ SHA-256ï¼Œç”¨äºŽåæŸ¥',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_drugbank_id` (`drugbank_id`),
  KEY `idx_smiles_hash` (`smiles_hash`),
  KEY `idx_drug_name` (`drug_name`)
) ENGINE=InnoDB AUTO_INCREMENT=1324 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DDI-LLM æ”¯æŒçš„è¯ç‰©ç™½åå•ï¼ˆè½¬å¯¼å¼æ¨¡åž‹èƒ½åŠ›è¾¹ç•Œï¼‰';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'ä¼šè¯ç¼–å· A+yyMMddHHmmss+éšæœº',
  `user_id` bigint NOT NULL COMMENT 'å½’å±žç”¨æˆ·',
  `goal` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'ç”¨æˆ·åŽŸå§‹ç›®æ ‡ï¼ˆè‡ªç„¶è¯­è¨€ï¼‰',
  `input_json` text COLLATE utf8mb4_unicode_ci COMMENT 'ç»“æž„åŒ–è¾“å…¥ï¼ˆSMILES/åºåˆ—ç­‰ï¼‰',
  `run_mode` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ONLINE' COMMENT 'ONLINE/OFFLINEï¼ˆç¦»çº¿èµ° RuleBasedLlmGatewayï¼‰',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PLANNING/EXECUTING/CRITIQUING/REPLANNING/REPORTING/COMPLETED/FAILED/CANCELLED',
  `plan_json` text COLLATE utf8mb4_unicode_ci COMMENT 'å½“å‰è½®æ¬¡çš„è®¡åˆ’ DAG',
  `conclusion_json` text COLLATE utf8mb4_unicode_ci COMMENT 'ç»¼åˆç»“è®ºï¼ˆå«ä¸€è‡´æ€§æ ‡è®°ä¸Žç½®ä¿¡åº¦ï¼‰',
  `confidence` decimal(5,4) DEFAULT NULL COMMENT 'ç»¼åˆç½®ä¿¡åº¦',
  `round_count` int NOT NULL DEFAULT '0',
  `max_rounds` int NOT NULL DEFAULT '3',
  `error_msg` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT 'é€»è¾‘åˆ é™¤ï¼š0æœªåˆ é™¤ 1å·²åˆ é™¤',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_no` (`session_no`),
  KEY `idx_user_status` (`user_id`,`status`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='æ™ºèƒ½ä½“ä¼šè¯';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_step` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `round_no` int NOT NULL DEFAULT '1' COMMENT 'ç¬¬å‡ è½®è¿­ä»£',
  `seq_no` int NOT NULL COMMENT 'è½®å†…åºå·ï¼ˆæ—¶é—´çº¿æŽ’åºç”¨ï¼‰',
  `agent_name` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Planner/Critic/Reporter/Executor/Evidence',
  `step_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'THOUGHT/ACTION/OBSERVATION/VERDICT',
  `thought` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'LLM æŽ¨ç†æ‘˜è¦',
  `action` varchar(60) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'å·¥å…·å',
  `action_input` text COLLATE utf8mb4_unicode_ci COMMENT 'å·¥å…·å…¥å‚ JSON',
  `observation` text COLLATE utf8mb4_unicode_ci COMMENT 'å·¥å…·è¾“å‡ºæ‘˜è¦',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SUCCESS/FAILED/TIMEOUT/SKIPPED',
  `latency_ms` int DEFAULT NULL,
  `token_usage` int NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_round_seq` (`session_id`,`round_no`,`seq_no`),
  KEY `idx_session_round_seq` (`session_id`,`round_no`,`seq_no`),
  CONSTRAINT `fk_astep_session` FOREIGN KEY (`session_id`) REFERENCES `agent_session` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='æ™ºèƒ½ä½“æ‰§è¡Œæ­¥éª¤ï¼ˆæ—¶é—´çº¿ï¼‰';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_report` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `report_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ™ºèƒ½ä½“æŠ¥å‘Šç¼–å· AR+yyMMddHHmmss+éšæœº',
  `format` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'MARKDOWN' COMMENT 'MARKDOWN/JSON/PDF',
  `summary` text COLLATE utf8mb4_unicode_ci COMMENT 'ä¸€å¥è¯ç»“è®º',
  `content` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'æŠ¥å‘Šæ­£æ–‡',
  `evidence_json` text COLLATE utf8mb4_unicode_ci COMMENT 'ç»“è®ºâ†’step_id æº¯æºæ˜ å°„',
  `confidence` decimal(5,4) DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT 'é€»è¾‘åˆ é™¤ï¼š0æœªåˆ é™¤ 1å·²åˆ é™¤',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_no` (`report_no`),
  KEY `idx_session` (`session_id`),
  CONSTRAINT `fk_arpt_session` FOREIGN KEY (`session_id`) REFERENCES `agent_session` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='æ™ºèƒ½ä½“æŠ¥å‘Š';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tool_invocation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `step_id` bigint DEFAULT NULL,
  `tool_name` varchar(60) COLLATE utf8mb4_unicode_ci NOT NULL,
  `input_digest` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'å…¥å‚æ‘˜è¦ï¼ˆè„±æ•ï¼Œä¸è½å®Œæ•´åºåˆ—/SMILES å¤§æ–‡æœ¬ï¼‰',
  `output_digest` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'å‡ºå‚æ‘˜è¦',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SUCCESS/FAILED/TIMEOUT',
  `latency_ms` int DEFAULT NULL,
  `error_msg` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_tool` (`session_id`,`tool_name`),
  KEY `idx_step` (`step_id`),
  CONSTRAINT `fk_til_session` FOREIGN KEY (`session_id`) REFERENCES `agent_session` (`id`),
  CONSTRAINT `fk_til_step` FOREIGN KEY (`step_id`) REFERENCES `agent_step` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='å·¥å…·è°ƒç”¨å®¡è®¡æ—¥å¿—';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


SET FOREIGN_KEY_CHECKS = 1;

-- -----------------------------------------------------------------------------
-- 3. 复制数据（非破坏性：synpharm 侧的原表与原数据一并保留）
--    实际行数：ddi_supported_drug 1323 / predict_task 3 / predict_result 2，其余为 0。
--    逐表显式列出列名，不依赖 `SELECT *` 的列序假设。
-- -----------------------------------------------------------------------------
USE synpharm_predict;

INSERT INTO synpharm_predict.`predict_task` (`id`, `task_no`, `user_id`, `predict_type`, `input_type`, `input_value`, `file_url`, `status`, `progress`, `error_message`, `ai_task_id`, `created_at`, `started_at`, `completed_at`, `deleted`)
  SELECT `id`, `task_no`, `user_id`, `predict_type`, `input_type`, `input_value`, `file_url`, `status`, `progress`, `error_message`, `ai_task_id`, `created_at`, `started_at`, `completed_at`, `deleted` FROM synpharm.`predict_task`;
INSERT INTO synpharm_predict.`predict_result` (`id`, `result_no`, `task_id`, `user_id`, `target_id`, `target_name`, `ligand_smiles`, `binding_affinity`, `confidence_score`, `confidence_level`, `prediction_data`, `interactions`, `dataset_source`, `created_at`, `deleted`)
  SELECT `id`, `result_no`, `task_id`, `user_id`, `target_id`, `target_name`, `ligand_smiles`, `binding_affinity`, `confidence_score`, `confidence_level`, `prediction_data`, `interactions`, `dataset_source`, `created_at`, `deleted` FROM synpharm.`predict_result`;
INSERT INTO synpharm_predict.`user_favorite` (`id`, `user_id`, `result_id`, `note`, `created_at`, `deleted`)
  SELECT `id`, `user_id`, `result_id`, `note`, `created_at`, `deleted` FROM synpharm.`user_favorite`;
INSERT INTO synpharm_predict.`batch_task` (`id`, `batch_id`, `user_id`, `file_path`, `total_count`, `success_count`, `fail_count`, `progress`, `status`, `algo_type`, `result_url`, `error_msg`, `create_time`, `update_time`, `deleted`)
  SELECT `id`, `batch_id`, `user_id`, `file_path`, `total_count`, `success_count`, `fail_count`, `progress`, `status`, `algo_type`, `result_url`, `error_msg`, `create_time`, `update_time`, `deleted` FROM synpharm.`batch_task`;
INSERT INTO synpharm_predict.`batch_task_item` (`id`, `batch_id`, `row_number`, `input_value`, `status`, `result_id`, `error_code`, `error_message`, `created_at`, `updated_at`, `deleted`)
  SELECT `id`, `batch_id`, `row_number`, `input_value`, `status`, `result_id`, `error_code`, `error_message`, `created_at`, `updated_at`, `deleted` FROM synpharm.`batch_task_item`;
INSERT INTO synpharm_predict.`ddi_supported_drug` (`id`, `drugbank_id`, `drug_name`, `smiles`, `smiles_hash`, `created_at`)
  SELECT `id`, `drugbank_id`, `drug_name`, `smiles`, `smiles_hash`, `created_at` FROM synpharm.`ddi_supported_drug`;
INSERT INTO synpharm_predict.`agent_session` (`id`, `session_no`, `user_id`, `goal`, `input_json`, `run_mode`, `status`, `plan_json`, `conclusion_json`, `confidence`, `round_count`, `max_rounds`, `error_msg`, `started_at`, `completed_at`, `deleted`, `created_at`, `updated_at`)
  SELECT `id`, `session_no`, `user_id`, `goal`, `input_json`, `run_mode`, `status`, `plan_json`, `conclusion_json`, `confidence`, `round_count`, `max_rounds`, `error_msg`, `started_at`, `completed_at`, `deleted`, `created_at`, `updated_at` FROM synpharm.`agent_session`;
INSERT INTO synpharm_predict.`agent_step` (`id`, `session_id`, `round_no`, `seq_no`, `agent_name`, `step_type`, `thought`, `action`, `action_input`, `observation`, `status`, `latency_ms`, `token_usage`, `created_at`)
  SELECT `id`, `session_id`, `round_no`, `seq_no`, `agent_name`, `step_type`, `thought`, `action`, `action_input`, `observation`, `status`, `latency_ms`, `token_usage`, `created_at` FROM synpharm.`agent_step`;
INSERT INTO synpharm_predict.`agent_report` (`id`, `session_id`, `report_no`, `format`, `summary`, `content`, `evidence_json`, `confidence`, `deleted`, `created_at`, `updated_at`)
  SELECT `id`, `session_id`, `report_no`, `format`, `summary`, `content`, `evidence_json`, `confidence`, `deleted`, `created_at`, `updated_at` FROM synpharm.`agent_report`;
INSERT INTO synpharm_predict.`tool_invocation_log` (`id`, `session_id`, `step_id`, `tool_name`, `input_digest`, `output_digest`, `status`, `latency_ms`, `error_msg`, `created_at`)
  SELECT `id`, `session_id`, `step_id`, `tool_name`, `input_digest`, `output_digest`, `status`, `latency_ms`, `error_msg`, `created_at` FROM synpharm.`tool_invocation_log`;

-- -----------------------------------------------------------------------------
-- 4. 核验清单（括号内为期望值）
-- -----------------------------------------------------------------------------
-- SELECT COUNT(*) FROM information_schema.tables
--   WHERE table_schema='synpharm_predict';                              -- 10
-- SELECT COUNT(*) FROM synpharm_predict.ddi_supported_drug;             -- 1323
-- SELECT COUNT(*) FROM synpharm_predict.predict_task;                   -- 3
-- SELECT COUNT(*) FROM synpharm_predict.predict_result;                 -- 2
-- SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
--   WHERE TABLE_SCHEMA='synpharm_predict' AND REFERENCED_TABLE_NAME IS NOT NULL;  -- 8
-- SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
--   WHERE TABLE_SCHEMA='synpharm_predict' AND REFERENCED_TABLE_SCHEMA<>'synpharm_predict'
--   AND REFERENCED_TABLE_NAME IS NOT NULL;                              -- 0（无跨 schema 外键）
