@echo off
REM ============================================
REM Game Platform Manager - Frontend Stop Script (Windows Batch)
REM Description: Stop frontend server
REM Usage: stop.bat
REM ============================================

setlocal enabledelayedexpansion

REM Configuration
set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."
set "LOG_DIR=%PROJECT_DIR%\logs"
set "PID_FILE=%PROJECT_DIR%\.frontend.pid"

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

REM Main execution
call :log "=========================================="
call :log "Game Platform Manager - Frontend Stop"
call :log "=========================================="

REM Check if PID file exists
if not exist "%PID_FILE%" (
    call :log "No PID file found. Service may not be running."

    REM Try to find and kill node processes related to frontend
    call :log "Attempting to find and stop any running frontend processes..."

    REM Kill any vite or serve processes
    for /f "tokens=2" %%P in ('tasklist /FI "IMAGENAME eq node.exe" /FO LIST ^| find "PID:"') do (
        taskkill /PID %%P /F >nul 2>&1
        if !errorlevel!==0 (
            call :log "Stopped node process with PID %%P"
        )
    )

    exit /b 0
)

REM Read PID from file
set /p TARGET_PID=<"%PID_FILE%"

REM Check if process is running
tasklist /FI "PID %TARGET_PID%" 2>nul | find "%TARGET_PID%" >nul
if !errorlevel! neq 0 (
    call :log "Process with PID %TARGET_PID% is not running. Cleaning up PID file..."
    del "%PID_FILE%" 2>nul
    exit /b 0
)

REM Stop the process
call :log "Stopping process with PID %TARGET_PID%..."

REM Try graceful shutdown first
taskkill /PID %TARGET_PID% >nul 2>&1
timeout /t 3 /nobreak >nul

REM Check if process still running
tasklist /FI "PID %TARGET_PID%" 2>nul | find "%TARGET_PID%" >nul
if !errorlevel!==0 (
    call :log "Process still running, forcing termination..."
    taskkill /PID %TARGET_PID% /F >nul 2>&1
)

REM Verify process stopped
tasklist /FI "PID %TARGET_PID%" 2>nul | find "%TARGET_PID%" >nul
if !errorlevel!==0 (
    call :log "ERROR: Failed to stop process with PID %TARGET_PID%"
    exit /b 1
) else (
    call :log "Process stopped successfully"
    del "%PID_FILE%" 2>nul
)

call :log "Frontend server stopped successfully"

endlocal
