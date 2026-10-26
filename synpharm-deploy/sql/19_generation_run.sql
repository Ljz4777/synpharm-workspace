-- =============================================
-- AI 药物分子设计平台 · 分子生成任务
-- 版本：v1.0  依赖：11_design_project / 13_design_round / 17_constraint_set
-- 来源：docs/modules/design/04-分子生成器.md §5.1
--
-- 状态字典修正（设计书 §0.3 D6 / §3.4.9）：统一 6 态，SUCCESS 更名为 SUCCEEDED，
-- 保留 PARTIAL 以表达「部分策略成功」——丢失 PARTIAL 会掩盖能力缺口（违反红线 R1）。
-- job_id 由 36_design_domain_revision.sql 追加（接入统一任务表 33_design_job）。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS generation_run (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_no              VARCHAR(64)  NOT NULL COMMENT '生成任务编号 G+yyMMddHHmmss+随机',
    project_id          BIGINT       NOT NULL,
    round_id            BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    constraint_set_id   BIGINT                COMMENT '使用的约束集（仅存 ID；规则内容另存快照）',
    constraint_snapshot JSON                  COMMENT '约束的人可读快照，保证历史可解释（红线 R2）',
    strategies          VARCHAR(500) NOT NULL COMMENT '策略名列表，逗号分隔，如 "R_GROUP,FRAGMENT_RECOMBINE"',
    strategy_params     JSON                  COMMENT '各策略参数与配额',
    seed_json           JSON                  COMMENT '种子来源，如 {"type":"POCKET_LIGAND","pocketId":12}',
    pocket_id           BIGINT                COMMENT '口袋引导时使用的口袋（16_binding_pocket）',
    target_count        INT          NOT NULL DEFAULT 100 COMMENT '目标产出数',
    generated_count     INT          NOT NULL DEFAULT 0 COMMENT '原始生成数',
    deduped_count       INT          NOT NULL DEFAULT 0 COMMENT '去重后数',
    rejected_count      INT          NOT NULL DEFAULT 0 COMMENT '被 hard 规则淘汰数',
    final_count         INT          NOT NULL DEFAULT 0 COMMENT '最终落库候选数',
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                        COMMENT 'PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED',
    progress            DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    error_msg           VARCHAR(1000)         COMMENT '失败/部分失败的逐策略原因',
    latency_ms          INT,
    started_at          DATETIME,
    completed_at        DATETIME,
    deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_run_no (run_no),
    KEY idx_round (round_id),
    KEY idx_project_status (project_id, status),
    CONSTRAINT fk_gen_project FOREIGN KEY (project_id) REFERENCES design_project(id),
    CONSTRAINT fk_gen_round   FOREIGN KEY (round_id)   REFERENCES design_round(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分子生成任务';
