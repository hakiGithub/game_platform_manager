# Pure ASCII script to start backend (avoids encoding issues)
$BACKEND_DIR = "d:\program\ai\game_platform_manger\backend"
$LOG_DIR = Join-Path $BACKEND_DIR "logs"
$APP_LOG = Join-Path $LOG_DIR "application.log"
$ERR_LOG = Join-Path $LOG_DIR "application.err.log"
$PID_FILE = Join-Path $LOG_DIR "game-platform-manager.pid"
$DB_PATH = Join-Path $BACKEND_DIR "data\game_platform.db"
$CP_FILE = Join-Path $BACKEND_DIR "core\target\cp.txt"
$ARG_FILE = Join-Path $env:TEMP "game-platform-jvm-args.txt"
$WRAPPER_BAT = Join-Path $env:TEMP "game-platform-start-wrapper.bat"

if (-not (Test-Path $LOG_DIR)) { New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null }
$dbDir = Split-Path -Parent $DB_PATH
if (-not (Test-Path $dbDir)) { New-Item -ItemType Directory -Path $dbDir -Force | Out-Null }

if (Test-Path $APP_LOG) { Clear-Content $APP_LOG -ErrorAction SilentlyContinue }
if (Test-Path $ERR_LOG) { Clear-Content $ERR_LOG -ErrorAction SilentlyContinue }

$depCp = (Get-Content $CP_FILE -Raw).Trim()
$cp = "core/target/classes;api/target/classes;plugin/target/classes;$depCp"

$dbPathNormalized = $DB_PATH -replace '\\', '/'
$dbUrl = "jdbc:sqlite:$dbPathNormalized"

$javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $javaExe) { $javaExe = "java" }

$jvmOpts = @(
    "-Xms512m",
    "-Xmx1024m",
    "-XX:MaxMetaspaceSize=256m",
    "-XX:+UseG1GC",
    "-XX:+HeapDumpOnOutOfMemoryError",
    "-XX:HeapDumpPath=$LOG_DIR\heap_dump.hprof",
    "-Dspring.devtools.restart.enabled=false",
    "-Dspring.devtools.livereload.enabled=false",
    "-Dlogging.file.path=$LOG_DIR"
)

$argLines = @()
foreach ($opt in $jvmOpts) { $argLines += $opt }
$argLines += "-Dspring.datasource.url=$dbUrl"
$argLines += "-cp"
$argLines += $cp
$argLines += "com.gameplatform.GamePlatformApplication"
$argLines += "--server.port=8080"
Set-Content -Path $ARG_FILE -Value ($argLines -join "`n") -Encoding ASCII

$batContent = "@echo off`r`n"
$batContent += "`"$javaExe`" @`"$ARG_FILE`" > `"$APP_LOG`" 2> `"$ERR_LOG`"`r`n"
Set-Content -Path $WRAPPER_BAT -Value $batContent -Encoding ASCII

Write-Host "Starting backend..."
Write-Host "DB URL: $dbUrl"
Write-Host "Arg file: $ARG_FILE"
Write-Host "Wrapper: $WRAPPER_BAT"

$process = Start-Process -FilePath $WRAPPER_BAT -WorkingDirectory $BACKEND_DIR -WindowStyle Hidden -PassThru
$processId = $process.Id
$processId | Out-File -FilePath $PID_FILE -Encoding utf8
Write-Host "Java process started, PID: $processId"

Write-Host "Waiting for port 8080..."
$startTime = Get-Date
$timeout = 120
$started = $false

while (((Get-Date) - $startTime).TotalSeconds -lt $timeout) {
    $procExists = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $procExists) {
        Write-Host "Process exited!"
        Write-Host "=== Last 30 lines of app log ==="
        if (Test-Path $APP_LOG) { Get-Content $APP_LOG -Tail 30 | ForEach-Object { Write-Host $_ } }
        Write-Host "=== Last 30 lines of err log ==="
        if (Test-Path $ERR_LOG) { Get-Content $ERR_LOG -Tail 30 | ForEach-Object { Write-Host $_ } }
        Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue
        exit 1
    }

    $listening = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    if ($listening) {
        Start-Sleep -Seconds 3
        try {
            $testBody = @{username='admin';password='admin123'} | ConvertTo-Json
            $resp = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType 'application/json' -Body $testBody -TimeoutSec 5
            if ($resp.code -eq 200) {
                $started = $true
                break
            }
        } catch {
            # Login endpoint may not be ready yet
        }
    }
    Start-Sleep -Seconds 2
}

if ($started) {
    Write-Host "Backend started successfully on port 8080"
} else {
    $procExists = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($procExists) {
        Write-Host "Backend process still running (PID: $processId) but not ready yet"
    } else {
        Write-Host "Backend failed to start"
    }
}
