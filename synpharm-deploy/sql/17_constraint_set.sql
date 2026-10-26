-- =============================================
-- AI 药物分子设计平台 · 设计约束集
-- 版本：v1.0  依赖：sys_user
-- 来源：docs/modules/design/03-设计约束.md §5.1
--
-- 说明：约束集可跨项目复用并版本化。激活后修改须 clone 出新版本（version+1），
--       原版本保持可解释（对齐 19_generation_run.constraint_snapshot 的快照语义）。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS constraint_set (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    set_no      VARCHAR(64)  NOT NULL COMMENT '约束集编号 CS+yyMMddHHmmss+随机',
    user_id     BIGINT       NOT NULL COMMENT '归属用户（可跨项目复用）',
    name        VARCHAR(200) NOT NULL COMMENT '如"EGFR 抑制剂类药性+PAINS"',
    description VARCHAR(500)          COMMENT '说明',
    logic       VARCHAR(10)  NOT NULL DEFAULT 'AND' COMMENT '顶层逻辑，固定 AND',
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/ARCHIVED',
    version     INT          NOT NULL DEFAULT 1 COMMENT '版本号，激活后修改须 +1',
    parent_id   BIGINT                COMMENT '版本来源（clone 自哪个约束集）',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_set_no (set_no),
    KEY idx_user_status (user_id, status),
    CONSTRAINT fk_cs_parent FOREIGN KEY (parent_id) REFERENCES constraint_set(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设计约束集';
