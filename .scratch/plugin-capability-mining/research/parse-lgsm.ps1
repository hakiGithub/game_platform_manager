# 从 LinuxGSM 的 _default.cfg 中提取每个游戏的真实能力字段
$raw = Join-Path $PSScriptRoot '_raw'
$rows = @()

foreach ($f in Get-ChildItem -Path $raw -Filter 'lgsm-*.cfg' | Sort-Object Name) {
    $code = $f.Name -replace '^lgsm-', '' -replace '\.cfg$', ''
    $c = Get-Content -LiteralPath $f.FullName -Raw

    $gamename = [regex]::Match($c, '(?m)^gamename="([^"]*)"').Groups[1].Value
    $engine   = [regex]::Match($c, '(?m)^engine="([^"]*)"').Groups[1].Value
    $querytype= [regex]::Match($c, '(?m)^querytype="([^"]*)"').Groups[1].Value
    $port     = [regex]::Match($c, '(?m)^port="([^"]*)"').Groups[1].Value
    $queryport= [regex]::Match($c, '(?m)^queryport="([^"]*)"').Groups[1].Value
    $rconport = [regex]::Match($c, '(?m)^rconport="([^"]*)"').Groups[1].Value
    $rconpwd  = [regex]::Match($c, '(?m)^rconpassword="([^"]*)"').Groups[1].Value
    $maxplay  = [regex]::Match($c, '(?m)^maxplayers="([^"]*)"').Groups[1].Value
    $defmap   = [regex]::Match($c, '(?m)^defaultmap="([^"]*)"').Groups[1].Value
    $svrlevel = [regex]::Match($c, '(?m)^serverlevel="([^"]*)"').Groups[1].Value
    $appid    = [regex]::Match($c, '(?m)^appid="([^"]*)"').Groups[1].Value
    $stopmode = [regex]::Match($c, '(?m)^stopmode="([^"]*)"').Groups[1].Value
    $servercfg= [regex]::Match($c, '(?m)^servercfg="([^"]*)"').Groups[1].Value
    $systemdir= [regex]::Match($c, '(?m)^systemdir="([^"]*)"').Groups[1].Value
    $start    = [regex]::Match($c, '(?m)^startparameters="([^"]*)"').Groups[1].Value

    $rows += [pscustomobject]@{
        code       = $code
        gamename   = $gamename
        engine     = $engine
        querytype  = $querytype
        port       = $port
        queryport  = $queryport
        rconport   = $rconport
        rcon       = $(if ($rconpwd) { 'Y' } else { '-' })
        maxplayers = $maxplay
        map        = $(if ($defmap) { $defmap } elseif ($svrlevel) { $svrlevel } else { '' })
        workshop   = $(if ($c -match '(?m)^workshopmods=') { 'Y' } else { '-' })
        appid      = $appid
        cfgfile    = $servercfg
        systemdir  = $systemdir
        stopmode   = $stopmode
        startlen   = $start.Length
    }
}

$rows | Sort-Object engine, code | Format-Table code, gamename, engine, querytype, port, queryport, rconport, rcon, workshop, appid -AutoSize | Out-String -Width 240

"--- 明细（配置文件 / 系统目录 / 停止模式） ---"
$rows | Sort-Object code | Format-Table code, cfgfile, systemdir, stopmode, maxplayers, map -AutoSize | Out-String -Width 240

$rows | Export-Csv -LiteralPath (Join-Path $PSScriptRoot 'lgsm-capabilities.csv') -NoTypeInformation -Encoding UTF8
