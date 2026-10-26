-- =============================================
-- AI 药物分子设计平台 · 智能体会话（第二阶段）
-- 版本：v1.0  依赖：sys_user
-- 来源：docs/development/第二阶段开发文档-多智能体协同与智能体闭环.md §4.1
--
-- 阶段归属：第二阶段（多智能体协同）。第一阶段不写入本表，但 34_design_report
-- 通过 agent_session_id 弱关联（Q7：如需解耦可去掉该 FK，仅保留字段）。
-- 规范化：原文档无外键，此处补 fk_as_user（设计书 §3.1「外键必建」）。
-- =============================================
USE synpharm_predict;

CREATE TABLE IF NOT EXISTS agent_session (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_no      VARCHAR(64)   NOT NULL COMMENT '会话编号 A+yyMMddHHmmss+随机',
    user_id         BIGINT        NOT NULL COMMENT '归属用户',
    goal            VARCHAR(1000)          COMMENT '用户原始目标（自然语言）',
    input_json      TEXT                   COMMENT '结构化输入（SMILES/序列等）',
    run_mode        VARCHAR(20)   NOT NULL DEFAULT 'ONLINE' COMMENT 'ONLINE/OFFLINE（离线走 RuleBasedLlmGateway）',
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                    COMMENT 'PENDING/PLANNING/EXECUTING/CRITIQUING/REPLANNING/REPORTING/COMPLETED/FAILED/CANCELLED',
    plan_json       TEXT                   COMMENT '当前轮次的计划 DAG',
    conclusion_json TEXT                   COMMENT '综合结论（含一致性标记与置信度）',
    confidence      DECIMAL(5,4)           COMMENT '综合置信度',
    round_count     INT           NOT NULL DEFAULT 0,
    max_rounds      INT           NOT NULL DEFAULT 3,
    error_msg       VARCHAR(500),
    started_at      DATETIME,
    completed_at    DATETIME,
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_session_no (session_no),
    KEY idx_user_status (user_id, status),
    KEY idx_created (created_at)
    -- user_id 指向 synpharm_auth.sys_user，跨 schema 外键禁止（方案 §7.2）
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体会话';
