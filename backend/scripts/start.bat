@echo off
REM ============================================================================
REM Game Platform Manager - 启动脚本 (Windows Batch)
REM 
REM 功能说明:
REM   - 支持开发环境启动 (mvn spring-boot:run)
REM   - 支持生产环境启动 (java -jar xxx.jar)
REM   - 包含JVM参数配置、日志记录、进程检查、健康检查
REM
REM 使用方法:
REM   start.bat              # 生产环境启动 (默认)
REM   start.bat dev          # 开发环境启动
REM   start.bat prod         # 生产环境启动
REM
REM 作者: Game Platform Manager
REM 创建日期: 2026-03-23
REM ============================================================================

setlocal enabledelayedexpansion

REM ============================================
REM 配置区域
REM ============================================

REM 应用名称
set APP_NAME=game-platform-manager

REM 脚本所在目录
set SCRIPT_DIR=%~dp0

REM 项目根目录
set PROJECT_DIR=%SCRIPT_DIR%..

REM JAR文件路径
set JAR_NAME=%APP_NAME%.jar
set JAR_FILE=%PROJECT_DIR%\target\%JAR_NAME%

REM PID文件路径
set PID_DIR=%PROJECT_DIR%\logs
set PID_FILE=%PID_DIR%\%APP_NAME%.pid

REM 日志目录
set LOG_DIR=%PROJECT_DIR%\logs
set LOG_FILE=%LOG_DIR%\startup.log
set APP_LOG_FILE=%LOG_DIR%\application.log

REM 主类
set MAIN_CLASS=com.gameplatform.GamePlatformApplication

REM 服务端口
set SERVER_PORT=8080

REM 健康检查URL
set HEALTH_CHECK_URL=http://localhost:%SERVER_PORT%/actuator/health

REM 启动等待超时时间(秒)
set STARTUP_TIMEOUT=60

REM ============================================
REM JVM参数配置
REM ============================================

REM 内存配置
set JVM_XMS=512m
set JVM_XMX=1024m
set JVM_METASPACE=256m

REM GC配置 (G1GC - Java 17推荐)
set JVM_GC_OPTS=-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2 -XX:+ExplicitGCInvokesConcurrent

REM 性能优化参数
set JVM_PERF_OPTS=-XX:+UseStringDeduplication -XX:+OptimizeStringConcat -XX:+UseCompressedOops -XX:+UseCompressedClassPointers

REM 内存溢出时生成堆转储
set JVM_OOM_OPTS=-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=%LOG_DIR%\heap_dump.hprof

REM GC日志配置
set JVM_GC_LOG_OPTS=-Xlog:gc*:file=%LOG_DIR%\gc.log:time,uptime,level,tags:filecount=5,filesize=10M

REM 远程调试参数 (生产环境默认关闭)
REM set JVM_DEBUG_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005

REM 组合JVM参数
set JVM_OPTS=-Xms%JVM_XMS% -Xmx%JVM_XMX% -XX:MaxMetaspaceSize=%JVM_METASPACE% %JVM_GC_OPTS% %JVM_PERF_OPTS% %JVM_OOM_OPTS% %JVM_GC_LOG_OPTS%

REM Spring Boot参数
set SPRING_OPTS=--spring.profiles.active=prod --server.port=%SERVER_PORT% --logging.file.path=%LOG_DIR%

REM ============================================
REM 解析参数
REM ============================================

set MODE=prod
if not "%1"=="" set MODE=%1

if "%MODE%"=="help" goto :show_help
if "%MODE%"=="--help" goto :show_help
if "%MODE%"=="-h" goto :show_help
if "%MODE%"=="dev" goto :start_dev
if "%MODE%"=="prod" goto :start_prod
goto :show_help

REM ============================================
REM 工具函数
REM ============================================

:log_info
echo [%date% %time%] [INFO] %*
if exist "%LOG_DIR%" echo [%date% %time%] [INFO] %* >> "%LOG_FILE%"
goto :eof

:log_warn
echo [%date% %time%] [WARN] %*
if exist "%LOG_DIR%" echo [%date% %time%] [WARN] %* >> "%LOG_FILE%"
goto :eof

:log_error
echo [%date% %time%] [ERROR] %*
if exist "%LOG_DIR%" echo [%date% %time%] [ERROR] %* >> "%LOG_FILE%"
goto :eof

:check_prerequisites
call :log_info 检查前置条件...

REM 检查Java
where java >nul 2>&1
if errorlevel 1 (
    call :log_error 未找到Java，请确保Java 17已安装并配置PATH环境变量
    exit /b 1
)

REM 检查Java版本
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VERSION=%%v
    goto :check_java_version
)
:check_java_version
set JAVA_VERSION=%JAVA_VERSION:"=%
for /f "tokens=1 delims=." %%a in ("%JAVA_VERSION%") do set JAVA_MAJOR=%%a
if %JAVA_MAJOR% lss 17 (
    call :log_error Java版本过低，需要Java 17或更高版本，当前版本: %JAVA_VERSION%
    exit /b 1
)
call :log_info Java版本检查通过

REM 创建必要目录
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%PID_DIR%" mkdir "%PID_DIR%"

call :log_info 前置条件检查完成
exit /b 0

:check_dev_prerequisites
call :check_prerequisites
if errorlevel 1 exit /b 1

where mvn >nul 2>&1
if errorlevel 1 (
    call :log_error 未找到Maven，请确保Maven已安装并配置PATH环境变量
    exit /b 1
)
call :log_info Maven检查通过
exit /b 0

:is_running
if exist "%PID_FILE%" (
    set /p PID=<"%PID_FILE%"
    tasklist /FI "PID eq !PID!" 2>nul | findstr /i "!PID!" >nul
    if !errorlevel! equ 0 (
        exit /b 0
    )
)
exit /b 1

:get_pid
if exist "%PID_FILE%" (
    set /p PID=<"%PID_FILE%"
)
goto :eof

:wait_for_startup
call :log_info 等待应用启动完成 ^(超时: %STARTUP_TIMEOUT%秒^)...

set /a COUNT=0
set /a MAX_COUNT=%STARTUP_TIMEOUT%

:wait_loop
if %COUNT% geq %MAX_COUNT% (
    call :log_error 应用启动超时 ^(%STARTUP_TIMEOUT%秒^)
    exit /b 1
)

REM 检查进程是否还存在
call :is_running
if errorlevel 1 (
    call :log_error 应用进程已退出，启动失败
    exit /b 1
)

REM 检查端口是否被监听
netstat -ano | findstr ":%SERVER_PORT% " | findstr "LISTENING" >nul
if !errorlevel! equ 0 (
    REM 端口已监听，等待几秒确保应用完全启动
    timeout /t 3 /nobreak >nul
    
    REM 检查健康检查端点
    curl -sf "%HEALTH_CHECK_URL%" >nul 2>&1
    if !errorlevel! equ 0 (
        call :log_info 应用启动成功，健康检查通过
        exit /b 0
    )
    call :log_info 应用已监听端口 %SERVER_PORT%，继续等待...
)

timeout /t 2 /nobreak >nul
set /a COUNT+=2
goto :wait_loop

REM ============================================
REM 启动函数
REM ============================================

:start_prod
call :log_info ========== 开始启动应用 ^(生产环境^) ==========

REM 检查是否已运行
call :is_running
if !errorlevel! equ 0 (
    call :get_pid
    call :log_warn 应用已在运行中，PID: !PID!
    exit /b 0
)

REM 检查JAR文件
if not exist "%JAR_FILE%" (
    call :log_error JAR文件不存在: %JAR_FILE%
    call :log_error 请先执行打包命令: mvn clean package -DskipTests
    exit /b 1
)

call :check_prerequisites
if errorlevel 1 exit /b 1

call :log_info JAR文件: %JAR_FILE%
call :log_info JVM参数: %JVM_OPTS%
call :log_info 日志目录: %LOG_DIR%

REM 启动应用
call :log_info 正在启动应用...

cd /d "%PROJECT_DIR%"

start "Game Platform Manager" /min cmd /c "java %JVM_OPTS% -jar "%JAR_FILE%" %SPRING_OPTS% >> "%APP_LOG_FILE%" 2>&1"

REM 等待进程启动并获取PID
timeout /t 2 /nobreak >nul

REM 通过端口查找PID
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%SERVER_PORT% " ^| findstr "LISTENING"') do (
    set PID=%%a
    goto :found_pid
)

REM 备用方案：通过进程名查找
for /f "tokens=2" %%a in ('tasklist /FI "IMAGENAME eq java.exe" /FO LIST ^| findstr "PID:"') do (
    set PID=%%a
    goto :found_pid
)

:found_pid
if defined PID (
    echo !PID! > "%PID_FILE%"
    call :log_info 应用进程已启动，PID: !PID!
) else (
    call :log_warn 无法获取PID，请手动检查进程
)

REM 等待启动完成
call :wait_for_startup
if errorlevel 1 (
    call :log_error ========== 应用启动失败 ==========
    if exist "%PID_FILE%" del "%PID_FILE%"
    exit /b 1
)

call :log_info ========== 应用启动成功 ==========
call :log_info 访问地址: http://localhost:%SERVER_PORT%
call :log_info API文档: http://localhost:%SERVER_PORT%/swagger-ui.html
exit /b 0

:start_dev
call :log_info ========== 开始启动应用 ^(开发环境^) ==========

REM 检查是否已运行
call :is_running
if !errorlevel! equ 0 (
    call :get_pid
    call :log_warn 应用已在运行中，PID: !PID!
    exit /b 0
)

call :check_dev_prerequisites
if errorlevel 1 exit /b 1

call :log_info 项目目录: %PROJECT_DIR%
call :log_info 主类: %MAIN_CLASS%

cd /d "%PROJECT_DIR%"

REM 启动应用 (使用Maven)
call :log_info 正在启动应用 ^(mvn spring-boot:run^)...

start "Game Platform Manager (Dev)" /min cmd /c "mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xms256m -Xmx512m" -Dspring-boot.run.arguments="--server.port=%SERVER_PORT%" >> "%APP_LOG_FILE%" 2>&1"

REM 等待进程启动
timeout /t 3 /nobreak >nul

REM 通过端口查找PID
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%SERVER_PORT% " ^| findstr "LISTENING"') do (
    set PID=%%a
    goto :found_pid_dev
)

:found_pid_dev
if defined PID (
    echo !PID! > "%PID_FILE%"
    call :log_info 应用进程已启动，PID: !PID!
)

REM 等待启动完成
call :wait_for_startup
if errorlevel 1 (
    call :log_error ========== 应用启动失败 ==========
    if exist "%PID_FILE%" del "%PID_FILE%"
    exit /b 1
)

call :log_info ========== 应用启动成功 ^(开发模式^) ==========
call :log_info 访问地址: http://localhost:%SERVER_PORT%
call :log_info API文档: http://localhost:%SERVER_PORT%/swagger-ui.html
exit /b 0

REM ============================================
REM 帮助信息
REM ============================================

:show_help
echo 用法: %~nx0 [选项]
echo.
echo 选项:
echo   (无参数)    生产环境启动 ^(默认^)
echo   dev         开发环境启动 ^(使用mvn spring-boot:run^)
echo   prod        生产环境启动
echo   help        显示此帮助信息
echo.
echo 示例:
echo   %~nx0          # 生产环境启动
echo   %~nx0 dev      # 开发环境启动
exit /b 0

endlocal
