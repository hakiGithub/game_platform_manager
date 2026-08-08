#!/bin/bash
#
# Game Platform Manager - 重启脚本 (Linux)
# 
# 功能说明:
#   - 先停止应用，再启动应用
#   - 支持优雅重启和强制重启
#   - 支持开发环境和生产环境
#
# 使用方法:
#   ./restart.sh              # 生产环境重启 (优雅停止)
#   ./restart.sh dev          # 开发环境重启
#   ./restart.sh force        # 生产环境强制重启
#   ./restart.sh dev force    # 开发环境强制重启
#
# 作者: Game Platform Manager
# 创建日期: 2026-03-23
#

set -e

# ============================================
# 配置区域
# ============================================

# 脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 日志目录
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOG_DIR="${PROJECT_DIR}/logs"
LOG_FILE="${LOG_DIR}/startup.log"

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

# 显示帮助信息
show_help() {
    echo "用法: $0 [环境] [选项]"
    echo ""
    echo "环境:"
    echo "  prod        生产环境 (默认)"
    echo "  dev         开发环境"
    echo ""
    echo "选项:"
    echo "  force       强制停止后重启"
    echo "  help        显示此帮助信息"
    echo ""
    echo "示例:"
    echo "  $0              # 生产环境优雅重启"
    echo "  $0 dev          # 开发环境优雅重启"
    echo "  $0 force        # 生产环境强制重启"
    echo "  $0 dev force    # 开发环境强制重启"
}

# ============================================
# 主程序
# ============================================

main() {
    local env="prod"
    local force_stop=false
    
    # 解析参数
    for arg in "$@"; do
        case "${arg}" in
            dev)
                env="dev"
                ;;
            prod)
                env="prod"
                ;;
            force|-f)
                force_stop=true
                ;;
            help|--help|-h)
                show_help
                exit 0
                ;;
        esac
    done
    
    # 确保日志目录存在
    mkdir -p "${LOG_DIR}"
    
    log_info "========== 开始重启应用 =========="
    log_info "环境: ${env}"
    log_info "强制停止: ${force_stop}"
    
    # 停止应用
    log_info "正在停止应用..."
    if [ "${force_stop}" = true ]; then
        "${SCRIPT_DIR}/stop.sh" force
    else
        "${SCRIPT_DIR}/stop.sh"
    fi
    
    # 等待一秒确保端口释放
    sleep 2
    
    # 启动应用
    log_info "正在启动应用..."
    "${SCRIPT_DIR}/start.sh" "${env}"
    
    log_info "========== 应用重启完成 =========="
}

main "$@"
