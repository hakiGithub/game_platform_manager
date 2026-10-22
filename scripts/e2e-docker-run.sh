#!/usr/bin/env bash
#
# Game Platform Manager - E2E 容器化执行（WSL + Docker）
#
# 被测前后端全部跑在 Docker 容器里，Playwright 跑在 WSL 宿主，测 http://localhost:${FRONTEND_E2E_PORT}。
# 与 npm run e2e（Windows 受管模式）等价，但完全隔离：不占 Windows 端口、不写 Windows 临时库。
#
# 设计约束（"不要保留太重的依赖"）：
#   - 零源码复制：主仓库只读挂载进容器
#   - 零 Maven 构建：直接用 Windows 侧已构建的 fat jar（backend/core/target/*-exec.jar）
#   - 前端依赖装进 Docker 命名卷（容器内 npm ci 约 10s），不占仓库也不占 Windows 盘
#   - 前端跑 vite dev，不 build
#
# 用法：
#   bash scripts/e2e-docker-run.sh                          # 跑全量
#   bash scripts/e2e-docker-run.sh e2e/main-app/auth        # 跑指定 spec
#   bash scripts/e2e-docker-run.sh --attach                 # 不起栈，测已在跑的栈
#   E2E_KEEP_STACK=1 bash scripts/e2e-docker-run.sh         # 跑完保留栈便于调试
#
# 首次前置：
#   - WSL 内 bash scripts/e2e-docker-browsers.sh     # 装 Playwright Chromium 1228
#   - WSL 内 bash scripts/e2e-docker-frontend.sh     # 装 Linux 版 node_modules 到命名卷
#   - backend/core/target/*-exec.jar 需已构建（Windows 侧 mvn package 过一次）
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/e2e-docker-compose.yml"

FRONTEND_PORT="${FRONTEND_E2E_PORT:-8081}"
BASE_URL="http://localhost:${FRONTEND_PORT}"
STACK_NAME="gpm-e2e"

log()   { echo "[e2e-docker] $*"; }
check() { echo "[CHECK][$1] $2"; }

ATTACH=0
if [ "${1:-}" = "--attach" ]; then
  ATTACH=1
  shift
fi

# ---- 0. 前置校验：缺什么说什么，不要静默失败 ----
bash "${SCRIPT_DIR}/e2e-link-jar.sh" || exit 1

if ! docker volume inspect "${STACK_NAME}_e2e-modules" >/dev/null 2>&1; then
  check "FAIL" "缺少 Linux 版 node_modules 命名卷（${STACK_NAME}_e2e-modules）"
  echo "  remediation: bash scripts/e2e-docker-frontend.sh" >&2
  exit 1
fi

if [ ! -f "${HOME}/.cache/ms-playwright/chromium_headless_shell-1228/chrome-headless-shell-linux64/chrome-headless-shell" ]; then
  check "FAIL" "WSL 缺少 Playwright Chromium 1228"
  echo "  remediation: bash scripts/e2e-docker-browsers.sh" >&2
  exit 1
fi

# ---- 1. 起栈 ----
if [ "${ATTACH}" -eq 0 ]; then
  log "起栈（前后端容器，项目名 ${STACK_NAME}）..."
  if ! REPO_ROOT="${REPO_ROOT}" FRONTEND_E2E_PORT="${FRONTEND_PORT}" \
      docker compose -p "${STACK_NAME}" -f "${COMPOSE_FILE}" up -d; then
    check "FAIL" "docker compose up 失败"
    echo "  remediation: docker compose -p ${STACK_NAME} -f ${COMPOSE_FILE} logs" >&2
    exit 1
  fi
fi

cleanup_stack() {
  if [ "${ATTACH}" -eq 0 ] && [ "${E2E_KEEP_STACK:-0}" != "1" ]; then
    log "清理容器栈（保留 node_modules 命名卷以便复用）..."
    # 不用 down -v：e2e-modules 是耗时装出来的，下次还要用
    docker compose -p "${STACK_NAME}" -f "${COMPOSE_FILE}" down >/dev/null 2>&1
    docker volume rm -f "${STACK_NAME}_e2e-data" "${STACK_NAME}_e2e-logs" "${STACK_NAME}_e2e-vite" >/dev/null 2>&1
    bash "${SCRIPT_DIR}/e2e-link-jar.sh" --unlink >/dev/null 2>&1
  fi
}

wait_ready() {
  local url="$1" what="$2" deadline
  deadline=$((SECONDS + 300))
  while [ "${SECONDS}" -lt "${deadline}" ]; do
    curl -sf -o /dev/null "${url}" && { check "PASS" "${what} 就绪 (${url})"; return 0; }
    sleep 3
  done
  check "FAIL" "${what} ${SECONDS}s 内未就绪 (${url})"
  return 1
}

if ! wait_ready "${BASE_URL}/" "前端"; then
  echo "  remediation: docker compose -p ${STACK_NAME} -f ${COMPOSE_FILE} logs frontend" >&2
  cleanup_stack
  exit 1
fi

# 后端就绪 = 种子管理员可登录（DatabaseInitializer 在 Tomcat 可服务之后才建表）
login_ok=0
deadline=$((SECONDS + 300))
while [ "${SECONDS}" -lt "${deadline}" ]; do
  if curl -sf -X POST "${BASE_URL}/api/auth/login" \
      -H 'Content-Type: application/json' \
      -d '{"username":"admin","password":"admin123"}' >/dev/null 2>&1; then
    login_ok=1
    break
  fi
  sleep 3
done
if [ "${login_ok}" -ne 1 ]; then
  check "FAIL" "后端种子管理员登录失败（DatabaseInitializer 可能未完成建表）"
  echo "  remediation: docker compose -p ${STACK_NAME} -f ${COMPOSE_FILE} logs backend" >&2
  cleanup_stack
  exit 1
fi
check "PASS" "栈就绪: ${BASE_URL}（登录可用）"

# ---- 2. 跑 Playwright（WSL 宿主 → 容器栈） ----
log "执行 Playwright: ${*:-<全量>}"
bash "${SCRIPT_DIR}/e2e-docker-pw.sh" "$@"
status=$?

cleanup_stack

if [ "${status}" -eq 0 ]; then
  check "PASS" "Playwright 全部通过"
else
  check "FAIL" "Playwright 失败（exit=${status}）；产物 e2e/.artifacts，报告 e2e/.report"
fi
# 票 03：结果落盘（summary 由 e2e-docker-pw.sh 写出）
if [ -f "${REPO_ROOT}/frontend/e2e/.last-run/summary.md" ]; then
  check "PASS" "结果摘要: frontend/e2e/.last-run/summary.md（JSON: summary.json）"
else
  check "FAIL" "结果摘要未生成（frontend/e2e/.last-run/summary.md 不存在）"
fi
exit "${status}"


