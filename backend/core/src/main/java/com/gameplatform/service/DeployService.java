package com.gameplatform.service;

import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.adapter.DeployAdapterFactory;
import com.gameplatform.adapter.DeployProgressCallback;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.SshUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 部署服务
 * 提供统一的部署流程控制，包括环境校验、部署执行、失败回滚等功能
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
public class DeployService {

    @Autowired
    private DeployAdapterFactory adapterFactory;

    @Autowired
    private GameInstanceMapper instanceMapper;

    @Autowired
    private HostMapper hostMapper;

    @Autowired
    private SshUtil sshUtil;

    // 部署任务状态缓存
    private final Map<Long, DeployTaskStatus> taskStatusMap = new ConcurrentHashMap<>();

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

    /**
     * 部署任务状态
     */
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

    /**
     * 部署上下文
     */
    @Data
    @Builder
    public static class DeployContext {
        private Long instanceId;
        private Long hostId;
        private DeployAdapter.DeployType deployType;
        private Map<String, Object> config;
        private boolean autoRollback;
        private boolean autoStart;
    }

    /**
     * 环境校验结果
     */
    @Data
    @Builder
    public static class EnvironmentCheckResult {
        private boolean passed;
        private String message;
        private Map<String, Boolean> checks;

        public static EnvironmentCheckResult success() {
            return EnvironmentCheckResult.builder()
                    .passed(true)
                    .message("环境校验通过")
                    .checks(new HashMap<>())
                    .build();
        }

        public static EnvironmentCheckResult fail(String message) {
            return EnvironmentCheckResult.builder()
                    .passed(false)
                    .message(message)
                    .checks(new HashMap<>())
                    .build();
        }
    }

    /**
     * 执行完整部署流程
     *
     * @param context 部署上下文
     * @param callback 进度回调
     * @return 是否部署成功
     */
    public boolean deploy(DeployContext context, DeployProgressCallback callback) {
        Long instanceId = context.getInstanceId();
        DeployAdapter.DeployType deployType = context.getDeployType();

        log.info("开始部署流程: instanceId={}, deployType={}", instanceId, deployType);

        // 初始化任务状态
        DeployTaskStatus status = DeployTaskStatus.builder()
                .instanceId(instanceId)
                .stage("INIT")
                .progress(0)
                .message("初始化部署任务")
                .completed(false)
                .startTime(LocalDateTime.now())
                .build();
        taskStatusMap.put(instanceId, status);

        // 包装 callback，收集日志到 taskStatus
        DeployProgressCallback collectingCallback = new LogCollectingCallback(instanceId, callback);

        DeployAdapter adapter = adapterFactory.getAdapter(deployType);
        boolean success = false;

        try {
            // 1. 环境校验（使用详细检查，输出每项检查结果）
            updateTaskStatus(instanceId, "ENV_CHECK", 5, "执行环境校验");
            EnvironmentCheckResult envResult = checkEnvironment(context.getHostId(), deployType, context.getConfig());
            // 逐项输出检查结果到日志
            envResult.getChecks().forEach((checkName, passed) -> {
                String displayName = mapCheckNameToDisplay(checkName);
                if (passed) {
                    appendLog(instanceId, "INFO", "[ENV_CHECK] " + displayName + "：通过", "ENV_CHECK");
                } else {
                    appendLog(instanceId, "ERROR", "[ENV_CHECK] " + displayName + "：未通过", "ENV_CHECK");
                }
            });
            if (!envResult.isPassed()) {
                appendLog(instanceId, "ERROR", "[ENV_CHECK] 环境校验失败：" + envResult.getMessage(), "ENV_CHECK");
                throw new DeployException("环境校验失败：" + envResult.getMessage());
            }
            notifyProgress(collectingCallback, 10, "ENV_CHECK", "环境校验通过");

            // 2. 端口冲突检测
            updateTaskStatus(instanceId, "PORT_CHECK", 10, "检测端口冲突");
            if (!checkPortAvailability(context)) {
                throw new DeployException("端口冲突检测失败");
            }
            notifyProgress(collectingCallback, 15, "PORT_CHECK", "端口检测通过");

            // 3. 资源检查
            updateTaskStatus(instanceId, "RESOURCE_CHECK", 15, "检查系统资源");
            if (!checkResources(context)) {
                throw new DeployException("资源检查失败");
            }
            notifyProgress(collectingCallback, 20, "RESOURCE_CHECK", "资源检查通过");

            // 4. 预部署
            updateTaskStatus(instanceId, "PRE_DEPLOY", 20, "执行预部署");
            notifyStageStart(collectingCallback, "PRE_DEPLOY", "开始预部署准备");
            if (!adapter.preDeploy(instanceId, context.getConfig(), createStageCallback(collectingCallback, "PRE_DEPLOY", 20, 40))) {
                throw new DeployException("预部署失败");
            }
            notifyStageComplete(collectingCallback, "PRE_DEPLOY", true, "预部署完成");

            // 5. 部署
            updateTaskStatus(instanceId, "DEPLOY", 40, "执行部署");
            notifyStageStart(collectingCallback, "DEPLOY", "开始部署");
            if (!adapter.deploy(instanceId, context.getConfig(), createStageCallback(collectingCallback, "DEPLOY", 40, 80))) {
                throw new DeployException("部署失败");
            }
            notifyStageComplete(collectingCallback, "DEPLOY", true, "部署完成");

            // 6. 健康检查
            updateTaskStatus(instanceId, "HEALTH_CHECK", 80, "执行健康检查");
            if (!adapter.healthCheck(instanceId, context.getConfig())) {
                throw new DeployException("健康检查失败");
            }
            notifyProgress(collectingCallback, 90, "HEALTH_CHECK", "健康检查通过");

            // 7. 更新实例状态
            updateTaskStatus(instanceId, "UPDATE_STATUS", 90, "更新实例状态");
            updateRunStatus(instanceId, 0); // stopped

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

            success = true;
            updateTaskStatus(instanceId, "COMPLETE", 100, "部署成功");
            notifyComplete(collectingCallback, true, "部署成功完成");

            log.info("部署成功: instanceId={}", instanceId);

        } catch (DeployException e) {
            log.error("部署失败: instanceId={}", instanceId, e);
            success = false;

            // 更新任务状态
            DeployTaskStatus currentStatus = taskStatusMap.get(instanceId);
            if (currentStatus != null) {
                currentStatus.setCompleted(true);
                currentStatus.setSuccess(false);
                currentStatus.setError(e.getMessage());
                currentStatus.setEndTime(LocalDateTime.now());
            }

            // 失败时标记为异常状态
            updateRunStatus(instanceId, 2); // error
            appendLog(instanceId, "ERROR", e.getMessage(),
                    taskStatusMap.get(instanceId) != null ? taskStatusMap.get(instanceId).getStage() : "UNKNOWN");
            notifyError(collectingCallback, e.getMessage(),
                    taskStatusMap.get(instanceId) != null ? taskStatusMap.get(instanceId).getStage() : "UNKNOWN", false);

            // 自动回滚
            if (context.isAutoRollback()) {
                log.info("开始自动回滚: instanceId={}", instanceId);
                rollback(context, collectingCallback);
            }
        } catch (Exception e) {
            // 非 DeployException 的运行时异常（如 adapter 抛出的诊断异常），保留原始消息展示给用户
            log.error("部署异常: instanceId={}", instanceId, e);
            success = false;
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            // 更新任务状态
            DeployTaskStatus currentStatus = taskStatusMap.get(instanceId);
            if (currentStatus != null) {
                currentStatus.setCompleted(true);
                currentStatus.setSuccess(false);
                currentStatus.setError(errorMsg);
                currentStatus.setEndTime(LocalDateTime.now());
            }

            // 失败时标记为异常状态
            updateRunStatus(instanceId, 2); // error
            appendLog(instanceId, "ERROR", errorMsg,
                    currentStatus != null ? currentStatus.getStage() : "UNKNOWN");
            notifyError(collectingCallback, errorMsg,
                    currentStatus != null ? currentStatus.getStage() : "UNKNOWN", false);

            // 自动回滚
            if (context.isAutoRollback()) {
                log.info("开始自动回滚: instanceId={}", instanceId);
                rollback(context, collectingCallback);
            }
        }

        return success;
    }

    /**
     * 异步部署（使用传入的回调）
     */
    @Async
    public CompletableFuture<Boolean> deployAsync(DeployContext context, DeployProgressCallback callback) {
        return CompletableFuture.supplyAsync(() -> deploy(context, callback));
    }

    /**
     * 异步部署
     *
     * @param context 部署上下文
     * @return CompletableFuture
     */
    @Async
    public CompletableFuture<Boolean> deployAsync(DeployContext context) {
        return CompletableFuture.supplyAsync(() -> deploy(context, DeployProgressCallback.NO_OP));
    }

    /**
     * 卸载实例
     *
     * @param instanceId 实例ID
     * @param deployType 部署类型
     * @param callback 进度回调
     * @return 是否卸载成功
     */
    public boolean uninstall(Long instanceId, DeployAdapter.DeployType deployType, DeployProgressCallback callback) {
        log.info("开始卸载: instanceId={}, deployType={}", instanceId, deployType);

        DeployAdapter adapter = adapterFactory.getAdapter(deployType);

        // 获取实例配置
        Map<String, Object> config = getInstanceConfig(instanceId);

        try {
            notifyStageStart(callback, "UNINSTALL", "开始卸载");
            boolean success = adapter.uninstall(instanceId, config, callback);
            notifyStageComplete(callback, "UNINSTALL", success, success ? "卸载成功" : "卸载失败");

            return success;
        } catch (Exception e) {
            log.error("卸载失败: instanceId={}", instanceId, e);
            notifyError(callback, e.getMessage(), "UNINSTALL", false);
            return false;
        }
    }

    /**
     * 更新实例
     *
     * @param instanceId 实例ID
     * @param deployType 部署类型
     * @param callback 进度回调
     * @return 是否更新成功
     */
    public boolean update(Long instanceId, DeployAdapter.DeployType deployType, DeployProgressCallback callback) {
        log.info("开始更新: instanceId={}, deployType={}", instanceId, deployType);

        DeployAdapter adapter = adapterFactory.getAdapter(deployType);
        Map<String, Object> config = getInstanceConfig(instanceId);

        try {
            notifyStageStart(callback, "UPDATE", "开始更新");
            boolean success = adapter.update(instanceId, config, callback);
            notifyStageComplete(callback, "UPDATE", success, success ? "更新成功" : "更新失败");

            return success;
        } catch (Exception e) {
            log.error("更新失败: instanceId={}", instanceId, e);
            notifyError(callback, e.getMessage(), "UPDATE", false);
            return false;
        }
    }

    /**
     * 启动实例
     *
     * @param instanceId 实例ID
     * @param deployType 部署类型
     * @return 是否启动成功
     */
    public boolean start(Long instanceId, DeployAdapter.DeployType deployType) {
        log.info("启动实例: instanceId={}, deployType={}", instanceId, deployType);

        DeployAdapter adapter = adapterFactory.getAdapter(deployType);
        Map<String, Object> config = getInstanceConfig(instanceId);

        boolean success = adapter.start(instanceId, config);
        if (success) {
            updateInstanceStatus(instanceId, DeployAdapter.InstanceStatus.RUNNING);
        }

        return success;
    }

    /**
     * 停止实例
     *
     * @param instanceId 实例ID
     * @param deployType 部署类型
     * @return 是否停止成功
     */
    public boolean stop(Long instanceId, DeployAdapter.DeployType deployType) {
        log.info("停止实例: instanceId={}, deployType={}", instanceId, deployType);

        DeployAdapter adapter = adapterFactory.getAdapter(deployType);
        Map<String, Object> config = getInstanceConfig(instanceId);

        boolean success = adapter.stop(instanceId, config);
        if (success) {
            updateInstanceStatus(instanceId, DeployAdapter.InstanceStatus.STOPPED);
        }

        return success;
    }

    /**
     * 重启实例
     *
     * @param instanceId 实例ID
     * @param deployType 部署类型
     * @return 是否重启成功
     */
    public boolean restart(Long instanceId, DeployAdapter.DeployType deployType) {
        log.info("重启实例: instanceId={}, deployType={}", instanceId, deployType);

        DeployAdapter adapter = adapterFactory.getAdapter(deployType);
        Map<String, Object> config = getInstanceConfig(instanceId);

        return adapter.restart(instanceId, config);
    }

    /**
     * 获取实例状态
     *
     * @param instanceId 实例ID
     * @param deployType 部署类型
     * @return 实例状态
     */
    public DeployAdapter.InstanceStatus getStatus(Long instanceId, DeployAdapter.DeployType deployType) {
        DeployAdapter adapter = adapterFactory.getAdapter(deployType);
        Map<String, Object> config = getInstanceConfig(instanceId);

        return adapter.getStatus(instanceId, config);
    }

    /**
     * 获取实例日志
     *
     * @param instanceId 实例ID
     * @param deployType 部署类型
     * @param lines 行数
     * @return 日志内容
     */
    public String getLogs(Long instanceId, DeployAdapter.DeployType deployType, int lines) {
        DeployAdapter adapter = adapterFactory.getAdapter(deployType);
        Map<String, Object> config = getInstanceConfig(instanceId);

        return adapter.getLogs(instanceId, config, lines);
    }

    /**
     * 执行实例命令
     *
     * @param instanceId 实例ID
     * @param deployType 部署类型
     * @param command 命令
     * @return 执行结果
     */
    public String executeCommand(Long instanceId, DeployAdapter.DeployType deployType, String command) {
        DeployAdapter adapter = adapterFactory.getAdapter(deployType);
        Map<String, Object> config = getInstanceConfig(instanceId);

        return adapter.executeCommand(instanceId, config, command);
    }

    /**
     * 备份实例
     *
     * @param instanceId 实例ID
     * @param deployType 部署类型
     * @param callback 进度回调
     * @return 备份路径
     */
    public String backup(Long instanceId, DeployAdapter.DeployType deployType, DeployProgressCallback callback) {
        log.info("备份实例: instanceId={}, deployType={}", instanceId, deployType);

        DeployAdapter adapter = adapterFactory.getAdapter(deployType);
        Map<String, Object> config = getInstanceConfig(instanceId);

        return adapter.backup(instanceId, config, callback);
    }

    /**
     * 恢复实例
     *
     * @param instanceId 实例ID
     * @param deployType 部署类型
     * @param backupPath 备份路径
     * @param callback 进度回调
     * @return 是否恢复成功
     */
    public boolean restore(Long instanceId, DeployAdapter.DeployType deployType, String backupPath, DeployProgressCallback callback) {
        log.info("恢复实例: instanceId={}, deployType={}", instanceId, deployType);

        DeployAdapter adapter = adapterFactory.getAdapter(deployType);
        Map<String, Object> config = getInstanceConfig(instanceId);

        return adapter.restore(instanceId, config, backupPath, callback);
    }

    /**
     * 回滚部署
     *
     * @param context 部署上下文
     * @param callback 进度回调
     * @return 是否回滚成功
     */
    public boolean rollback(DeployContext context, DeployProgressCallback callback) {
        log.info("开始回滚: instanceId={}", context.getInstanceId());

        DeployAdapter adapter = adapterFactory.getAdapter(context.getDeployType());

        notifyStageStart(callback, "ROLLBACK", "开始回滚");

        try {
            boolean success = adapter.cleanup(context.getInstanceId(), context.getConfig(), callback);
            notifyStageComplete(callback, "ROLLBACK", success, success ? "回滚完成" : "回滚失败");
            return success;
        } catch (Exception e) {
            log.error("回滚失败: instanceId={}", context.getInstanceId(), e);
            notifyError(callback, "回滚失败: " + e.getMessage(), "ROLLBACK", false);
            return false;
        }
    }

    /**
     * 获取部署任务状态
     *
     * @param instanceId 实例ID
     * @return 任务状态
     */
    public DeployTaskStatus getTaskStatus(Long instanceId) {
        return taskStatusMap.get(instanceId);
    }

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

    /**
     * 将环境检查项英文名映射为中文显示名
     */
    private String mapCheckNameToDisplay(String checkName) {
        if (checkName == null) return "未知检查项";
        return switch (checkName) {
            case "sshConnection" -> "SSH 连接";
            case "dockerInstalled" -> "Docker 安装";
            case "dockerRunning" -> "Docker 服务运行";
            case "dockerComposeInstalled" -> "Docker Compose 安装";
            case "diskSpace" -> "磁盘空间";
            case "memory" -> "内存";
            case "ports" -> "端口可用性";
            default -> checkName;
        };
    }

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

    /**
     * 执行环境校验
     *
     * @param hostId 主机ID
     * @param deployType 部署类型
     * @param config 配置
     * @return 校验结果
     */
    public EnvironmentCheckResult checkEnvironment(Long hostId, DeployAdapter.DeployType deployType, Map<String, Object> config) {
        log.info("执行环境校验: hostId={}, deployType={}", hostId, deployType);

        DeployAdapter adapter = adapterFactory.getAdapter(deployType);
        Map<String, Boolean> checks = new HashMap<>();

        // 获取主机信息
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            return EnvironmentCheckResult.fail("主机不存在");
        }

        // 检查SSH连接
        checks.put("sshConnection", checkSshConnection(host));

        // 检查Docker（如果是Docker部署）
        if (deployType == DeployAdapter.DeployType.DOCKER
                || deployType == DeployAdapter.DeployType.DOCKER_COMPOSE
                || deployType == DeployAdapter.DeployType.LINUX_GSM_DOCKER) {
            checks.put("dockerInstalled", checkDockerInstalled(host));
            checks.put("dockerRunning", checkDockerRunning(host));
        }

        // 检查Docker Compose（如果是Docker Compose 或 LinuxGSM Docker 部署）
        if (deployType == DeployAdapter.DeployType.DOCKER_COMPOSE
                || deployType == DeployAdapter.DeployType.LINUX_GSM_DOCKER) {
            checks.put("dockerComposeInstalled", checkDockerComposeInstalled(host));
        }

        // 检查磁盘空间
        checks.put("diskSpace", checkDiskSpace(host, config));

        // 检查内存
        checks.put("memory", checkMemory(host, config));

        // 检查端口
        checks.put("ports", checkPorts(host, config));

        // 汇总结果
        boolean allPassed = checks.values().stream().allMatch(Boolean::booleanValue);

        EnvironmentCheckResult result = EnvironmentCheckResult.builder()
                .passed(allPassed)
                .checks(checks)
                .build();

        if (!allPassed) {
            StringBuilder failedChecks = new StringBuilder();
            checks.forEach((key, value) -> {
                if (!value) {
                    failedChecks.append(key).append(", ");
                }
            });
            result.setMessage("以下检查未通过: " + failedChecks.toString().replaceAll(", $", ""));
        } else {
            result.setMessage("环境校验通过");
        }

        return result;
    }

    // ========== 私有方法 ==========

    /**
     * 检查端口可用性
     */
    private boolean checkPortAvailability(DeployContext context) {
        // 端口检查在适配器的validateEnvironment中已完成
        return true;
    }

    /**
     * 检查资源
     */
    private boolean checkResources(DeployContext context) {
        // 资源检查在适配器的validateEnvironment中已完成
        return true;
    }

    /**
     * 获取实例配置
     */
    private Map<String, Object> getInstanceConfig(Long instanceId) {
        GameInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            return new HashMap<>();
        }

        Map<String, Object> config = new HashMap<>();
        if (instance.getConfigInfo() != null) {
            config.putAll(instance.getConfigInfo());
        }
        if (instance.getPortConfig() != null) {
            config.putAll(instance.getPortConfig());
        }

        return config;
    }

    /**
     * 更新实例状态
     */
    private void updateInstanceStatus(Long instanceId, DeployAdapter.InstanceStatus status) {
        GameInstance instance = instanceMapper.selectById(instanceId);
        if (instance != null) {
            instance.setRunStatus(status.getCode());
            instanceMapper.updateById(instance);
        }
    }

    /**
     * 更新任务状态
     */
    private void updateTaskStatus(Long instanceId, String stage, int progress, String message) {
        DeployTaskStatus status = taskStatusMap.get(instanceId);
        if (status != null) {
            status.setStage(stage);
            status.setProgress(progress);
            status.setMessage(message);
        }
    }

    /**
     * 创建阶段回调
     */
    private DeployProgressCallback createStageCallback(DeployProgressCallback parentCallback, String stage, int startPercent, int endPercent) {
        return new DeployProgressCallback() {
            @Override
            public void onProgress(int percent, String currentStage, String message) {
                int actualPercent = startPercent + (percent * (endPercent - startPercent) / 100);
                notifyProgress(parentCallback, actualPercent, stage, message);
            }

            @Override
            public void onComplete(boolean success, String message) {
                // 由父回调处理
            }

            @Override
            public void onError(String error, String errorStage, boolean recoverable) {
                notifyError(parentCallback, error, stage, recoverable);
            }

            @Override
            public void onLog(String level, String message) {
                notifyLog(parentCallback, level, message);
            }

            @Override
            public void onStageStart(String stageName, String description) {
                notifyStageStart(parentCallback, stageName, description);
            }

            @Override
            public void onStageComplete(String stageName, boolean success, String message) {
                notifyStageComplete(parentCallback, stageName, success, message);
            }
        };
    }

    /**
     * 检查SSH连接
     */
    private boolean checkSshConnection(Host host) {
        SshUtil.CommandResult result = sshUtil.executeCommand(
                host.getIpAddress(),
                host.getSshPort(),
                host.getSshUser(),
                decryptSshPrivateKey(host),
                decryptSshPassword(host),
                "echo 'SSH connection test'",
                10000
        );
        return result.isSuccess();
    }

    /**
     * 检查Docker是否已安装
     */
    private boolean checkDockerInstalled(Host host) {
        SshUtil.CommandResult result = sshUtil.executeCommand(
                host.getIpAddress(),
                host.getSshPort(),
                host.getSshUser(),
                decryptSshPrivateKey(host),
                decryptSshPassword(host),
                "docker --version",
                10000
        );
        return result.isSuccess() && result.getOutput().contains("Docker version");
    }

    /**
     * 检查Docker是否运行
     */
    private boolean checkDockerRunning(Host host) {
        SshUtil.CommandResult result = sshUtil.executeCommand(
                host.getIpAddress(),
                host.getSshPort(),
                host.getSshUser(),
                decryptSshPrivateKey(host),
                decryptSshPassword(host),
                "docker info",
                10000
        );
        return result.isSuccess();
    }

    /**
     * 检查Docker Compose是否已安装
     */
    private boolean checkDockerComposeInstalled(Host host) {
        SshUtil.CommandResult result = sshUtil.executeCommand(
                host.getIpAddress(),
                host.getSshPort(),
                host.getSshUser(),
                decryptSshPrivateKey(host),
                decryptSshPassword(host),
                "docker compose version || docker-compose --version",
                10000
        );
        return result.isSuccess() &&
                (result.getOutput().contains("Docker Compose") || result.getOutput().contains("docker-compose"));
    }

    /**
     * 检查磁盘空间
     */
    private boolean checkDiskSpace(Host host, Map<String, Object> config) {
        SshUtil.CommandResult result = sshUtil.executeCommand(
                host.getIpAddress(),
                host.getSshPort(),
                host.getSshUser(),
                decryptSshPrivateKey(host),
                decryptSshPassword(host),
                "df -h / | tail -1 | awk '{print $5}' | sed 's/%//'",
                10000
        );

        if (result.isSuccess()) {
            try {
                int usage = Integer.parseInt(result.getOutput().trim());
                return usage < 90; // 磁盘使用率小于90%
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 检查内存
     */
    private boolean checkMemory(Host host, Map<String, Object> config) {
        SshUtil.CommandResult result = sshUtil.executeCommand(
                host.getIpAddress(),
                host.getSshPort(),
                host.getSshUser(),
                decryptSshPrivateKey(host),
                decryptSshPassword(host),
                "free | grep Mem | awk '{print ($3/$2) * 100.0}'",
                10000
        );

        if (result.isSuccess()) {
            try {
                double usage = Double.parseDouble(result.getOutput().trim());
                return usage < 95; // 内存使用率小于95%
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 检查端口
     */
    private boolean checkPorts(Host host, Map<String, Object> config) {
        // 端口检查由适配器具体实现
        return true;
    }

    /**
     * AES 加密密钥（与 HostServiceImpl 保持一致）
     */
    private static final String ENCRYPT_KEY = "GamePlatform2024";

    /**
     * 解密 SSH 私钥
     * 若密码存在则优先使用密码认证，不传私钥（避免 SshUtil 优先使用私钥导致认证失败）
     *
     * @param host 主机实体
     * @return 解密后的私钥，或 null
     */
    private String decryptSshPrivateKey(Host host) {
        // 若密码存在，优先使用密码认证，不传私钥
        if (cn.hutool.core.util.StrUtil.isNotBlank(host.getSshPassword())) {
            return null;
        }
        if (cn.hutool.core.util.StrUtil.isBlank(host.getSshPrivateKey())) {
            return null;
        }
        try {
            return cn.hutool.crypto.SecureUtil.aes(ENCRYPT_KEY.getBytes()).decryptStr(host.getSshPrivateKey());
        } catch (Exception e) {
            log.warn("解密 SSH 私钥失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解密 SSH 密码
     *
     * @param host 主机实体
     * @return 解密后的密码，或 null
     */
    private String decryptSshPassword(Host host) {
        if (cn.hutool.core.util.StrUtil.isBlank(host.getSshPassword())) {
            return null;
        }
        try {
            return cn.hutool.crypto.SecureUtil.aes(ENCRYPT_KEY.getBytes()).decryptStr(host.getSshPassword());
        } catch (Exception e) {
            log.warn("解密 SSH 密码失败: {}", e.getMessage());
            return null;
        }
    }

    // ========== 回调通知方法 ==========

    private void notifyProgress(DeployProgressCallback callback, int percent, String stage, String message) {
        if (callback != null) {
            callback.onProgress(percent, stage, message);
        }
    }

    private void notifyStageStart(DeployProgressCallback callback, String stage, String description) {
        if (callback != null) {
            callback.onStageStart(stage, description);
        }
    }

    private void notifyStageComplete(DeployProgressCallback callback, String stage, boolean success, String message) {
        if (callback != null) {
            callback.onStageComplete(stage, success, message);
        }
    }

    private void notifyError(DeployProgressCallback callback, String error, String stage, boolean recoverable) {
        if (callback != null) {
            callback.onError(error, stage, recoverable);
        }
    }

    private void notifyLog(DeployProgressCallback callback, String level, String message) {
        if (callback != null) {
            callback.onLog(level, message);
        }
    }

    private void notifyComplete(DeployProgressCallback callback, boolean success, String message) {
        if (callback != null) {
            callback.onComplete(success, message);
        }
    }

    /**
     * 部署异常
     */
    public static class DeployException extends RuntimeException {
        public DeployException(String message) {
            super(message);
        }

        public DeployException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
