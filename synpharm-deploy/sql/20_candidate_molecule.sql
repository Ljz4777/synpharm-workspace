-- =============================================
-- AI 药物分子设计平台 · 候选分子（自引用版本链）
-- 版本：v1.0  依赖：11_design_project / 13_design_round / 19_generation_run
-- 来源：docs/modules/design/04-分子生成器.md §5.2
--
-- uk_project_inchikey 是「小分子去重」的唯一权威：同一项目内相同 InChIKey 只能一条，
-- 数据库层兜底防并发重复（§8.2 验收项）。
-- mw/logp/sa_score/qed 只做排序缓存，权威来源仍是 22/24 的评估结果。
--
-- 多模态扩展（modality_code / sequence / sequence_hash / structure_* / total_score）
-- 与 smiles、inchikey 放开 NOT NULL 由 36_design_domain_revision.sql 完成
-- —— 抗体/多肽没有 SMILES。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS candidate_molecule (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id        BIGINT        NOT NULL COMMENT '冗余，便于按项目查询',
    round_id          BIGINT                 COMMENT '产生于哪一轮（编辑产物记为此轮）',
    origin_run_id     BIGINT                 COMMENT '若由生成产生，指向 generation_run',
    smiles            VARCHAR(2000) NOT NULL COMMENT '标准化后的 SMILES',
    inchikey          CHAR(27)      NOT NULL COMMENT '标准化 InChIKey，去重键',
    canonical_smiles  VARCHAR(2000)          COMMENT 'RDKit canonical（展示用）',
    molecule_name     VARCHAR(200)           COMMENT '可选名称',
    origin_type       VARCHAR(20)   NOT NULL DEFAULT 'GENERATED'
                      COMMENT 'GENERATED/EDITED/IMPORTED/SEED',
    strategy_name     VARCHAR(40)            COMMENT '生成策略名（origin_type=GENERATED 时）',
    parent_mol_id     BIGINT                 COMMENT '编辑来源的父候选（origin_type=EDITED 时）',
    generation_params JSON                   COMMENT '该分子的生成参数，保证可复现（红线 R5）',
    mw                DECIMAL(9,3)           COMMENT '分子量（冗余，便于排序）',
    logp              DECIMAL(7,3)           COMMENT 'LogP（冗余，便于排序）',
    sa_score          DECIMAL(5,2)           COMMENT '合成可及性（冗余）',
    qed               DECIMAL(5,4)           COMMENT 'QED（冗余）',
    hard_passed       TINYINT       NOT NULL DEFAULT 1 COMMENT '是否通过全部 hard 约束',
    review_status     VARCHAR(20)   NOT NULL DEFAULT 'NEW'
                      COMMENT 'NEW/QUALIFIED/REJECTED/TOP/SELECTED',
    batch_tag         VARCHAR(60)            COMMENT '用户打的标签',
    note              VARCHAR(500)           COMMENT '用户备注',
    deleted           TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_project_inchikey (project_id, inchikey),
    KEY idx_round (round_id),
    KEY idx_run (origin_run_id),
    KEY idx_review (project_id, review_status),
    KEY idx_sort_mw (project_id, mw),
    KEY idx_parent (parent_mol_id),
    CONSTRAINT fk_cand_project FOREIGN KEY (project_id) REFERENCES design_project(id),
    CONSTRAINT fk_cand_parent  FOREIGN KEY (parent_mol_id) REFERENCES candidate_molecule(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='候选分子';

-- 谱系成环防护（红线：MOLECULE_LINEAGE_CYCLE）依赖三条约束共同保证，无法单靠 DDL：
--   ① 父候选一旦创建即不可改（服务层无 update parent_mol_id 的写路径）；
--   ② 编辑只能在"当前项目内"的候选之间发生（服务层校验 project_id 一致）；
--   ③ 服务层在写入前沿 parent 链上行检测是否命中自身。
-- 仅在 36_design_domain_revision.sql 追加 idx_lineage 以加速链上行查询。
