<#
.SYNOPSIS
    Pack all plugin directories under l4d2-server-next-master/plugins into ZIPs,
    output to plugin-l4d2-core/src/main/resources/builtin-plugins/.

.DESCRIPTION
    - Source: d:\program\open_source\l4d2-server-next-master\plugins
    - Destination: backend/plugin-l4d2/plugin-l4d2-core/src/main/resources/builtin-plugins
    - ZIP internal structure: <pluginName>/left4dead2/... (with base directory)
    - Existing ZIPs are skipped unless -Force is specified
    - Also copies existing platform-plugins/*.zip to builtin-plugins/ if found

.PARAMETER Force
    Overwrite existing ZIP files

.EXAMPLE
    .\pack-builtin-plugins.ps1
    .\pack-builtin-plugins.ps1 -Force
#>
[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

# Use ASCII-only paths to avoid PowerShell 5.1 GBK encoding issues with Chinese chars
$sourceBase = 'd:\program\open_source\l4d2-server-next-master\plugins'
$destBase = Join-Path $PSScriptRoot 'plugin-l4d2-core\src\main\resources\builtin-plugins'
$oldPlatformDir = Join-Path $PSScriptRoot 'plugin-l4d2-core\src\main\resources\platform-plugins'

if (-not (Test-Path $sourceBase)) {
    Write-Error "Source directory not found: $sourceBase"
    exit 1
}

if (-not (Test-Path $destBase)) {
    New-Item -ItemType Directory -Path $destBase -Force | Out-Null
    Write-Host "Created destination: $destBase"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

# 1. Copy existing platform plugin ZIPs from platform-plugins/ to builtin-plugins/
if (Test-Path $oldPlatformDir) {
    Get-ChildItem -Path $oldPlatformDir -Filter '*.zip' | ForEach-Object {
        $destZip = Join-Path $destBase $_.Name
        if (-not (Test-Path $destZip) -or $Force) {
            Copy-Item -Path $_.FullName -Destination $destZip -Force
            Write-Host "Copied from platform-plugins/: $($_.Name)"
        } else {
            Write-Host "Already exists in builtin-plugins/, skip: $($_.Name)"
        }
    }
}

# 2. Pack each plugin directory into a ZIP
$plugins = Get-ChildItem -Path $sourceBase -Directory
$count = 0
$skipped = 0
$failed = 0

foreach ($plugin in $plugins) {
    $zipName = $plugin.Name + '.zip'
    $zipPath = Join-Path $destBase $zipName

    if (Test-Path $zipPath -and -not $Force) {
        $skipped++
        continue
    }

    try {
        if (Test-Path $zipPath) {
            Remove-Item $zipPath -Force
        }
        # 4th param $true = includeBaseDirectory, preserves top-level dir name
        # so ZIP internal structure is <pluginName>/left4dead2/...
        [System.IO.Compression.ZipFile]::CreateFromDirectory(
            $plugin.FullName,
            $zipPath,
            [System.IO.Compression.CompressionLevel]::Optimal,
            $true
        )
        $count++
        Write-Host "Packed: $zipName"
    } catch {
        $failed++
        Write-Host "FAILED: $zipName - $($_.Exception.Message)" -ForegroundColor Red
    }
}

# 3. Summary
$totalZips = (Get-ChildItem $destBase -Filter *.zip).Count
$totalSize = (Get-ChildItem $destBase -Filter *.zip | Measure-Object -Property Length -Sum).Sum
$totalSizeMB = [math]::Round($totalSize / 1MB, 2)

Write-Host ''
Write-Host '========================================='
Write-Host "Packed:   $count"
Write-Host "Skipped:  $skipped"
Write-Host "Failed:   $failed"
Write-Host "Total ZIPs in builtin-plugins/: $totalZips"
Write-Host "Total size: $totalSizeMB MB"
Write-Host '========================================='
