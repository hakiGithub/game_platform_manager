# 04: 生存/沙盒类调研（palworld / valheim / rust / ark / pz / sdtd / dst / dayz 等）

Status: ready-for-agent
Type: research
Blocked by: 01

## 范围

gameCode：`palworld` `valheim` `rust` `ark` `pz` `sdtd` `dst` `dayz` `unt` `sm`（Soulmask）`eco` `hz`
`mh`（MORDHAU，非生存但同属"无 SourceMod 生态"）`rw` `sb` `terraria` `stn` `ti` `dodr` `dys`。

## 调研问题

1. 这批游戏的**管理协议面**分别是什么：
   - Palworld：RCON（`thijsvanloef` 镜像用 `RCON_ENABLED` + `rcon-cli`）+ REST（`restapi`？），端口契约？
   - Rust：RCON（WebSocket，`Facepunch` 协议）+ Oxide/UMod 插件生态，命令语义？
   - Valheim / Ark / PZ / 7DTD / DST / DayZ：各自是 RCON、Telnet、HTTP API，还是"只能改配置文件+重启"？
2. 存档/世界管理：Palworld `Level.sav` 可用 `cheahjs/palworld-save-tools` 解析，Valheim `.db`/`.fwl`、
   PZ 存档、7DTD 世界目录各自有什么成熟工具？这些工具的运行时依赖（Python/.NET）对平台主机
   （ADR-0021 主机工具安装）意味着什么？
3. 自动化运维能力：定时备份、自动更新、崩溃自愈、无人时自动关服（Palworld 镜像的
   `BACKUP_CRON_EXPRESSION` 之类），哪些是镜像自带 vs 需要平台编排？
4. 玩家/管理员管理：白名单、管理员名单、封禁（SteamID/EOS ID）、离线消息，各自落哪些文件或数据库？
5. `fpsacha/zomboid-control-panel`（PZ）这类"单游戏专用面板"的形态，对平台"一个游戏一个插件"的
   粒度有什么启示？它做了哪些平台没有的功能？

## 输出

`.scratch/plugin-capability-mining/research/03-survival-family.md`。因为游戏多，允许按游戏分小节，
但每节必须回答上面 5 个问题的对应部分，并标"无成熟工具"时也要给出核验证据（搜过了、没有）。

## 验收

- [ ] 至少覆盖 palworld / valheim / rust / pz 四个重点游戏，每个有 ≥2 个已核验上游项目。
- [ ] 每个游戏的"管理协议"字段明确（RCON / REST / 仅文件 / 无），不写"可能"。
- [ ] 存档工具链标注了运行时依赖，并对照 ADR-0021 判断可行性。
