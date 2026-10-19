$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$bashScript = Join-Path $root 'scripts/deploy-plugin.sh'
$powershellScript = Join-Path $root 'backend/scripts/build-and-deploy.ps1'

foreach ($script in @($bashScript, $powershellScript)) {
    if (-not (Test-Path -LiteralPath $script -PathType Leaf)) {
        throw "部署脚本不存在: $script"
    }
}

$bash = Get-Content -LiteralPath $bashScript -Raw
$powershell = Get-Content -LiteralPath $powershellScript -Raw

if ($bash -notmatch 'resolved_jar|find_plugin_jar|resolve.*jar') {
    throw 'deploy-plugin.sh 必须先解析并校验实际构建产物'
}
if ($powershell -notmatch 'SNAPSHOT|Get-ChildItem.*target.*\.jar') {
    throw 'build-and-deploy.ps1 必须兼容带 SNAPSHOT 的 Maven 产物'
}
if ($bash -notmatch 'ROLLBACK|rollback|restore') {
    throw 'deploy-plugin.sh 必须在热部署失败时恢复旧插件'
}

Write-Output 'deploy-plugin artifact contract test passed'
