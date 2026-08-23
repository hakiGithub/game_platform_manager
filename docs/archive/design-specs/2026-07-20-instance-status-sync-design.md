# 实例状态双向同步设计

> 设计日期: 2026-07-20
> 状态: 已批准，待制定实现计划
> 关联模块: backend/core

---

## 1. 背景与目标

### 1.1 现状

平台通过 `game_instance` 表维护游戏实例记录，`run_status` 字段记录运行状态（0-已停止、1-运行中、2-异常等）。现状存在以下问题：

1. **状态脱节**：用户在主机上手动启动/停止容器，或容器被外部删除，平台 `run_status` 不会自动更新
2. **遗漏实例**：主机上运行的游戏服务器未在平台登记时，平台无法感知
3. **重启遗漏**：应用重启后，无法校正停机期间主机上发生的状态变化
4. **containerId 持久化不统一**：3 个 Docker 类适配器写入位置不一致
   - `DockerAdapter`：写入 `install_path` 列
   - `DockerComposeAdapter`：写入 `runtime_metadata` JSON 的 `containerId` 键
   - `LinuxGsmDockerAdapter`：不写 containerId，仅写 `containerName`

### 1.2 目标

实现主机容器/进程状态与平台实例状态的**双向同步**：

- 启动时执行一次全量同步，校正停机期间的状态变化
- 运行期定时同步（5 分钟周期），感知外部启停
- 严格匹配规则，避免误识别
- 以主机实际状态为准，更新平台 `run_status`
- 主机上未关联已知游戏的容器不处理、不记录（用户决策）

### 1.3 非目标（YAGNI）

- 不识别未关联游戏的容器（如 nginx、mysql 等非游戏容器）
- 不实时同步（用户操作时实时校验容器状态）
- 不自动删除实例记录（容器被删除仅置 `run_status=0`）
- 不修改现有适配器的 containerId 持久化方式
- 不新增前端 API（同步为后台任务）

---

## 2. 整体架构

### 2.1 组件清单

| 组件 | 类型 | 位置 | 职责 |
|------|------|------|------|
| `InstanceSyncService` | 接口 | `backend/core/.../service/InstanceSyncService.java` | 同步业务契约 |
| `InstanceSyncServiceImpl` | 实现 | `backend/core/.../service/impl/InstanceSyncServiceImpl.java` | 协调 Docker 同步 + Native 同步，单主机维度执行 |
| `DockerInstanceSyncStrategy` | 策略类 | `backend/core/.../service/sync/DockerInstanceSyncStrategy.java` | Docker 类部署的同步逻辑（3 个适配器共用） |
| `NativeInstanceSyncStrategy` | 策略类 | `backend/core/.../service/sync/NativeInstanceSyncStrategy.java` | Native 部署的 pgrep 进程检测 |
| `InstanceSyncStartupRunner` | ApplicationRunner | `backend/core/.../listener/InstanceSyncStartupRunner.java` | 启动时异步触发一次全量同步 |
| `HostMonitorTask` | 扩展 | `backend/core/.../task/HostMonitorTask.java`（已有） | 在 `refreshAllHostsStatus()` 后追加 `instanceSyncService.syncAll()` |

### 2.2 调用关系

```
应用启动 → InstanceSyncStartupRunner.run()
                  ↓ (异步, @Async)
                  → InstanceSyncService.syncAll()
                          ↓ 遍历在线主机
                          → DockerInstanceSyncStrategy.syncHost(host, instances)
                          → NativeInstanceSyncStrategy.syncHost(host, instances)

HostMonitorTask (5min周期)
    → hostService.refreshAllHostsStatus()  // 已有
    → instanceSyncService.syncAll()         // 新增
```

### 2.3 单元边界

- **InstanceSyncService**：协调入口，只负责"遍历主机 + 分发到策略"，不关心具体检测细节
- **DockerInstanceSyncStrategy**：负责"列出主机所有容器 → 匹配实例 → 状态对账"，依赖 `DockerContainerLinkService` 与 `InstanceService`
- **NativeInstanceSyncStrategy**：负责"遍历主机上 Native 实例 → pgrep 检测 → 状态对账"，依赖 `SshUtil` 与 `InstanceService`
- 两个策略类互不依赖，可独立单测

---

## 3. 匹配规则

### 3.1 Docker 类部署的匹配流程

**输入**：主机上所有容器列表（`docker ps -a`）+ 主机上所有 Docker 类实例（DB）

**单实例匹配流程**（按优先级降序）：

#### 第 1 级：容器ID 精确匹配（最高优先级）

读取实例的容器ID（任一处存在即用）：
- `runtime_metadata.containerId`（DockerComposeAdapter 写入）
- `install_path`（DockerAdapter 写入容器ID）
- `docker_container_link.container_id`（已有连接记录）

与主机容器的 `ID` 字段精确比对，命中即匹配成功。

#### 第 2 级：容器名精确匹配

读取实例预期容器名：
- DockerAdapter：`game-instance-{instanceId}` 或 `config_info.containerName`
- DockerComposeAdapter：compose 项目名 `<projectName>` 或 `runtime_metadata.containerName`
- LinuxGsmDockerAdapter：`runtime_metadata.containerName` 或 `config_info.containerName`

与主机容器的 `Names` 字段精确比对（区分大小写）。

#### 第 3 级：多字段严格匹配（兜底）

同时满足以下全部条件才算匹配：
- 镜像名一致（主机容器 `Image` 与实例 `config_info.image` 或游戏元数据 `deploy_config.docker.image` 一致）
- 主端口一致（主机容器 `Ports` 暴露的端口与实例 `port_config.game` 一致）
- 容器名包含实例的 `gameCode` 或 `instanceName` 关键字（子串匹配，不区分大小写）

**任一不一致不匹配**（严格匹配原则）。

#### 第 4 级：匹配失败

- 实例在主机上找不到任何对应容器 → 状态置为 `0`（已停止），`remark` 写入"容器不存在（已被外部删除）"
- 主机上有未匹配的容器 → **不处理**（仅识别关联已知游戏）

### 3.2 Native 部署的匹配流程

通过 SSH 执行 `pgrep -f "<startCommand 关键部分>"` 检测进程：

- **startCommand 解析规则**：取命令中的可执行文件名或 `-game` 参数值（如 `./srcds_run -game left4dead2` → 关键部分 `left4dead2`），避免完整命令中的引号/转义问题
- **返回值判断**：
  - exit code 0（找到进程）→ RUNNING
  - exit code 1（无进程）→ STOPPED
  - 其他 exit code → 跳过该实例（命令异常）

### 3.3 匹配数据来源汇总

| 字段 | Docker 类来源 | Native 类来源 |
|------|--------------|---------------|
| 容器ID | `runtime_metadata.containerId` / `install_path` / `docker_container_link.container_id` | 不适用 |
| 容器名 | `runtime_metadata.containerName` / `config_info.containerName` / `game-instance-{id}` | 不适用 |
| 镜像名 | `config_info.image` / `game_metadata.deploy_config.docker.image` | 不适用 |
| 端口 | `port_config.game` / `port_config.{query,rcon,steam}` | `port_config.game` |
| 进程标识 | 不适用 | `start_command` 解析 |

---

## 4. 状态对账规则

### 4.1 对账表（以主机为准）

| 主机实际状态 | 平台当前 run_status | 是否更新 | 新 run_status | remark |
|--------------|---------------------|----------|---------------|--------|
| 容器运行中 | 1（运行中） | 否 | 1 | 清空 |
| 容器运行中 | 0/2/3/5/6 | **是** | 1 | 清空 |
| 容器已退出 | 0（已停止） | 否 | 0 | 清空 |
| 容器已退出 | 1/2/3/5/6 | **是** | 0 | 写"容器已退出" |
| 容器不存在 | 0 | 否 | 0 | 写"容器不存在" |
| 容器不存在 | 1/2/3/5/6 | **是** | 0 | 写"容器不存在（已被外部删除）" |
| pgrep 找到进程 | 0/2/3/5/6 | **是** | 1 | 清空 |
| pgrep 无进程 | 1/2/3/5/6 | **是** | 0 | 写"进程未运行" |

### 4.2 关键约束

- 状态 5（部署中）和 6（启动中）也参与对账（避免部署卡死时同步覆盖）
- 仅当主机与平台不一致时才执行 UPDATE，避免无意义的 DB 写入
- 不删除实例记录，仅更新 `run_status` 和 `remark`
- 同步修改 `run_status` 时**不触发** `InstanceStatusChangedEvent`（避免与用户手动操作的事件混淆，影响前端推送）

---

## 5. 数据流与异常处理

### 5.1 同步流程详解（单次 syncAll 执行）

```
InstanceSyncService.syncAll()
    │
    ├─ 1. hostService.getOnlineHosts()  // 复用现有方法，跳过离线主机
    │
    ├─ 2. for each host (并行流, 复用 SSH 连接池):
    │       │
    │       ├─ 2.1 instanceMapper.selectByHostId(hostId)  // 该主机所有实例
    │       │
    │       ├─ 2.2 按 deployType 分组:
    │       │      docker / docker-compose / linuxgsm-docker → DockerInstanceSyncStrategy
    │       │      native → NativeInstanceSyncStrategy
    │       │
    │       ├─ 2.3 DockerInstanceSyncStrategy.syncHost(host, dockerInstances)
    │       │      ├─ dockerContainerLinkService.getContainers(host)  // SSH: docker ps -a
    │       │      ├─ for each instance: 匹配容器 → 状态对账 → 写 DB（仅不一致时）
    │       │      └─ 异常隔离：单实例失败不影响其他实例
    │       │
    │       └─ 2.4 NativeInstanceSyncStrategy.syncHost(host, nativeInstances)
    │              ├─ for each instance: SSH pgrep → 状态对账 → 写 DB
    │              └─ 异常隔离
    │
    └─ 3. 记录整体同步结果到日志（成功/失败计数、耗时）
```

### 5.2 并发控制

- 使用 `hostService.getOnlineHosts().parallelStream()` 并行处理多台主机
- 复用现有 `SshUtil` 连接池（已实现 `ConcurrentHashMap<HostKey, CachedSession>`），不会因并发导致 SSH 连接泄漏
- 单主机内部串行处理该主机的所有实例（避免对同一台主机并发 SSH 命令过多）

### 5.3 异常处理策略

| 异常类型 | 处理方式 | 用户感知 |
|----------|----------|----------|
| SSH 连接失败（主机不可达） | 跳过该主机所有实例，记录 WARN 日志 | 实例状态保持不变，下次周期重试 |
| `docker ps` 命令失败 | 跳过该主机 Docker 类实例，记录 WARN 日志 | 同上 |
| `pgrep` 命令失败 | 跳过该 Native 实例，记录 WARN 日志 | 同上 |
| 单实例匹配异常 | 跳过该实例，继续处理其他实例 | 该实例状态保持不变 |
| DB 更新失败 | 记录 ERROR 日志，不影响其他实例 | 下次周期重试 |
| 整体同步异常 | 记录 ERROR 日志，不影响下次调度 | 下次周期重试 |

**核心原则**：异常不传播，单点失败不扩散。同步是"尽力而为"的操作，不能因一台主机或一个实例的失败导致整个同步任务崩溃。

### 5.4 关键设计决策

1. **不使用事务**：单次同步可能涉及多个实例的 UPDATE，每个实例独立提交，避免长事务锁表
2. **不批量更新**：每个实例单独 UPDATE，便于异常隔离和日志追踪
3. **乐观更新**：先比较当前状态与目标状态，仅在不一致时才 UPDATE，减少 DB 写入
4. **不触发业务事件**：同步修改 `run_status` 时不触发 `InstanceStatusChangedEvent`

### 5.5 启动时同步的并发控制

```java
@Component
public class InstanceSyncStartupRunner implements ApplicationRunner {
    @Async("taskExecutor")  // 复用现有异步线程池
    @Override
    public void run(ApplicationArguments args) {
        // 延迟 10 秒启动，避免与 PluginAutoLoader / SchemaMigrationRunner 抢资源
        Thread.sleep(10_000);
        instanceSyncService.syncAll();
    }
}
```

- 异步执行，不阻塞应用启动
- 延迟 10 秒，让插件加载、数据库迁移等先完成
- 与 `DeployRecoveryListener` 并行执行，互不干扰

---

## 6. 测试策略

复用现有测试金字塔比例（Unit 30% / Component 40% / Integration 20% / E2E 10%），重点覆盖匹配规则和状态对账。

### 6.1 单元测试

#### DockerInstanceSyncStrategyTest（预计 12-15 个用例）

| 测试场景 | 验证点 |
|----------|--------|
| 容器ID 精确匹配（runtime_metadata.containerId） | 匹配成功，状态对账正确 |
| 容器ID 精确匹配（install_path） | 匹配成功 |
| 容器ID 精确匹配（docker_container_link） | 匹配成功 |
| 容器名精确匹配 | 匹配成功 |
| 多字段严格匹配（镜像+端口+名称全中） | 匹配成功 |
| 多字段匹配：镜像不一致 | 不匹配 |
| 多字段匹配：端口不一致 | 不匹配 |
| 多字段匹配：名称前缀不包含关键字 | 不匹配 |
| 容器运行中 + 平台状态 0 | 更新为 1 |
| 容器运行中 + 平台状态 1 | 不更新 |
| 容器已退出 + 平台状态 1 | 更新为 0，remark 写入 |
| 容器不存在 + 平台状态 1 | 更新为 0，remark 写入 |
| 部署中（状态 5）+ 容器运行中 | 更新为 1 |
| 主机上有未知容器 | 不处理，不新增实例 |

#### NativeInstanceSyncStrategyTest（预计 6-8 个用例）

| 测试场景 | 验证点 |
|----------|--------|
| pgrep 返回 0 + 平台状态 0 | 更新为 1 |
| pgrep 返回 1 + 平台状态 1 | 更新为 0 |
| pgrep 命令异常 | 跳过该实例，不更新 |
| startCommand 为空 | 跳过该实例 |
| startCommand 含复杂转义 | 正确解析关键部分 |
| 状态一致 | 不触发 UPDATE |

#### InstanceSyncServiceImplTest（预计 5-6 个用例）

| 测试场景 | 验证点 |
|----------|--------|
| syncAll 正常流程 | 调用策略类正确次数 |
| 主机离线 | 跳过该主机 |
| SSH 异常 | 不影响其他主机 |
| 无在线主机 | 直接返回 |
| 按部署类型正确分发策略 | Docker 类→Docker策略，Native→Native策略 |

### 6.2 集成测试

复用现有 `instance-system-test` profile（H2 + Mockito mock SshUtil）：

| 测试场景 | 验证点 |
|----------|--------|
| 启动时同步触发 | `InstanceSyncStartupRunner` 异步调用 `syncAll` |
| HostMonitorTask 触发同步 | 5 分钟周期后 `syncAll` 被调用 |
| 端到端 Docker 同步 | mock SSH 返回容器列表 → DB 状态正确更新 |
| 端到端 Native 同步 | mock SSH 返回 pgrep 结果 → DB 状态正确更新 |

### 6.3 测试工具

- **Mockito**：mock `SshUtil.executeCommand()` 返回预设的容器列表/pgrep 结果
- **H2 数据库**：复用 `data-h2.sql` 准备测试实例数据
- **@SpringBootTest** + `@ActiveProfiles("test")`：集成测试

---

## 7. 可观测性

### 7.1 日志规范

所有同步日志统一前缀 `[InstanceSync]`，便于检索：

```
INFO  [InstanceSync] 开始同步所有主机实例状态
INFO  [InstanceSync] 主机 host-1 (192.168.1.100): 在线，开始同步 5 个实例
WARN  [InstanceSync] 主机 host-2 (192.168.1.101): SSH 连接失败，跳过 3 个实例
INFO  [InstanceSync] 实例 #1001 (l4d2-server): 状态变更 1→0 (容器已退出)
INFO  [InstanceSync] 实例 #1002 (minecraft-server): 状态未变化 (1→1)，跳过更新
INFO  [InstanceSync] 同步完成: 成功 8, 失败 2, 跳过 1, 耗时 3.2s
ERROR [InstanceSync] 实例 #1003 同步异常: Docker 命令执行超时
```

### 7.2 操作日志

同步触发的状态变更**不写入** `operation_log` 表（避免日志膨胀，每 5 分钟一次同步会产生大量记录）。仅通过应用日志（`application.log`）记录。

---

## 8. 配置项

在 `application.yml` 新增可配置项（默认值即可用，无需用户必填）：

```yaml
game-platform:
  instance-sync:
    enabled: true                          # 是否启用同步
    startup-sync-delay-ms: 10000           # 启动时同步延迟（毫秒）
    log-level: INFO                        # 同步日志级别
```

- `enabled: false` 时，`InstanceSyncStartupRunner` 和 `HostMonitorTask` 中的同步逻辑跳过
- 便于在测试环境快速关闭同步功能

---

## 9. 影响范围

### 9.1 新增文件（6 个 + 4 个测试）

| 文件 | 模块 | 行数估计 |
|------|------|----------|
| `InstanceSyncService.java`（接口） | backend/core | ~30 |
| `InstanceSyncServiceImpl.java` | backend/core | ~150 |
| `DockerInstanceSyncStrategy.java` | backend/core | ~250 |
| `NativeInstanceSyncStrategy.java` | backend/core | ~120 |
| `InstanceSyncStartupRunner.java` | backend/core | ~40 |
| 测试文件 ×4（单元 + 集成） | backend/core/src/test | ~600 |

### 9.2 修改文件（3 个）

| 文件 | 改动点 |
|------|--------|
| `HostMonitorTask.java` | 在 `refreshAllHostsStatus()` 后追加 `instanceSyncService.syncAll()` 调用（约 5 行） |
| `application.yml` | 新增 `game-platform.instance-sync` 配置块（4 行） |
| `application-test.yml` | 同步配置关闭或保持默认（2 行） |

### 9.3 不修改的文件（关键约束）

- **3 个适配器**（`DockerAdapter` / `DockerComposeAdapter` / `LinuxGsmDockerAdapter`）：不改动现有部署/启动/停止逻辑，containerId 持久化方式保持现状
- **`GameInstance` 实体与 schema**：不新增 `container_id` 列（复用 `runtime_metadata` / `install_path` / `docker_container_link` 三处已有数据源）
- **`docker_container_link` 表**：不改动结构，仅作为只读数据源
- **`InstanceController`**：不新增 API（同步是后台任务，无需前端触发）
- **前端**：完全无改动（状态变更通过现有的轮询/WS 推送自动反映）

---

## 10. 已知限制

1. **LinuxGsmDockerAdapter 的 containerId 缺失**：该适配器部署后不写 containerId，仅写 containerName。匹配时走容器名或多字段严格匹配，仍可正常工作，但精度略低于其他两个适配器
2. **Native 部署的 startCommand 解析**：pgrep 用关键部分匹配（如 `left4dead2`），可能存在同名进程误报。限制：解析时取 `startCommand` 中第一个 `-game` 参数值或可执行文件名
3. **同步精度**：5 分钟周期意味着状态变更最多有 5 分钟延迟。用户在前端操作实例时，Controller 层不实时校验容器状态
4. **多主机并发 SSH**：使用 `parallelStream`，并发度受 JVM `ForkJoinPool` 默认并行度限制（通常 = CPU 核数）。如未来主机规模 > 20 台，需评估是否引入自定义线程池
5. **不识别外部启动的未知容器**：仅识别关联已知游戏，主机上非游戏容器不会被记录到 `docker_container_link`

---

## 11. 与现有功能的协作关系

| 现有功能 | 协作方式 |
|----------|----------|
| `DeployRecoveryListener`（启动时处理部署卡死） | 并行执行，互不干扰。`DeployRecoveryListener` 处理状态 5 的实例；`InstanceSyncStartupRunner` 处理状态 0/1/2/3/6 的实例 |
| `DeployRecoveryTask`（60 秒周期） | 互不冲突。`DeployRecoveryTask` 仅处理状态 5 且 2 分钟未更新的实例；同步任务处理其他状态 |
| `HostMonitorTask`（5 分钟周期） | 同步逻辑追加在主机状态刷新之后，确保 `hostService.getOnlineHosts()` 拿到的是最新在线状态 |
| 用户手动启动/停止实例 | 同步不触发业务事件，不会与用户操作产生冲突。如用户刚点停止（状态置 3 stopping），下一轮同步若发现容器已退出会置 0（符合预期） |

---

## 12. 未来扩展点（YAGNI，本次不实现）

1. **前端"同步"按钮**：如需手动触发同步，可新增 `POST /api/instances/sync` API
2. **WebSocket 推送同步结果**：同步完成后推送变更的实例列表到前端
3. **自动纳管未知容器**：将主机上未关联的容器记录到 `docker_container_link`，前端提供"纳管"按钮
4. **Prometheus 指标**：暴露同步耗时、状态变更次数等监控指标
5. **可配置周期**：通过 `application.yml` 调整同步频率（当前复用 HostMonitorTask 的 5 分钟）

---

## 13. 验收标准

| # | 验收项 | 验证方式 |
|---|--------|----------|
| 1 | 应用启动后 10 秒内触发首次同步 | 查看日志 `[InstanceSync] 开始同步所有主机实例状态` |
| 2 | 5 分钟周期触发同步 | 等待 5 分钟后查看日志 |
| 3 | 主机上容器运行但平台状态为 0 | 5 分钟内平台状态变更为 1 |
| 4 | 主机上容器停止但平台状态为 1 | 5 分钟内平台状态变更为 0，remark 写入原因 |
| 5 | 主机上容器被删除 | 同上，remark 写"容器不存在" |
| 6 | SSH 连接失败的主机 | 该主机实例状态不变，其他主机正常同步 |
| 7 | 同步过程中单实例异常 | 该实例跳过，其他实例正常处理 |
| 8 | 配置 `enabled: false` | 同步逻辑不执行 |
| 9 | Native 部署实例通过 pgrep 检测 | 进程存在→1，进程不存在→0 |
| 10 | 单元测试覆盖率 ≥ 80% | `mvn test jacoco:report` |

---

## 14. 开发前提交约束

> 用户明确要求：开发前先提交当前已有的代码变更。

开发实施前需要先执行 git commit，将本次设计文档及之前会话中已完成的修改（脚本集成、UI 测试文档等）提交到版本库，确保实施阶段的代码变更与历史清晰隔离。

---

*设计完成日期: 2026-07-20*
