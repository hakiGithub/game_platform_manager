<#
.SYNOPSIS
    Game Platform Manager - 一键编译并重启前后端 (Windows PowerShell)

.DESCRIPTION
    统一脚本，依次执行：
    1. 编译并重启后端（mvn compile + java -cp 启动）
    2. 重启前端（npm run dev）
    后端使用 java -cp 方式启动，绕过 spring-boot:run 无法保持运行的问题
    覆盖数据库 URL 到项目目录，绕过 TRAE 沙箱写入限制

.PARAMETER BackendOnly
    仅重启后端

.PARAMETER FrontendOnly
    仅重启前端

.PARAMETER SkipBackendCompile
    跳过后端编译步骤

.PARAMETER FrontendPort
    前端端口（默认 3000）

.PARAMETER DbPath
    自定义数据库路径（默认：backend/data/game_platform.db）

.EXAMPLE
    .\restart-all.ps1
    编译并重启前后端

.EXAMPLE
    .\restart-all.ps1 -BackendOnly
    仅重启后端

.EXAMPLE
    .\restart-all.ps1 -FrontendOnly
    仅重启前端

.EXAMPLE
    .\restart-all.ps1 -SkipBackendCompile
    跳过后端编译，仅重启

.EXAMPLE
    .\restart-all.ps1 -FrontendPort 3001
    指定前端端口 3001

.NOTES
    作者: Game Platform Manager
    创建日期: 2026-07-16
    用途：测试前自行重启前后端服务进行测试
#>

param(
    [switch]$BackendOnly,
    [switch]$FrontendOnly,
    [switch]$SkipBackendCompile,
    [int]$FrontendPort = 3000,
    [string]$DbPath = ""
)

# ============================================
# 配置区域
# ============================================

$script:SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
# scripts/ 在项目根目录下，需要回退一级
$script:PROJECT_DIR = Split-Path -Parent $SCRIPT_DIR
$script:BACKEND_DIR = Join-Path $PROJECT_DIR "backend"
$script:FRONTEND_DIR = Join-Path $PROJECT_DIR "frontend"

$script:BACKEND_SCRIPT = Join-Path $BACKEND_DIR "scripts\rebuild-restart.ps1"
$script:FRONTEND_SCRIPT = Join-Path $FRONTEND_DIR "scripts\rebuild-restart.ps1"

$script:LOG_DIR = Join-Path $PROJECT_DIR "logs"
$script:STARTUP_LOG_FILE = Join-Path $LOG_DIR "restart-all.log"

# ============================================
# 工具函数
# ============================================

function Write-Log {
    param(
        [string]$Level = "INFO",
        [string]$Message = ""
    )
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $logMessage = "[$timestamp] [$Level] $Message"
    Write-Host $logMessage
    if (Test-Path $LOG_DIR) {
        Add-Content -Path $STARTUP_LOG_FILE -Value $logMessage
    }
}

function Write-LogInfo { param([string]$Message) Write-Log -Level "INFO" -Message $Message }
function Write-LogWarn { param([string]$Message) Write-Log -Level "WARN" -Message $Message }
function Write-LogError { param([string]$Message) Write-Log -Level "ERROR" -Message $Message }

# ============================================
# 主程序
# ============================================

# 确保日志目录存在
if (-not (Test-Path $LOG_DIR)) {
    New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null
}

Write-LogInfo "############################################"
Write-LogInfo "#  Game Platform Manager - 一键编译并重启  #"
Write-LogInfo "############################################"
Write-LogInfo "项目目录: $PROJECT_DIR"
Write-LogInfo "仅后端: $($BackendOnly.IsPresent) / 仅前端: $($FrontendOnly.IsPresent)"
Write-LogInfo "跳过后端编译: $($SkipBackendCompile.IsPresent)"
Write-LogInfo "前端端口: $FrontendPort"
if ($DbPath) {
    Write-LogInfo "自定义数据库路径: $DbPath"
}

$startTime = Get-Date
$success = $true

# 校验：BackendOnly 和 FrontendOnly 不能同时为 true
if ($BackendOnly -and $FrontendOnly) {
    Write-LogError "不能同时指定 -BackendOnly 和 -FrontendOnly"
    exit 1
}

# ============================================
# 1. 重启后端
# ============================================

if (-not $FrontendOnly) {
    Write-LogInfo ""
    Write-LogInfo "========== [1/2] 重启后端 =========="

    if (-not (Test-Path $BACKEND_SCRIPT)) {
        Write-LogError "后端脚本不存在: $BACKEND_SCRIPT"
        $success = $false
    } else {
        # 构建后端脚本参数（使用 hashtable 正确传递 switch 参数，避免被当作位置参数绑定到 DbPath）
        $backendArgs = @{}
        if ($SkipBackendCompile) {
            $backendArgs['SkipCompile'] = $true
        }
        if ($DbPath) {
            $backendArgs['DbPath'] = $DbPath
        }

        $argDisplay = ($backendArgs.GetEnumerator() | ForEach-Object { "-$($_.Key) $($_.Value)" }) -join ' '
        Write-LogInfo "执行后端脚本: $BACKEND_SCRIPT $argDisplay"
        & $BACKEND_SCRIPT @backendArgs

        if ($LASTEXITCODE -ne 0) {
            Write-LogError "后端重启失败 (exit code: $LASTEXITCODE)"
            $success = $false
        } else {
            Write-LogInfo "后端重启成功"
        }
    }
}

# ============================================
# 2. 重启前端
# ============================================

if (-not $BackendOnly -and $success) {
    Write-LogInfo ""
    Write-LogInfo "========== [2/2] 重启前端 =========="

    if (-not (Test-Path $FRONTEND_SCRIPT)) {
        Write-LogError "前端脚本不存在: $FRONTEND_SCRIPT"
        $success = $false
    } else {
        Write-LogInfo "执行前端脚本: $FRONTEND_SCRIPT -Port $FrontendPort"
        & $FRONTEND_SCRIPT -Port $FrontendPort

        if ($LASTEXITCODE -ne 0) {
            Write-LogError "前端重启失败 (exit code: $LASTEXITCODE)"
            $success = $false
        } else {
            Write-LogInfo "前端重启成功"
        }
    }
}

# ============================================
# 汇总
# ============================================

$endTime = Get-Date
$duration = ($endTime - $startTime).TotalSeconds

Write-LogInfo ""
Write-LogInfo "############################################"
if ($success) {
    Write-LogInfo "#          全部重启完成                    #"
} else {
    Write-LogInfo "#          重启过程存在问题                #"
}
Write-LogInfo "############################################"
Write-LogInfo "总耗时: $([math]::Round($duration, 1)) 秒"
Write-LogInfo ""

if (-not $FrontendOnly) {
    Write-LogInfo "后端访问地址: http://localhost:8080"
    Write-LogInfo "后端 API 文档: http://localhost:8080/swagger-ui.html"
    Write-LogInfo "后端应用日志: $BACKEND_DIR\logs\application.log"
}
if (-not $BackendOnly) {
    Write-LogInfo "前端访问地址: http://localhost:$FrontendPort"
    Write-LogInfo "前端应用日志: $FRONTEND_DIR\logs\frontend.log"
}

Write-LogInfo ""
Write-LogInfo "统一日志: $STARTUP_LOG_FILE"

if (-not $success) {
    exit 1
}
