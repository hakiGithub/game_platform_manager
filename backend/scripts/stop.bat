@echo off
REM ============================================================================
REM Game Platform Manager - 停止脚本 (Windows Batch)
REM 
REM 功能说明:
REM   - 优雅停止应用 (发送Ctrl+C信号)
REM   - 强制停止应用 (taskkill /F)
REM   - 进程检查和PID文件管理
REM
REM 使用方法:
REM   stop.bat              # 优雅停止 (默认)
REM   stop.bat force        # 强制停止
REM   stop.bat status       # 查看状态
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

REM PID文件路径
set PID_DIR=%PROJECT_DIR%\logs
set PID_FILE=%PID_DIR%\%APP_NAME%.pid

REM 日志目录
set LOG_DIR=%PROJECT_DIR%\logs
set LOG_FILE=%LOG_DIR%\startup.log

REM 服务端口
set SERVER_PORT=8080

REM 停止等待超时时间(秒)
set STOP_TIMEOUT=30

REM ============================================
REM 解析参数
REM ============================================

set MODE=graceful
if not "%1"=="" set MODE=%1

if "%MODE%"=="help" goto :show_help
if "%MODE%"=="--help" goto :show_help
if "%MODE%"=="-h" goto :show_help
if "%MODE%"=="force" goto :stop_force
if "%MODE%"=="-f" goto :stop_force
if "%MODE%"=="status" goto :show_status
if "%MODE%"=="graceful" goto :stop_graceful
if "%MODE%"=="stop" goto :stop_graceful
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

:find_java_process
REM 通过端口查找PID
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%SERVER_PORT% " ^| findstr "LISTENING" 2^>nul') do (
    set FOUND_PID=%%a
    goto :found_process
)

REM 通过进程名查找
for /f "tokens=2" %%a in ('wmic process where "CommandLine like '%%game-platform-manager%%'" get ProcessId 2^>nul ^| findstr /r "[0-9]"') do (
    set FOUND_PID=%%a
    goto :found_process
)

REM 通过主类查找
for /f "tokens=2" %%a in ('wmic process where "CommandLine like '%%GamePlatformApplication%%'" get ProcessId 2^>nul ^| findstr /r "[0-9]"') do (
    set FOUND_PID=%%a
    goto :found_process
)

set FOUND_PID=
goto :eof

:found_process
goto :eof

:wait_for_stop
set PID=%~1
call :log_info 等待进程停止 ^(PID: %PID%, 超时: %STOP_TIMEOUT%秒^)...

set /a COUNT=0
set /a MAX_COUNT=%STOP_TIMEOUT%

:wait_stop_loop
if %COUNT% geq %MAX_COUNT% (
    call :log_warn 进程停止超时
    exit /b 1
)

tasklist /FI "PID eq %PID%" 2>nul | findstr /i "%PID%" >nul
if errorlevel 1 (
    call :log_info 进程已停止
    exit /b 0
)

timeout /t 1 /nobreak >nul
set /a COUNT+=1
goto :wait_stop_loop

:cleanup_pid_file
if exist "%PID_FILE%" (
    del "%PID_FILE%"
    call :log_info 已清理PID文件: %PID_FILE%
)
goto :eof

REM ============================================
REM 停止函数
REM ============================================

:stop_graceful
call :log_info ========== 开始停止应用 ^(优雅停止^) ==========

REM 确保日志目录存在
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

call :is_running
if errorlevel 1 (
    call :log_warn 应用未在运行
    
    REM 尝试查找残留进程
    call :find_java_process
    if defined FOUND_PID (
        call :log_warn 发现残留进程，PID: !FOUND_PID!
        set /p CONFIRM="是否停止该进程? [y/N]: "
        if /i "!CONFIRM!"=="y" (
            taskkill /PID !FOUND_PID! >nul 2>&1
            call :log_info 已发送停止信号
        )
    )
    
    call :cleanup_pid_file
    exit /b 0
)

call :get_pid
call :log_info 应用正在运行，PID: !PID!

REM 发送停止信号 (优雅停止)
call :log_info 发送停止信号...
taskkill /PID !PID! >nul 2>&1

REM 等待进程停止
call :wait_for_stop !PID!
if errorlevel 1 (
    call :log_warn 优雅停止超时，请使用强制停止: %~nx0 force
    exit /b 1
)

call :cleanup_pid_file
call :log_info ========== 应用已停止 ==========
exit /b 0

:stop_force
call :log_info ========== 开始停止应用 ^(强制停止^) ==========

REM 确保日志目录存在
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

set PID=

call :is_running
if !errorlevel! equ 0 (
    call :get_pid
) else (
    call :find_java_process
    set PID=!FOUND_PID!
)

if not defined PID (
    call :log_warn 未找到运行中的应用进程
    call :cleanup_pid_file
    exit /b 0
)

call :log_info 应用进程PID: %PID%

REM 先尝试优雅停止
call :log_info 发送停止信号...
taskkill /PID %PID% >nul 2>&1
timeout /t 3 /nobreak >nul

REM 检查是否停止
tasklist /FI "PID eq %PID%" 2>nul | findstr /i "%PID%" >nul
if errorlevel 1 (
    call :cleanup_pid_file
    call :log_info ========== 应用已停止 ==========
    exit /b 0
)

REM 强制停止
call :log_warn 进程未响应，强制停止...
taskkill /F /PID %PID% >nul 2>&1
timeout /t 1 /nobreak >nul

REM 最终检查
tasklist /FI "PID eq %PID%" 2>nul | findstr /i "%PID%" >nul
if errorlevel 1 (
    call :cleanup_pid_file
    call :log_info ========== 应用已强制停止 ==========
    exit /b 0
) else (
    call :log_error 无法停止进程，请手动处理
    exit /b 1
)

REM ============================================
REM 状态查看
REM ============================================

:show_status
echo ========== 应用状态 ==========
echo.

call :is_running
if !errorlevel! equ 0 (
    call :get_pid
    echo 状态: 运行中
    echo PID: !PID!
    echo PID文件: %PID_FILE%
    echo.
    
    REM 显示进程信息
    echo 进程信息:
    tasklist /FI "PID eq !PID!" /V 2>nul
    echo.
    
    REM 检查端口
    echo 端口监听:
    netstat -ano | findstr ":%SERVER_PORT% " | findstr "LISTENING"
    if errorlevel 1 echo 端口 %SERVER_PORT% 未监听
) else (
    echo 状态: 未运行
    
    REM 检查是否有残留进程
    call :find_java_process
    if defined FOUND_PID (
        echo.
        echo 警告: 发现可能的残留进程，PID: !FOUND_PID!
    )
)

echo.
echo ==============================
exit /b 0

REM ============================================
REM 帮助信息
REM ============================================

:show_help
echo 用法: %~nx0 [选项]
echo.
echo 选项:
echo   (无参数)    优雅停止
echo   force       强制停止
echo   status      查看应用状态
echo   help        显示此帮助信息
echo.
echo 示例:
echo   %~nx0          # 优雅停止
echo   %~nx0 force    # 强制停止
echo   %~nx0 status   # 查看状态
exit /b 0

endlocal
