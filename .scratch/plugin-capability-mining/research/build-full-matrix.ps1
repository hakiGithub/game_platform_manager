# 全量 139 游戏 x 功能矩阵
# 数据来源：1) LinuxGSM _default.cfg（34 个，权威）2) LinuxGSM config-default 目录清单（139 个）
#           3) 平台 games/*.yml（139 个）4) plugin-l4d2 菜单（已实现能力）
$dir = $PSScriptRoot

$lgsmDirs = @'
acserver ahl2server ahlserver arkserver arma3server armarserver atsserver avserver bb2server bbserver
bdserver bf1942server bfvserver bmdmserver boserver bsserver btlserver btserver ccserver ceserver
ckserver cmwserver cod2server cod4server codserver coduoserver codwawserver colserver cs2server
csczserver csgoserver csserver cssserver ctserver dabserver dayzserver dmcserver dodrserver dodserver
dodsserver doiserver dstserver dysserver ecoserver emserver etlserver ets2server fctrserver fofserver
gmodserver hcuserver hl2dmserver hldmserver hldmsserver hwserver hytserver hzserver insserver inssserver
iosserver jbep3server jc2server jc3server jk2server jk3server kf2server kfserver l4d2server l4dserver
mcbserver mcserver mcvserver mhserver mohaaserver momserver mtaserver ndserver necserver nmrihserver
ns2cserver ns2server nsserver ohdserver onsetserver opforserver pc2server pcserver pmcserver pvkiiserver
pvrserver pwserver pzserver q2server q3server q4server qlserver qwserver ricochetserver roserver
rsdwserver rtcwserver rustserver rsdwserver rwrserver rwserver sampserver sbotsserver sbserver
scpslserver sdtdserver sfcserver sfserver smserver sof2server solserver squad44server squadserver
stnserver stserver svenserver terrariaserver tf2cserver tf2server tfcserver tfserver tiserver ts3server
tsserver tuserver twserver untserver ut2k4server ut3server ut99server utserver vhserver vintsserver
vpmcserver vsserver wetserver wfserver wmcserver wurmserver xntserver zmrserver zpsserver
'@ -split '\s+' | Where-Object { $_ }
$lgsmSet = $lgsmDirs | ForEach-Object { $_ -replace 'server$', '' } | Where-Object { $_ }

$lgsm = @{}
foreach ($r in Import-Csv (Join-Path $dir 'lgsm-capabilities.csv')) { $lgsm[$r.code] = $r }
$plat = @{}
foreach ($r in Import-Csv (Join-Path $dir 'platform-games.csv')) { $plat[$r.code] = $r }

# 已知引擎族（LinuxGSM 权威 engine 字段 + 平台命名的同源推断）
$engineOf = @{}
foreach ($k in $lgsm.Keys) { $engineOf[$k] = $lgsm[$k].engine }
# 同源游戏按 LinuxGSM 亲缘补充（同系列同引擎）
$kin = @{
  'l4d'='source'; 'cs'='goldsrc'; 'cscz'='goldsrc'; 'dod'='goldsrc'; 'dods'='source'; 'tfc'='goldsrc'
  'tf2c'='source'; 'hl2dm'='source'; 'hldm'='goldsrc'; 'hldms'='source'; 'dmc'='goldsrc'; 'opfor'='goldsrc'
  'ricochet'='goldsrc'; 'ahl'='goldsrc'; 'ahl2'='source'; 'bmdm'='source'; 'bb'='source'; 'bb2'='source'
  'cc'='source'; 'dab'='source'; 'dys'='source'; 'em'='source'; 'fof'='source'; 'ins'='source'
  'pvkii'='source'; 'sfc'='source'; 'zmr'='source'; 'ns'='goldsrc'; 'ns2'='unity3d'; 'ns2c'='unity3d'
  'ts'='goldsrc'; 'cod'='iw'; 'cod2'='iw'; 'cod4'='iw'; 'coduo'='iw'; 'codwaw'='iw'; 'sof2'='iw'
  'mohaa'='iw'; 'rtcw'='iw'; 'wet'='iw'; 'etl'='iw'; 'q2'='idtech2'; 'q3'='idtech3'; 'q4'='idtech4'
  'ql'='idtech3'; 'qw'='idtech1'; 'jk2'='idtech3'; 'ut'='unreal'; 'ut99'='unreal'; 'ut2k4'='unreal2'
  'ut3'='unreal3'; 'kf'='unreal2'; 'ro'='unreal2'; 'bf1942'='refractor'; 'bfv'='refractor'
  'arma3'='realserv'; 'armar'='realserv'; 'dayz'='enfusion'
}
foreach ($k in $kin.Keys) { if (-not $engineOf.ContainsKey($k)) { $engineOf[$k] = $kin[$k] } }

function Caps($e) {
    switch -Wildcard ($e) {
        'source'   { 'RCON,Source查询,SourceMod/MetaMod,创意工坊,地图管理,cfg配置' }
        'source2'  { 'RCON,Source查询,地图管理,cfg配置' }
        'goldsrc'  { 'RCON(部分),Source查询,地图管理' }
        'unity3d'  { '查询,配置文件,存档/世界管理' }
        'unreal3'  { 'WebAdmin,查询,地图管理,INI配置' }
        'unreal4'  { 'RCON,查询,地图管理,INI配置' }
        'unreal5'  { 'RCON,REST API,INI配置,存档解析' }
        'unreal2'  { '查询,地图管理,INI配置' }
        'unreal'   { '查询,地图管理,INI配置' }
        'lwjgl2'   { 'RCON(MC),查询,server.properties,模组/加载器,世界管理' }
        'bedrock'  { 'server.properties,世界管理' }
        'enfusion' { 'RCON,查询,XML/JSON经济配置,创意工坊' }
        'factorio' { 'RCON,JSON配置,存档管理' }
        'terraria' { '配置文件,世界管理' }
        'dontstarve' { '配置文件,存档(集群)管理' }
        'eco'      { '配置文件,世界管理' }
        'vintagestory' { '配置文件,世界管理' }
        'teamspeak3' { 'ServerQuery,权限/频道管理' }
        'projectzomboid' { '配置文件,存档管理,Mod管理' }
        'iw'       { '查询,配置文件,地图管理' }
        'idtech*'  { '查询,配置文件,地图管理' }
        'refractor'{ '查询,配置文件,地图管理' }
        'realserv' { '查询,配置文件,模组管理' }
        default    { '配置文件' }
    }
}

$rows = @()
foreach ($code in ($plat.Keys | Sort-Object)) {
    $p = $plat[$code]
    $l = $lgsm[$code]
    $e = if ($engineOf.ContainsKey($code)) { $engineOf[$code] } else { '' }
    $rows += [pscustomobject]@{
        code     = $code
        name     = $p.name
        engine   = $e
        lgsmCfg  = $(if ($l) { 'Y' } else { '-' })
        rconStd  = $(if ($l -and $l.rcon -eq 'Y') { 'Y' } else { '-' })
        workshop = $(if ($l -and $l.workshop -eq 'Y') { 'Y' } else { '-' })
        platCfg  = $(if ($p.cfgSchema -eq 'Y') { "$($p.cfgKeys)键" } else { '-' })
        platOps  = $p.ops
        caps     = Caps $e
    }
}

$rows | Export-Csv (Join-Path $dir 'full-matrix.csv') -NoTypeInformation -Encoding UTF8

"总计: $($rows.Count)"
"引擎已识别: $(($rows | Where-Object engine -ne '').Count) / 未识别: $(($rows | Where-Object engine -eq '').Count)"
""
"--- 按引擎族的规模（决定插件复用的杠杆） ---"
$rows | Where-Object engine -ne '' | Group-Object engine | Sort-Object Count -Descending |
    Select-Object Count, @{n='engine';e={$_.Name}} | Format-Table -AutoSize | Out-String
""
"--- 平台已深度支持（有 configSchema 或 ops） ---"
$rows | Where-Object { $_.platCfg -ne '-' -or $_.platOps -gt 0 } |
    Format-Table code, name, engine, platCfg, platOps -AutoSize | Out-String
