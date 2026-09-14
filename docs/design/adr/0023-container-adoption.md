# ADR-0023: Docker 容器手动认领为游戏实例（Container Adoption）

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-09-11 |
| 决策者 | User (grill-with-docs session) |
| 关联 | [ADR-0005](0005-run-status-vocabulary-unification.md)（run_status 词汇）、[ADR-0018](0018-map-upload-task-and-archives.md)（异步任务模型） |
| Supersedes | 无 |

## 背景（Context）

环境从旧库迁移到 Docker 部署模式后（2026-09-11 排查确认），主机上真实运行的容器（如 l4d2）在平台库里没有对应的 `game_instance` 记录，容器详情/列表的"关联实例"一律显示未关联。平台已有的"匹配已有实例"逻辑（`DockerInstanceSyncStrategy` 三级匹配）方向是**拿库里的实例记录去主机上对账容器状态**，库为空则无从匹配；且 `createInstance` 落库后必然触发异步部署（`deployAsync`），对已存在的容器是破坏性的。

另存在两套互不相通的"容器↔实例"机制：`docker_container_link` 表（`POST /docker/links`，实例详情页"关联容器"在用）不影响 `isLinked` 展示、不参与启停与状态同步，是孤岛；`isLinked` 实际由 `DockerContainerServiceImpl.enrichInstanceLinkForDetail/matchInstance` 按 `runtime_metadata.containerId`、容器名等规则对 `game_instance` 表实时计算。

## 决策（Decision）

### 决策 1：认领 = 创建真实实例记录 + 回写 runtime_metadata，不触发部署

- 新增认领端点（容器视角：`POST /docker/hosts/{hostId}/containers/{containerId}/adopt`），校验容器存在（docker inspect）与同主机实例名唯一后，插入 `game_instance` 记录并回写 `runtime_metadata.containerId/containerName` + `adopted: true` 标记。
- **跳过 `deployAsync`**：容器已存在，绝不重复部署。
- 初始 `run_status` 取容器实际状态（running→RUNNING，其余→STOPPED），不落 INSTALLING。
- 触发插件 `onInstanceCreate` hook，与常规创建保持一致（插件按实例懒初始化资源）。
- 认领成功后，三级匹配、ContainerIdResolver、启停、状态同步、isLinked 展示全部自动生效，无需额外联动。

### 决策 2：deployType 按游戏元数据自动探测

- 用户选定游戏后，候选类型 = 游戏 yml `deployTypes` 与 Docker 类型的交集；优先级 `docker` > `docker-compose` > `linuxgsm-docker`（有 docker 用 docker，部分游戏仅支持 docker-compose 则自动落到 compose）。
- 选定 `docker-compose` 时，从容器的 `com.docker.compose.project / .project.working_dir / .service` labels 预填 `projectName / workDir / serviceName`；labels 缺失时这三个字段可编辑且必填。
- `linuxgsm-docker` 的 shortname 等照常由游戏元数据在运行时提供。

### 决策 3：认领实例的删除是记录级删除（默认不动容器）

- `adopted` 标记（runtime_metadata 内）参与删除语义：删除认领实例默认**只删实例记录、不删容器**。
- 前端删除确认弹窗对 adopted 实例提供"同时删除容器"勾选（默认不勾），勾选后调用删除接口携带显式参数才执行 docker rm。

### 决策 4：入口与预填

- 入口两处：容器列表操作列 + 容器详情页 header，均命名"识别为实例"；`isLinked=true` 的容器不显示。
- 认领对话框自动预填：实例名=容器名、端口映射填入 `portConfig`、按镜像名猜测游戏预选（猜测可改，不自动提交）；高级折叠区可改名称/备注/compose 字段。

### 决策 5：顺带修复容器详情页字段错位（同一验收前提）

`container.vue` 三处读错后端字段导致关联永远显示"未关联"：`linkedInstanceName`→应为 `linkInfo.instanceName`、`linkedInstanceId`→`linkInfo.instanceId`、`containerIdFull`→`containerId`，以及环境变量 tab `environment`→`env`。

### 范围外

- `docker_container_link` 表与 `POST /docker/links` 孤岛机制本次不动（不删除、不联动），后续如无使用场景另行 ADR 处置。
- 容器→实例的**批量自动发现/导入**（扫描主机全部无主容器批量认领）不做，单容器手动认领已覆盖当前诉求。

## 备选方案（Alternatives）

| 方案 | 描述 | 否决理由 |
|------|------|---------|
| 复用 `POST /docker/links` 轻量关联 | 只插 link 表记录 | 该表不影响 isLinked、不参与启停/同步，是展示摆设，"识别成实例"名不副实 |
| deployType 固定 docker | 所有认领实例都是 docker 型 | 部分游戏元数据仅支持 docker-compose，固定 docker 越过游戏约束 |
| 纯镜像名自动判定游戏，不可改 | 零交互 | 镜像命名不可靠，误判后果是启停/RCON 配置全错 |
| 认领后仍走完整创建流程（含部署） | 复用 createInstance | deployAsync 会对运行中容器重复建容器，破坏性 |
| 删除认领实例时维持现状（可能删容器） | 仅加警告文案 | 误删真实服务的代价过高，安全不能依赖一句文案 |
| 迁移旧库数据代替本功能 | 一次性 SQL 搬运 | 只解决本次事故，不解决"平台上来的容器无法纳管"的长期需求；两者不互斥 |
