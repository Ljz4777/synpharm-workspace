-- =============================================
-- AI 药物分子设计平台 · 项目-靶点绑定
-- 版本：v1.0  依赖：11_design_project / 14_target
-- 来源：docs/modules/design/01-项目管理与生命周期.md §5.2
--
-- role 是选择性设计的基础：同一项目内把野生型与突变体分别绑定并标注角色，
-- 06 评估器才能算出「选择性指数 = 突变体亲和力 / 野生型亲和力」。
--
-- 唯一键语义：uk_project_target 不含 deleted（沿用平台表既有约定，见 sys_user.uk_email）。
--   含义：绑定关系一旦逻辑删除，同一 (project, target) 不可再重建；
--   与 §2.1「跨聚合不做级联删除，保留科研数据」一致。若未来需允许重建，须改为归档语义。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS project_target (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT      NOT NULL COMMENT '项目ID',
    target_id  BIGINT      NOT NULL COMMENT '靶点ID（见 14_target）',
    is_primary TINYINT     NOT NULL DEFAULT 0 COMMENT '1=主靶点（生成以它为准）',
    role       VARCHAR(40)          COMMENT 'WILD_TYPE/MUTANT/ANTI_TARGET/OFF_TARGET',
    sort_no    INT         NOT NULL DEFAULT 0,
    deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- 一个项目只允许一个主靶点（生成以它为准）。
    -- MySQL 不支持带 WHERE 的部分唯一索引，故用「生成列 + 唯一键」实现：
    --   is_primary=1 时该列为 project_id；is_primary=0 时为 NULL（唯一索引允许多个 NULL）。
    -- 生成列直接声明在建表内，保证本脚本可重复执行（幂等，不用 ALTER）。
    primary_project_id BIGINT GENERATED ALWAYS AS (IF(is_primary = 1, project_id, NULL)) STORED
                       COMMENT '仅主靶点非空，用于 uk_primary_target 保证一项目一主靶点',

    UNIQUE KEY uk_project_target (project_id, target_id),
    UNIQUE KEY uk_primary_target (primary_project_id),
    KEY idx_project (project_id),
    KEY idx_target (target_id),
    -- 注意：target_id 的外键被刻意推迟到 36_design_domain_revision.sql。
    --   docker-entrypoint-initdb.d 按**文件名字母序**执行，12_ 早于 14_target.sql，
    --   在此直接建 FK 会报 ERROR 1824 (Failed to open the referenced table 'target')。
    --   这是设计书 §3.2「编号顺序即落库顺序」与 FK 依赖的一处冲突，
    --   详见 docs/design/detailed/00-设计评审与勘误.md R-05。
    CONSTRAINT fk_pt_project FOREIGN KEY (project_id) REFERENCES design_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目-靶点绑定';
