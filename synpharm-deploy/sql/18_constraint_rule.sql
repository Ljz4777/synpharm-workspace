-- =============================================
-- AI 药物分子设计平台 · 约束规则
-- 版本：v1.0  依赖：17_constraint_set
-- 来源：docs/modules/design/03-设计约束.md §5.2
--
-- 唯一对 constraint_set 采用 ON DELETE CASCADE 的表（设计书 §2.1 唯一例外）：
-- 规则离开约束集无独立意义。
-- dimension / operator / threshold_num / threshold_max / unit 五列由
-- 36_design_domain_revision.sql 追加（需求 project_constraints）。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS constraint_rule (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    set_id         BIGINT       NOT NULL,
    group_key      VARCHAR(20)  NOT NULL DEFAULT 'G1'
                   COMMENT '逻辑分组；同一 group_key 内按 group_logic 组合',
    group_logic    VARCHAR(10)  NOT NULL DEFAULT 'AND' COMMENT '该组的组内逻辑 AND/OR',
    -- 注意：MySQL 8 在 COMMENT 位置不接受「相邻字符串字面量拼接」，
    -- 长枚举必须写成一个完整字面量（设计书 §3.3 的折行写法在 MySQL 上无法执行）。
    rule_type      VARCHAR(30)  NOT NULL
                   COMMENT 'NUMERIC_RANGE/SMARTS_REQUIRED/SMARTS_FORBIDDEN/SCAFFOLD_REQUIRED/SCAFFOLD_FORBIDDEN/FILTER_CATALOG/SIMILARITY_RANGE/SIMILARITY_TO_SET/RING_COUNT/SUBSTITUENT_ALLOWED/RULE_SET',
    severity       VARCHAR(10)  NOT NULL DEFAULT 'HARD' COMMENT 'HARD 淘汰 / SOFT 加权扣分',
    weight         INT          NOT NULL DEFAULT 10 COMMENT 'SOFT 规则的扣分权重',
    params_json    JSON         NOT NULL COMMENT '规则参数，结构随 rule_type 而定（见文件尾注释）',
    human_readable VARCHAR(500)          COMMENT '人类可读描述，直接展示给用户（红线 R2：不做隐藏规则）',
    sort_no        INT          NOT NULL DEFAULT 0,
    enabled        TINYINT      NOT NULL DEFAULT 1,
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    KEY idx_set_group (set_id, group_key, sort_no),
    CONSTRAINT fk_cr_set FOREIGN KEY (set_id) REFERENCES constraint_set(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='约束规则';

-- params_json 结构约定（按 rule_type）：
--   NUMERIC_RANGE      {"field":"MolWt","min":200,"max":550}
--   SMARTS_REQUIRED    {"smarts":"S(=O)(=O)N","name":"磺酰胺"}
--   SMARTS_FORBIDDEN   {"smarts":"[NX3][NX3]","name":"肼类"}
--   SCAFFOLD_*         {"scaffoldSmiles":"...","allowSubstituent":true}
--   FILTER_CATALOG     {"catalog":"PAINS","mode":"must_pass"}
--   SIMILARITY_RANGE   {"refSmiles":"CC(=O)Oc1ccccc1C(=O)O","metric":"TANIMOTO","min":0.4,"max":0.8}
--   SIMILARITY_TO_SET  {"setIds":[1,2],"metric":"TANIMOTO","max":0.6}
--   RING_COUNT         {"min":1,"max":5,"ringType":"AROMATIC"}
--   SUBSTITUENT_ALLOWED{"allowedSmarts":["[F,Cl,Br,I]","[OH]","[NH2]","[C;H3]"]}
--   RULE_SET           {"ruleSetCode":"LIPINSKI","mode":"must_pass"}
