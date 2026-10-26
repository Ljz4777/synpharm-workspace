#!/bin/bash
# =============================================================================
# SynPharm 每服务独立数据库账号
#
# 为什么用 .sh 而不是 .sql：账号口令绝不能写进源码。本脚本从环境变量读取口令，
# 由 docker-compose 的 env 注入；口令未提供时**跳过**该账号（不创建弱口令账号）。
#
# 执行时机：MySQL 官方镜像的 docker-entrypoint-initdb.d，**仅在数据卷为空时**执行。
# 已部署环境请手工执行等效 SQL（见 docs/deploy）。
#
# 变量约定（新增服务时在此追加一行调用即可）：
#   AUTH_DB_USER / AUTH_DB_PASSWORD       -> synpharm_auth
#   PREDICT_DB_USER / PREDICT_DB_PASSWORD -> synpharm_predict   （阶段 4）
#   CORE_DB_USER / CORE_DB_PASSWORD       -> synpharm_core      （阶段 2 起；2026-10-22 收敛）
#     · 原 design(阶段2) + asset(阶段3) + platform(阶段5) 三个 schema 已合并为 synpharm_core
#     · 三者同库后可以建跨域外键（原先因跨 schema 禁止而无法建立）
# =============================================================================
set -euo pipefail

create_account() {
  local user="$1"
  local pass="$2"
  local db="$3"

  if [ -z "${user}" ] || [ -z "${pass}" ]; then
    echo "[00_create_accounts] 跳过 ${db}：未提供 账号/口令（${user:-<未设置>}）"
    return 0
  fi

  mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
	CREATE DATABASE IF NOT EXISTS \`${db}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
	CREATE USER IF NOT EXISTS '${user}'@'%' IDENTIFIED BY '${pass}';
	ALTER USER '${user}'@'%' IDENTIFIED BY '${pass}';
	-- 最小授权：只给本服务的 schema，且不给 GRANT OPTION
	GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER, REFERENCES
	  ON \`${db}\`.* TO '${user}'@'%';
	FLUSH PRIVILEGES;
EOSQL

  echo "[00_create_accounts] ${user} -> ${db} 已就绪（仅本 schema 权限）"
}

# ---------- 阶段 1：身份与访问 ----------
create_account "${AUTH_DB_USER:-synpharm_auth}" "${AUTH_DB_PASSWORD:-}" "synpharm_auth"

# ---------- 阶段 2：药物设计 ----------
create_account "${CORE_DB_USER:-synpharm_core}"     "${CORE_DB_PASSWORD:-}"     "synpharm_core"

# ---------- 阶段 3：资产 ----------

# ---------- 阶段 4：预测 ----------
create_account "${PREDICT_DB_USER:-synpharm_predict}"   "${PREDICT_DB_PASSWORD:-}"  "synpharm_predict"

# ---------- 阶段 5：平台 ----------

echo "[00_create_accounts] 完成"
