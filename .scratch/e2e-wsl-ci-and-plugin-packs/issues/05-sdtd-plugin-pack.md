# 05 — sdtd（7 Days to Die）插件用例包

**Status:** resolved
**Type:** task
**Blocked by:** 04（已 resolved）

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

- [x] `e2e/plugins/sdtd/` 用例包可独立跑（`bash scripts/e2e-docker-run.sh e2e/plugins/sdtd`）。
- [x] 6 个菜单页全部有覆盖断言。
- [x] `e2e/support/` 与 `playwright.config.js` 零改动（只新增了用例包目录）。
- [x] 跑完牺牲主机无残留实例与主机。
- [x] 更新 `e2e/plugins/README.md`：4 个插件包的实际状态，以及"用例在主仓库不在插件仓库"的说明。

## 结论（2026-10-22，容器化跑测）

### 实测数据

| 轮次 | 结果 |
|------|------|
| 首轮（`--attach`，栈已在跑） | ✓ 8 passed (28.6s) |
| 次轮（从零起栈） | ✓ 8 passed (27.9s) |

部署这条腿：`linuxgsm-docker`，15 秒进入稳定态（"已停止"，`autoRestart: false`），
容器内 LinuxGSM auto-install 继续在后台下载游戏文件 —— **页面类断言不等它**。

### 覆盖的 6 个菜单

| 菜单 | 断言 |
|------|------|
| `/dashboard` | 四张指标卡标签齐全 + 运行状态非空 + 血月"进行中/剩余天数/暂无数据"三选一 |
| `/console` | 三个快速命令按钮 + 输入框；下发 `gettime` 后历史出现该命令且有非空输出 |
| `/players` | 三 Tab 存在 + 在线玩家表可见 + 数据源标记匹配；切到 `#pane-bans` 可见 |
| `/logs` | 标题 + 刷新打入 `/logs?` 接口；日志行数 > 0 或"暂无日志"明确空态 |
| `/events` | CSMM 上报引导文案 + 三 Tab；切到"进出"后 `aria-selected=true` |
| `/mods` | 已安装列表标题 + 从目录安装/上传 zip/刷新三入口 + file input 存在；目录弹窗打开并回读 catalog |

写操作闭环：`/players` 白名单页经 UI 新增 17 位 SteamID → 平台 API 回读命中 →
UI 列表出现 → 确认框移除 → 平台 API 回读为空。`afterAll` 再兜底清一次
（白名单是插件自管扩展资源，不随实例删除）。

### 范式复制过程中踩到的 4 个坑（已写进用例注释）

1. **主机 IP 全局唯一**：不能像 l4d2 包那样直接 `POST /api/hosts`，同一台牺牲主机
   可能已被别的分组纳管 → 改用 `ensureHostByIp()`，且只删本次新建的记录。
2. **`.plugin-menu` 不是主侧边栏菜单**：它是"有菜单才渲染"的独立组件，当前宿主走
   主侧边栏分组菜单 → 断言改打 `.el-menu` 里的 `menuitem`。
3. **Element Plus tab-pane 懒渲染**：未激活的 pane 仍在 DOM 且 `aria-hidden=true`，
   用位置选择器（`.last()` / `.first()`）会命中隐藏的那个 → 改用 `#pane-xxx` 具名选择器。
4. **`el-upload` 双层 role=button**：外层 div 与内部 button 都匹配 `getByRole("button")`
   → 断言真正的 `<button>` 并额外确认 `input[type=file]` 存在。

这 4 条都不是 l4d2 范式本身的问题，而是"套到别的插件/别的页面组件时"必然遇到的
定位器差异 —— 结论仍是**框架代码零改动**，新增插件包 = 新增一个子目录。
