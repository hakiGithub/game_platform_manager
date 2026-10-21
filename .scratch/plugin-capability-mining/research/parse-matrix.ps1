# 合并 LinuxGSM 真实能力与平台现状，产出"游戏 x 功能"矩阵
$dir = $PSScriptRoot
$lgsm = @{}
foreach ($r in Import-Csv (Join-Path $dir 'lgsm-capabilities.csv')) { $lgsm[$r.code] = $r }

$plat = @{}
foreach ($r in Import-Csv (Join-Path $dir 'platform-games.csv')) { $plat[$r.code] = $r }

# 引擎 -> 该引擎天然支持的能力（依据 LinuxGSM engine/querytype 字段推导）
function Engine-Caps($engine, $querytype) {
    switch ($engine) {
        'source'   { 'RCON|Source查询|SourceMod/MetaMod|Workshop创意工坊|地图/关卡管理|插件配置(.cfg)' }
        'source2'  { 'RCON|Source查询|地图/关卡管理|插件配置(.cfg)' }
        'goldsrc'  { 'RCON(部分)|Source查询|老式地图管理' }
        'unreal3'  { 'WebAdmin(unreal3 内置)|查询|地图管理' }
        'unreal4'  { 'RCON|查询|INI配置(Config层级)|地图管理' }
        'unreal5'  { 'RCON|REST API|INI配置|存档解析' }
        'unity3d'  { '查询|配置文件驱动|存档/世界管理' }
        'lwjgl2'   { 'RCON(MC协议)|查询|server.properties|模组/加载器|世界管理' }
        'bedrock'  { 'server.properties|世界管理' }
        'enfusion' { 'RCON|查询|XML/JSON 任务与经济配置' }
        'factorio' { 'RCON|JSON配置|存档管理' }
        'terraria' { '配置文件|世界管理' }
        'dontstarve' { '配置文件|存档(集群)管理' }
        'eco'      { '配置文件|世界管理' }
        'vintagestory' { '配置文件|世界管理' }
        'teamspeak3' { 'ServerQuery(Telnet)|权限/频道管理' }
        'projectzomboid' { '配置文件|存档管理|Mod管理' }
        default { '配置文件' }
    }
}

$rows = @()
# 优先按 LinuxGSM 有据的一批（34 个），其余标"仅平台元数据"
foreach ($code in ($lgsm.Keys | Sort-Object)) {
    $l = $lgsm[$code]
    $p = $plat[$code]
    $rows += [pscustomobject]@{
        code       = $code
        name       = $(if ($p) { $p.name } else { $l.gamename })
        engine     = $l.engine
        query      = $l.querytype
        rcon       = $l.rcon
        workshop   = $l.workshop
        plat_cfg   = $(if ($p -and $p.cfgSchema -eq 'Y') { "$($p.cfgKeys)键" } else { '-' })
        plat_ops   = $(if ($p) { $p.ops } else { 0 })
        caps       = Engine-Caps $l.engine $l.querytype
    }
}

"=== 按引擎分组：能力面与平台现状 ==="
$rows | Sort-Object engine, code | Format-Table code, name, engine, query, rcon, workshop, plat_cfg, plat_ops, caps -AutoSize | Out-String -Width 260

""
"=== 统计 ==="
"LinuxGSM 有据游戏: $($rows.Count)"
"其中声明 rconpassword: $(($rows | Where-Object rcon -eq 'Y').Count)"
"其中支持 workshop: $(($rows | Where-Object workshop -eq 'Y').Count)"
"其中平台已有 configSchema: $(($rows | Where-Object plat_cfg -ne '-').Count)"
"其中平台已有 customOperations: $(($rows | Where-Object plat_ops -gt 0).Count)"

$rows | Export-Csv (Join-Path $dir 'game-capability-matrix.csv') -NoTypeInformation -Encoding UTF8
