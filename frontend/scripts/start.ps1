# ============================================
# Game Platform Manager - Frontend Start Script (Windows PowerShell)
# Description: Start frontend development server or production server
# Usage: .\start.ps1 [-Mode <dev|prod>] [-Port <port>]
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
$PidFile = Join-Path $ProjectDir ".frontend.pid"
$DefaultDevPort = 5173
$DefaultProdPort = 4173

# Set default port based on mode
if ($Port -eq 0) {
    $Port = if ($Mode -eq "prod") { $DefaultProdPort } else { $DefaultDevPort }
}

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

# Check if service is already running
function Test-ServiceRunning {
    if (Test-Path $PidFile) {
        $ExistingPid = Get-Content $PidFile -Raw
        $Process = Get-Process -Id $ExistingPid -ErrorAction SilentlyContinue
        if ($Process) {
            Write-Log "Service is already running with PID $ExistingPid"
            return $true
        } else {
            Write-Log "Stale PID file found, cleaning up..."
            Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
        }
    }
    return $false
}

# Start development server
function Start-DevServer {
    Write-Log "Starting development server on port $Port..."

    Set-Location $ProjectDir

    # Check if node_modules exists
    if (-not (Test-Path "node_modules")) {
        Write-Log "Installing dependencies..."
        npm install
        if ($LASTEXITCODE -ne 0) {
            Write-Log "ERROR: Failed to install dependencies"
            exit 1
        }
    }

    # Start Vite dev server
    $LogPath = Join-Path $LogDir "dev-server.log"

    # Create start info for background process
    $ProcessInfo = New-Object System.Diagnostics.ProcessStartInfo
    $ProcessInfo.FileName = "npm"
    $ProcessInfo.Arguments = "run dev -- --port $Port"
    $ProcessInfo.UseShellExecute = $false
    $ProcessInfo.RedirectStandardOutput = $true
    $ProcessInfo.RedirectStandardError = $true
    $ProcessInfo.WorkingDirectory = $ProjectDir
    $ProcessInfo.CreateNoWindow = $true

    # Start process
    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $ProcessInfo

    # Redirect output to log file
    $Process.Add_OutputDataReceived({
        param($sender, $e)
        if ($e.Data) {
            Add-Content -Path (Join-Path $using:LogDir "dev-server.log") -Value $e.Data
        }
    }) | Out-Null

    $Process.Add_ErrorDataReceived({
        param($sender, $e)
        if ($e.Data) {
            Add-Content -Path (Join-Path $using:LogDir "dev-server.log") -Value $e.Data
        }
    }) | Out-Null

    $Process.Start() | Out-Null
    $Process.BeginOutputReadLine()
    $Process.BeginErrorReadLine()

    # Save PID
    $Process.Id | Out-File -FilePath $PidFile -Encoding utf8

    # Wait for server to start
    Start-Sleep -Seconds 3

    # Verify process is running
    $RunningProcess = Get-Process -Id $Process.Id -ErrorAction SilentlyContinue
    if ($RunningProcess) {
        Write-Log "Development server started successfully with PID $($Process.Id) on port $Port"
        Write-Log "Access URL: http://localhost:$Port"
    } else {
        Write-Log "WARNING: Server process may have failed to start. Check logs for details."
    }
}

# Start production server
function Start-ProdServer {
    Write-Log "Starting production server on port $Port..."

    Set-Location $ProjectDir

    # Check if dist directory exists
    if (-not (Test-Path "dist")) {
        Write-Log "Building production files..."
        npm run build
        if ($LASTEXITCODE -ne 0) {
            Write-Log "ERROR: Failed to build production files"
            exit 1
        }
    }

    # Check if serve is installed
    $ServeInstalled = Get-Command serve -ErrorAction SilentlyContinue
    if (-not $ServeInstalled) {
        Write-Log "Installing serve package..."
        npm install -g serve
    }

    # Start static file server
    $LogPath = Join-Path $LogDir "prod-server.log"

    $ProcessInfo = New-Object System.Diagnostics.ProcessStartInfo
    $ProcessInfo.FileName = "serve"
    $ProcessInfo.Arguments = "-s dist -l $Port"
    $ProcessInfo.UseShellExecute = $false
    $ProcessInfo.RedirectStandardOutput = $true
    $ProcessInfo.RedirectStandardError = $true
    $ProcessInfo.WorkingDirectory = $ProjectDir
    $ProcessInfo.CreateNoWindow = $true

    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $ProcessInfo

    $Process.Add_OutputDataReceived({
        param($sender, $e)
        if ($e.Data) {
            Add-Content -Path (Join-Path $using:LogDir "prod-server.log") -Value $e.Data
        }
    }) | Out-Null

    $Process.Add_ErrorDataReceived({
        param($sender, $e)
        if ($e.Data) {
            Add-Content -Path (Join-Path $using:LogDir "prod-server.log") -Value $e.Data
        }
    }) | Out-Null

    $Process.Start() | Out-Null
    $Process.BeginOutputReadLine()
    $Process.BeginErrorReadLine()

    # Save PID
    $Process.Id | Out-File -FilePath $PidFile -Encoding utf8

    Start-Sleep -Seconds 2

    $RunningProcess = Get-Process -Id $Process.Id -ErrorAction SilentlyContinue
    if ($RunningProcess) {
        Write-Log "Production server started successfully with PID $($Process.Id) on port $Port"
        Write-Log "Access URL: http://localhost:$Port"
    } else {
        Write-Log "WARNING: Server process may have failed to start. Check logs for details."
    }
}

# Main execution
Write-Log "=========================================="
Write-Log "Game Platform Manager - Frontend Start"
Write-Log "Mode: $Mode"
Write-Log "Port: $Port"
Write-Log "=========================================="

if (Test-ServiceRunning) {
    exit 1
}

if ($Mode -eq "prod") {
    Start-ProdServer
} else {
    Start-DevServer
}
