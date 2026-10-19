$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$scriptPath = Join-Path $root 'scripts/deploy-plugin.sh'
$content = Get-Content -Raw -LiteralPath $scriptPath

$required = @(
    'HTTP_CODE="$(curl -s -o /dev/null -w ''%{http_code}'' --max-time 3 \',
    'if [[ "${HTTP_CODE}" == "000" ]]; then'
)

foreach ($line in $required) {
    if (-not $content.Contains($line)) {
        throw "deploy-plugin.sh health probe regression: missing '$line'"
    }
}

if ($content -match '(?s)# ---------- 2\. 后端未运行时.*?\[\[ "\$\{HTTP_CODE\}" == "200" \]\]') {
    throw 'deploy-plugin.sh must not treat non-200 responses as offline'
}

Write-Output 'deploy-plugin health probe test passed'
