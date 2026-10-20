$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$scriptPath = Join-Path $root 'backend/scripts/rebuild-restart.ps1'

if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw "启动脚本不存在: $scriptPath"
}

$content = Get-Content -LiteralPath $scriptPath -Raw

if ($content -notmatch 'function Build-PluginFrontend') {
    throw 'rebuild-restart.ps1 必须构建插件前端'
}

$frontendBuild = $content.IndexOf('Build-PluginFrontend')
$pluginPackage = $content.IndexOf('mvn clean package -pl plugin-l4d2/plugin-l4d2-core')
if ($frontendBuild -lt 0 -or $pluginPackage -lt 0 -or $frontendBuild -gt $pluginPackage) {
    throw 'rebuild-restart.ps1 必须在打包插件 JAR 前构建插件前端'
}

foreach ($required in @(
    'npm ci',
    'npm run build',
    'Join-Path $PLUGIN_L4D2_UI_DIR "index.html"',
    'Join-Path $PLUGIN_L4D2_UI_DIR "assets"',
    '插件前端构建失败'
)) {
    if (-not $content.Contains($required)) {
        throw "rebuild-restart.ps1 缺少插件 UI 构建契约: $required"
    }
}

Write-Output 'rebuild-restart plugin UI contract test passed'
