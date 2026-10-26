-- =============================================
-- AI 药物分子设计平台 · 评估指标定义（表驱动，便于扩展）
-- 版本：v1.0  依赖：无
-- 来源：docs/modules/design/06-评估器.md §6.1
--
-- 内置指标随表定义一起插入（ON DUPLICATE KEY UPDATE ⇒ 可重复执行，幂等）。
-- 生物大分子补充指标（TM_STABILITY 等）见 37_design_seed_data.sql。
-- dimension / applies_to 两列由 36_design_domain_revision.sql 追加（需求 8 大维度）。
--
-- ⚠ 权重口径：下列 weight 之和为 1.05（>1）。综合得分按 §7 公式对
--   「实际启用指标」的权重做归一化（Σw），因此权重之和不要求为 1；
--   但必须保证同一 run 内 weightsSnapshot 固定，否则分数不可复现（见 01-数据层详细设计.md §4）。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS metric_definition (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    metric_code VARCHAR(40)   NOT NULL COMMENT '指标码，如 MOL_WT / QED / SA_SCORE / DTI_CONFIDENCE',
    metric_name VARCHAR(100)  NOT NULL COMMENT '中文名，如"分子量"',
    category    VARCHAR(30)   NOT NULL
                COMMENT 'CONSTRAINT/PROPERTY/DRUGLIKENESS/SAFETY/SYNTHESIS/DIVERSITY/ACTIVITY/SELECTIVITY/DOCKING/ADMET',
    value_type  VARCHAR(20)   NOT NULL DEFAULT 'NUMERIC' COMMENT 'NUMERIC/BOOLEAN/GRADE/JSON',
    unit        VARCHAR(20)            COMMENT '单位，如 Da / Å / kcal/mol',
    better      VARCHAR(10)   NOT NULL DEFAULT 'HIGHER'
                COMMENT 'HIGHER 越大越好 / LOWER 越小越好 / NONE',
    min_value   DECIMAL(12,4)          COMMENT '归一化下限',
    max_value   DECIMAL(12,4)          COMMENT '归一化上限',
    weight      DECIMAL(5,4)  NOT NULL DEFAULT 0.0000 COMMENT '综合得分权重（0=仅展示不参与打分）',
    enabled     TINYINT       NOT NULL DEFAULT 1,
    sort_no     INT           NOT NULL DEFAULT 0,
    remark      VARCHAR(300),
    deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_metric_code (metric_code),
    KEY idx_category (category, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评估指标定义';

INSERT INTO metric_definition
  (metric_code, metric_name, category, value_type, unit, better, min_value, max_value, weight, sort_no, remark)
VALUES
  ('MOL_WT',            '分子量',         'PROPERTY',     'NUMERIC', 'Da',       'NONE',   0, 1000, 0.0000,  10, '区间型，由约束判定'),
  ('MOL_LOGP',          '脂水分配系数',   'PROPERTY',     'NUMERIC', '',         'NONE',  -3,    8, 0.0000,  20, '区间型'),
  ('TPSA',              '极性表面积',     'PROPERTY',     'NUMERIC', 'Å²',       'NONE',   0,  250, 0.0000,  30, '区间型'),
  ('NUM_H_DONORS',      '氢键供体数',     'PROPERTY',     'NUMERIC', '',         'LOWER',  0,   10, 0.0000,  40, NULL),
  ('NUM_H_ACCEPTORS',   '氢键受体数',     'PROPERTY',     'NUMERIC', '',         'LOWER',  0,   15, 0.0000,  50, NULL),
  ('NUM_ROTATABLE',     '可旋转键数',     'PROPERTY',     'NUMERIC', '',         'LOWER',  0,   15, 0.0000,  60, NULL),
  ('FRACTION_CSP3',     'sp³ 碳占比',     'PROPERTY',     'NUMERIC', '',         'HIGHER', 0,    1, 0.0000,  70, NULL),
  ('QED',               '类药性 QED',     'DRUGLIKENESS', 'NUMERIC', '',         'HIGHER', 0,    1, 0.1500,  80, '综合得分主项之一'),
  ('LIPINSKI_PASS',     'Lipinski 通过数','DRUGLIKENESS', 'NUMERIC', '条',       'HIGHER', 0,    4, 0.0500,  90, NULL),
  ('SA_SCORE',          '合成可及性',     'SYNTHESIS',    'NUMERIC', '',         'LOWER',  1,   10, 0.1000, 100, '1 易合成，10 极难'),
  ('PAINS_HITS',        'PAINS 命中数',   'SAFETY',       'NUMERIC', '个',       'LOWER',  0,    5, 0.1000, 110, '命中即降分'),
  ('BRENK_HITS',        'BRENK 命中数',   'SAFETY',       'NUMERIC', '个',       'LOWER',  0,    5, 0.0500, 120, NULL),
  ('MAX_SIMILARITY',    '最大相似度',     'DIVERSITY',    'NUMERIC', '',         'LOWER',  0,    1, 0.0500, 130, '与同批其他候选'),
  ('DTI_CONFIDENCE',    'DTI 结合置信度', 'ACTIVITY',     'NUMERIC', '',         'HIGHER', 0,    1, 0.3000, 140, '复用 KAN-MoDTI（C-04：靶点不得写死）'),
  ('DDI_PROBABILITY',   'DDI 相互作用概率','ACTIVITY',    'NUMERIC', '',         'LOWER',  0,    1, 0.0500, 150, '复用 DDI-LLM（受 1323 条白名单边界限制）'),
  ('SELECTIVITY_INDEX', '选择性指数',     'SELECTIVITY',  'NUMERIC', '',         'HIGHER', 0,   10, 0.1000, 160, '突变体/野生型，依赖 project_target.role'),
  ('VINA_AFFINITY',     'Vina 对接打分',  'DOCKING',      'NUMERIC', 'kcal/mol','LOWER', -14,    0, 0.1000, 170, '引擎未安装时权重自动重分配（capability_key=vina）')
ON DUPLICATE KEY UPDATE
  metric_name = VALUES(metric_name),
  category    = VALUES(category),
  unit        = VALUES(unit),
  better      = VALUES(better),
  min_value   = VALUES(min_value),
  max_value   = VALUES(max_value),
  sort_no     = VALUES(sort_no),
  remark      = VALUES(remark);
