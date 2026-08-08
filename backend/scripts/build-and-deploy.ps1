# 后端编译 + 插件打包部署 + 启动脚本
#
# 用途：当 plugin-l4d2-core 代码或后端代码变更后，一键编译、打包插件 JAR、部署并启动
#
# 用法：
#   .\build-and-deploy.ps1                    # 全量：编译后端 + 构建前端 + 打包插件 + 部署 + 启动
#   .\build-and-deploy.ps1 -SkipBackend       # 跳过后端编译（仅改了插件代码）
#   .\build-and-deploy.ps1 -SkipFrontend      # 跳过前端构建（仅改了后端代码）
#   .\build-and-deploy.ps1 -SkipBackend -SkipFrontend  # 仅打包插件 JAR + 部署 + 启动
#
# 常见场景：
#   1. 改了插件 Java + 前端 → 直接运行（默认全量）
#   2. 只改了插件 Java → -SkipFrontend
#   3. 只改了插件前端 → -SkipBackend -SkipFrontend（需手动先构建前端再运行此脚本打包 JAR）
#   4. 只改了后端 core → -SkipFrontend（跳过前端，但仍打包插件）

param(
    [switch]$SkipBackend,
    [switch]$SkipFrontend,
    [switch]$SkipPlugins
)

# === 路径常量 ===
$SCRIPT_DIR = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $SCRIPT_DIR) { $SCRIPT_DIR = "D:\program\ai\game_platform_manger\backend\scripts" }
$BACKEND_DIR = Split-Path -Parent $SCRIPT_DIR
$PROJECT_DIR = Split-Path -Parent $BACKEND_DIR
$PLUGIN_L4D2_DIR = Join-Path $BACKEND_DIR "plugin-l4d2"
$PLUGIN_FRONTEND_DIR = Join-Path $PLUGIN_L4D2_DIR "frontend"
$PLUGIN_CORE_DIR = Join-Path $PLUGIN_L4D2_DIR "plugin-l4d2-core"
$PLUGINS_DIR = Join-Path $BACKEND_DIR "plugins"
$PLUGIN_JAR = Join-Path $PLUGIN_CORE_DIR "target\plugin-l4d2-core-1.0.0.jar"
$PLUGIN_DEST_JAR = Join-Path $PLUGINS_DIR "plugin-l4d2-core-1.0.0.jar"

# === 日志函数 ===
function Write-LogInfo($Msg) { Write-Host "[INFO] $Msg" -ForegroundColor Gray }
function Write-LogError($Msg) { Write-Host "[ERROR] $Msg" -ForegroundColor Red }
function Write-LogSuccess($Msg) { Write-Host "[SUCCESS] $Msg" -ForegroundColor Green }
function Write-LogWarn($Msg) { Write-Host "[WARN] $Msg" -ForegroundColor Yellow }

# === Step 1: 编译后端核心模块 ===
function Build-Backend {
    if ($script:SkipBackend) {
        Write-LogWarn "跳过后端编译"
        return
    }

    Write-LogInfo "========== [1/4] 编译后端核心模块 =========="
    Set-Location $BACKEND_DIR
    Write-LogInfo "执行 mvn install -pl api,plugin,core -am -DskipTests -q ..."
    & mvn install -pl api,plugin,core -am -DskipTests -q 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-LogError "后端编译失败"
        exit 1
    }
    Write-LogSuccess "后端编译成功"

    # 生成 classpath
    Write-LogInfo "生成 classpath..."
    & mvn -pl core dependency:build-classpath "-Dmdep.outputFile=target/cp.txt" -q 2>&1 | Out-Null
    $cpFile = Join-Path $BACKEND_DIR "core\target\cp.txt"
    if (-not (Test-Path $cpFile)) {
        Write-LogError "生成 classpath 失败"
        exit 1
    }
    Write-LogSuccess "Classpath 已生成"
}

# === Step 2: 构建插件前端 ===
function Build-PluginFrontend {
    if ($script:SkipFrontend) {
        Write-LogWarn "跳过插件前端构建"
        return
    }

    Write-LogInfo "========== [2/4] 构建插件前端 =========="
    if (-not (Test-Path $PLUGIN_FRONTEND_DIR)) {
        Write-LogWarn "插件前端目录不存在: $PLUGIN_FRONTEND_DIR，跳过"
        return
    }

    Set-Location $PLUGIN_FRONTEND_DIR
    Write-LogInfo "执行 npm run build ..."
    # Windows 下 npm 实际是 npm.cmd，PowerShell 直接 & npm 可能被截断为 pm。
    # 使用 cmd /c 调用 npm，规避 PowerShell 对 .cmd 的解析问题。
    $buildOutput = cmd /c "npm run build 2>&1"
    $buildExitCode = $LASTEXITCODE
    $buildOutput | Select-Object -Last 10
    if ($buildExitCode -ne 0) {
        Write-LogError "插件前端构建失败 (exit code: $buildExitCode)"
        exit 1
    }
    Write-LogSuccess "插件前端构建成功（输出到 plugin-l4d2-core/src/main/resources/ui/）"
}

# === Step 3: 打包插件 JAR 并部署 ===
function Build-And-Deploy-Plugin {
    if ($script:SkipPlugins) {
        Write-LogWarn "跳过插件打包部署"
        return
    }

    Write-LogInfo "========== [3/4] 打包插件 JAR =========="
    Set-Location $BACKEND_DIR
    Write-LogInfo "执行 mvn package -pl plugin-l4d2/plugin-l4d2-core -am -DskipTests -q ..."
    & mvn package -pl plugin-l4d2/plugin-l4d2-core -am -DskipTests -q 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-LogError "插件打包失败"
        exit 1
    }

    if (-not (Test-Path $PLUGIN_JAR)) {
        Write-LogError "插件 JAR 未生成: $PLUGIN_JAR"
        exit 1
    }

    $jarInfo = Get-Item $PLUGIN_JAR
    Write-LogSuccess "插件 JAR 已生成: $($jarInfo.Name), $([math]::Round($jarInfo.Length / 1MB, 2)) MB"

    # 部署到 plugins 目录
    if (-not (Test-Path $PLUGINS_DIR)) {
        New-Item -ItemType Directory -Path $PLUGINS_DIR -Force | Out-Null
    }

    # 停止旧后端进程，避免 JAR 文件被锁定无法替换
    Stop-RunningBackend

    # 清理旧 JAR
    Get-ChildItem $PLUGINS_DIR -Filter "plugin-l4d2-core*.jar" -ErrorAction SilentlyContinue |
        ForEach-Object { Remove-Item $_.FullName -Force }

    Copy-Item -Path $PLUGIN_JAR -Destination $PLUGIN_DEST_JAR -Force
    Write-LogSuccess "插件 JAR 已部署: $PLUGIN_DEST_JAR"
}

# === 停止运行中的后端进程 ===
function Stop-RunningBackend {
    Write-LogInfo "检查并停止运行中的后端进程..."
    $stopped = $false

    # 通过 PID 文件查找
    $pidFile = Join-Path $BACKEND_DIR "logs\game-platform-manager.pid"
    if (Test-Path $pidFile) {
        $oldPid = Get-Content $pidFile -ErrorAction SilentlyContinue
        if ($oldPid) {
            $proc = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
            if ($proc) {
                Write-LogInfo "停止后端进程 (PID: $oldPid)..."
                Stop-Process -Id $oldPid -Force -ErrorAction SilentlyContinue
                Start-Sleep -Seconds 2
                $stopped = $true
            }
        }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }

    # 通过端口 8080 查找残留进程
    $conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        foreach ($c in $conn) {
            $pidFromPort = $c.OwningProcess
            if ($pidFromPort -and $pidFromPort -ne 0) {
                Write-LogInfo "停止占用 8080 端口的进程 (PID: $pidFromPort)..."
                Stop-Process -Id $pidFromPort -Force -ErrorAction SilentlyContinue
                $stopped = $true
            }
        }
        Start-Sleep -Seconds 2
    }

    if ($stopped) {
        Write-LogSuccess "后端进程已停止"
    } else {
        Write-LogInfo "未发现运行中的后端进程"
    }
}

# === Step 4: 停止旧进程并启动后端 ===
function Start-Backend {
    Write-LogInfo "========== [4/4] 启动后端 =========="
    Set-Location $BACKEND_DIR

    # 调用现有 rebuild-restart.ps1 跳过编译和插件打包
    $rebuildScript = Join-Path $SCRIPT_DIR "rebuild-restart.ps1"
    if (Test-Path $rebuildScript) {
        & $rebuildScript -SkipCompile -SkipPlugins
    } else {
        Write-LogError "未找到 rebuild-restart.ps1: $rebuildScript"
        exit 1
    }
}

# === 主流程 ===
Write-LogInfo "############################################"
Write-LogInfo "#  Game Platform - 编译部署启动脚本        #"
Write-LogInfo "############################################"
Write-LogInfo "项目目录: $PROJECT_DIR"
Write-LogInfo "后端目录: $BACKEND_DIR"
Write-LogInfo "跳过后端: $SkipBackend"
Write-LogInfo "跳过前端: $SkipFrontend"
Write-LogInfo "跳过插件: $SkipPlugins"
Write-LogInfo ""

Build-Backend
Build-PluginFrontend
Build-And-Deploy-Plugin
Start-Backend
