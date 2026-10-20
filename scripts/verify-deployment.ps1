<#
.SYNOPSIS
    Game Platform Manager - 部署后自动验收脚本 (Windows PowerShell)

.DESCRIPTION
    对已启动的平台执行四项自动验收：
      1. 后端管理员登录接口
      2. l4d2 插件 UI index.html
      3. 已部署插件 JAR 中的 UI 产物
      4. l4d2 插件 Playwright 用例

    该脚本不启动或停止服务，只做验证。全栈重部署脚本默认在服务启动后调用。

.PARAMETER BackendUrl
    后端地址（默认 http://127.0.0.1:8080）

.PARAMETER FrontendUrl
    前端地址（默认从 logs/frontend.port 读取，回退 http://127.0.0.1:3000）

.PARAMETER SkipPlaywright
    跳过浏览器用例，只做接口与产物验证

.EXAMPLE
    .\scripts\verify-deployment.ps1

.NOTES
    作者: Game Platform Manager
    创建日期: 2026-10-20
#>

param(
    [string]$BackendUrl = "http://127.0.0.1:8080",

    [string]$FrontendUrl = "",

    [switch]$SkipPlaywright
)

$ErrorActionPreference = "Stop"

$script:SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $SCRIPT_DIR) { $script:SCRIPT_DIR = $PSScriptRoot }
$script:PROJECT_DIR = Split-Path -Parent $SCRIPT_DIR
$script:FRONTEND_DIR = Join-Path $PROJECT_DIR "frontend"
$script:PLUGINS_DIR = Join-Path $PROJECT_DIR "backend\plugins"

function Write-Check {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [scriptblock]$Check
    )

    try {
        & $Check
        Write-Host "[PASS] $Name"
    } catch {
        Write-Host "[FAIL] $Name"
        throw
    }
}

if (-not $FrontendUrl) {
    $frontendPortFile = Join-Path $PROJECT_DIR "logs\frontend.port"
    $frontendPort = 3000
    if (Test-Path -LiteralPath $frontendPortFile) {
        $portText = (Get-Content -LiteralPath $frontendPortFile -ErrorAction SilentlyContinue |
            Select-Object -First 1)
        if ($portText -match '^\d+$') {
            $frontendPort = [int]$portText
        }
    }
    $FrontendUrl = "http://127.0.0.1:$frontendPort"
}

Write-Host "部署验收: backend=$BackendUrl frontend=$FrontendUrl"

Write-Check "后端管理员登录" {
    $body = @{ username = 'admin'; password = 'admin123' } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "$BackendUrl/api/auth/login" `
        -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 10
    if ($login.code -ne 200 -or -not $login.data.token) {
        throw "登录接口未返回有效 token: $($login | ConvertTo-Json -Compress)"
    }
}

Write-Check "l4d2 插件 UI index.html" {
    $body = @{ username = 'admin'; password = 'admin123' } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "$BackendUrl/api/auth/login" `
        -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 10
    $headers = @{ Authorization = "Bearer $($login.data.token)" }
    $ui = Invoke-WebRequest -Uri "$BackendUrl/api/pf4j/plugin/l4d2/ui/index.html" `
        -Headers $headers -TimeoutSec 10
    if ($ui.StatusCode -ne 200 -or $ui.Content -notmatch '<div id="app"></div>') {
        throw "插件 UI 资源异常: status=$($ui.StatusCode), hasApp=$($ui.Content -match '<div id="app"></div>')"
    }
}

Write-Check "插件 JAR 包含 UI 产物" {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jar = Get-ChildItem -LiteralPath $PLUGINS_DIR -Filter "plugin-l4d2-core*.jar" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) {
        throw "未找到已部署插件 JAR: $PLUGINS_DIR"
    }

    $archive = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        $hasIndex = [bool]($archive.Entries | Where-Object { $_.FullName -eq "ui/index.html" })
        $assetCount = ($archive.Entries | Where-Object { $_.FullName -like "ui/assets/*" }).Count
        if (-not $hasIndex -or $assetCount -le 0) {
            throw "插件 JAR UI 产物不完整: index=$hasIndex, assets=$assetCount, jar=$($jar.Name)"
        }
    } finally {
        $archive.Dispose()
    }
}

if (-not $SkipPlaywright) {
    Write-Check "l4d2 插件 Playwright 用例" {
        $playwrightCli = Join-Path $FRONTEND_DIR "node_modules\@playwright\test\cli.js"
        if (-not (Test-Path -LiteralPath $playwrightCli)) {
            throw "未找到 Playwright CLI: $playwrightCli"
        }

        $previousBaseUrl = $env:E2E_BASE_URL
        $previousBackendUrl = $env:E2E_BACKEND_URL
        try {
            $env:E2E_BASE_URL = $FrontendUrl
            $env:E2E_BACKEND_URL = $BackendUrl
            Push-Location $FRONTEND_DIR
            try {
                & node $playwrightCli test `
                    "e2e/main-app/plugins/plugins.spec.js" `
                    "--reporter=list"
            } finally {
                Pop-Location
            }
            if ($LASTEXITCODE -ne 0) {
                throw "Playwright 用例失败 (exit code: $LASTEXITCODE)"
            }
        } finally {
            $env:E2E_BASE_URL = $previousBaseUrl
            $env:E2E_BACKEND_URL = $previousBackendUrl
        }
    }
} else {
    Write-Host "[SKIP] l4d2 插件 Playwright 用例（-SkipPlaywright）"
}

Write-Host "部署验收通过"
