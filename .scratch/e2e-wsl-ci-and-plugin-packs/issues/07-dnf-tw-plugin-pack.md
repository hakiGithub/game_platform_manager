# 07 — dnf_tw（DNF 台服）插件用例包

**Status:** resolved
**Type:** task
**Blocked by:** 06

## 任务

按票 05/06 的范式，建 `frontend/e2e/plugins/dnf_tw/`。**本票自带一个可行性前置**，
做不动就停在"已调研 + 阻塞"，不要硬凑用例。

插件仓库在 `D:\program\ai\plugin-dnf-tw`（`plugin.id=plugin-dnf-tw`，gameCode=`dnf_tw`）。

### 目标菜单（来自 `DnfTwExtension.getMenus()`）

`/dashboard` `/connections` `/accounts` `/roles` `/email` `/online` `/activities`
`/auction` `/pvf` `/logs` —— 共 10 个。

### 为什么排在最后（三个真实障碍）

1. **部署方式不同**：`dnf_tw.yml` 的 `deployTypes` 是 `docker-compose`（不是
   `linuxgsm-docker`）。现有 E2E 在牺牲主机上**没有 docker-compose 部署的先例**
   （`drill-legs` 只跑过 linuxgsm-docker，`lifecycle` 跑的是 docker）。
2. **依赖游戏自带 MySQL**：插件直连 `d_taiwan` / `taiwan_cain` / `taiwan_cain_2nd` /
   `taiwan_billing` / `taiwan_login` 五个库。这些库由 compose 栈里的 MySQL 容器初始化，
   初始化完成前所有页面都是错误态。
3. **镜像体积**：`llnut/dnf` 镜像 + 网关，拉取耗时与主机内存压力都远大于前两个插件
   （WSL 只有 4 GiB，已跑着 l4d2/steam302/agent2api 等容器）。

### 分步走

1. **先探**：能否在牺牲主机上用平台部署一个 dnf_tw 实例并到达稳定态。若不能，
   记录阻塞点（镜像拉不动？compose 启动失败？内存不足？），本票停在"探明阻塞"。
2. **能部署再写用例**：10 个菜单页逐个可进入 + 页面根渲染 + 列表或显式空态。
   写操作优先选"连接管理"（插件自己的资源配置，经 `ExtensionClient` 持久化，
   不依赖游戏库是否就绪）。
3. **不能部署**：整组用例写成 `skipWithoutDnfTwInstance()` 门控（与
   `skipWithoutLiveL4d2Instance()` 同款），未配置实例 ID 时显式 SKIP，
   不硬编码实例 ID（票 14 的规矩）。

## 验收

- [x] 明确回答"dnf_tw 能否在牺牲主机上自动化部署到稳定态"（实测，不推断）。
- [x] 能部署 → 10 个菜单页有覆盖断言，跑完无残留。
- [x] `e2e/plugins/README.md` 同步状态与阻塞说明。

## 实测结论（2026-10-23）

**可以部署**，但内存是硬约束，且暴露了两个平台侧缺陷。

| 项 | 实测值 |
|----|--------|
| 部署方式 | `docker-compose`，`MYSQL_PORT=13306` |
| 创建 → 运行中 | 约 40s |
| 游戏库 | 5 个（`d_taiwan` / `taiwan_cain` / `taiwan_cain_2nd` / `taiwan_billing` / `taiwan_login`）全部初始化成功 |
| 用例 | 11 条 serial，全绿 1.5m（`--attach` 复用栈） |

### 内存：`mem_limit=1g` 必须覆盖

- 上游 compose 固定 `mem_limit: 1g`，两个 `df_game_r` 分片会被 cgroup 反复 SIGKILL
  （`dmesg` 可见 `Memory cgroup out of memory: Killed process ... (df_game_r)`）。
- `1.5` 也不够：跑满 1 分钟后分片仍被杀，`/dashboard/overview` 会因为逐源探活而长时间不返回。
- 本包用 `resources.memoryLimit=2.5`（`E2E_DNF_MEMORY_LIMIT_G` 可调）。这是**上限**，
  容器实测常驻 1.2~1.5 GiB；WSL 3.8 GiB 下可跑，但期间不要并行起别的重容器。
- 建议上游把 `dnf_tw.yml` 的 `mem_limit` 与 `dependencies.memory: 2G` 对齐（另开票）。

### 顺带修掉的两个真实缺陷

1. **主应用**：`PluginStatusVO` 没有 `gameCode`，前端只能从 pluginId 猜。
   `plugin-dnf-tw` 猜出 `dnf-tw`，而实例 `game_code` 是 `dnf_tw`，导致
   "进入工作区" 恒报"未找到实例"。已改为由 `manifest.getGameCode()` 下发，
   前端 `resolveGameCode()` 优先用它（单测 `pluginWorkspaceGameCode.test.js`）。
2. **主应用**：`ExtensionClientImpl.update()` 只写 `spec/metadata/status`，**不写 `name`**，
   插件"编辑连接档案改名"保存成功但名字不变。已补 `name=?` 与唯一键冲突转
   `DuplicateExtensionException`（单测 +2）。

### 遗留

- 实例删除仍会 `partial_success`：compose 里 MySQL 容器以 root 创建 `mysql/mysql.sock`，
  SSH 用户 `rm` 无权限，远端目录残留。属部署适配器清理逻辑问题（另开票）。
- 模板本身的 `mem_limit` 与 dependencies 不一致，见上。
