-- =============================================
-- AI 药物分子设计平台 · 多级设计知识库
-- 版本：v1.0  依赖：无（自引用树）
-- 来源：docs/design/AI药物分子设计平台-详细设计书.md §3.3.1（权威版本）
-- 落实需求：library_items（疾病 / 靶点 / 分子形式 / 约束模板 / 目标 / 检测类型）
--
-- path 为物化路径（/DISEASE/ONCOLOGY/NSCLC），子树查询走前缀匹配；
-- uk_category_code 保证「同分类内 code 唯一」；builtin=1 的记录仅允许改 enabled/sortNo/remark。
-- meta_json 结构随 category 而定，约定见 01-数据层详细设计.md §5。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS design_library_item (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '知识项ID',
    category   VARCHAR(30)  NOT NULL
               COMMENT 'DISEASE/TARGET/MODALITY/CONSTRAINT_TEMPLATE/OBJECTIVE/ASSAY',
    code       VARCHAR(64)  NOT NULL COMMENT '分类内唯一编码，如 NSCLC / EGFR / ANTIBODY',
    name       VARCHAR(200) NOT NULL COMMENT '显示名（中文）',
    name_en    VARCHAR(200)          COMMENT '英文名',
    parent_id  BIGINT                COMMENT '上级项（自引用，支持 疾病→亚型→靶点 多级）',
    level_no   INT          NOT NULL DEFAULT 1 COMMENT '层级，从 1 开始',
    path       VARCHAR(500)          COMMENT '物化路径 /DISEASE/ONCOLOGY/NSCLC，前缀匹配取子树',
    meta_json  JSON                  COMMENT '分类扩展属性，结构随 category 而定',
    sort_no    INT          NOT NULL DEFAULT 0 COMMENT '同级排序',
    enabled    TINYINT      NOT NULL DEFAULT 1 COMMENT '1=可选；0=停用（历史数据仍可读）',
    builtin    TINYINT      NOT NULL DEFAULT 0 COMMENT '1=内置数据，版本升级时可覆盖',
    remark     VARCHAR(500)          COMMENT '备注',
    deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_category_code (category, code),
    KEY idx_parent (parent_id, sort_no),
    KEY idx_category_enabled (category, enabled, sort_no),
    KEY idx_path (path(191)),
    CONSTRAINT fk_lib_parent FOREIGN KEY (parent_id) REFERENCES design_library_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设计知识库（多级）';
