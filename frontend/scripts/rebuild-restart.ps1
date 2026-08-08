<#
.SYNOPSIS
    Game Platform Manager - 编译并重启前端脚本 (Windows PowerShell)

.DESCRIPTION
    停止旧的前端进程 → 通过 npm run dev 启动 Vite 开发服务器
    前端无需编译步骤（Vite 热重载），本脚本主要用于快速重启

.PARAMETER Port
    指定端口（默认 3000，若被占用 Vite 会自动切换）

.EXAMPLE
    .\rebuild-restart.ps1
    重启前端（默认端口）

.EXAMPLE
    .\rebuild-restart.ps1 -Port 3001
    重启前端并指定端口

.NOTES
    作者: Game Platform Manager
    创建日期: 2026-07-16
#>

param(
    [int]$Port = 3000
)

# ============================================
# 配置区域
# ============================================

$script:SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $SCRIPT_DIR) { $script:SCRIPT_DIR = $PSScriptRoot }
$script:FRONTEND_DIR = Split-Path -Parent $SCRIPT_DIR
$script:PROJECT_DIR = Split-Path -Parent $script:FRONTEND_DIR

$script:LOG_DIR = Join-Path $script:FRONTEND_DIR "logs"
$script:APP_LOG_FILE = Join-Path $script:LOG_DIR "frontend.log"
$script:STARTUP_LOG_FILE = Join-Path $script:LOG_DIR "startup.log"
$script:PID_FILE = Join-Path $script:LOG_DIR "frontend.pid"

$script:POSSIBLE_PORTS = @($Port, 3001, 3002)

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
    if (Test-Path $script:LOG_DIR) {
        Add-Content -Path $script:STARTUP_LOG_FILE -Value $logMessage
    }
}

function Write-LogInfo { param([string]$Message) Write-Log -Level "INFO" -Message $Message }
function Write-LogWarn { param([string]$Message) Write-Log -Level "WARN" -Message $Message }
function Write-LogError { param([string]$Message) Write-Log -Level "ERROR" -Message $Message }

# 停止旧的前端进程
function Stop-FrontendProcess {
    Write-LogInfo "正在停止旧的前端进程..."

    $stopped = $false

    # 方式 1：通过 PID 文件停止
    if (Test-Path $script:PID_FILE) {
        $savedPid = Get-Content $script:PID_FILE -ErrorAction SilentlyContinue
        if ($savedPid) {
            $proc = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
            if ($proc) {
                Write-LogInfo "通过 PID 文件停止进程: $savedPid"
                Stop-Process -Id $savedPid -Force -ErrorAction SilentlyContinue
                Start-Sleep -Seconds 2
                $stopped = $true
            }
        }
        Remove-Item $script:PID_FILE -Force -ErrorAction SilentlyContinue
    }

    # 方式 2：通过端口查找（3000/3001/3002）
    foreach ($p in $script:POSSIBLE_PORTS) {
        $conn = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
        if ($conn) {
            $portPid = $conn.OwningProcess
            Write-LogInfo "通过端口 $p 查找到进程: $portPid"
            Stop-Process -Id $portPid -Force -ErrorAction SilentlyContinue
            $stopped = $true
        }
    }

    if ($stopped) {
        Start-Sleep -Seconds 2
    }

    # 方式 3：查找 vite/node 进程（命令行包含 vite）
    $wmiProcesses = Get-WmiObject Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*vite*" -and $_.CommandLine -like "*$script:FRONTEND_DIR*" }
    if ($wmiProcesses) {
        foreach ($p in $wmiProcesses) {
            Write-LogInfo "通过命令行查找到 Vite 进程: $($p.ProcessId)"
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
            $stopped = $true
        }
        Start-Sleep -Seconds 2
    }

    if (-not $stopped) {
        Write-LogInfo "未发现运行中的前端进程"
    }
}

# 启动前端
function Start-Frontend {
    Write-LogInfo "========== 开始启动前端 =========="

    Set-Location $script:FRONTEND_DIR

    # 检查 node_modules
    $nodeModules = Join-Path $script:FRONTEND_DIR "node_modules"
    if (-not (Test-Path $nodeModules)) {
        Write-LogInfo "node_modules 不存在，执行 npm install..."
        & npm install
        if ($LASTEXITCODE -ne 0) {
            Write-LogError "npm install 失败"
            exit 1
        }
    }

    Write-LogInfo "前端目录: $script:FRONTEND_DIR"
    Write-LogInfo "应用日志: $script:APP_LOG_FILE"
    Write-LogInfo "指定端口: $Port"

    # 启动 Vite（直接调用 node 运行 vite.js，避免 cmd.exe/npm 中间层导致进程链被销毁）
    Write-LogInfo "正在启动 Vite 开发服务器..."

    # 优先使用项目本地的 vite（node_modules/.bin/vite），避免全局依赖
    $viteJs = Join-Path $script:FRONTEND_DIR "node_modules\vite\bin\vite.js"
    if (-not (Test-Path $viteJs)) {
        Write-LogError "未找到 vite: $viteJs"
        Write-LogError "请先执行 npm install"
        exit 1
    }

    # 查找 node 可执行文件
    $nodeExe = (Get-Command node -ErrorAction SilentlyContinue).Source
    if (-not $nodeExe) {
        Write-LogError "未找到 node 可执行文件"
        exit 1
    }

    # 生成启动脚本 start-frontend.bat，由 cmd 原生处理 > 重定向（规避 PowerShell 5.1
    # Start-Process -RedirectStandard* 的字典冲突 bug，与后端脚本保持一致）。
    # 进程独立存活（脚本退出后不会被连带终止），输出可靠落盘。
    $errLog = Join-Path $script:LOG_DIR "frontend.err.log"
    $batFile = Join-Path $env:TEMP "start-frontend.bat"
    $batContent = "@echo off`r`n"
    $batContent += "`"$nodeExe`" `"$viteJs`" --port $Port --strictPort > `"$script:APP_LOG_FILE`" 2> `"$errLog`"`r`n"
    Set-Content -Path $batFile -Value $batContent -Encoding ASCII

    Write-LogInfo "正在启动 Vite (start-frontend.bat)..."
    Write-LogInfo "启动脚本: $batFile"
    $process = Start-Process -FilePath $batFile `
        -WorkingDirectory $script:FRONTEND_DIR `
        -WindowStyle Hidden `
        -PassThru

    $processId = $process.Id

    # 保存 PID
    $processId | Out-File -FilePath $script:PID_FILE -Encoding utf8
    Write-LogInfo "Vite 进程已启动，PID: $processId"

    # 等待启动完成
    Write-LogInfo "等待前端启动 (最多 30 秒)..."
    $startTime = Get-Date
    $timeout = 30
    $started = $false
    $actualPort = $null

    while (((Get-Date) - $startTime).TotalSeconds -lt $timeout) {
        # 检查进程是否还在
        $procExists = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if (-not $procExists) {
            Write-LogError "Vite 进程已退出，启动失败"
            Write-LogError "最近日志（最后 20 行）:"
            if (Test-Path $script:APP_LOG_FILE) {
                Get-Content $script:APP_LOG_FILE -Tail 20 | ForEach-Object { Write-Host $_ }
            }
            Remove-Item $script:PID_FILE -Force -ErrorAction SilentlyContinue
            exit 1
        }

        # 检查端口监听
        foreach ($p in $script:POSSIBLE_PORTS) {
            $listening = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
            if ($listening) {
                $actualPort = $p
                $started = $true
                break
            }
        }

        if ($started) { break }
        Start-Sleep -Seconds 1
    }

    if ($started) {
        Write-LogInfo "========== 前端启动成功 =========="
        Write-LogInfo "访问地址: http://localhost:$actualPort"
        Write-LogInfo "应用日志: $script:APP_LOG_FILE"
        Write-LogInfo "PID 文件: $script:PID_FILE"
    } else {
        Write-LogError "========== 前端启动超时 =========="
        Write-LogError "请检查日志: $script:APP_LOG_FILE"
        Write-LogError "最近日志（最后 20 行）:"
        if (Test-Path $script:APP_LOG_FILE) {
            Get-Content $script:APP_LOG_FILE -Tail 20 | ForEach-Object { Write-Host $_ }
        }
        exit 1
    }
}

# ============================================
# 主程序
# ============================================

# 确保日志目录存在
if (-not (Test-Path $script:LOG_DIR)) {
    New-Item -ItemType Directory -Path $script:LOG_DIR -Force | Out-Null
}

Write-LogInfo "############################################"
Write-LogInfo "#   Game Platform Manager - 前端重启       #"
Write-LogInfo "############################################"
Write-LogInfo "前端目录: $script:FRONTEND_DIR"
Write-LogInfo "指定端口: $Port"

# 1. 停止旧进程
Stop-FrontendProcess

# 2. 启动
Start-Frontend

Write-LogInfo "############################################"
Write-LogInfo "#          前端重启完成                    #"
Write-LogInfo "############################################"
