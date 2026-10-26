-- =============================================
-- AI 药物分子设计平台 · 药物设计项目（聚合根）
-- 版本：v1.0  依赖：sys_user
-- 作者：设计域 DDL 汇总（见 docs/design/detailed/01-数据层详细设计.md）
-- 来源：docs/modules/design/01-项目管理与生命周期.md §5.1
--
-- 幂等策略：CREATE TABLE IF NOT EXISTS，不含 DROP（与 01_~06_ 的破坏性脚本区分）。
-- 审计约定：统一 deleted / created_at / updated_at（设计书 §3.1）。
-- 另注：disease_id / modality_id / current_round_id 三列由
--       36_design_domain_revision.sql 追加（保持与设计书 §3.4 的分层一致）。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS design_project (
    id                       BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '项目ID',
    project_no               VARCHAR(64)  NOT NULL COMMENT '项目编号 P+yyMMddHHmmss+随机',
    user_id                  BIGINT       NOT NULL COMMENT '归属用户',
    name                     VARCHAR(200) NOT NULL COMMENT '项目名称',
    goal_text                TEXT                  COMMENT '设计目标（自然语言）',
    goal_metric_json         JSON                  COMMENT '结构化目标指标，如 {"selectivity_index":{">":3}}',
    active_constraint_set_id BIGINT                COMMENT '当前生效的约束集（见 17_constraint_set）',
    status                   VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                             COMMENT 'DRAFT/READY/ACTIVE/COMPLETED/CANCELLED/ARCHIVED',
    summary                  TEXT                  COMMENT '结项小结',
    started_at               DATETIME              COMMENT '首次进入 ACTIVE 的时间',
    closed_at                DATETIME              COMMENT '结项时间',
    deleted                  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_project_no (project_no),
    KEY idx_user_status (user_id, status),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='药物设计项目';
