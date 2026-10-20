# Spec: L4D2 地图热重载参数契约修复

Status: ready-for-agent

## Problem

地图管理页点击“热重载”时，前端把 `instanceId` 放在 JSON 请求体中，
而 `POST /api/plugin/l4d2/maps/hot-reload` 后端只接受同名 query 参数，
请求返回 `HTTP 400` / `缺少请求参数: instanceId`。

## Scope

- 对齐前端 `mapApi.hotReload` 与后端 `MapController.hotReload` 的参数契约。
- 增加前端 API 回归测试，防止再次把 query 参数放入 body。
- 不改变热重载业务逻辑、RCON 命令或地图管理其他接口。

## Acceptance

- 点击地图热重载发出的请求包含 `?instanceId=<id>`，不再依赖 JSON body。
- 前端定向测试通过。
- 插件前端生产构建通过。
- 部署后真实请求不再返回“缺少请求参数: instanceId”。
