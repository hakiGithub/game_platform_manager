<#
.SYNOPSIS
    Game Platform Manager - 停止脚本 (Windows PowerShell)

.DESCRIPTION
    支持优雅停止应用和强制停止应用
    包含进程检查和PID文件管理

.PARAMETER Mode
    停止模式: graceful (优雅停止，默认), force (强制停止), status (查看状态)

.EXAMPLE
    .\stop.ps1
    优雅停止应用

.EXAMPLE
    .\stop.ps1 -Mode force
    强制停止应用

.EXAMPLE
    .\stop.ps1 status
    查看应用状态

.NOTES
    作者: Game Platform Manager
    创建日期: 2026-03-23
#>

param(
    [Parameter(Position = 0)]
    [ValidateSet("graceful", "force", "status", "help")]
    [string]$Mode = "graceful"
)

# ============================================
# 配置区域
# ============================================

# 应用名称
$script:APP_NAME = "game-platform-manager"

# Script directory (hardcoded fallback for remote session compatibility)
$script:SCRIPT_DIR = $PSScriptRoot
if (-not $script:SCRIPT_DIR -and $MyInvocation.MyCommand.Path) { $script:SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $script:SCRIPT_DIR) { $script:SCRIPT_DIR = "d:\program\ai\game_platform_manger\backend\scripts" }

# 项目根目录
$script:PROJECT_DIR = Split-Path -Parent $SCRIPT_DIR

# PID文件路径
$script:PID_DIR = Join-Path $PROJECT_DIR "logs"
$script:PID_FILE = Join-Path $PID_DIR "$APP_NAME.pid"

# 日志目录
$script:LOG_DIR = Join-Path $PROJECT_DIR "logs"
$script:LOG_FILE = Join-Path $LOG_DIR "startup.log"

# 服务端口
$script:SERVER_PORT = 8080

# 停止等待超时时间(秒)
$script:STOP_TIMEOUT = 30

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

function Test-AppRunning {
    if (Test-Path $PID_FILE) {
        $pid = Get-Content $PID_FILE -ErrorAction SilentlyContinue
        if ($pid) {
            $process = Get-Process -Id $pid -ErrorAction SilentlyContinue
            if ($process) {
                return $true
            }
        }
    }
    return $false
}

function Get-AppPid {
    if (Test-Path $PID_FILE) {
        return Get-Content $PID_FILE -ErrorAction SilentlyContinue
    }
    return $null
}

function Find-JavaProcess {
    # 通过端口查找
    $conn = Get-NetTCPConnection -LocalPort $SERVER_PORT -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        return $conn.OwningProcess
    }
    
    # 通过进程名查找
    $processes = Get-Process -Name "java" -ErrorAction SilentlyContinue | 
                 Where-Object { $_.MainWindowTitle -like "*game-platform-manager*" }
    if ($processes) {
        return $processes.Id
    }
    
    # 通过命令行查找
    $wmiProcesses = Get-WmiObject Win32_Process -ErrorAction SilentlyContinue | 
                    Where-Object { $_.CommandLine -like "*GamePlatformApplication*" }
    if ($wmiProcesses) {
        return $wmiProcesses.ProcessId
    }
    
    return $null
}

function Wait-ForStop {
    param(
        [Parameter(Mandatory = $true)]
        [int]$ProcessId
    )
    
    Write-LogInfo "等待进程停止 (PID: $ProcessId, 超时: $STOP_TIMEOUT 秒)..."
    
    $startTime = Get-Date
    $endTime = $startTime.AddSeconds($STOP_TIMEOUT)
    
    while ((Get-Date) -lt $endTime) {
        $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
        if (-not $process) {
            Write-LogInfo "进程已停止"
            return $true
        }
        Start-Sleep -Seconds 1
    }
    
    Write-LogWarn "进程停止超时"
    return $false
}

function Remove-PidFile {
    if (Test-Path $PID_FILE) {
        Remove-Item $PID_FILE -Force
        Write-LogInfo "已清理PID文件: $PID_FILE"
    }
}

# ============================================
# 停止函数
# ============================================

function Stop-Graceful {
    Write-LogInfo "========== 开始停止应用 (优雅停止) =========="
    
    # 确保日志目录存在
    if (-not (Test-Path $LOG_DIR)) {
        New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null
    }
    
    if (-not (Test-AppRunning)) {
        Write-LogWarn "应用未在运行"
        
        # 尝试查找残留进程
        $orphanPid = Find-JavaProcess
        if ($orphanPid) {
            Write-LogWarn "发现残留进程，PID: $orphanPid"
            $confirm = Read-Host "是否停止该进程? [y/N]"
            if ($confirm -eq "y" -or $confirm -eq "Y") {
                Stop-Process -Id $orphanPid -Force -ErrorAction SilentlyContinue
                Write-LogInfo "已发送停止信号"
            }
        }
        
        Remove-PidFile
        return
    }
    
    $pid = Get-AppPid
    Write-LogInfo "应用正在运行，PID: $pid"
    
    # 发送停止信号
    Write-LogInfo "发送停止信号..."
    Stop-Process -Id $pid -ErrorAction SilentlyContinue
    
    # 等待进程停止
    if (Wait-ForStop -ProcessId $pid) {
        Remove-PidFile
        Write-LogInfo "========== 应用已停止 =========="
    }
    else {
        Write-LogWarn "优雅停止超时，请使用强制停止: .\stop.ps1 force"
        exit 1
    }
}

function Stop-Force {
    Write-LogInfo "========== 开始停止应用 (强制停止) =========="
    
    # 确保日志目录存在
    if (-not (Test-Path $LOG_DIR)) {
        New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null
    }
    
    $pid = $null
    
    if (Test-AppRunning) {
        $pid = Get-AppPid
    }
    else {
        $pid = Find-JavaProcess
    }
    
    if (-not $pid) {
        Write-LogWarn "未找到运行中的应用进程"
        Remove-PidFile
        return
    }
    
    Write-LogInfo "应用进程PID: $pid"
    
    # 先尝试优雅停止
    Write-LogInfo "发送停止信号..."
    Stop-Process -Id $pid -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    
    # 检查是否停止
    $process = Get-Process -Id $pid -ErrorAction SilentlyContinue
    if (-not $process) {
        Remove-PidFile
        Write-LogInfo "========== 应用已停止 =========="
        return
    }
    
    # 强制停止
    Write-LogWarn "进程未响应，强制停止..."
    Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
    
    # 最终检查
    $process = Get-Process -Id $pid -ErrorAction SilentlyContinue
    if (-not $process) {
        Remove-PidFile
        Write-LogInfo "========== 应用已强制停止 =========="
    }
    else {
        Write-LogError "无法停止进程，请手动处理"
        exit 1
    }
}

function Show-Status {
    Write-Host "========== 应用状态 =========="
    Write-Host ""
    
    if (Test-AppRunning) {
        $pid = Get-AppPid
        Write-Host "状态: 运行中"
        Write-Host "PID: $pid"
        Write-Host "PID文件: $PID_FILE"
        Write-Host ""
        
        # 显示进程信息
        Write-Host "进程信息:"
        $process = Get-Process -Id $pid -ErrorAction SilentlyContinue
        if ($process) {
            $process | Format-Table -Property Id, ProcessName, CPU, WorkingSet, StartTime -AutoSize
        }
        Write-Host ""
        
        # 检查端口
        Write-Host "端口监听:"
        $conn = Get-NetTCPConnection -LocalPort $SERVER_PORT -State Listen -ErrorAction SilentlyContinue
        if ($conn) {
            $conn | Format-Table -Property LocalAddress, LocalPort, State, OwningProcess -AutoSize
        }
        else {
            Write-Host "端口 $SERVER_PORT 未监听"
        }
    }
    else {
        Write-Host "状态: 未运行"
        
        # 检查是否有残留进程
        $orphanPid = Find-JavaProcess
        if ($orphanPid) {
            Write-Host ""
            Write-Host "警告: 发现可能的残留进程，PID: $orphanPid"
        }
    }
    
    Write-Host ""
    Write-Host "=============================="
}

function Show-Help {
    $helpText = @"

用法: .\$($MyInvocation.MyCommand.Name) [选项]

选项:
  (无参数)    优雅停止
  force       强制停止
  status      查看应用状态
  help        显示此帮助信息

示例:
  .\$($MyInvocation.MyCommand.Name)          # 优雅停止
  .\$($MyInvocation.MyCommand.Name) force    # 强制停止
  .\$($MyInvocation.MyCommand.Name) status   # 查看状态

"@
    Write-Host $helpText
}

# ============================================
# 主程序
# ============================================

switch ($Mode) {
    "graceful" {
        Stop-Graceful
    }
    "force" {
        Stop-Force
    }
    "status" {
        Show-Status
    }
    "help" {
        Show-Help
    }
    default {
        Write-LogError "未知参数: $Mode"
        Show-Help
        exit 1
    }
}
