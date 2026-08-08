<#
.SYNOPSIS
    Game Platform Manager - 启动脚本 (Windows PowerShell)

.DESCRIPTION
    支持开发环境启动 (mvn spring-boot:run) 和生产环境启动 (java -jar xxx.jar)
    包含JVM参数配置、日志记录、进程检查、健康检查

.PARAMETER Mode
    启动模式: dev (开发环境) 或 prod (生产环境，默认)

.EXAMPLE
    .\start.ps1
    生产环境启动

.EXAMPLE
    .\start.ps1 -Mode dev
    开发环境启动

.EXAMPLE
    .\start.ps1 prod
    生产环境启动

.NOTES
    作者: Game Platform Manager
    创建日期: 2026-03-23
#>

param(
    [Parameter(Position = 0)]
    [ValidateSet("dev", "prod", "help")]
    [string]$Mode = "prod"
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

# JAR文件路径
$script:JAR_NAME = "$APP_NAME.jar"
$script:JAR_FILE = Join-Path $PROJECT_DIR "target\$JAR_NAME"

# PID文件路径
$script:PID_DIR = Join-Path $PROJECT_DIR "logs"
$script:PID_FILE = Join-Path $PID_DIR "$APP_NAME.pid"

# 日志目录
$script:LOG_DIR = Join-Path $PROJECT_DIR "logs"
$script:LOG_FILE = Join-Path $LOG_DIR "startup.log"
$script:APP_LOG_FILE = Join-Path $LOG_DIR "application.log"

# 主类
$script:MAIN_CLASS = "com.gameplatform.GamePlatformApplication"

# 服务端口
$script:SERVER_PORT = 8080

# 健康检查URL
$script:HEALTH_CHECK_URL = "http://localhost:$SERVER_PORT/actuator/health"

# 启动等待超时时间(秒)
$script:STARTUP_TIMEOUT = 60

# ============================================
# JVM参数配置
# ============================================

# 内存配置
$script:JVM_XMS = "512m"
$script:JVM_XMX = "1024m"
$script:JVM_METASPACE = "256m"

# GC配置 (G1GC - Java 17推荐)
$script:JVM_GC_OPTS = @(
    "-XX:+UseG1GC",
    "-XX:MaxGCPauseMillis=200",
    "-XX:ParallelGCThreads=4",
    "-XX:ConcGCThreads=2",
    "-XX:+ExplicitGCInvokesConcurrent"
)

# 性能优化参数
$script:JVM_PERF_OPTS = @(
    "-XX:+UseStringDeduplication",
    "-XX:+OptimizeStringConcat",
    "-XX:+UseCompressedOops",
    "-XX:+UseCompressedClassPointers"
)

# 内存溢出时生成堆转储
$script:JVM_OOM_OPTS = @(
    "-XX:+HeapDumpOnOutOfMemoryError",
    "-XX:HeapDumpPath=$LOG_DIR\heap_dump.hprof"
)

# GC日志配置
$script:JVM_GC_LOG_OPTS = "-Xlog:gc*:file=$LOG_DIR\gc.log:time,uptime,level,tags:filecount=5,filesize=10M"

# 远程调试参数 (生产环境默认关闭)
# $script:JVM_DEBUG_OPTS = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

# 组合JVM参数
$script:JVM_OPTS = @(
    "-Xms$JVM_XMS",
    "-Xmx$JVM_XMX",
    "-XX:MaxMetaspaceSize=$JVM_METASPACE"
) + $JVM_GC_OPTS + $JVM_PERF_OPTS + $JVM_OOM_OPTS + @($JVM_GC_LOG_OPTS)

# Spring Boot参数
$script:SPRING_OPTS = @(
    "--spring.profiles.active=prod",
    "--server.port=$SERVER_PORT",
    "--logging.file.path=$LOG_DIR"
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

function Test-JavaVersion {
    try {
        $javaVersion = & java -version 2>&1 | Select-Object -First 1
        $versionMatch = $javaVersion -match '"(\d+)'
        if ($versionMatch) {
            $majorVersion = [int]$matches[1]
            return $majorVersion -ge 17
        }
    }
    catch {
        return $false
    }
    return $false
}

function Test-Prerequisites {
    Write-LogInfo "检查前置条件..."
    
    # 检查Java
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCmd) {
        Write-LogError "未找到Java，请确保Java 17已安装并配置PATH环境变量"
        return $false
    }
    
    # 检查Java版本
    if (-not (Test-JavaVersion)) {
        Write-LogError "Java版本过低，需要Java 17或更高版本"
        return $false
    }
    Write-LogInfo "Java版本检查通过"
    
    # 创建必要目录
    if (-not (Test-Path $LOG_DIR)) {
        New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null
    }
    if (-not (Test-Path $PID_DIR)) {
        New-Item -ItemType Directory -Path $PID_DIR -Force | Out-Null
    }
    
    Write-LogInfo "前置条件检查完成"
    return $true
}

function Test-DevPrerequisites {
    if (-not (Test-Prerequisites)) {
        return $false
    }
    
    $mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvnCmd) {
        Write-LogError "未找到Maven，请确保Maven已安装并配置PATH环境变量"
        return $false
    }
    Write-LogInfo "Maven检查通过"
    return $true
}

function Wait-ForStartup {
    Write-LogInfo "等待应用启动完成 (超时: $STARTUP_TIMEOUT 秒)..."
    
    $startTime = Get-Date
    $endTime = $startTime.AddSeconds($STARTUP_TIMEOUT)
    
    while ((Get-Date) -lt $endTime) {
        # 检查进程是否还存在
        if (-not (Test-AppRunning)) {
            Write-LogError "应用进程已退出，启动失败"
            return $false
        }
        
        # 检查端口是否被监听
        $portListening = Get-NetTCPConnection -LocalPort $SERVER_PORT -State Listen -ErrorAction SilentlyContinue
        if ($portListening) {
            # 端口已监听，等待几秒确保应用完全启动
            Start-Sleep -Seconds 3
            
            # 检查健康检查端点
            try {
                $response = Invoke-WebRequest -Uri $HEALTH_CHECK_URL -UseBasicParsing -TimeoutSec 5 -ErrorAction SilentlyContinue
                if ($response.StatusCode -eq 200) {
                    Write-LogInfo "应用启动成功，健康检查通过"
                    return $true
                }
            }
            catch {
                # 健康检查失败，继续等待
            }
            
            Write-LogInfo "应用已监听端口 $SERVER_PORT，继续等待..."
        }
        
        Start-Sleep -Seconds 2
    }
    
    Write-LogError "应用启动超时 ($STARTUP_TIMEOUT 秒)"
    return $false
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

# ============================================
# 启动函数
# ============================================

function Start-Prod {
    Write-LogInfo "========== 开始启动应用 (生产环境) =========="
    
    # 检查是否已运行
    if (Test-AppRunning) {
        $pid = Get-AppPid
        Write-LogWarn "应用已在运行中，PID: $pid"
        return
    }
    
    # 检查JAR文件
    if (-not (Test-Path $JAR_FILE)) {
        Write-LogError "JAR文件不存在: $JAR_FILE"
        Write-LogError "请先执行打包命令: mvn clean package -DskipTests"
        exit 1
    }
    
    if (-not (Test-Prerequisites)) {
        exit 1
    }
    
    Write-LogInfo "JAR文件: $JAR_FILE"
    Write-LogInfo "JVM参数: $($JVM_OPTS -join ' ')"
    Write-LogInfo "日志目录: $LOG_DIR"
    
    # 启动应用
    Write-LogInfo "正在启动应用..."
    
    Set-Location $PROJECT_DIR
    
    $jvmArgs = $JVM_OPTS -join " "
    $springArgs = $SPRING_OPTS -join " "
    
    $processInfo = New-Object System.Diagnostics.ProcessStartInfo
    $processInfo.FileName = "java"
    $processInfo.Arguments = "$jvmArgs -jar `"$JAR_FILE`" $springArgs"
    $processInfo.WorkingDirectory = $PROJECT_DIR
    $processInfo.UseShellExecute = $false
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $processInfo.CreateNoWindow = $true
    
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $processInfo
    
    # 重定向输出到日志文件
    $process.Start() | Out-Null
    
    # 异步读取输出
    $outputJob = Start-Job -ScriptBlock {
        param($pid, $logFile)
        $process = Get-Process -Id $pid -ErrorAction SilentlyContinue
        if ($process) {
            $process.WaitForExit()
        }
    } -ArgumentList $process.Id, $APP_LOG_FILE
    
    # 保存PID
    $process.Id | Out-File -FilePath $PID_FILE -Encoding utf8
    
    Write-LogInfo "应用进程已启动，PID: $($process.Id)"
    
    # 等待启动完成
    if (Wait-ForStartup) {
        Write-LogInfo "========== 应用启动成功 =========="
        Write-LogInfo "访问地址: http://localhost:$SERVER_PORT"
        Write-LogInfo "API文档: http://localhost:$SERVER_PORT/swagger-ui.html"
    }
    else {
        Write-LogError "========== 应用启动失败 =========="
        if (Test-Path $PID_FILE) {
            Remove-Item $PID_FILE -Force
        }
        exit 1
    }
}

function Start-Dev {
    Write-LogInfo "========== 开始启动应用 (开发环境) =========="
    
    # 检查是否已运行
    if (Test-AppRunning) {
        $pid = Get-AppPid
        Write-LogWarn "应用已在运行中，PID: $pid"
        return
    }
    
    if (-not (Test-DevPrerequisites)) {
        exit 1
    }
    
    Write-LogInfo "项目目录: $PROJECT_DIR"
    Write-LogInfo "主类: $MAIN_CLASS"
    
    Set-Location $PROJECT_DIR
    
    # 启动应用 (使用Maven)
    Write-LogInfo "正在启动应用 (mvn spring-boot:run)..."
    
    $processInfo = New-Object System.Diagnostics.ProcessStartInfo
    $processInfo.FileName = "mvn"
    $processInfo.Arguments = "spring-boot:run -D`"spring-boot.run.jvmArguments=-Xms256m -Xmx512m`" -D`"spring-boot.run.arguments=--server.port=$SERVER_PORT`""
    $processInfo.WorkingDirectory = $PROJECT_DIR
    $processInfo.UseShellExecute = $false
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $processInfo.CreateNoWindow = $true
    
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $processInfo
    $process.Start() | Out-Null
    
    # 保存PID
    $process.Id | Out-File -FilePath $PID_FILE -Encoding utf8
    
    Write-LogInfo "应用进程已启动，PID: $($process.Id)"
    
    # 等待启动完成
    if (Wait-ForStartup) {
        Write-LogInfo "========== 应用启动成功 (开发模式) =========="
        Write-LogInfo "访问地址: http://localhost:$SERVER_PORT"
        Write-LogInfo "API文档: http://localhost:$SERVER_PORT/swagger-ui.html"
    }
    else {
        Write-LogError "========== 应用启动失败 =========="
        if (Test-Path $PID_FILE) {
            Remove-Item $PID_FILE -Force
        }
        exit 1
    }
}

function Show-Help {
    $helpText = @"

用法: .\$($MyInvocation.MyCommand.Name) [选项]

选项:
  (无参数)    生产环境启动 (默认)
  dev         开发环境启动 (使用mvn spring-boot:run)
  prod        生产环境启动
  help        显示此帮助信息

示例:
  .\$($MyInvocation.MyCommand.Name)          # 生产环境启动
  .\$($MyInvocation.MyCommand.Name) dev      # 开发环境启动

"@
    Write-Host $helpText
}

# ============================================
# 主程序
# ============================================

switch ($Mode) {
    "dev" {
        Start-Dev
    }
    "prod" {
        Start-Prod
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
