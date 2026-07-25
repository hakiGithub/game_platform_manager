<#
.SYNOPSIS
    Game Platform Manager - 全栈编译并重启脚本 (Windows PowerShell)

.DESCRIPTION
    一键编译并重启整个项目：
      1. 编译后端 core 模块 + 生成 classpath
      2. 打包 plugin-l4d2-core JAR 并部署到 backend/plugins/
      3. 停止旧后端进程并启动新后端
      4. 停止旧前端进程并启动新前端 (Vite)
    适用于修改后端 / 插件 / 前端后的快速部署验证。

.PARAMETER SkipBackendCompile
    跳过后端 core 编译（保留插件打包和前端重启）

.PARAMETER SkipPlugins
    跳过插件 JAR 打包

.PARAMETER SkipFrontend
    跳过前端重启

.PARAMETER FrontendPort
    前端端口（默认 3000）

.EXAMPLE
    .\rebuild-restart-all.ps1
    全栈编译并重启（后端 + 插件 + 前端）

.EXAMPLE
    .\rebuild-restart-all.ps1 -SkipPlugins
    跳过插件打包，仅重启后端 + 前端

.EXAMPLE
    .\rebuild-restart-all.ps1 -SkipBackendCompile -SkipFrontend
    仅打包插件并重启后端（适用于只改了插件代码）

.NOTES
    作者: Game Platform Manager
    创建日期: 2026-07-20
#>

param(
    [switch]$SkipBackendCompile,

    [switch]$SkipPlugins,

    [switch]$SkipFrontend,

    [int]$FrontendPort = 3000
)

# ============================================
# 配置区域
# ============================================

$script:SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $SCRIPT_DIR) { $script:SCRIPT_DIR = $PSScriptRoot }
$script:PROJECT_DIR = Split-Path -Parent $SCRIPT_DIR
$script:BACKEND_DIR = Join-Path $PROJECT_DIR "backend"
$script:FRONTEND_DIR = Join-Path $PROJECT_DIR "frontend"

$script:LOG_DIR = Join-Path $PROJECT_DIR "logs"
$script:STARTUP_LOG_FILE = Join-Path $LOG_DIR "startup-all.log"

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
        Add-Content -Path $STARTUP_LOG_FILE -Value $logMessage
    }
}

function Write-LogInfo { param([string]$Message) Write-Log -Level "INFO" -Message $Message }
function Write-LogWarn { param([string]$Message) Write-Log -Level "WARN" -Message $Message }
function Write-LogError { param([string]$Message) Write-Log -Level "ERROR" -Message $Message }

# ============================================
# 主程序
# ============================================

if (-not (Test-Path $LOG_DIR)) {
    New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null
}

Write-LogInfo "###################################################"
Write-LogInfo "#  Game Platform Manager - 全栈编译并重启          #"
Write-LogInfo "###################################################"
Write-LogInfo "项目目录: $PROJECT_DIR"
Write-LogInfo "跳过后端编译: $($SkipBackendCompile.IsPresent)"
Write-LogInfo "跳过插件打包: $($SkipPlugins.IsPresent)"
Write-LogInfo "跳过前端重启: $($SkipFrontend.IsPresent)"
Write-LogInfo "前端端口: $FrontendPort"
Write-LogInfo ""

# 1. 后端编译 + 插件打包 + 重启
Write-LogInfo "========== [1/2] 启动后端脚本 =========="
$backendScript = Join-Path $BACKEND_DIR "scripts\rebuild-restart.ps1"
if (-not (Test-Path $backendScript)) {
    Write-LogError "后端重启脚本不存在: $backendScript"
    exit 1
}

$backendArgs = @{}
if ($SkipBackendCompile) { $backendArgs['SkipCompile'] = $true }
if ($SkipPlugins) { $backendArgs['SkipPlugins'] = $true }

$argDisplay = ($backendArgs.GetEnumerator() | ForEach-Object { "-$($_.Key) $($_.Value)" }) -join ' '
Write-LogInfo "调用: $backendScript $argDisplay"
& $backendScript @backendArgs
# rebuild-restart.ps1 通过 start-backend.bat 启动后台 Java 进程，$LASTEXITCODE 可能是 null，
# 不能依赖它判断后端是否启动成功。改为检查 8080 端口是否监听。
$backendOk = $false
if ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) {
    # 等待最多 10 秒确认 8080 端口监听
    for ($i = 0; $i -lt 10; $i++) {
        Start-Sleep -Seconds 1
        $listening = netstat -ano | Select-String ":8080\s+.*LISTENING"
        if ($listening) { $backendOk = $true; break }
    }
} else {
    $backendOk = $false
}

if (-not $backendOk) {
    Write-LogError "后端重启失败 (exit code: $LASTEXITCODE, 8080 端口未监听)"
    exit 1
}

Write-LogInfo "后端重启成功"
Write-LogInfo ""

# 2. 前端重启
if (-not $SkipFrontend) {
    Write-LogInfo "========== [2/2] 启动前端脚本 =========="
    $frontendScript = Join-Path $FRONTEND_DIR "scripts\rebuild-restart.ps1"
    if (-not (Test-Path $frontendScript)) {
        Write-LogError "前端重启脚本不存在: $frontendScript"
        exit 1
    }

    Write-LogInfo "调用: $frontendScript -Port $FrontendPort"
    & $frontendScript -Port $FrontendPort
    # 前端脚本同样通过 start-frontend.bat 启动后台 Vite 进程，$LASTEXITCODE 可能是 null，
    # 改为检查指定端口是否监听。
    $frontendOk = $false
    if ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) {
        for ($i = 0; $i -lt 10; $i++) {
            Start-Sleep -Seconds 1
            $listening = netstat -ano | Select-String ":$FrontendPort\s+.*LISTENING"
            if ($listening) { $frontendOk = $true; break }
        }
    } else {
        $frontendOk = $false
    }

    if (-not $frontendOk) {
        Write-LogError "前端重启失败 (exit code: $LASTEXITCODE, $FrontendPort 端口未监听)"
        exit 1
    }

    Write-LogInfo "前端重启成功"
} else {
    Write-LogWarn "跳过前端重启"
}

Write-LogInfo ""
Write-LogInfo "###################################################"
Write-LogInfo "#          全栈编译并重启完成                      #"
Write-LogInfo "###################################################"
Write-LogInfo "后端访问: http://localhost:8080"
if (-not $SkipFrontend) {
    Write-LogInfo "前端访问: http://localhost:$FrontendPort"
}
Write-LogInfo "默认账号: admin / admin123"
