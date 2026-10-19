# 01: L4D2 切换第三方地图后未生效

Status: needs-triage
Type: bug
来源: 用户反馈（2026-10-19）

## 现象

在 L4D2 实例中请求切换第三方地图 `c1918_red_square_c1` 后，`/rcon/status` 没有返回或保持为该目标地图，服务器仍停留在原地图。

用户提供的顺序：

1. 调用 `POST /api/plugin/l4d2/rcon/change-map`，请求体包含 `{"instanceId":97,"mapName":"c1918_red_square_c1"}`。
2. 随后调用 `POST /api/plugin/l4d2/rcon/status`，请求体包含 `{"instanceId":97}`。
3. `status` 返回的地图不是替换后的三方地图 `c1918_red_square_c1`。

复现入口：

- L4D2 地图管理页切换第三方地图。
- 或直接调用上述两个接口，以状态接口返回的 `map` 为最终判定。

## 期望

- 对已安装且服务器可识别的第三方地图，`change-map` 成功后，`status` 在合理等待时间内返回 `c1918_red_square_c1`。
- 若服务器拒绝或无法加载该地图，接口不能只返回成功；需要给出可定位的失败结果或明确状态。
- 原有地图管理能力（列表展示、选择地图、触发切换）继续可用。

## 已核对的现状

- 前端 `changeMap` 调用 `/rcon/change-map`。
- 后端接口目前只执行 `changelevel <mapName>`，未把 RCON 输出或换图结果作为响应返回。
- 仓库已有相关但不完全相同的问题：Docker addons 地图列表不可见（已记录、状态为 done）以及换图终态未被 E2E 强断言。本 issue 聚焦“接口返回/状态显示未切到指定三方地图”。

## 脱敏复现请求

请求头中的认证信息已移除，字段结构保持不变：

```http
POST /api/plugin/l4d2/rcon/change-map
Accept: */*
Content-Type: application/json
Authorization: Bearer <REDACTED>
Cookie: <REDACTED>

{"instanceId":97,"mapName":"c1918_red_square_c1"}
```

```http
POST /api/plugin/l4d2/rcon/status
Accept: */*
Content-Type: application/json
Authorization: Bearer <REDACTED>
Cookie: <REDACTED>

{"instanceId":97}
```

## 待 triage 确认

- 目标地图是否已实际存在于服务器的 addons/maps 目录，且 `maps *` 能看到该地图？
- `changelevel` 的 RCON 回显是什么？服务器是否返回未知地图或错误？
- 该实例是 Docker 还是原生部署，地图文件路径是否与实际读取路径一致？
- 换图后等待多久再查 `status`？是否与地图加载耗时有关？
- 是否需要轮询到终态，而不是一次请求后立即判断？
- 用户提供的 token/cookie 只用于本次复现，不应写入仓库或继续复用。

## 验收

- [ ] 在目标地图确实已安装的前提下，换图后 `status.map` 最终为 `c1918_red_square_c1`。
- [ ] 无法换图时，接口返回可操作的错误信息，不再仅返回成功空结果。
- [ ] 重复切换或切换回官方地图不会留下错误选中/运行状态。
- [ ] 记录并覆盖一次 `change-map` → `status` 的自动化或手工验证。
- [ ] 地图不存在、实例离线、RCON 不可达三种失败路径都有明确反馈。

## 关联

- 现有相关记录：`.scratch/backlog/issues/02-docker-addons-invisible-to-map-list.md`、`.scratch/backlog/issues/06-rcon-deep-ops-with-live-players.md`。
- 本 issue 仅记录问题，不包含实现。
