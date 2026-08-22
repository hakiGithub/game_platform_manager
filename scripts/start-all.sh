#!/usr/bin/env bash
#
# Game Platform Manager - 一键启动前后端 (bash 版)
#
# 与 scripts/restart-all.ps1 等价：编译后端 + 打包插件 + 启动后端 + 启动前端。
# 后端以 java -cp 方式后台启动（nohup），前端以 node 直接运行 Vite（避免 npm 中间层）。
#
# 用法:
#   ./scripts/start-all.sh                  # 全量：编译 + 插件打包 + 启动前后端
#   ./scripts/start-all.sh --backend-only   # 仅后端
#   ./scripts/start-all.sh --frontend-only  # 仅前端
#   ./scripts/start-all.sh --skip-compile   # 跳过后端编译（保留插件打包与前端启动）
#   ./scripts/start-all.sh --skip-plugins   # 跳过插件 JAR 打包
#   ./scripts/start-all.sh --port 3001      # 指定前端端口
#   ./scripts/start-all.sh --db /path/db.sqlite  # 自定义数据库路径
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BACKEND_DIR="${PROJECT_DIR}/backend"
FRONTEND_DIR="${PROJECT_DIR}/frontend"
PLUGIN_DIR="${BACKEND_DIR}/plugin-l4d2/plugin-l4d2-core"

SERVER_PORT=8080
FRONTEND_PORT=3000
DB_PATH="${BACKEND_DIR}/data/game_platform.db"
MAIN_CLASS="com.gameplatform.GamePlatformApplication"
LOG_DIR="${PROJECT_DIR}/logs"
BACKEND_LOG="${LOG_DIR}/backend.log"
FRONTEND_LOG="${LOG_DIR}/frontend.log"
BACKEND_PID_FILE="${LOG_DIR}/backend.pid"
FRONTEND_PID_FILE="${LOG_DIR}/frontend.pid"

JVM_OPTS=(
  "-Xms512m"
  "-Xmx1024m"
  "-XX:MaxMetaspaceSize=256m"
  "-XX:+UseG1GC"
  "-XX:+HeapDumpOnOutOfMemoryError"
  "-Dspring.devtools.restart.enabled=false"
  "-Dspring.devtools.livereload.enabled=false"
  "-Djava.net.preferIPv4Stack=true"
)

BACKEND_ONLY=0
FRONTEND_ONLY=0
SKIP_COMPILE=0
SKIP_PLUGINS=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend-only) BACKEND_ONLY=1; shift ;;
    --frontend-only) FRONTEND_ONLY=1; shift ;;
    --skip-compile) SKIP_COMPILE=1; shift ;;
    --skip-plugins) SKIP_PLUGINS=1; shift ;;
    --port) FRONTEND_PORT="$2"; shift 2 ;;
    --db) DB_PATH="$2"; shift 2 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

if [[ ${BACKEND_ONLY} -eq 1 && ${FRONTEND_ONLY} -eq 1 ]]; then
  echo "错误: --backend-only 与 --frontend-only 不能同时指定"; exit 1
fi

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
mkdir -p "${LOG_DIR}"

# 仅杀掉占用目标端口的进程（避免误杀其他 Java/Node 进程）
kill_port() {
  local port="$1"
  local pid
  pid="$(netstat -ano 2>/dev/null | awk -v p=":${port}" '$1 ~ /TCP/ && $2 ~ p"$" && $4 == "LISTENING" {print $NF; exit}')"
  if [[ -n "${pid}" ]]; then
    log "端口 ${port} 被 PID ${pid} 占用，停止旧进程"
    taskkill //F //PID "${pid}" >/dev/null 2>&1 || true
    sleep 2
  fi
}

# ============ 1. 后端 ============
if [[ ${FRONTEND_ONLY} -eq 0 ]]; then
  log "========== [1/2] 启动后端 (端口 ${SERVER_PORT}) =========="
  cd "${BACKEND_DIR}"

  if [[ ${SKIP_COMPILE} -eq 0 ]]; then
    log "编译后端..."
    mvn -pl core -am install -DskipTests -q
    log "生成 classpath..."
    mvn -pl core dependency:build-classpath -Dmdep.outputFile=target/cp.txt -q
  fi

  if [[ ${SKIP_PLUGINS} -eq 0 ]]; then
    if [[ -d "${PLUGIN_DIR}" ]]; then
      log "打包插件 JAR 并部署..."
      mvn -pl plugin-l4d2/plugin-l4d2-core -am install -DskipTests -q
      mkdir -p "${BACKEND_DIR}/plugins"
      cp "${PLUGIN_DIR}/target/plugin-l4d2-core-1.0.0-SNAPSHOT.jar" "${BACKEND_DIR}/plugins/"
    else
      log "插件源码目录不存在，跳过插件打包"
    fi
  fi

  CP_FILE="${BACKEND_DIR}/core/target/cp.txt"
  if [[ ! -f "${CP_FILE}" ]]; then
    echo "错误: classpath 文件不存在 ${CP_FILE}（需先编译）"; exit 1
  fi
  DEP_CP="$(cat "${CP_FILE}")"
  # Windows 版 java 使用分号分隔 classpath（cp.txt 已为分号分隔），
  # 前三个项目模块段同样用分号拼接，避免与冒号混用导致主类找不到
  CP="core/target/classes;api/target/classes;plugin/target/classes;${DEP_CP}"

  mkdir -p "$(dirname "${DB_PATH}")"
  # Git Bash 的 /d/... 路径 Windows JDBC 不识别，转成 D:/... 风格
  if command -v cygpath >/dev/null 2>&1; then
    DB_WIN="$(cygpath -w "${DB_PATH}")"
  else
    DB_WIN="${DB_PATH}"
  fi
  DB_URL="jdbc:sqlite:${DB_WIN//\\//}"

  kill_port ${SERVER_PORT}
  log "启动后端进程 (DB=${DB_PATH})..."
  nohup java -cp "${CP}" "${JVM_OPTS[@]}" \
    -Dspring.datasource.url="${DB_URL}" \
    "${MAIN_CLASS}" --server.port=${SERVER_PORT} \
    > "${BACKEND_LOG}" 2>&1 &
  echo $! > "${BACKEND_PID_FILE}"
  log "后端 PID: $(cat "${BACKEND_PID_FILE}")，日志: ${BACKEND_LOG}"
fi

# ============ 2. 前端 ============
if [[ ${BACKEND_ONLY} -eq 0 ]]; then
  log "========== [2/2] 启动前端 (端口 ${FRONTEND_PORT}) =========="
  cd "${FRONTEND_DIR}"

  if [[ ! -d node_modules ]]; then
    log "node_modules 不存在，执行 npm install..."
    npm install
  fi

  VITE_JS="${FRONTEND_DIR}/node_modules/vite/bin/vite.js"
  if [[ ! -f "${VITE_JS}" ]]; then
    echo "错误: 未找到 ${VITE_JS}（npm install 后重试）"; exit 1
  fi

  kill_port ${FRONTEND_PORT}
  log "启动 Vite..."
  nohup node "${VITE_JS}" --port ${FRONTEND_PORT} --strictPort > "${FRONTEND_LOG}" 2>&1 &
  echo $! > "${FRONTEND_PID_FILE}"
  log "前端 PID: $(cat "${FRONTEND_PID_FILE}")，日志: ${FRONTEND_LOG}"
fi

# ============ 健康检查 ============
check_up() {
  local port="$1" name="$2" tries=0
  while (( tries < 30 )); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:${port}/" 2>/dev/null || true)"
    if [[ "${code}" != "000" && -n "${code}" ]]; then
      log "${name} 已就绪 (http=${code})"
      return 0
    fi
    sleep 2; (( tries++ ))
  done
  log "${name} 启动超时（30s），请查看日志"
  return 1
}

if [[ ${FRONTEND_ONLY} -eq 0 ]]; then check_up ${SERVER_PORT} "后端" || true; fi
if [[ ${BACKEND_ONLY} -eq 0 ]]; then check_up ${FRONTEND_PORT} "前端" || true; fi

log "############################################"
log "#          全部启动完成                     #"
log "############################################"
if [[ ${FRONTEND_ONLY} -eq 0 ]]; then
  log "后端地址: http://localhost:${SERVER_PORT}"
  log "API 文档: http://localhost:${SERVER_PORT}/swagger-ui.html"
fi
if [[ ${BACKEND_ONLY} -eq 0 ]]; then
  log "前端地址: http://localhost:${FRONTEND_PORT}"
fi
