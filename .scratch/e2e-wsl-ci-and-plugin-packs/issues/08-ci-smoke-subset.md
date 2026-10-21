# 08 — CI 冒烟子集（不需要牺牲主机的那组）

**Status:** ready-for-agent
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

- [ ] 冒烟分组可在干净环境（无 `E2E_TEST_HOST_*`）跑通并判定 PASS/FAIL。
- [ ] `.gitlab-ci.yml` 与 GitHub workflow 都有对应 job，失败上传 artifact。
- [ ] 本地 `npm run e2e -- --group smoke` 与 CI 行为一致。
- [ ] README 说明哪些组进 CI、哪些不进、为什么不进。

