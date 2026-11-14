#!/usr/bin/env bash
# =============================================================================
# apply-seeds.sh —— 基线种子幂等执行器
#
# 解决的问题：
#   MySQL 官方镜像只在「数据卷为空」时执行 /docker-entrypoint-initdb.d；
#   已部署环境的旧数据卷不会再跑 37/48/49，于是公共库为空、新建项目选不到
#   分子形式与靶点。本脚本可对已有数据卷重复执行，且不会重复灌数据。
#
# 用法：
#   MYSQL_ROOT_PASSWORD=*** bash synpharm-deploy/scripts/apply-seeds.sh
#   MYSQL_ROOT_PASSWORD=*** bash synpharm-deploy/scripts/apply-seeds.sh --force
#   MYSQL_ROOT_PASSWORD=*** bash synpharm-deploy/scripts/apply-seeds.sh --dry-run
#
# 环境变量：
#   SYNPHARM_DB_CONTAINER  容器名，默认 synpharm-mysql（开发环境通常是 synpharm-dev-mysql）
#   MYSQL_ROOT_USER        root 账号，默认 root
#   MYSQL_ROOT_PASSWORD    root 口令，必填
#
# 为什么必须 root：
#   37 第 85 行跨库写 synpharm_platform.design_tool；
#   48 需要建表并 DROP INDEX uk_uniprot。
# =============================================================================
set -euo pipefail

CONTAINER="${SYNPHARM_DB_CONTAINER:-synpharm-mysql}"
ROOT_USER="${MYSQL_ROOT_USER:-root}"
ROOT_PASS="${MYSQL_ROOT_PASSWORD:?请设置 MYSQL_ROOT_PASSWORD}"
FORCE=0
DRY_RUN=0

for arg in "$@"; do
  case "$arg" in
    --force)   FORCE=1 ;;
    --dry-run) DRY_RUN=1 ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_DIR="$(cd "$SCRIPT_DIR/../sql" && pwd)"

# 顺序即依赖，不可调换：37 建字典 → 48 建库壳 → 49 灌条目
SEEDS=(
  "37_design_seed_data.sql"
  "48_library.sql"
  "49_library_mock.sql"
)

# 必须带默认库：49_library_mock.sql 直接引用 library_dataset 等未加库名的表，
# 官方镜像靠 `mysql <db>` 选库；手工执行时若不带 -D 会报 "No database selected"。
mysql_exec() {
  docker exec -i -e MYSQL_PWD="$ROOT_PASS" "$CONTAINER" \
    mysql -u"$ROOT_USER" --default-character-set=utf8mb4 -D synpharm_core "$@"
}

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -c1-16
  else
    shasum -a 256 "$1" | cut -c1-16   # macOS
  fi
}

is_applied() {
  local code="$1" sum="$2" n
  n=$(mysql_exec -N -B -e \
    "SELECT COUNT(*) FROM synpharm_core.schema_seed_log
      WHERE seed_code='$code' AND checksum='$sum'")
  [ "$n" != "0" ]
}

# 执行后回读，避免「脚本 0 退出但数据没进去」
verify_seed() {
  local code="$1" ok
  case "$code" in
    37_design_seed_data)
      ok=$(mysql_exec -N -B -e "
        SELECT (SELECT COUNT(*) FROM synpharm_core.design_library_item
                 WHERE category='MODALITY' AND deleted=0) >= 3
           AND (SELECT COUNT(*) FROM synpharm_core.design_library_item
                 WHERE category='ASSAY' AND deleted=0) >= 8;") ;;
    48_library)
      ok=$(mysql_exec -N -B -e "
        SELECT (SELECT COUNT(*) FROM synpharm_core.library_dataset WHERE deleted=0) >= 3;") ;;
    49_library_mock)
      ok=$(mysql_exec -N -B -e "
        SELECT (SELECT COUNT(*) FROM synpharm_core.target WHERE deleted=0) >= 20
           AND (SELECT COUNT(*) FROM synpharm_core.library_item WHERE deleted=0) >= 50;") ;;
    *) ok=1 ;;
  esac
  [ "$ok" = "1" ]
}

echo "容器: $CONTAINER"

# 登记表本身要最先存在，否则 is_applied 会因表不存在而报错
ensure_log_table() {
  local exists
  exists=$(mysql_exec -N -B -e "
    SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema='synpharm_core' AND table_name='schema_seed_log'")
  if [ "$exists" = "0" ]; then
    echo "▶  BOOTSTRAP  51_schema_seed_log.sql"
    mysql_exec < "$SQL_DIR/51_schema_seed_log.sql"
  fi
}

ensure_log_table

for file in "${SEEDS[@]}"; do
  code="${file%.sql}"
  path="$SQL_DIR/$file"
  [ -f "$path" ] || { echo "❌ 缺少文件: $path" >&2; exit 1; }
  sum="$(checksum "$path")"

  if [ "$FORCE" -eq 0 ] && is_applied "$code" "$sum"; then
    echo "⏭  SKIP  $code  (已执行 $sum)"
    continue
  fi

  if [ "$DRY_RUN" -eq 1 ]; then
    echo "▶  WOULD RUN  $code  ($sum)"
    continue
  fi

  echo "▶  RUN   $code  ($sum)"
  mysql_exec < "$path"

  if ! verify_seed "$code"; then
    echo "❌ 校验失败: $code 执行后数据未达预期，已中止（未写入登记表）" >&2
    exit 1
  fi

  mysql_exec -e "
    INSERT INTO synpharm_core.schema_seed_log (seed_code, checksum, rows_affected, applied_by)
    VALUES ('$code', '$sum', 0, 'apply-seeds.sh')
    ON DUPLICATE KEY UPDATE applied_at = NOW();"
  echo "✅ OK    $code"
done

echo "全部种子处理完成。"
