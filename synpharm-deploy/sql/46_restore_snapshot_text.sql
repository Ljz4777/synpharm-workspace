-- =============================================================================
-- 46_restore_snapshot_text.sql
--
-- 重建 `baseline_snapshot` 里被破坏成 `?` 的中文（第 45 号脚本的反面：这些**不能**
-- 从自身字节还原，只能回到业务表取回原值）。
--
-- 背景：这三个字段的中文一旦经过一次「UTF-8 → latin1」的有损转换就永久变成 `?`，
-- 信息已丢失。好在这三处内容都有权威来源：
--   快照字段                                     业务来源
--   targets_json.items[].targetType      ←     target.target_type
--   hard_rules_json.items[].humanReadable ←    constraint_rule.human_readable
--   risks_json.items[].description        ←    project_risk_item.description
--
-- 快照里保留了 `targetId` / `ruleId` / (`project_id`,`riskCode`)，所以能精确对上。
-- 只改「含 `?`」的字段，其余一字不动 —— 快照的数值、权重、阈值、哈希都不变。
--
-- 执行：需 root，`--default-character-set=utf8mb4`。幂等。
-- =============================================================================

USE synpharm_core;

-- -----------------------------------------------------------------------------
-- 1. 靶点类型 ← target.target_type
-- -----------------------------------------------------------------------------
UPDATE baseline_snapshot s
JOIN target t ON t.id = JSON_EXTRACT(s.targets_json, '$.items[0].targetId')
SET s.targets_json = JSON_SET(s.targets_json, '$.items[0].targetType', t.target_type)
WHERE JSON_EXTRACT(s.targets_json, '$.items[0].targetType') LIKE '%?%';

-- -----------------------------------------------------------------------------
-- 2. 硬约束可读式 ← constraint_rule.human_readable
--    先只处理「业务表本身还是好的」那些；业务表也坏了的留给第 3 步重建。
-- -----------------------------------------------------------------------------
UPDATE baseline_snapshot s
JOIN constraint_rule r ON r.id = JSON_EXTRACT(s.hard_rules_json, '$.items[0].ruleId')
SET s.hard_rules_json = JSON_SET(s.hard_rules_json, '$.items[0].humanReadable', r.human_readable)
WHERE JSON_EXTRACT(s.hard_rules_json, '$.items[0].humanReadable') LIKE '%?%'
  AND r.human_readable NOT LIKE '%?%';

-- 软约束同理（当前没有脏行，留着保持两边一致）
UPDATE baseline_snapshot s
JOIN constraint_rule r ON r.id = JSON_EXTRACT(s.soft_rules_json, '$.items[0].ruleId')
SET s.soft_rules_json = JSON_SET(s.soft_rules_json, '$.items[0].humanReadable', r.human_readable)
WHERE s.soft_rules_json IS NOT NULL
  AND JSON_EXTRACT(s.soft_rules_json, '$.items[0].humanReadable') LIKE '%?%'
  AND r.human_readable NOT LIKE '%?%';

-- -----------------------------------------------------------------------------
-- 3. 业务表自己也坏了：按 params 重建可读式
--    规则形态固定（NUMERIC_RANGE + params.field/min/max），可以确定性还原，
--    不存在「猜」的成分。字段名走白名单，未识别的保留原样不重建。
-- -----------------------------------------------------------------------------
UPDATE constraint_rule
SET human_readable = CONCAT(
      CASE JSON_UNQUOTE(JSON_EXTRACT(params_json, '$.field'))
        WHEN 'MolWt' THEN '分子量'
        WHEN 'MW'    THEN '分子量'
        ELSE JSON_UNQUOTE(JSON_EXTRACT(params_json, '$.field'))
      END,
      ' ',
      JSON_UNQUOTE(JSON_EXTRACT(params_json, '$.min')),
      '~',
      JSON_UNQUOTE(JSON_EXTRACT(params_json, '$.max')),
      CASE JSON_UNQUOTE(JSON_EXTRACT(params_json, '$.field'))
        WHEN 'MolWt' THEN ' Da'
        WHEN 'MW'    THEN ' Da'
        ELSE ''
      END)
WHERE rule_type = 'NUMERIC_RANGE'
  AND human_readable LIKE '%?%'
  AND JSON_VALID(params_json) = 1;

-- 重建后再回填一次快照（此时业务表已干净）
UPDATE baseline_snapshot s
JOIN constraint_rule r ON r.id = JSON_EXTRACT(s.hard_rules_json, '$.items[0].ruleId')
SET s.hard_rules_json = JSON_SET(s.hard_rules_json, '$.items[0].humanReadable', r.human_readable)
WHERE JSON_EXTRACT(s.hard_rules_json, '$.items[0].humanReadable') LIKE '%?%'
  AND r.human_readable NOT LIKE '%?%';

-- -----------------------------------------------------------------------------
-- 4. 风险描述 ← project_risk_item.description（按 project + riskCode 对齐）
-- -----------------------------------------------------------------------------
UPDATE baseline_snapshot s
JOIN project_risk_item r
  ON r.project_id = s.project_id
 AND r.risk_code = JSON_UNQUOTE(JSON_EXTRACT(s.risks_json, '$.items[0].riskCode'))
SET s.risks_json = JSON_SET(s.risks_json, '$.items[0].description', r.description)
WHERE s.risks_json IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(s.risks_json, '$.items[0].description')) LIKE '%?%'
  AND r.deleted = 0
  AND r.description NOT LIKE '%?%';

-- -----------------------------------------------------------------------------
-- 5. 自检：八块里不该再有 `?`
-- -----------------------------------------------------------------------------
SELECT 'targets_json 含 ?' AS item, COUNT(*) AS n FROM baseline_snapshot
WHERE CAST(targets_json AS CHAR) LIKE '%?%'
UNION ALL
SELECT 'structure_json 含 ?', COUNT(*) FROM baseline_snapshot
WHERE structure_json IS NOT NULL AND CAST(structure_json AS CHAR) LIKE '%?%'
UNION ALL
SELECT 'hard_rules_json 含 ?', COUNT(*) FROM baseline_snapshot
WHERE CAST(hard_rules_json AS CHAR) LIKE '%?%'
UNION ALL
SELECT 'soft_rules_json 含 ?', COUNT(*) FROM baseline_snapshot
WHERE soft_rules_json IS NOT NULL AND CAST(soft_rules_json AS CHAR) LIKE '%?%'
UNION ALL
SELECT 'metrics_json 含 ?', COUNT(*) FROM baseline_snapshot
WHERE CAST(metrics_json AS CHAR) LIKE '%?%'
UNION ALL
SELECT 'risks_json 含 ?', COUNT(*) FROM baseline_snapshot
WHERE risks_json IS NOT NULL AND CAST(risks_json AS CHAR) LIKE '%?%'
UNION ALL
SELECT 'constraint_rule 含 ?', COUNT(*) FROM constraint_rule WHERE human_readable LIKE '%?%'
UNION ALL
SELECT 'baseline_snapshot 总数', COUNT(*) FROM baseline_snapshot;
