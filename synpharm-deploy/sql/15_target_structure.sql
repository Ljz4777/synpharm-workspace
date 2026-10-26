-- =============================================
-- AI 药物分子设计平台 · 靶点结构
-- 版本：v1.0  依赖：14_target
-- 来源：docs/modules/design/02-靶点与结合口袋.md §6.2
--
-- 修正（见 docs/design/detailed/00-设计评审与勘误.md R-04）：
--   原 uk_target_pdb_chain (target_id, pdb_id, chain_id) 对 AlphaFold 记录失效
--   —— pdb_id/chain_id 为 NULL 时，唯一索引允许重复插入，同一靶点可产生多份 AF 结构。
--   改为 (target_id, source, source_key)：source_key 为「来源内的稳定标识」，
--   PDB 时为 pdb_id:chain，AlphaFold 时为 uniprot_id:version，用户上传时为文件 sha256 前 16 位。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS target_structure (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    target_id         BIGINT       NOT NULL,
    pdb_id            VARCHAR(10)           COMMENT '4 位 PDB ID；AlphaFold / 用户上传时为空',
    chain_id          VARCHAR(10)           COMMENT '所选链',
    source            VARCHAR(20)  NOT NULL DEFAULT 'PDB'
                      COMMENT 'PDB/ALPHAFOLD/USER_UPLOAD',
    source_key        VARCHAR(80)  NOT NULL COMMENT '来源内稳定标识：PDB=1M17:A / AF=P00533:v4 / UPLOAD=sha256前16位',
    experiment_method VARCHAR(40)           COMMENT 'X_RAY/CRYO_EM/NMR/PREDICTED',
    resolution        DECIMAL(5,2)          COMMENT '分辨率 Å；预测模型为 NULL',
    file_path         VARCHAR(500)          COMMENT '落盘的 mmCIF/PDB 路径（对象存储上线后由 file_asset_id 取代）',
    ligand_count      INT          NOT NULL DEFAULT 0 COMMENT '共晶配体数',
    pocket_count      INT          NOT NULL DEFAULT 0 COMMENT '已检测口袋数',
    is_preferred      TINYINT      NOT NULL DEFAULT 0 COMMENT '1=项目默认使用的结构',
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_target_source (target_id, source, source_key),
    KEY idx_target_pref (target_id, is_preferred),
    CONSTRAINT fk_struct_target FOREIGN KEY (target_id) REFERENCES target(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='靶点结构';
