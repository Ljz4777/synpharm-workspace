-- =============================================
-- AI 药物分子设计平台 · 分子编辑历史
-- 版本：v1.0  依赖：11_design_project / 13_design_round / 20_candidate_molecule
-- 来源：docs/modules/design/05-分子编辑器.md
--
-- 追加型日志：只增不改，故刻意不设 deleted / updated_at
-- （设计书 §3.1 的逻辑删除约定适用于可列表查询的实体，见 00-设计评审与勘误.md R-02）。
-- operator_type 预留 AGENT：第二阶段智能体改分子时与人工操作共用同一张表，
-- 是「人改的 / 智能体改的」可解释性的基础。
-- strategy_code / target_region / modality_code 三列由 36_design_domain_revision.sql 追加。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS molecule_edit_log (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id    BIGINT        NOT NULL,
    round_id      BIGINT                 COMMENT '编辑发生在哪一轮',
    parent_mol_id BIGINT                 COMMENT '父候选；手工导入时为 NULL',
    child_mol_id  BIGINT        NOT NULL COMMENT '编辑产物候选',
    edit_type     VARCHAR(30)   NOT NULL
                  COMMENT 'ADD_SUBSTITUENT/REMOVE_SUBSTITUENT/REPLACE_GROUP/RING_OPEN_CLOSE/CHAIN_EXTEND/HETERO_SWAP/STEREO_TUNE/RAW_SMILES/FRAGMENT_JOIN',
    params_json   JSON          NOT NULL COMMENT '操作参数，如 {"atomIdx":12,"group":"[OH]"}',
    parent_smiles VARCHAR(2000) NOT NULL COMMENT '编辑前 SMILES（快照）',
    child_smiles  VARCHAR(2000) NOT NULL COMMENT '编辑后 SMILES（快照）',
    changed_atoms JSON                   COMMENT '发生变化的原子索引与前后值，供前端高亮',
    describe_text VARCHAR(500)           COMMENT '人类可读描述，如"在 12 位 N 上添加羟基"',
    valid         TINYINT       NOT NULL DEFAULT 1 COMMENT '化学合法性校验是否通过',
    error_msg     VARCHAR(500),
    operator_id   BIGINT        NOT NULL COMMENT '谁改的（人 或 二阶段的智能体）',
    operator_type VARCHAR(20)   NOT NULL DEFAULT 'USER' COMMENT 'USER/AGENT（二阶段用）',
    latency_ms    INT,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_project_round (project_id, round_id, created_at),
    KEY idx_parent (parent_mol_id),
    KEY idx_child (child_mol_id),
    CONSTRAINT fk_mel_parent FOREIGN KEY (parent_mol_id) REFERENCES candidate_molecule(id),
    CONSTRAINT fk_mel_child  FOREIGN KEY (child_mol_id)  REFERENCES candidate_molecule(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分子编辑历史';
