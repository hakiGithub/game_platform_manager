#!/usr/bin/env bash
#
# 在 WSL 宿主跑 Playwright，测容器栈（docker compose -p gpm-e2e）。
#
# 关键：必须用 nvm 的 node。WSL 系统 node 是 v12.22.9，Playwright 1.61 要求 ≥18，
# 且实测 nvm.sh 在非交互 bash -lc 下 source 后 PATH 仍可能指向系统 node ——
# 所以这里显式用绝对路径，不依赖 nvm use。
#
# 用法：bash scripts/e2e-docker-pw.sh [playwright 参数...]
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="${REPO_ROOT}/frontend"
NODE="$(ls -d "${HOME}"/.nvm/versions/node/v*/bin/node 2>/dev/null | sort -V | tail -1)"

if [ -z "${NODE}" ]; then
  echo "[FAIL] 未找到 nvm node（${HOME}/.nvm/versions/node/*/bin/node）" >&2
  echo "  remediation: nvm install 24" >&2
  exit 1
fi
echo "[e2e-docker] node: $(${NODE} -v) (${NODE})"

export E2E_BASE_URL="${E2E_BASE_URL:-http://localhost:${FRONTEND_E2E_PORT:-8081}}"
export E2E_BACKEND_URL="${E2E_BACKEND_URL:-${E2E_BASE_URL}}"

cd "${FRONTEND_DIR}" || exit 1

# 票 03：结果落盘的数据源 —— 由 playwright.config.js 的 json reporter 写出
LAST_RUN_DIR="${FRONTEND_DIR}/e2e/.last-run"
JSON_REPORT="${LAST_RUN_DIR}/playwright-report.json"
# 先清上一轮：本轮若中途崩溃，不能让旧 summary 冒充最新结果
rm -rf "${LAST_RUN_DIR}"
mkdir -p "${LAST_RUN_DIR}"
export E2E_JSON_REPORT="${JSON_REPORT}"

STARTED_AT="$(date +%s%3N)"
"${NODE}" node_modules/@playwright/test/cli.js test "$@"
STATUS=$?

# 写 summary.{json,md}；失败只 warning，不改变被测结果
"${NODE}" e2e/support/summarize-cli.mjs \
  --mode docker --executor wsl \
  --started-at "${STARTED_AT}" --exit-code "${STATUS}" \
  --json "${JSON_REPORT}" || true

exit "${STATUS}"
