@echo off
REM ============================================
REM Game Platform Manager - Frontend Restart Script (Windows Batch)
REM Description: Restart frontend server
REM Usage: restart.bat [dev|prod] [port]
REM ============================================

setlocal enabledelayedexpansion

REM Configuration
set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."
set "LOG_DIR=%PROJECT_DIR%\logs"

REM Create log directory if not exists
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

REM Get timestamp for log
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do set "DATETIME=%%I"
set "TIMESTAMP=%DATETIME:~0,4%-%DATETIME:~4,2%-%DATETIME:~6,2% %DATETIME:~8,2%:%DATETIME:~10,2%:%DATETIME:~12,2%"

REM Log function
:log
echo [%TIMESTAMP%] %* >> "%LOG_DIR%\frontend.log"
echo [%TIMESTAMP%] %*
goto :eof

REM Store arguments
set "MODE=%1"
set "PORT=%2"

call :log "=========================================="
call :log "Game Platform Manager - Frontend Restart"
call :log "=========================================="

REM Stop the service
call :log "Stopping frontend server..."
call "%SCRIPT_DIR%stop.bat"
if !errorlevel! neq 0 (
    call :log "WARNING: Stop command returned non-zero exit code"
)

REM Wait for complete shutdown
timeout /t 2 /nobreak >nul

REM Start the service
call :log "Starting frontend server..."
call "%SCRIPT_DIR%start.bat" %MODE% %PORT%
if !errorlevel! neq 0 (
    call :log "ERROR: Failed to start frontend server"
    exit /b 1
)

call :log "Frontend server restarted successfully"

endlocal
