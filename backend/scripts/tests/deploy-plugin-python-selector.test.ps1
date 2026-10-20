$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$scriptPath = Join-Path $root 'scripts\deploy-plugin.sh'
$content = Get-Content -LiteralPath $scriptPath -Raw

if ($content -notmatch 'for candidate in python python3') {
    throw 'deploy-plugin.sh must probe python before python3 for Git Bash compatibility'
}

if ($content -notmatch 'import json,sys') {
    throw 'deploy-plugin.sh must verify the selected Python interpreter is executable'
}

if ($content -match 'command -v python3 \|\| command -v python') {
    throw 'deploy-plugin.sh must not prefer the Windows Store python3 alias over python'
}

Write-Output 'deploy-plugin Python selector contract test passed'
