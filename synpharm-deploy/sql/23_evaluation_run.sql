-- =============================================
-- AI 药物分子设计平台 · 评估任务
-- 版本：v1.0  依赖：11_design_project / 13_design_round
-- 来源：docs/modules/design/06-评估器.md §6.2
--
-- metric_weights 是「权重快照」：保证同一 run 重算得到同样的分数（可复现，红线 R1/R2）。
-- job_id 由 36_design_domain_revision.sql 追加。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS evaluation_run (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_no          VARCHAR(64)   NOT NULL COMMENT '评估任务编号 E+yyMMddHHmmss+随机',
    project_id      BIGINT        NOT NULL,
    round_id        BIGINT                 COMMENT '属于哪一轮',
    user_id         BIGINT        NOT NULL,
    scope           VARCHAR(20)   NOT NULL DEFAULT 'SELECTED' COMMENT 'ALL/SELECTED/INCREMENTAL',
    candidate_ids   JSON                   COMMENT 'scope=SELECTED 时的候选 id 列表',
    enabled_metrics VARCHAR(1000) NOT NULL COMMENT '启用的指标码列表（逗号分隔）',
    metric_weights  JSON                   COMMENT '本次使用的权重快照（防止后续改权重导致历史不可复现）',
    total_count     INT           NOT NULL DEFAULT 0,
    evaluated_count INT           NOT NULL DEFAULT 0,
    rejected_count  INT           NOT NULL DEFAULT 0,
    qualified_count INT           NOT NULL DEFAULT 0,
    top_count       INT           NOT NULL DEFAULT 0,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                    COMMENT 'PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED',
    progress        DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    metric_stats    JSON                   COMMENT '指标分布统计（均值/中位/最大/最小）',
    error_msg       VARCHAR(1000),
    latency_ms      INT,
    started_at      DATETIME,
    completed_at    DATETIME,
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_eval_run_no (run_no),
    KEY idx_project_round (project_id, round_id),
    KEY idx_project_status (project_id, status),
    CONSTRAINT fk_ev_project FOREIGN KEY (project_id) REFERENCES design_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评估任务';
