#!/usr/bin/env bash
#
# CI 冒烟 E2E：不依赖 WSL / 牺牲主机 / Docker 的一份最小启动方式。
#
# 与本地受管模式（frontend/e2e/runner.mjs）的区别：
#   - 受管模式靠 scripts/start-all.sh 起栈，而它在 Linux 上是调 powershell.exe 的
#     （为 WSL 设计），托管 runner 上不可用
#   - 这里直接 java -jar fat jar + node vite dev，然后让 runner 以 --attach 模式跑
#
# 用法（仓库根目录）：
#   bash scripts/e2e-ci.sh            # 默认 --group smoke
#   bash scripts/e2e-ci.sh --group smoke
#
# 前置：
#   - backend/core/target/*-exec.jar 已构建
#   - frontend/node_modules 已 npm ci
#   - 已 npx playwright install --with-deps chromium
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

log()   { echo "[e2e-ci] $*"; }
check() { echo "[CHECK][$1] $2"; }

BACKEND_PORT="${E2E_CI_BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_E2E_PORT:-5180}"
WORK_DIR="${E2E_CI_WORK_DIR:-${REPO_ROOT}/.e2e-ci}"
# 默认用 PATH 上的 java；沙箱/容器化验证时可换成
#   E2E_CI_JAVA="docker run --rm --network host -v /mnt/d:/mnt/d eclipse-temurin:17-jre java"
JAVA_CMD="${E2E_CI_JAVA:-java}"

# Node 解析：CI 镜像里的 node 就是 20；WSL 系统 node 是 12（Playwright 1.61 要 ≥18），
# 所以版本不够时回退到 nvm 里最新的 one，与 e2e-docker-pw.sh 的做法一致。
NODE_CMD="${E2E_CI_NODE:-}"
if [ -z "${NODE_CMD}" ]; then
  NODE_CMD="$(command -v node || true)"
  if [ -z "${NODE_CMD}" ] || ! "${NODE_CMD}" -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 18 ? 0 : 1)' 2>/dev/null; then
    NODE_CMD="$(ls -d "${HOME}"/.nvm/versions/node/v*/bin/node 2>/dev/null | sort -V | tail -1)"
  fi
fi
if [ -z "${NODE_CMD}" ]; then
  check "FAIL" "未找到 Node ≥ 18"
  echo "  remediation: nvm install 24" >&2
  exit 1
fi

log()   { echo "[e2e-ci] $*"; }
check() { echo "[CHECK][$1] $2"; }

JAR_ABS="$(find "${REPO_ROOT}/backend/core/target" -maxdepth 1 -name '*-exec.jar' | head -1)"
if [ -z "${JAR_ABS}" ]; then
  check "FAIL" "未找到 fat jar（backend/core/target/*-exec.jar）"
  echo "  remediation: cd backend && mvn -B -pl core -am package -DskipTests" >&2
  exit 1
fi

if [ ! -d "${REPO_ROOT}/frontend/node_modules/vite" ]; then
  check "FAIL" "frontend/node_modules 缺失"
  echo "  remediation: cd frontend && npm ci" >&2
  exit 1
fi

# npm 只装当前平台的可选依赖：Windows 侧 node_modules 缺 @rollup/rollup-linux-x64-gnu，
# 在 Linux 上直接起 vite 会炸（e2e-docker-frontend.sh 里有同样的记录）。
# CI 里是干净 npm ci，不会遇到；本地 WSL 想验证请走容器化入口。
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) ;;  # Windows 侧：Java/Vite 都能直接用
  *)
    if [ -d "${REPO_ROOT}/frontend/node_modules/@rollup/rollup-win32-x64-msvc" ]; then
      check "FAIL" "frontend/node_modules 是 Windows 版（缺 Linux 版 rollup 原生包）"
      echo "  remediation: 本地 WSL 请用 bash scripts/e2e-docker-run.sh --attach --group smoke" >&2
      exit 1
    fi
    ;;
esac

mkdir -p "${WORK_DIR}"
# 用仓库相对路径启动后端：E2E_CI_JAVA 走 docker 时挂载点是别的绝对路径，
# 传绝对路径会找不到 jar（实测：容器内 /repo 下没有 /mnt/d/...）
JAR="$(cd "${REPO_ROOT}" && realpath --relative-to=. "${JAR_ABS}")"
WORK_REL="$(cd "${REPO_ROOT}" && realpath --relative-to=. "${WORK_DIR}")"
BACKEND_LOG="${WORK_DIR}/backend.log"
FRONTEND_LOG="${WORK_DIR}/frontend.log"
BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
  [ -n "${FRONTEND_PID}" ] && kill "${FRONTEND_PID}" 2>/dev/null
  [ -n "${BACKEND_PID}" ] && kill "${BACKEND_PID}" 2>/dev/null
  wait 2>/dev/null
}
trap cleanup EXIT

wait_http() {
  local url="$1" label="$2" deadline=$((SECONDS + 300))
  while [ "${SECONDS}" -lt "${deadline}" ]; do
    if curl -sf -o /dev/null "${url}"; then
      check "PASS" "${label} 就绪 (${url})"
      return 0
    fi
    sleep 3
  done
  check "FAIL" "${label} 300s 内未就绪 (${url})"
  return 1
}

log "启动后端（临时 SQLite，端口 ${BACKEND_PORT}）..."
# jar / SQLite 路径是仓库相对路径，必须在仓库根目录启动
cd "${REPO_ROOT}" || exit 1
# shellcheck disable=SC2086  # JAVA_CMD 允许带参数（docker run ... java）
${JAVA_CMD} -Xms256m -Xmx768m \
  -Dspring.datasource.url="jdbc:sqlite:${WORK_REL}/e2e-ci.sqlite" \
  -jar "${JAR}" --server.port="${BACKEND_PORT}" \
  >"${BACKEND_LOG}" 2>&1 &
BACKEND_PID=$!

# 后端就绪 = 种子管理员能登录（建表在端口可服务之后）
deadline=$((SECONDS + 300))
login_ok=0
while [ "${SECONDS}" -lt "${deadline}" ]; do
  if curl -sf -X POST "http://localhost:${BACKEND_PORT}/api/auth/login" \
      -H 'Content-Type: application/json' \
      -d '{"username":"admin","password":"admin123"}' >/dev/null 2>&1; then
    login_ok=1
    break
  fi
  sleep 3
done
if [ "${login_ok}" -ne 1 ]; then
  check "FAIL" "后端种子管理员登录失败"
  tail -40 "${BACKEND_LOG}" >&2
  exit 1
fi
check "PASS" "后端就绪（登录可用，端口 ${BACKEND_PORT}）"

log "启动前端（Vite dev，端口 ${FRONTEND_PORT}）..."
( cd "${REPO_ROOT}/frontend" && \
  VITE_PROXY_TARGET="http://127.0.0.1:${BACKEND_PORT}" \
  "${NODE_CMD}" node_modules/vite/bin/vite.js --host 0.0.0.0 --port "${FRONTEND_PORT}" --strictPort \
  >"${FRONTEND_LOG}" 2>&1 &
  echo $! > "${WORK_DIR}/frontend.pid" )
FRONTEND_PID="$(cat "${WORK_DIR}/frontend.pid")"

if ! wait_http "http://localhost:${FRONTEND_PORT}/" "前端"; then
  tail -40 "${FRONTEND_LOG}" >&2
  exit 1
fi

# 复用 runner 的 --attach：分组解析、summary 落盘、失败产物都在同一处
cd "${REPO_ROOT}/frontend" || exit 1
export E2E_BASE_URL="http://localhost:${FRONTEND_PORT}"
export E2E_BACKEND_URL="http://localhost:${BACKEND_PORT}"
log "执行 Playwright（${*:-}）..."
"${NODE_CMD}" e2e/runner.mjs --attach "$@"
STATUS=$?

if [ -f "${REPO_ROOT}/frontend/e2e/.last-run/summary.md" ]; then
  check "PASS" "结果摘要: frontend/e2e/.last-run/summary.md"
else
  check "FAIL" "结果摘要未生成"
fi
exit "${STATUS}"
