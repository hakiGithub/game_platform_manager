#!/usr/bin/env bash
#
# 在 WSL 内为前端容器准备一份 Linux 版 node_modules。
#
# 为什么必须单独准备：Windows 侧 frontend/node_modules 只装了当前平台的可选依赖
# （实测：@rollup/rollup-win32-*、@esbuild/win32-x64），Linux 容器里跑 vite 会报
# "Cannot find module @rollup/rollup-linux-x64-gnu"。这是 npm 的平台相关可选依赖行为，
# 无法靠只读挂载绕过。
#
# 做法：package*.json 复制到 WSL ext4（几 KB，秒级），容器内 npm ci 装到命名卷，
# 之后 vite dev 复用该卷。源码仍只读挂载主仓库，不复制整份 frontend。
#
# 用法：bash scripts/e2e-docker-frontend.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
STAGE_DIR="${E2E_STAGE_DIR:-${HOME}/.cache/gpm-e2e}"

mkdir -p "${STAGE_DIR}"
cp "${REPO_ROOT}/frontend/package.json" "${STAGE_DIR}/"
cp "${REPO_ROOT}/frontend/package-lock.json" "${STAGE_DIR}/"

echo "[e2e-docker] 暂存目录: ${STAGE_DIR}（仅 package*.json，共 $(du -sh "${STAGE_DIR}" | cut -f1)）"
echo "[e2e-docker] 容器内安装 Linux 版 node_modules（一次性，后续复用命名卷）..."

docker run --rm \
  -v "${STAGE_DIR}":/stage \
  -v gpm-e2e_e2e-modules:/app/node_modules \
  -w /app \
  node:20-slim \
  sh -c 'cp /stage/package*.json /app/ && npm ci --no-audit --no-fund'

echo "[e2e-docker] 完成。命名卷 gpm-e2e_e2e-modules 内含 Linux 版依赖。"
