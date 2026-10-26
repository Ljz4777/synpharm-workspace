-- =============================================
-- AI 药物分子设计平台 · 智能体报告（第二阶段）
-- 版本：v1.0  依赖：26_agent_session
-- 来源：docs/development/第二阶段开发文档-多智能体协同与智能体闭环.md §4.3
--
-- 与 34_design_report 的关系（设计书 §2.2C）：
--   agent_report  = 智能体会话产出（含思维链溯源 evidence_json → agent_step.id）
--   design_report = 业务报告与审批载体（审批流只挂在这里，避免审批语义耦合进智能体会话）
--   二者通过 design_report.agent_session_id 关联；ReporterAgent 产出的正文写入 design_report.body_md。
-- =============================================
USE synpharm_predict;

CREATE TABLE IF NOT EXISTS agent_report (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id    BIGINT       NOT NULL,
    report_no     VARCHAR(64)  NOT NULL COMMENT '智能体报告编号 AR+yyMMddHHmmss+随机',
    format        VARCHAR(10)  NOT NULL DEFAULT 'MARKDOWN' COMMENT 'MARKDOWN/JSON/PDF',
    summary       TEXT                  COMMENT '一句话结论',
    content       MEDIUMTEXT            COMMENT '报告正文',
    evidence_json TEXT                  COMMENT '结论→step_id 溯源映射',
    confidence    DECIMAL(5,4),
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_report_no (report_no),
    KEY idx_session (session_id),
    CONSTRAINT fk_arpt_session FOREIGN KEY (session_id) REFERENCES agent_session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体报告';
