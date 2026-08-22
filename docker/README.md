# Docker 部署（ADR-0012）

应用容器化部署：后端多阶段构建（Maven → JRE fat jar），前端多阶段构建（Node → nginx 静态托管 + 反代），插件目录全挂载。

## 文件清单

| 文件 | 说明 |
|------|------|
| `Dockerfile.backend` | 后端镜像，产物为 `game-platform-core-*-exec.jar` fat jar |
| `Dockerfile.frontend` | 前端镜像，构建 `frontend/dist` 后由 nginx 托管 |
| `nginx.conf` | 前端反代规则（`/api` 透传、`/ws` → `/api/ws`，含 WebSocket 升级） |
| `docker-compose.yml` | 基础部署（SQLite），前后端两服务 |
| `docker-compose.mysql.yml` | MySQL 8.4 服务（预留，后端暂不支持） |
| `docker-compose.pg.yml` | PostgreSQL 16 服务（预留，后端暂不支持） |

## 快速开始

```bash
cd docker
docker compose up -d --build
# 访问 http://localhost:8081，默认账号 admin/admin123
```

- 前端对外端口 `8081`（避开本机开发的 3000/8080）；后端仅容器内网暴露 8080。
- 后端挂载宿主 `/var/run/docker.sock`，并通过 `GAME_PLATFORM_DOCKER_HOST=unix:///var/run/docker.sock` 让 docker-java 客户端管理宿主上的游戏服务器容器（Linux 环境）。

## 挂载目录

运行时目录全部挂载到宿主 `backend/` 下，可直接复用本机已有数据：

| 容器路径 | 宿主路径 | 用途 |
|----------|----------|------|
| `/app/data` | `../backend/data` | SQLite 数据库文件 |
| `/app/plugins` | `../backend/plugins` | **插件目录**（镜像不含任何插件） |
| `/app/backups` | `../backend/backups` | 实例备份 |
| `/app/storage` | `../backend/storage` | 文件存储 |
| `/app/temp` | `../backend/temp` | 临时文件 |
| `/app/games` | `../backend/games` | 外部游戏元数据（可选） |
| `/app/logs` | `../backend/logs` | 应用日志 |

## 插件部署（热部署）

镜像内不含插件 jar，全部通过 `backend/plugins/` 挂载注入：

1. 构建/获取插件 jar（如 `plugin-l4d2-core-1.0.0-SNAPSHOT.jar`）；
2. 拷贝到宿主 `backend/plugins/`（Windows 上若目标为 Linux 容器，无文件锁问题，直接覆盖即可）；
3. 二选一：
   - 等待 hot-reload 自动扫描（默认 30s）；或
   - 调 PF4J API：先 `DELETE /api/pf4j/plugins/{id}`（`purgeTasks=false`）卸载旧版，再 `POST /api/pf4j/plugins/load?jarName=xxx.jar`。

## 数据库切换点（MySQL / PostgreSQL）

**当前后端仅支持 SQLite**（无 MySQL/PG 驱动，`db/schema.sql` 为 SQLite 方言）。compose 中已预留标准环境变量切换点：

- `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_DRIVER_CLASS_NAME` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD`

启用 MySQL/PG 需先完成后端改造：

1. `backend/pom.xml` 增加 `com.mysql:mysql-connector-j` 或 `org.postgresql:postgresql` 驱动；
2. 将 `core/src/main/resources/db/schema.sql`、`data.sql` 拆分为各方言版本（注意 SQLite 的 `AUTOINCREMENT`/动态类型与 MySQL/PG 的差异，以及 MyBatis-Plus 主键策略 `id-type: AUTO` 的兼容性）；
3. `DatabaseInitializer` 按 `spring.datasource.url` 方言路由初始化脚本；
4. 改造完成后叠加启动：

```bash
# MySQL
docker compose -f docker-compose.yml -f docker-compose.mysql.yml up -d
# PostgreSQL
docker compose -f docker-compose.yml -f docker-compose.pg.yml up -d
```

数据库服务的连接信息见对应 compose 文件（用户名/密码为示例值，生产请修改）。

## CI 镜像构建（ADR-0013）

镜像可由 CI 自动构建并推送（本地无需 Docker build）：

| 平台 | 工作流文件 | 镜像仓库 | 镜像名 |
|------|-----------|----------|--------|
| GitHub Actions | `.github/workflows/docker-build.yml` | ghcr.io | `ghcr.io/{owner}/{repo}-backend` / `-frontend` |
| GitLab CI | `.gitlab-ci.yml` | GitLab Container Registry | `$CI_REGISTRY_IMAGE/backend` / `/frontend` |

- **发布**：push tag `v*`（如 `v1.0.0`）→ 测试门禁（`mvn test` + 前端 `test:run`）→ 推送 `v{版本}` 与 `latest` 双 tag。
- **验证**：push main 只跑测试 + 构建不推送；GitLab MR 仅跑测试。
- **消费 CI 镜像**：把 `docker-compose.yml` 里的 `build:` 段替换为对应 `image:`（如 `ghcr.io/xxx/game_platform_manger-backend:latest`），部署机上 `docker compose pull && docker compose up -d` 即可。
- GitHub 侧使用内置 `GITHUB_TOKEN`（需仓库 Settings → Actions → Workflow permissions 允许写 packages）；GitLab 侧要求 runner 支持 dind（privileged）。
- 未做 CD 自动部署（ADR-0013 决策 6），部署为人工执行。

## 注意事项

- 两个 Dockerfile 的构建上下文均为**仓库根目录**，不要在 `docker/` 目录内直接 `docker build .`。
- 后端 fat jar 以 `*-exec.jar` 通配匹配 COPY（ADR-0013），升级 `backend/pom.xml` 版本无需改 Dockerfile。
- SQLite 数据库通过 bind mount 落在宿主 `backend/data/`，与本地开发共用同一文件时请勿同时启动本机后端与容器后端。
- Windows Docker Desktop 下挂载 `/var/run/docker.sock` 需启用 WSL2 集成；docker-java 管理的游戏容器应部署在 Linux 宿主上。
