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
$script:LAUNCHER_PID_FILE = Join-Path $LOG_DIR "$APP_NAME.launcher.pid"

. (Join-Path $PSScriptRoot "backend-process.ps1")

# 插件目录
$script:PLUGINS_DIR = Join-Path $BACKEND_DIR "plugins"
$script:PLUGIN_L4D2_DIR = Join-Path $BACKEND_DIR "plugin-l4d2\plugin-l4d2-core"
$script:PLUGIN_L4D2_FRONTEND_DIR = Join-Path $BACKEND_DIR "plugin-l4d2\frontend"
$script:PLUGIN_L4D2_UI_DIR = Join-Path $PLUGIN_L4D2_DIR "src\main\resources\ui"
# 插件 JAR 名带版本，随父 POM 版本变化（当前 SNAPSHOT），用通配符动态匹配
$script:PLUGIN_L4D2_JAR_GLOB = Join-Path $PLUGIN_L4D2_DIR "target\plugin-l4d2-core*.jar"

# 数据库路径（默认：项目目录下，绕过 TRAE 沙箱限制）
if ([string]::IsNullOrEmpty($DbPath)) {
    $script:DB_PATH = Join-Path $BACKEND_DIR "data\game_platform.db"
} else {
    $script:DB_PATH = $DbPath
}

# JVM 参数
$script:JVM_OPTS = @(
    "-Xms256m",
    "-Xmx384m",
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
        $savedPidText = (Get-Content $PID_FILE -ErrorAction SilentlyContinue | Select-Object -First 1)
        $savedPid = 0
        if ([int]::TryParse($savedPidText, [ref]$savedPid)) {
            $proc = Get-BackendProcessByPid -ProcessId $savedPid -MainClass $MAIN_CLASS
            if ($proc) {
                Write-LogInfo "通过 PID 文件停止后端 Java 进程: $savedPid"
                Stop-Process -Id $savedPid -Force -ErrorAction SilentlyContinue
                Start-Sleep -Seconds 2
                $stopped = $true
            } elseif (Get-Process -Id $savedPid -ErrorAction SilentlyContinue) {
                Write-LogWarn "PID 文件中的进程 $savedPid 不是当前后端 Java 进程，已忽略"
            }
        } else {
            Write-LogWarn "PID 文件内容无效，已忽略: $savedPidText"
        }
        Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue
    }
    Remove-Item $LAUNCHER_PID_FILE -Force -ErrorAction SilentlyContinue

    # 方式 2：通过端口查找
    $conn = Get-NetTCPConnection -LocalPort $SERVER_PORT -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        foreach ($portPid in ($conn.OwningProcess | Select-Object -Unique)) {
            $proc = Get-BackendProcessByPid -ProcessId $portPid -MainClass $MAIN_CLASS
            if ($proc) {
                Write-LogInfo "通过端口 $SERVER_PORT 停止后端 Java 进程: $portPid"
                Stop-Process -Id $portPid -Force -ErrorAction SilentlyContinue
                $stopped = $true
            } else {
                Write-LogWarn "端口 $SERVER_PORT 由非当前后端进程占用，未停止 PID $portPid"
            }
        }
        if ($stopped) {
            Start-Sleep -Seconds 2
        }
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
        throw "未找到 Maven"
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
        throw "后端编译失败 (exit code: $mvnExitCode)"
    }

    Write-LogInfo "编译并安装成功"

    # 生成 classpath
    Write-LogInfo "生成 classpath..."
    $cpFile = Join-Path $BACKEND_DIR "core\target\cp.txt"
    & mvn -pl core dependency:build-classpath "-Dmdep.outputFile=target/cp.txt" -q 2>&1 | Out-Null

    if (-not (Test-Path $cpFile)) {
        Write-LogError "生成 classpath 失败"
        throw "生成 classpath 失败"
    }

    Write-LogInfo "Classpath 已生成: $cpFile"
}

# 构建插件前端并校验 UI 产物
function Build-PluginFrontend {
    Write-LogInfo "========== 开始构建插件前端 =========="
    Write-LogInfo "插件前端目录: $PLUGIN_L4D2_FRONTEND_DIR"

    if (-not (Test-Path $PLUGIN_L4D2_FRONTEND_DIR)) {
        Write-LogError "插件前端目录不存在: $PLUGIN_L4D2_FRONTEND_DIR"
        throw "插件前端目录不存在"
    }

    $npmCmd = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if (-not $npmCmd) {
        $npmCmd = Get-Command npm -ErrorAction SilentlyContinue
    }
    if (-not $npmCmd) {
        Write-LogError "未找到 npm，无法构建插件前端"
        throw "未找到 npm"
    }

    Set-Location $PLUGIN_L4D2_FRONTEND_DIR

    $nodeModulesDir = Join-Path $PLUGIN_L4D2_FRONTEND_DIR "node_modules"
    $viteDir = Join-Path $nodeModulesDir "vite"
    $vueTscDir = Join-Path $nodeModulesDir "vue-tsc"
    if (-not (Test-Path $nodeModulesDir) -or -not (Test-Path $viteDir) -or -not (Test-Path $vueTscDir)) {
        Write-LogInfo "插件前端依赖不完整，执行 npm ci ..."
        $installOutput = cmd /c "npm ci 2>&1"
        $installExitCode = $LASTEXITCODE
        if ($installExitCode -ne 0) {
            Write-LogError "插件前端依赖安装失败 (exit code: $installExitCode)"
            $installOutput | Select-Object -Last 30 | ForEach-Object { Write-Host $_ }
            throw "插件前端依赖安装失败"
        }
    }

    Write-LogInfo "执行 npm run build ..."
    # Windows 下 npm 实际是 npm.cmd；通过 cmd /c 调用，避免 PowerShell 对 .cmd 的解析问题。
    $buildOutput = cmd /c "npm run build 2>&1"
    $buildExitCode = $LASTEXITCODE
    if ($buildExitCode -ne 0) {
        Write-LogError "插件前端构建失败 (exit code: $buildExitCode)"
        $buildOutput | Select-Object -Last 30 | ForEach-Object { Write-Host $_ }
        throw "插件前端构建失败"
    }

    $uiIndex = Join-Path $PLUGIN_L4D2_UI_DIR "index.html"
    $uiAssets = Get-ChildItem -Path (Join-Path $PLUGIN_L4D2_UI_DIR "assets") -File -ErrorAction SilentlyContinue
    if (-not (Test-Path $uiIndex) -or -not $uiAssets) {
        Write-LogError "插件 UI 产物不完整: index.html=$uiIndex, assets=$($uiAssets.Count)"
        throw "插件 UI 产物不完整"
    }

    Write-LogInfo "插件前端构建成功，UI 产物校验通过: $PLUGIN_L4D2_UI_DIR"
}

# 构建插件前端，打包 JAR 并部署到 plugins 目录
function Build-Plugins {
    Write-LogInfo "========== 开始打包插件 JAR =========="
    Write-LogInfo "插件源码目录: $PLUGIN_L4D2_DIR"

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

    Build-PluginFrontend
    Set-Location $BACKEND_DIR

    # 打包 plugin-l4d2-core（跳过测试以加速）。必须带 -am，确保 plugin SDK 与本模块
    # 一起编译；-SkipCompile 时本地仓库可能还是旧 SDK，否则新增 builder 字段会编译失败。
    Write-LogInfo "执行 mvn clean package -pl plugin-l4d2/plugin-l4d2-core -am -DskipTests -q ..."
    $mvnResult = & mvn clean package -pl plugin-l4d2/plugin-l4d2-core -am -DskipTests -q 2>&1
    $mvnExitCode = $LASTEXITCODE

    if ($mvnExitCode -ne 0) {
        Write-LogError "插件打包失败 (exit code: $mvnExitCode)"
        Write-LogError "打包输出:"
        $mvnResult | Select-Object -Last 30 | ForEach-Object { Write-Host $_ }
        throw "插件打包失败 (exit code: $mvnExitCode)"
    }

    # 检查 JAR 是否生成（动态匹配版本文件名）
    $builtJar = Get-ChildItem $PLUGIN_L4D2_JAR_GLOB -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" -and $_.Name -notlike "*-exec.jar" -and -not $_.Name.EndsWith("-original.jar") } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $builtJar) {
        Write-LogError "插件 JAR 未生成: $PLUGIN_L4D2_JAR_GLOB"
        throw "插件 JAR 未生成"
    }
    $PLUGIN_L4D2_JAR = $builtJar.FullName

    $jarInfo = Get-Item $PLUGIN_L4D2_JAR
    Write-LogInfo "插件 JAR 已生成: $($jarInfo.Name), 大小: $([math]::Round($jarInfo.Length / 1MB, 2)) MB"

    # 仅复制主 JAR 还不够：必须确认 PF4J 描述符存在，避免把空清单或文档包
    # 部署到 plugins/ 后，后端直到运行时才以 "Field 'id' cannot be empty" 失败。
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jarArchive = [System.IO.Compression.ZipFile]::OpenRead($PLUGIN_L4D2_JAR)
    try {
        $manifestEntry = $jarArchive.GetEntry("META-INF/MANIFEST.MF")
        if (-not $manifestEntry) {
            Write-LogError "插件 JAR 缺少 META-INF/MANIFEST.MF: $PLUGIN_L4D2_JAR"
            throw "插件 JAR 缺少 MANIFEST.MF"
        }
        $manifestReader = New-Object System.IO.StreamReader($manifestEntry.Open())
        try {
            $manifestText = $manifestReader.ReadToEnd()
        } finally {
            $manifestReader.Dispose()
        }
        if ($manifestText -notmatch "(?m)^Plugin-Id:\s*\S+") {
            Write-LogError "插件 JAR 清单缺少 Plugin-Id，不能部署: $PLUGIN_L4D2_JAR"
            Write-LogError "请检查 maven-jar-plugin 的 manifestEntries 配置与构建输出"
            throw "插件 JAR 清单缺少 Plugin-Id"
        }
    } finally {
        $jarArchive.Dispose()
    }
    Write-LogInfo "插件 JAR 清单校验通过: Plugin-Id 已声明"

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

    # 复制新 JAR 到 plugins 目录（保留构建版本文件名；PF4J 从 manifest 读 plugin.id，不依赖文件名）
    $destJar = Join-Path $PLUGINS_DIR $jarInfo.Name
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
        throw "Classpath 文件不存在: $cpFile"
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
    $argFile = Join-Path $env:TEMP "game-platform-jvm-args-$PID.txt"
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
    # Start-Process 返回的是 cmd.exe 的 PID，不是 java 的 PID；把同一个 argfile
    # 路径写入启动脚本目录，E2E runner 可据此校验 java 进程真实归属。
    $argFilePointer = Join-Path $LOG_DIR "game-platform-jvm-args.path"
    Set-Content -Path $argFilePointer -Value $argFile -Encoding UTF8
    Set-Content -Path $batFile -Value $batContent -Encoding ASCII

    Write-LogInfo "正在启动 Java 进程 (start-backend.bat)..."
    Write-LogInfo "参数文件: $argFile"
    Write-LogInfo "启动脚本: $batFile"
    $process = Start-Process -FilePath $batFile `
        -WorkingDirectory $BACKEND_DIR `
        -WindowStyle Hidden `
        -PassThru

    $launcherPid = $process.Id
    $launcherPid | Out-File -FilePath $LAUNCHER_PID_FILE -Encoding utf8
    Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue
    Write-LogInfo "启动器 PID: $launcherPid（仅用于诊断，不作为 Java PID）"

    # 等待启动完成（最多 120 秒，给首次冷启动留出余量）
    Write-LogInfo "等待应用启动 (最多 120 秒)..."
    $startTime = Get-Date
    $timeout = 120
    $started = $false
    $javaProcess = $null
    $javaProcessAbsentSince = $null

    while (((Get-Date) - $startTime).TotalSeconds -lt $timeout) {
        # 包装器 cmd.exe 退出是正常现象；按 argfile 找到真正持有它的 Java 进程。
        $javaProcess = Find-BackendJavaProcess -ArgFile $argFile -MainClass $MAIN_CLASS
        if ($javaProcess) {
            $javaProcessAbsentSince = $null
        } elseif ($null -eq $javaProcessAbsentSince) {
            $javaProcessAbsentSince = Get-Date
        }

        # 检查端口是否监听
        $listenerPid = Get-PortListenerProcessId -Port $SERVER_PORT
        if ($javaProcess -and $listenerPid -eq $javaProcess.ProcessId) {
            # 端口已监听，再等 3 秒确保完全就绪
            Start-Sleep -Seconds 3

            # 健康检查（尝试登录接口）
            try {
                $testBody = @{username='admin';password='admin123'} | ConvertTo-Json
                $resp = Invoke-RestMethod -Uri "http://localhost:$SERVER_PORT/api/auth/login" `
                    -Method Post -ContentType 'application/json' -Body $testBody -TimeoutSec 5
                if ($resp.code -eq 200) {
                    $started = $true
                    $javaProcess.ProcessId | Out-File -FilePath $PID_FILE -Encoding utf8
                    break
                }
            } catch {
                # 登录接口可能还未就绪，继续等待
            }
        }

        # 给 Java 从 cmd.exe 包装器接管的短暂窗口，不因包装器先退出而误报。
        if (-not $javaProcess -and -not $listenerPid -and $javaProcessAbsentSince -and
            ((Get-Date) - $javaProcessAbsentSince).TotalSeconds -ge 15) {
            Write-LogError "未找到启动参数对应的 Java 进程，且端口 $SERVER_PORT 未监听"
            break
        }

        Start-Sleep -Seconds 2
    }

    if ($started) {
        Write-LogInfo "========== 后端启动成功 =========="
        Write-LogInfo "Java PID: $($javaProcess.ProcessId)"
        Write-LogInfo "访问地址: http://localhost:$SERVER_PORT"
        Write-LogInfo "API 文档: http://localhost:$SERVER_PORT/swagger-ui.html"
        Write-LogInfo "应用日志: $APP_LOG_FILE"
        Write-LogInfo "PID 文件: $PID_FILE"
    } else {
        $javaProcess = Find-BackendJavaProcess -ArgFile $argFile -MainClass $MAIN_CLASS
        $listenerPid = Get-PortListenerProcessId -Port $SERVER_PORT
        if ($javaProcess) {
            # 进程仍在启动时保留它，避免拖慢首次冷启动。
            $javaProcess.ProcessId | Out-File -FilePath $PID_FILE -Encoding utf8
            Write-LogWarn "========== 后端未在 120 秒内通过登录健康检查，但 Java 进程仍存在 (PID: $($javaProcess.ProcessId)) =========="
            Write-LogWarn "应用日志: $APP_LOG_FILE"
            Write-LogWarn "请稍后用 curl http://localhost:$SERVER_PORT/api/system/info 确认就绪"
        } else {
            Write-LogError "========== 后端启动失败：未确认到 Java 进程，端口 $SERVER_PORT 未监听 =========="
            Write-LogError "请检查日志: $APP_LOG_FILE"
            Write-LogError "最近日志（最后 30 行）:"
            if (Test-Path $APP_LOG_FILE) {
                Get-Content $APP_LOG_FILE -Tail 30 | ForEach-Object { Write-Host $_ }
            }
            if (Test-Path $APP_ERR_LOG_FILE) {
                Get-Content $APP_ERR_LOG_FILE -Tail 30 | ForEach-Object { Write-Host $_ }
            }
            Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue
            throw "后端启动失败：未确认到 Java 进程，端口 $SERVER_PORT 未监听"
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
