@echo off
REM ============================================
REM Game Platform Manager - Frontend Start Script (Windows Batch)
REM Description: Start frontend development server or production server
REM Usage: start.bat [dev|prod] [port]
REM ============================================

setlocal enabledelayedexpansion

REM Configuration
set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."
set "LOG_DIR=%PROJECT_DIR%\logs"
set "PID_FILE=%PROJECT_DIR%\.frontend.pid"
set "DEFAULT_PORT=5173"
set "PROD_PORT=4173"

REM Parse arguments
set "MODE=%1"
set "PORT=%2"
if "%MODE%"=="" set "MODE=dev"
if "%PORT%"=="" (
    if "%MODE%"=="prod" (
        set "PORT=%PROD_PORT%"
    ) else (
        set "PORT=%DEFAULT_PORT%"
    )
)

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

REM Check if service is already running
:check_running
if exist "%PID_FILE%" (
    set /p EXISTING_PID=<"%PID_FILE%"
    tasklist /FI "PID !EXISTING_PID!" 2>nul | find "!EXISTING_PID!" >nul
    if !errorlevel!==0 (
        call :log "Service is already running with PID !EXISTING_PID!"
        exit /b 1
    ) else (
        call :log "Stale PID file found, cleaning up..."
        del "%PID_FILE%" 2>nul
    )
)
exit /b 0

REM Start development server
:start_dev
call :log "Starting development server on port %PORT%..."

cd /d "%PROJECT_DIR%"

REM Check if node_modules exists
if not exist "node_modules" (
    call :log "Installing dependencies..."
    call npm install
    if !errorlevel! neq 0 (
        call :log "ERROR: Failed to install dependencies"
        exit /b 1
    )
)

REM Start Vite dev server in background
start "GamePlatform Frontend Dev Server" /min cmd /c "npm run dev -- --port %PORT% >> "%LOG_DIR%\dev-server.log" 2>&1"

REM Wait for server to start and get PID
timeout /t 3 /nobreak >nul

REM Find node process
for /f "tokens=2" %%P in ('tasklist /FI "IMAGENAME eq node.exe" /FO LIST ^| find "PID:"') do (
    set "NODE_PID=%%P"
)

if defined NODE_PID (
    echo !NODE_PID! > "%PID_FILE%"
    call :log "Development server started successfully with PID !NODE_PID! on port %PORT%"
    call :log "Access URL: http://localhost:%PORT%"
) else (
    call :log "WARNING: Could not determine PID, server may still be starting..."
)

exit /b 0

REM Start production server
:start_prod
call :log "Starting production server on port %PORT%..."

cd /d "%PROJECT_DIR%"

REM Check if dist directory exists
if not exist "dist" (
    call :log "Building production files..."
    call npm run build
    if !errorlevel! neq 0 (
        call :log "ERROR: Failed to build production files"
        exit /b 1
    )
)

REM Check if serve is installed
where serve >nul 2>&1
if !errorlevel! neq 0 (
    call :log "Installing serve package..."
    call npm install -g serve
)

REM Start static file server
start "GamePlatform Frontend Prod Server" /min cmd /c "serve -s dist -l %PORT% >> "%LOG_DIR%\prod-server.log" 2>&1"

timeout /t 2 /nobreak >nul

REM Find serve process
for /f "tokens=2" %%P in ('tasklist /FI "IMAGENAME eq node.exe" /FO LIST ^| find "PID:"') do (
    set "SERVE_PID=%%P"
)

if defined SERVE_PID (
    echo !SERVE_PID! > "%PID_FILE%"
    call :log "Production server started successfully with PID !SERVE_PID! on port %PORT%"
    call :log "Access URL: http://localhost:%PORT%"
) else (
    call :log "WARNING: Could not determine PID, server may still be starting..."
)

exit /b 0

REM Main execution
call :log "=========================================="
call :log "Game Platform Manager - Frontend Start"
call :log "Mode: %MODE%"
call :log "Port: %PORT%"
call :log "=========================================="

call :check_running
if !errorlevel! neq 0 exit /b 1

if "%MODE%"=="prod" (
    call :start_prod
) else (
    call :start_dev
)

endlocal
