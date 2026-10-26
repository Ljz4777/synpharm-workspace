-- =============================================
-- AI 药物分子设计平台 · 智能体执行步骤（时间线，第二阶段）
-- 版本：v1.0  依赖：26_agent_session
-- 来源：docs/development/第二阶段开发文档-多智能体协同与智能体闭环.md §4.2
--
-- 追加型时间线日志：只增不改，刻意不设 deleted / updated_at。
-- uk_session_round_seq 保证「同会话同轮内序号唯一」，使时间线可确定性重放（可溯源）。
-- =============================================
USE synpharm_predict;

CREATE TABLE IF NOT EXISTS agent_step (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id  BIGINT        NOT NULL,
    round_no    INT           NOT NULL DEFAULT 1 COMMENT '第几轮迭代',
    seq_no      INT           NOT NULL COMMENT '轮内序号（时间线排序用）',
    agent_name  VARCHAR(40)   NOT NULL COMMENT 'Planner/Critic/Reporter/Executor/Evidence',
    step_type   VARCHAR(20)   NOT NULL COMMENT 'THOUGHT/ACTION/OBSERVATION/VERDICT',
    thought     VARCHAR(1000)          COMMENT 'LLM 推理摘要',
    action      VARCHAR(60)            COMMENT '工具名',
    action_input TEXT                  COMMENT '工具入参 JSON',
    observation TEXT                   COMMENT '工具输出摘要',
    status      VARCHAR(20)   NOT NULL COMMENT 'SUCCESS/FAILED/TIMEOUT/SKIPPED',
    latency_ms  INT,
    token_usage INT           NOT NULL DEFAULT 0,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_session_round_seq (session_id, round_no, seq_no),
    KEY idx_session_round_seq (session_id, round_no, seq_no),
    CONSTRAINT fk_astep_session FOREIGN KEY (session_id) REFERENCES agent_session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体执行步骤（时间线）';
