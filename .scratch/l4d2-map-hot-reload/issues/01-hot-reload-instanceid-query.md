# 01: 地图热重载请求缺少 instanceId query 参数

Status: resolved
Type: bug
来源: 用户反馈（2026-10-21）

## 现象

地图管理页点击“热重载”，接口返回：

```text
热重载失败：缺少请求参数: instanceId
```

真实请求：

```http
POST /api/plugin/l4d2/maps/hot-reload
Content-Type: application/json

{"instanceId":97}
```

响应：

```json
{"code":400,"message":"缺少请求参数: instanceId","data":null}
```

## 根因

`MapController.hotReload(...)` 使用 `@RequestParam Long instanceId`，只从 query
读取参数；前端 `mapApi.hotReload` 使用 JSON body 调用。

## 期望

- 前端请求改为 `POST /maps/hot-reload?instanceId=<id>`。
- 保持后端控制器现有 query 契约，不扩大到其他地图接口。
- 增加 API 单元测试断言 `instanceId` 出现在 URL，而不是请求体。

## 验收

- [x] 前端热重载请求 URL 包含 `instanceId`。
- [x] 前端定向测试通过。
- [x] 插件前端生产构建通过。
- [x] 部署后真实热重载请求不再返回缺少参数错误。

## Comments

- 2026-10-21：已复现真实接口返回 `code:400`、`缺少请求参数: instanceId`；
  根因是 query/body 参数位置不一致。
- 2026-10-21：`mapApi.hotReload` 改为 query 传参；新增 API 契约测试，
  断言 URL 含 `instanceId=97` 且请求体为空。`index.test.ts` 7/7 通过，
  插件前端生产构建通过。
