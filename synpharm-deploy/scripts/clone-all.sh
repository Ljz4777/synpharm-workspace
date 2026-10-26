#!/usr/bin/env bash
# =============================================================================
# 一键拉取 SynPharm 全部 4 仓库到同一父目录（约定结构）
#
# 用法：
#   ./clone-all.sh [父目录] [git remote base URL]
#   默认父目录：./synpharm-workspace
#   默认 git base：git@github.com:Ljz4777
#
# 执行后目录结构：
#   synpharm-workspace/
#   ├── synpharm-frontend/
#   ├── synpharm-cloud/
#   ├── synpharm-fastapi/
#   └── synpharm-deploy/
#
# 拉取后启动开发环境：
#   cd synpharm-workspace
#   cp synpharm-deploy/.env.example .env
#   docker compose -f synpharm-deploy/docker-compose.dev.yml up -d
# =============================================================================
set -euo pipefail

PARENT_DIR="${1:-./synpharm-workspace}"
GIT_BASE="${2:-git@github.com:Ljz4777}"

REPOS=("synpharm-frontend" "synpharm-cloud" "synpharm-fastapi" "synpharm-deploy")

mkdir -p "$PARENT_DIR"

for repo in "${REPOS[@]}"; do
  echo "=== Cloning $repo ==="
  if [ -d "$PARENT_DIR/$repo/.git" ]; then
    echo "  $repo already exists, pulling latest..."
    git -C "$PARENT_DIR/$repo" pull --ff-only || echo "  WARN: pull failed, check $repo manually"
  else
    git clone "$GIT_BASE/$repo.git" "$PARENT_DIR/$repo"
  fi
done

echo ""
echo "=== Done! 目录结构： ==="
ls -la "$PARENT_DIR"
echo ""
echo "下一步："
echo "  cd $PARENT_DIR"
echo "  cp synpharm-deploy/.env.example .env"
echo "  # 编辑 .env 配置密码/密钥"
echo "  docker compose -f synpharm-deploy/docker-compose.dev.yml up -d"
