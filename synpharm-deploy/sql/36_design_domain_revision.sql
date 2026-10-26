-- =============================================
-- AI 药物分子设计平台 · 设计域既有表修订
-- 版本：v1.0  依赖：11_ ~ 35_ 全部已执行
-- 来源：docs/design/AI药物分子设计平台-详细设计书.md §3.4
--
-- ⚠ 幂等性重写：设计书 §3.4 原文直接使用 ALTER TABLE ... ADD COLUMN，
--   重复执行必然报 1060 Duplicate column，与 §8.2 验收项
--   「36_ / 37_ 在既有库执行成功且幂等（可重复执行不报错）」直接冲突。
--   本脚本改为「存储过程 + information_schema 守卫」，可安全重复执行。
--   （同一技巧已在 08_batch_task_alter.sql 中用于 batch_task，保持项目内一致。）
--
-- 执行前置：对 synpharm 库做一次全量备份（本脚本含 MODIFY COLUMN 与新增约束）。
-- =============================================
USE synpharm_core;

DROP PROCEDURE IF EXISTS sp_design_domain_revision;

DELIMITER $$
CREATE PROCEDURE sp_design_domain_revision()
BEGIN
    DECLARE n INT DEFAULT 0;

    -- =======================================================================
    -- 3.4.0 延迟外键补建（编号顺序与 FK 依赖冲突的收敛点）
    --   docker-entrypoint-initdb.d 按文件名字母序执行，因此：
    --     · 12_project_target 建于 14_target 之前  → fk_pt_target 不能写在 12_
    --     · 11_design_project 建于 17_constraint_set 之前 → fk_dp_constraint_set 不能写在 11_
    --   二者统一在本脚本（36_，最后执行）补建。详见 00-设计评审与勘误.md R-05。
    -- =======================================================================
    SET n := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
              WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'project_target'
                AND CONSTRAINT_NAME = 'fk_pt_target');
    IF n = 0 THEN
        ALTER TABLE project_target
            ADD CONSTRAINT fk_pt_target FOREIGN KEY (target_id) REFERENCES target(id);
    END IF;

    -- =======================================================================
    -- 3.4.1 design_project：疾病 / 分子形式 / 当前轮次
    -- =======================================================================
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'design_project'
                AND COLUMN_NAME = 'disease_id');
    IF n = 0 THEN
        ALTER TABLE design_project
            ADD COLUMN disease_id       BIGINT NULL COMMENT '疾病（design_library_item.category=DISEASE）' AFTER goal_metric_json,
            ADD COLUMN modality_id      BIGINT NULL COMMENT '分子形式（design_library_item.category=MODALITY）' AFTER disease_id,
            ADD COLUMN current_round_id BIGINT NULL COMMENT '当前轮次（工作台恢复用，避免每次 MAX() 推导）' AFTER active_constraint_set_id,
            ADD KEY idx_lib (disease_id, modality_id),
            ADD KEY idx_current_round (current_round_id);
    END IF;

    -- 延迟外键：active_constraint_set_id 在 11_ 建表时 17_constraint_set 尚不存在，
    -- 故在此补建（约束集被归档/删除不应级联删除项目，故为 NO ACTION）。
    SET n := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
              WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'design_project'
                AND CONSTRAINT_NAME = 'fk_dp_constraint_set');
    IF n = 0 THEN
        ALTER TABLE design_project
            ADD CONSTRAINT fk_dp_constraint_set FOREIGN KEY (active_constraint_set_id)
                REFERENCES constraint_set(id);
    END IF;

    -- =======================================================================
    -- 3.4.2 candidate_molecule：多模态 + 对象存储 + 综合得分
    --   关键：smiles / inchikey 必须放开 NOT NULL —— 抗体、多肽没有 SMILES
    -- =======================================================================
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'candidate_molecule'
                AND COLUMN_NAME = 'smiles' AND IS_NULLABLE = 'NO');
    IF n = 1 THEN
        ALTER TABLE candidate_molecule
            MODIFY COLUMN smiles   VARCHAR(2000) NULL COMMENT '标准化后的 SMILES；生物大分子为 NULL',
            MODIFY COLUMN inchikey CHAR(27)      NULL COMMENT '标准化 InChIKey，小分子去重键；生物大分子为 NULL';
    END IF;

    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'candidate_molecule'
                AND COLUMN_NAME = 'modality_code');
    IF n = 0 THEN
        ALTER TABLE candidate_molecule
            ADD COLUMN modality_code      VARCHAR(30)  NULL
                 COMMENT 'SMALL_MOLECULE/ANTIBODY/PEPTIDE/BISPECIFIC/ADC/FUSION_PROTEIN' AFTER origin_type,
            ADD COLUMN sequence           MEDIUMTEXT   NULL COMMENT '生物大分子序列（抗体/多肽/蛋白）；小分子为 NULL',
            ADD COLUMN sequence_hash      CHAR(64)     NULL COMMENT 'sha256(规范化序列)，生物大分子去重键',
            ADD COLUMN sequence_length    INT          NULL COMMENT '序列长度',
            ADD COLUMN structure_uri      VARCHAR(800) NULL COMMENT '结构文件 URI（SDF/PDB，对象存储）',
            ADD COLUMN structure_asset_id BIGINT       NULL COMMENT '结构文件资产（file_asset.id）',
            ADD COLUMN total_score        DECIMAL(7,3) NULL COMMENT '加权综合得分（06 评估器回写，仅用于排序）',
            ADD UNIQUE KEY uk_project_sequence (project_id, sequence_hash),
            ADD KEY idx_modality (project_id, modality_code);
    END IF;

    -- 说明：MySQL 唯一索引允许重复 NULL ⇒ 小分子（sequence_hash 为 NULL）不受
    --       uk_project_sequence 影响；生物大分子（inchikey 为 NULL）也不受 uk_project_inchikey 影响。
    --       两套去重键互不干扰，这正是 §0.3 D7 的落地方式。

    -- 排行榜主查询需要 (project_id, total_score DESC)；MySQL 8 支持真正的降序索引。
    SET n := (SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'candidate_molecule'
                AND INDEX_NAME = 'idx_total_score');
    IF n = 0 THEN
        ALTER TABLE candidate_molecule
            ADD KEY idx_total_score (project_id, total_score DESC);
    END IF;

    -- fk_cand_structure 永久不建：file_asset 归 synpharm-asset（阶段 3），跨 schema 外键禁止（方案 §7.2）。
    -- structure_asset_id 保留为普通列，上面的 idx_file_asset 足以支撑按结构查候选的查询。

    -- =======================================================================
    -- 3.4.3 constraint_rule：维度 / 算子 / 阈值 / 单位（需求 project_constraints）
    -- =======================================================================
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'constraint_rule'
                AND COLUMN_NAME = 'dimension');
    IF n = 0 THEN
        ALTER TABLE constraint_rule
            ADD COLUMN dimension     VARCHAR(30)   NULL COMMENT '评估维度：AFFINITY/.../IMMUNOGENICITY（驱动加权）' AFTER severity,
            ADD COLUMN operator      VARCHAR(10)   NULL COMMENT 'GT/GTE/LT/LTE/EQ/BETWEEN' AFTER dimension,
            ADD COLUMN threshold_num DECIMAL(14,5) NULL COMMENT '阈值（单边）' AFTER operator,
            ADD COLUMN threshold_max DECIMAL(14,5) NULL COMMENT '区间上界（operator=BETWEEN 时使用）' AFTER threshold_num,
            ADD COLUMN unit          VARCHAR(20)   NULL COMMENT '阈值单位，如 nM / kcal/mol / Da' AFTER threshold_max,
            ADD KEY idx_dimension (dimension);
    END IF;

    -- =======================================================================
    -- 3.4.4 metric_definition：8 大维度 + 适用分子形式
    -- =======================================================================
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'metric_definition'
                AND COLUMN_NAME = 'dimension');
    IF n = 0 THEN
        ALTER TABLE metric_definition
            ADD COLUMN dimension  VARCHAR(30) NULL
                 COMMENT '8 大维度：AFFINITY/STABILITY/EXPRESSION/ADMET/SYNTHESIZABILITY/DEVELOPABILITY/SELECTIVITY/IMMUNOGENICITY'
                 AFTER metric_code,
            ADD COLUMN applies_to JSON NULL COMMENT '适用分子形式：["SMALL_MOLECULE","ANTIBODY","PEPTIDE"]',
            ADD KEY idx_dimension (dimension);
    END IF;

    -- =======================================================================
    -- 3.4.5 evaluation_result：风险等级 + 真实性边界 + 来源工具（红线 R1）
    -- =======================================================================
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'evaluation_result'
                AND COLUMN_NAME = 'risk_level');
    IF n = 0 THEN
        ALTER TABLE evaluation_result
            ADD COLUMN risk_level     VARCHAR(20) NULL COMMENT 'LOW/MEDIUM/HIGH/CRITICAL' AFTER grade,
            ADD COLUMN realness       VARCHAR(20) NULL
                 COMMENT 'REAL/SEMI_REAL/HEURISTIC/SIMULATED，生成时从 design_tool 快照' AFTER risk_level,
            ADD COLUMN source_tool_id BIGINT      NULL COMMENT '产出该指标的注册工具（design_tool.id）' AFTER realness,
            ADD KEY idx_risk (project_id, risk_level);
    END IF;

    -- fk_er_tool 永久不建：design_tool 归 synpharm-platform（阶段 5），跨 schema 外键禁止（方案 §7.2）。
    -- 另外 realness / source_tool_id 本就是**快照**语义（记录当时用的是哪个工具版本），
    -- 加了外键反而会在工具下线时阻止历史结果保留，语义上也不该建。

    -- 生物大分子没有 InChIKey（24_ 建表时已放开，此处仅为既有库补齐）
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'evaluation_result'
                AND COLUMN_NAME = 'inchikey' AND IS_NULLABLE = 'NO');
    IF n = 1 THEN
        ALTER TABLE evaluation_result
            MODIFY COLUMN inchikey CHAR(27) NULL COMMENT '冗余，便于按结构查询；生物大分子为 NULL';
    END IF;

    -- =======================================================================
    -- 3.4.6 molecule_edit_log：7 大编辑策略 + 作用区域（需求 edit_actions）
    -- =======================================================================
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'molecule_edit_log'
                AND COLUMN_NAME = 'strategy_code');
    IF n = 0 THEN
        ALTER TABLE molecule_edit_log
            ADD COLUMN strategy_code VARCHAR(30)  NULL
                 COMMENT 'SIDE_CHAIN/SCAFFOLD_HOP/MUTATION/LINKER/INTERFACE_REDESIGN/HUMANIZATION/GLYCO_OPT'
                 AFTER edit_type,
            ADD COLUMN target_region VARCHAR(200) NULL COMMENT '作用区域/位点：CDR-H3 / 界面残基 745 / R 基位置 C12'
                 AFTER strategy_code,
            ADD COLUMN modality_code VARCHAR(30)  NULL COMMENT '分子形式' AFTER target_region,
            ADD KEY idx_strategy (project_id, strategy_code);
    END IF;

    -- =======================================================================
    -- 3.4.7 target_structure：接入对象存储
    -- =======================================================================
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'target_structure'
                AND COLUMN_NAME = 'file_asset_id');
    IF n = 0 THEN
        ALTER TABLE target_structure
            ADD COLUMN file_asset_id BIGINT NULL COMMENT '结构文件资产（file_asset.id）' AFTER file_path,
            ADD KEY idx_file_asset (file_asset_id);
    END IF;

    -- fk_struct_asset 永久不建：file_asset 归 synpharm-asset（阶段 3），跨 schema 外键禁止（方案 §7.2）。

    -- =======================================================================
    -- 3.4.8 generation_run / evaluation_run：接入统一任务表（job_id）
    -- =======================================================================
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'generation_run'
                AND COLUMN_NAME = 'job_id');
    IF n = 0 THEN
        ALTER TABLE generation_run
            ADD COLUMN job_id BIGINT NULL COMMENT '统一任务（design_job.id）' AFTER round_id,
            ADD KEY idx_job (job_id);
    END IF;

    SET n := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
              WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'generation_run'
                AND CONSTRAINT_NAME = 'fk_gen_job');
    IF n = 0 THEN
        ALTER TABLE generation_run
            ADD CONSTRAINT fk_gen_job FOREIGN KEY (job_id) REFERENCES design_job(id);
    END IF;

    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'evaluation_run'
                AND COLUMN_NAME = 'job_id');
    IF n = 0 THEN
        ALTER TABLE evaluation_run
            ADD COLUMN job_id BIGINT NULL COMMENT '统一任务（design_job.id）' AFTER round_id,
            ADD KEY idx_job (job_id);
    END IF;

    SET n := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
              WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'evaluation_run'
                AND CONSTRAINT_NAME = 'fk_ev_job');
    IF n = 0 THEN
        ALTER TABLE evaluation_run
            ADD CONSTRAINT fk_ev_job FOREIGN KEY (job_id) REFERENCES design_job(id);
    END IF;

    -- =======================================================================
    -- 3.4.9 任务状态字典统一（D6）：SUCCESS → SUCCEEDED
    --   UPDATE 天然幂等；对全新库为 0 行更新。
    -- =======================================================================
    UPDATE generation_run SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';
    UPDATE evaluation_run SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';
END$$
DELIMITER ;

CALL sp_design_domain_revision();
DROP PROCEDURE sp_design_domain_revision;
