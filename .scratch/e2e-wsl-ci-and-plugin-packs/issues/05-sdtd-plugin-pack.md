# 05 — sdtd（7 Days to Die）插件用例包

**Status:** ready-for-agent
**Type:** task
**Blocked by:** 04

## 任务

在主仓库 `frontend/e2e/plugins/sdtd/` 建第一个"复制 l4d2 范式"的插件用例包，
验证范式本身可复制（这是本票最重要的产出，比覆盖本身更重要）。

插件仓库在 `D:\program\ai\plugin-7dtd`（`plugin.id=plugin-sdtd`，gameCode=`sdtd`），
用例**不进插件仓库**，理由见 spec.md Part B。

### 目标菜单（来自 `SdtdExtension.getMenus()`，前端路由已核对一致）

`/dashboard` `/console` `/players` `/logs` `/events` `/mods` —— 共 6 个。

### 前置

- 牺牲主机（WSL）凭据：`E2E_TEST_HOST_*`（与现有用例共用）。
- `beforeAll` 经平台 API 造主机 + 部署 sdtd 实例。`drill-legs.spec.js` 已跑通 sdtd 腿
  （linuxgsm-docker，容器名 `e2e-sdtd-lgsm`，端口 27000/27001，rcon 8181），
  **直接复用这套参数**。
- `afterAll` 删实例 + 删主机，零残留。

### 用例范围

1. 进入插件工作区：`/extensions/app/sdtd/dashboard`，Wujie 容器挂载、子应用渲染、
   主侧边栏出现插件菜单组。
2. 6 个菜单页逐个：可进入 + 页面根渲染 + 列表或空态可见（空态必须显式断言"暂无数据"一类文案，
   不允许"页面空白也算过"）。
3. 至少 1 个写操作断言：控制台页发一条命令并断言请求返回成功（sdtd 走游戏自带 HTTP API，
   注意实例可能未真正运行 —— 若未运行则断言可观测的失败态，不强行断言成功）。

### 框架改动

**零改动**。若发现必须改 `e2e/support/`，先停下来在票里记录原因 —— 说明 l4d2 范式不够通用。

## 验收

- [ ] `e2e/plugins/sdtd/` 用例包可独立跑（`npm run e2e -- --attach e2e/plugins/sdtd`）。
- [ ] 6 个菜单页全部有覆盖断言。
- [ ] `e2e/support/` 与 `playwright.config.js` 零改动（或改动已记录理由）。
- [ ] 跑完牺牲主机无残留实例与主机。
- [ ] 更新 `e2e/plugins/README.md`：3 个插件包的实际状态，以及"用例在主仓库不在插件仓库"的说明。
