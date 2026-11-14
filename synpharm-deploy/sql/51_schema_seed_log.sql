-- ===========================================================================
-- 51_schema_seed_log.sql —— 基线种子脚本执行登记
-- 适用 schema：synpharm_core
-- 幂等：CREATE TABLE IF NOT EXISTS
-- 执行账号：root（与 37/48/49 一致；37 需要跨库写 synpharm_platform.design_tool）
--
-- 为什么需要它：
--   1) 本目录的脚本只在 MySQL 数据卷为空时由官方镜像自动执行；已部署环境的
--      旧数据卷**永远不会**再跑 37/48/49，导致库为空、建不了项目（问题 #1/#2）。
--   2) 37/48/49 都写了幂等 SQL，但「幂等」不等于「知道自己跑过没有」；
--      记录 checksum 后，种子文件被修改可以自动重跑，未被修改则快速跳过。
--   3) 出现部分失败时，可据此判断从哪个脚本续跑。
--
-- 用法：已部署环境执行
--   MYSQL_ROOT_PASSWORD=*** bash synpharm-deploy/scripts/apply-seeds.sh
-- ===========================================================================

USE synpharm_core;

CREATE TABLE IF NOT EXISTS `schema_seed_log` (
    `id`            bigint      NOT NULL AUTO_INCREMENT,
    `seed_code`     varchar(64) NOT NULL COMMENT '脚本编号，如 37_design_seed_data',
    `checksum`      char(16)    NOT NULL COMMENT '脚本内容 sha256 前 16 位',
    `rows_affected` int         NOT NULL DEFAULT 0 COMMENT '预留：执行影响行数',
    `applied_at`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `applied_by`    varchar(64) DEFAULT NULL COMMENT '执行者标识',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_seed_code_checksum` (`seed_code`, `checksum`),
    KEY `idx_seed_code` (`seed_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='种子脚本执行登记（运维用，不对外暴露）';
