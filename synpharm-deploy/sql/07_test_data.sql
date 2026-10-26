-- =============================================
-- SynPharm 测试数据
-- 版本：v3.0.0
-- =============================================

-- ⚠ 身份表已在阶段 1 迁到 synpharm_auth，这一段必须切库；
--   且要显式指定 id=1 并做成幂等 —— 下面的 predict_task / predict_result 测试数据
--   全部引用 user_id = 1，若这里生成的是别的 id，测试数据就会挂在不存在的用户上。
--
-- ✅ 2026-10-17 修正（问题总账 H-26 / B-02）：原先这里写「BCrypt 口令对应明文 123456」，
--   实测 `BCryptPasswordEncoder.matches("123456", <原哈希>)` = **false**（注释是错的），
--   而且即便口令真是 123456 也过不了 `LoginRequest.PASSWORD_REGEX`
--   （要求 ≥8 位且含大小写与数字）—— 也就是说这个账号**无论如何都登不进去**。
--
--   现在把 `password` 置 NULL：该行**仅作测试数据的引用锚点**（predict_task / predict_result
--   的 user_id = 1），不可用密码登录。这也顺带消掉了 B-02「弱口令管理员随部署自动创建」。
--
--   需要管理员时请走正常注册通道，口令由你自己设定（会过强度校验）：
--     1) POST /api/auth/register   { email, password, captchaEmail, captcha }  → 得到普通用户
--     2) UPDATE synpharm_auth.sys_user SET role = 'admin' WHERE email = '<你的邮箱>';
USE synpharm_auth;

INSERT INTO sys_user (id, email, password, nickname, role, status, email_verified, register_type, login_count) VALUES
(1, 'admin@synpharm.com', NULL, '管理员', 'admin', 1, 1, 'qq_email', 0)
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), role = VALUES(role), status = VALUES(status);

USE synpharm_predict;

-- 插入测试任务
INSERT INTO predict_task (task_no, user_id, predict_type, input_type, input_value, status, progress, created_at) VALUES
('task_20240101120001', 1, 'dti', 'smiles', 'CC(=O)OC1=CC=CC=C1C(=O)O,P00533', 'completed', 100, '2024-01-01 12:00:00'),
('task_20240101120002', 1, 'ppi', 'uniprot', 'P00533,P07333', 'completed', 100, '2024-01-01 13:00:00'),
('task_20240101120003', 1, 'ddi', 'smiles', 'CC(=O)OC1=CC=CC=C1C(=O)O,c1ccccc1', 'pending', 0, '2024-01-01 14:00:00');

-- 插入测试结果
INSERT INTO predict_result (result_no, task_id, user_id, target_id, target_name, ligand_smiles, binding_affinity, confidence_score, confidence_level, dataset_source, created_at) VALUES
('result_001', 1, 1, 'P00533', 'EGFR', 'CC(=O)OC1=CC=CC=C1C(=O)O', -9.2500, 0.9200, 'high', 'DrugBank', '2024-01-01 12:05:00'),
('result_002', 2, 1, 'P00533', 'EGFR-P07333', NULL, NULL, 0.8500, 'high', 'STRING', '2024-01-01 13:05:00');