-- =============================================
-- SynPharm · 阶段 B：需求分析与基线快照（已部署环境的增量迁移）
-- 版本：v1.0  日期：2026-10-22
-- 来源：docs/design/detailed/06-主业务三阶段设计方案.md §3
--
-- 本文件与 sql/39_requirement_baseline.sql **内容一致**：
--   · 39_ 用于空卷初始化（docker-entrypoint-initdb.d 按序执行）
--   · 本文件用于已部署环境（手工执行）
-- 两者都幂等（存储过程 + information_schema 守卫），可安全重复执行。
--
-- 变更内容：建 3 表（baseline_snapshot / project_metric / project_risk_item）
--          + 改 2 表（design_project / constraint_rule）
--          + 补字典（NANOBODY / MOA / FILTER_REASON）
--
-- 执行前置：建议先 mysqldump 一次 synpharm_core。
-- =============================================
USE synpharm_core;

-- ---------------------------------------------------------------------------
-- 1. baseline_snapshot：需求基线快照（冻结，不可变）
-- ---------------------------------------------------------------------------
-- 为什么八块用 JSON 而不是拆 8 张表：快照只被整体读取与哈希比对，不被 JOIN 查询。
-- 拆表会让 content_hash 的幂等校验变得极其复杂，收益为零。
CREATE TABLE IF NOT EXISTS baseline_snapshot (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_no       VARCHAR(64)  NOT NULL COMMENT '快照编号 BS+yyMMddHHmmss+随机',
    project_id        BIGINT       NOT NULL,
    project_version   INT          NOT NULL COMMENT '冻结时的项目版本号',
    user_id           BIGINT       NOT NULL COMMENT '冻结操作人',

    -- 八块内容（冻结副本）
    targets_json      JSON         NOT NULL COMMENT '块1 靶点列表 + 主靶点角色',
    structure_json    JSON                  COMMENT '块2 prep 后的结构 URI/SHA256/口袋',
    modality_code     VARCHAR(30)  NOT NULL COMMENT '块3 分子形式',
    hard_rules_json   JSON         NOT NULL COMMENT '块4 硬约束快照（≥1 条）',
    soft_rules_json   JSON                  COMMENT '块5 软约束 + 权重',
    metrics_json      JSON         NOT NULL COMMENT '块6 指标清单 + 权重 + 目标 + 误差带',
    quota_json        JSON         NOT NULL COMMENT '块7 GPUh / 湿实验名额 / min_candidates',
    risks_json        JSON                  COMMENT '块8 风险关注点',

    completeness_json JSON         NOT NULL COMMENT '逐块校验结果 {block: ok|missing|warn}',
    content_hash      CHAR(64)     NOT NULL COMMENT '八块内容 sha256，用于幂等与审计',
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                      COMMENT 'ACTIVE/SUPERSEDED（被新版本取代）',
    superseded_by     BIGINT                COMMENT '被哪个快照取代',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_snapshot_no (snapshot_no),
    UNIQUE KEY uk_bs_project_version (project_id, project_version),
    KEY idx_bs_project_status (project_id, status),
    CONSTRAINT fk_bs_project FOREIGN KEY (project_id) REFERENCES design_project(id),
    CONSTRAINT fk_bs_superseded FOREIGN KEY (superseded_by) REFERENCES baseline_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='需求基线快照（冻结）';

-- ---------------------------------------------------------------------------
-- 2. project_metric：项目启用的指标与目标
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS project_metric (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id   BIGINT       NOT NULL,
    metric_code  VARCHAR(40)  NOT NULL COMMENT 'metric_definition.metric_code',
    dimension    VARCHAR(30)           COMMENT '冗余自 metric_definition.dimension',
    weight       DECIMAL(6,3) NOT NULL DEFAULT 1.000 COMMENT '综合分权重',
    target_value DECIMAL(14,5)         COMMENT '期望值（软目标）',
    threshold    DECIMAL(14,5)         COMMENT '达标阈值',
    operator     VARCHAR(10)           COMMENT 'GT/GTE/LT/LTE/EQ',
    tolerance    DECIMAL(14,5)         COMMENT '方法学误差带；NULL=无容差',
    boundary_policy VARCHAR(20)        COMMENT 'STRICT/WITHIN_TOLERANCE/ESCALATE_TO_SOFT',
    priority     INT          NOT NULL DEFAULT 0,
    enabled      TINYINT      NOT NULL DEFAULT 1,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_project_metric (project_id, metric_code),
    KEY idx_pm_project (project_id, enabled),
    CONSTRAINT fk_pm_project FOREIGN KEY (project_id) REFERENCES design_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='项目启用的评估指标与目标';

-- ---------------------------------------------------------------------------
-- 3. project_risk_item：风险关注点
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS project_risk_item (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id  BIGINT       NOT NULL,
    risk_code   VARCHAR(40)  NOT NULL
                COMMENT '枚举：IMMUNOGENICITY/POLYSPECIFICITY/PTM_SITE/AGGREGATION/CARDIOVASCULAR/HEPATOTOXICITY/IP_INFRINGEMENT/OFF_TARGET',
    dimension   VARCHAR(30)           COMMENT '关联评估维度（可空）',
    description VARCHAR(500),
    severity    VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM' COMMENT 'LOW/MEDIUM/HIGH/CRITICAL',
    must_verify TINYINT      NOT NULL DEFAULT 1 COMMENT '是否必须湿实验验证',
    source      VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT 'USER/AUTO（按 modality 自动带出）',
    deleted     TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_project_risk (project_id, risk_code),
    KEY idx_pri_project (project_id),
    CONSTRAINT fk_pri_project FOREIGN KEY (project_id) REFERENCES design_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='项目风险关注点';

-- ---------------------------------------------------------------------------
-- 4~5. 既有表改造（幂等：列存在则跳过）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_requirement_baseline;

DELIMITER $$
CREATE PROCEDURE sp_requirement_baseline()
BEGIN
    DECLARE n INT DEFAULT 0;

    -- ===== 4.1 design_project：作用机制 / 配额 / 当前基线 / 版本号 =====
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'design_project'
                AND COLUMN_NAME = 'moa_code');
    IF n = 0 THEN
        ALTER TABLE design_project
            ADD COLUMN moa_code VARCHAR(30) NULL
                COMMENT '作用机制（design_library_item.category=MOA）：INHIBITOR/AGONIST/...'
                AFTER modality_id,
            ADD COLUMN current_baseline_id BIGINT NULL
                COMMENT '当前生效的基线快照（baseline_snapshot.id）' AFTER active_constraint_set_id,
            ADD COLUMN project_version INT NOT NULL DEFAULT 1
                COMMENT '项目版本号；约束变更即 +1（编辑设计铁律三）' AFTER current_baseline_id,
            ADD COLUMN quota_gpu_hours DECIMAL(10,3) NULL COMMENT 'GPU 小时配额',
            ADD COLUMN quota_gpu_used DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT '已消耗 GPU 小时',
            ADD COLUMN quota_wet_lab INT NULL COMMENT '湿实验名额',
            ADD COLUMN quota_wet_used INT NOT NULL DEFAULT 0 COMMENT '已用湿实验名额';

        -- 循环外键：design_project → baseline_snapshot → design_project
        -- 安全：快照只做 SUPERSEDED 不物理删除，故不会阻断项目行
        ALTER TABLE design_project
            ADD CONSTRAINT fk_dp_baseline FOREIGN KEY (current_baseline_id) REFERENCES baseline_snapshot(id);
    END IF;

    -- ===== 4.2 constraint_rule：误差带与边界判定（06 文档 §3.5）=====
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'constraint_rule'
                AND COLUMN_NAME = 'tolerance');
    IF n = 0 THEN
        ALTER TABLE constraint_rule
            ADD COLUMN tolerance DECIMAL(14,5) NULL
                COMMENT '方法学误差带；NULL=无容差' AFTER threshold_max,
            ADD COLUMN boundary_policy VARCHAR(20) NULL DEFAULT 'STRICT'
                COMMENT 'STRICT/WITHIN_TOLERANCE/ESCALATE_TO_SOFT' AFTER tolerance,
            ADD COLUMN applies_to_modality VARCHAR(60) NULL
                COMMENT '适用分子形式，逗号分隔；NULL=全部' AFTER boundary_policy;
    END IF;

    -- ===== 4.3 project_metric 的 boundary_policy 缺省补齐（新表可能由旧版脚本建过）=====
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_metric'
                AND COLUMN_NAME = 'tolerance');
    IF n = 0 THEN
        ALTER TABLE project_metric
            ADD COLUMN tolerance DECIMAL(14,5) NULL COMMENT '方法学误差带；NULL=无容差',
            ADD COLUMN boundary_policy VARCHAR(20) NULL COMMENT 'STRICT/WITHIN_TOLERANCE/ESCALATE_TO_SOFT';
    END IF;
END$$
DELIMITER ;

CALL sp_requirement_baseline();
DROP PROCEDURE sp_requirement_baseline;

-- ---------------------------------------------------------------------------
-- 6. 字典补充
-- ---------------------------------------------------------------------------
-- 6.1 纳米抗体（用户明确提到，原有 6 种 MODALITY 里没有）
INSERT INTO design_library_item (category, code, name, name_en, level_no, path, meta_json, sort_no, builtin)
VALUES ('MODALITY','NANOBODY','纳米抗体','Nanobody (VHH)',1,'/MODALITY/NANOBODY',
  '{"defaultDimensions":["AFFINITY","STABILITY","EXPRESSION","IMMUNOGENICITY"],
    "defaultEditStrategies":["MUTATION","HUMANIZATION"],"seqRequired":true}', 15, 1)
ON DUPLICATE KEY UPDATE name = VALUES(name), name_en = VALUES(name_en), meta_json = VALUES(meta_json);

-- 6.2 作用机制（原有 schema 完全没有这个概念）
INSERT INTO design_library_item (category, code, name, name_en, level_no, path, meta_json, sort_no, builtin)
VALUES
 ('MOA','INHIBITOR',   '抑制剂','Inhibitor',  1,'/MOA/INHIBITOR',  '{}',10,1),
 ('MOA','AGONIST',     '激动剂','Agonist',    1,'/MOA/AGONIST',    '{}',20,1),
 ('MOA','ANTAGONIST',  '拮抗剂','Antagonist', 1,'/MOA/ANTAGONIST', '{}',30,1),
 ('MOA','DEGRADER',    '降解剂','Degrader',   1,'/MOA/DEGRADER',   '{}',40,1),
 ('MOA','BRIDGING',    '桥连（双抗）','Bridging', 1,'/MOA/BRIDGING','{}',50,1)
ON DUPLICATE KEY UPDATE name = VALUES(name), name_en = VALUES(name_en);

-- 6.3 筛选原因（生成 S6 与编辑 E5 共用同一套枚举，06 文档 §5.3 注）
--     severity：HARD=直接淘汰 / SOFT=扣分 / INFO=仅记录（去重、配额裁掉）
INSERT INTO design_library_item (category, code, name, name_en, level_no, path, meta_json, sort_no, builtin)
VALUES
 ('FILTER_REASON','LENGTH_OUT_OF_RANGE','长度越界','Length out of range',1,'/FILTER_REASON/LENGTH_OUT_OF_RANGE','{"severity":"HARD"}',10,1),
 ('FILTER_REASON','ILLEGAL_RESIDUE','非法残基','Illegal residue',1,'/FILTER_REASON/ILLEGAL_RESIDUE','{"severity":"HARD"}',20,1),
 ('FILTER_REASON','SA_SCORE_HIGH','合成难度过高','SA score too high',1,'/FILTER_REASON/SA_SCORE_HIGH','{"severity":"HARD"}',30,1),
 ('FILTER_REASON','PAINS_HIT','PAINS 警报命中','PAINS hit',1,'/FILTER_REASON/PAINS_HIT','{"severity":"HARD"}',40,1),
 ('FILTER_REASON','BRENK_HIT','BRENK 不良片段','BRENK hit',1,'/FILTER_REASON/BRENK_HIT','{"severity":"HARD"}',50,1),
 ('FILTER_REASON','IP_SIMILARITY','专利相似度超限','IP similarity exceeded',1,'/FILTER_REASON/IP_SIMILARITY','{"severity":"HARD"}',60,1),
 ('FILTER_REASON','SEQUENCE_MOTIF_FORBIDDEN','序列禁区命中','Forbidden sequence motif',1,'/FILTER_REASON/SEQUENCE_MOTIF_FORBIDDEN','{"severity":"HARD"}',70,1),
 ('FILTER_REASON','CDR_DESTROYED','关键 CDR 被破坏','CDR destroyed',1,'/FILTER_REASON/CDR_DESTROYED','{"severity":"HARD"}',80,1),
 ('FILTER_REASON','HARD_CONSTRAINT_VIOLATION','硬约束违例','Hard constraint violated',1,'/FILTER_REASON/HARD_CONSTRAINT_VIOLATION','{"severity":"HARD"}',90,1),
 ('FILTER_REASON','PTM_RISK_SITE','高风险 PTM 位点','High-risk PTM site',1,'/FILTER_REASON/PTM_RISK_SITE','{"severity":"SOFT"}',100,1),
 ('FILTER_REASON','CHARGE_IMBALANCE','净电荷失衡','Charge imbalance',1,'/FILTER_REASON/CHARGE_IMBALANCE','{"severity":"SOFT"}',110,1),
 ('FILTER_REASON','HYDROPHOBIC_PATCH','疏水斑块过大','Hydrophobic patch too large',1,'/FILTER_REASON/HYDROPHOBIC_PATCH','{"severity":"SOFT"}',120,1),
 ('FILTER_REASON','AGGREGATION_RISK','聚集风险高','Aggregation risk',1,'/FILTER_REASON/AGGREGATION_RISK','{"severity":"SOFT"}',130,1),
 ('FILTER_REASON','DEDUP_MERGED','去重合并','Merged by dedup',1,'/FILTER_REASON/DEDUP_MERGED','{"severity":"INFO"}',140,1),
 ('FILTER_REASON','QUOTA_CUT','多样性配额裁掉','Cut by diversity quota',1,'/FILTER_REASON/QUOTA_CUT','{"severity":"INFO"}',150,1)
ON DUPLICATE KEY UPDATE name = VALUES(name), name_en = VALUES(name_en), meta_json = VALUES(meta_json);

-- ---------------------------------------------------------------------------
-- ---------------------------------------------------------------------------
-- 5.4 补充：块7 需要 min_candidates（基线冻结时的闸门阈值，06 文档 §4.1 后置闸门）
-- 放列而不放 JSON：它要参与 SQL 过滤与排序，且前端要单独编辑。
--
-- 刻意允许 NULL：默认值 10 会让「用户没设配额」静默变成「设了 10」，
-- 生成 S7 后置闸门就按一个用户从未声明的阈值判淘汰 —— 这违反红线 R2（不做隐藏规则）。
-- NULL 时基线块7 判 NO_MIN_CANDIDATES，用户必须在需求分析阶段显式填写才能冻结。
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_design_project_min_candidates;
DELIMITER $$
CREATE PROCEDURE sp_design_project_min_candidates()
BEGIN
    DECLARE n INT DEFAULT 0;
    DECLARE nullable VARCHAR(3) DEFAULT 'YES';
    SET n := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'design_project'
                AND COLUMN_NAME = 'min_candidates');
    IF n = 0 THEN
        ALTER TABLE design_project
            ADD COLUMN min_candidates INT NULL DEFAULT NULL
                COMMENT '最少候选数闸门（生成 S7 后置闸门 / 编辑后置闸门共用）'
                AFTER quota_wet_used;
    ELSE
        -- 早期版本误加了 DEFAULT 10，这里就地纠正（已显式写入的值不动）
        SET nullable := (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'design_project'
                           AND COLUMN_NAME = 'min_candidates');
        IF nullable = 'NO' THEN
            ALTER TABLE design_project
                MODIFY COLUMN min_candidates INT NULL DEFAULT NULL
                    COMMENT '最少候选数闸门（生成 S7 后置闸门 / 编辑后置闸门共用）';
        END IF;
    END IF;
END$$
DELIMITER ;
CALL sp_design_project_min_candidates();
DROP PROCEDURE sp_design_project_min_candidates;

-- 7. 验收查询（人工执行确认）
-- ---------------------------------------------------------------------------
-- SELECT table_name FROM information_schema.tables
--  WHERE table_schema='synpharm_core'
--    AND table_name IN ('baseline_snapshot','project_metric','project_risk_item');
-- SELECT column_name FROM information_schema.columns
--  WHERE table_schema='synpharm_core' AND table_name='design_project'
--    AND column_name IN ('moa_code','current_baseline_id','project_version','quota_gpu_hours');
-- SELECT column_name FROM information_schema.columns
--  WHERE table_schema='synpharm_core' AND table_name='constraint_rule'
--    AND column_name IN ('tolerance','boundary_policy','applies_to_modality');
-- SELECT category, count(*) FROM design_library_item
--  WHERE category IN ('MODALITY','MOA','FILTER_REASON') GROUP BY category;
