# 05 — l4d2 子应用次要页面渲染用例补齐（约 8 条）

**Status:** resolved

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

- [x] 上述 8 条用例已加入 `frontend/e2e/plugins/l4d2/plugin-subapp.spec.js` 的共享 fixture 套件，
      随 `npm run e2e:round` 的“插件子应用 l4d2”分组执行
- [ ] 整轮 PASS（本环境未能执行：运行中的前端仍提供旧 Wujie 插件文档，
      请求 `/extensions/app/l4d2/assets/index-CSTw2yRc.js` 等旧资源均 404；当前仓库
      `frontend/node_modules` 缺失，且任务要求不重启已有服务）

## Answer

- 在既有 `plugin-subapp.spec.js` 共享 fixture 上补齐 8 条次要页面用例：系统监控、日志、
  玩家统计、管理员、服务器信息、预设场景、下载管理、地图中心。
- 每条用例新增文件头契约（页面/用例/前置/步骤/通过标准），并强化关键交互冒烟：
  monitor 查询历史、logs 刷新文件列表、player-stats 切换玩家列表/游玩时长校验、
  admins 列表或空态与新增必填校验、server-info 信息字段与版本折叠、preset 卡片/空态、
  download 三入口与任务列表/空态、map-center 筛选/爬取状态/列表或加载失败提示。
- 静态验证：`node --check frontend/e2e/plugins/l4d2/plugin-subapp.spec.js` 与
  `git diff --check` 均通过。
- E2E 执行证据：尝试以临时 Playwright 探针对已运行 `instanceId=97` 逐一访问 8 条路由，
  均在 Wujie 外层停在“页面加载似乎遇到了阻碍”边界；网络证据显示浏览器加载的是
  `/extensions/app/l4d2/assets/index-CSTw2yRc.js`、`date-ByRweZPH.js`、
  `style-a92S0HYx.css` 等 404 资源，而服务端当前插件 index 实际引用
  `index-NRy5riBx.js`。这是运行中前端/插件 bundle 版本不一致的环境阻塞，不是本票
  页面实现失败；需重建并重启前端后运行 `npm run e2e:round` 才能给“整轮 PASS”结论。
