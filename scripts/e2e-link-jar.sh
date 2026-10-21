#!/usr/bin/env bash
# 把已构建的 fat jar 软链成固定名 app.jar，供 E2E compose 挂载使用。
#
# 为什么软链：Dockerfile/compose 不想写死版本号（game-platform-core-1.0.0-SNAPSHOT-exec.jar），
# 而软链放在 backend/core/target/ 下（gitignored），不进版本库。
#
# 用法：bash scripts/e2e-link-jar.sh [--unlink]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="${REPO_ROOT}/backend/core/target"
LINK="${TARGET_DIR}/app.jar"

if [ "${1:-}" = "--unlink" ]; then
  if [ -L "${LINK}" ]; then
    rm -f "${LINK}"
    echo "[e2e-docker] 已移除软链 app.jar"
  fi
  exit 0
fi

# 用 find 而非 glob：9p 挂载点下 bash 的 glob 展开不可靠（实测 *-exec.jar 失败）
JAR="$(find "${TARGET_DIR}" -maxdepth 1 -name '*-exec.jar' | head -1)"
if [ -z "${JAR}" ]; then
  echo "[FAIL] 未找到 fat jar（backend/core/target/*-exec.jar）" >&2
  echo "  remediation: cd backend && mvn -B -pl core -am package -DskipTests" >&2
  exit 1
fi

ln -sfn "$(basename "${JAR}")" "${LINK}"
echo "[e2e-docker] app.jar -> $(readlink "${LINK}")"
