# ============================================
# Game Platform Manager - Frontend Stop Script (Windows PowerShell)
# Description: Stop frontend server
# Usage: .\stop.ps1
# ============================================

# Configuration
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Split-Path -Parent $ScriptDir
$LogDir = Join-Path $ProjectDir "logs"
$PidFile = Join-Path $ProjectDir ".frontend.pid"

# Create log directory if not exists
if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
}

# Log function
function Write-Log {
    param([string]$Message)
    $Timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $LogMessage = "[$Timestamp] $Message"
    $LogFile = Join-Path $LogDir "frontend.log"
    Add-Content -Path $LogFile -Value $LogMessage
    Write-Host $LogMessage
}

# Main execution
Write-Log "=========================================="
Write-Log "Game Platform Manager - Frontend Stop"
Write-Log "=========================================="

# Check if PID file exists
if (-not (Test-Path $PidFile)) {
    Write-Log "No PID file found. Service may not be running."

    # Try to find and kill node processes related to frontend
    Write-Log "Attempting to find and stop any running frontend processes..."

    $NodeProcesses = Get-Process -Name "node" -ErrorAction SilentlyContinue
    foreach ($Proc in $NodeProcesses) {
        try {
            Stop-Process -Id $Proc.Id -Force -ErrorAction SilentlyContinue
            Write-Log "Stopped node process with PID $($Proc.Id)"
        } catch {
            # Ignore errors
        }
    }

    exit 0
}

# Read PID from file
$TargetPid = Get-Content $PidFile -Raw

# Check if process is running
$Process = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
if (-not $Process) {
    Write-Log "Process with PID $TargetPid is not running. Cleaning up PID file..."
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

# Stop the process
Write-Log "Stopping process with PID $TargetPid..."

# Try graceful shutdown first
try {
    Stop-Process -Id $TargetPid -ErrorAction Stop
    Start-Sleep -Seconds 3
} catch {
    Write-Log "Graceful shutdown failed, forcing termination..."
}

# Check if process still running
$Process = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
if ($Process) {
    Write-Log "Process still running, forcing termination..."
    Stop-Process -Id $TargetPid -Force -ErrorAction SilentlyContinue
}

# Verify process stopped
$Process = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
if ($Process) {
    Write-Log "ERROR: Failed to stop process with PID $TargetPid"
    exit 1
} else {
    Write-Log "Process stopped successfully"
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
}

Write-Log "Frontend server stopped successfully"
