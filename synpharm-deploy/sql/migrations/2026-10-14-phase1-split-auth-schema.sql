-- =============================================================================
-- 迁移：阶段 1 · 把 sys_user / sys_login_log 从过渡库 synpharm 迁到 synpharm_auth
--
-- 适用对象：**已部署环境**（MySQL 数据卷非空，docker-entrypoint-initdb.d 不会重跑）。
-- 全新安装无需执行本文件 —— sql/00~09 已按新归属组织。
--
-- 幂等性：可重复执行（CREATE ... IF NOT EXISTS + DROP ... IF EXISTS）。
--         但 INSERT ... SELECT 会重复插入，故重复执行前请先自行判断是否需要。
--
-- ⚠️ 本目录是 migrations/ 子目录，不会被 MySQL 的 initdb 逻辑执行
--    （该逻辑只处理 /docker-entrypoint-initdb.d 下的直属文件）。
--
-- 执行方式：
--   docker exec -i synpharm-dev-mysql mysql -uroot -p<root_pwd> < 本文件
--
-- 回滚：从阶段 1 之前的备份恢复（例如 _synpharm-db-backup-prephase1-*.sql）。
--       迁移后旧表被删除，且 auth 服务会持续写入新库，故**不能**靠反向 SQL 回滚。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. 目标 schema
-- ---------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS synpharm_auth
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 2. 断开指向 sys_user 的跨 schema 外键
--
--    必须先做：外键存在时 DROP TABLE synpharm.sys_user 会被拒绝。
--    方案 §7.2：跨服务引用一律改为普通列 + 索引，只保留应用层校验。
--    外键名取自 information_schema（InnoDB 依建表顺序自动生成，实测值如下）。
-- ---------------------------------------------------------------------------
ALTER TABLE synpharm.predict_task   DROP FOREIGN KEY predict_task_ibfk_1;
ALTER TABLE synpharm.predict_result DROP FOREIGN KEY predict_result_ibfk_2;
ALTER TABLE synpharm.user_favorite  DROP FOREIGN KEY user_favorite_ibfk_1;
ALTER TABLE synpharm.batch_task     DROP FOREIGN KEY batch_task_ibfk_1;
ALTER TABLE synpharm.agent_session  DROP FOREIGN KEY fk_as_user;

-- ---------------------------------------------------------------------------
-- 3. 迁结构与数据
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS synpharm_auth.sys_user LIKE synpharm.sys_user;
INSERT INTO synpharm_auth.sys_user SELECT * FROM synpharm.sys_user;

CREATE TABLE IF NOT EXISTS synpharm_auth.sys_login_log LIKE synpharm.sys_login_log;
INSERT INTO synpharm_auth.sys_login_log SELECT * FROM synpharm.sys_login_log;

-- ---------------------------------------------------------------------------
-- 4. 核对（必须两边一致；不一致请立刻停止，不要执行第 5 步）
-- ---------------------------------------------------------------------------
SELECT 'sys_user'       AS tbl, (SELECT COUNT(*) FROM synpharm.sys_user)       AS old_cnt,
       (SELECT COUNT(*) FROM synpharm_auth.sys_user)                            AS new_cnt
UNION ALL
SELECT 'sys_login_log'  AS tbl, (SELECT COUNT(*) FROM synpharm.sys_login_log)  AS old_cnt,
       (SELECT COUNT(*) FROM synpharm_auth.sys_login_log)                       AS new_cnt;

-- ---------------------------------------------------------------------------
-- 5. 删除旧表，落实「一张表只有一个服务能写」（拆分铁律 R2）
--
--    保留旧表会让单体与新 auth 服务存在隐式双写风险，故直接删除。
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS synpharm.sys_user;
DROP TABLE IF EXISTS synpharm.sys_login_log;

-- ---------------------------------------------------------------------------
-- 6. 数据库账号不在这里创建
--
--    口令不能进源码。请用 sql/00_create_accounts.sh 的等效 SQL 手工创建：
--      CREATE USER 'synpharm_auth'@'%' IDENTIFIED BY '<强随机口令>';
--      GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER, REFERENCES
--        ON synpharm_auth.* TO 'synpharm_auth'@'%';
--    口令需与 .env 的 AUTH_DB_PASSWORD 一致。
-- ---------------------------------------------------------------------------
