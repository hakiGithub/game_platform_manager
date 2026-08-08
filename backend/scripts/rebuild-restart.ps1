<#
.SYNOPSIS
    Game Platform Manager - 编译并重启后端脚本 (Windows PowerShell)

.DESCRIPTION
    编译后端代码 → 打包插件 JAR → 停止旧进程 → 生成 classpath → 通过 java -cp 启动
    使用 java -cp 方式启动，绕过 spring-boot:run 无法保持运行的问题
    覆盖数据库 URL 到项目目录，绕过 TRAE 沙箱写入限制

.PARAMETER SkipCompile
    跳过编译步骤，仅停止并重启（用于快速重启）

.PARAMETER SkipPlugins
    跳过插件 JAR 打包步骤（用于仅修改后端代码时的快速重启）

.PARAMETER DbPath
    自定义数据库路径（默认：项目目录下 backend/data/game_platform.db）

.EXAMPLE
    .\rebuild-restart.ps1
    编译后端 + 打包插件 + 重启

.EXAMPLE
    .\rebuild-restart.ps1 -SkipCompile
    跳过编译，仅重启后端

.EXAMPLE
    .\rebuild-restart.ps1 -SkipPlugins
    跳过插件打包，仅编译后端并重启

.NOTES
    作者: Game Platform Manager
    创建日期: 2026-07-16
#>

param(
    [switch]$SkipCompile,

    [switch]$SkipPlugins,

    [string]$DbPath = ""
)

# ============================================
# 配置区域
# ============================================

$script:SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $SCRIPT_DIR) { $script:SCRIPT_DIR = $PSScriptRoot }
$script:BACKEND_DIR = Split-Path -Parent $SCRIPT_DIR
$script:PROJECT_DIR = Split-Path -Parent $BACKEND_DIR

$script:SERVER_PORT = 8080
$script:MAIN_CLASS = "com.gameplatform.GamePlatformApplication"
$script:APP_NAME = "game-platform-manager"

$script:LOG_DIR = Join-Path $BACKEND_DIR "logs"
$script:APP_LOG_FILE = Join-Path $LOG_DIR "application.log"
$script:APP_ERR_LOG_FILE = Join-Path $LOG_DIR "application.err.log"
$script:STARTUP_LOG_FILE = Join-Path $LOG_DIR "startup.log"
$script:PID_FILE = Join-Path $LOG_DIR "$APP_NAME.pid"

# 插件目录
$script:PLUGINS_DIR = Join-Path $BACKEND_DIR "plugins"
$script:PLUGIN_L4D2_DIR = Join-Path $BACKEND_DIR "plugin-l4d2\plugin-l4d2-core"
$script:PLUGIN_L4D2_JAR = Join-Path $PLUGIN_L4D2_DIR "target\plugin-l4d2-core-1.0.0.jar"

# 数据库路径（默认：项目目录下，绕过 TRAE 沙箱限制）
if ([string]::IsNullOrEmpty($DbPath)) {
    $script:DB_PATH = Join-Path $BACKEND_DIR "data\game_platform.db"
} else {
    $script:DB_PATH = $DbPath
}

# JVM 参数
$script:JVM_OPTS = @(
    "-Xms512m",
    "-Xmx1024m",
    "-XX:MaxMetaspaceSize=256m",
    "-XX:+UseG1GC",
    "-XX:+HeapDumpOnOutOfMemoryError",
    "-XX:HeapDumpPath=$LOG_DIR\heap_dump.hprof",
    "-Dspring.devtools.restart.enabled=false",
    "-Dspring.devtools.livereload.enabled=false",
    "-Dlogging.file.path=$LOG_DIR",
    "-Djava.net.preferIPv4Stack=true"
)

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

# 通过端口查找并停止 Java 进程
function Stop-BackendProcess {
    Write-LogInfo "正在停止旧的后端进程..."

    $stopped = $false

    # 方式 1：通过 PID 文件停止
    if (Test-Path $PID_FILE) {
        $savedPid = Get-Content $PID_FILE -ErrorAction SilentlyContinue
        if ($savedPid) {
            $proc = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
            if ($proc) {
                Write-LogInfo "通过 PID 文件停止进程: $savedPid"
                Stop-Process -Id $savedPid -Force -ErrorAction SilentlyContinue
                Start-Sleep -Seconds 2
                $stopped = $true
            }
        }
        Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue
    }

    # 方式 2：通过端口查找
    $conn = Get-NetTCPConnection -LocalPort $SERVER_PORT -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        $portPid = $conn.OwningProcess
        Write-LogInfo "通过端口 $SERVER_PORT 查找到进程: $portPid"
        Stop-Process -Id $portPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
        $stopped = $true
    }

    # 方式 3：通过命令行查找 GamePlatformApplication 进程
    $wmiProcesses = Get-WmiObject Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*GamePlatformApplication*" }
    if ($wmiProcesses) {
        foreach ($p in $wmiProcesses) {
            Write-LogInfo "通过命令行查找到进程: $($p.ProcessId)"
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
            $stopped = $true
        }
        Start-Sleep -Seconds 2
    }

    if (-not $stopped) {
        Write-LogInfo "未发现运行中的后端进程"
    }

    # 等待端口释放
    $waitCount = 0
    while ($waitCount -lt 10) {
        $stillListening = Get-NetTCPConnection -LocalPort $SERVER_PORT -State Listen -ErrorAction SilentlyContinue
        if (-not $stillListening) { break }
        Start-Sleep -Seconds 1
        $waitCount++
    }
}

# 编译后端
function Compile-Backend {
    Write-LogInfo "========== 开始编译后端 =========="
    Write-LogInfo "后端目录: $BACKEND_DIR"

    Set-Location $BACKEND_DIR

    # 检查 Maven
    $mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvnCmd) {
        Write-LogError "未找到 Maven，请确保已安装并配置 PATH"
        exit 1
    }

    # 编译并安装所有模块到本地仓库（-am 同时构建依赖模块）。
    # 使用 install 而非 compile，确保 api/plugin 等依赖模块的 JAR 在本地仓库中保持最新，
    # 避免 plugin-l4d2-core 打包时引用 stale JAR 导致 NoSuchMethodError。
    Write-LogInfo "执行 mvn clean install -pl core -am -DskipTests -q ..."
    $mvnResult = & mvn clean install -pl core -am -DskipTests -q 2>&1
    $mvnExitCode = $LASTEXITCODE

    if ($mvnExitCode -ne 0) {
        Write-LogError "编译失败 (exit code: $mvnExitCode)"
        Write-LogError "编译输出:"
        $mvnResult | Select-Object -Last 30 | ForEach-Object { Write-Host $_ }
        exit 1
    }

    Write-LogInfo "编译并安装成功"

    # 生成 classpath
    Write-LogInfo "生成 classpath..."
    $cpFile = Join-Path $BACKEND_DIR "core\target\cp.txt"
    & mvn -pl core dependency:build-classpath "-Dmdep.outputFile=target/cp.txt" -q 2>&1 | Out-Null

    if (-not (Test-Path $cpFile)) {
        Write-LogError "生成 classpath 失败"
        exit 1
    }

    Write-LogInfo "Classpath 已生成: $cpFile"
}

# 打包插件 JAR 并部署到 plugins 目录
function Build-Plugins {
    Write-LogInfo "========== 开始打包插件 JAR =========="
    Write-LogInfo "插件源码目录: $PLUGIN_L4D2_DIR"

    Set-Location $BACKEND_DIR

    # 检查插件源码目录
    if (-not (Test-Path $PLUGIN_L4D2_DIR)) {
        Write-LogWarn "插件源码目录不存在: $PLUGIN_L4D2_DIR，跳过插件打包"
        return
    }

    # 检查 Maven
    $mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvnCmd) {
        Write-LogError "未找到 Maven，跳过插件打包"
        return
    }

    # 打包 plugin-l4d2-core（跳过测试以加速）
    Write-LogInfo "执行 mvn clean package -pl plugin-l4d2/plugin-l4d2-core -DskipTests -q ..."
    $mvnResult = & mvn clean package -pl plugin-l4d2/plugin-l4d2-core -DskipTests -q 2>&1
    $mvnExitCode = $LASTEXITCODE

    if ($mvnExitCode -ne 0) {
        Write-LogError "插件打包失败 (exit code: $mvnExitCode)"
        Write-LogError "打包输出:"
        $mvnResult | Select-Object -Last 30 | ForEach-Object { Write-Host $_ }
        exit 1
    }

    # 检查 JAR 是否生成
    if (-not (Test-Path $PLUGIN_L4D2_JAR)) {
        Write-LogError "插件 JAR 未生成: $PLUGIN_L4D2_JAR"
        exit 1
    }

    $jarInfo = Get-Item $PLUGIN_L4D2_JAR
    Write-LogInfo "插件 JAR 已生成: $($jarInfo.Name), 大小: $([math]::Round($jarInfo.Length / 1MB, 2)) MB"

    # 确保 plugins 目录存在
    if (-not (Test-Path $PLUGINS_DIR)) {
        New-Item -ItemType Directory -Path $PLUGINS_DIR -Force | Out-Null
        Write-LogInfo "创建 plugins 目录: $PLUGINS_DIR"
    }

    # 清理旧的同名 JAR（避免残留）
    Get-ChildItem $PLUGINS_DIR -Filter "plugin-l4d2-core*.jar" -ErrorAction SilentlyContinue |
        ForEach-Object {
            Write-LogInfo "清理旧插件 JAR: $($_.Name)"
            Remove-Item $_.FullName -Force
        }

    # 复制新 JAR 到 plugins 目录
    $destJar = Join-Path $PLUGINS_DIR "plugin-l4d2-core-1.0.0.jar"
    Copy-Item -Path $PLUGIN_L4D2_JAR -Destination $destJar -Force
    Write-LogInfo "插件 JAR 已部署: $destJar"
    Write-LogInfo "========== 插件打包完成 =========="
}

# 启动后端
function Start-Backend {
    Write-LogInfo "========== 开始启动后端 =========="

    Set-Location $BACKEND_DIR

    # 读取 classpath
    $cpFile = Join-Path $BACKEND_DIR "core\target\cp.txt"
    if (-not (Test-Path $cpFile)) {
        Write-LogError "Classpath 文件不存在: $cpFile"
        Write-LogError "请先执行编译（去掉 -SkipCompile 参数）"
        exit 1
    }

    $depCp = (Get-Content $cpFile -Raw).Trim()
    $cp = "core/target/classes;api/target/classes;plugin/target/classes;$depCp"

    # 确保数据库目录存在
    $dbDir = Split-Path -Parent $script:DB_PATH
    if (-not (Test-Path $dbDir)) {
        New-Item -ItemType Directory -Path $dbDir -Force | Out-Null
        Write-LogInfo "创建数据库目录: $dbDir"
    }

    # 构造数据库 URL（使用正斜杠，SQLite 兼容）
    $dbPathNormalized = $script:DB_PATH -replace '\\', '/'
    $dbUrl = "jdbc:sqlite:$dbPathNormalized"

    Write-LogInfo "数据库路径: $dbPathNormalized"
    Write-LogInfo "日志目录: $LOG_DIR"
    Write-LogInfo "应用日志: $APP_LOG_FILE"
    Write-LogInfo "主类: $MAIN_CLASS"

    # 清空旧应用日志，便于本次启动诊断
    if (Test-Path $APP_LOG_FILE) {
        Clear-Content -Path $APP_LOG_FILE -ErrorAction SilentlyContinue
    }

    # 解析 java 完整路径
    $javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
    if (-not $javaExe) { $javaExe = "java" }

    # 将 JVM 参数写入 @argfile（Java 9+ 支持），规避 cmd.exe 的 8191 字符命令行长度限制
    # （classpath 较长）。每行一个参数；路径均为 ASCII，使用 ASCII 编码避免 BOM 干扰。
    $argFile = Join-Path $env:TEMP "game-platform-jvm-args.txt"
    $argLines = @()
    foreach ($opt in $JVM_OPTS) { $argLines += $opt }
    $argLines += "-Dspring.datasource.url=$dbUrl"
    $argLines += "-cp"
    $argLines += $cp
    $argLines += $MAIN_CLASS
    $argLines += "--server.port=$SERVER_PORT"
    Set-Content -Path $argFile -Value ($argLines -join "`n") -Encoding ASCII

    # 生成启动脚本 start-backend.bat，由 cmd 原生解析带空格的可执行文件路径与 > 重定向。
    # 说明：本机 PowerShell 5.1 的 Start-Process -RedirectStandardOutput/Error 会触发内部
    # 字典冲突错误（"Path/path"）；而 cmd /c "java ..." 在可执行文件路径含空格时引号解析会错乱。
    # 改为写成 .bat 文件、再用 Start-Process 启动该 .bat——所有引号/重定向交给 cmd 原生处理，
    # 输出可靠落盘，且进程独立存活（脚本退出后不会被连带终止）。
    $batFile = Join-Path $env:TEMP "start-backend.bat"
    $batContent = "@echo off`r`n"
    $batContent += "`"$javaExe`" @`"$argFile`" > `"$APP_LOG_FILE`" 2> `"$APP_ERR_LOG_FILE`"`r`n"
    Set-Content -Path $batFile -Value $batContent -Encoding ASCII

    Write-LogInfo "正在启动 Java 进程 (start-backend.bat)..."
    Write-LogInfo "参数文件: $argFile"
    Write-LogInfo "启动脚本: $batFile"
    $process = Start-Process -FilePath $batFile `
        -WorkingDirectory $BACKEND_DIR `
        -WindowStyle Hidden `
        -PassThru

    $processId = $process.Id
    $processId | Out-File -FilePath $PID_FILE -Encoding utf8
    Write-LogInfo "Java 进程已启动，PID: $processId"

    # 等待启动完成（最多 120 秒，给首次冷启动留出余量）
    Write-LogInfo "等待应用启动 (最多 120 秒)..."
    $startTime = Get-Date
    $timeout = 120
    $started = $false

    while (((Get-Date) - $startTime).TotalSeconds -lt $timeout) {
        # 检查进程是否还在
        $procExists = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if (-not $procExists) {
            Write-LogError "进程已退出，启动失败"
            Write-LogError "最近日志（最后 30 行）:"
            if (Test-Path $APP_LOG_FILE) {
                Get-Content $APP_LOG_FILE -Tail 30 | ForEach-Object { Write-Host $_ }
            }
            if (Test-Path $APP_ERR_LOG_FILE) {
                Get-Content $APP_ERR_LOG_FILE -Tail 30 | ForEach-Object { Write-Host $_ }
            }
            Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue
            exit 1
        }

        # 检查端口是否监听
        $listening = Get-NetTCPConnection -LocalPort $SERVER_PORT -State Listen -ErrorAction SilentlyContinue
        if ($listening) {
            # 端口已监听，再等 3 秒确保完全就绪
            Start-Sleep -Seconds 3

            # 健康检查（尝试登录接口）
            try {
                $testBody = @{username='admin';password='admin123'} | ConvertTo-Json
                $resp = Invoke-RestMethod -Uri "http://localhost:$SERVER_PORT/api/auth/login" `
                    -Method Post -ContentType 'application/json' -Body $testBody -TimeoutSec 5
                if ($resp.code -eq 200) {
                    $started = $true
                    break
                }
            } catch {
                # 登录接口可能还未就绪，继续等待
            }
        }

        Start-Sleep -Seconds 2
    }

    if ($started) {
        Write-LogInfo "========== 后端启动成功 =========="
        Write-LogInfo "访问地址: http://localhost:$SERVER_PORT"
        Write-LogInfo "API 文档: http://localhost:$SERVER_PORT/swagger-ui.html"
        Write-LogInfo "应用日志: $APP_LOG_FILE"
        Write-LogInfo "PID 文件: $PID_FILE"
    } else {
        # 超时但未退出：进程可能仍在启动（如首次冷启动较慢），此时不应误杀，
        # 改为警告并保留进程，交由调用方稍后通过健康检查确认就绪。
        $procExists = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($procExists) {
            Write-LogWarn "========== 后端未在 120 秒内通过登录健康检查，但进程仍在运行 (PID: $processId) =========="
            Write-LogWarn "应用日志: $APP_LOG_FILE"
            Write-LogWarn "请稍后用 curl http://localhost:$SERVER_PORT/api/system/info 确认就绪"
        } else {
            Write-LogError "========== 后端启动超时且进程已退出 =========="
            Write-LogError "请检查日志: $APP_LOG_FILE"
            Write-LogError "最近日志（最后 30 行）:"
            if (Test-Path $APP_LOG_FILE) {
                Get-Content $APP_LOG_FILE -Tail 30 | ForEach-Object { Write-Host $_ }
            }
            if (Test-Path $APP_ERR_LOG_FILE) {
                Get-Content $APP_ERR_LOG_FILE -Tail 30 | ForEach-Object { Write-Host $_ }
            }
            Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue
            exit 1
        }
    }
}

# ============================================
# 主程序
# ============================================

# 确保日志目录存在
if (-not (Test-Path $LOG_DIR)) {
    New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null
}

Write-LogInfo "############################################"
Write-LogInfo "#   Game Platform Manager - 后端编译并重启  #"
Write-LogInfo "############################################"
Write-LogInfo "跳过编译: $($SkipCompile.IsPresent)"
Write-LogInfo "跳过插件打包: $($SkipPlugins.IsPresent)"
Write-LogInfo "后端目录: $BACKEND_DIR"
Write-LogInfo "数据库路径: $script:DB_PATH"

# 1. 停止旧进程
Stop-BackendProcess

# 2. 编译（除非跳过）
if (-not $SkipCompile) {
    Compile-Backend
} else {
    Write-LogWarn "跳过编译步骤"
}

# 3. 打包插件 JAR（除非跳过）
if (-not $SkipPlugins) {
    Build-Plugins
} else {
    Write-LogWarn "跳过插件打包步骤"
}

# 4. 启动
Start-Backend

Write-LogInfo "############################################"
Write-LogInfo "#          后端编译并重启完成              #"
Write-LogInfo "############################################"
