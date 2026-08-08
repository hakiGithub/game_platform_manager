@echo off
setlocal enabledelayedexpansion

set BACKEND_DIR=d:\program\ai\game_platform_manger\backend
set LOG_DIR=%BACKEND_DIR%\logs
set APP_LOG=%LOG_DIR%\application.log
set ERR_LOG=%LOG_DIR%\application.err.log
set DB_PATH=%BACKEND_DIR%\data\game_platform.db
set DB_URL=jdbc:sqlite:%DB_PATH:\=/%
set CP_FILE=%BACKEND_DIR%\core\target\cp.txt
set ARG_FILE=%TEMP%\game-platform-jvm-args.txt
set WRAPPER_BAT=%TEMP%\game-platform-start-wrapper.bat

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%BACKEND_DIR%\data" mkdir "%BACKEND_DIR%\data"

if exist "%APP_LOG%" type nul > "%APP_LOG%"
if exist "%ERR_LOG%" type nul > "%ERR_LOG%"

for /f "delims=" %%i in (%CP_FILE%) do set DEP_CP=%%i
set CP=core/target/classes;api/target/classes;plugin/target/classes;!DEP_CP!

echo -Xms512m> "%ARG_FILE%"
echo -Xmx1024m>> "%ARG_FILE%"
echo -XX:MaxMetaspaceSize=256m>> "%ARG_FILE%"
echo -XX:+UseG1GC>> "%ARG_FILE%"
echo -XX:+HeapDumpOnOutOfMemoryError>> "%ARG_FILE%"
echo -XX:HeapDumpPath=%LOG_DIR%\heap_dump.hprof>> "%ARG_FILE%"
echo -Dspring.devtools.restart.enabled=false>> "%ARG_FILE%"
echo -Dspring.devtools.livereload.enabled=false>> "%ARG_FILE%"
echo -Dlogging.file.path=%LOG_DIR%>> "%ARG_FILE%"
echo -Dspring.datasource.url=%DB_URL%>> "%ARG_FILE%"
echo -cp>> "%ARG_FILE%"
echo %CP%>> "%ARG_FILE%"
echo com.gameplatform.GamePlatformApplication>> "%ARG_FILE%"
echo --server.port=8080>> "%ARG_FILE%"

echo @echo off> "%WRAPPER_BAT%"
echo java @"%ARG_FILE%" > "%APP_LOG%" 2> "%ERR_LOG%">> "%WRAPPER_BAT%"

echo Starting backend...
echo DB URL: %DB_URL%
echo Arg file: %ARG_FILE%

start "" /D "%BACKEND_DIR%" /B "%WRAPPER_BAT%"

echo Backend started in background. Waiting for port 8080...
timeout /t 8 /nobreak > nul

set WAIT=0
:waitloop
netstat -ano | findstr "LISTENING" | findstr ":8080" > nul
if !errorlevel! equ 0 (
    echo Backend is listening on port 8080
    goto :done
)
set /a WAIT+=1
if !WAIT! geq 60 (
    echo Timeout waiting for backend to start
    goto :showlogs
)
timeout /t 2 /nobreak > nul
goto :waitloop

:showlogs
echo.
echo === Last 30 lines of application log ===
if exist "%APP_LOG%" powershell -NoProfile -Command "Get-Content '%APP_LOG%' -Tail 30"
echo.
echo === Last 30 lines of error log ===
if exist "%ERR_LOG%" powershell -NoProfile -Command "Get-Content '%ERR_LOG%' -Tail 30"

:done
endlocal
