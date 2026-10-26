-- =============================================
-- AI 药物分子设计平台 · 工具调用审计日志（第二阶段）
-- 版本：v1.0  依赖：26_agent_session / 27_agent_step
-- 来源：docs/development/第二阶段开发文档-多智能体协同与智能体闭环.md §4.4
--
-- 敏感数据约束：digest 字段只存「摘要 + 长度」，不落完整蛋白序列与 SMILES 原文，
-- 避免日志膨胀与数据外泄（对齐设计书 §7.1 安全设计）。
-- 追加型审计日志：只增不改，刻意不设 deleted / updated_at。
-- =============================================
USE synpharm_predict;

CREATE TABLE IF NOT EXISTS tool_invocation_log (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id    BIGINT       NOT NULL,
    step_id       BIGINT,
    tool_name     VARCHAR(60)  NOT NULL,
    input_digest  VARCHAR(500) COMMENT '入参摘要（脱敏，不落完整序列/SMILES 大文本）',
    output_digest VARCHAR(500) COMMENT '出参摘要',
    status        VARCHAR(20)  NOT NULL COMMENT 'SUCCESS/FAILED/TIMEOUT',
    latency_ms    INT,
    error_msg     VARCHAR(500),
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_session_tool (session_id, tool_name),
    KEY idx_step (step_id),
    CONSTRAINT fk_til_session FOREIGN KEY (session_id) REFERENCES agent_session(id),
    CONSTRAINT fk_til_step    FOREIGN KEY (step_id)    REFERENCES agent_step(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具调用审计日志';
