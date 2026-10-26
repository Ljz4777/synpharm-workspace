-- =============================================
-- AI 药物分子设计平台 · 靶点库
-- 版本：v1.0  依赖：无
-- 来源：docs/modules/design/02-靶点与结合口袋.md §6.1
--
-- 回应问题总账 C-08（靶点库整体 mock、后端无 /api/targets、无 targets 表）。
-- 字段与前端 src/types/index.ts 的 Target 接口逐一对齐；
-- 额外增加 sequence —— 前端接口没有它，但 DTI 预测必需（见 测试数据准备清单.md）。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS target (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '靶点ID',
    uniprot_id       VARCHAR(20)  NOT NULL COMMENT 'UniProt 登录号，如 P00533',
    name             VARCHAR(200) NOT NULL COMMENT '英文名，如 EGFR',
    chinese_name     VARCHAR(200)          COMMENT '中文名',
    gene_name        VARCHAR(100)          COMMENT '基因名',
    organism         VARCHAR(60)  NOT NULL DEFAULT 'human' COMMENT '物种',
    target_type      VARCHAR(80)           COMMENT '靶点类型（前端筛选项）',
    family           VARCHAR(200)          COMMENT '蛋白家族',
    pathway          VARCHAR(500)          COMMENT '主要通路',
    disease_area     VARCHAR(200)          COMMENT '疾病领域（前端筛选项）',
    related_diseases VARCHAR(500)          COMMENT '相关疾病',
    known_drugs      VARCHAR(500)          COMMENT '已知药物/干预方式',
    sequence         TEXT                  COMMENT '蛋白序列（DTI 预测必需；缺失则 TARGET_SEQUENCE_REQUIRED）',
    sequence_length  INT                   COMMENT '序列长度',
    pdb_ids          VARCHAR(200)          COMMENT '已知 PDB ID，逗号分隔',
    status           VARCHAR(20)  NOT NULL DEFAULT 'supported'
                     COMMENT 'supported/beta/planned（对齐前端 Target.status）',
    source           VARCHAR(40)  NOT NULL DEFAULT 'UNIPROT' COMMENT '数据来源',
    description      TEXT                  COMMENT '功能描述',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_uniprot (uniprot_id),
    KEY idx_status (status),
    KEY idx_target_type (target_type),
    KEY idx_disease_area (disease_area),
    KEY idx_gene_name (gene_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='靶点库';

-- sequence 为 TEXT（最长 65535 字节），无法建索引；序列去重/比对由应用层按
-- sha256(规范化序列) 处理，避免依赖不可索引的大字段（同 20_candidate_molecule.sequence_hash 的做法）。
