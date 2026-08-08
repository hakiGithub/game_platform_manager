<#
.SYNOPSIS
    Game Platform Manager - 重启脚本 (Windows PowerShell)

.DESCRIPTION
    先停止应用，再启动应用
    支持优雅重启和强制重启
    支持开发环境和生产环境

.PARAMETER Environment
    运行环境: dev (开发环境) 或 prod (生产环境，默认)

.PARAMETER Force
    是否强制停止应用

.EXAMPLE
    .\restart.ps1
    生产环境优雅重启

.EXAMPLE
    .\restart.ps1 -Environment dev
    开发环境优雅重启

.EXAMPLE
    .\restart.ps1 -Force
    生产环境强制重启

.EXAMPLE
    .\restart.ps1 dev -Force
    开发环境强制重启

.NOTES
    作者: Game Platform Manager
    创建日期: 2026-03-23
#>

param(
    [Parameter(Position = 0)]
    [ValidateSet("dev", "prod", "help")]
    [string]$Environment = "prod",
    
    [switch]$Force
)

# ============================================
# 配置区域
# ============================================

# Script directory (hardcoded fallback for remote session compatibility)
$script:SCRIPT_DIR = $PSScriptRoot
if (-not $script:SCRIPT_DIR -and $MyInvocation.MyCommand.Path) { $script:SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $script:SCRIPT_DIR) { $script:SCRIPT_DIR = "d:\program\ai\game_platform_manger\backend\scripts" }

# 项目根目录
$script:PROJECT_DIR = Split-Path -Parent $SCRIPT_DIR

# 日志目录
$script:LOG_DIR = Join-Path $PROJECT_DIR "logs"
$script:LOG_FILE = Join-Path $LOG_DIR "startup.log"

# ============================================
# 工具函数
# ============================================

function Write-Log {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Level,
        
        [Parameter(Mandatory = $true)]
        [string]$Message
    )
    
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $logMessage = "[$timestamp] [$Level] $Message"
    
    Write-Host $logMessage
    
    if (Test-Path $LOG_DIR) {
        Add-Content -Path $LOG_FILE -Value $logMessage
    }
}

function Write-LogInfo {
    param([string]$Message)
    Write-Log -Level "INFO" -Message $Message
}

function Write-LogWarn {
    param([string]$Message)
    Write-Log -Level "WARN" -Message $Message
}

function Write-LogError {
    param([string]$Message)
    Write-Log -Level "ERROR" -Message $Message
}

function Show-Help {
    $helpText = @"

用法: .\$($MyInvocation.MyCommand.Name) [环境] [选项]

环境:
  prod        生产环境 (默认)
  dev         开发环境

选项:
  -Force      强制停止后重启
  help        显示此帮助信息

示例:
  .\$($MyInvocation.MyCommand.Name)              # 生产环境优雅重启
  .\$($MyInvocation.MyCommand.Name) dev          # 开发环境优雅重启
  .\$($MyInvocation.MyCommand.Name) -Force       # 生产环境强制重启
  .\$($MyInvocation.MyCommand.Name) dev -Force   # 开发环境强制重启

"@
    Write-Host $helpText
}

# ============================================
# 主程序
# ============================================

if ($Environment -eq "help") {
    Show-Help
    exit 0
}

# 确保日志目录存在
if (-not (Test-Path $LOG_DIR)) {
    New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null
}

Write-LogInfo "========== 开始重启应用 =========="
Write-LogInfo "环境: $Environment"
Write-LogInfo "强制停止: $($Force.IsPresent)"

# 停止应用
Write-LogInfo "正在停止应用..."
$stopScript = Join-Path $SCRIPT_DIR "stop.ps1"
if ($Force) {
    & $stopScript force
}
else {
    & $stopScript
}

# 等待端口释放
Start-Sleep -Seconds 2

# 启动应用
Write-LogInfo "正在启动应用..."
$startScript = Join-Path $SCRIPT_DIR "start.ps1"
& $startScript $Environment

Write-LogInfo "========== 应用重启完成 =========="
