# 部署任务执行与实例状态机管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建实例后自动触发异步部署任务，实例列表显示"部署中"状态并提供实时日志查看能力，含完整状态机和健康检查。

**Architecture:** 复用现有 `DeployService.deploy()` 完整生命周期，在 `DeployTaskStatus` 新增日志累积字段，通过 `LogCollectingCallback` 包装收集日志。HTTP 轮询（2s）获取进度。`taskId` 即 `instanceId`（字符串形式）。

**Tech Stack:** Java 17 + Spring Boot 3.2.5 + MyBatis-Plus（后端），Vue 3 + Element Plus + Vite（前端）

**关键约定：**
- 状态码：`0=stopped, 1=running, 2=error, 3=stopping, 5=deploying, 6=starting`（与 `DeployAdapter.InstanceStatus` 枚举不同，本特性直接使用整型 runStatus，不复用枚举）
- `taskId = String.valueOf(instanceId)`
- 前端 `status` 字符串字段由后端 `convertToVO` 时根据 `runStatus` 整型映射生成

---

## 文件结构

### 后端修改
| 文件 | 责任 |
|---|---|
| `backend/core/.../service/DeployService.java` | 新增 `LogEntry`、`LogCollectingCallback`、`retryHealthCheck`、`updateRunStatus`；修改 `deploy()` 同步状态 + 日志收集；新增 `deployAsync(context, callback)` 重载 |
| `backend/core/.../service/InstanceService.java` | 接口新增 `retryDeploy(Long id)` |
| `backend/core/.../service/impl/InstanceServiceImpl.java` | 修改 `createInstance()` 触发异步部署；新增 `retryDeploy()`、`buildDeployConfig()`；`convertToVO()` 加状态字符串映射 |
| `backend/core/.../controller/InstanceController.java` | 新增 `GET /{id}/deploy-progress`、`POST /{id}/retry-deploy`；修改 `GET /{id}/logs` 处理部署中分支；新增 `DeployProgressVO` 等内部 VO |
| `backend/api/.../vo/InstanceVO.java` | 新增 `status` 和 `deployTaskId` 字段 |
| `backend/core/.../listener/DeployRecoveryListener.java`（新建） | `ApplicationReadyEvent` 监听，恢复中断的部署任务 |

### 前端修改
| 文件 | 责任 |
|---|---|
| `frontend/src/api/instance.js` | 修正 `getDeployProgress(instanceId)` URL；新增 `retryDeploy(id)` |
| `frontend/src/views/instance/deploy.vue` | `handleDeploy` 改为跳转列表（不再弹窗） |
| `frontend/src/views/instance/index.vue` | 状态显示 + 操作列（含"查看日志"按钮） + 自动刷新 |
| `frontend/src/components/DeployProgress.vue` | 新增 `mode`/`instanceId` props；双模式轮询（deploy/runtime） |

---

## Task 1: 在 DeployService 新增 LogEntry 和日志收集能力

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/service/DeployService.java`

- [ ] **Step 1: 在 DeployService 内部新增 LogEntry 静态内部类**

在 `DeployService.java` 的 `DeployTaskStatus` 类**之前**（约 line 50 附近，`taskStatusMap` 声明之后）插入：

```java
/**
 * 日志条目
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public static class LogEntry {
    private long id;          // 日志ID（自增，前端去重用）
    private String level;     // INFO/WARN/ERROR/SUCCESS
    private String message;
    private String stage;     // 关联阶段
    private LocalDateTime time;
}
```

需要在文件顶部 import 区添加（如果缺失）：
```java
import java.util.ArrayList;
import java.util.List;
```

- [ ] **Step 2: 在 DeployTaskStatus 类中新增 logs 和 status 字段**

修改 `DeployTaskStatus` 内部类（line 53-65），新增 3 个字段：

```java
@Data
@Builder
public static class DeployTaskStatus {
    private Long instanceId;
    private String stage;
    private int progress;
    private String message;
    private boolean completed;
    private boolean success;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String error;

    // 新增字段
    @Builder.Default
    private List<LogEntry> logs = new ArrayList<>();
    private long logIdCounter = 0;
    private String status;  // pending/preparing/.../completed/failed
}
```

- [ ] **Step 3: 新增 appendLog 辅助方法**

在 `getTaskStatus()` 方法（约 line 460）之后添加：

```java
/**
 * 向任务状态追加日志
 */
private void appendLog(Long instanceId, String level, String message, String stage) {
    DeployTaskStatus status = taskStatusMap.get(instanceId);
    if (status == null) {
        return;
    }
    synchronized (status) {
        long logId = status.getLogIdCounter() + 1;
        status.setLogIdCounter(logId);
        status.getLogs().add(new LogEntry(logId, level, message, stage, LocalDateTime.now()));
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `cd backend && mvn -pl core compile -q`
Expected: 编译成功，无错误

- [ ] **Step 5: Commit**

```bash
cd backend
git add core/src/main/java/com/gameplatform/service/DeployService.java
git commit -m "feat(deploy): 新增 LogEntry 和日志收集字段到 DeployTaskStatus"
```

---

## Task 2: 新增 LogCollectingCallback 内部类

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/service/DeployService.java`

- [ ] **Step 1: 新增 stage 到 status 字符串的映射方法**

在 `appendLog()` 方法之后添加：

```java
/**
 * 将后端阶段名映射为前端 status 字符串
 */
private String mapStageToStatus(String stage) {
    if (stage == null) return "pending";
    return switch (stage) {
        case "INIT", "ENV_CHECK", "PORT_CHECK", "RESOURCE_CHECK" -> "preparing";
        case "PRE_DEPLOY" -> "downloading";
        case "DEPLOY" -> "installing";
        case "HEALTH_CHECK" -> "checking";
        case "START", "UPDATE_STATUS" -> "starting";
        case "COMPLETE" -> "completed";
        default -> "preparing";
    };
}
```

- [ ] **Step 2: 新增 LogCollectingCallback 内部类**

在 `mapStageToStatus()` 之后添加：

```java
/**
 * 日志收集回调 - 包装另一个 callback，同时将所有事件记录到 DeployTaskStatus.logs
 */
private class LogCollectingCallback implements DeployProgressCallback {
    private final Long instanceId;
    private final DeployProgressCallback delegate;

    public LogCollectingCallback(Long instanceId, DeployProgressCallback delegate) {
        this.instanceId = instanceId;
        this.delegate = delegate;
    }

    @Override
    public void onProgress(int percent, String stage, String message) {
        DeployTaskStatus status = taskStatusMap.get(instanceId);
        if (status != null) {
            synchronized (status) {
                status.setProgress(percent);
                status.setStage(stage);
                status.setMessage(message);
                status.setStatus(mapStageToStatus(stage));
            }
        }
        appendLog(instanceId, "INFO", message, stage);
        delegate.onProgress(percent, stage, message);
    }

    @Override
    public void onComplete(boolean success, String message) {
        DeployTaskStatus status = taskStatusMap.get(instanceId);
        if (status != null) {
            synchronized (status) {
                status.setCompleted(true);
                status.setSuccess(success);
                status.setEndTime(LocalDateTime.now());
                status.setStatus(success ? "completed" : "failed");
            }
        }
        appendLog(instanceId, success ? "SUCCESS" : "ERROR", message, "COMPLETE");
        delegate.onComplete(success, message);
    }

    @Override
    public void onError(String error, String stage, boolean recoverable) {
        DeployTaskStatus status = taskStatusMap.get(instanceId);
        if (status != null) {
            synchronized (status) {
                status.setError(error);
                status.setStatus("failed");
            }
        }
        appendLog(instanceId, "ERROR", "[" + stage + "] " + error, stage);
        delegate.onError(error, stage, recoverable);
    }

    @Override
    public void onLog(String level, String message) {
        DeployTaskStatus status = taskStatusMap.get(instanceId);
        String stage = status != null ? status.getStage() : "UNKNOWN";
        appendLog(instanceId, level, message, stage);
        delegate.onLog(level, message);
    }

    @Override
    public void onStageStart(String stage, String description) {
        DeployTaskStatus status = taskStatusMap.get(instanceId);
        if (status != null) {
            synchronized (status) {
                status.setStage(stage);
                status.setStatus(mapStageToStatus(stage));
            }
        }
        appendLog(instanceId, "INFO", "[" + stage + "] " + description, stage);
        delegate.onStageStart(stage, description);
    }

    @Override
    public void onStageComplete(String stage, boolean success, String message) {
        appendLog(instanceId, success ? "SUCCESS" : "WARN",
                "[" + stage + "] " + message, stage);
        delegate.onStageComplete(stage, success, message);
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `cd backend && mvn -pl core compile -q`
Expected: 编译成功

- [ ] **Step 4: Commit**

```bash
cd backend
git add core/src/main/java/com/gameplatform/service/DeployService.java
git commit -m "feat(deploy): 新增 LogCollectingCallback 收集部署日志到 taskStatusMap"
```

---

## Task 3: 修改 deploy() 方法，集成日志收集、状态更新和健康检查重试

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/service/DeployService.java`

- [ ] **Step 1: 新增 updateRunStatus 方法**

在 `appendLog()` 之后添加：

```java
/**
 * 更新实例的运行状态（持久化到数据库）
 */
private void updateRunStatus(Long instanceId, int runStatus) {
    try {
        GameInstance instance = instanceMapper.selectById(instanceId);
        if (instance != null) {
            instance.setRunStatus(runStatus);
            instanceMapper.updateById(instance);
        }
    } catch (Exception e) {
        log.error("更新实例状态失败: instanceId={}, runStatus={}", instanceId, runStatus, e);
    }
}
```

需要 import（如果缺失）：
```java
import com.gameplatform.entity.GameInstance;
```
（注：`GameInstance` 已在文件顶部 import，line 6）

- [ ] **Step 2: 新增 retryHealthCheck 方法**

在 `updateRunStatus()` 之后添加：

```java
/**
 * 带重试的健康检查
 * @param maxRetries 最大重试次数
 * @param intervalMs 重试间隔（毫秒）
 */
private boolean retryHealthCheck(DeployAdapter adapter, Long instanceId,
                                  Map<String, Object> config,
                                  int maxRetries, long intervalMs) {
    for (int i = 0; i < maxRetries; i++) {
        try {
            if (adapter.healthCheck(instanceId, config)) {
                return true;
            }
            log.warn("健康检查失败（第{}次）: instanceId={}", i + 1, instanceId);
        } catch (Exception e) {
            log.warn("健康检查异常（第{}次）: instanceId={}", i + 1, instanceId, e);
        }
        if (i < maxRetries - 1) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
    return false;
}
```

- [ ] **Step 3: 修改 deploy() 方法 - 包装 callback 并更新状态**

修改 `deploy()` 方法（line 115-223）。在 `taskStatusMap.put(instanceId, status);`（line 130）之后、`DeployAdapter adapter = ...`（line 132）之前插入：

```java
        // 包装 callback，收集日志到 taskStatus
        DeployProgressCallback collectingCallback = new LogCollectingCallback(instanceId, callback);
```

然后把方法内所有 `callback.` 调用改为 `collectingCallback.`。具体替换：
- line 141: `notifyProgress(callback, 10, ...)` → `notifyProgress(collectingCallback, 10, ...)`
- line 148: `notifyProgress(callback, 15, ...)` → `notifyProgress(collectingCallback, 15, ...)`
- line 155: `notifyProgress(callback, 20, ...)` → `notifyProgress(collectingCallback, 20, ...)`
- line 159: `notifyStageStart(callback, ...)` → `notifyStageStart(collectingCallback, ...)`
- line 160: `createStageCallback(callback, ...)` → `createStageCallback(collectingCallback, ...)`
- line 163: `notifyStageComplete(callback, ...)` → `notifyStageComplete(collectingCallback, ...)`
- line 167, 168, 171: 同理
- line 179: `notifyProgress(callback, 90, ...)` → `notifyProgress(collectingCallback, 90, ...)`
- line 190: `notifyProgress(callback, 98, ...)` → `notifyProgress(collectingCallback, 98, ...)`
- line 196: `notifyComplete(callback, ...)` → `notifyComplete(collectingCallback, ...)`
- line 213: `notifyError(callback, ...)` → `notifyError(collectingCallback, ...)`
- line 218: `rollback(context, callback)` → `rollback(context, collectingCallback)`

- [ ] **Step 4: 修改 deploy() 方法 - 替换 updateInstanceStatus 为 updateRunStatus**

将 line 183 的 `updateInstanceStatus(instanceId, DeployAdapter.InstanceStatus.STOPPED);` 替换为：
```java
            updateRunStatus(instanceId, 0); // stopped
```

将 line 189 的 `updateInstanceStatus(instanceId, DeployAdapter.InstanceStatus.RUNNING);` 替换为：
```java
                    updateRunStatus(instanceId, 1); // running
```

- [ ] **Step 5: 修改 deploy() 方法 - 在自动启动阶段加入状态更新和健康检查重试**

替换 line 185-192 的自动启动块：

```java
            // 8. 自动启动（如果配置）
            if (context.isAutoStart()) {
                updateTaskStatus(instanceId, "START", 95, "启动实例");
                updateRunStatus(instanceId, 6); // starting
                if (adapter.start(instanceId, context.getConfig())) {
                    // 健康检查重试（最多 3 次，间隔 5 秒）
                    Thread.sleep(5000);
                    boolean healthy = retryHealthCheck(adapter, instanceId, context.getConfig(), 3, 5000);
                    if (healthy) {
                        updateRunStatus(instanceId, 1); // running
                        notifyProgress(collectingCallback, 98, "START", "实例已启动并健康");
                    } else {
                        updateRunStatus(instanceId, 2); // error
                        appendLog(instanceId, "ERROR", "健康检查 3 次重试均失败", "HEALTH_CHECK");
                        throw new DeployException("健康检查失败：3 次重试均未通过");
                    }
                } else {
                    updateRunStatus(instanceId, 2); // error
                    throw new DeployException("实例启动失败");
                }
            } else {
                // 未配置自动启动，保持 stopped
                updateRunStatus(instanceId, 0); // stopped
            }
```

- [ ] **Step 6: 修改 catch 块 - 失败时更新 runStatus 为 error**

在 catch 块内（line 200 附近），在 `notifyError` 调用**之前**插入：

```java
            // 失败时标记为异常状态
            updateRunStatus(instanceId, 2); // error
```

- [ ] **Step 7: 新增 deployAsync(context, callback) 重载方法**

修改现有 `deployAsync()`（line 231-234），新增一个接受 callback 的重载：

```java
    /**
     * 异步部署（使用传入的回调）
     */
    @Async
    public CompletableFuture<Boolean> deployAsync(DeployContext context, DeployProgressCallback callback) {
        return CompletableFuture.supplyAsync(() -> deploy(context, callback));
    }

    /**
     * 异步部署（无回调）
     */
    @Async
    public CompletableFuture<Boolean> deployAsync(DeployContext context) {
        return CompletableFuture.supplyAsync(() -> deploy(context, DeployProgressCallback.NO_OP));
    }
```

- [ ] **Step 8: 编译验证**

Run: `cd backend && mvn -pl core compile -q`
Expected: 编译成功

- [ ] **Step 9: Commit**

```bash
cd backend
git add core/src/main/java/com/gameplatform/service/DeployService.java
git commit -m "feat(deploy): deploy() 集成日志收集、状态更新和健康检查重试"
```

---

## Task 4: 在 InstanceVO 新增 status 和 deployTaskId 字段

**Files:**
- Modify: `backend/api/src/main/java/com/gameplatform/vo/InstanceVO.java`

- [ ] **Step 1: 新增字段**

在 `InstanceVO.java` 的 `updateTime` 字段（约 line 134）之后、`getRunStatusDesc()` 方法（line 139）之前添加：

```java

    @Schema(description = "状态字符串（前端使用）")
    private String status;

    @Schema(description = "部署任务ID（等于实例ID的字符串形式）")
    private String deployTaskId;
```

- [ ] **Step 2: 扩展 getRunStatusDesc() 支持新状态码**

修改 `getRunStatusDesc()` 方法（line 139-147），扩展 case：

```java
    public String getRunStatusDesc() {
        if (runStatus == null) { return "未知"; }
        return switch (runStatus) {
            case 0 -> "已停止";
            case 1 -> "运行中";
            case 2 -> "异常";
            case 3 -> "停止中";
            case 5 -> "部署中";
            case 6 -> "启动中";
            default -> "未知";
        };
    }
```

- [ ] **Step 3: 编译验证**

Run: `cd backend && mvn -pl api compile -q`
Expected: 编译成功

- [ ] **Step 4: Commit**

```bash
cd backend
git add api/src/main/java/com/gameplatform/vo/InstanceVO.java
git commit -m "feat(instance): InstanceVO 新增 status 和 deployTaskId 字段"
```

---

## Task 5: 修改 InstanceServiceImpl - 触发异步部署 + 状态映射

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/service/impl/InstanceServiceImpl.java`
- Modify: `backend/core/src/main/java/com/gameplatform/service/InstanceService.java`（接口）

- [ ] **Step 1: 在 InstanceServiceImpl 注入 DeployService**

在 `InstanceServiceImpl` 的字段区（找到现有的 `@Autowired` 字段附近）添加：

```java
    @Autowired
    private DeployService deployService;
```

需要 import：
```java
import com.gameplatform.service.DeployService;
import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.adapter.DeployAdapterFactory;
import org.springframework.scheduling.annotation.Async;
```

如果 `DeployAdapterFactory` 已注入则跳过；若未注入，添加：
```java
    @Autowired
    private DeployAdapterFactory adapterFactory;
```

- [ ] **Step 2: 修改 createInstance() - 设置部署中状态并触发异步部署**

修改 `createInstance()` 方法（line 52-93）。将 line 79-92 替换为：

```java
        // 初始状态为部署中
        instance.setRunStatus(5);
        instance.setOnlinePlayers(0);

        instanceMapper.insert(instance);

        logService.log(getCurrentUser(), "CREATE", "INSTANCE",
                "创建实例: " + instance.getInstanceName(), "success", null, null);

        // 通知 gameCode 匹配的插件扩展点
        pluginLifecycleHook.executeInstanceCreateHooks(instance.getId(), game.getGameCode(),
                instance.getConfigInfo() != null ? instance.getConfigInfo() : Map.of());

        // 构建部署上下文并异步触发部署
        DeployService.DeployContext context = DeployService.DeployContext.builder()
                .instanceId(instance.getId())
                .hostId(dto.getHostId())
                .deployType(DeployAdapter.DeployType.fromCode(dto.getDeployType()))
                .config(buildDeployConfig(instance))
                .autoRollback(false)
                .autoStart(true)
                .build();

        deployService.deployAsync(context);

        InstanceVO vo = convertToVO(instance);
        vo.setDeployTaskId(String.valueOf(instance.getId()));
        return vo;
```

- [ ] **Step 3: 新增 buildDeployConfig 辅助方法**

在 `convertToVO()` 方法（line 480）之前添加：

```java
    /**
     * 构建部署配置 Map（合并 configInfo、portConfig、installPath）
     */
    private Map<String, Object> buildDeployConfig(GameInstance instance) {
        Map<String, Object> config = new HashMap<>();
        if (instance.getConfigInfo() != null) {
            config.putAll(instance.getConfigInfo());
        }
        if (instance.getPortConfig() != null) {
            config.putAll(instance.getPortConfig());
        }
        config.put("installPath", instance.getInstallPath());
        config.put("instanceId", instance.getId());
        config.put("gameCode", instance.getGameCode());
        return config;
    }
```

需要 import（如果缺失）：
```java
import java.util.HashMap;
```

- [ ] **Step 4: 修改 convertToVO() 添加 status 字符串映射**

修改 `convertToVO()` 方法（line 480-497），在 `return vo;` 之前添加 status 映射：

```java
    private InstanceVO convertToVO(GameInstance instance) {
        InstanceVO vo = new InstanceVO();
        BeanUtil.copyProperties(instance, vo);

        // 获取主机名称
        Host host = hostMapper.selectById(instance.getHostId());
        if (host != null) {
            vo.setHostName(host.getHostName());
        }

        // 获取游戏名称
        GameMetadata game = gameMetadataMapper.selectById(instance.getGameId());
        if (game != null) {
            vo.setGameName(game.getGameName());
        }

        // 映射 runStatus 到 status 字符串
        vo.setStatus(mapRunStatusToString(instance.getRunStatus()));

        return vo;
    }

    /**
     * 将 runStatus 整型映射为前端使用的 status 字符串
     */
    private String mapRunStatusToString(Integer runStatus) {
        if (runStatus == null) return "unknown";
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

- [ ] **Step 5: 新增 retryDeploy 方法到 InstanceServiceImpl**

在 `buildDeployConfig()` 之后添加：

```java
    @Override
    public void retryDeploy(Long id) {
        GameInstance instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }
        if (instance.getRunStatus() != 2) {
            throw new BusinessException("只有异常状态的实例可以重试部署");
        }

        // 标记为部署中
        instance.setRunStatus(5);
        instanceMapper.updateById(instance);

        // 先清理旧容器（忽略失败）
        try {
            String deployType = instance.getDeployType();
            if (deployType != null && !deployType.isEmpty()) {
                DeployAdapter adapter = adapterFactory.getAdapter(deployType);
                adapter.uninstall(id, buildDeployConfig(instance), DeployProgressCallback.NO_OP);
            }
        } catch (Exception e) {
            log.warn("重试部署时清理旧容器失败: instanceId={}", id, e);
        }

        // 重新触发部署
        DeployService.DeployContext context = DeployService.DeployContext.builder()
                .instanceId(instance.getId())
                .hostId(instance.getHostId())
                .deployType(DeployAdapter.DeployType.fromCode(instance.getDeployType()))
                .config(buildDeployConfig(instance))
                .autoRollback(false)
                .autoStart(true)
                .build();

        deployService.deployAsync(context);
    }
```

需要 import（如果缺失）：
```java
import com.gameplatform.adapter.DeployProgressCallback;
```

- [ ] **Step 6: 在 InstanceService 接口新增 retryDeploy 方法声明**

打开 `backend/core/src/main/java/com/gameplatform/service/InstanceService.java`，在接口中添加：

```java
    /**
     * 重试部署（仅限异常状态实例）
     * @param id 实例ID
     */
    void retryDeploy(Long id);
```

- [ ] **Step 7: 编译验证**

Run: `cd backend && mvn -pl core compile -q`
Expected: 编译成功

- [ ] **Step 8: Commit**

```bash
cd backend
git add core/src/main/java/com/gameplatform/service/impl/InstanceServiceImpl.java core/src/main/java/com/gameplatform/service/InstanceService.java
git commit -m "feat(instance): createInstance 触发异步部署，新增 retryDeploy 和状态映射"
```

---

## Task 6: 在 InstanceController 新增 deploy-progress 和 retry-deploy 端点

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/controller/InstanceController.java`

- [ ] **Step 1: 在 InstanceController 注入 DeployService**

在 `InstanceController` 字段区添加（如果 `DeployService` 尚未注入）：

```java
    private final DeployService deployService;
```

由于 controller 使用 `@RequiredArgsConstructor`，添加 final 字段即可自动注入。需要 import：
```java
import com.gameplatform.service.DeployService;
```

- [ ] **Step 2: 新增 DeployProgressVO 和 LogEntryVO 内部类**

在 `InstanceController` 类的内部 VO 区（`LogResultVO` 之后）添加：

```java
    /**
     * 部署进度响应 VO
     */
    @Data
    public static class DeployProgressVO {
        private Integer progress;
        private String status;
        private String statusText;
        private List<LogEntryVO> logs;
        private Boolean completed;
        private Boolean success;
        private String error;
    }

    /**
     * 日志条目 VO
     */
    @Data
    public static class LogEntryVO {
        private Long id;
        private String level;
        private String message;
        private String stage;
        private String time;
    }
```

需要 import（如果缺失）：
```java
import java.time.format.DateTimeFormatter;
```

- [ ] **Step 3: 新增 GET /{id}/deploy-progress 端点**

在 `getLogs()` 方法之后添加：

```java
    /**
     * 获取部署进度
     */
    @Operation(summary = "获取部署进度", description = "获取实例部署任务的实时进度和日志")
    @GetMapping("/{id}/deploy-progress")
    public Result<DeployProgressVO> getDeployProgress(@Parameter(description = "实例ID") @PathVariable Long id) {
        DeployService.DeployTaskStatus status = deployService.getTaskStatus(id);
        if (status == null) {
            return Result.fail("部署任务不存在或已完成清理");
        }

        DeployProgressVO vo = new DeployProgressVO();
        vo.setProgress(status.getProgress());
        vo.setStatus(status.getStatus());
        vo.setStatusText(mapStatusText(status.getStatus()));
        vo.setCompleted(status.isCompleted());
        vo.setSuccess(status.isSuccess());
        vo.setError(status.getError());

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        List<LogEntryVO> logVOs = status.getLogs().stream().map(le -> {
            LogEntryVO leVo = new LogEntryVO();
            leVo.setId(le.getId());
            leVo.setLevel(le.getLevel());
            leVo.setMessage(le.getMessage());
            leVo.setStage(le.getStage());
            leVo.setTime(le.getTime() != null ? le.getTime().format(fmt) : "");
            return leVo;
        }).toList();
        vo.setLogs(logVOs);

        return Result.success(vo);
    }

    /**
     * 将 status 字符串映射为中文描述
     */
    private String mapStatusText(String status) {
        if (status == null) return "处理中";
        return switch (status) {
            case "pending" -> "等待中";
            case "preparing" -> "准备中";
            case "downloading" -> "下载中";
            case "installing" -> "安装中";
            case "configuring" -> "配置中";
            case "starting" -> "启动中";
            case "checking" -> "健康检查中";
            case "completed" -> "已完成";
            case "failed" -> "失败";
            default -> "处理中";
        };
    }
```

- [ ] **Step 4: 新增 POST /{id}/retry-deploy 端点**

在 `getDeployProgress()` 之后添加：

```java
    /**
     * 重试部署
     */
    @Operation(summary = "重试部署", description = "对异常状态的实例重新触发部署")
    @PostMapping("/{id}/retry-deploy")
    @OperationLog(type = "DEPLOY", target = "INSTANCE", description = "重试部署实例")
    public Result<Void> retryDeploy(@Parameter(description = "实例ID") @PathVariable Long id) {
        instanceService.retryDeploy(id);
        return Result.success();
    }
```

- [ ] **Step 5: 编译验证**

Run: `cd backend && mvn -pl core compile -q`
Expected: 编译成功

- [ ] **Step 6: Commit**

```bash
cd backend
git add core/src/main/java/com/gameplatform/controller/InstanceController.java
git commit -m "feat(instance): 新增 deploy-progress 和 retry-deploy 端点"
```

---

## Task 7: 修改 getLogs() 端点 - 部署中状态返回任务日志

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/controller/InstanceController.java`

- [ ] **Step 1: 修改 getLogs() 方法处理部署中分支**

修改 `getLogs()` 方法（line 328-350）。在现有的 `String logContent = instanceService.getInstanceLogs(id, lines);` 之前插入部署中分支判断：

```java
    @Operation(summary = "获取实例日志", description = "获取游戏实例运行日志")
    @GetMapping("/{id}/logs")
    public Result<LogResultVO> getLogs(@Parameter(description = "实例ID") @PathVariable Long id,
                                        @Parameter(description = "日志行数") @RequestParam(defaultValue = "100") Integer lines,
                                        @Parameter(description = "日志类型") @RequestParam(defaultValue = "stdout") String type) {
        InstanceVO instance = instanceService.getInstanceById(id);
        if (instance == null) {
            return Result.fail("实例不存在");
        }

        LogResultVO logResult = new LogResultVO();
        logResult.setInstanceId(id);
        logResult.setLines(lines);

        // 部署中：返回 DeployService 内存中的部署日志
        if (instance.getRunStatus() != null && instance.getRunStatus() == 5) {
            DeployService.DeployTaskStatus taskStatus = deployService.getTaskStatus(id);
            if (taskStatus != null) {
                StringBuilder sb = new StringBuilder();
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
                for (DeployService.LogEntry le : taskStatus.getLogs()) {
                    sb.append("[").append(le.getTime() != null ? le.getTime().format(fmt) : "").append("] ")
                      .append("[").append(le.getLevel()).append("] ")
                      .append(le.getMessage()).append("\n");
                }
                logResult.setContent(sb.toString());
                return Result.success(logResult);
            }
        }

        // 运行中/已停止/异常：通过适配器获取容器/进程日志
        String logContent = instanceService.getInstanceLogs(id, lines);
        logResult.setContent(logContent);

        return Result.success(logResult);
    }
```

需要 import：
```java
import java.time.format.DateTimeFormatter;
```
（若 Task 6 已添加则跳过）

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -pl core compile -q`
Expected: 编译成功

- [ ] **Step 3: Commit**

```bash
cd backend
git add core/src/main/java/com/gameplatform/controller/InstanceController.java
git commit -m "feat(instance): getLogs 端点部署中状态返回任务日志"
```

---

## Task 8: 新增 DeployRecoveryListener - 应用启动恢复机制

**Files:**
- Create: `backend/core/src/main/java/com/gameplatform/listener/DeployRecoveryListener.java`

- [ ] **Step 1: 创建监听器类**

创建新文件 `backend/core/src/main/java/com/gameplatform/listener/DeployRecoveryListener.java`：

```java
package com.gameplatform.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.mapper.GameInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 部署任务恢复监听器
 * 应用启动时，将中断的部署中实例标记为异常
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeployRecoveryListener {

    private final GameInstanceMapper instanceMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedDeploys() {
        List<GameInstance> deploying = instanceMapper.selectList(
                new LambdaQueryWrapper<GameInstance>()
                        .eq(GameInstance::getRunStatus, 5));

        if (deploying.isEmpty()) {
            return;
        }

        log.warn("发现 {} 个部署中被中断的实例，将标记为异常", deploying.size());
        for (GameInstance instance : deploying) {
            instance.setRunStatus(2); // error
            instanceMapper.updateById(instance);
            log.warn("实例 {} [{}] 部署任务因应用重启中断，已标记为异常",
                    instance.getId(), instance.getInstanceName());
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -pl core compile -q`
Expected: 编译成功

- [ ] **Step 3: Commit**

```bash
cd backend
git add core/src/main/java/com/gameplatform/listener/DeployRecoveryListener.java
git commit -m "feat(deploy): 新增启动恢复监听器，标记中断的部署任务为异常"
```

---

## Task 9: 后端集成验证

**Files:** 无修改，仅验证

- [ ] **Step 1: 完整编译**

Run: `cd backend && mvn clean compile -q`
Expected: 编译成功，无错误

- [ ] **Step 2: 确认 @EnableAsync 已配置**

搜索后端是否已启用 `@EnableAsync`（DeployService 的 `deployAsync` 需要）。

Run Grep: pattern=`@EnableAsync`, path=`d:\program\ai\game_platform_manger\backend`

如果未找到，需要在主应用类或配置类添加 `@EnableAsync`。若找到则跳过。

- [ ] **Step 3: 启动后端**

Run（非阻塞）: `cd backend && mvn -pl core spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"`
Expected: 应用启动成功，监听 8080

- [ ] **Step 4: 测试创建实例端点**

Run:
```bash
curl -X POST http://localhost:8080/api/instances -H "Content-Type: application/json" -H "Authorization: Bearer <你的JWT>" -d "{\"instanceName\":\"test-deploy\",\"gameId\":1,\"hostId\":1,\"deployType\":\"docker\",\"installPath\":\"/opt/games/test\",\"portConfig\":{\"game\":27015},\"configInfo\":{\"autoRestart\":1}}"
```
Expected: 返回 200，响应包含 `deployTaskId` 和 `status: "deploying"`

- [ ] **Step 5: 测试 deploy-progress 端点**

Run:
```bash
curl http://localhost:8080/api/instances/<instanceId>/deploy-progress -H "Authorization: Bearer <你的JWT>"
```
Expected: 返回 200，响应包含 `progress`、`status`、`logs`、`completed`

- [ ] **Step 6: 停止后端**

停止 Step 3 的进程。

---

## Task 10: 前端 - 修改 instance.js API

**Files:**
- Modify: `frontend/src/api/instance.js`

- [ ] **Step 1: 修正 getDeployProgress 函数**

修改 `getDeployProgress()`（line 402-412），URL 改为 RESTful 风格，参数名改为 `instanceId`：

```javascript
/**
 * 获取部署进度
 * @param {number|string} instanceId - 实例ID（即部署任务ID）
 * @returns {Promise<{progress: number, status: string, statusText: string, logs: Array, completed: boolean, success: boolean, error: string}>}
 */
export function getDeployProgress(instanceId) {
  return request({
    url: `/instances/${instanceId}/deploy-progress`,
    method: "get",
  });
}
```

- [ ] **Step 2: 新增 retryDeploy 函数**

在 `getDeployProgress()` 之后添加：

```javascript
/**
 * 重试部署
 * @param {number} id - 实例ID
 * @returns {Promise<null>}
 */
export function retryDeploy(id) {
  return request({
    url: `/instances/${id}/retry-deploy`,
    method: "post",
  });
}
```

- [ ] **Step 3: Commit**

```bash
cd frontend
git add src/api/instance.js
git commit -m "feat(api): 修正 getDeployProgress URL，新增 retryDeploy"
```

---

## Task 11: 前端 - 修改 deploy.vue 的 handleDeploy

**Files:**
- Modify: `frontend/src/views/instance/deploy.vue`

- [ ] **Step 1: 修改 handleDeploy - 创建成功后跳转列表**

修改 `handleDeploy()` 函数（line 370-407）。将 `if (result.deployTaskId) {...} else {...}` 块替换为统一跳转列表：

```javascript
async function handleDeploy() {
  if (!canDeploy.value) {
    ElMessage.warning("环境校验未通过，无法部署");
    return;
  }

  try {
    await createInstance({
      instanceName: deployForm.name,
      gameId: selectedGame.value.id,
      hostId: selectedHost.value.id,
      deployType: selectedDeployMethod.value,
      installPath: deployForm.deployPath,
      portConfig: {
        game: deployForm.port,
      },
      configInfo: {
        ...deployForm.config,
        resources: deployForm.resources,
        envVars: deployForm.envVars.filter((v) => v.key && v.value),
        autoRestart: deployForm.autoStart ? 1 : 0,
        gameVersion: selectedGame.value.version,
        gameCode: selectedGame.value.gameCode,
      },
    });

    ElMessage.success("部署任务已创建，可在实例列表查看部署进度");
    router.push("/instance/list");
  } catch (error) {
    console.error("Failed to deploy instance:", error);
    ElMessage.error("部署失败: " + (error.message || "未知错误"));
  }
}
```

- [ ] **Step 2: 保留 handleDeployComplete 函数（DeployProgress 组件仍可能在其他地方使用）**

`handleDeployComplete` 函数（line 410-417）保持不变。`showDeployProgress` 和 `deployTaskId` ref 保留，但 `handleDeploy` 不再设置它们。

- [ ] **Step 3: Commit**

```bash
cd frontend
git add src/views/instance/deploy.vue
git commit -m "feat(deploy): handleDeploy 创建成功后跳转实例列表"
```

---

## Task 12: 前端 - 修改 DeployProgress.vue 支持双模式

**Files:**
- Modify: `frontend/src/components/DeployProgress.vue`

- [ ] **Step 1: 新增 instanceId 和 mode props**

修改 props 定义（line 13-16）：

```javascript
const props = defineProps({
  visible: { type: Boolean, default: false },
  taskId: { type: String, default: "" },          // 保留向后兼容
  instanceId: { type: [Number, String], default: "" },  // 新增，优先使用
  mode: { type: String, default: "deploy" },      // "deploy" | "runtime"
});

const emit = defineEmits(["update:visible", "complete"]);
```

- [ ] **Step 2: 新增 runtime 日志追加辅助函数**

在 `fetchProgress()` 之前添加：

```javascript
// 运行时模式：最后 5 行 hash 用于去重
let runtimeLastHash = "";

function appendRuntimeLogs(content) {
  if (!content) return;
  const lines = content.split("\n").filter((l) => l.trim());
  if (lines.length === 0) return;

  // 按最后 5 行 hash 去重
  const last5 = lines.slice(-5).join("|");
  if (runtimeLastHash && last5 === runtimeLastHash) {
    return; // 内容未变化
  }

  // 找到与已存在日志的重复点
  const existingMessages = new Set(logs.value.map((l) => l.message));
  const newLogs = [];
  for (const line of lines) {
    if (!existingMessages.has(line)) {
      newLogs.push({
        id: Date.now() + Math.random(),
        level: "info",
        message: line,
        time: new Date().toLocaleTimeString(),
      });
    }
  }
  if (newLogs.length > 0) {
    logs.value.push(...newLogs);
    // 限制最多 1000 条
    if (logs.value.length > 1000) {
      logs.value = logs.value.slice(-1000);
    }
    scrollToBottom();
  }
  runtimeLastHash = last5;
}
```

- [ ] **Step 3: 修改 fetchProgress - 按 mode 分支**

修改 `fetchProgress()`（line 121-159）：

```javascript
async function fetchProgress() {
  const targetId = props.instanceId || props.taskId;
  if (!targetId) return;

  try {
    if (props.mode === "runtime") {
      // 运行时模式：轮询容器日志
      const data = await getInstanceLogs(targetId, { lines: 200 });
      // 后端返回 { instanceId, lines, content }
      appendRuntimeLogs(data.content || data.logs || "");
      return;
    }

    // 部署模式：轮询 deploy-progress
    const data = await getDeployProgress(targetId);

    progress.value = data.progress || 0;
    status.value = data.status || "pending";
    statusText.value = data.statusText || statusMap[status.value]?.text || "处理中...";

    // 添加新日志（按 id 去重）
    if (data.logs && data.logs.length > 0) {
      const existingIds = new Set(logs.value.map((l) => l.id));
      const newLogs = data.logs.filter((l) => !existingIds.has(l.id));
      if (newLogs.length > 0) {
        logs.value.push(...newLogs);
        scrollToBottom();
      }
    }

    // 更新错误信息
    if (data.error) {
      error.value = data.error;
    }

    // 检查是否完成
    if (data.completed || isCompleted.value) {
      stopProgressPolling();
      emit("complete", isSuccess.value);
    }
  } catch (err) {
    console.error("Failed to fetch progress:", err);
    addLog({
      level: "error",
      message: "获取进度失败: " + (err.message || "未知错误"),
      time: new Date().toLocaleTimeString(),
    });
  }
}
```

需要确保 import：
```javascript
import { getDeployProgress, getInstanceLogs } from "@/api/instance";
```

- [ ] **Step 4: 修改 watcher 使用 instanceId**

修改 `watch(() => props.visible, ...)` 和 `watch(() => props.taskId, ...)`（line 232-246），改为监听 `instanceId`：

```javascript
watch(() => props.visible, (val) => {
  const targetId = props.instanceId || props.taskId;
  if (val && targetId) {
    // 重置状态
    progress.value = 0;
    status.value = "pending";
    logs.value = [];
    error.value = "";
    elapsedTime.value = 0;
    startTime.value = Date.now();
    runtimeLastHash = "";
    if (props.mode === "deploy") {
      addLog({ level: "info", message: "开始部署...", time: new Date().toLocaleTimeString() });
    } else {
      addLog({ level: "info", message: "开始获取运行日志...", time: new Date().toLocaleTimeString() });
    }
    startProgressPolling();
  } else {
    stopProgressPolling();
  }
});

watch(() => [props.instanceId, props.taskId], () => {
  const targetId = props.instanceId || props.taskId;
  if (targetId && props.visible) {
    startProgressPolling();
  }
});
```

- [ ] **Step 5: 修改模板 - 运行时模式隐藏进度条**

在模板中找到进度条区域，用 `v-if="mode === 'deploy'"` 包裹：

```html
<!-- 进度条区域 -->
<div v-if="mode === 'deploy'" class="progress-section">
  <!-- 原有进度条内容 -->
</div>
```

（具体位置根据现有模板结构调整，通常进度条只在 deploy 模式显示，runtime 模式只显示日志区）

- [ ] **Step 6: Commit**

```bash
cd frontend
git add src/components/DeployProgress.vue
git commit -m "feat(deploy-progress): 新增 instanceId 和 mode props，支持部署/运行时双模式"
```

---

## Task 13: 前端 - 修改 index.vue 实例列表

**Files:**
- Modify: `frontend/src/views/instance/index.vue`

- [ ] **Step 1: 新增 import**

修改 import 区（line 2-12），新增 `onBeforeUnmount` 和 `Loading` 图标：

```javascript
import { ref, reactive, onMounted, onBeforeUnmount } from "vue";
```

并在组件图标 import 中确保有 `Loading`（如果使用 `<component :is>` 动态渲染，则无需额外 import）。

- [ ] **Step 2: 新增日志弹窗状态和方法**

在 `<script setup>` 中（现有 ref 声明附近）添加：

```javascript
// 日志查看弹窗
const logDialogVisible = ref(false);
const currentLogInstanceId = ref("");
const currentLogMode = ref("deploy");

function handleViewLogs(row) {
  currentLogInstanceId.value = row.id;
  // 部署中/启动中用 deploy 模式，其他用 runtime 模式
  currentLogMode.value = ["deploying", "starting"].includes(row.status) ? "deploy" : "runtime";
  logDialogVisible.value = true;
}

// 重试部署
async function handleRetryDeploy(row) {
  try {
    await ElMessageBox.confirm(
      `确定要重新部署实例「${row.instanceName || row.name}」吗？`,
      "确认重试",
      { type: "warning" }
    );
    await retryDeploy(row.id);
    ElMessage.success("已重新触发部署");
    fetchData();
    startAutoRefresh();
  } catch (e) {
    if (e !== "cancel") {
      ElMessage.error("重试部署失败: " + (e.message || "未知错误"));
    }
  }
}
```

需要确保 import：
```javascript
import { retryDeploy } from "@/api/instance";
```

- [ ] **Step 3: 新增自动刷新逻辑**

在 `fetchData` 函数之后添加：

```javascript
// 列表自动刷新（存在部署中/启动中/停止中状态时）
let autoRefreshTimer = null;

function startAutoRefresh() {
  stopAutoRefresh();
  autoRefreshTimer = setInterval(() => {
    const hasActive = tableData.value.some((row) =>
      ["deploying", "starting", "stopping"].includes(row.status)
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
```

- [ ] **Step 4: 修改 onMounted 和新增 onBeforeUnmount**

修改 `onMounted`（line 318-321），启动自动刷新：

```javascript
onMounted(() => {
  fetchHostOptions();
  fetchData().then(() => startAutoRefresh());
});

onBeforeUnmount(() => {
  stopAutoRefresh();
});
```

- [ ] **Step 5: 修改操作列模板 - 添加查看日志按钮**

修改操作列模板（line 465-505），替换为：

```html
<el-table-column label="操作" width="240" fixed="right">
  <template #default="{ row }">
    <div class="action-cell">
      <!-- 运行中状态 -->
      <template v-if="row.status === 'running'">
        <el-button type="warning" link size="small" :loading="row._loading" @click="handleStop(row)">停止</el-button>
        <el-button type="primary" link size="small" @click="handleViewLogs(row)">查看日志</el-button>
      </template>
      <!-- 已停止状态 -->
      <template v-else-if="row.status === 'stopped'">
        <el-button type="success" link size="small" :loading="row._loading" @click="handleStart(row)">启动</el-button>
        <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
      </template>
      <!-- 异常状态 -->
      <template v-else-if="row.status === 'error'">
        <el-button type="warning" link size="small" @click="handleRetryDeploy(row)">重试</el-button>
        <el-button type="primary" link size="small" @click="handleViewLogs(row)">查看日志</el-button>
        <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
      </template>
      <!-- 部署中/启动中/停止中状态 -->
      <template v-else-if="['deploying', 'starting', 'stopping'].includes(row.status)">
        <el-button type="primary" link size="small" @click="handleViewLogs(row)">
          <el-icon class="is-loading"><Loading /></el-icon>
          查看日志
        </el-button>
      </template>
      <!-- 其他状态 -->
      <template v-else>
        <el-tag type="info" size="small">{{ getStatusText(row.status) }}</el-tag>
      </template>
      <!-- 更多操作下拉 -->
      <el-dropdown v-if="getAvailableActions(row.status).length > 0" trigger="click" @command="(cmd) => handleCommand(cmd, row)">
        <el-button type="primary" link size="small">更多<el-icon><ArrowDown /></el-icon></el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="action in getAvailableActions(row.status)" :key="action.command" :command="action.command" :class="{ 'dropdown-danger': action.danger }">
              <el-icon><component :is="action.icon" /></el-icon>
              {{ action.label }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </template>
</el-table-column>
```

- [ ] **Step 6: 在模板末尾添加日志查看弹窗**

在 `</el-table>` 之后、页面结束标签之前添加：

```html
<!-- 日志查看弹窗 -->
<el-dialog v-model="logDialogVisible" title="实例日志" width="80%" :close-on-click-modal="false" destroy-on-close>
  <DeployProgress
    v-model:visible="logDialogVisible"
    :instance-id="currentLogInstanceId"
    :mode="currentLogMode"
  />
</el-dialog>
```

确保 `DeployProgress` 组件已 import（通常在文件顶部）：
```javascript
import DeployProgress from "@/components/DeployProgress.vue";
```

- [ ] **Step 7: Commit**

```bash
cd frontend
git add src/views/instance/index.vue
git commit -m "feat(instance-list): 状态显示、查看日志按钮、自动刷新、重试部署"
```

---

## Task 14: 前端集成验证

**Files:** 无修改，仅验证

- [ ] **Step 1: 启动后端**

Run（非阻塞）: `cd backend && mvn -pl core spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"`
Expected: 应用启动成功

- [ ] **Step 2: 启动前端**

Run（非阻塞）: `cd frontend && npm run dev`
Expected: Vite 启动，监听 3000

- [ ] **Step 3: 浏览器验证 - 部署流程**

1. 访问 `http://localhost:3000/login`，登录
2. 访问 `http://localhost:3000/instance/deploy`
3. 填写部署表单，选择游戏/主机/部署方式，点击"开始部署"
4. Expected: 显示成功提示"部署任务已创建，可在实例列表查看部署进度"
5. Expected: 自动跳转到 `/instance/list`
6. Expected: 实例列表顶部出现新实例，状态显示"部署中"（黄色 Loading 图标）
7. Expected: 操作列显示"查看日志"按钮（带 Loading 图标）

- [ ] **Step 4: 浏览器验证 - 查看部署日志**

1. 在实例列表点击"查看日志"按钮
2. Expected: 弹出日志弹窗，显示部署阶段日志（环境校验→端口检测→资源检查→预部署→部署→健康检查→启动）
3. Expected: 日志每 2 秒自动刷新，新日志追加到末尾
4. 等待部署完成
5. Expected: 部署成功后状态变为"运行中"（绿色），日志显示"部署成功"
6. Expected: 部署失败后状态变为"异常"（红色），日志显示失败原因

- [ ] **Step 5: 浏览器验证 - 运行时日志**

1. 部署成功的实例（状态为"运行中"），点击"查看日志"
2. Expected: 弹出日志弹窗，显示 Docker 容器日志
3. Expected: 日志每 2 秒自动刷新

- [ ] **Step 6: 浏览器验证 - 重试部署**

1. 异常状态的实例，点击"重试"
2. Expected: 弹出确认对话框
3. 确认后，Expected: 显示"已重新触发部署"，实例状态变为"部署中"

- [ ] **Step 7: 停止应用**

停止后端和前端进程。

---

## Task 15: 最终提交和清理

**Files:** 无修改

- [ ] **Step 1: 检查 git 状态**

Run: `cd d:\program\ai\game_platform_manger && git status`
Expected: 工作区干净（所有改动已分 task 提交）

- [ ] **Step 2: 查看提交历史**

Run: `cd d:\program\ai\game_platform_manger && git log --oneline -15`
Expected: 能看到本次特性的多个 commit

---

## 验收标准

完成所有任务后，应满足：

1. ✅ 点击部署后，实例列表出现新实例，状态显示"部署中"（黄色 Loading 图标）
2. ✅ 点击"查看日志"按钮，打开日志弹窗，实时显示部署阶段日志
3. ✅ 部署成功 → 状态变为"运行中"（绿色），日志弹窗显示"部署成功"
4. ✅ 部署失败 → 状态变为"异常"（红色），日志弹窗显示失败原因，可点击"重试"
5. ✅ 运行中实例点击"查看日志" → 显示 Docker 容器实时日志（每 2s 刷新）
6. ✅ 应用重启后，"部署中"实例自动标记为"异常"
7. ✅ 重复部署同一实例（已部署中）→ 后端拒绝（由现有 `selectByInstanceName` 校验实例名唯一性）

---

*Plan 完成日期: 2026-07-16*
