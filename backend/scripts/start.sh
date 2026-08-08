#!/bin/bash
#
# Game Platform Manager - 启动脚本 (Linux)
# 
# 功能说明:
#   - 支持开发环境启动 (mvn spring-boot:run)
#   - 支持生产环境启动 (java -jar xxx.jar)
#   - 包含JVM参数配置、日志记录、进程检查、健康检查
#
# 使用方法:
#   ./start.sh              # 生产环境启动 (默认)
#   ./start.sh dev          # 开发环境启动
#   ./start.sh prod         # 生产环境启动
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

# JAR文件路径
JAR_NAME="${APP_NAME}.jar"
JAR_FILE="${PROJECT_DIR}/target/${JAR_NAME}"

# PID文件路径
PID_DIR="${PROJECT_DIR}/logs"
PID_FILE="${PID_DIR}/${APP_NAME}.pid"

# 日志目录
LOG_DIR="${PROJECT_DIR}/logs"
LOG_FILE="${LOG_DIR}/startup.log"
APP_LOG_FILE="${LOG_DIR}/application.log"

# 主类
MAIN_CLASS="com.gameplatform.GamePlatformApplication"

# 服务端口
SERVER_PORT=8080

# 健康检查URL
HEALTH_CHECK_URL="http://localhost:${SERVER_PORT}/actuator/health"

# 启动等待超时时间(秒)
STARTUP_TIMEOUT=60

# ============================================
# JVM参数配置
# ============================================

# 内存配置
JVM_XMS="512m"          # 初始堆内存
JVM_XMX="1024m"         # 最大堆内存
JVM_METASPACE="256m"    # 元空间大小

# GC配置 (G1GC - Java 17推荐)
JVM_GC_OPTS="-XX:+UseG1GC \
-XX:MaxGCPauseMillis=200 \
-XX:ParallelGCThreads=4 \
-XX:ConcGCThreads=2 \
-XX:+ExplicitGCInvokesConcurrent"

# 性能优化参数
JVM_PERF_OPTS="-XX:+UseStringDeduplication \
-XX:+OptimizeStringConcat \
-XX:+UseCompressedOops \
-XX:+UseCompressedClassPointers"

# 内存溢出时生成堆转储
JVM_OOM_OPTS="-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=${LOG_DIR}/heap_dump.hprof"

# GC日志配置
JVM_GC_LOG_OPTS="-Xlog:gc*:file=${LOG_DIR}/gc.log:time,uptime,level,tags:filecount=5,filesize=10M"

# 远程调试参数 (生产环境默认关闭)
# JVM_DEBUG_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

# 组合JVM参数
JVM_OPTS="-Xms${JVM_XMS} \
-Xmx${JVM_XMX} \
-XX:MaxMetaspaceSize=${JVM_METASPACE} \
${JVM_GC_OPTS} \
${JVM_PERF_OPTS} \
${JVM_OOM_OPTS} \
${JVM_GC_LOG_OPTS}"

# Spring Boot参数
SPRING_OPTS="--spring.profiles.active=prod \
--server.port=${SERVER_PORT} \
--logging.file.path=${LOG_DIR}"

# ============================================
# 工具函数
# ============================================

# 打印带时间戳的日志
log() {
    local level="$1"
    local message="$2"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[${timestamp}] [${level}] ${message}"
    echo "[${timestamp}] [${level}] ${message}" >> "${LOG_FILE}"
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

# 等待应用启动完成
wait_for_startup() {
    local start_time=$(date +%s)
    local end_time=$((start_time + STARTUP_TIMEOUT))
    
    log_info "等待应用启动完成 (超时: ${STARTUP_TIMEOUT}秒)..."
    
    while [ $(date +%s) -lt ${end_time} ]; do
        # 检查进程是否还存在
        if ! is_running; then
            log_error "应用进程已退出，启动失败"
            return 1
        fi
        
        # 检查健康检查端点
        if curl -sf "${HEALTH_CHECK_URL}" > /dev/null 2>&1; then
            log_info "应用启动成功，健康检查通过"
            return 0
        fi
        
        # 检查端口是否被监听
        if netstat -tuln 2>/dev/null | grep -q ":${SERVER_PORT} " || \
           ss -tuln 2>/dev/null | grep -q ":${SERVER_PORT} "; then
            # 端口已监听，再等待几秒确保应用完全启动
            sleep 3
            if curl -sf "${HEALTH_CHECK_URL}" > /dev/null 2>&1; then
                log_info "应用启动成功，健康检查通过"
                return 0
            fi
            log_info "应用已监听端口 ${SERVER_PORT}，但健康检查未通过，继续等待..."
        fi
        
        sleep 2
    done
    
    log_error "应用启动超时 (${STARTUP_TIMEOUT}秒)"
    return 1
}

# 检查前置条件
check_prerequisites() {
    log_info "检查前置条件..."
    
    # 检查Java
    if ! command -v java &> /dev/null; then
        log_error "未找到Java，请确保Java 17已安装并配置PATH环境变量"
        exit 1
    fi
    
    local java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "${java_version}" -lt 17 ]; then
        log_error "Java版本过低，需要Java 17或更高版本，当前版本: ${java_version}"
        exit 1
    fi
    log_info "Java版本检查通过"
    
    # 创建必要目录
    mkdir -p "${LOG_DIR}"
    mkdir -p "${PID_DIR}"
    
    log_info "前置条件检查完成"
}

# 检查开发环境前置条件
check_dev_prerequisites() {
    check_prerequisites
    
    if ! command -v mvn &> /dev/null; then
        log_error "未找到Maven，请确保Maven已安装并配置PATH环境变量"
        exit 1
    fi
    log_info "Maven检查通过"
}

# ============================================
# 启动函数
# ============================================

# 生产环境启动
start_prod() {
    log_info "========== 开始启动应用 (生产环境) =========="
    
    # 检查是否已运行
    if is_running; then
        local pid=$(get_pid)
        log_warn "应用已在运行中，PID: ${pid}"
        exit 0
    fi
    
    # 检查JAR文件
    if [ ! -f "${JAR_FILE}" ]; then
        log_error "JAR文件不存在: ${JAR_FILE}"
        log_error "请先执行打包命令: mvn clean package -DskipTests"
        exit 1
    fi
    
    check_prerequisites
    
    log_info "JAR文件: ${JAR_FILE}"
    log_info "JVM参数: ${JVM_OPTS}"
    log_info "日志目录: ${LOG_DIR}"
    
    # 启动应用
    log_info "正在启动应用..."
    
    cd "${PROJECT_DIR}"
    
    nohup java ${JVM_OPTS} \
        -jar "${JAR_FILE}" \
        ${SPRING_OPTS} \
        >> "${APP_LOG_FILE}" 2>&1 &
    
    local pid=$!
    echo "${pid}" > "${PID_FILE}"
    
    log_info "应用进程已启动，PID: ${pid}"
    
    # 等待启动完成
    if wait_for_startup; then
        log_info "========== 应用启动成功 =========="
        log_info "访问地址: http://localhost:${SERVER_PORT}"
        log_info "API文档: http://localhost:${SERVER_PORT}/swagger-ui.html"
        exit 0
    else
        log_error "========== 应用启动失败 =========="
        # 清理PID文件
        rm -f "${PID_FILE}"
        exit 1
    fi
}

# 开发环境启动
start_dev() {
    log_info "========== 开始启动应用 (开发环境) =========="
    
    # 检查是否已运行
    if is_running; then
        local pid=$(get_pid)
        log_warn "应用已在运行中，PID: ${pid}"
        exit 0
    fi
    
    check_dev_prerequisites
    
    log_info "项目目录: ${PROJECT_DIR}"
    log_info "主类: ${MAIN_CLASS}"
    
    cd "${PROJECT_DIR}"
    
    # 启动应用 (使用Maven)
    log_info "正在启动应用 (mvn spring-boot:run)..."
    
    nohup mvn spring-boot:run \
        -Dspring-boot.run.jvmArguments="-Xms256m -Xmx512m" \
        -Dspring-boot.run.arguments="--server.port=${SERVER_PORT}" \
        >> "${APP_LOG_FILE}" 2>&1 &
    
    local pid=$!
    echo "${pid}" > "${PID_FILE}"
    
    log_info "应用进程已启动，PID: ${pid}"
    
    # 等待启动完成
    if wait_for_startup; then
        log_info "========== 应用启动成功 (开发模式) =========="
        log_info "访问地址: http://localhost:${SERVER_PORT}"
        log_info "API文档: http://localhost:${SERVER_PORT}/swagger-ui.html"
        exit 0
    else
        log_error "========== 应用启动失败 =========="
        rm -f "${PID_FILE}"
        exit 1
    fi
}

# 显示帮助信息
show_help() {
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  (无参数)    生产环境启动 (默认)"
    echo "  dev         开发环境启动 (使用mvn spring-boot:run)"
    echo "  prod        生产环境启动"
    echo "  help        显示此帮助信息"
    echo ""
    echo "示例:"
    echo "  $0          # 生产环境启动"
    echo "  $0 dev      # 开发环境启动"
}

# ============================================
# 主程序
# ============================================

main() {
    local mode="${1:-prod}"
    
    case "${mode}" in
        dev)
            start_dev
            ;;
        prod)
            start_prod
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
