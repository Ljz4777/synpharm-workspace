-- =============================================
-- AI 药物分子设计平台 · 湿实验反馈
-- 版本：v1.0  依赖：11 design_project / 13 design_round / 20 candidate_molecule
--                  / 31 file_asset / 34 design_report
-- 来源：docs/design/AI药物分子设计平台-详细设计书.md §3.3.6（权威版本）
-- 落实需求：experiments（candidate_id / assay_type / result_value / status / feedback_to_model）
--
-- predicted_value + error_delta 是「实测准确率」与「回灌校准」的数据基础：
--   GET /projects/{id}/experiment-accuracy 按 (assay_type, target_role) 分组算 MAE/R²/命中率；
--   POST /experiments/feedback 按批次产出校准工件 calibration_id（可回滚、可审计）。
-- calibration_id 指向对象存储中的版本化工件（协议见 04-计算层契约与校准协议.md §4）。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS wet_experiment (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '实验记录ID',
    experiment_no     VARCHAR(64)  NOT NULL COMMENT '实验编号 W+yyMMddHHmmss+随机',
    project_id        BIGINT       NOT NULL COMMENT '所属项目',
    round_id          BIGINT                COMMENT '所属轮次',
    candidate_id      BIGINT                COMMENT '被验证的候选（对照组/外部样品可为空）',
    report_id         BIGINT                COMMENT '由哪份报告驱动（送样来源）',
    assay_type        VARCHAR(40)  NOT NULL
                      COMMENT 'IC50/EC50/KD_SPR/KD_BLI/ADCC/CDC/THERMAL_SHIFT/EXPRESSION_YIELD/SERUM_STABILITY/IMMUNOGENICITY_TCELL',
    assay_protocol    VARCHAR(500)          COMMENT '实验方案/条件摘要',
    target_role       VARCHAR(40)           COMMENT 'WILD_TYPE/MUTANT/ANTI_TARGET/OFF_TARGET，对齐 project_target.role',
    result_status     VARCHAR(20)  NOT NULL DEFAULT 'PLANNED'
                      COMMENT 'PLANNED/RUNNING/SUCCEEDED/FAILED/INCONCLUSIVE',
    result_num        DECIMAL(14,5)         COMMENT '数值结果（按 unit 解释）',
    result_text       VARCHAR(300)          COMMENT '非数值结果/备注',
    unit              VARCHAR(30)           COMMENT '单位：nM / kcal/mol / °C / mg/L',
    operator          VARCHAR(10)           COMMENT '不等式算子：GT/GTE/LT/LTE/EQ（如 >10000 nM）',
    is_hit            TINYINT               COMMENT '是否命中（按阈值判定），用于回算命中率',
    predicted_value   DECIMAL(14,5)         COMMENT '实验前系统给出的预测值（配对比较用）',
    predicted_metric  VARCHAR(40)           COMMENT '预测值对应指标码（metric_definition.metric_code）',
    error_delta       DECIMAL(14,5)         COMMENT '预测-实测偏差（result_num - predicted_value）',
    feedback_to_model TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已回灌用于模型校准',
    feedback_batch    VARCHAR(64)           COMMENT '回灌批次号 B+yyMMddHHmmss',
    feedback_at       DATETIME              COMMENT '回灌时间',
    calibration_id    VARCHAR(64)           COMMENT '产出的校准工件 ID（版本化，可回滚）',
    data_asset_id     BIGINT                COMMENT '原始数据/仪器导出（file_asset.id）',
    lab_name          VARCHAR(200)          COMMENT '实验单位',
    operator_id       BIGINT       NOT NULL COMMENT '录入人',
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_experiment_no (experiment_no),
    KEY idx_project_assay (project_id, assay_type, result_status),
    KEY idx_candidate (candidate_id),
    KEY idx_feedback (feedback_to_model, feedback_batch),
    KEY idx_report (report_id),
    KEY idx_accuracy (project_id, assay_type, target_role, result_status),
    CONSTRAINT fk_we_project   FOREIGN KEY (project_id)   REFERENCES design_project(id),
    CONSTRAINT fk_we_round     FOREIGN KEY (round_id)     REFERENCES design_round(id),
    CONSTRAINT fk_we_candidate FOREIGN KEY (candidate_id) REFERENCES candidate_molecule(id),
    -- data_asset_id 指向 file_asset（原 synpharm_asset 域，2026-10-22 起与设计域同库）
    -- 刻意仍不建外键：资产 GC 是异步的，硬约束会让实验记录无法保留孤儿引用。
    CONSTRAINT fk_we_report    FOREIGN KEY (report_id)    REFERENCES design_report(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='湿实验反馈';
