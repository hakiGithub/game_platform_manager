# Start Java backend as a fully detached process via CIM Win32_Process.Create
$ErrorActionPreference = "Continue"

$BACKEND_DIR = "d:\program\ai\game_platform_manger\backend"
$argFile = Join-Path $BACKEND_DIR "core\target\jvm-args.txt"
$LOG_DIR = Join-Path $BACKEND_DIR "logs"
$APP_LOG_FILE = Join-Path $LOG_DIR "application.log"
$APP_ERR_LOG_FILE = Join-Path $LOG_DIR "application.err.log"
$PID_FILE = Join-Path $LOG_DIR "game-platform-manager.pid"

Clear-Content $APP_LOG_FILE -ErrorAction SilentlyContinue
Clear-Content $APP_ERR_LOG_FILE -ErrorAction SilentlyContinue

$javaArgs = Get-Content $argFile | Where-Object { $_ -ne "" }
$argString = ($javaArgs | ForEach-Object { if ($_ -match '\s') { "`"$_`"" } else { $_ } }) -join " "

$javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $javaExe) { $javaExe = "java" }

$batFile = Join-Path $env:TEMP "start-backend-detached.bat"
$batContent = "@echo off`r`n"
$batContent += "cd /d `"$BACKEND_DIR`"`r`n"
$batContent += "`"$javaExe`" $argString > `"$APP_LOG_FILE`" 2> `"$APP_ERR_LOG_FILE`"`r`n"
Set-Content -Path $batFile -Value $batContent -Encoding ASCII

$result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
    -Arguments @{CommandLine = "cmd.exe /c `"$batFile`""; CurrentDirectory = $BACKEND_DIR}

if ($result.ReturnValue -ne 0) {
    Write-Host "CIM Create failed, return: $($result.ReturnValue)"
    exit 1
}

$procId = $result.ProcessId
Write-Host "Java started via CIM, PID: $procId"
$procId | Out-File -FilePath $PID_FILE -Encoding utf8

$start = Get-Date
$ok = $false
while (((Get-Date) - $start).TotalSeconds -lt 60) {
    $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if (-not $proc) {
        Write-Host "Process exited"
        break
    }
    $listening = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    if ($listening) {
        Start-Sleep -Seconds 3
        try {
            $resp = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 5
            if ($resp.code -eq 200) { $ok = $true; break }
        } catch {}
    }
    Start-Sleep -Seconds 2
}

if ($ok) {
    Write-Host "Backend started OK, PID: $procId"
    Write-Host "URL: http://localhost:8080"
} else {
    Write-Host "Backend not ready in 60s, alive: $([bool](Get-Process -Id $procId -ErrorAction SilentlyContinue))"
    Write-Host "Log: $APP_LOG_FILE"
}
