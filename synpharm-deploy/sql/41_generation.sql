-- ===========================================================================
-- 41_generation.sql —— 生成阶段（06-主业务三阶段设计方案.md §4）
-- ===========================================================================
-- 适用范围：synpharm_core
-- 幂等：全部走 information_schema 守卫，可重复执行
-- 执行账号：**必须 root**（DROP/CREATE PROCEDURE 与 PREPARE 动态 DDL 需要更高权限）
--
-- 本脚本建 5 张表 + 改造 2 张表：
--   generation_slice            分片（S4）
--   generation_candidate_log    逐分子筛选日志（S5/S6/S7「原因必记」的落点）
--   generation_cache            三元组缓存（缓存优先）
--   dedup_merge                 去重合并关系（S5）
--   generation_output_snapshot  完整回放包（S8）
--   generation_run              +13 列（S3 计划 / 配额 / 闸门阈值）
--   candidate_molecule          +9 列（流水线状态 / 多链 / 结构置信度）
--
-- 注意 sequence_hash 的语义在本阶段被改写为「多链拼接」，
-- 见 06 §4.5 与 CandidateMolecule#sequenceHash 的注释（Java 侧实现）。
-- ===========================================================================

USE synpharm_core;

-- ---------------------------------------------------------------------------
-- 0. 辅助过程：按需加列（避免 22 段 IF NOT EXISTS 重复）
-- ---------------------------------------------------------------------------
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
-- 1. generation_slice（S4 分片）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_generation_slice;
DELIMITER $$
CREATE PROCEDURE sp_create_generation_slice()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'generation_slice') THEN
        CREATE TABLE generation_slice (
            id             BIGINT        NOT NULL AUTO_INCREMENT,
            run_id         BIGINT        NOT NULL,
            job_id         BIGINT        NULL COMMENT '对应 design_job（复用任务表的状态机）',
            slice_no       INT           NOT NULL COMMENT '片序号，从 1 开始',
            seed           BIGINT        NULL COMMENT '该片随机种子（回放凭证）',
            param_hash     CHAR(64)      NOT NULL COMMENT '参数哈希（缓存键之一）',
            input_hash     CHAR(64)      NOT NULL COMMENT '输入哈希（缓存键之一）',
            tool_id        BIGINT        NULL COMMENT 'design_tool.id',
            tool_version   VARCHAR(20)   NULL,
            status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                           COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED',
            retry_count    INT           NOT NULL DEFAULT 0,
            produced_count INT           NOT NULL DEFAULT 0 COMMENT '本片产出数',
            gpu_hours_used DECIMAL(10,3) NOT NULL DEFAULT 0,
            cache_hit      TINYINT       NOT NULL DEFAULT 0 COMMENT '1=复用缓存，不消耗配额',
            artifact_uri   VARCHAR(800)  NULL COMMENT '产物 URI（对象存储）',
            error_msg      VARCHAR(1000) NULL,
            started_at     DATETIME      NULL,
            completed_at   DATETIME      NULL,
            created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_run_slice (run_id, slice_no),
            KEY idx_status (run_id, status),
            CONSTRAINT fk_gs_run FOREIGN KEY (run_id) REFERENCES generation_run (id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成分片（S4）';
    END IF;
END$$
DELIMITER ;
CALL sp_create_generation_slice();
DROP PROCEDURE sp_create_generation_slice;

-- ---------------------------------------------------------------------------
-- 2. generation_candidate_log（S5/S6/S7 逐分子日志）
--
-- 为什么不能复用 candidate_molecule，而复用它的唯一键会丢信息：
-- candidate_molecule 上有 uk_project_inchikey(project_id, inchikey) 与
-- uk_project_sequence(project_id, sequence_hash)。同一分子在第 1 批被筛掉、
-- 第 2 批又生成时，第二次插不进去，也就无法记录「这次为什么又被筛」。
-- 日志表按 (run_id, 分子) 维度记录，不受业务唯一键约束。
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_generation_candidate_log;
DELIMITER $$
CREATE PROCEDURE sp_create_generation_candidate_log()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'generation_candidate_log') THEN
        CREATE TABLE generation_candidate_log (
            id            BIGINT        NOT NULL AUTO_INCREMENT,
            run_id        BIGINT        NOT NULL,
            slice_id      BIGINT        NULL COMMENT '来自哪一片',
            stage         VARCHAR(20)   NOT NULL COMMENT 'S5_DEDUP / S6_PRE_FILTER / S7_QUOTA',
            smiles        VARCHAR(2000) NULL,
            sequence_hash CHAR(64)      NULL,
            inchikey      CHAR(27)      NULL,
            candidate_id  BIGINT        NULL COMMENT '通过后才有值（candidate_molecule.id）',
            outcome       VARCHAR(20)   NOT NULL COMMENT 'KEPT/FILTERED/DEDUPED/QUOTA_CUT',
            filter_reason JSON          NULL COMMENT '[{"code":"PAINS_HIT","severity":"HARD","detail":{}}]',
            pre_score     DECIMAL(7,3)  NULL COMMENT '粗筛得分',
            dedup_into    BIGINT        NULL COMMENT '被合并到哪个候选',
            cluster_id    VARCHAR(40)   NULL,
            created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            KEY idx_run_stage (run_id, stage),
            KEY idx_outcome (run_id, outcome),
            KEY idx_candidate (candidate_id),
            KEY idx_inchikey (inchikey),
            CONSTRAINT fk_gcl_run FOREIGN KEY (run_id) REFERENCES generation_run (id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成逐分子筛选日志（原因必记）';
    END IF;
END$$
DELIMITER ;
CALL sp_create_generation_candidate_log();
DROP PROCEDURE sp_create_generation_candidate_log;

-- ---------------------------------------------------------------------------
-- 3. generation_cache（三元组缓存）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_generation_cache;
DELIMITER $$
CREATE PROCEDURE sp_create_generation_cache()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'generation_cache') THEN
        CREATE TABLE generation_cache (
            id           BIGINT       NOT NULL AUTO_INCREMENT,
            cache_key    CHAR(64)     NOT NULL COMMENT 'sha256(tool_id|param_hash|input_hash)',
            tool_id      BIGINT       NOT NULL,
            tool_version VARCHAR(20)  NULL,
            param_hash   CHAR(64)     NOT NULL,
            input_hash   CHAR(64)     NOT NULL,
            artifact_uri VARCHAR(800) NOT NULL,
            result_count INT          NOT NULL DEFAULT 0,
            hit_count    INT          NOT NULL DEFAULT 0,
            expires_at   DATETIME     NULL,
            created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_cache_key (cache_key),
            KEY idx_expires (expires_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成结果缓存（tool+param+input 三元组）';
    END IF;
END$$
DELIMITER ;
CALL sp_create_generation_cache();
DROP PROCEDURE sp_create_generation_cache;

-- ---------------------------------------------------------------------------
-- 4. dedup_merge（S5 去重合并关系）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_dedup_merge;
DELIMITER $$
CREATE PROCEDURE sp_create_dedup_merge()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dedup_merge') THEN
        CREATE TABLE dedup_merge (
            id                BIGINT       NOT NULL AUTO_INCREMENT,
            run_id            BIGINT       NOT NULL,
            merged_id         BIGINT       NULL COMMENT '被合并者 candidate_molecule.id（落库后才有）',
            merged_log_id     BIGINT       NULL COMMENT '尚未落库时指向 generation_candidate_log.id',
            representative_id BIGINT       NULL COMMENT '代表者 candidate_molecule.id',
            similarity        DECIMAL(6,4) NULL,
            method            VARCHAR(20)  NOT NULL COMMENT 'ECFP4/TMSCORE/EXACT',
            created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            KEY idx_run (run_id),
            KEY idx_rep (representative_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='去重合并关系（S5）';
    END IF;
END$$
DELIMITER ;
CALL sp_create_dedup_merge();
DROP PROCEDURE sp_create_dedup_merge;

-- ---------------------------------------------------------------------------
-- 5. generation_output_snapshot（S8 完整回放包）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_generation_output_snapshot;
DELIMITER $$
CREATE PROCEDURE sp_create_generation_output_snapshot()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'generation_output_snapshot') THEN
        CREATE TABLE generation_output_snapshot (
            id             BIGINT       NOT NULL AUTO_INCREMENT,
            snapshot_no    VARCHAR(64)  NOT NULL,
            run_id         BIGINT       NOT NULL,
            candidate_ids  JSON         NOT NULL COMMENT '候选集 v1 的 id 列表',
            filter_summary JSON         NULL COMMENT '四段漏斗统计',
            cluster_dist   JSON         NULL COMMENT '簇分布',
            plan_json      JSON         NULL COMMENT 'S3 完整计划',
            tool_versions  JSON         NULL COMMENT '各工具版本',
            content_hash   CHAR(64)     NOT NULL COMMENT '以上内容 sha256（不含时间）',
            created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_gos_no (snapshot_no),
            UNIQUE KEY uk_gos_run (run_id),
            CONSTRAINT fk_gos_run FOREIGN KEY (run_id) REFERENCES generation_run (id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成完整回放包（S8）';
    END IF;
END$$
DELIMITER ;
CALL sp_create_generation_output_snapshot();
DROP PROCEDURE sp_create_generation_output_snapshot;

-- ---------------------------------------------------------------------------
-- 6. generation_run 改造（06 §4.3，13 列）
-- ---------------------------------------------------------------------------
CALL sp_add_column_if_missing('generation_run', 'baseline_snapshot_id',
    'baseline_snapshot_id BIGINT NULL COMMENT ''只读的需求快照（S0 唯一输入）''');
CALL sp_add_column_if_missing('generation_run', 'plan_json',
    'plan_json JSON NULL COMMENT ''S3 完整 generation_plan''');
CALL sp_add_column_if_missing('generation_run', 'plan_hash',
    'plan_hash CHAR(64) NULL COMMENT ''计划哈希（回放凭证）''');
CALL sp_add_column_if_missing('generation_run', 'fallback_strategy',
    'fallback_strategy VARCHAR(60) NULL COMMENT ''主策略失败时的降级策略''');
CALL sp_add_column_if_missing('generation_run', 'n_samples',
    'n_samples INT NULL COMMENT ''采样配额（由 S2 空间规模决定，不人工拍脑袋）''');
CALL sp_add_column_if_missing('generation_run', 'batch_size',
    'batch_size INT NULL COMMENT ''分片大小''');
CALL sp_add_column_if_missing('generation_run', 'seed',
    'seed BIGINT NULL COMMENT ''全局种子''');
CALL sp_add_column_if_missing('generation_run', 'estimated_gpu_hours',
    'estimated_gpu_hours DECIMAL(10,3) NULL COMMENT ''预估 GPU 小时''');
CALL sp_add_column_if_missing('generation_run', 'rationale',
    'rationale TEXT NULL COMMENT ''S3 选策略的理由（Agent 产出）''');
CALL sp_add_column_if_missing('generation_run', 'plan_confidence',
    'plan_confidence DECIMAL(5,4) NULL COMMENT ''S3 计划置信度''');
CALL sp_add_column_if_missing('generation_run', 'output_snapshot_id',
    'output_snapshot_id BIGINT NULL COMMENT ''S8 回放包''');
CALL sp_add_column_if_missing('generation_run', 'valid_cluster_count',
    'valid_cluster_count INT NULL COMMENT ''S7 有效簇数（<3 严重告警）''');
CALL sp_add_column_if_missing('generation_run', 'min_candidates',
    'min_candidates INT NULL COMMENT ''闸门阈值（来自 baseline 块7）''');
CALL sp_add_column_if_missing('generation_run', 'idempotency_key',
    'idempotency_key VARCHAR(64) NULL COMMENT ''幂等键：同键重复提交返回同一批次''');

-- 幂等唯一键。注意 NULL 不被去重 —— 不传幂等键的调用可以共存，这正是想要的行为
DROP PROCEDURE IF EXISTS sp_generation_run_idem_index;
DELIMITER $$
CREATE PROCEDURE sp_generation_run_idem_index()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'generation_run'
                     AND INDEX_NAME = 'uk_run_idempotent') THEN
        ALTER TABLE generation_run ADD UNIQUE KEY uk_run_idempotent (project_id, idempotency_key);
    END IF;
END$$
DELIMITER ;
CALL sp_generation_run_idem_index();
DROP PROCEDURE sp_generation_run_idem_index;

-- ---------------------------------------------------------------------------
-- 7. candidate_molecule 改造（06 §4.4，9 列）
-- ---------------------------------------------------------------------------
CALL sp_add_column_if_missing('candidate_molecule', 'pipeline_status',
    'pipeline_status VARCHAR(20) NOT NULL DEFAULT ''generated''
     COMMENT ''生成流水线：pending/generating/generated/screening/passed/rejected/error/archived''');
CALL sp_add_column_if_missing('candidate_molecule', 'cluster_id',
    'cluster_id VARCHAR(40) NULL COMMENT ''S7 簇标签''');
CALL sp_add_column_if_missing('candidate_molecule', 'pre_score',
    'pre_score DECIMAL(7,3) NULL COMMENT ''S6 粗筛得分''');
CALL sp_add_column_if_missing('candidate_molecule', 'molecule_subtype',
    'molecule_subtype VARCHAR(30) NULL COMMENT ''亚型，如 IgG1/IgG4（modality 之下一层）''');
CALL sp_add_column_if_missing('candidate_molecule', 'heavy_chain',
    'heavy_chain MEDIUMTEXT NULL COMMENT ''重链（抗体）''');
CALL sp_add_column_if_missing('candidate_molecule', 'light_chain',
    'light_chain MEDIUMTEXT NULL COMMENT ''轻链（抗体）''');
CALL sp_add_column_if_missing('candidate_molecule', 'extra_chains',
    'extra_chains JSON NULL COMMENT ''额外链（双抗/ADC），[{name,sequence}]''');
CALL sp_add_column_if_missing('candidate_molecule', 'structure_model_method',
    'structure_model_method VARCHAR(40) NULL COMMENT ''AF2_multimer_v3 / PDB / —''');
CALL sp_add_column_if_missing('candidate_molecule', 'structure_confidence',
    'structure_confidence DECIMAL(5,4) NULL COMMENT ''结构置信度（0~1）''');

-- ---------------------------------------------------------------------------
-- 8. pipeline_status 索引（按流水线状态筛候选是高频查询）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_candidate_pipeline_index;
DELIMITER $$
CREATE PROCEDURE sp_candidate_pipeline_index()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'candidate_molecule'
                     AND INDEX_NAME = 'idx_pipeline_status') THEN
        ALTER TABLE candidate_molecule ADD KEY idx_pipeline_status (project_id, pipeline_status);
    END IF;
END$$
DELIMITER ;
CALL sp_candidate_pipeline_index();
DROP PROCEDURE sp_candidate_pipeline_index;

DROP PROCEDURE IF EXISTS sp_add_column_if_missing;

-- ---------------------------------------------------------------------------
-- 9. 验收查询
-- ---------------------------------------------------------------------------
-- 9.1 五张新表
-- SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='synpharm_core'
--  AND TABLE_NAME IN ('generation_slice','generation_candidate_log','generation_cache',
--                     'dedup_merge','generation_output_snapshot');
--
-- 9.2 generation_run 新列
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='synpharm_core'
--  AND TABLE_NAME='generation_run' AND COLUMN_NAME IN
--  ('baseline_snapshot_id','plan_json','plan_hash','fallback_strategy','n_samples',
--   'batch_size','seed','estimated_gpu_hours','rationale','plan_confidence',
--   'output_snapshot_id','valid_cluster_count','min_candidates');
--
-- 9.3 candidate_molecule 新列
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='synpharm_core'
--  AND TABLE_NAME='candidate_molecule' AND COLUMN_NAME IN
--  ('pipeline_status','cluster_id','pre_score','molecule_subtype','heavy_chain',
--   'light_chain','extra_chains','structure_model_method','structure_confidence');
