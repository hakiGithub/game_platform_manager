#!/bin/bash
#
# Game Platform Manager - 停止脚本 (Linux)
# 
# 功能说明:
#   - 优雅停止应用 (发送SIGTERM)
#   - 强制停止应用 (发送SIGKILL)
#   - 进程检查和PID文件管理
#
# 使用方法:
#   ./stop.sh              # 优雅停止 (默认)
#   ./stop.sh force        # 强制停止
#   ./stop.sh status       # 查看状态
#
# 作者: Game Platform Manager
# 创建日期: 2026-03-23
#

set -e

# ============================================
# 配置区域
# ============================================

# 应用名称
APP_NAME="game-platform-manager"

# 脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 项目根目录
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# PID文件路径
PID_DIR="${PROJECT_DIR}/logs"
PID_FILE="${PID_DIR}/${APP_NAME}.pid"

# 日志目录
LOG_DIR="${PROJECT_DIR}/logs"
LOG_FILE="${LOG_DIR}/startup.log"

# 服务端口
SERVER_PORT=8080

# 停止等待超时时间(秒)
STOP_TIMEOUT=30

# ============================================
# 工具函数
# ============================================

# 打印带时间戳的日志
log() {
    local level="$1"
    local message="$2"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[${timestamp}] [${level}] ${message}"
    if [ -d "${LOG_DIR}" ]; then
        echo "[${timestamp}] [${level}] ${message}" >> "${LOG_FILE}"
    fi
}

log_info() {
    log "INFO" "$1"
}

log_warn() {
    log "WARN" "$1"
}

log_error() {
    log "ERROR" "$1"
}

# 检查应用是否正在运行
is_running() {
    if [ -f "${PID_FILE}" ]; then
        local pid=$(cat "${PID_FILE}")
        if ps -p "${pid}" > /dev/null 2>&1; then
            return 0
        fi
    fi
    return 1
}

# 获取PID
get_pid() {
    if [ -f "${PID_FILE}" ]; then
        cat "${PID_FILE}"
    fi
}

# 查找可能的Java进程
find_java_process() {
    # 通过端口查找
    local pid=$(netstat -tlnp 2>/dev/null | grep ":${SERVER_PORT} " | awk '{print $7}' | cut -d'/' -f1 | head -n 1)
    if [ -n "${pid}" ]; then
        echo "${pid}"
        return
    fi
    
    # 通过进程名查找
    pid=$(ps aux | grep "game-platform-manager" | grep -v grep | awk '{print $2}' | head -n 1)
    if [ -n "${pid}" ]; then
        echo "${pid}"
        return
    fi
    
    # 通过主类查找
    pid=$(ps aux | grep "com.gameplatform.GamePlatformApplication" | grep -v grep | awk '{print $2}' | head -n 1)
    if [ -n "${pid}" ]; then
        echo "${pid}"
        return
    fi
}

# 等待进程停止
wait_for_stop() {
    local pid="$1"
    local start_time=$(date +%s)
    local end_time=$((start_time + STOP_TIMEOUT))
    
    log_info "等待进程停止 (PID: ${pid}, 超时: ${STOP_TIMEOUT}秒)..."
    
    while [ $(date +%s) -lt ${end_time} ]; do
        if ! ps -p "${pid}" > /dev/null 2>&1; then
            log_info "进程已停止"
            return 0
        fi
        sleep 1
    done
    
    log_warn "进程停止超时"
    return 1
}

# 清理PID文件
cleanup_pid_file() {
    if [ -f "${PID_FILE}" ]; then
        rm -f "${PID_FILE}"
        log_info "已清理PID文件: ${PID_FILE}"
    fi
}

# ============================================
# 停止函数
# ============================================

# 优雅停止
stop_graceful() {
    log_info "========== 开始停止应用 (优雅停止) =========="
    
    if ! is_running; then
        log_warn "应用未在运行"
        
        # 尝试查找残留进程
        local orphan_pid=$(find_java_process)
        if [ -n "${orphan_pid}" ]; then
            log_warn "发现残留进程，PID: ${orphan_pid}"
            read -p "是否停止该进程? [y/N]: " confirm
            if [ "${confirm}" = "y" ] || [ "${confirm}" = "Y" ]; then
                kill "${orphan_pid}" 2>/dev/null || true
                log_info "已发送停止信号"
            fi
        fi
        
        cleanup_pid_file
        exit 0
    fi
    
    local pid=$(get_pid)
    log_info "应用正在运行，PID: ${pid}"
    
    # 发送SIGTERM信号
    log_info "发送SIGTERM信号..."
    kill "${pid}" 2>/dev/null
    
    # 等待进程停止
    if wait_for_stop "${pid}"; then
        cleanup_pid_file
        log_info "========== 应用已停止 =========="
        exit 0
    else
        log_warn "优雅停止超时，请使用强制停止: $0 force"
        exit 1
    fi
}

# 强制停止
stop_force() {
    log_info "========== 开始停止应用 (强制停止) =========="
    
    local pid=""
    
    if is_running; then
        pid=$(get_pid)
    else
        # 尝试查找进程
        pid=$(find_java_process)
    fi
    
    if [ -z "${pid}" ]; then
        log_warn "未找到运行中的应用进程"
        cleanup_pid_file
        exit 0
    fi
    
    log_info "应用进程PID: ${pid}"
    
    # 先尝试SIGTERM
    log_info "发送SIGTERM信号..."
    kill "${pid}" 2>/dev/null || true
    sleep 3
    
    # 检查是否停止
    if ! ps -p "${pid}" > /dev/null 2>&1; then
        cleanup_pid_file
        log_info "========== 应用已停止 =========="
        exit 0
    fi
    
    # 强制SIGKILL
    log_warn "进程未响应，发送SIGKILL信号..."
    kill -9 "${pid}" 2>/dev/null || true
    sleep 1
    
    # 最终检查
    if ! ps -p "${pid}" > /dev/null 2>&1; then
        cleanup_pid_file
        log_info "========== 应用已强制停止 =========="
        exit 0
    else
        log_error "无法停止进程，请手动处理"
        exit 1
    fi
}

# 查看状态
show_status() {
    echo "========== 应用状态 =========="
    echo ""
    
    if is_running; then
        local pid=$(get_pid)
        echo "状态: 运行中"
        echo "PID: ${pid}"
        echo "PID文件: ${PID_FILE}"
        echo ""
        
        # 显示进程信息
        echo "进程信息:"
        ps -p "${pid}" -o pid,ppid,user,%cpu,%mem,vsz,rss,stat,start,time,command 2>/dev/null || echo "无法获取进程信息"
        echo ""
        
        # 检查端口
        echo "端口监听:"
        netstat -tlnp 2>/dev/null | grep ":${SERVER_PORT}" || ss -tlnp 2>/dev/null | grep ":${SERVER_PORT}" || echo "端口 ${SERVER_PORT} 未监听"
    else
        echo "状态: 未运行"
        
        # 检查是否有残留进程
        local orphan_pid=$(find_java_process)
        if [ -n "${orphan_pid}" ]; then
            echo ""
            echo "警告: 发现可能的残留进程，PID: ${orphan_pid}"
        fi
    fi
    
    echo ""
    echo "=============================="
}

# 显示帮助信息
show_help() {
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  (无参数)    优雅停止 (发送SIGTERM)"
    echo "  force       强制停止 (发送SIGKILL)"
    echo "  status      查看应用状态"
    echo "  help        显示此帮助信息"
    echo ""
    echo "示例:"
    echo "  $0          # 优雅停止"
    echo "  $0 force    # 强制停止"
    echo "  $0 status   # 查看状态"
}

# ============================================
# 主程序
# ============================================

main() {
    local mode="${1:-graceful}"
    
    # 确保日志目录存在
    mkdir -p "${LOG_DIR}"
    
    case "${mode}" in
        force|-f)
            stop_force
            ;;
        graceful|stop)
            stop_graceful
            ;;
        status)
            show_status
            ;;
        help|--help|-h)
            show_help
            exit 0
            ;;
        *)
            log_error "未知参数: ${mode}"
            show_help
            exit 1
            ;;
    esac
}

main "$@"
