@echo off
REM ============================================================================
REM Game Platform Manager - 重启脚本 (Windows Batch)
REM 
REM 功能说明:
REM   - 先停止应用，再启动应用
REM   - 支持优雅重启和强制重启
REM   - 支持开发环境和生产环境
REM
REM 使用方法:
REM   restart.bat              # 生产环境重启 (优雅停止)
REM   restart.bat dev          # 开发环境重启
REM   restart.bat force        # 生产环境强制重启
REM   restart.bat dev force    # 开发环境强制重启
REM
REM 作者: Game Platform Manager
REM 创建日期: 2026-03-23
REM ============================================================================

setlocal enabledelayedexpansion

REM ============================================
REM 配置区域
REM ============================================

REM 脚本所在目录
set SCRIPT_DIR=%~dp0

REM 项目根目录
set PROJECT_DIR=%SCRIPT_DIR%..

REM 日志目录
set LOG_DIR=%PROJECT_DIR%\logs
set LOG_FILE=%LOG_DIR%\startup.log

REM ============================================
REM 解析参数
REM ============================================

set ENV=prod
set FORCE_STOP=false

for %%a in (%*) do (
    if "%%a"=="dev" set ENV=dev
    if "%%a"=="prod" set ENV=prod
    if "%%a"=="force" set FORCE_STOP=true
    if "%%a"=="-f" set FORCE_STOP=true
    if "%%a"=="help" goto :show_help
    if "%%a"=="--help" goto :show_help
    if "%%a"=="-h" goto :show_help
)

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

REM ============================================
REM 主程序
REM ============================================

REM 确保日志目录存在
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

call :log_info ========== 开始重启应用 ==========
call :log_info 环境: %ENV%
call :log_info 强制停止: %FORCE_STOP%

REM 停止应用
call :log_info 正在停止应用...
if "%FORCE_STOP%"=="true" (
    call "%SCRIPT_DIR%stop.bat" force
) else (
    call "%SCRIPT_DIR%stop.bat"
)

REM 等待端口释放
timeout /t 2 /nobreak >nul

REM 启动应用
call :log_info 正在启动应用...
call "%SCRIPT_DIR%start.bat" %ENV%

call :log_info ========== 应用重启完成 ==========
exit /b 0

REM ============================================
REM 帮助信息
REM ============================================

:show_help
echo 用法: %~nx0 [环境] [选项]
echo.
echo 环境:
echo   prod        生产环境 ^(默认^)
echo   dev         开发环境
echo.
echo 选项:
echo   force       强制停止后重启
echo   help        显示此帮助信息
echo.
echo 示例:
echo   %~nx0              # 生产环境优雅重启
echo   %~nx0 dev          # 开发环境优雅重启
echo   %~nx0 force        # 生产环境强制重启
echo   %~nx0 dev force    # 开发环境强制重启
exit /b 0

endlocal
