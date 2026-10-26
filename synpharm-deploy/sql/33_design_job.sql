-- =============================================
-- AI 药物分子设计平台 · 统一异步任务
-- 版本：v1.0  依赖：11 design_project / 13 design_round / 20 candidate_molecule / 32 design_tool
-- 来源：docs/design/AI药物分子设计平台-详细设计书.md §3.3.4（权威版本）
-- 落实需求：jobs（project_id / candidate_id / job_type / tool_id / status），
--          对外 job_no 即需求中的 job_id。
--
-- uk_idempotent_key 依赖 MySQL「唯一索引允许多个 NULL」：不需要幂等去重的任务留 NULL 即可，
-- 不影响其它行。同键未终态时拒绝重复提交并返回既有 job（§8.2 验收项）。
-- biz_type + biz_id 组合定位业务主表（GENERATION_RUN / EVALUATION_RUN / REPORT），
-- 使「任务」与「业务 run」解耦、又可互相追溯。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS design_job (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '任务ID',
    job_no         VARCHAR(64)  NOT NULL COMMENT '对外任务编号 J+yyMMddHHmmss+随机，即需求中的 job_id',
    job_type       VARCHAR(30)  NOT NULL
                   COMMENT 'GENERATION/EVALUATION/POCKET_DETECT/STRUCTURE_FETCH/REPORT/FEEDBACK_CALIBRATE/EXPORT',
    project_id     BIGINT                COMMENT '所属项目（用户级任务可为空）',
    round_id       BIGINT                COMMENT '所属轮次',
    candidate_id   BIGINT                COMMENT '针对单候选的任务（如单分子全量评估）',
    user_id        BIGINT       NOT NULL COMMENT '发起人',
    tool_id        BIGINT                COMMENT '执行工具（design_tool.id），用于能力与真实性追溯',
    biz_type       VARCHAR(30)           COMMENT '业务主表类型：GENERATION_RUN/EVALUATION_RUN/REPORT',
    biz_id         BIGINT                COMMENT '业务主表 ID（与 biz_type 组合定位）',
    params_json    JSON                  COMMENT '入参快照，保证任务可重放（红线 R5 可追溯）',
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                   COMMENT 'PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED',
    progress       DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '进度百分比 0-100',
    total_count    INT          NOT NULL DEFAULT 0 COMMENT '总条目数',
    done_count     INT          NOT NULL DEFAULT 0 COMMENT '已完成条目数',
    result_json    JSON                  COMMENT '轻量结果摘要（大结果落业务表或对象存储）',
    error_code     VARCHAR(60)           COMMENT '失败错误码（见 02-接口层详细设计.md §2）',
    error_msg      VARCHAR(1000)         COMMENT '失败详情（PARTIAL 时记录逐项原因）',
    idempotent_key VARCHAR(120)          COMMENT '幂等键；同键未终态时拒绝重复提交',
    queue_name     VARCHAR(80)           COMMENT '投递的 RabbitMQ 队列名',
    retry_count    INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    started_at     DATETIME              COMMENT '开始时间',
    completed_at   DATETIME              COMMENT '完成时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_job_no (job_no),
    UNIQUE KEY uk_idempotent_key (idempotent_key),
    KEY idx_user_status (user_id, status, created_at),
    KEY idx_project_type (project_id, job_type, status),
    KEY idx_biz (biz_type, biz_id),
    KEY idx_candidate (candidate_id),
    CONSTRAINT fk_job_project   FOREIGN KEY (project_id)   REFERENCES design_project(id),
    CONSTRAINT fk_job_round     FOREIGN KEY (round_id)     REFERENCES design_round(id),
    -- tool_id 指向 design_tool（原 synpharm_platform 域，2026-10-22 起与设计域同库）
    -- 刻意仍不建外键：工具下线后历史任务要保留，外键会阻止删除工具行。
    CONSTRAINT fk_job_candidate FOREIGN KEY (candidate_id) REFERENCES candidate_molecule(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统一异步任务';
