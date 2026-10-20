$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$allScript = Join-Path $root 'scripts/rebuild-restart-all.ps1'
$verifyScript = Join-Path $root 'scripts/verify-deployment.ps1'

foreach ($script in @($allScript, $verifyScript)) {
    if (-not (Test-Path -LiteralPath $script -PathType Leaf)) {
        throw "部署验收脚本不存在: $script"
    }
}

$allContent = Get-Content -LiteralPath $allScript -Raw
$verifyContent = Get-Content -LiteralPath $verifyScript -Raw

if ($allContent -notmatch '\[switch\]\$SkipVerify') {
    throw 'rebuild-restart-all.ps1 必须提供 -SkipVerify 开关'
}
if ($allContent -notmatch '部署后自动验收') {
    throw 'rebuild-restart-all.ps1 必须默认执行部署后自动验收'
}
if ($allContent -notmatch 'verify-deployment\.ps1') {
    throw 'rebuild-restart-all.ps1 必须调用 verify-deployment.ps1'
}

foreach ($required in @(
    '后端管理员登录',
    'l4d2 插件 UI index.html',
    '插件 JAR 包含 UI 产物',
    'l4d2 插件 Playwright 用例',
    'SkipPlaywright'
)) {
    if ($verifyContent -notmatch [regex]::Escape($required)) {
        throw "verify-deployment.ps1 缺少验收契约: $required"
    }
}

Write-Output 'deployment verification contract test passed'
