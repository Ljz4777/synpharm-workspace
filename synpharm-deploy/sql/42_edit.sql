-- ===========================================================================
-- 42_edit.sql —— 最小评估 + 编辑阶段（06-主业务三阶段设计方案.md §5）
-- ===========================================================================
-- 适用范围：synpharm_core
-- 幂等：全部走 information_schema 守卫，可重复执行
-- 执行账号：**必须 root**（DROP/CREATE PROCEDURE 与 PREPARE 动态 DDL 需要更高权限）
--
-- 本脚本建 6 张表 + 改造 2 张表：
--   evaluation_freeze          评估冻结快照（编辑与决策的**唯一**输入）
--   edit_action_mapping        缺口→动作映射表（核心资产，编辑引擎全从它读）
--   edit_gap_report            E1 缺口诊断
--   edit_impact_assessment     E3 影响面（回归守卫）
--   edit_batch                 E4/E5 编辑批次
--   edit_delta_report          E6 前后变化
--   candidate_molecule         +5 列（版本号 / 编辑轮数 / 分支状态 / 待重评 / 评分配置）
--   molecule_edit_log          +7 列（缺口 / 批次 / 影响面 / delta / 轮次 / 预算 / 合规）
--
-- 与 06 §5.4 的一处差异：candidate_molecule 额外加 version_no。
-- 原因：§5.4 的 evaluation_freeze 与 edit_delta_report 都以 (candidate_id, version_no)
-- 定位版本，但 candidate_molecule 原本只用 parent_mol_id 表达血缘，没有版本号列。
-- 每次靠血缘递归推版本既慢又易错，故显式落列。已回写 06 §5.6。
-- ===========================================================================

USE synpharm_core;

-- ---------------------------------------------------------------------------
-- 0. 辅助过程：按需加列
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
-- 1. evaluation_freeze —— 评估冻结快照
-- ---------------------------------------------------------------------------
-- 为什么必须要「冻结」：编辑的输入契约是 snapshot_id，不是「当前分数」。
-- 否则同一候选在两次评估之间改了阈值，改前改后就不可比，delta 报告失去意义。
CREATE TABLE IF NOT EXISTS evaluation_freeze (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT,
    freeze_no       VARCHAR(64)  NOT NULL,
    project_id      BIGINT       NOT NULL,
    candidate_id    BIGINT       NOT NULL,
    version_no      INT          NOT NULL DEFAULT 1 COMMENT '被评估的候选版本',
    eval_run_id     BIGINT                COMMENT '来源 evaluation_run（最小评估可为空）',
    scores_json     JSON         NOT NULL COMMENT '{metric: {value, normalized, passed, risk}}',
    hard_passed     TINYINT      NOT NULL DEFAULT 1,
    total_score     DECIMAL(7,3)          COMMENT '综合分（权重归一化后 0~100）',
    weight_coverage DECIMAL(5,4)          COMMENT '可算权重占比：total_score 只代表这部分，不是全部',
    missing_metrics JSON                  COMMENT '权重里有但算不出来的指标（诚实记账）',
    skipped_rules   JSON                  COMMENT '未能判定的规则码（没检查 ≠ 检查通过）',
    engine_versions JSON                  COMMENT '各指标引擎版本',
    calibration_id  VARCHAR(64)           COMMENT '校准工件（若有）',
    content_hash    CHAR(64)     NOT NULL COMMENT '快照内容哈希：同哈希即同结论',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_freeze_no (freeze_no),
    KEY idx_candidate_version (candidate_id, version_no),
    KEY idx_project (project_id)
) COMMENT='评估冻结快照（编辑与决策的唯一输入）';

-- ---------------------------------------------------------------------------
-- 2. edit_action_mapping —— 缺口→动作映射（可配置规则）
-- ---------------------------------------------------------------------------
-- gap_cause 用 '' 而不是 NULL：MySQL 唯一键对 NULL 不去重，种子会被反复插入。
-- 40_decision.sql 的 decision_veto_rule 踩过同一个坑。
CREATE TABLE IF NOT EXISTS edit_action_mapping (
    id                  BIGINT       PRIMARY KEY AUTO_INCREMENT,
    gap_dimension       VARCHAR(30)  NOT NULL COMMENT '缺口维度：AFFINITY/AGGREGATION/EXPRESSION/…',
    gap_cause           VARCHAR(40)  NOT NULL DEFAULT '' COMMENT '归因码；''=不限',
    primary_action      VARCHAR(30)  NOT NULL COMMENT '首选动作：MUTATION/SIDE_CHAIN/HUMANIZATION/…',
    fallback_action     VARCHAR(30)  NULL     COMMENT '次选动作',
    applies_to_modality VARCHAR(60)  NOT NULL DEFAULT '*' COMMENT '适用形式，逗号分隔',
    required_structures JSON         NULL     COMMENT '需要的结构条件，如 ["COMPLEX"]',
    side_effects_json   JSON         NULL     COMMENT '{"AFFINITY":"MUST_DROP"} 副作用矩阵',
    success_rate        DECIMAL(5,4) NOT NULL DEFAULT 0.5000 COMMENT '历史成功率（回流更新）',
    sample_size         INT          NOT NULL DEFAULT 0 COMMENT '统计样本量；0=估计值',
    priority            INT          NOT NULL DEFAULT 100,
    enabled             TINYINT      NOT NULL DEFAULT 1,
    deleted             TINYINT      NOT NULL DEFAULT 0,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_mapping (gap_dimension, gap_cause, primary_action, applies_to_modality),
    KEY idx_dimension (gap_dimension, enabled)
) COMMENT='缺口→动作映射表（可配置规则）';

-- ---------------------------------------------------------------------------
-- 2.5 evaluation_freeze 补列（表可能已按旧定义建过，CREATE IF NOT EXISTS 会跳过新列）
-- ---------------------------------------------------------------------------
CALL sp_add_column_if_missing('evaluation_freeze', 'weight_coverage',    'weight_coverage DECIMAL(5,4) NULL
     COMMENT ''可算权重占比：total_score 只代表这部分，不是全部''');

-- ---------------------------------------------------------------------------
-- 3. edit_gap_report —— E1 缺口诊断
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS edit_gap_report (
    id                BIGINT       PRIMARY KEY AUTO_INCREMENT,
    report_no         VARCHAR(64)  NOT NULL,
    project_id        BIGINT       NOT NULL,
    candidate_id      BIGINT       NOT NULL,
    version_no        INT          NOT NULL DEFAULT 1,
    eval_freeze_id    BIGINT       NOT NULL COMMENT '只认冻结快照',
    gaps_json         JSON         NOT NULL COMMENT '缺口数组（含 attribution/editable/difficulty）',
    no_gap_json       JSON         NULL     COMMENT '已达标的维度（保护名单）',
    unfixable_json    JSON         NULL     COMMENT '当前手段修不了的缺口',
    recommended_order JSON         NULL     COMMENT '建议修复顺序',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_gap_no (report_no),
    KEY idx_candidate_version (candidate_id, version_no),
    KEY idx_project (project_id)
) COMMENT='编辑缺口诊断报告';

-- ---------------------------------------------------------------------------
-- 4. edit_impact_assessment —— E3 影响面（回归守卫）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS edit_impact_assessment (
    id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    gap_report_id  BIGINT       NOT NULL,
    candidate_id   BIGINT       NOT NULL,
    action_code    VARCHAR(30)  NOT NULL,
    target_region  VARCHAR(200) NULL,
    protected_json JSON         NOT NULL COMMENT '{dimension: {predicted_delta, threshold, risk}}',
    guard_result   VARCHAR(10)  NOT NULL COMMENT 'PASS/WARN/BLOCK',
    block_reason   VARCHAR(500) NULL,
    overridden     TINYINT      NOT NULL DEFAULT 0 COMMENT '负责人手动解锁',
    override_by    BIGINT       NULL,
    override_note  VARCHAR(500) NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_gap (gap_report_id),
    KEY idx_candidate (candidate_id)
) COMMENT='编辑动作影响面评估（回归守卫）';

-- ---------------------------------------------------------------------------
-- 5. edit_batch —— E4/E5 编辑批次
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS edit_batch (
    id                   BIGINT       PRIMARY KEY AUTO_INCREMENT,
    batch_no             VARCHAR(64)  NOT NULL,
    project_id           BIGINT       NOT NULL,
    round_id             BIGINT       NULL,
    parent_version       INT          NOT NULL COMMENT '本批基于哪个版本',
    action_codes         VARCHAR(200) NOT NULL COMMENT '本批使用的动作，逗号分隔（无默认值：必须显式给出）',
    params_json          JSON         NULL     COMMENT '编辑预算/允许区域/枚举方式',
    design_space_json    JSON         NULL     COMMENT 'E4：规模/剪枝后/每动作方案数',
    input_candidate_ids  JSON         NULL     COMMENT '输入候选',
    output_version_count INT          NOT NULL DEFAULT 0,
    filtered_out_count   INT          NOT NULL DEFAULT 0,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                         COMMENT 'PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED',
    job_id               BIGINT       NULL,
    error_msg            VARCHAR(1000) NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at         DATETIME     NULL,
    UNIQUE KEY uk_batch_no (batch_no),
    KEY idx_project (project_id, created_at)
) COMMENT='编辑批次';

-- ---------------------------------------------------------------------------
-- 6. edit_delta_report —— E6 前后变化
-- ---------------------------------------------------------------------------
-- residual_json 是判断「是否进下一轮」的唯一依据，不是装饰字段。
CREATE TABLE IF NOT EXISTS edit_delta_report (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    report_no        VARCHAR(64)  NOT NULL,
    edit_batch_id    BIGINT       NOT NULL,
    candidate_id     BIGINT       NOT NULL,
    parent_version   INT          NOT NULL,
    child_version    INT          NOT NULL,
    parent_freeze_id BIGINT       NULL COMMENT '改前评估快照',
    child_freeze_id  BIGINT       NULL COMMENT '改后评估快照',
    delta_json       JSON         NOT NULL COMMENT '{dimension: {before, after, delta, threshold, ok}}',
    residual_json    JSON         NULL     COMMENT '剩余缺口',
    guard_result     VARCHAR(10)  NOT NULL DEFAULT 'PASS',
    verdict          VARCHAR(20)  NULL     COMMENT 'PASS/PASS_WITH_RESIDUAL/FAIL',
    agent_report_id  BIGINT       NULL     COMMENT 'E7 Agent 文案（agent_report.id）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_delta_no (report_no),
    KEY idx_batch (edit_batch_id),
    KEY idx_candidate (candidate_id)
) COMMENT='编辑前后变化报告';

-- ---------------------------------------------------------------------------
-- 7. candidate_molecule 改造（06 §5.6，+ version_no）
-- ---------------------------------------------------------------------------
CALL sp_add_column_if_missing('candidate_molecule', 'version_no',
    'version_no INT NOT NULL DEFAULT 1
     COMMENT ''版本号（root=1，每个子版本 = 父版本+1）；版本树与 delta 的定位键''');
CALL sp_add_column_if_missing('candidate_molecule', 'edit_round',
    'edit_round INT NOT NULL DEFAULT 0 COMMENT ''已编辑轮数（上限默认 5，阈值在基线快照）''');
CALL sp_add_column_if_missing('candidate_molecule', 'branch_status',
    'branch_status VARCHAR(20) NOT NULL DEFAULT ''active''
     COMMENT ''active/abandoned（放弃的分支保留但不参与后续）''');
CALL sp_add_column_if_missing('candidate_molecule', 'needs_re_eval',
    'needs_re_eval TINYINT NOT NULL DEFAULT 0 COMMENT ''约束变更后置 1''');
CALL sp_add_column_if_missing('candidate_molecule', 'eval_profile_id',
    'eval_profile_id BIGINT NULL COMMENT ''分支横向可比的前提：同一评分配置''');

-- ---------------------------------------------------------------------------
-- 8. molecule_edit_log 改造（06 §5.5，7 列）
-- ---------------------------------------------------------------------------
CALL sp_add_column_if_missing('molecule_edit_log', 'gap_id',
    'gap_id BIGINT NULL COMMENT ''哪条缺口驱动了这次编辑''');
CALL sp_add_column_if_missing('molecule_edit_log', 'edit_batch_id',
    'edit_batch_id BIGINT NULL COMMENT ''所属编辑批次''');
CALL sp_add_column_if_missing('molecule_edit_log', 'impact_id',
    'impact_id BIGINT NULL COMMENT ''影响面评估记录''');
CALL sp_add_column_if_missing('molecule_edit_log', 'delta_id',
    'delta_id BIGINT NULL COMMENT ''变化报告''');
CALL sp_add_column_if_missing('molecule_edit_log', 'round_no',
    'round_no INT NULL COMMENT ''第几轮编辑（上限默认 5）''');
CALL sp_add_column_if_missing('molecule_edit_log', 'budget_used',
    'budget_used INT NULL COMMENT ''本次占用几个可改位置''');
CALL sp_add_column_if_missing('molecule_edit_log', 'compliance_violations',
    'compliance_violations JSON NULL COMMENT ''编辑后合规检查违例明细''');

-- ---------------------------------------------------------------------------
-- 9. 索引：按版本筛候选 / 按轮次筛编辑日志
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_edit_indexes;
DELIMITER $$
CREATE PROCEDURE sp_edit_indexes()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'candidate_molecule'
                     AND INDEX_NAME = 'idx_version') THEN
        ALTER TABLE candidate_molecule ADD KEY idx_version (project_id, version_no);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'candidate_molecule'
                     AND INDEX_NAME = 'idx_branch') THEN
        ALTER TABLE candidate_molecule ADD KEY idx_branch (project_id, branch_status);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'molecule_edit_log'
                     AND INDEX_NAME = 'idx_edit_batch') THEN
        ALTER TABLE molecule_edit_log ADD KEY idx_edit_batch (edit_batch_id);
    END IF;
END$$
DELIMITER ;
CALL sp_edit_indexes();
DROP PROCEDURE sp_edit_indexes;

-- ---------------------------------------------------------------------------
-- 10. edit_action_mapping 种子（06 §5.3 E2 表）
-- ---------------------------------------------------------------------------
-- success_rate 初始值是**估计值**且 sample_size=0：回流数据接入后才更新。
-- 不写成「看起来像实测」的数字，是为了让前端能如实标出「这是先验不是统计」。
INSERT INTO edit_action_mapping
    (gap_dimension, gap_cause, primary_action, fallback_action, applies_to_modality,
     required_structures, side_effects_json, success_rate, sample_size, priority)
SELECT * FROM (
    SELECT 'AFFINITY'          AS d, '' AS c, 'MUTATION'           AS p, 'INTERFACE_REDESIGN' AS f,
           '*'                 AS m, NULL AS r, '{"IMMUNOGENICITY":"WATCH","STABILITY":"WATCH"}' AS s,
           0.4100 AS sr, 0 AS n, 10 AS pr
    UNION ALL SELECT 'AGGREGATION',      '', 'SIDE_CHAIN',         'MUTATION',
           'NANOBODY,ANTIBODY',        NULL, '{"AFFINITY":"MUST_DROP"}',        0.5800, 0, 20
    UNION ALL SELECT 'EXPRESSION',       '', 'MUTATION',           NULL,
           '*',                        NULL, '{"STABILITY":"WATCH"}',            0.5200, 0, 30
    UNION ALL SELECT 'IMMUNOGENICITY',   '', 'HUMANIZATION',       NULL,
           'ANTIBODY,BISPECIFIC,NANOBODY', '[{"type":"COMPLEX"}]', '{"AFFINITY":"MUST_DROP"}', 0.3500, 0, 40
    UNION ALL SELECT 'SELECTIVITY',      '', 'INTERFACE_REDESIGN', NULL,
           '*',                        '[{"type":"COMPLEX"}]', '{"AFFINITY":"MUST_DROP"}',    0.2900, 0, 50
    UNION ALL SELECT 'STABILITY',        '', 'LINKER',             'GLYCO_OPT',
           'BISPECIFIC,ADC,FUSION_PROTEIN', NULL, '{"AFFINITY":"WATCH"}',           0.4400, 0, 60
    UNION ALL SELECT 'SYNTHESIZABILITY', '', 'SCAFFOLD_HOP',       NULL,
           'SMALL_MOLECULE',           NULL, '{"AFFINITY":"MUST_DROP"}',           0.3100, 0, 70
) AS seed
WHERE NOT EXISTS (
    SELECT 1 FROM edit_action_mapping t
    WHERE t.gap_dimension = seed.d AND t.gap_cause = seed.c
      AND t.primary_action = seed.p AND t.applies_to_modality = seed.m
);

-- ---------------------------------------------------------------------------
-- 11. FILTER_REASON 字典补编辑阶段原因码
-- ---------------------------------------------------------------------------
-- E5 的筛除原因需要机器码，否则「原因必记」在编辑阶段就是空的。
-- 与 40_decision.sql 一致：stage 放 meta_json（只用于过滤与展示，不参与业务判定）。
INSERT INTO design_library_item
    (category, code, name, name_en, level_no, path, meta_json, sort_no, enabled, builtin)
VALUES
    ('FILTER_REASON', 'EDIT_PARENT_INVALID', '父本结构无法解析', 'Parent unparsable', 1,
     '/FILTER_REASON/EDIT_PARENT_INVALID', JSON_OBJECT('stage', 'EDIT', 'severity', 'BLOCK'), 201, 1, 1),
    ('FILTER_REASON', 'EDIT_GUARD_BLOCKED', '回归守卫拦截', 'Guard blocked', 1,
     '/FILTER_REASON/EDIT_GUARD_BLOCKED', JSON_OBJECT('stage', 'EDIT', 'severity', 'BLOCK'), 202, 1, 1),
    ('FILTER_REASON', 'EDIT_BUDGET_EXCEEDED', '超出可改位置预算', 'Edit budget exceeded', 1,
     '/FILTER_REASON/EDIT_BUDGET_EXCEEDED', JSON_OBJECT('stage', 'EDIT', 'severity', 'WARN'), 203, 1, 1),
    ('FILTER_REASON', 'EDIT_REGION_NOT_ALLOWED', '改动落在允许区域之外', 'Region not allowed', 1,
     '/FILTER_REASON/EDIT_REGION_NOT_ALLOWED', JSON_OBJECT('stage', 'EDIT', 'severity', 'WARN'), 204, 1, 1),
    ('FILTER_REASON', 'EDIT_COMPLIANCE_VIOLATION', '编辑后违反硬约束', 'Compliance violation after edit', 1,
     '/FILTER_REASON/EDIT_COMPLIANCE_VIOLATION', JSON_OBJECT('stage', 'EDIT', 'severity', 'BLOCK'), 205, 1, 1),
    ('FILTER_REASON', 'EDIT_NO_CHANGE', '与父版本无实质差异', 'No effective change', 1,
     '/FILTER_REASON/EDIT_NO_CHANGE', JSON_OBJECT('stage', 'EDIT', 'severity', 'INFO'), 206, 1, 1),
    ('FILTER_REASON', 'EDIT_UNSUPPORTED_MODALITY', '当前形式不支持该动作', 'Action unsupported for modality', 1,
     '/FILTER_REASON/EDIT_UNSUPPORTED_MODALITY', JSON_OBJECT('stage', 'EDIT', 'severity', 'WARN'), 207, 1, 1)
ON DUPLICATE KEY UPDATE
    name      = VALUES(name),
    name_en   = VALUES(name_en),
    meta_json = VALUES(meta_json),
    sort_no   = VALUES(sort_no);

-- ---------------------------------------------------------------------------
-- 12. 清理
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS sp_add_column_if_missing;

-- ---------------------------------------------------------------------------
-- 13. 验收查询
-- ---------------------------------------------------------------------------
-- 13.1 六张新表
-- SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='synpharm_core'
--  AND TABLE_NAME IN ('evaluation_freeze','edit_action_mapping','edit_gap_report',
--                     'edit_impact_assessment','edit_batch','edit_delta_report');
--
-- 13.2 candidate_molecule / molecule_edit_log 新列
-- SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA='synpharm_core'
--    AND ((TABLE_NAME='candidate_molecule' AND COLUMN_NAME IN
--          ('version_no','edit_round','branch_status','needs_re_eval','eval_profile_id'))
--      OR (TABLE_NAME='molecule_edit_log' AND COLUMN_NAME IN
--          ('gap_id','edit_batch_id','impact_id','delta_id','round_no','budget_used','compliance_violations')));
--
-- 13.3 映射表种子（应为 7 条，连跑多遍不变）
-- SELECT gap_dimension, primary_action, success_rate, sample_size FROM edit_action_mapping ORDER BY priority;
--
-- 13.4 编辑阶段原因码（应为 7 条）
-- SELECT code, name FROM design_library_item WHERE category='FILTER_REASON'
--   AND JSON_EXTRACT(payload_json,'$.stage')='EDIT';
