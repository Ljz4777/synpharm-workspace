-- =============================================
-- SynPharm 数据库初始化脚本
-- 版本：v4.0.0（微服务：单实例 + 每服务独立 schema）
-- =============================================
--
-- 拆分策略（方案 §7，决策 B）：
--   单 MySQL 实例 + 每服务独立 schema + 独立账号 + 禁止跨服务外键 / JOIN。
--   账号**不**在这里创建（不能把口令写进 SQL 源码），由同目录的 00_create_accounts.sh
--   从环境变量读取口令后创建。
--
-- ⚠️ 本目录的脚本只在 MySQL 数据卷为空时自动执行。已部署的环境需手工执行
--    sql/migrations/ 下的迁移脚本（见各文件头部说明）。
-- =============================================

-- ---------- synpharm_auth ----------
-- 归属：synpharm-auth（身份与访问）
-- 表：sys_user（含科研档案 4 列）、sys_login_log
CREATE DATABASE IF NOT EXISTS synpharm_auth
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- ---------- synpharm_predict ----------
-- 归属：synpharm-predict（AI 预测）——阶段 4 迁入（原在 synpharm 过渡库）
CREATE DATABASE IF NOT EXISTS synpharm_predict
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- ---------- synpharm_core ----------
-- 归属：synpharm-core（主业务）——2026-10-22 服务收敛
-- 表：原 design(19) + asset(1) + platform(1) = 21 张
-- 为什么合并：主业务四段（需求分析/生成/编辑/决策）全在设计域，asset 与 platform
--   本就是它的支撑件。三者同库后可以建跨域外键（原先因跨 schema 禁止而无法建立）。
CREATE DATABASE IF NOT EXISTS synpharm_core
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- ---------- synpharm（已废弃的过渡库）----------
-- 原名含义就是「全平台共用一个库」。微服务化过程中它已被彻底掏空：
--   阶段 1：sys_user / sys_login_log 迁出到 synpharm_auth            ✅
--   阶段 2：设计域 19 张表迁出到 synpharm_design（过渡期曾在两库各留一份镜像）
--   阶段 3：file_asset 迁出到 synpharm_asset                          ✅
--   阶段 4：预测域 6 张表迁出到 synpharm_predict                       ✅
--   阶段 5：design_tool 迁出到 synpharm_platform                       ✅
--
-- **2026-10-16 起本库 0 张表**（过渡期镜像由 39_legacy_monolith_design_mirror.sh 制造，
-- 该脚本与 19 张旧表已一并删除，见 migrations/2026-10-14-phase2-split-design-schema.sql §5）。
--
-- 为什么还保留这个空 schema：compose 里 `MYSQL_DATABASE` 指向它，MySQL 官方镜像初始化时
-- 会据此建库并授权给 `MYSQL_USER`；去掉它要牵动该变量及其下游引用，而一个空库的代价接近 0。
-- **不要再往里建表** —— 业务表一律归各自的 schema。
CREATE DATABASE IF NOT EXISTS synpharm
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;