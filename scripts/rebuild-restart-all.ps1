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
    前端端口（默认 3000）；传 0 自动选择空闲端口

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
        [AllowEmptyString()]
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
$backendOk = $false
try {
    & $backendScript @backendArgs
    $backendExitCode = $LASTEXITCODE
} catch {
    Write-LogError "后端脚本执行失败: $($_.Exception.Message)"
    $backendExitCode = 1
}

# 后台启动器的退出码不可靠；以 PID 文件、8080 监听和登录接口共同确认。
$backendPidFile = Join-Path $BACKEND_DIR "logs\game-platform-manager.pid"
for ($i = 0; $i -lt 10; $i++) {
    $savedPid = 0
    $pidText = if (Test-Path $backendPidFile) {
        (Get-Content $backendPidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    } else {
        $null
    }
    $listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener -and [int]::TryParse($pidText, [ref]$savedPid) -and
        $savedPid -eq [int]$listener.OwningProcess) {
        try {
            $loginBody = @{username='admin';password='admin123'} | ConvertTo-Json
            $login = Invoke-RestMethod -Uri 'http://localhost:8080/api/auth/login' `
                -Method Post -ContentType 'application/json' -Body $loginBody -TimeoutSec 5
            if ($login.code -eq 200) {
                $backendOk = $true
                break
            }
        } catch {
            # 应用可能仍在完成登录接口初始化。
        }
    }
    Start-Sleep -Seconds 1
}

if (-not $backendOk) {
    Write-LogError "后端重启失败 (exit code: $backendExitCode, PID 文件与 8080 监听未通过健康检查)"
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
        $expectedFrontendPort = $FrontendPort
        if ($expectedFrontendPort -eq 0) {
            $frontendPortFile = Join-Path $PROJECT_DIR "logs\frontend.port"
            if (Test-Path $frontendPortFile) {
                $savedFrontendPort = Get-Content $frontendPortFile -ErrorAction SilentlyContinue
                if ($savedFrontendPort -match '^\d+$') {
                    $expectedFrontendPort = [int]$savedFrontendPort
                }
            }
        }
        for ($i = 0; $i -lt 10; $i++) {
            Start-Sleep -Seconds 1
            $listening = netstat -ano | Select-String ":$expectedFrontendPort\s+.*LISTENING"
            if ($listening) { $frontendOk = $true; break }
        }
    } else {
        $frontendOk = $false
    }

    if (-not $frontendOk) {
        Write-LogError "前端重启失败 (exit code: $LASTEXITCODE, $expectedFrontendPort 端口未监听)"
        exit 1
    }

    $actualFrontendPort = $FrontendPort
    $frontendPortFile = Join-Path $PROJECT_DIR "logs\frontend.port"
    if (Test-Path $frontendPortFile) {
        $savedFrontendPort = Get-Content $frontendPortFile -ErrorAction SilentlyContinue
        if ($savedFrontendPort -match '^\d+$') {
            $actualFrontendPort = [int]$savedFrontendPort
        }
    }
    Write-LogInfo "前端重启成功 (端口 $actualFrontendPort)"

    # 前端脚本启动后等待端口监听，但启动器可能在端口出现后稍晚退出；
    # 统一回写实际监听 PID，避免根 logs/frontend.pid 与 Vite 进程不一致。
    $frontendPidFile = Join-Path $PROJECT_DIR "logs\frontend.pid"
    for ($i = 0; $i -lt 10; $i++) {
        $listener = Get-NetTCPConnection -LocalPort $actualFrontendPort -State Listen -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($listener) {
            $listener.OwningProcess | Out-File -FilePath $frontendPidFile -Encoding ascii -Force
            break
        }
        Start-Sleep -Seconds 1
    }
} else {
    Write-LogWarn "跳过前端重启"
}

Write-LogInfo ""
Write-LogInfo "###################################################"
Write-LogInfo "#          全栈编译并重启完成                      #"
Write-LogInfo "###################################################"
Write-LogInfo "后端访问: http://localhost:8080"
if (-not $SkipFrontend) {
    $finalFrontendPort = $FrontendPort
    $finalPortFile = Join-Path $PROJECT_DIR "logs\frontend.port"
    if (Test-Path $finalPortFile) {
        $savedFinalPort = Get-Content $finalPortFile -ErrorAction SilentlyContinue
        if ($savedFinalPort -match '^\d+$') {
            $finalFrontendPort = [int]$savedFinalPort
        }
    }
    Write-LogInfo "前端访问: http://localhost:$finalFrontendPort"
}
Write-LogInfo "默认账号: admin / admin123"
