# Start Java backend as a fully detached process via CIM Win32_Process.Create
$ErrorActionPreference = "Continue"

$BACKEND_DIR = "d:\program\ai\game_platform_manger\backend"
$argFile = Join-Path $BACKEND_DIR "core\target\jvm-args.txt"
$LOG_DIR = Join-Path $BACKEND_DIR "logs"
$APP_LOG_FILE = Join-Path $LOG_DIR "application.log"
$APP_ERR_LOG_FILE = Join-Path $LOG_DIR "application.err.log"
$PID_FILE = Join-Path $LOG_DIR "game-platform-manager.pid"

. (Join-Path $PSScriptRoot "backend-process.ps1")

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
Write-Host "Launcher started via CIM, PID: $procId (not the Java PID)"
$procId | Out-File -FilePath (Join-Path $LOG_DIR "game-platform-manager.launcher.pid") -Encoding utf8
Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue

$start = Get-Date
$ok = $false
while (((Get-Date) - $start).TotalSeconds -lt 60) {
    $javaProcess = Find-BackendJavaProcess -ArgFile $argFile -MainClass "com.gameplatform.GamePlatformApplication"
    $listening = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    $listenerPid = if ($listening) { [int]$listening[0].OwningProcess } else { 0 }
    if ($javaProcess -and $listenerPid -eq [int]$javaProcess.ProcessId) {
        Start-Sleep -Seconds 3
        try {
            $resp = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 5
            if ($resp.code -eq 200) {
                $ok = $true
                $javaProcess.ProcessId | Out-File -FilePath $PID_FILE -Encoding utf8
                break
            }
        } catch {}
    }
    Start-Sleep -Seconds 2
}

if ($ok) {
    Write-Host "Backend started OK, PID: $javaProcess.ProcessId"
    Write-Host "URL: http://localhost:8080"
} else {
    $javaProcess = Find-BackendJavaProcess -ArgFile $argFile -MainClass "com.gameplatform.GamePlatformApplication"
    if ($javaProcess) {
        $javaProcess.ProcessId | Out-File -FilePath $PID_FILE -Encoding utf8
        Write-Host "Backend Java is still starting, PID: $($javaProcess.ProcessId)"
    } else {
        Write-Host "Backend failed to start"
    }
    Write-Host "Log: $APP_LOG_FILE"
    if (-not $javaProcess) {
        exit 1
    }
}
