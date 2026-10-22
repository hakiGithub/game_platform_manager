# 06 — dst（饥荒联机版）插件用例包

**Status:** resolved
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

- [x] `e2e/plugins/dst/` 可独立跑。
- [x] 7 个菜单页全部有覆盖断言。
- [x] 依赖运行中服务器的断言在无 token 时显式 SKIP 并注明原因，不静默失败。
- [x] 跑完无残留。
- [x] `e2e/plugins/README.md` 同步状态。

## 落地结果（2026-10-22）

`frontend/e2e/plugins/dst/`：`plugin-subapp.spec.js`（8 条）+ `README.md`。
连续两轮全绿：**8/8（1.2m，`--attach` 复用栈）/ 8/8（1.7m，冷启动起栈）**。

| # | 用例 | 断言落点 |
|---|------|---------|
| 1 | 进入工作区 | Wujie 容器 + `.dst-console` + 主侧边栏 7 个菜单项 |
| 2 | 控制台 | 状态卡、6 个操作按钮、广播/命令输入、`/console/status` 与 `/console/logs` 响应 |
| 3 | 房间设置 | 4 个 Tab、token tag、`#pane-cluster` 里 `.section-title` > 0 且含 GAMEPLAY/NETWORK/MISC/SHARD |
| 4 | 玩家管理 | 4 个 Tab、`#pane-admin` 的 adminlist.txt + KU_ 输入 + 添加/保存、`#pane-whitelist` |
| 5 | 玩家统计 | 时间范围 radio、5 个 Tab、甘特空态文案 |
| 6 | Mod 管理 | Master/Caves 分片 Tab、`/mods?` 响应、卡片非空 |
| 7 | 备份管理 | 备份表 + 回档快照区、`/backup/list` 响应、空态文案 |
| 8 | 定时任务 | `/schedules/{id}` 响应、计划卡片、保存/手动触发入口 |

### 未做（受票 10 阻塞，已在用例文件末尾留 TODO）

- 房间设置改值回读、玩家名单写入落盘、Mod 覆写写盘 —— 这些依赖
  `InstanceFileService` 的路径基准正确，当前落在 `/app` 而非 `/data`，
  强断言会红且红得没有信息量。票 10 修好后按 TODO 升级为强断言。
- 启停类语义断言：无 Klei token 时服务器启动即退，`beforeAll` 只要求"离开安装中"
  并记录 `runStatusDesc`（三轮实测分别观测到 `异常` / `运行中` / `启动中`，
  都是同一真实状态的快照差异）。

### 本轮新增的定位器/时序坑（供后两个包复用）

1. **Element Plus tab-pane 是懒挂载**：`waitForResponse` 必须建在"点击 Tab"之前，
   否则等的是一个已经发出的请求。首版就是这么红的。
   解决：切 Tab 后点编辑器自带的「重新加载」，把请求时序握在手里。
2. **`el-radio-group` 渲染的是 `role=radio`，不是 `role=button`**：
   "最近 24 小时"这类时间范围用 `getByRole("radio")` 定位。
3. **部署端口冲突会被当成"部署成功"**：首次跑时宿主 `11099` 被上一个探针容器占用，
   `docker compose up` 失败 → 实例 run_status 停在 5，插件侧报
   「无法解析实例容器名」。用例本身没问题，但环境残留会伪装成插件缺陷，
   **跑之前必须 `docker ps -a | grep -i e2e\|probe` 确认干净**。

### 显式提出、但不在本票范围内的产品缺陷

- [票 10](10-dst-path-root-mismatch.md)：`LinuxGsmDockerAdapter` 写死
  `containerWorkDir=/app`，与 DST 插件的 `/data` 约定冲突。
- [票 11](11-plugin-workspace-entry-hardcodes-dashboard.md)：主应用「进入工作区」
  硬编码 `/dashboard`，dst 没有该路由也没有 catch-all → 空工作区。
  用例里的绕行（进入后显式 `goto /console`）带注释指向本票，修好后应删除。
