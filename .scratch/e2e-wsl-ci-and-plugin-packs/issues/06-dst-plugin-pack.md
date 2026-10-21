# 06 — dst（饥荒联机版）插件用例包

**Status:** ready-for-agent
**Type:** task
**Blocked by:** 05

## 任务

按票 05 验证过的范式，建 `frontend/e2e/plugins/dst/`。

插件仓库在 `D:\program\ai\plugin-dst`（`plugin.id=plugin-dst`，gameCode=`dst`）。

### 目标菜单（来自 `DstExtension.getMenus()`）

`/console` `/settings` `/players` `/player-stats` `/mods` `/backup` `/schedules` —— 共 7 个。

### 已知环境限制（既有事实，不要重新踩）

`drill-legs.spec.js` 里 dst 腿标了 `needsToken: true`：**DST 无 cluster token 会启动即退**
（token 需 Steam 账号生成，无法自动化注入）。因此：

- 部署可断言"完成（离开安装中）"，**启停类断言需显式 SKIP 并注明原因**。
- 依赖运行中服务器的页面能力（控制台发命令、玩家列表有数据）按实际状态分支：
  未运行时断言可观测的空态/离线提示，不强行断言成功。
- 参考 `host-hygiene.js` 的 `waitForLinuxGsmReady`：容器内 GitHub 不通会秒级失败，
  届时整组 SKIP。

### 用例范围

1. 进入插件工作区 + Wujie 容器挂载。
2. 7 个菜单页逐个：可进入 + 页面根渲染 + 列表或显式空态。
3. 写操作：房间设置页改一项配置并保存，经平台 API 回读确认（DST 配置走 `IniFile` 解析，
   有 `mergeAndRender` 保留未知键，可断言"改的键变了、未改的键还在"）。

## 验收

- [ ] `e2e/plugins/dst/` 可独立跑。
- [ ] 7 个菜单页全部有覆盖断言。
- [ ] 依赖运行中服务器的断言在无 token 时显式 SKIP 并注明原因，不静默失败。
- [ ] 跑完无残留。
- [ ] `e2e/plugins/README.md` 同步状态。
