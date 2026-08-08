# ============================================
# Game Platform Manager - Frontend Status Script (Windows PowerShell)
# Description: Check frontend server status
# Usage: .\status.ps1
# ============================================

# Configuration
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Split-Path -Parent $ScriptDir
$LogDir = Join-Path $ProjectDir "logs"
$PidFile = Join-Path $ProjectDir ".frontend.pid"

# Colors for output
$Green = [System.ConsoleColor]::Green
$Red = [System.ConsoleColor]::Red
$Yellow = [System.ConsoleColor]::Yellow

Write-Host "=========================================="
Write-Host "Game Platform Manager - Frontend Status"
Write-Host "=========================================="
Write-Host ""

# Check if PID file exists
if (-not (Test-Path $PidFile)) {
    Write-Host "Status: " -NoNewline
    Write-Host "STOPPED" -ForegroundColor $Red
    Write-Host "PID File: Not found"
    Write-Host ""
    Write-Host "Service is not running."
    exit 0
}

# Read PID from file
$TargetPid = Get-Content $PidFile -Raw

# Check if process is running
$Process = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
if (-not $Process) {
    Write-Host "Status: " -NoNewline
    Write-Host "STOPPED (Stale PID file)" -ForegroundColor $Yellow
    Write-Host "PID File: $TargetPid (process not running)"
    Write-Host ""
    Write-Host "Service is not running. Cleaning up PID file..."
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

# Get process information
Write-Host "Status: " -NoNewline
Write-Host "RUNNING" -ForegroundColor $Green
Write-Host "PID: $($Process.Id)"
Write-Host "Process Name: $($Process.ProcessName)"
Write-Host "Memory Usage: $([math]::Round($Process.WorkingSet64 / 1MB, 2)) MB"
Write-Host "Start Time: $($Process.StartTime)"
Write-Host ""

# Check port usage
Write-Host "Port Usage:"
Write-Host "-----------"
$Connections = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -in @(5173, 4173) }

if ($Connections) {
    $Connections | ForEach-Object {
        Write-Host "Port $($_.LocalPort) is in use by PID $($_.OwningProcess)"
    }
} else {
    Write-Host "No frontend ports (5173, 4173) are currently in use"
}

Write-Host ""

# Check recent log entries
$LogFile = Join-Path $LogDir "frontend.log"
if (Test-Path $LogFile) {
    Write-Host "Recent Log Entries:"
    Write-Host "-------------------"
    Get-Content $LogFile -Tail 5
    Write-Host ""
}

Write-Host "=========================================="
