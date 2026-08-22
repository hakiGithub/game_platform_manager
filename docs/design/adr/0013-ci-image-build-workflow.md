# ADR-0013: CI 镜像构建工作流（GitHub Actions + GitLab CI）

- 状态：Accepted
- 日期：2026-08-22
- 关联：[ADR-0012 应用容器化部署](0012-app-containerization-and-db-reserve.md)

## 背景（Context）

ADR-0012 交付了 Dockerfile 与 compose 部署，但镜像只能在装有 Docker 的机器上手动 `docker compose build`。需要接入 GitLab / GitHub 工作流自动打包镜像。已定事实：两个镜像（backend / frontend），构建上下文均为仓库根目录；后端 fat jar 文件名含版本号；前端测试脚本 `npm run test` 为 vitest watch 模式（CI 必须用 `test:run`）。

## 决策（Decision）

1. **双平台交付**：`.github/workflows/docker-build.yml`（GitHub Actions）与 `.gitlab-ci.yml`（GitLab CI）并行提供，二者行为对齐：测试门禁 → 构建镜像。
2. **镜像仓库用托管平台自带 registry**：GitHub 推 `ghcr.io/{repo}-backend/frontend`（`GITHUB_TOKEN` 认证，无额外 secret）；GitLab 推 `$CI_REGISTRY_IMAGE/backend/frontend`（内置 `CI_REGISTRY_USER/PASSWORD`）。
3. **触发与版本策略**：push tag `v*` 时跑测试并推送镜像（tag 同时打 `v{版本}` 与 `latest`）；push main 只跑测试 + 构建验证（不推送）；GitLab MR 仅跑测试；GitHub 手动 `workflow_dispatch` 可触发验证。
4. **测试门禁**：镜像构建 job 依赖后端 `mvn test` 与前端 `npm run test:run`（注意非 watch 模式的 `test`），失败短路不出镜像。
5. **仅构建 `linux/amd64`**：游戏服务器宿主为 x86；arm64 需 QEMU 模拟跑 Maven，成本过高，暂不做 multi-arch。
6. **本期只做 CI 不做 CD**：推送镜像后部署仍由人工在目标机 `docker compose pull && up -d` 完成；自动部署（服务器凭据、窗口、回滚）为独立后续任务。
7. **解除镜像与版本号耦合**：`Dockerfile.backend` 改为 `COPY core/target/*-exec.jar`（`-exec` 分类器产物唯一），CI 与 `backend/pom.xml` 版本升级解耦。

## 后果（Consequences）

- 打 tag `v1.2.3` 即得到 `ghcr.io` / GitLab Registry 上带版本与 `latest` 的两对镜像，目标机只需改 compose 的 `image:` 字段即可消费 CI 产物（不再本地 build）。
- GHCR 镜像名须小写，GitHub workflow 内已做 `${GITHUB_REPOSITORY,,}` 转换；仓库名含大写时自动兼容。
- GitLab 构建作业要求 runner 支持 dind（privileged）或 DOCKER_HOST socket 绑定，shared runner 配置差异需各自确认。
- `latest` tag 仅在 tag 触发时更新，main 分支直推不会污染 `latest`。
- GitHub 侧构建缓存走 `type=gha`，GitLab 侧未配 registry 缓存（docker 层缓存依赖 runner 本地），重复构建 GitLab 可能更慢。

## 备选方案（Alternatives）

- **自建 Harbor / 私有 registry**：可控性强但需运维与额外凭据配置，被否决（决策 2）。
- **main 每次 push 都推 `latest`**：发布粒度失控、无法区分验证构建与发布，被否决（决策 3）。
- **multi-arch（含 arm64）**：QEMU 下 Maven 构建慢一个数量级且当前无 arm64 部署目标，被否决（决策 5）。
- **顺带 CD 自动部署**：引入服务器凭据与回滚复杂度，拆为独立任务，被否决（决策 6）。
