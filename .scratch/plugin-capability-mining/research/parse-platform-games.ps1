# 扫描平台 games/*.yml，统计每个游戏已声明的能力面
$dir = 'D:\program\ai\game_platform_manger\backend\core\src\main\resources\games'
$rows = @()

foreach ($f in Get-ChildItem -Path $dir -Filter '*.yml' | Sort-Object Name) {
    $c = Get-Content -LiteralPath $f.FullName -Raw
    $code = $f.BaseName
    $name = [regex]::Match($c, '(?m)^  name:\s*(.+)$').Groups[1].Value.Trim().Trim('"')

    # deployTypes：取 deployTypes: 到下一个同级 key 之间的行
    $dt = @()
    $dtBlock = [regex]::Match($c, '(?ms)^  deployTypes:(.*?)^  [a-zA-Z]')
    if ($dtBlock.Success) {
        $dt = ($dtBlock.Groups[1].Value -split "`r?`n" |
            Where-Object { $_ -match '^\s+- [a-z]+.*$' } |
            ForEach-Object { ($_ -replace '^\s+- ', '').Trim() })
    }

    # configSchema.properties 数量
    $cfgCount = ([regex]::Matches($c, '(?m)^      [a-zA-Z0-9_]+:\s*$')).Count
    $hasCfg   = $(if ($c -match '(?m)^  configSchema:') { 'Y' } else { '-' })

    # customOperations
    $ops = @()
    if ($c -match '(?m)^  customOperations:') {
        $ops = ([regex]::Matches($c, '(?m)^    - name:\s*(.+)$') | ForEach-Object { $_.Groups[1].Value.Trim() })
    }

    # 关键配置项
    $rconKey = $(if ($c -match '(?m)^      rconPassword:') { 'Y' } else { '-' })
    $ports   = ([regex]::Matches($c, '(?m)^    [a-zA-Z]+:\s*\d+')).Count

    $rows += [pscustomobject]@{
        code     = $code
        name     = $name
        deploys  = ($dt -join '/')
        cfgSchema= $hasCfg
        cfgKeys  = $cfgCount
        rconKey  = $rconKey
        ports    = $ports
        ops      = $ops.Count
        opNames  = ($ops -join ', ')
    }
}

$rows | Export-Csv -LiteralPath (Join-Path $PSScriptRoot 'platform-games.csv') -NoTypeInformation -Encoding UTF8

"总计游戏: $($rows.Count)"
"有 configSchema: $(($rows | Where-Object cfgSchema -eq 'Y').Count)"
"有 customOperations: $(($rows | Where-Object ops -gt 0).Count)"
"有 rconPassword 配置键: $(($rows | Where-Object rconKey -eq 'Y').Count)"
""
$sectionTitle = "--- deployTypes 分布 ---"
$sectionTitle
$rows | Group-Object deploys | Sort-Object Count -Descending |
    Select-Object Count, @{n = 'deploys'; e = { $_.Name } } | Format-Table -AutoSize | Out-String -Width 160
""
"--- 有 customOperations 的游戏（平台已具备语义化操作） ---"
$rows | Where-Object { $_.ops -gt 0 } | Format-Table code, name, ops, opNames -AutoSize | Out-String -Width 220
