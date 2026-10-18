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
#   ./scripts/start-all.sh --port 0         # 从 3000 起自动选择空闲端口
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
AUTO_FRONTEND_PORT=0
DB_PATH="${BACKEND_DIR}/data/game_platform.db"
MAIN_CLASS="com.gameplatform.GamePlatformApplication"
LOG_DIR="${PROJECT_DIR}/logs"
BACKEND_LOG="${LOG_DIR}/backend.log"
FRONTEND_LOG="${LOG_DIR}/frontend.log"
BACKEND_PID_FILE="${LOG_DIR}/backend.pid"
FRONTEND_PID_FILE="${LOG_DIR}/frontend.pid"

# 统一通过 JAVACMD 启动 Maven/JVM，兼容 Git Bash、WSL 和已配置的 JAVA_HOME。
JAVACMD=""
MAVEN_CMD=""
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  JAVACMD="${JAVA_HOME}/bin/java"
elif [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java.exe" ]]; then
  JAVACMD="${JAVA_HOME}/bin/java.exe"
else
  for candidate in \
    "/mnt/c/Program Files/Java/jdk-21/bin/java.exe" \
    "/c/Program Files/Java/jdk-21/bin/java.exe" \
    "$(command -v java 2>/dev/null || true)"; do
    if [[ -n "${candidate}" && -x "${candidate}" ]]; then
      JAVACMD="${candidate}"
      break
    fi
  done
fi

if command -v mvn.cmd >/dev/null 2>&1; then
  MAVEN_CMD="$(command -v mvn.cmd)"
elif command -v mvn >/dev/null 2>&1; then
  MAVEN_CMD="$(command -v mvn)"
elif [[ -n "${MAVEN_HOME:-}" && -x "${MAVEN_HOME}/bin/mvn" ]]; then
  MAVEN_CMD="${MAVEN_HOME}/bin/mvn"
fi

if [[ -z "${JAVACMD}" ]]; then
  echo "错误: 未找到 Java 17+ 可执行文件（可在 PATH 或 JAVA_HOME 中配置）" >&2
  exit 1
fi
if [[ -z "${MAVEN_CMD}" ]]; then
  echo "错误: 未找到 Maven（可在 PATH 中配置）" >&2
  exit 1
fi

NODE_CMD=""
if command -v node.exe >/dev/null 2>&1; then
  NODE_CMD="$(command -v node.exe)"
elif command -v node >/dev/null 2>&1; then
  NODE_CMD="$(command -v node)"
fi
if [[ -z "${NODE_CMD}" ]]; then
  echo "错误: 未找到 Node.js" >&2
  exit 1
fi

to_windows_path() {
  local path="$1"
  if [[ "${path}" == /mnt/[a-zA-Z]/* ]]; then
    printf '%s' "${path#/mnt/}" | sed 's#^\([a-zA-Z]\)/#\1:/#'
  elif [[ "${path}" == /[a-zA-Z]/* ]]; then
    printf '%s' "${path#/}" | sed 's#^\([a-zA-Z]\)/#\1:/#'
  else
    printf '%s' "${path}"
  fi
}

run_mvn() {
  if [[ "${MAVEN_CMD}" == *.cmd ]]; then
    local java_win maven_win
    java_win="$(to_windows_path "${JAVACMD}")"
    maven_win="$(to_windows_path "${MAVEN_CMD}")"
    if [[ "${java_win}" == */bin/java.exe ]]; then
      java_win="${java_win%/bin/java.exe}"
    fi
    cmd.exe /c "set JAVA_HOME=${java_win//\//\\}&& ${maven_win//\//\\} $*"
  elif command -v cygpath >/dev/null 2>&1; then
    env JAVA_HOME="$(cygpath -w "$(dirname "$(dirname "${JAVACMD}")")")" "${MAVEN_CMD}" "$@"
  else
    env JAVA_HOME="$(dirname "$(dirname "${JAVACMD}")")" "${MAVEN_CMD}" "$@"
  fi
}

JVM_OPTS=(
  "-Xms256m"
  "-Xmx384m"
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
    --port)
      FRONTEND_PORT="$2"
      AUTO_FRONTEND_PORT=0
      if [[ "${FRONTEND_PORT}" == "0" ]]; then
        FRONTEND_PORT=3000
        AUTO_FRONTEND_PORT=1
      fi
      shift 2
      ;;
    --db) DB_PATH="$2"; shift 2 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

if [[ ${BACKEND_ONLY} -eq 1 && ${FRONTEND_ONLY} -eq 1 ]]; then
  echo "错误: --backend-only 与 --frontend-only 不能同时指定"; exit 1
fi

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
mkdir -p "${LOG_DIR}"

# 包装 powershell.exe 路径参数中的单引号，供 Windows 侧解析。
ps_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/''/g")"
}

windows_path() {
  local path="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "${path}"
  else
    to_windows_path "${path}"
  fi
}

# WSL/Git Bash 中启动 Windows 程序时，不能把 /mnt/d/... 或 /d/... 直接交给 Node/Java。
JAVACMD_WIN="$(windows_path "${JAVACMD}")"
NODE_CMD_WIN="$(windows_path "${NODE_CMD}")"
PROJECT_DIR_WIN="$(windows_path "${PROJECT_DIR}")"
BACKEND_DIR_WIN="$(windows_path "${BACKEND_DIR}")"
FRONTEND_DIR_WIN="$(windows_path "${FRONTEND_DIR}")"
BACKEND_LOG_WIN="$(windows_path "${BACKEND_LOG}")"
FRONTEND_LOG_WIN="$(windows_path "${FRONTEND_LOG}")"
BACKEND_PID_FILE_WIN="$(windows_path "${BACKEND_PID_FILE}")"
FRONTEND_PID_FILE_WIN="$(windows_path "${FRONTEND_PID_FILE}")"

start_windows_process() {
  local work_dir="$1" log_file="$2" pid_file="$3"
  shift 3

  local file_path="$1"
  shift
  local arguments="@("
  local arg
  for arg in "$@"; do
    arguments+="$(ps_quote "${arg}"),"
  done
  arguments="${arguments%,})"

  local err_file="${log_file%.log}.err.log"
  local start_command="\$p = Start-Process -FilePath $(ps_quote "${file_path}") -ArgumentList ${arguments} -WorkingDirectory $(ps_quote "${work_dir}") -WindowStyle Hidden -RedirectStandardOutput $(ps_quote "${log_file}") -RedirectStandardError $(ps_quote "${err_file}") -PassThru; \$p.Id | Set-Content -LiteralPath $(ps_quote "${pid_file}") -Encoding ascii"
  powershell.exe -NoProfile -NonInteractive -Command \
    "Start-Process -FilePath powershell.exe -ArgumentList '-NoProfile','-NonInteractive','-WindowStyle','Hidden','-Command',$(ps_quote "$start_command") -WindowStyle Hidden" \
    </dev/null >/dev/null 2>&1
}

# 返回目标端口上的监听 PID；空表示端口可用。
port_pid() {
  local port="$1"
  cmd.exe /c netstat -ano 2>/dev/null | tr -d '\r' | awk -v target=":${port}" '
      $1 == "TCP" && $4 == "LISTENING" && substr($2, length($2) - length(target) + 1) == target {
        print $5
        exit
      }
    '
}

pid_exists() {
  local pid="$1"
  [[ "${pid}" =~ ^[0-9]+$ ]] || return 1
  powershell.exe -NoProfile -NonInteractive -Command \
    "if (Get-Process -Id ${pid} -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }"
}

stop_windows_process() {
  local pid="$1"
  powershell.exe -NoProfile -NonInteractive -Command \
    "Stop-Process -Id ${pid} -Force -ErrorAction SilentlyContinue"
}

# 只停止本项目 PID 文件记录的旧前端进程，不按常见端口批量杀进程。
kill_frontend_pid() {
  if [[ ! -f "${FRONTEND_PID_FILE}" ]]; then
    return
  fi

  local pid
  pid="$(tr -d '[:space:]' < "${FRONTEND_PID_FILE}")"
  if pid_exists "${pid}"; then
    local cmdline
    cmdline="$(powershell.exe -NoProfile -NonInteractive -Command \
      "(Get-CimInstance Win32_Process -Filter 'ProcessId = ${pid}').CommandLine" \
      2>/dev/null | tr -d '\r')"
    local frontend_dir_win
    frontend_dir_win="$(windows_path "${FRONTEND_DIR}")"
    if [[ "${cmdline}" == *"${frontend_dir_win}"* && "${cmdline}" == *"vite"* ]]; then
      log "停止本项目旧前端进程 (PID ${pid})"
      stop_windows_process "${pid}"
      sleep 2
    else
      log "跳过非本项目前端 PID ${pid}（命令行不匹配）"
    fi
  fi
  rm -f "${FRONTEND_PID_FILE}"
}

# 端口开始监听后再记录真实 PID，避免把 nohup 包装进程写进 PID 文件。
# 首次启动需扫描 139 个游戏元数据，20 秒窗口在较慢机器上会裸奔到超时。
write_listener_pid() {
  local port="$1"
  local pid_file="$2"
  local tries=0
  local pid=""

  while (( tries < 120 )); do
    pid="$(port_pid "${port}")"
    if [[ -n "${pid}" ]]; then
      echo "${pid}" > "${pid_file}"
      return 0
    fi
    sleep 1
    (( tries++ ))
  done
  return 1
}

cleanup_failed_backend_start() {
  if [[ -f "${BACKEND_PID_FILE}" ]]; then
    local pid
    pid="$(tr -d '[:space:]' < "${BACKEND_PID_FILE}")"
    if pid_exists "${pid}"; then
      local cmdline
      cmdline="$(powershell.exe -NoProfile -NonInteractive -Command \
        "(Get-CimInstance Win32_Process -Filter 'ProcessId = ${pid}').CommandLine" \
        2>/dev/null | tr -d '\r')"
      if [[ "${cmdline}" == *"${MAIN_CLASS}"* ]]; then
        stop_windows_process "${pid}"
      fi
    fi
    rm -f "${BACKEND_PID_FILE}"
  fi
}

find_free_frontend_port() {
  local port
  for (( port = 3000; port <= 3099; port++ )); do
    if [[ -z "$(port_pid "${port}")" ]]; then
      echo "${port}"
      return 0
    fi
  done
  return 1
}

stop_managed_backend() {
  if [[ ! -f "${BACKEND_PID_FILE}" ]]; then
    return
  fi

  local pid
  pid="$(tr -d '[:space:]' < "${BACKEND_PID_FILE}")"
  if pid_exists "${pid}"; then
    local cmdline
    cmdline="$(powershell.exe -NoProfile -NonInteractive -Command \
      "(Get-CimInstance Win32_Process -Filter 'ProcessId = ${pid}').CommandLine" \
      2>/dev/null | tr -d '\r')"
    if [[ "${cmdline}" == *"${MAIN_CLASS}"* ]]; then
      log "停止本项目旧后端进程 (PID ${pid})"
      stop_windows_process "${pid}"
      sleep 2
    else
      log "跳过非本项目后端 PID ${pid}（命令行不匹配）"
    fi
  fi
  rm -f "${BACKEND_PID_FILE}"
}

# 自动模式只在目标端口被其他进程占用时选空闲端口。
resolve_frontend_port() {
  kill_frontend_pid

  if [[ ${AUTO_FRONTEND_PORT} -eq 1 && -n "$(port_pid "${FRONTEND_PORT}")" ]]; then
    local fallback
    fallback="$(find_free_frontend_port)" || {
      echo "错误: 3000-3099 没有可用的前端端口" >&2
      return 1
    }
    log "端口 ${FRONTEND_PORT} 被其他进程占用，自动改用 ${fallback}"
    FRONTEND_PORT="${fallback}"
  fi

  if [[ -n "$(port_pid "${FRONTEND_PORT}")" ]]; then
    echo "错误: 前端端口 ${FRONTEND_PORT} 已被其他进程占用；请使用 --port 0 自动选择端口" >&2
    return 1
  fi
}

# ============ 1. 后端 ============
if [[ ${FRONTEND_ONLY} -eq 0 ]]; then
  log "========== [1/2] 启动后端 (端口 ${SERVER_PORT}) =========="
  cd "${BACKEND_DIR}"

  if [[ ${SKIP_COMPILE} -eq 0 ]]; then
    log "编译后端..."
    run_mvn -pl core -am install -DskipTests -q
    log "生成 classpath..."
    run_mvn -pl core dependency:build-classpath -Dmdep.outputFile=target/cp.txt -q
  fi

  if [[ ${SKIP_PLUGINS} -eq 0 ]]; then
    if [[ -d "${PLUGIN_DIR}" ]]; then
      log "打包插件 JAR 并部署..."
      run_mvn -pl plugin-l4d2/plugin-l4d2-core -am install -DskipTests -q
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
  CP="$(windows_path "${BACKEND_DIR}/core/target/classes");$(windows_path "${BACKEND_DIR}/api/target/classes");$(windows_path "${BACKEND_DIR}/plugin/target/classes");${DEP_CP}"

  mkdir -p "$(dirname "${DB_PATH}")"
  # Git Bash 的 /d/...、WSL 的 /mnt/d/... 路径 Windows JDBC 都不识别，转成 D:/... 风格
  if command -v cygpath >/dev/null 2>&1; then
    DB_WIN="$(cygpath -w "${DB_PATH}")"
  elif [[ "${DB_PATH}" == /mnt/[a-zA-Z]/* ]]; then
    DB_WIN="$(printf '%s' "${DB_PATH#/mnt/}" | sed 's#^\([a-zA-Z]\)/#\1:/#')"
  elif [[ "${DB_PATH}" == /[a-zA-Z]/* ]]; then
    DB_WIN="$(printf '%s' "${DB_PATH#/}" | sed 's#^\([a-zA-Z]\)/#\1:/#')"
  else
    DB_WIN="${DB_PATH}"
  fi
  DB_URL="jdbc:sqlite:${DB_WIN//\\//}"

  stop_managed_backend
  if [[ -n "$(port_pid "${SERVER_PORT}")" ]]; then
    echo "错误: 后端端口 ${SERVER_PORT} 已被其他进程占用" >&2
    exit 1
  fi
  log "启动后端进程 (DB=${DB_PATH})..."
  start_windows_process "${BACKEND_DIR_WIN}" "${BACKEND_LOG_WIN}" "${BACKEND_PID_FILE_WIN}" \
    "${JAVACMD_WIN}" -cp "${CP}" "${JVM_OPTS[@]}" \
    -Dspring.datasource.url="${DB_URL}" \
    "${MAIN_CLASS}" --server.port=${SERVER_PORT}
  if ! write_listener_pid "${SERVER_PORT}" "${BACKEND_PID_FILE}"; then
    cleanup_failed_backend_start
    echo "错误: 后端进程未在 ${SERVER_PORT} 监听，请查看 ${BACKEND_LOG}" >&2
    exit 1
  fi
  log "后端 PID: $(cat "${BACKEND_PID_FILE}")，日志: ${BACKEND_LOG}"
fi

# ============ 2. 前端 ============
if [[ ${BACKEND_ONLY} -eq 0 ]]; then
  resolve_frontend_port
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

  log "启动 Vite..."
  start_windows_process "${FRONTEND_DIR_WIN}" "${FRONTEND_LOG_WIN}" "${FRONTEND_PID_FILE_WIN}" \
    "${NODE_CMD_WIN}" "$(windows_path "${VITE_JS}")" --port ${FRONTEND_PORT} --strictPort
  if ! write_listener_pid "${FRONTEND_PORT}" "${FRONTEND_PID_FILE}"; then
    echo "错误: 前端进程未在 ${FRONTEND_PORT} 监听，请查看 ${FRONTEND_LOG}" >&2
    exit 1
  fi
  log "前端 PID: $(cat "${FRONTEND_PID_FILE}")，日志: ${FRONTEND_LOG}"
  printf '%s\n' "${FRONTEND_PORT}" > "${FRONTEND_PID_FILE}.port"
fi

# 保留可用端口供 E2E runner 和调用脚本读取。
if [[ ${BACKEND_ONLY} -eq 0 ]]; then
  printf '%s\n' "${FRONTEND_PORT}" > "${LOG_DIR}/frontend.port"
fi

# ============ 健康检查 ============
# 后端没有可用的根路径，404 也会让 Invoke-WebRequest 抛异常；监听成功即视为启动成功。
wait_for_listener() {
  local port="$1" name="$2" timeout_seconds="${3:-120}" tries=0
  local max_tries=$(( timeout_seconds / 2 ))
  while (( tries < max_tries )); do
    if [[ -n "$(port_pid "${port}")" ]]; then
      log "${name} 已就绪 (监听端口 ${port})"
      return 0
    fi
    sleep 2; (( tries++ ))
  done
  log "${name} 启动超时（${timeout_seconds}s），请查看日志"
  return 1
}

if [[ ${FRONTEND_ONLY} -eq 0 ]]; then wait_for_listener ${SERVER_PORT} "后端" 120 || true; fi
if [[ ${BACKEND_ONLY} -eq 0 ]]; then wait_for_listener ${FRONTEND_PORT} "前端" 60 || true; fi

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
