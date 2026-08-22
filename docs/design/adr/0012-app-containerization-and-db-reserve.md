# ADR-0012: 应用容器化部署与多数据库预留

- 状态：Accepted
- 日期：2026-08-22
- 关联：[ADR-0002 主应用与插件范围隔离](0002-main-app-plugin-scope-isolation.md)、[docker/README.md](../../../docker/README.md)

## 背景（Context）

项目原有部署方式为宿主机直跑脚本（`scripts/start-all.sh` / `rebuild-restart-all.ps1`），依赖本机安装 JDK 17、Maven、Node，且仅在 Windows 开发环境验证过。需要在 Linux 服务器上以 Docker / docker-compose 方式部署应用本体，并期望支持 MySQL / PostgreSQL 作为数据库。

现状事实：

- 后端**仅支持 SQLite**：父 pom 只引入 `org.xerial:sqlite-jdbc`，`db/schema.sql` 为 SQLite 方言，`DatabaseInitializer` 按 SQLite 语义执行初始化与迁移。
- 后端已有可执行 fat jar（`game-platform-core-*-exec.jar`，`-exec` 分类器）。
- 运行时相对 `backend/` 的目录：`data/`（数据库）、`plugins/`（插件）、`backups/`、`storage/`、`temp/`、`games/`（外部元数据）、`logs/`。
- 前端产物为 `frontend/dist`，开发态由 Vite proxy 将 `/api` 透传、`/ws` 重写为 `/api/ws` 转发到 8080。
- docker-java 客户端默认 `game-platform.docker.host: unix:///var/run/docker.sock`（管理被纳管的游戏服务器容器）。

## 决策（Decision）

1. 应用容器化采用**多阶段构建 + 前后端两服务**：后端 `maven → JRE` 构建 fat jar 运行，前端 `node → nginx` 构建静态产物并由 nginx 反代后端（`/api` 透传、`/ws` → `/api/ws`、WebSocket 升级、长连接超时放宽），前端为唯一对外入口（默认 `8081:80`），后端仅内网暴露。
2. **插件不打进镜像**，全部通过 volume 挂载 `backend/plugins → /app/plugins` 注入，配合已开启的 hot-reload（30s 扫描）与 PF4J load/unload API 实现热部署；运行时其余目录（`data/backups/storage/temp/games/logs`）一并 bind mount 到宿主 `backend/` 下。
3. **本期内后端继续仅支持 SQLite**，MySQL / PostgreSQL 不做后端代码改造；compose 中以标准环境变量（`SPRING_DATASOURCE_URL` 等）预留切换点，并交付真实可启动的 `docker-compose.mysql.yml`（MySQL 8.4）与 `docker-compose.pg.yml`（PostgreSQL 16）作为独立数据库服务，后端接入段以注释占位。
4. 后端容器挂载宿主 `/var/run/docker.sock` 并设 `GAME_PLATFORM_DOCKER_HOST=unix:///var/run/docker.sock`，使平台可管理宿主上的游戏服务器容器（目标环境为 Linux）。
5. MySQL/PG 正式启用为独立后续任务，前置改造清单（驱动依赖、方言 schema、`DatabaseInitializer` 方言路由）登记于 `docker/README.md`。

## 后果（Consequences）

- `docker compose up -d --build` 一条命令完成部署，不依赖宿主机 JDK/Maven/Node 工具链。
- 插件安装/升级退化为"拷贝 jar 到挂载目录"，与 `scripts/deploy-plugin.sh` 的工作流语义一致；Windows 宿主上的 jar 文件锁问题因目标为 Linux 容器自然消解。
- SQLite 文件与本地开发共用 `backend/data/`，本机后端与容器后端**不可同时启动**（README 已注明）。
- fat jar 文件名含版本号，升级 `backend/pom.xml` 版本时需同步修改 `Dockerfile.backend`。
- MySQL/PG compose 中后端接入段为注释占位，直接叠加启动只额外拉起数据库容器，平台数据仍落 SQLite；用户若误以为已切换数据库，以环境变量实际生效值为准。

## 备选方案（Alternatives）

- **完整多数据库支持（加驱动 + 方言 schema + 迁移路由）**：一次性交付三个 compose 完整可用，但 schema 重写与迁移逻辑改造工作量大、回归风险高，拆为独立后续任务（决策 3/5）。
- **镜像内置插件 jar**：镜像开箱即用含插件，但每次插件升级需重建镜像，与现有热部署工作流冲突，被否决（决策 2）。
- **前后端合一单镜像（后端托管静态资源）**：服务更少，但需改造 Spring 静态资源托管与 SPA 路由回退，且前后端发布节奏耦合，被否决（决策 1）。
- **宿主机预构建 + 只 COPY 产物**：Dockerfile 更薄，但依赖宿主机工具链且 CI 不友好，被否决（决策 1）。
