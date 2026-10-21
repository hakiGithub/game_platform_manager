# E2E 自动化：全栈容器化 + 多插件用例包

Status: in-progress

> 来源：2026-10-21 用户提问（"CI 自动化跑用例可以交给 WSL 吗？其他插件也希望有 e2e"），
> 2026-10-22 用户修正方案（"wsl 里面有 docker，可以用 docker 启动后端和前端，然后跑 e2e"）。
> 术语遵循 `docs/plugins.md`（插件登记）、`.scratch/ui-e2e-automation/spec.md`（E2E 既有规格）。

---

## Problem Statement

1. **E2E 跑在 Windows 上，每次都要人守着。** 整轮 18~30 分钟，失败要人工去
   `e2e/.artifacts` 翻截图和 trace。没有一个"跑完把结果或错误信息交给我"的入口。
2. **CI 里没有 E2E。** `.gitlab-ci.yml` 与 `.github/workflows/docker-build.yml` 都只跑
   `mvn -B test` + `npm ci && npm run test:run`。
3. **只有 l4d2 有插件 E2E。** `e2e/plugins/` 下只有 `l4d2/` 一个用例包。而 `docs/plugins.md`
   登记了 4 个插件（l4d2 / dst / dnf_tw / sdtd，共 41 个菜单页），其余 3 个的 23 个页面
   **零自动化覆盖**。

## Solution

### Part A — 全栈容器化（已落地）

被测前后端全部跑在 Docker 容器里，Playwright 跑在 WSL 宿主。入口
`bash scripts/e2e-docker-run.sh`，实测 **18 条用例 21.4 秒**（含从零起栈）。

设计约束是用户提的"**不要保留太重的依赖**"，落实为：

| 约束 | 做法 |
|------|------|
| 零源码复制 | 主仓库只读挂载进容器，不往 WSL ext4 同步第二份 |
| 零 Maven 构建 | 直接复用 Windows 侧已构建的 fat jar |
| 零 npm ci 在仓库内 | 前端依赖装进 Docker 命名卷（容器内 `npm ci` 约 10s，可复用） |
| 零二进制入库 | 6 个脚本全文本，共 13 KB |

与 `docker/docker-compose.yml`（生产部署）**刻意分开**：E2E 要临时库、可覆盖端口、
只读插件挂载；生产要持久化数据与镜像推送。

### Part B — 插件用例包横向复制到另外 3 个插件

l4d2 包已是可复制范式（`e2e/plugins/README.md`："新增插件用例包 = 新增一个子目录"）。
其余 3 个按同一范式接入，**框架代码零改动**。

接入顺序按"能不能造出运行中的实例"排序，不按插件大小：

| 顺序 | 插件 | gameCode | 部署方式 | 能否自动化造实例 | 说明 |
|------|------|----------|---------|----------------|------|
| 1 | `plugin-sdtd` | `sdtd` | linuxgsm-docker | 已验证 | `drill-legs.spec.js` 已跑通 sdtd 腿；6 个菜单页最小，先跑通范式 |
| 2 | `plugin-dst` | `dst` | linuxgsm-docker | 部分 | 部署可达稳定态，但 DST 无 cluster token 会启动即退（既有 `needsToken: true`），启停类断言需 SKIP |
| 3 | `plugin-dnf-tw` | `dnf_tw` | docker-compose | 阻塞 | 依赖游戏自带 5 个 MySQL 库；平台 Docker Compose 部署在牺牲主机上尚无 E2E 先例 |

**容器化对 Part B 是利好**：4 个插件 jar 只读挂载进容器即全部 STARTED，
插件 E2E 的前置成本大幅下降。

### Part C — CI 集成（可选，独立于 A/B）

容器化落地后，CI 集成比原规划简单：镜像里本来就能起前后端 + Chromium。

1. **CI 跑冒烟子集**（`infra` / `auth` / `workspace` / `games` / `system` / `tasks` /
   `plugins`）—— 不需要牺牲主机，容器内可跑通。GitLab 与 GitHub 的镜像都满足。
2. **全量 E2E 进 CI** 需要 self-hosted runner（托管 runner 上没有 WSL、没有 SSH 目标）。

建议先做 1。

## User Stories

### Part A
1. As a 平台维护者, I want 一条命令在干净容器环境跑完 E2E 并给出通过判定, so that 不污染本机也能回归
2. As a 平台维护者, I want 跑完自动清理容器与临时库, so that 不残留垃圾现场
3. As a 平台维护者, I want 缺前置时得到明确错误与修复建议, so that 不猜为什么跑不起来

### Part B
4. As a 插件维护者, I want sdtd 插件的 6 个页面有 E2E, so that 改 7dtd 插件前端有回归保障
5. As a 插件维护者, I want dst 插件的 7 个页面有 E2E（启停类按环境 SKIP）, so that 改 DST 插件前端有回归保障
6. As a 平台维护者, I want 4 个插件用例包能用同一条命令整轮跑完, so that 回归覆盖面可度量

### Part C
7. As a 平台维护者, I want CI 里跑不需要牺牲主机的冒烟子集, so that 主应用改动有自动化门禁

## Implementation Decisions

1. **容器化是新增模式，不替换现有模式。** `npm run e2e`（Windows 受管）与
   `npm run e2e -- --attach` 保留；容器化是第三种选择，各有适用场景（见 README 取舍表）。
2. **不建自定义镜像。** 直接用官方 `eclipse-temurin:17-jre` 与 `node:20-slim`，
   靠挂载把 fat jar / 源码 / 依赖卷喂进去。避免维护 Dockerfile 与镜像构建时间。
3. **前端跑 vite dev，不 build。** 省一次构建，且能直接反映源码改动。
4. **健康检查必须用 `bash -c`**：temurin 镜像的 `sh` 是 dash，不支持 `/dev/tcp`，
   否则后端已启动仍判 unhealthy，前端永不启动。
5. **nvm node 用绝对路径**：WSL 系统 node 是 v12，且非交互 shell 里 `nvm use` 不可靠。
6. **Chromium 走 npmmirror 镜像源**：直连 cdn.playwright.dev 实测 20 分钟无进展。
7. **插件用例包位置**：全部在**主仓库** `frontend/e2e/plugins/{gameCode}/`，不进插件仓库。
8. **插件实例前置**：复用 l4d2 包做法 —— `beforeAll` 经平台 API 造主机 + 部署实例，
   `afterAll` 删实例删主机，跑完零残留。
9. **门控复用**：`skipWithoutTestHost()` 照用；dnf_tw 若无法造实例，整组 SKIP 并注明原因，
   不硬编码实例 ID（票 14 已确立的规矩）。
10. **不改 ADR**：不改变插件运行模式（ADR-0003）、菜单归属（ADR-0001）、范围隔离（ADR-0002）。

## Testing Decisions

- **容器化自身验证**：`bash scripts/e2e-docker-run.sh` 从零起栈跑冒烟组，判定 PASS。
- **插件用例包验收**：每个新包至少覆盖该插件 `getMenus()` 声明的全部菜单页"可进入且渲染出
  页面根"，其中至少 1 个页面做写操作断言。
- **不直连 SSH**：沿用既有红线，效果断言走平台 REST API（`support/api.js`）。
- **不新增 flaky**：新用例禁止固定 sleep，一律 `expect.poll` / Playwright auto-wait。
- **已知失败先修**：`plugins/l4d2/plugin-subapp.spec.js` 的"服务器日志页渲染文件表与
  刷新入口"在 2026-10-21 整轮失败（日志列表 0 行，环境侧问题）。新包接入前需先处理。

## Out of Scope

- 在插件仓库内建 E2E 工程（理由见 Part B）。
- 浏览器矩阵（仍只 Chromium）。
- 把 E2E 变成 MR 的强制门禁（先做冒烟子集，观察稳定性后再谈）。
- 性能门槛。
- 自建 E2E 镜像 / Dockerfile（用官方镜像 + 挂载即可）。

## Further Notes

### 四个插件的菜单与路由（来自 `docs/plugins.md` 与各插件 `getMenus()`）

| 插件 | 菜单数 | 路由 |
|------|-------|------|
| l4d2 | 18 | `/dashboard` `/server-config` `/server-info` `/preset` `/rcon` `/monitor` `/logs` `/restart` `/maps` `/map-center` `/player-stats` `/playtime` `/admins` `/version` `/plugins` `/plugin-store` `/plugin-config` `/backup` `/download` |
| dst | 7 | `/console` `/settings` `/players` `/player-stats` `/mods` `/backup` `/schedules` |
| dnf_tw | 10 | `/dashboard` `/connections` `/accounts` `/roles` `/email` `/online` `/activities` `/auction` `/pvf` `/logs` |
| sdtd | 6 | `/dashboard` `/console` `/players` `/logs` `/events` `/mods` |

### 现状基线（2026-10-21 Windows 整轮）

- 前端单测：35 文件 / 586 用例全绿。
- 后端 `mvn test`：592 用例，1 个**存量**失败（`SourceModCfgServiceCandidatesTest`，
  已在改动前提交上复现，与本规划无关）。
- E2E 整轮：48 passed / 1 failed / 9 skipped / 22 did not run，18.3 分钟。

### 环境实测（2026-10-22）

WSL Ubuntu 22.04.5，8 核 / 4 GiB，Docker 29.1.3，WSL→Windows 网关 `192.168.96.1` 可达，
容器→WSL 宿主 `172.17.0.1:22` 可达。完整数据见票 01。
