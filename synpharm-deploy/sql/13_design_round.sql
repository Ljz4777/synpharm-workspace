-- =============================================
-- AI 药物分子设计平台 · 设计迭代轮次
-- 版本：v1.0  依赖：11_design_project
-- 来源：docs/modules/design/01-项目管理与生命周期.md §5.3
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS design_round (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id      BIGINT       NOT NULL,
    round_no        INT          NOT NULL COMMENT '第几轮，从 1 开始',
    strategy_note   TEXT                  COMMENT '本轮策略说明（人工或 PlannerAgent 产出）',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PLANNED'
                    COMMENT 'PLANNED/GENERATING/EDITING/EVALUATING/REVIEWING/CLOSED',
    conclusion_text TEXT                  COMMENT '本轮结论',
    metrics_json    JSON                  COMMENT '本轮指标快照，如 {"top1_binding":8.2,"pass_rate":0.35}',
    opened_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at       DATETIME,
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_project_round (project_id, round_no),
    KEY idx_project_status (project_id, status),
    CONSTRAINT fk_round_project FOREIGN KEY (project_id) REFERENCES design_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设计迭代轮次';

-- 同一项目内最多一个「未关闭轮次」由服务层在开启新轮前校验；
-- 数据库层如需强约束，可用与 12_project_target 相同的生成列技巧，此处刻意不加以免阻塞
-- 「并行跑两轮对照」这一合理科研用法（见 docs/design/detailed/00-设计评审与勘误.md R-07）。
