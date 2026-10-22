# 08 — CI 冒烟子集（不需要牺牲主机的那组）

**Status:** resolved
**Type:** task
**Blocked by:** 01, 03

## 任务

把"不需要牺牲主机"的那组用例接进 CI，作为真实门禁。全量 E2E（含主机链路）
进 CI 需要 self-hosted runner，本票不做。

### 冒烟子集范围

`ROUND_GROUPS` 里不依赖 `E2E_TEST_HOST_*` 的部分：

| 分组 | 目录 |
|------|------|
| 冒烟与导航 | `infra`、`main-app/auth`、`main-app/workspace` |
| 无主机页面组 | `main-app/games`、`main-app/system`、`main-app/tasks`、`main-app/plugins` |

其余五组（主机纳管 / 容器认领 / 实例生命周期 / 插件子应用 / 演练腿）都需要牺牲主机，
**不进 CI**。

### 为什么能进 CI

托管 runner 上没有 WSL、没有 SSH 目标，但冒烟子集只需要：
后端（JDK 17 + 临时 SQLite）+ 前端（Vite dev 或 preview）+ Chromium。
 GitLab 有 `maven:3.9-eclipse-temurin-17` 与 `node:20` 镜像，GitHub Actions 有
`setup-java` / `setup-node`，都能满足。

### 要点

1. runner 需要一个 `--group <name>` 之类的参数来只跑指定分组（整轮编排已存在，
   复用即可，不要新写一套）。
2. CI job 里：起栈（受管模式）→ 跑冒烟分组 → 出 summary → 上传 artifact
   （`e2e/.report` + `e2e/.last-run` + `e2e/.artifacts`）→ 退出码决定红绿。
3. 两个 CI 文件都要加（`.gitlab-ci.yml` + `.github/workflows/docker-build.yml`），
   两边保持同一组用例。
4. 前端 dev server 需要监听 `0.0.0.0`（与票 01/02 的 WSL 前提一致）。

### 两个 CI 的处境不同，注意

- **GitHub**：`origin` 是 github.com，Actions 能真跑。
- **GitLab**：远端是 `http://192.168.3.48:3300`（局域网 Gitea/GitLab），
  2026-10-21 实测**连接超时**。加 CI 配置前先确认该实例是否还在服务，
  不在的话改了也没人跑。

## 验收

- [x] 冒烟分组可在干净环境（无 `E2E_TEST_HOST_*`）跑通并判定 PASS/FAIL。
- [x] `.gitlab-ci.yml` 与 GitHub workflow 都有对应 job，失败上传 artifact。
- [x] 本地 `npm run e2e -- --group smoke` 与 CI 行为一致。
- [x] README 说明哪些组进 CI、哪些不进、为什么不进。

## 落地结果（2026-10-23）

### 一份分组定义，三个入口复用

`frontend/e2e/runner.mjs` 的 `GROUP_PRESETS.smoke` 直接由 `ROUND_GROUPS` 前两组
`flatMap` 出来，不新增第二份路径列表：

```text
e2e/infra
e2e/main-app/auth
e2e/main-app/workspace
e2e/main-app/games
e2e/main-app/system
e2e/main-app/tasks
e2e/main-app/plugins   ← CI 默认排除，见下
```

| 场景 | 命令 |
|------|------|
| 本地 Windows 受管 | `npm run e2e -- --group smoke` |
| 本地 WSL + 容器栈 | `bash scripts/e2e-docker-run.sh --attach --group smoke` |
| 托管 CI（无 WSL/无 Docker） | `bash scripts/e2e-ci.sh --group smoke` |

`--group` 解析在 `e2e/support/e2e-args.js`，支持 `--group smoke` 与 `--group=smoke`；
缺值或未知分组显式报错（不静默跑全量），单测 `e2e-args.test.mjs` 5 条。

### 为什么 CI 默认排除 `main-app/plugins`

那组断言"l4d2 插件卡片已启动"，依赖 `backend/plugins/*.jar`——gitignored 的构建产物，
且 l4d2 插件 JAR 有 124MB。干净 checkout 里没有它，为了 3 条 UI 断言去构建 124MB
插件与"不保留重依赖"冲突。本地要跑时加 `E2E_SMOKE_INCLUDE_PLUGINS=1`。

### `scripts/e2e-ci.sh` 为什么必须存在

受管模式（`runner.mjs` 不带 `--attach`）在 Linux 上会调 `scripts/start-all.sh`，
而它用 `powershell.exe` 起 Windows 进程，托管 runner 上不可用。
`e2e-ci.sh` 只做三件事：`java -jar` 启 fat jar → `vite` dev → `runner.mjs --attach`，
不复制任何分组/断言逻辑。`E2E_CI_JAVA` 可替换 java 命令（本地无 JDK 时用
`docker run ... eclipse-temurin:17-jre java` 验证过后端可起；前端那步在 WSL 里因
Windows 版 node_modules 缺 `@rollup/rollup-linux-x64-gnu` 失败，脚本已加前置校验
并给出 remediation）。

### 实测

| 入口 | 结果 |
|------|------|
| `e2e-docker-run.sh --attach --group smoke`（含 plugins） | 20 passed / 2 skipped / 24.3s |
| 同上（默认排除 plugins） | **18 passed / 20.3s** |

### CI 配置

- `.github/workflows/docker-build.yml`：新增 `e2e-smoke` job（setup-java + setup-node +
  `npm ci` + `playwright install --with-deps chromium` → `bash scripts/e2e-ci.sh --group smoke`
  → 失败也上传 `.report/.last-run/.artifacts`），`build-push` 依赖它。
- `.gitlab-ci.yml`：拆成 `e2e-jar`（maven 镜像出 fat jar 当 artifact）+
  `e2e-smoke`（node 镜像，`needs: e2e-jar`，只装 JRE 不装 JDK）。

> GitLab 远端 `192.168.3.48:3300` 2026-10-21 实测连接超时，配置已写但不保证有人跑；
> GitHub Actions 是真正的门禁。

