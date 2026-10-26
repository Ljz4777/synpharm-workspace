-- =============================================================================
-- 45_fix_metric_encoding.sql
--
-- 修「指标字典里的中文被双重编码」的历史脏数据。
--
-- 现象：`metric_definition.metric_name` 显示为 `ç±»è¯æ§ QED` 而不是 `类药性 QED`。
-- 成因：写入时把 UTF-8 字节当成 latin1 字符串又存了一遍（存进去的是「åˆ†å­」这类
--       latin1 补充区字符），再按 UTF-8 读出来就成了乱码。
-- 影响：基线八块的「优化指标」块直接读字典与 `baseline_snapshot.metrics_json`，
--       所以冻结快照里也带着乱码 —— 用户看到的是一串看不懂的字符。
--
-- 修法：latin1 → binary → utf8mb4 三步还原（这是双重编码的精确逆运算）。
--
-- 安全边界（这两条是唯一正确的判定。别改成 `LIKE '%å%'`：
-- utf8mb4 默认排序规则是 ai_ci，`'å' LIKE 'a'` 为真，会命中所有含英文字母的字符串，
-- 进而把**正确的中文**也做一次 latin1 转换，结果变成不可逆的 `?`）：
--   ① 还原结果与原值**字节不同**；
--   ② 还原结果里**没有 `?`** —— 正确的中文做这个转换会退化成 `?????`。
-- 两条同时满足，才说明原值确实是双重编码，否则一律不动。
--
-- 幂等：跑第二遍条件①已不成立，等于空转。
-- 执行：需 root，`--default-character-set=utf8mb4`。
--
-- 注意：本脚本**只碰字典与 metrics_json**。快照里 targets / hard_rules / risks 的中文
-- 一旦被破坏成 `?` 就无法从自身字节还原，只能溯源重建，
-- 见 46_restore_snapshot_text.sql。
-- =============================================================================

USE synpharm_core;

-- -----------------------------------------------------------------------------
-- 1. metric_definition：指标名 / 单位 / 备注
-- -----------------------------------------------------------------------------
UPDATE metric_definition
SET metric_name = CONVERT(CAST(CONVERT(metric_name USING latin1) AS BINARY) USING utf8mb4)
WHERE CAST(CONVERT(CAST(CONVERT(metric_name USING latin1) AS BINARY) USING utf8mb4) AS BINARY)
        <> CAST(metric_name AS BINARY)
  AND CONVERT(CAST(CONVERT(metric_name USING latin1) AS BINARY) USING utf8mb4) NOT LIKE '%?%';

UPDATE metric_definition
SET unit = CONVERT(CAST(CONVERT(unit USING latin1) AS BINARY) USING utf8mb4)
WHERE unit IS NOT NULL
  AND CAST(CONVERT(CAST(CONVERT(unit USING latin1) AS BINARY) USING utf8mb4) AS BINARY)
        <> CAST(unit AS BINARY)
  AND CONVERT(CAST(CONVERT(unit USING latin1) AS BINARY) USING utf8mb4) NOT LIKE '%?%';

UPDATE metric_definition
SET remark = CONVERT(CAST(CONVERT(remark USING latin1) AS BINARY) USING utf8mb4)
WHERE remark IS NOT NULL
  AND CAST(CONVERT(CAST(CONVERT(remark USING latin1) AS BINARY) USING utf8mb4) AS BINARY)
        <> CAST(remark AS BINARY)
  AND CONVERT(CAST(CONVERT(remark USING latin1) AS BINARY) USING utf8mb4) NOT LIKE '%?%';

-- -----------------------------------------------------------------------------
-- 2. baseline_snapshot.metrics_json：指标名与单位是**双重编码**（不是被破坏），
--    可以原地还原。用二进制定位乱码前导字节，避免排序规则误命中。
-- -----------------------------------------------------------------------------
UPDATE baseline_snapshot
SET metrics_json = CAST(
        CONVERT(CAST(CONVERT(CAST(metrics_json AS CHAR) USING latin1) AS BINARY) USING utf8mb4)
        AS JSON)
WHERE JSON_VALID(
        CONVERT(CAST(CONVERT(CAST(metrics_json AS CHAR) USING latin1) AS BINARY) USING utf8mb4)) = 1
  AND CONVERT(CAST(CONVERT(CAST(metrics_json AS CHAR) USING latin1) AS BINARY) USING utf8mb4)
        NOT LIKE '%?%'
  AND (
        LOCATE(UNHEX('C3A5'), CAST(metrics_json AS BINARY)) > 0   -- å
     OR LOCATE(UNHEX('C3A7'), CAST(metrics_json AS BINARY)) > 0   -- ç
     OR LOCATE(UNHEX('C3A8'), CAST(metrics_json AS BINARY)) > 0   -- è
     OR LOCATE(UNHEX('C3A9'), CAST(metrics_json AS BINARY)) > 0   -- é
     OR LOCATE(UNHEX('C3A6'), CAST(metrics_json AS BINARY)) > 0   -- æ
      );

-- -----------------------------------------------------------------------------
-- 3. 自检：「双重编码」用二进制判定，「含 ?」用来确认没把好数据写坏
-- -----------------------------------------------------------------------------
SELECT 'metric_definition 双重编码' AS item, COUNT(*) AS n
FROM metric_definition
WHERE CAST(CONVERT(CAST(CONVERT(metric_name USING latin1) AS BINARY) USING utf8mb4) AS BINARY)
        <> CAST(metric_name AS BINARY)
  AND CONVERT(CAST(CONVERT(metric_name USING latin1) AS BINARY) USING utf8mb4) NOT LIKE '%?%'
UNION ALL
SELECT 'metric_definition 含 ?', COUNT(*) FROM metric_definition WHERE metric_name LIKE '%?%'
UNION ALL
SELECT 'metrics_json 双重编码', COUNT(*)
FROM baseline_snapshot
WHERE LOCATE(UNHEX('C3A5'), CAST(metrics_json AS BINARY)) > 0
   OR LOCATE(UNHEX('C3A7'), CAST(metrics_json AS BINARY)) > 0
   OR LOCATE(UNHEX('C3A8'), CAST(metrics_json AS BINARY)) > 0
UNION ALL
SELECT 'metrics_json 含 ?', COUNT(*)
FROM baseline_snapshot WHERE CAST(metrics_json AS CHAR) LIKE '%?%'
UNION ALL
SELECT 'metric_definition 总数', COUNT(*) FROM metric_definition
UNION ALL
SELECT 'baseline_snapshot 总数', COUNT(*) FROM baseline_snapshot;
