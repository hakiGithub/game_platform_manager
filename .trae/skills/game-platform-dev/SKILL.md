---
name: "game-platform-dev"
description: "Game Platform Manager 项目开发约定与模式指南。在修改部署适配器、实例生命周期、SSH/Docker 交互、前端定时器或 compose 配置时调用，确保符合项目既有约定。"
---

# Game Platform Manager 开发约定

本 skill 总结自 game_platform_manger 项目的 git 提交历史，记录后端部署适配器、实例生命周期、SSH 交互、前端集成等方面的关键约定。修改相关代码前应先查阅，保持一致性。

## 1. 部署适配器（DeployAdapter）模式

项目支持四种部署类型：`native` / `docker` / `docker-compose` / `linuxgsm-docker`，通过 `DeployAdapterFactory.getAdapter(deployType)` 获取对应适配器。

### 关键约定
- 所有适配器继承 `AbstractDeployAdapter`，实现 `validateEnvironment` / `preDeploy` / `deploy` / `start` / `stop` / `restart` / `uninstall` / `getDetails` / `healthCheck` 等方法。
- 适配器方法接收 `Long instanceId, Map<String, Object> config, DeployProgressCallback callback`，通过 `getInstanceHostInfo(instanceId)` 获取实例和主机信息。
- 非交互式调用（如 `deleteInstance` 触发的 uninstall）使用 `DeployProgressCallback.NO_OP` 空回调。
- `buildDeployConfig(instance)` 构建完整配置，已将 `runtimeMetadata` 中的 `projectName`/`workDir`/`containerName`/`shortname`/`serviceName` 提升到 config 顶层（`promoteMetadataIfAbsent`），确保 uninstall/stop/start 能还原部署时路径。

### Docker Compose 命令检测
- `DockerComposeAdapter` 和 `LinuxGsmDockerAdapter` 都需动态检测主机支持的 compose 命令，优先 `docker compose`（CLI 插件），回退 `docker-compose`（独立二进制），结果缓存在 `ConcurrentHashMap<hostId, command>`。
- 执行 compose 命令前必须 `cd workDir`，确保 compose 文件路径正确。
- `logs` 命令必须添加 `--no-color` 参数，去除 ANSI 颜色控制字符；并使用 `stripAnsiCodes` 正则 `\u001B\[[0-9;]*[a-zA-Z]` 清理残留转义序列。

### LinuxGSM Docker 适配器特殊约定
- `executeLinuxGsmCommand` 必须使用 `docker exec -w /app` 而非 `bash -lc '...'`，避免 SSH 远程 shell 嵌套单引号解析问题导致 exit code 127。
- `healthCheck` 只验证容器运行状态（`docker inspect State.Running`），不调用 monitor 命令；因为容器 entrypoint 启动后会异步执行 SteamCMD 下载+auto-install，游戏服务器就绪需数分钟，部署后立即 monitor 必失败。
- `uploadComposeFile` 必须通过 `injectRequiredCaCerts` **无条件**注入 `/etc/ssl/certs/ca-certificates.crt` 挂载（只读），这是 LinuxGSM 框架访问 GitHub 下载 serverlist.csv 的基本需求，不依赖 `mountHostCerts` 配置；缺失会导致 curl SSL 验证失败。
- `ensureVolumesDeclaration` 自动注入顶级 `volumes:` 声明，避免 compose 配置验证失败。
- start/stop/restart 命令需检查输出关键词（started/already running/stopped/not running）而非 exit code，因为 LinuxGSM 在服务器已运行/未运行时返回非零 exit code。

### mountHostCerts 配置
- 部署级通用选项，三种 docker 类部署（docker/docker-compose/linuxgsm-docker）统一支持，默认关闭。
- 前端用户在部署向导选择，通过 `configInfo` 覆盖 yml 默认值。
- `DockerAdapter` 通过 `docker run -v` 参数挂载证书；`DockerComposeAdapter` 和 `LinuxGsmDockerAdapter` 通过 `injectHostCertsMount` 方法注入 compose volumes 项。

## 2. SSH 交互约定

### 连接池模式（必须使用）
- `SshUtil` 使用共享 `SshClient`（懒启动）+ `ConcurrentHashMap<HostKey, CachedSession>` 会话池，按 `host+port+username` 复用已认证会话。
- `executeCommand` 和 SFTP 操作（uploadFile/downloadFile/listFiles/deleteFile）通过 `executeWithRetry` / `executeSftpWithRetry` 复用会话，失效自动重建。
- 后台守护线程每 60s 清理空闲超时会话（5 分钟），`@PreDestroy` 优雅关闭。

### SSH 认证逻辑（必须遵守）
- 优先使用解析后的私钥（通过 `parsePrivateKey` 方法），其次使用解密后的密码。
- **禁止**将用户名作为密码传递给 `session.addPasswordIdentity`。
- `HostConnection` 内类必须包含 `password` 字段，`getHostConnection` 读取并解密 `host.getSshPassword()`。

### executeCommand exit code 处理
- `SshUtil.executeCommand` 必须在 catch 块中解析 exit code 并保留 stdout/stderr，因为 LinuxGSM 的 start/stop 命令在服务器已运行/未运行时返回非零 exit code 但输出包含成功标识。

## 3. 实例生命周期管理

### 状态码
- `runStatus`: 1=运行中, 2=异常, 5=部署中, 其他见 `GameInstance` 枚举。
- 前端 `status` 字段为字符串：running/stopped/error/starting/stopping/deploying。

### createInstance 流程
1. 校验实例名、主机、游戏
2. 插入数据库（`runStatus=5` 部署中）
3. 触发 `deployService.deployAsync(context)` 异步部署
4. 部署失败标记 `runStatus=2`

### deleteInstance 流程
- **必须**调用 `adapter.uninstall(id, config, DeployProgressCallback.NO_OP)` 完全清理远程资源（停止+删除容器+删除工作目录）。
- 卸载失败**不**阻止数据库记录删除，继续物理删除（`physicalDeleteById`，避免逻辑删除后 `instance_name` UNIQUE 约束冲突）。
- 删除前通知 `pluginLifecycleHook.executeInstanceDeleteHooks`。

### 实例详情接口拆分
- `GET /instances/{id}`：静态接口，快速响应，无 SSH/Docker 调用。
- `GET /instances/{id}/metrics`：动态接口，拉取 CPU/内存/运行时长。
- 前端详情页先渲染静态数据，异步加载动态数据，15 秒定时刷新（静默更新，无转圈）。

### InstanceVO 字段
- 必须包含 `iconUrl` 字段，`convertToVO` 时从 `GameMetadata.iconUrl` 关联填充。
- 必须包含 `hostName`、`hostIp`、`gameName` 等关联字段。

## 4. 前端约定

### 三种运行模式
通过 `detectMode()` 函数区分：
- **Wujie 插件模式**：路由 `createWebHistory('/plugin/l4d2/ui/')`
- **Standalone 部署模式**：路由 `createWebHistory('/ui/')`，新增实例选择页，通过 `/api/standalone/instances` 获取实例列表
- **Vite 开发模式**：开发环境

### 定时器管理（防泄漏）
- `startPolling()` 开头**必须**先调用 `stopPolling()` 清除旧定时器，避免多个 watch 同时触发时定时器变量被覆盖导致泄漏。
- 组件 `onBeforeUnmount` 必须清理所有定时器。

### 部署向导
- 必须展示游戏所有默认端口（`defaultPorts` Map），主端口 `game` 单独输入，其他端口（query/rcon/steam）作为附加端口展示并允许编辑。
- 环境校验 `performEnvCheck` 必须并发校验所有端口（主端口+附加端口），用 `Promise.all` 调用 `checkPort` API。
- 部署路径默认使用 SSH 用户家目录（`~/games/${gameCode}`）而非 `/opt/`，避免普通用户权限不足。

### 列表自动刷新
- 存在 deploying/starting/stopping 状态时启动 5 秒定时刷新，否则停止。
- 状态比较使用字符串（`i.status === "running"`）而非整数。

## 5. 游戏元数据配置（yml）

### 结构
```yaml
game:
  code: minecraft          # 游戏编码（唯一）
  name: Minecraft Server
  icon: /icons/mc.png
  deployTypes: [docker, linuxgsm-docker]
  defaultPorts:
    game: 25565
    query: 25566
  linuxgsmDocker:
    shortname: mcserver
    imageRepo: gameservermanagers/gameserver
    imageTag: mc
    composeTemplate: |
      services: ...
    variables:
      - name: CONTAINER_NAME
        defaultValue: "mcserver-lgsm"
    namedVolumes:
      - mc-lgsm-data
```

### 注意事项
- 138 款游戏配置文件在 `backend/core/src/main/resources/games/`，启动时由 `GameMetadataScanner` 扫描加载。
- `composeTemplate` 中的 `${VAR:-default}` 语法在生成 .env 文件时需从 `variables.defaultValue` 回填。
- `namedVolumes` 必须与 compose 模板中的卷名一致。

## 6. 数据库约定

- 主键：`AUTO` 自增（游戏实例等）或雪花 ID（扩展资源，String 类型）。
- 敏感数据（SSH 密码）AES 加密存储。
- 逻辑删除字段 `is_deleted`，但实例删除使用物理删除避免 UNIQUE 约束冲突。
- `MyMetaObjectHandler` 自动填充 `create_time` / `update_time`。

## 7. 构建与部署

### 后端
```bash
cd backend
mvn install -pl api,plugin,core -am -DskipTests  # api 模块字段变更后必须先 install
mvn spring-boot:run -pl core                      # 启动
```
- **重要**：`api` 模块 `InstanceVO` 等字段变更后，必须先 `mvn install -pl api,plugin,core -am -DskipTests`，否则本地仓库 stale JAR 报 `NoSuchMethodError`。

### 前端
```bash
cd frontend
npm run dev    # Vite 开发，端口 3000
npm run build  # 构建到 ../plugin-l4d2-core/src/main/resources/ui
```

### 子模块提交流程
1. 在 `backend/` 和 `frontend/` 子仓库分别 commit
2. 在主仓库 `git add backend frontend` 更新子模块引用
3. 主仓库 commit

## 8. 常见陷阱

- **WSL2 DNS 污染**：可能导致 Docker Hub 连接失败（`registry-1.docker.io` 被解析为 `127.0.0.1`），需手动解决 DNS。
- **大镜像拉取**：10GB+ 镜像需先手动 `docker pull` 完成下载，避免部署时拉取超时。
- **docker-compose V1 vs V2**：V1 `ps` 输出 `Up`，V2 输出 `running`，`deploy` 和 `getStatus` 方法需同时兼容。
- **家目录展开**：`~` 简写在 SFTP 上传前必须通过 `resolveWorkDir` 展开为绝对路径。
- **runtimeMetadata 持久化**：preDeploy 中展开 `~` 后的 workDir 只写入 `runtimeMetadata`，不回写 `configInfo`，后续操作需通过 `buildDeployConfig` 的 `promoteMetadataIfAbsent` 还原。
