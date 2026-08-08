# ============================================
# Game Platform Manager - Frontend Restart Script (Windows PowerShell)
# Description: Restart frontend server
# Usage: .\restart.ps1 [-Mode <dev|prod>] [-Port <port>]
# ============================================

param(
    [ValidateSet("dev", "prod")]
    [string]$Mode = "dev",

    [int]$Port = 0
)

# Configuration
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Split-Path -Parent $ScriptDir
$LogDir = Join-Path $ProjectDir "logs"

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
Write-Log "Game Platform Manager - Frontend Restart"
Write-Log "=========================================="

# Stop the service
Write-Log "Stopping frontend server..."
$StopScript = Join-Path $ScriptDir "stop.ps1"
& $StopScript

# Wait for complete shutdown
Start-Sleep -Seconds 2

# Start the service
Write-Log "Starting frontend server..."
$StartScript = Join-Path $ScriptDir "start.ps1"

# Build arguments
$StartArgs = @{}
if ($Mode) { $StartArgs["Mode"] = $Mode }
if ($Port -gt 0) { $StartArgs["Port"] = $Port }

& $StartScript @StartArgs

if ($LASTEXITCODE -ne 0) {
    Write-Log "ERROR: Failed to start frontend server"
    exit 1
}

Write-Log "Frontend server restarted successfully"
