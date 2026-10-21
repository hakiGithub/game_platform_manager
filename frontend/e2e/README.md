# E2E 自动化用例集（全量回归）

按「页面 × 页面内功能」组织的 Playwright E2E 用例集。规格见 `.scratch/ui-e2e-automation/spec.md`。

## 运行

```bash
cd frontend

# 受管模式（推荐）：临时 SQLite 起后端+前端 → 跑全部用例 → 报告 → 清理
# 前端自动选择空闲端口；不会占用或杀掉被 WSL 转发等进程占用的 3000/3001。
npm run e2e

# 整轮编排：七个分组保序执行（冒烟导航 → 无主机 → 主机 → 容器认领 → 生命周期 → 插件 → 演练腿），
# 聚合各分组结果并输出整轮通过判定（除显式 SKIP 外全部用例通过 = PASS）
npm run e2e:round

# 跳过 mvn 编译（之前编译过一次、core/target/cp.txt 存在时）
E2E_FAST=1 npm run e2e

# 附着模式：对已运行的环境跑用例（不起栈、不清理）
npm run e2e -- --attach

# 查看 HTML 报告（总数/通过/失败/跳过 + 失败截图与 trace）
npm run e2e:report
```

首次运行前：`npm install` + `npx playwright install chromium`。

## 牺牲主机凭据（凭据不入库）

在 `frontend/e2e/.env.local`（已被 .gitignore，格式 KEY=VALUE）写入：

```
E2E_TEST_HOST_ADDRESS=127.0.0.1
E2E_TEST_HOST_PORT=22
E2E_TEST_HOST_USERNAME=haki
E2E_TEST_HOST_AUTH=你的密码
```

该文件由 runner 与用例进程自动装载（`support/env-file.js`），真实环境变量优先。
红线：**凭据只存本地，绝不提交入库**。

## 测试数据仓库（e2e/fixtures/）

用例只从本目录取数，**运行时禁止临时外网下载资产**（红线）。

| 资产 | 路径 | 格式契约 |
|------|------|----------|
| 最小 VPK 地图包 | `vpk/e2e-test-map.vpk` | 可被后端 VpkParser 解析：magic `0x55AA1234` v1 + 目录树含 `missions/e2e_test_mission.txt`（preload=0，与标准 VPK v1 布局一致） |
| SourceMod 插件样例 | `sourcemod/e2e-sample-plugin.smx` | 平台上传通道只做扩展名/名称处理，样例为占位二进制（非真实插件，E2E 不依赖 SourceMod 加载它） |
| 游戏元数据 YAML | `games/e2e-drill-game.yml` | 满足 `GameYamlConfig.isValid` 强校验（`game.code` + `game.name`），gameCode 固定 `e2edrill` |

二进制资产由 `fixtures/build-fixtures.mjs` 合成并自检，重新生成：`node e2e/fixtures/build-fixtures.mjs`。
生成脚本与本 README 一起维护；改格式契约（后端解析逻辑）时同步改合成器。

## 环境契约（凭据不入库）

| 变量 | 缺省 | 说明 |
|------|------|------|
| `E2E_BASE_URL` | 读取 `logs/frontend.port`，否则 `http://localhost:3000` | 前端地址 |
| `E2E_BACKEND_URL` | `http://localhost:8080` | 后端地址（API 断言通道） |
| `E2E_ADMIN_USER` / `E2E_ADMIN_PASS` | `admin` / `admin123` | 种子管理员 |
| `E2E_TEST_HOST_ADDRESS` / `_PORT` / `_USERNAME` / `_AUTH` | 空 | 牺牲主机凭据；任一缺失 ⇒ 主机类用例整组 `SKIP (no test host)` |
| `E2E_L4D2_LIVE_INSTANCE_ID` | 空 | 真实 L4D2 live 插件用例的目标实例；未设置时相关用例显式 SKIP |
| `E2E_WORKERS` | `1` | 并行 worker 数（用例共享一个后端，默认串行） |
| `E2E_FAST` | 空 | `1` = 受管模式跳过 mvn 编译 |

## 通过判定

单条用例 = 断言全过即通过；**整轮通过 = 除显式 SKIP 外全部用例通过**。

## 目录约定（新增用例不改框架）

```
e2e/
├── support/            # 框架层：环境契约(env)、平台 API 断言客户端(api)、主机 SKIP(skip)
├── fixtures/           # 测试数据仓库（VPK/插件样例/YAML，见上表；build-fixtures.mjs 再生成）
├── infra/              # 基础设施自检用例（如凭据契约哑用例）
├── main-app/           # 主应用用例：按「页面/功能」建目录，如 auth/login.spec.js
└── plugins/            # 插件用例包：每个插件一个子目录（见 plugins/README.md）
```

新增一条用例 = 在对应命名空间下新建 `<功能>.spec.js`，文件头按既有格式声明
「页面 / 用例 / 前置 / 步骤 / 通过标准」。
首次运行前：`npm install` + `npx playwright install chromium`。

## 容器化执行（WSL + Docker，隔离环境首选）

被测前后端全部跑在 Docker 容器里，Playwright 跑在 WSL 宿主。与 Windows 受管模式等价，
但不占 Windows 端口、不写 Windows 临时库，也不依赖本机已起的前后端。

```bash
# WSL 内执行；首次需准备两件事（各约 10s，一次性）
bash scripts/e2e-docker-browsers.sh    # 装 Playwright Chromium 1228（走 npmmirror 镜像源）
bash scripts/e2e-docker-frontend.sh    # 装 Linux 版 node_modules 到 Docker 命名卷

# 之后每次
bash scripts/e2e-docker-run.sh                                  # 全量
bash scripts/e2e-docker-run.sh e2e/main-app/auth                # 指定 spec
E2E_KEEP_STACK=1 bash scripts/e2e-docker-run.sh --attach        # 保留栈调试
```

实测（18 条冒烟+导航+无主机用例）：**从零起栈到跑完 21.4 秒**。

### 为什么前端不能直接复用 Windows 侧 node_modules

npm 只安装当前平台的可选依赖。Windows 侧 `frontend/node_modules` 里实测只有
`@rollup/rollup-win32-x64-*` 与 `@esbuild/win32-x64`，Linux 容器里 vite 直接报
`Cannot find module @rollup/rollup-linux-x64-gnu`。所以必须有一份 Linux 版依赖，
装在 Docker 命名卷 `gpm-e2e_e2e-modules` 里（`e2e-docker-frontend.sh` 负责）。

同理后端不跑容器内 Maven：冷缓存下载实测 27 分钟（且会被超时杀掉），
改为直接复用 Windows 侧已构建的 fat jar。

### 与其他模式的取舍

| 模式 | 命令 | 适用 |
|------|------|------|
| Windows 受管 | `npm run e2e` | 本机开发，要跑主机链路（需要 Windows 能 SSH 到牺牲主机） |
| Windows 附着 | `npm run e2e -- --attach` | 已有运行中的栈，只想重跑用例 |
| 容器化 | `bash scripts/e2e-docker-run.sh` | 干净环境、CI、不想污染本机数据 |

容器模式下 `E2E_TEST_HOST_*` 仍可用（牺牲主机照常配），但后端在容器里，
通过它创建的"主机"实际是 WSL 宿主（`172.17.0.1` 可达，已实测）。

## 牺牲主机凭据（凭据不入库）
