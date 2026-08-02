# 部署任务执行与实例状态机管理 - 设计文档

> 日期: 2026-07-16
> 状态: 已批准
> 范围: 主应用 core 模块 + 前端主应用

---

## 1. 背景与问题

### 1.1 问题描述

用户点击"开始部署"后，后端 `POST /instances` 返回成功，但实例列表没有任何实例正在处理部署。经排查：

- `InstanceController.create` 只调用 `instanceService.createInstance()` 插入实例记录（`runStatus=0` 已停止），**未触发任何部署任务**
- 前端 `handleDeploy` 期望返回 `deployTaskId`，但后端 `InstanceVO` 无该字段 → `DeployProgress` 弹窗不会打开
- 前端 `getDeployProgress(taskId)` 调用 `GET /instances/deploy-progress/{taskId}`，但后端**完全没有这个端点**
- `DeployService.deploy()` 已实现完整生命周期（环境校验→端口→预部署→部署→健康检查→启动），但**从未被 createInstance 调用**
- 状态映射不一致：后端 `runStatus` 数字（0/1/2/3），前端期望字符串（`running/stopped/deploying`）

### 1.2 目标

1. 创建实例后自动触发异步部署任务
2. 实例列表显示"部署中"状态，提供"查看日志"按钮
3. 日志弹窗实时显示部署进度和阶段日志
4. Docker 部署的运行中实例可查看容器实时日志
5. 完整的实例状态机，含健康检查判定部署成功/失败
6. 应用重启后自动恢复中断的部署任务

### 1.3 方案选择

**方案 A（已选）：最小集成** — 复用现有 `DeployService.deploy()` 和 `DeployProgress.vue`，HTTP 轮询获取进度。

优点：改动最小，复用已实现的完整部署生命周期和三个真实适配器（Docker/LinuxGSM/DockerCompose），见效最快。
缺点：任务状态存内存（`ConcurrentHashMap`），重启丢失（通过启动恢复机制缓解）。

放弃方案：独立 DeployTaskService（过度设计）、内存+DB 持久化（部署是短时任务，持久化价值低）。

---

## 2. 整体架构与数据流

### 2.1 核心改动

**后端（5 处）：**

1. `InstanceServiceImpl.createInstance()` — 创建实例（`runStatus=5` 部署中）后，异步调用 `deployService.deployAsync(context)`，返回 InstanceVO（含 `deployTaskId=instanceId`、`status="deploying"`）
2. `DeployService.DeployTaskStatus` — 新增 `List<LogEntry> logs` 字段，通过 `DeployProgressCallback.onLog/onProgress/onStageStart` 回调实时累积日志
3. `InstanceController` — 新增 `GET /instances/{id}/deploy-progress` 端点，返回 `{progress, status, statusText, logs[], completed, success, error}`
4. `InstanceController.getLogs()` — 增强现有 `/{id}/logs` 端点：
   - 部署中（`runStatus=5`）：返回 `DeployService` 内存中的部署日志
   - 运行中 Docker 容器：通过 SSH 执行 `docker logs --tail N <container>` 获取容器日志
5. 状态映射 — `InstanceVO` 新增 `status` 字符串字段，`convertToVO` 时转换

**前端（3 处）：**

1. `deploy.vue` 的 `handleDeploy` — 创建成功后跳转回实例列表，不弹窗
2. `index.vue` 实例列表 — 用 `status` 字段判断状态，显示"部署中"标签，操作列加"查看日志"按钮
3. `DeployProgress.vue` — 复用现有组件，支持 `mode="deploy"`（轮询 deploy-progress）和 `mode="runtime"`（轮询容器日志）

### 2.2 数据流

```
[用户点击部署] → POST /instances → createInstance()
  ├─ 插入 GameInstance (runStatus=5 部署中)
  ├─ 异步 deployService.deployAsync(context) ← 后台执行部署生命周期
  └─ 返回 InstanceVO (status="deploying", deployTaskId=instanceId)
  ↓
[前端跳转回实例列表] → 列表显示"部署中"标签 + "查看日志"按钮
  ↓
[用户点击"查看日志"] → 打开 DeployProgress.vue (mode="deploy")
  ↓
[每 2s 轮询 GET /instances/{id}/deploy-progress]
  ↓ 返回 {progress, status, logs[], completed, success}

[后台 deploy() 执行：环境校验→端口→预部署→部署→健康检查→启动]
  ├─ 每阶段回调 onLog("INFO", "拉取镜像...") → 写入 logs
  └─ 完成 → runStatus=1（运行中）或 2（异常）
  ↓
[前端轮询到 completed=true] → 显示部署结果

[Docker 运行中实例查看日志] → 打开 DeployProgress.vue (mode="runtime")
  ↓ 每 2s 轮询 GET /instances/{id}/logs?lines=200
  ↓ 后端 SSH 执行 docker logs --tail 200 <container>
  ↓ 返回最新 200 行容器日志（含 stdout+stderr）
```

---

## 3. 实例状态机设计

### 3.1 状态码定义

| runStatus | status 字符串 | 描述 | 触发条件 |
|---|---|---|---|
| 0 | `stopped` | 已停止 | 部署成功且未自动启动 / 手动停止成功 |
| 1 | `running` | 运行中 | 健康检查通过 + 容器/进程存活 |
| 2 | `error` | 异常 | 部署失败 / 健康检查失败 / 启动失败 |
| 3 | `stopping` | 停止中 | 手动停止中（过渡态） |
| 5 | `deploying` | 部署中 | 创建实例后，部署任务运行中 |
| 6 | `starting` | 启动中 | 部署完成自动启动中 / 手动启动中（过渡态） |

### 3.2 状态转换图

```
                          ┌─────────────┐
                          │  creating   │ (临时，runStatus=5 前的瞬间)
                          └──────┬──────┘
                                 ↓
                    ┌────────────────────────┐
                    │   deploying (runStatus=5) │ ◄── 创建实例
                    └────────────┬───────────┘
                                 │
                  ┌──────────────┼──────────────┐
                  │              │              │
            部署失败        部署成功          部署异常
            (异常退出)     (deploy()=true)   (throw异常)
                  │              │              │
                  ↓              ↓              ↓
            ┌─────────┐  ┌──────────────┐  ┌─────────┐
            │  error  │  │  starting    │  │  error  │
            │ (rs=2)  │  │  (runStatus=6) │  │ (rs=2)  │
            └─────────┘  └──────┬───────┘  └─────────┘
                                │
                    ┌───────────┴───────────┐
                    │                       │
              健康检查通过              健康检查失败
              (healthCheck=true)      (healthCheck=false)
              + 容器存活              或容器未存活
                    │                       │
                    ↓                       ↓
              ┌──────────┐            ┌─────────┐
              │ running  │            │  error  │
              │ (rs=1)   │            │ (rs=2)  │
              └────┬─────┘            └─────────┘
                   │
            手动停止 / 重启
                   │
                   ↓
              ┌──────────┐
              │ stopping │ ──停止成功──→ stopped (rs=0)
              │ (rs=3)   │ ──停止失败──→ error (rs=2)
              └──────────┘
```

### 3.3 关键判定逻辑

**部署成功判定（DeployService.deploy）：**
- `adapter.deploy()` 返回 `true` → 部署成功
- `adapter.deploy()` 返回 `false` 或抛异常 → 部署失败 → `runStatus=2`
- 部署成功后，若 `autoStart=true`，进入 `starting` 状态

**健康检查判定（DeployService.deploy 自动启动阶段）：**
```java
if (context.isAutoStart()) {
    updateRunStatus(instanceId, 6); // starting
    if (adapter.start(instanceId, config)) {
        Thread.sleep(5000); // 等待服务启动
        boolean healthy = retryHealthCheck(adapter, instanceId, config, 3, 5000);
        if (healthy) {
            updateRunStatus(instanceId, 1); // running
        } else {
            updateRunStatus(instanceId, 2); // error
        }
    } else {
        updateRunStatus(instanceId, 2); // error
    }
}
```

**健康检查重试机制：**
- 最多重试 3 次，每次间隔 5 秒
- Docker：`isContainerRunning()` + `healthCheck.command`（如配置）
- LinuxGSM：进程存活 + 端口监听
- 全部通过才算健康

**状态持久化：**
- 每次 `runStatus` 变更，立即 `instanceMapper.updateRunStatus(id, newStatus)`
- 部署任务状态（含日志）存 `DeployService.taskStatusMap`（内存）
- 应用重启后：`runStatus=5`（部署中）的实例，启动时检测，若部署任务不存在 → 标记为 `error`（rs=2）

### 3.4 前端列表状态显示

| status | 标签 | 颜色 | 操作按钮 |
|---|---|---|---|
| `deploying` | 部署中 | warning（Loading 图标） | 查看日志 |
| `starting` | 启动中 | warning（Loading 图标） | 查看日志 |
| `running` | 运行中 | success | 停止、查看日志 |
| `stopped` | 已停止 | info | 启动、删除 |
| `error` | 异常 | danger | 重试部署、查看日志、删除 |
| `stopping` | 停止中 | warning（Loading 图标） | - |

---

## 4. 后端组件改动详细设计

### 4.1 DeployService 增强

**新增 `LogEntry` 内部类：**
```java
@Data
@AllArgsConstructor
public static class LogEntry {
    private long id;          // 日志ID（自增，前端去重用）
    private String level;     // INFO/WARN/ERROR/SUCCESS
    private String message;
    private String stage;     // 关联阶段
    private LocalDateTime time;
}
```

**`DeployTaskStatus` 新增字段：**
```java
private List<LogEntry> logs = new ArrayList<>();  // 累积日志
private long logIdCounter = 0;                     // 日志ID计数器
private String status;                             // pending/preparing/downloading/.../completed/failed
```

**新增 `LogCollectingCallback`（内部类）：**
- 实现 `DeployProgressCallback`
- 所有回调方法（`onLog/onProgress/onStageStart/onStageComplete/onError/onComplete`）都写入 `DeployTaskStatus.logs`
- `onProgress` 同时更新 `progress` 百分比
- `onStageStart` 转换为状态字符串（`preparing/downloading/installing/configuring/starting/checking`）
- `onComplete` 设置 `completed=true, success=success, status=completed/failed`

**新增 `retryHealthCheck` 方法：**
```java
private boolean retryHealthCheck(DeployAdapter adapter, Long instanceId,
                                  Map<String, Object> config,
                                  int maxRetries, long intervalMs) {
    for (int i = 0; i < maxRetries; i++) {
        if (adapter.healthCheck(instanceId, config)) {
            return true;
        }
        if (i < maxRetries - 1) {
            Thread.sleep(intervalMs);
        }
    }
    return false;
}
```

**修改 `deploy()` 方法：**
- 使用 `LogCollectingCallback` 包装传入的 callback
- 状态变更时同步更新 `GameInstance.runStatus`（5→6→1/2）
- 自动启动阶段加入健康检查重试

### 4.2 InstanceServiceImpl 改动

**`createInstance()` 方法：**
```java
@Transactional
public InstanceVO createInstance(InstanceCreateDTO dto) {
    // ... 现有校验和插入逻辑 ...
    instance.setRunStatus(5); // 部署中
    instanceMapper.insert(instance);

    // 构建部署上下文
    DeployService.DeployContext context = DeployService.DeployContext.builder()
        .instanceId(instance.getId())
        .hostId(dto.getHostId())
        .deployType(DeployAdapter.DeployType.fromCode(dto.getDeployType()))
        .config(buildDeployConfig(instance))
        .autoRollback(false)
        .autoStart(true)
        .build();

    // 异步触发部署
    deployService.deployAsync(context);

    InstanceVO vo = convertToVO(instance);
    vo.setDeployTaskId(String.valueOf(instance.getId()));
    return vo;
}

**`buildDeployConfig(instance)` 辅助方法：** 合并 `instance.getConfigInfo()` 和 `instance.getPortConfig()` 为一个扁平 Map，供适配器使用。
```java
private Map<String, Object> buildDeployConfig(GameInstance instance) {
    Map<String, Object> config = new HashMap<>();
    if (instance.getConfigInfo() != null) {
        config.putAll(instance.getConfigInfo());
    }
    if (instance.getPortConfig() != null) {
        config.putAll(instance.getPortConfig());
    }
    config.put("installPath", instance.getInstallPath());
    return config;
}
```

**`convertToVO()` 新增状态映射：**
```java
private String mapRunStatusToString(int runStatus) {
    return switch (runStatus) {
        case 0 -> "stopped";
        case 1 -> "running";
        case 2 -> "error";
        case 3 -> "stopping";
        case 5 -> "deploying";
        case 6 -> "starting";
        default -> "unknown";
    };
}
```

### 4.3 InstanceController 新增端点

**`GET /instances/{id}/deploy-progress`：**
```java
@GetMapping("/{id}/deploy-progress")
public Result<DeployProgressVO> getDeployProgress(@PathVariable Long id) {
    DeployService.DeployTaskStatus status = deployService.getTaskStatus(id);
    if (status == null) {
        return Result.fail("部署任务不存在");
    }

    DeployProgressVO vo = new DeployProgressVO();
    vo.setProgress(status.getProgress());
    vo.setStatus(status.getStatus());
    vo.setStatusText(mapStatusText(status.getStatus()));
    vo.setLogs(status.getLogs().stream().map(this::toLogVO).toList());
    vo.setCompleted(status.isCompleted());
    vo.setSuccess(status.isSuccess());
    vo.setError(status.getError());
    return Result.success(vo);
}
```

**增强 `GET /instances/{id}/logs`：**
```java
@GetMapping("/{id}/logs")
public Result<LogResultVO> getLogs(@PathVariable Long id,
                                    @RequestParam(defaultValue="200") Integer lines) {
    GameInstance instance = instanceService.getInstanceById(id);

    // 部署中：返回 DeployService 内存日志
    if (instance.getRunStatus() == 5) {
        DeployService.DeployTaskStatus taskStatus = deployService.getTaskStatus(id);
        if (taskStatus != null) {
            return Result.success(buildLogResultFromTask(taskStatus));
        }
    }

    // 运行中/已停止：通过适配器获取容器/进程日志
    DeployAdapter adapter = adapterFactory.getAdapter(instance.getDeployType());
    String logs = adapter.getLogs(id, instance.getConfigInfo(), lines);
    return Result.success(new LogResultVO(logs));
}
```

**新增 `POST /instances/{id}/retry-deploy`：**
```java
@PostMapping("/{id}/retry-deploy")
public Result<Void> retryDeploy(@PathVariable Long id) {
    instanceService.retryDeploy(id);
    return Result.success();
}
```

`InstanceServiceImpl.retryDeploy()`：
- 检查 `runStatus == 2`（异常），否则拒绝
- 调用 `adapter.uninstall()` 清理旧容器
- 重新 `deployService.deployAsync(context)`

### 4.4 InstanceVO 新增字段

```java
private String status;           // 状态字符串
private String deployTaskId;     // 部署任务ID（= instanceId）
```

### 4.5 启动时恢复机制

**`ApplicationReadyEvent` 监听器：**
```java
@EventListener(ApplicationReadyEvent.class)
public void recoverInterruptedDeploys() {
    List<GameInstance> deploying = instanceMapper.selectList(
        new LambdaQueryWrapper<GameInstance>().eq(GameInstance::getRunStatus, 5));
    for (GameInstance instance : deploying) {
        instance.setRunStatus(2); // 标记为异常
        instanceMapper.updateById(instance);
        log.warn("实例 {} 部署任务因应用重启中断，标记为异常", instance.getId());
    }
}
```

---

## 5. 前端组件改动详细设计

### 5.1 deploy.vue 的 handleDeploy 改动

```javascript
async function handleDeploy() {
    // ... 现有校验逻辑 ...

    try {
        const result = await createInstance({
            instanceName: deployForm.name,
            gameId: selectedGame.value.id,
            hostId: selectedHost.value.id,
            deployType: selectedDeployMethod.value,
            installPath: deployForm.deployPath,
            portConfig: { game: deployForm.port },
            configInfo: { ...deployForm.config, resources, envVars, autoRestart },
        });

        // 不弹窗，跳转回实例列表
        ElMessage.success("部署任务已创建，可在实例列表查看部署进度");
        router.push("/instance/list");
    } catch (error) {
        // ... 错误处理 ...
    }
}
```

### 5.2 index.vue 实例列表改动

**操作列改动：**

```vue
<el-table-column label="操作" width="240" fixed="right">
  <template #default="{ row }">
    <div class="action-cell">
      <!-- 运行中：停止按钮 + 查看日志 -->
      <template v-if="row.status === 'running'">
        <el-button type="warning" link @click="handleStop(row)">停止</el-button>
        <el-button type="primary" link @click="handleViewLogs(row)">查看日志</el-button>
      </template>

      <!-- 部署中/启动中：查看日志（禁用其他操作） -->
      <template v-else-if="['deploying', 'starting', 'stopping'].includes(row.status)">
        <el-button type="primary" link @click="handleViewLogs(row)">
          <el-icon class="is-loading"><Loading /></el-icon>
          查看日志
        </el-button>
      </template>

      <!-- 已停止：启动 + 删除 -->
      <template v-else-if="row.status === 'stopped'">
        <el-button type="success" link @click="handleStart(row)">启动</el-button>
        <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
      </template>

      <!-- 异常：重试部署 + 查看日志 + 删除 -->
      <template v-else-if="row.status === 'error'">
        <el-button type="warning" link @click="handleRetryDeploy(row)">重试</el-button>
        <el-button type="primary" link @click="handleViewLogs(row)">查看日志</el-button>
        <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
      </template>

      <!-- 更多操作下拉（保留现有） -->
      <el-dropdown ...>...</el-dropdown>
    </div>
  </template>
</el-table-column>
```

**新增方法：**

```javascript
// 查看日志
function handleViewLogs(row) {
    logDialogVisible.value = true;
    currentLogInstanceId.value = row.id;
    currentLogStatus.value = row.status;
}

// 重试部署（异常状态）
async function handleRetryDeploy(row) {
    try {
        await ElMessageBox.confirm(
            `确定要重新部署实例「${row.instanceName}」吗？`,
            "确认重试", { type: "warning" }
        );
        await retryDeploy(row.id);
        ElMessage.success("已重新触发部署");
        fetchData();
    } catch (e) { /* ... */ }
}
```

**列表自动刷新：** 存在 `deploying/starting/stopping` 状态时，每 5 秒自动刷新列表。

```javascript
let autoRefreshTimer = null;

function startAutoRefresh() {
    stopAutoRefresh();
    autoRefreshTimer = setInterval(() => {
        const hasActive = tableData.value.some(row =>
            ['deploying', 'starting', 'stopping'].includes(row.status)
        );
        if (hasActive) {
            fetchData();
        } else {
            stopAutoRefresh();
        }
    }, 5000);
}

function stopAutoRefresh() {
    if (autoRefreshTimer) {
        clearInterval(autoRefreshTimer);
        autoRefreshTimer = null;
    }
}

onMounted(() => {
    fetchHostOptions();
    fetchData().then(() => startAutoRefresh());
});
onBeforeUnmount(() => stopAutoRefresh());
```

### 5.3 DeployProgress.vue 复用改动

**Props 新增 `instanceId` 和 `mode`：**
```javascript
const props = defineProps({
    visible: Boolean,
    instanceId: { type: [Number, String], default: "" },
    mode: { type: String, default: "deploy" },  // "deploy" | "runtime"
});
```

**轮询逻辑根据 mode 分支：**
```javascript
async function fetchProgress() {
    if (props.mode === "deploy") {
        // 部署中：轮询 deploy-progress
        const data = await getDeployProgress(props.instanceId);
        progress.value = data.progress;
        status.value = data.status;
        // ... 更新 logs、completed、success ...
        if (data.completed) {
            stopProgressPolling();
            emit("complete", data.success);
        }
    } else {
        // 运行中：轮询容器日志（追加模式）
        const data = await getInstanceLogs(props.instanceId, { lines: 200 });
        appendRuntimeLogs(data.logs);
    }
}
```

**运行时日志追加逻辑：**
- 后端返回纯文本日志（`docker logs --tail 200` 的输出）
- 前端按行分割，每行生成 `{id, level:'info', message, time:now}`
- 用最后 5 行的 hash 去重，避免重复追加
- 进度条隐藏（运行时模式无进度概念），只显示日志区

### 5.4 API 接口新增

```javascript
// instance.js 新增
export function getDeployProgress(instanceId) {
    return request({
        url: `/instances/${instanceId}/deploy-progress`,
        method: "get",
    });
}

export function retryDeploy(id) {
    return request({
        url: `/instances/${id}/retry-deploy`,
        method: "post",
    });
}
```

---

## 6. 错误处理、边界情况与测试

### 6.1 错误处理

**部署阶段错误：**
- SSH 连接失败 → `DeployService.deploy()` catch 块捕获，`runStatus=2`，日志写入 ERROR 级别日志
- Docker 镜像拉取失败 → `DockerAdapter.deploy()` 返回 false → `runStatus=2`，日志含具体失败原因
- 健康检查超时（3 次重试均失败）→ `runStatus=2`，日志记录每次重试结果
- 适配器 `deploy()` 抛未捕获异常 → catch 块兜底，`runStatus=2`

**运行时错误：**
- `GET /deploy-progress/{id}` 任务不存在 → 返回 `Result.fail("部署任务不存在")`，前端显示提示
- `GET /{id}/logs` 适配器执行失败 → 返回空日志 + 错误信息，前端日志区显示"获取日志失败"
- SSH 命令超时 → `SshUtil` 返回 `CommandResult(success=false)`，适配器返回空字符串

**前端错误：**
- 轮询 deploy-progress 失败 → 在日志区追加 ERROR 日志"获取部署进度失败"，继续轮询（最多 5 次连续失败后停止）
- 列表自动刷新失败 → 静默重试，不打断用户

### 6.2 边界情况

**并发与重复：**
- 同一实例重复点击"部署" → `createInstance` 前检查 `runStatus=5`，若已部署中则拒绝：`throw new BusinessException("实例正在部署中")`
- 部署中用户点击"删除" → 前端禁用删除按钮（`deploying` 状态不显示删除）；后端 `deleteInstance` 检查 `runStatus=5` 拒绝
- 部署中应用重启 → 启动恢复机制标记为 `error`（rs=2）

**资源清理：**
- 部署失败但容器已创建 → `autoRollback=false`（默认），保留容器供排查；日志记录容器 ID，用户可手动清理或重试部署时先清理
- 重试部署 → `POST /instances/{id}/retry-deploy` 先调用 `adapter.uninstall()` 清理旧容器，再重新 `deployAsync()`

**状态一致性：**
- `DeployService.taskStatusMap` 内存存储，应用重启丢失 → 已通过启动恢复机制处理（`runStatus=5` → `2`）
- 前端列表自动刷新检测到 `deploying` 实例消失（变为 `error`）→ 停止自动刷新，显示最新状态

**Docker 容器日志边界：**
- 容器不存在（已删除）→ `docker logs` 命令失败 → 返回错误信息"容器不存在"
- 容器刚启动无日志 → 返回空字符串 → 前端显示"暂无日志"
- 日志编码问题 → SSH 输出统一 UTF-8 解码

### 6.3 测试策略

**单元测试（后端）：**
- `DeployServiceTest`：
  - `testDeploySuccess_AllStagesPass` → 验证状态 5→6→1
  - `testDeployFail_DeployReturnsFalse` → 验证状态 5→2
  - `testDeployFail_HealthCheckRetry3Times` → 验证重试逻辑
  - `testDeployFail_StartThrowsException` → 验证异常兜底
  - `testLogCollectingCallback_AllCallbacksLogged` → 验证日志累积

- `InstanceServiceImplTest`：
  - `testCreateInstance_TriggersDeployAsync` → 验证异步部署触发
  - `testCreateInstance_AlreadyDeploying_ThrowsException` → 验证重复部署拒绝
  - `testConvertToVO_StatusMapping` → 验证状态字符串映射

**集成测试（后端）：**
- `InstanceControllerIntegrationTest`：
  - `testCreateInstance_ReturnsDeployTaskId` → 验证响应含 deployTaskId
  - `testGetDeployProgress_TaskExists` → 验证进度端点
  - `testGetDeployProgress_TaskNotExists` → 验证错误响应
  - `testGetLogs_DeployingStatus_ReturnsTaskLogs` → 验证部署中日志分支
  - `testGetLogs_RunningStatus_ReturnsContainerLogs` → 验证运行时日志分支

**前端测试（Vitest）：**
- `deploy.spec.js`：
  - 验证创建成功后跳转 `/instance/list`
  - 验证不打开 DeployProgress 弹窗

- `index.spec.js`：
  - 验证 `deploying` 状态显示"查看日志"按钮
  - 验证自动刷新启动/停止逻辑
  - 验证 `handleViewLogs` 打开日志弹窗

- `DeployProgress.spec.js`：
  - 验证 `mode="deploy"` 轮询 `getDeployProgress`
  - 验证 `mode="runtime"` 轮询 `getInstanceLogs`
  - 验证日志去重追加逻辑

### 6.4 验收标准

1. 点击部署后，实例列表出现新实例，状态显示"部署中"（黄色 Loading 图标）
2. 点击"查看日志"按钮，打开日志弹窗，实时显示部署阶段日志（环境校验→拉取镜像→启动容器→健康检查）
3. 部署成功 → 状态变为"运行中"（绿色），日志弹窗显示"部署成功"
4. 部署失败 → 状态变为"异常"（红色），日志弹窗显示失败原因，可点击"重试"
5. 运行中实例点击"查看日志" → 显示 Docker 容器实时日志（每 2s 刷新）
6. 应用重启后，"部署中"实例自动标记为"异常"
7. 重复部署同一实例 → 拒绝并提示"实例正在部署中"

---

## 7. 范围与非目标

**本次范围内：**
- 主应用 core 模块后端改动
- 主应用前端 `deploy.vue`、`index.vue`、`DeployProgress.vue` 改动
- `instance.js` API 接口新增

**本次范围外：**
- plugin-l4d2 模块（独立子系统，不影响）
- WebSocket 实时推送（用户已选 HTTP 轮询方案）
- 部署任务持久化到数据库（YAGNI，部署是短时任务）
- 多节点分布式部署任务调度

---

*设计完成日期: 2026-07-16*
