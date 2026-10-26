-- =============================================
-- AI 药物分子设计平台 · 评估结果明细（EAV 模型）
-- 版本：v1.0  依赖：23_evaluation_run / 20_candidate_molecule
-- 来源：docs/modules/design/06-评估器.md §6.3
--
-- uk_run_mol_metric 保证「同一 run 内同一分子同一指标只有一条」⇒ 重复评估幂等
-- （重跑只更新不翻倍，§8.2 验收项）。
-- idx_run_metric_value 支撑「按某指标排序取 TopN」（排行榜主查询）。
--
-- 追加型明细：可热写入；刻意不设 deleted / updated_at。
-- risk_level / realness / source_tool_id 三列由 36_design_domain_revision.sql 追加
-- —— 红线 R1 要求每条结果都能追到「用了哪个工具、真实性属于哪一层」。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS evaluation_result (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id      BIGINT        NOT NULL,
    project_id  BIGINT        NOT NULL COMMENT '冗余，便于直接按项目查询',
    molecule_id BIGINT        NOT NULL,
    inchikey    CHAR(27)               COMMENT '冗余，便于按结构查询；生物大分子为 NULL',
    metric_code VARCHAR(40)   NOT NULL,
    value_num   DECIMAL(14,5)          COMMENT '数值型指标值',
    value_text  VARCHAR(300)           COMMENT '文本/等级型指标值',
    passed      TINYINT                COMMENT '布尔型（约束类）结果；NULL=不适用',
    normalized  DECIMAL(6,5)           COMMENT '归一化后 0-1，用于综合得分',
    grade       VARCHAR(20)            COMMENT '等级：EXCELLENT/GOOD/FAIR/POOR',
    detail_json JSON                   COMMENT '明细，如命中警报的 SMARTS、相互作用残基',
    success     TINYINT       NOT NULL DEFAULT 1 COMMENT '该指标是否算成功（模型不可用/静默兜底时为 0）',
    error_msg   VARCHAR(500),
    latency_ms  INT,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_run_mol_metric (run_id, molecule_id, metric_code),
    KEY idx_project_metric (project_id, metric_code),
    KEY idx_molecule (molecule_id),
    KEY idx_run_metric_value (run_id, metric_code, value_num),
    KEY idx_success (run_id, success),
    CONSTRAINT fk_er_run FOREIGN KEY (run_id)      REFERENCES evaluation_run(id),
    CONSTRAINT fk_er_mol FOREIGN KEY (molecule_id) REFERENCES candidate_molecule(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评估结果明细';
