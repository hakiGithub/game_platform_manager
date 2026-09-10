# 05 — l4d2 子应用次要页面渲染用例补齐（约 8 条）

**Status:** needs-triage

## 背景

票 07 已覆盖 l4d2 子应用 6 个主路径页（仪表盘/RCON/地图管理/SourceMod 插件/服务器配置/重启管理）。子应用共 **17 个路由**（backend/plugin-l4d2/frontend/src/router/index.ts），以下页面尚无用例。

## 要补的用例（每页一条"可达渲染 + 关键交互冒烟"）

| 路由 | 页面 | 冒烟断言建议 |
|------|------|--------------|
| /monitor | 系统监控 | 页面标题/指标区块渲染 |
| /logs | 日志 | 日志区渲染、刷新可点 |
| /player-stats | 玩家统计 | 面板渲染（空数据态） |
| /admins | 管理员 | 列表/空态渲染 |
| /server-info | 服务器信息 | 信息字段渲染 |
| /preset | 预设场景 | 预设卡片/列表渲染 |
| /download | 下载管理 | 列表/空态渲染 |
| /map-center | 地图中心 | 页面可达、区块渲染（数据依赖三方源，容忍加载失败提示） |

## 实施要点

- 复用 `frontend/e2e/plugins/l4d2/plugin-subapp.spec.js` 的共享 fixture 模式（beforeAll API 部署 l4d2 运行实例 + afterAll 清理）；若票 02（addons 可见性）已修，可直接扩展现有文件。
- 选择器事实已探明：`backend/plugin-l4d2/frontend/src/pages/*` 各页根元素类名（`.dashboard-page` 风格）。
- 每条用例头按既有格式声明"页面/用例/前置/步骤/通过标准"。

## 验收

- [ ] 上述 8 条用例绿（挂到 `npm run e2e:round` 插件分组）
- [ ] 整轮仍 PASS
