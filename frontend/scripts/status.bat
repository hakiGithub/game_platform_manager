@echo off
REM ============================================
REM Game Platform Manager - Frontend Status Script (Windows Batch)
REM Description: Check frontend server status
REM Usage: status.bat
REM ============================================

setlocal enabledelayedexpansion

REM Configuration
set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."
set "LOG_DIR=%PROJECT_DIR%\logs"
set "PID_FILE=%PROJECT_DIR%\.frontend.pid"

echo ==========================================
echo Game Platform Manager - Frontend Status
echo ==========================================
echo.

REM Check if PID file exists
if not exist "%PID_FILE%" (
    echo Status: STOPPED
    echo PID File: Not found
    echo.
    echo Service is not running.
    goto :end
)

REM Read PID from file
set /p TARGET_PID=<"%PID_FILE%"

REM Check if process is running
tasklist /FI "PID %TARGET_PID%" 2>nul | find "%TARGET_PID%" >nul
if !errorlevel! neq 0 (
    echo Status: STOPPED ^(Stale PID file^)
    echo PID File: %TARGET_PID% ^(process not running^)
    echo.
    echo Service is not running. Cleaning up PID file...
    del "%PID_FILE%" 2>nul
    goto :end
)

REM Get process information
echo Status: RUNNING
echo PID: %TARGET_PID%
echo.

REM Get process details
for /f "tokens=1,2,3,4,5" %%A in ('tasklist /FI "PID %TARGET_PID%" /FO TABLE ^| find "%TARGET_PID%"') do (
    echo Process Name: %%A
    echo Memory Usage: %%E
)

echo.

REM Check if log file exists
if exist "%LOG_DIR%\frontend.log" (
    echo Recent Log Entries:
    echo -------------------
    type "%LOG_DIR%\frontend.log" | more +1 | find /v "" | findstr /n "." | findstr "^[0-9]*:" | tail -n 5
    echo.
)

REM Check port usage
echo Port Usage:
echo -----------
netstat -ano | findstr ":5173" | findstr "LISTENING"
netstat -ano | findstr ":4173" | findstr "LISTENING"

:end
echo.
echo ==========================================

endlocal
