# 07 — dnf_tw（DNF 台服）插件用例包

**Status:** ready-for-agent
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

- [ ] 明确回答"dnf_tw 能否在牺牲主机上自动化部署到稳定态"（实测，不推断）。
- [ ] 能部署 → 10 个菜单页有覆盖断言，跑完无残留。
- [ ] 不能部署 → 用例包存在但显式 SKIP，报告注明原因；阻塞点另开产品票或部署票。
- [ ] `e2e/plugins/README.md` 同步状态与阻塞说明。
