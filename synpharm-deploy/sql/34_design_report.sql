-- =============================================
-- AI 药物分子设计平台 · 设计报告与审批
-- 版本：v1.0  依赖：11 design_project / 13 design_round / 23 evaluation_run
--                  / 26 agent_session / 33 design_job
-- 来源：docs/design/AI药物分子设计平台-详细设计书.md §3.3.5（权威版本）
-- 落实需求：reports（project_id / body(jsonb) / decision）
--
-- 审批流只挂在 design_report（不与智能体会话耦合）；revision_of + version_no 实现
-- 「REVISE 后重出报告」的版本链（同步插入修订版占位，见 §4.4 ⑨）。
-- capability_note 是红线 R1/R2 的落点：报告必须声明「生成时哪些引擎不可用、realness 分布」；
-- 正文另须包含实测准确率（含样本量），由 GET /projects/{id}/experiment-accuracy 提供。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS design_report (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '报告ID',
    report_no         VARCHAR(64)  NOT NULL COMMENT '报告编号 R+yyMMddHHmmss+随机',
    project_id        BIGINT       NOT NULL COMMENT '所属项目',
    round_id          BIGINT                COMMENT '所属轮次',
    evaluation_run_id BIGINT                COMMENT '数据来源评估任务（保证可复现）',
    user_id           BIGINT       NOT NULL COMMENT '创建人',
    title             VARCHAR(300) NOT NULL COMMENT '报告标题',
    report_type       VARCHAR(20)  NOT NULL DEFAULT 'TOP_N' COMMENT 'TOP_N/ROUND_SUMMARY/ADHOC',
    top_n             INT          NOT NULL DEFAULT 10 COMMENT 'Top-N 的 N',
    body_json         JSON                  COMMENT '结构化正文：候选+指标+血缘+风险+实测准确率',
    body_md           MEDIUMTEXT            COMMENT '渲染后的 Markdown 正文',
    capability_note   VARCHAR(1000)         COMMENT '生成时的能力边界声明（哪些引擎不可用、realness 分布）',
    decision          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                      COMMENT 'PENDING/APPROVED/REVISE/REJECTED',
    decision_by       BIGINT                COMMENT '审批人（sys_user.id）',
    decision_at       DATETIME              COMMENT '审批时间',
    decision_note     VARCHAR(1000)         COMMENT '审批意见（REVISE/REJECTED 时必填）',
    revision_of       BIGINT                COMMENT '修订来源报告（REVISE 后重出）',
    version_no        INT          NOT NULL DEFAULT 1 COMMENT '版本号，修订 +1',
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                      COMMENT 'PENDING/GENERATING/SUCCEEDED/PARTIAL/FAILED',
    job_id            BIGINT                COMMENT '生成该报告的统一任务',
    agent_session_id  BIGINT                COMMENT '若由 ReporterAgent 生成，关联智能体会话',
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_report_no (report_no),
    KEY idx_project_round (project_id, round_id, created_at),
    KEY idx_decision (project_id, decision),
    KEY idx_eval_run (evaluation_run_id),
    CONSTRAINT fk_rpt_project  FOREIGN KEY (project_id)        REFERENCES design_project(id),
    CONSTRAINT fk_rpt_round    FOREIGN KEY (round_id)          REFERENCES design_round(id),
    CONSTRAINT fk_rpt_eval     FOREIGN KEY (evaluation_run_id) REFERENCES evaluation_run(id),
    CONSTRAINT fk_rpt_job      FOREIGN KEY (job_id)            REFERENCES design_job(id),
    -- agent_session_id 指向 synpharm_agent（二期），跨 schema 外键禁止（方案 §7.2）
    CONSTRAINT fk_rpt_revision FOREIGN KEY (revision_of)       REFERENCES design_report(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设计报告与审批';
