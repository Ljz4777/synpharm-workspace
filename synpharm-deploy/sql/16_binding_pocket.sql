-- =============================================
-- AI 药物分子设计平台 · 结合口袋
-- 版本：v1.0  依赖：15_target_structure
-- 来源：docs/modules/design/02-靶点与结合口袋.md §6.3
--
-- 追加唯一键 uk_structure_selected：同一结构最多一个「已选定设计口袋」。
--   设计书 §2.1 要求「口袋选定为单例，切换在事务内先清零」——数据库层用生成列兜底，
--   防止并发切换时出现两个 is_selected=1（服务层事务仍是主路径）。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS binding_pocket (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    structure_id  BIGINT       NOT NULL,
    pocket_no     INT          NOT NULL COMMENT '结构内的口袋序号（按 score 降序）',
    method        VARCHAR(20)  NOT NULL COMMENT 'P2RANK/FPOCKET/LIGAND_BASED/MANUAL',
    score         DECIMAL(8,4)          COMMENT '方法原始打分',
    druggability  DECIMAL(5,4)          COMMENT '可成药性 0-1',
    volume        DECIMAL(10,2)         COMMENT '空腔体积 Å³',
    center_x      DECIMAL(9,3) NOT NULL COMMENT '中心 X',
    center_y      DECIMAL(9,3) NOT NULL COMMENT '中心 Y',
    center_z      DECIMAL(9,3) NOT NULL COMMENT '中心 Z',
    residues_json JSON                  COMMENT '[{"chain":"A","resno":745,"resname":"LYS"}]',
    is_selected   TINYINT      NOT NULL DEFAULT 0 COMMENT '1=当前使用的设计口袋',
    note          VARCHAR(500)          COMMENT '人工备注（如"已知 ATP 位点"）',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    selected_structure_id BIGINT GENERATED ALWAYS AS (IF(is_selected = 1, structure_id, NULL)) STORED
                          COMMENT '仅选定口袋非空，用于 uk_structure_selected',

    UNIQUE KEY uk_struct_pocket (structure_id, method, pocket_no),
    UNIQUE KEY uk_structure_selected (selected_structure_id),
    KEY idx_struct_selected (structure_id, is_selected),
    CONSTRAINT fk_pocket_struct FOREIGN KEY (structure_id) REFERENCES target_structure(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='结合口袋';
