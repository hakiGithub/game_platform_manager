# 01 — E2E 全栈容器化（WSL + Docker）

**Status:** resolved
**Type:** task
**Blocked by:** -

## 结论（2026-10-22 实测，已落地）

方案**成立且已实现**。原规划（只把 Playwright 挪进 WSL、前后端留 Windows）被用户否决：
"wsl 里面有 docker，可以用 docker 启动后端和前端，然后跑 e2e" —— 这个判断更对，
`docker/docker-compose.yml` 与镜像本就现成。

### 落地形态

被测前后端全部跑在 Docker 容器里，Playwright 跑在 WSL 宿主，测 `http://localhost:8081`。
新增 6 个脚本（全文本，共 13 KB，无二进制）：

| 文件 | 职责 |
|------|------|
| `scripts/e2e-docker-compose.yml` | 前后端两服务；项目名 `gpm-e2e` |
| `scripts/e2e-docker-run.sh` | 统一入口：前置校验 → 起栈 → 等就绪 → 跑用例 → 清理 |
| `scripts/e2e-docker-pw.sh` | WSL 宿主跑 Playwright（显式锁定 nvm node v24） |
| `scripts/e2e-docker-frontend.sh` | 容器内 `npm ci` 装 Linux 版依赖到命名卷 |
| `scripts/e2e-docker-browsers.sh` | 装 Chromium 1228（走 npmmirror 镜像源） |
| `scripts/e2e-link-jar.sh` | 把 fat jar 软链成固定名 `app.jar`（避免写死版本号） |

### 实测结果

- 18 条用例（infra + auth + games + system + tasks + workspace）**全绿，21.4 秒**，
  含从零起栈。对比 Windows 受管模式整轮 18~30 分钟。
- 后端容器内经挂载的 `docker.sock` 可管理宿主 Docker（E2E 部署真实游戏容器的前提）。
- 4 个插件 jar 只读挂载进容器，全部 STARTED。
- 清理后零残留：容器 0、软链删除、临时卷删除；只留 `e2e-modules` 命名卷（刻意复用）。

### 踩过并记录的坑（都有实测依据，不是猜）

1. **容器内跑 Maven 要 27 分钟且被超时杀掉** —— 依赖下载速率低至 7.9 kB/s。
   改：直接复用 Windows 侧已构建的 fat jar。
2. **9p 文件系统是隐形杀手** —— 传 95 MB jar 走 `/mnt/d` 要 10 分 37 秒，
   复制到 WSL ext4 只要 1.6 秒。改：源码只读挂载，不复制。
3. **Windows node_modules 不能在 Linux 容器复用** —— npm 只装当前平台可选依赖，
   实测只有 `@rollup/rollup-win32-*` 与 `@esbuild/win32-x64`，Alpine 容器报
   `Cannot find module @rollup/rollup-linux-x64-gnu`。改：命名卷装 Linux 版（10 秒）。
4. **Chromium 版本差两档** —— WSL 缓存是 1208，Playwright 1.61 要 1228。
   直连 `cdn.playwright.dev` **20 分钟无进展**，换 `cdn.npmmirror.com` 后 **27 秒**。
5. **temurin 镜像的 `sh` 是 dash，不支持 `/dev/tcp`** —— healthcheck 必须用 `bash -c`，
   否则后端明明已启动却判 unhealthy，前端一直起不来。
6. **WSL 系统 node 是 v12.22.9** —— Playwright 1.61 要求 ≥18；且实测在非交互
   `bash -lc` 里 `source nvm.sh && nvm use` 后 PATH 仍可能指向系统 node，
   所以脚本用 `~/.nvm/versions/node/v*/bin/node` 绝对路径。

### 与生产部署的分界

`scripts/e2e-docker-compose.yml` 与 `docker/docker-compose.yml` **刻意分开**：
E2E 要临时库（命名卷，不污染 `backend/data`）、可覆盖端口、只读插件挂载；
生产部署要持久化数据与镜像推送。两者不共用 compose 文件。

## 验收

- [x] 一条命令从零起栈跑完冒烟组（`bash scripts/e2e-docker-run.sh`）
- [x] 后端容器能管理宿主 Docker
- [x] 4 个插件在容器内加载
- [x] 跑完零残留（除刻意保留的 `e2e-modules` 卷）
- [x] README 记录容器化用法与三个坑
- [x] 仓库新增仅文本脚本，无二进制、无第二份源码
