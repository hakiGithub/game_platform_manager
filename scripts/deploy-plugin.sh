#!/usr/bin/env bash
#
# Game Platform Manager - 插件热部署脚本（免重启后端）
#
# 适用场景：只改动了 plugin-l4d2 的代码（前端页面或 Java）。
# 流程：构建插件前端（可选）→ 打插件 JAR → 通过 PF4J 管理 API
#       卸载旧插件（释放 Windows jar 文件锁）→ 覆盖 jar → 加载并启动。
# 后端进程全程不重启；主应用（core/api/plugin 模块）代码变更仍需
# bash scripts/start-all.sh 重启后端。
#
# 用法：
#   bash scripts/deploy-plugin.sh                    # 前端 + Java 全量构建后热部署
#   bash scripts/deploy-plugin.sh --skip-frontend    # 仅改了 Java 代码
#   bash scripts/deploy-plugin.sh --skip-build       # 跳过构建，重新部署上次构建的 JAR
#   bash scripts/deploy-plugin.sh --jar /path/x.jar  # 直接部署指定 JAR
#
# 可用环境变量：
#   PLUGIN_ID      插件 ID（默认 plugin-l4d2）
#   JAR_NAME       插件目录中的 jar 文件名（默认 plugin-l4d2-core-1.0.0.jar）
#   SERVER_PORT    后端端口（默认 8080）
#   GPM_USER/GPM_PWD  登录账号（默认 admin/admin123）
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="${ROOT_DIR}/backend"
FRONTEND_DIR="${BACKEND_DIR}/plugin-l4d2/frontend"
PLUGIN_MODULE="plugin-l4d2/plugin-l4d2-core"

PLUGIN_ID="${PLUGIN_ID:-plugin-l4d2}"
JAR_NAME="${JAR_NAME:-plugin-l4d2-core-1.0.0.jar}"
SERVER_PORT="${SERVER_PORT:-8080}"
GPM_USER="${GPM_USER:-admin}"
GPM_PWD="${GPM_PWD:-admin123}"

SKIP_FRONTEND=0
SKIP_BUILD=0
JAR_OVERRIDE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-frontend) SKIP_FRONTEND=1; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --jar) JAR_OVERRIDE="$2"; shift 2 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

log() { echo "[$(date '+%H:%M:%S')] $*"; }

# ---------- 1. 构建 ----------
if [[ -n "${JAR_OVERRIDE}" ]]; then
  JAR_SRC="${JAR_OVERRIDE}"
  [[ -f "${JAR_SRC}" ]] || { echo "错误: JAR 不存在 ${JAR_SRC}"; exit 1; }
else
  if [[ ${SKIP_BUILD} -eq 0 && ${SKIP_FRONTEND} -eq 0 ]]; then
    log "构建插件前端..."
    cd "${FRONTEND_DIR}"
    [[ -d node_modules ]] || npm install
    npm run build
  fi
  if [[ ${SKIP_BUILD} -eq 0 ]]; then
    log "打包插件 JAR (mvn ${PLUGIN_MODULE})..."
    cd "${BACKEND_DIR}"
    mvn -pl "${PLUGIN_MODULE}" -am install -DskipTests -q
  fi
  JAR_SRC="${BACKEND_DIR}/${PLUGIN_MODULE}/target/${JAR_NAME}"
  [[ -f "${JAR_SRC}" ]] || { echo "错误: 目标 JAR 不存在 ${JAR_SRC}（先去掉 --skip-build）"; exit 1; }
fi

# ---------- 2. 后端未运行时：仅落盘，下次启动自动加载 ----------
cd "${ROOT_DIR}"
mkdir -p "${BACKEND_DIR}/plugins"
HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:${SERVER_PORT}/api/auth/login" || true)"
if [[ "${HTTP_CODE}" == "000" ]]; then
  cp "${JAR_SRC}" "${BACKEND_DIR}/plugins/${JAR_NAME}"
  log "后端未运行：JAR 已放入 plugins/，下次 start-all.sh 启动时自动加载"
  exit 0
fi

# ---------- 3. 登录取 token ----------
log "登录后端..."
TOKEN="$(curl -s -X POST "http://localhost:${SERVER_PORT}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${GPM_USER}\",\"password\":\"${GPM_PWD}\"}" \
  | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['token'] if d.get('code')==200 else '')")"
[[ -n "${TOKEN}" ]] || { echo "错误: 登录失败（检查 GPM_USER/GPM_PWD）"; exit 1; }
AUTH=(-H "Authorization: Bearer ${TOKEN}")

plugin_state() {
  curl -s --max-time 3 "http://localhost:${SERVER_PORT}/api/pf4j/plugins" "${AUTH[@]}" \
    | python -c "import sys,json
try:
    plugins=[p for p in json.load(sys.stdin)['data'] if p['pluginId']=='${PLUGIN_ID}']
    print(plugins[0]['state'] if plugins else 'ABSENT')
except Exception:
    print('ERR')"
}

# 端口通不等于就绪：Spring 初始化期间插件可能尚未被启动加载器装载。
# 等到插件出现在列表中（或超时按 ABSENT 处理），避免覆盖 jar 时撞上启动加载。
STATE="$(plugin_state)"
i=0
while [[ "${STATE}" == "ABSENT" || "${STATE}" == "ERR" ]] && (( i < 60 )); do
  sleep 1; (( i++ )); STATE="$(plugin_state)"
done
[[ "${STATE}" == "ERR" ]] && { echo "错误: 插件列表接口异常"; exit 1; }

# ---------- 4. 卸载（释放 jar 文件锁）→ 覆盖 → 加载启动 ----------
if [[ "${STATE}" != "ABSENT" ]]; then
  log "卸载插件 ${PLUGIN_ID}（当前状态 ${STATE}）..."
  [[ "${STATE}" == "STARTED" ]] && curl -sf -X POST "http://localhost:${SERVER_PORT}/api/pf4j/plugins/${PLUGIN_ID}/stop" "${AUTH[@]}" >/dev/null
  # purgeTasks=false：热部署保留任务历史（取消运行中任务与注销 Handler 仍执行）
  curl -sf -X DELETE "http://localhost:${SERVER_PORT}/api/pf4j/plugins/${PLUGIN_ID}?purgeTasks=false" "${AUTH[@]}" >/dev/null \
    || { echo "错误: 卸载失败，jar 文件锁可能未释放"; exit 1; }
fi

log "覆盖 plugins/${JAR_NAME}..."
cp "${JAR_SRC}" "${BACKEND_DIR}/plugins/${JAR_NAME}" \
  || { echo "错误: 覆盖 JAR 失败（文件被占用？确认插件已卸载）"; exit 1; }

log "加载并启动插件..."
LOAD_RESULT="$(curl -s -X POST "http://localhost:${SERVER_PORT}/api/pf4j/plugins/load?jarName=${JAR_NAME}" "${AUTH[@]}")"
echo "${LOAD_RESULT}" | python -c "import sys,json;d=json.load(sys.stdin);exit(0 if d.get('code')==200 else (print('错误: '+d.get('message','加载失败')),1)[1])" \
  || exit 1

# ---------- 5. 验证 ----------
sleep 2
STATUS="$(plugin_state)"

if [[ "${STATUS}" == "STARTED" ]]; then
  log "热部署完成：${PLUGIN_ID} 已启动（后端未重启）"
else
  echo "警告: 插件状态为 ${STATUS}，请查看后端日志 logs/backend.log"
  exit 1
fi
