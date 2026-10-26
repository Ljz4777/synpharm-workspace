-- ===========================================================================
-- 40_decision.sql —— 决策阶段（07-决策阶段设计方案.md §5）
-- ===========================================================================
-- 适用范围：synpharm_core
-- 幂等：全部走 information_schema 守卫，可重复执行
-- 执行账号：**必须 root**（DROP PROCEDURE 需要对已存在过程有 ALTER ROUTINE 权限，
--           业务账号 synpharm_core 只有 CREATE ROUTINE，第二次执行会 ERROR 1370）
--
-- 为什么现在就建表：06 文档的阶段 C（生成）S6 要往 filter_reason 写原因，
-- 而 S6 必须先知道「这条原因属于哪个阶段」。表可以先建，业务代码等 C/D 完成后写。
--
-- 三条口径（07 §3，已定）：
--   A1 技术性淘汰（constraint_rule）与业务性否决（decision_veto_rule）分两层
--   A2 证据包只装引用，不装决策产物
--   A3 风险接受逐候选逐风险点，must_verify 项不可接受
-- ===========================================================================

USE synpharm_core;

-- ---------------------------------------------------------------------------
-- 1. 证据包：逐候选一份，不可变（A2 —— 只装引用与摘要）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_evidence_bundle;
DELIMITER $$
CREATE PROCEDURE sp_create_evidence_bundle()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'evidence_bundle') THEN
        CREATE TABLE evidence_bundle (
            id                     BIGINT       NOT NULL AUTO_INCREMENT,
            bundle_no              VARCHAR(64)  NOT NULL,
            project_id             BIGINT       NOT NULL,
            round_id               BIGINT       NULL,
            candidate_id           BIGINT       NOT NULL,
            baseline_snapshot_id   BIGINT       NOT NULL COMMENT '需求基线：判定阈值来源',
            evaluation_freeze_id   BIGINT       NOT NULL COMMENT '评估冻结快照：分数来源',
            lineage_json           JSON         NULL COMMENT 'root到当前 的 id + edit_reason，只存引用',
            constraint_verdict_json JSON        NULL COMMENT '[{ruleId,operator,threshold,tolerance,policy,outcome,deviation}]',
            risk_flags_json        JSON         NULL COMMENT '[{riskCode,severity,mustVerify,source}]',
            pending_items_json     JSON         NULL COMMENT '客观遗漏项（由 must_verify 推导）',
            covers_metrics         INT          NOT NULL DEFAULT 0 COMMENT '已覆盖指标数（缺数据不补零，UI 必须显式展示）',
            completeness           VARCHAR(16)  NOT NULL DEFAULT 'OK' COMMENT 'OK / PARTIAL / INCOMPLETE',
            content_hash           CHAR(64)     NOT NULL COMMENT '以上内容 sha256（不含时间，保证幂等）',
            status                 VARCHAR(16)  NOT NULL DEFAULT 'FROZEN' COMMENT 'FROZEN / SUPERSEDED',
            frozen_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_bundle_no (bundle_no),
            UNIQUE KEY uk_candidate_hash (candidate_id, content_hash),
            KEY idx_project_round (project_id, round_id),
            KEY idx_freeze (evaluation_freeze_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候选证据包（不可变，决策唯一输入）';
    END IF;
END$$
DELIMITER ;
CALL sp_create_evidence_bundle();
DROP PROCEDURE sp_create_evidence_bundle;

-- ---------------------------------------------------------------------------
-- 2. 业务性否决规则（A1 第二层）
--    注意与 constraint_rule 的分工：
--      constraint_rule   = 技术性淘汰，随分子形式走，算法负责人维护，S6/E5 生效
--      decision_veto_rule = 业务性否决，随项目走，项目负责人维护，D3 生效
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_decision_veto_rule;
DELIMITER $$
CREATE PROCEDURE sp_create_decision_veto_rule()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'decision_veto_rule') THEN
        CREATE TABLE decision_veto_rule (
            id             BIGINT       NOT NULL AUTO_INCREMENT,
            -- 全局规则用 0 而不是 NULL：唯一键含 NULL 时 MySQL 不做去重，
            -- NULL 种子每次重跑都会再插一遍（实测第二遍变成 12 条）
            project_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '0 = 全局默认规则',
            rule_code      VARCHAR(40)  NOT NULL COMMENT 'MUST_VERIFY_UNVERIFIED / BUDGET_EXCEEDED / FTO_RISK_HIGH / DATA_INCOMPLETE / HARD_RULE_VIOLATED / MANUAL_VETO',
            severity       VARCHAR(10)  NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK 不可绕过 / WARN 可接受但留痕',
            dimension      VARCHAR(30)  NULL,
            threshold_num  DECIMAL(12,4) NULL,
            params_json    JSON         NULL,
            human_readable VARCHAR(500) NOT NULL COMMENT '红线 R2：不允许存在用户看不到的规则',
            enabled        TINYINT      NOT NULL DEFAULT 1,
            sort_no        INT          NOT NULL DEFAULT 0,
            created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_project_rule (project_id, rule_code),
            KEY idx_project_enabled (project_id, enabled)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='决策阶段业务性否决规则';
    END IF;
END$$
DELIMITER ;
CALL sp_create_decision_veto_rule();
DROP PROCEDURE sp_create_decision_veto_rule;

-- 早期版本用 NULL 表示全局规则，会导致重跑时种子重复插入。
-- 这张表是阶段 E 新建的、还没有业务数据，因此直接清掉旧语义的种子行再改列。
DROP PROCEDURE IF EXISTS sp_fix_decision_veto_rule_null;
DELIMITER $$
CREATE PROCEDURE sp_fix_decision_veto_rule_null()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'decision_veto_rule'
                     AND COLUMN_NAME = 'project_id' AND IS_NULLABLE = 'NO') THEN
        DELETE FROM decision_veto_rule WHERE project_id IS NULL;
        ALTER TABLE decision_veto_rule
            MODIFY COLUMN project_id BIGINT NOT NULL DEFAULT 0 COMMENT '0 = 全局默认规则';
    END IF;
END$$
DELIMITER ;
CALL sp_fix_decision_veto_rule_null();
DROP PROCEDURE sp_fix_decision_veto_rule_null;

-- ---------------------------------------------------------------------------
-- 3. 决策记录：一次决策 = 一行
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_decision_record;
DELIMITER $$
CREATE PROCEDURE sp_create_decision_record()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'decision_record') THEN
        CREATE TABLE decision_record (
            id                   BIGINT       NOT NULL AUTO_INCREMENT,
            decision_no          VARCHAR(64)  NOT NULL,
            project_id           BIGINT       NOT NULL,
            round_id             BIGINT       NULL,
            baseline_snapshot_id BIGINT       NOT NULL,
            report_id            BIGINT       NULL COMMENT '关联正式报告（报告审批与候选决策分离）',
            weights_snapshot     JSON         NOT NULL COMMENT '排序用的指标权重快照（B4 回放必需）',
            rule_version         VARCHAR(32)  NOT NULL COMMENT '排序+否决规则版本（B4 回放必需）',
            input_snapshot_hash  CHAR(64)     NOT NULL COMMENT '全部证据包 hash 排序聚合（B4 回放必需）',
            replay_hash          CHAR(64)     NULL COMMENT '确认时重算结果的 hash',
            status               VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / CONFIRMED / DEFERRED / CANCELLED',
            selected_count       INT          NOT NULL DEFAULT 0,
            backup_count         INT          NOT NULL DEFAULT 0,
            rejected_count       INT          NOT NULL DEFAULT 0,
            deferred_count       INT          NOT NULL DEFAULT 0,
            decision_note        VARCHAR(2000) NULL,
            decided_by           BIGINT       NULL,
            decided_at           DATETIME     NULL,
            revision_of          BIGINT       NULL COMMENT '上一版决策（CONFIRMED 后改动走新版本，不覆盖）',
            version_no           INT          NOT NULL DEFAULT 1,
            created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_decision_no (decision_no),
            KEY idx_project_round (project_id, round_id),
            KEY idx_status (status),
            KEY idx_revision (revision_of)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='决策记录（CONFIRMED 后不可变）';
    END IF;
END$$
DELIMITER ;
CALL sp_create_decision_record();
DROP PROCEDURE sp_create_decision_record;

-- ---------------------------------------------------------------------------
-- 4. 决策-候选明细
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_decision_candidate;
DELIMITER $$
CREATE PROCEDURE sp_create_decision_candidate()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'decision_candidate') THEN
        CREATE TABLE decision_candidate (
            id                   BIGINT       NOT NULL AUTO_INCREMENT,
            decision_id          BIGINT       NOT NULL,
            candidate_id         BIGINT       NOT NULL,
            evidence_bundle_id   BIGINT       NOT NULL,
            bucket               VARCHAR(16)  NOT NULL COMMENT 'FULL_PASS / TOLERANCE / FAIL',
            rank_no              INT          NULL COMMENT '桶内排名；FAIL 桶为 NULL',
            weighted_score       DECIMAL(8,4) NULL,
            covers_metrics       INT          NOT NULL DEFAULT 0,
            missing_metrics_json JSON         NULL,
            outcome              VARCHAR(16)  NOT NULL COMMENT 'SELECTED / BACKUP / REJECTED / DEFERRED',
            reject_reason        VARCHAR(40)  NULL COMMENT 'FILTER_REASON 字典（stage=DECISION）',
            manual_override      TINYINT      NOT NULL DEFAULT 0,
            override_reason      VARCHAR(500) NULL COMMENT 'manual_override=1 时必填',
            created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_decision_candidate (decision_id, candidate_id),
            KEY idx_decision_outcome (decision_id, outcome),
            KEY idx_candidate (candidate_id),
            KEY idx_bundle (evidence_bundle_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='决策明细（候选级取舍）';
    END IF;
END$$
DELIMITER ;
CALL sp_create_decision_candidate();
DROP PROCEDURE sp_create_decision_candidate;

-- ---------------------------------------------------------------------------
-- 5. 否决命中明细
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_decision_veto_hit;
DELIMITER $$
CREATE PROCEDURE sp_create_decision_veto_hit()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'decision_veto_hit') THEN
        CREATE TABLE decision_veto_hit (
            id           BIGINT       NOT NULL AUTO_INCREMENT,
            decision_id  BIGINT       NOT NULL,
            candidate_id BIGINT       NOT NULL,
            rule_code    VARCHAR(40)  NOT NULL,
            severity     VARCHAR(10)  NOT NULL,
            message      VARCHAR(500) NOT NULL,
            evidence_ref VARCHAR(200) NULL COMMENT '指向证据包里具体字段，便于追溯',
            created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            KEY idx_decision_candidate (decision_id, candidate_id),
            KEY idx_rule (rule_code)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='否决命中明细';
    END IF;
END$$
DELIMITER ;
CALL sp_create_decision_veto_hit();
DROP PROCEDURE sp_create_decision_veto_hit;

-- ---------------------------------------------------------------------------
-- 6. 风险接受（A3 —— 逐候选逐风险点）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_create_risk_acceptance;
DELIMITER $$
CREATE PROCEDURE sp_create_risk_acceptance()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'risk_acceptance') THEN
        CREATE TABLE risk_acceptance (
            id                 BIGINT        NOT NULL AUTO_INCREMENT,
            decision_id        BIGINT        NOT NULL,
            candidate_id       BIGINT        NOT NULL,
            risk_code          VARCHAR(40)   NOT NULL,
            severity_at_accept VARCHAR(10)   NOT NULL,
            reason             VARCHAR(1000) NOT NULL COMMENT '接受理由（必填：没有理由的接受等于没记录）',
            accepted_by        BIGINT        NOT NULL,
            accepted_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
            valid_until        DATE          NULL COMMENT 'NULL = 本决策内长期有效',
            PRIMARY KEY (id),
            UNIQUE KEY uk_accept (decision_id, candidate_id, risk_code),
            KEY idx_candidate (candidate_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险接受记录（must_verify 项不可接受）';
    END IF;
END$$
DELIMITER ;
CALL sp_create_risk_acceptance();
DROP PROCEDURE sp_create_risk_acceptance;

-- ---------------------------------------------------------------------------
-- 7. 改造 design_report：报告级审批与候选级决策分离（07 §9 方案②）
--    注意：既有 idx_decision 是 (project_id, decision) 上的报告审批索引，不能重名。
--    列与索引分开判断：早期版本可能只加上了列而索引建失败，重跑要能补齐。
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_design_report_decision_id;
DELIMITER $$
CREATE PROCEDURE sp_design_report_decision_id()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'design_report'
                     AND COLUMN_NAME = 'decision_id') THEN
        ALTER TABLE design_report
            ADD COLUMN decision_id BIGINT NULL COMMENT '候选级决策记录（报告结论的来源）'
                AFTER decision_note;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'design_report'
                     AND INDEX_NAME = 'idx_report_decision_id') THEN
        ALTER TABLE design_report ADD KEY idx_report_decision_id (decision_id);
    END IF;
END$$
DELIMITER ;
CALL sp_design_report_decision_id();
DROP PROCEDURE sp_design_report_decision_id;

-- ---------------------------------------------------------------------------
-- 7.5 design_project 补「决策名额档位」（B2 可配置，07 §4）
--
--     名额 = min(湿实验余额, 按档位算出的推荐数)
--     档位两种模式：
--       RATIO    —— 按候选数比例，value 取 0~1（默认 0.200 = 20%）
--       ABSOLUTE —— 固定个数，value 取正整数
--
--     为什么放 project 而不是写死在代码里：不同靶点、不同阶段的可承受实验量差很多，
--     写死 20% 会让"小项目推不出一个"和"大项目推一堆测不完"同时出现。
--     这两个字段进基线块7（quota_json），随快照冻结 —— 用户中途改档位不影响已提交的决策。
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_design_project_decision_capacity;
DELIMITER $$
CREATE PROCEDURE sp_design_project_decision_capacity()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'design_project'
                     AND COLUMN_NAME = 'decision_capacity_mode') THEN
        ALTER TABLE design_project
            ADD COLUMN decision_capacity_mode VARCHAR(16) NOT NULL DEFAULT 'RATIO'
                COMMENT '决策名额档位：RATIO=按候选数比例 / ABSOLUTE=固定个数'
                AFTER min_candidates;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'design_project'
                     AND COLUMN_NAME = 'decision_capacity_value') THEN
        ALTER TABLE design_project
            ADD COLUMN decision_capacity_value DECIMAL(8,3) NOT NULL DEFAULT 0.200
                COMMENT 'RATIO 时是 0~1 的比例，ABSOLUTE 时是个数；最终仍受湿实验余额约束'
                AFTER decision_capacity_mode;
    END IF;
END$$
DELIMITER ;
CALL sp_design_project_decision_capacity();
DROP PROCEDURE sp_design_project_decision_capacity;

-- ---------------------------------------------------------------------------
-- 8. FILTER_REASON 补 stage 元数据（A1）
--    存 meta_json 而不是新增列：stage 只用于过滤与展示，不参与业务判定。
--
--    现状：库里 15 条**全部是生成/编辑阶段**的原因码（长度越界 → 多样性配额），
--    所以先整批置 GENERATION，再补 6 条决策专用码。
-- ---------------------------------------------------------------------------
UPDATE design_library_item
   SET meta_json = JSON_SET(COALESCE(meta_json, JSON_OBJECT()), '$.stage', 'GENERATION')
 WHERE category = 'FILTER_REASON'
   AND (meta_json IS NULL OR JSON_EXTRACT(meta_json, '$.stage') IS NULL);

-- 决策阶段的淘汰原因（decision_candidate.reject_reason 引用这里）
INSERT INTO design_library_item
    (category, code, name, name_en, level_no, path, meta_json, sort_no, enabled, builtin)
VALUES
    ('FILTER_REASON', 'VETO_BUDGET_CUT', '名额不足转备选', 'Cut by decision capacity', 1,
     '/FILTER_REASON/VETO_BUDGET_CUT', JSON_OBJECT('stage', 'DECISION', 'severity', 'WARN'), 210, 1, 1),
    ('FILTER_REASON', 'VETO_FTO_RISK', 'FTO 风险否决', 'FTO risk veto', 1,
     '/FILTER_REASON/VETO_FTO_RISK', JSON_OBJECT('stage', 'DECISION', 'severity', 'BLOCK'), 220, 1, 1),
    ('FILTER_REASON', 'VETO_MUST_VERIFY_MISSING', '必测项未验证', 'Must-verify item missing', 1,
     '/FILTER_REASON/VETO_MUST_VERIFY_MISSING', JSON_OBJECT('stage', 'DECISION', 'severity', 'BLOCK'), 230, 1, 1),
    ('FILTER_REASON', 'VETO_DATA_INCOMPLETE', '证据包不完整', 'Evidence incomplete', 1,
     '/FILTER_REASON/VETO_DATA_INCOMPLETE', JSON_OBJECT('stage', 'DECISION', 'severity', 'BLOCK'), 240, 1, 1),
    ('FILTER_REASON', 'VETO_HARD_RECHECK_FAILED', '复核不通过', 'Hard rule recheck failed', 1,
     '/FILTER_REASON/VETO_HARD_RECHECK_FAILED', JSON_OBJECT('stage', 'DECISION', 'severity', 'BLOCK'), 250, 1, 1),
    ('FILTER_REASON', 'VETO_MANUAL', '人工否决', 'Manual veto', 1,
     '/FILTER_REASON/VETO_MANUAL', JSON_OBJECT('stage', 'DECISION', 'severity', 'BLOCK'), 260, 1, 1)
ON DUPLICATE KEY UPDATE
    name      = VALUES(name),
    name_en   = VALUES(name_en),
    meta_json = VALUES(meta_json),
    sort_no   = VALUES(sort_no);

-- ---------------------------------------------------------------------------
-- 9. 否决规则种子（6 条，全局默认；项目级可覆盖）
--    human_readable 必填 —— 红线 R2：不允许存在用户看不到的规则
--    project_id = 0 表示全局（不用 NULL，见上：唯一键含 NULL 不去重）
-- ---------------------------------------------------------------------------
INSERT INTO decision_veto_rule
    (project_id, rule_code, severity, dimension, threshold_num, params_json, human_readable, enabled, sort_no)
VALUES
    (0, 'MUST_VERIFY_UNVERIFIED', 'BLOCK', NULL, NULL,
     JSON_OBJECT('requireExperiment', TRUE),
     '项目标记为「必须验证」的风险点若没有实测证据，该候选不得入选（只能补数据或转暂缓）', 1, 10),
    (0, 'BUDGET_EXCEEDED', 'WARN', NULL, NULL,
     JSON_OBJECT('quotaField', 'quota_wet_lab'),
     '排名超出剩余湿实验名额的候选只能作为备选，不占用名额', 1, 20),
    (0, 'FTO_RISK_HIGH', 'BLOCK', NULL, 0.8500,
     JSON_OBJECT('metricField', 'fto_similarity_max'),
     '与专利序列相似度超过 0.85 视为 FTO 高风险，不得入选', 1, 30),
    (0, 'DATA_INCOMPLETE', 'BLOCK', NULL, NULL,
     JSON_OBJECT('requireMetrics', JSON_ARRAY()),
     '证据包缺少关键评估指标时不得入选（缺哪些由规则参数指定，为空表示只判完整性等级）', 1, 40),
    (0, 'HARD_RULE_VIOLATED', 'BLOCK', NULL, NULL,
     JSON_OBJECT('source', 'D2_RECHECK'),
     '决策阶段复核发现技术性硬约束未达标（兜底：防止生成/编辑阶段漏记原因）', 1, 50),
    (0, 'MANUAL_VETO', 'BLOCK', NULL, NULL,
     JSON_OBJECT('requireReason', TRUE),
     '由人录入的否决，必须填写理由', 1, 60)
ON DUPLICATE KEY UPDATE
    severity       = VALUES(severity),
    threshold_num  = VALUES(threshold_num),
    params_json    = VALUES(params_json),
    human_readable = VALUES(human_readable),
    enabled        = VALUES(enabled),
    sort_no        = VALUES(sort_no);

-- ---------------------------------------------------------------------------
-- 10. 验收查询
-- ---------------------------------------------------------------------------
-- 10.1 六张新表都在
-- SELECT TABLE_NAME FROM information_schema.TABLES
--  WHERE TABLE_SCHEMA = 'synpharm_core'
--    AND TABLE_NAME IN ('evidence_bundle','decision_veto_rule','decision_record',
--                       'decision_candidate','decision_veto_hit','risk_acceptance');
--
-- 10.2 design_report 有 decision_id
-- SELECT COLUMN_NAME, IS_NULLABLE FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA='synpharm_core' AND TABLE_NAME='design_report' AND COLUMN_NAME='decision_id';
--
-- 10.3 FILTER_REASON 按阶段可查（期望：GENERATION 15 条 + DECISION 6 条）
-- SELECT JSON_UNQUOTE(JSON_EXTRACT(meta_json,'$.stage')) AS stage, COUNT(*) AS n
--   FROM design_library_item WHERE category='FILTER_REASON' GROUP BY stage;
--
-- 10.4 否决规则 6 条
-- SELECT rule_code, severity, human_readable FROM decision_veto_rule WHERE project_id = 0 ORDER BY sort_no;
--
-- 10.5 决策名额档位列
-- SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA='synpharm_core' AND TABLE_NAME='design_project'
--    AND COLUMN_NAME IN ('decision_capacity_mode','decision_capacity_value');
