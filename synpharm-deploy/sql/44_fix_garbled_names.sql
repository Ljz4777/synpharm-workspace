-- ===========================================================================
-- 44_fix_garbled_names.sql —— 修复历史乱码数据（2026-10-25）
-- ===========================================================================
-- 背景：早期用 PowerShell + Invoke-RestMethod 造数据时，请求体中文被按系统
-- 代码页编码，存进库变成了字面的 '?'（不可逆）。本脚本把已知的坏名字改成正确中文。
--
-- 另：design_library_item 的 MODALITY / ASSAY / OBJECTIVE 曾出现双重编码
-- （'å°åˆ†å­' 这类），已由重跑 37_design_seed_data.sql 修复，不在本脚本范围。
--
-- 幂等：只按主键 UPDATE，重复执行结果一致。
-- 执行账号：任意有 synpharm_core 写权限的账号即可。
-- ===========================================================================

USE synpharm_core;

UPDATE design_project SET name = 'EGFR 小分子亲和力优化（演示）' WHERE id = 152;
UPDATE design_project SET name = '演示项目 A（可删除）'            WHERE id = 151;
UPDATE design_project SET name = '演示项目 B（可删除）'            WHERE id = 150;

-- 验收：不应再有连续问号
-- SELECT id, name, HEX(name) LIKE '%3F3F%' AS still_bad
--   FROM design_project WHERE id IN (150,151,152);
