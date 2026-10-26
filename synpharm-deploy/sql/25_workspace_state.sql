-- =============================================
-- AI 药物分子设计平台 · 工作台视图状态（IDE 式恢复）
-- 版本：v1.0  依赖：sys_user / 11_design_project / 13_design_round / 20_candidate_molecule
-- 来源：docs/modules/design/07-工作台与交互设计.md §10.1
--
-- 一个用户对一个项目一行（project_id 为 NULL 表示「用户级最近项目」，
-- 因此 uk_user_project 依赖 MySQL「唯一索引允许多个 NULL」——
-- 同一用户可以有多行 project_id IS NULL 的历史记录，取 last_visited_at 最大者）。
-- 前端同时用 localStorage 兜底（离线/未登录），以服务端为准。
-- last_mol_id 指向 20_candidate_molecule；此处不建 FK —— 候选可能因历史清理而移除，
-- 且该字段只是「上次停在哪」的弱引用，断链不应阻塞状态写入。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS workspace_state (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL COMMENT '归属用户（工作台是"我的"视图）',
    project_id        BIGINT                COMMENT 'NULL = 用户级最近项目',
    last_round_id     BIGINT                COMMENT '上次打开的轮次',
    last_mol_id       BIGINT                COMMENT '上次选中的候选',
    active_tab        VARCHAR(30)           COMMENT 'CANVAS/CANDIDATES/DIFF/POCKET',
    open_mol_ids      JSON                  COMMENT '已打开的分子页签列表（IDE 多标签）',
    panel_layout_json JSON                  COMMENT '面板展开/折叠/宽度、表格排序与筛选',
    last_visited_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_user_project (user_id, project_id),
    KEY idx_user_visited (user_id, last_visited_at),
    CONSTRAINT fk_ws_project FOREIGN KEY (project_id) REFERENCES design_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作台视图状态';
