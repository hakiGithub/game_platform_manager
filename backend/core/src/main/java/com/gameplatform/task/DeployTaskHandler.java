package com.gameplatform.task;

import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.adapter.DeployProgressCallback;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import com.gameplatform.plugin.task.TaskSubmitContext;
import com.gameplatform.service.DeployService;
import com.gameplatform.service.DeployService.DeployContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 实例部署任务处理器（主应用 Handler，source=MAIN）
 *
 * <p>将 {@link DeployService#deploy} 封装为无状态 {@link TaskHandler}，
 * 由任务中心统一调度、监控、重试。部署进度与日志通过 {@link TaskContext} 上报，
 * 原有 {@link DeployProgressCallback} 回调适配为 TaskContext 调用。
 *
 * <p>payload 参数：
 * <ul>
 *   <li>{@code instanceId}（Long，必填）：实例ID</li>
 *   <li>{@code hostId}（Long，必填）：主机ID</li>
 *   <li>{@code deployType}（String，必填）：部署类型 docker / docker-compose / linuxgsm-docker</li>
 *   <li>{@code config}（Map，可选）：部署配置，由调用方构建（含镜像、端口、路径等）</li>
 *   <li>{@code autoRollback}（Boolean，默认 false）：失败时是否自动回滚</li>
 *   <li>{@code autoStart}（Boolean，默认 true）：部署后是否自动启动</li>
 * </ul>
 *
 * <p>互斥：默认按 (source=MAIN, taskType=deploy) 互斥。
 * 如需按实例互斥，调用方提交时设置 scopeKey=instanceId。
 *
 * <p>重试策略：maxRetryCount=1。部署有副作用（容器创建/启动），避免重复创建。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeployTaskHandler implements TaskHandler {

    /** 超时：30 分钟（大镜像拉取 + 健康检查重试） */
    private static final long DEFAULT_TIMEOUT_MS = 30 * 60 * 1000L;

    private final TaskHandlerRegistry registry;
    private final DeployService deployService;

    @PostConstruct
    public void init() {
        registry.register("MAIN", "deploy", this);
    }

    @Override
    public String getType() {
        return "deploy";
    }

    @Override
    public String getDisplayName() {
        return "实例部署";
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

    @Override
    public int getMaxRetryCount() {
        return 1;
    }

    @Override
    public long getDefaultTimeoutMs() {
        return DEFAULT_TIMEOUT_MS;
    }

    @Override
    public void onSubmit(TaskSubmitContext ctx) {
        TaskPayload payload = ctx.getPayload();
        Long instanceId = payload.getLong("instanceId");
        Long hostId = payload.getLong("hostId");
        String deployType = payload.getString("deployType");

        if (instanceId == null) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        if (hostId == null) {
            throw new IllegalArgumentException("hostId 不能为空");
        }
        if (deployType == null || deployType.isBlank()) {
            throw new IllegalArgumentException("deployType 不能为空");
        }
        if (DeployAdapter.DeployType.fromCode(deployType) == null) {
            throw new IllegalArgumentException("无效的 deployType: " + deployType);
        }
    }

    @Override
    public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
        Long instanceId = payload.getLong("instanceId");
        Long hostId = payload.getLong("hostId");
        String deployTypeStr = payload.getString("deployType");
        DeployAdapter.DeployType deployType = DeployAdapter.DeployType.fromCode(deployTypeStr);

        @SuppressWarnings("unchecked")
        Map<String, Object> config = payload.get("config") instanceof Map
                ? (Map<String, Object>) payload.get("config")
                : Map.of();
        boolean autoRollback = payload.getBoolean("autoRollback", false);
        boolean autoStart = payload.getBoolean("autoStart", true);

        context.log("开始部署: instanceId=" + instanceId
                + ", hostId=" + hostId + ", deployType=" + deployTypeStr);
        context.reportProgress(0, "初始化部署任务");

        DeployContext deployContext = DeployContext.builder()
                .instanceId(instanceId)
                .hostId(hostId)
                .deployType(deployType)
                .config(config)
                .autoRollback(autoRollback)
                .autoStart(autoStart)
                .build();

        // 将 DeployProgressCallback 适配为 TaskContext 调用
        DeployProgressCallback callback = new TaskContextCallbackAdapter(context);

        boolean success = deployService.deploy(deployContext, callback);

        if (success) {
            context.log("部署成功: instanceId=" + instanceId);
            return TaskResult.success(Map.of(
                    "instanceId", instanceId,
                    "deployType", deployTypeStr
            ), "部署成功完成");
        } else {
            return TaskResult.failure("部署失败，请查看日志详情",
                    Map.of("instanceId", instanceId));
        }
    }

    @Override
    public String getResultSummary(TaskResult result) {
        if (result == null) {
            return null;
        }
        if (!result.isSuccess()) {
            return result.getMessage();
        }
        Object instanceId = result.getData().get("instanceId");
        return "实例 " + instanceId + " 部署成功";
    }

    // ==================== 回调适配器 ====================

    /**
     * 将 {@link DeployProgressCallback} 适配为 {@link TaskContext} 调用。
     *
     * <p>进度回调映射：
     * <ul>
     *   <li>{@code onProgress} → {@link TaskContext#reportProgress}</li>
     *   <li>{@code onLog} → {@link TaskContext#log}</li>
     *   <li>{@code onStageStart/onStageComplete} → {@link TaskContext#log}（带阶段标识）</li>
     *   <li>{@code onError/onComplete} → 不转发（由 deploy 返回值决定终态）</li>
     * </ul>
     */
    private static class TaskContextCallbackAdapter implements DeployProgressCallback {

        private final TaskContext ctx;

        TaskContextCallbackAdapter(TaskContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onProgress(int percent, String stage, String message) {
            ctx.reportProgress(percent, "[" + stage + "] " + message);
        }

        @Override
        public void onComplete(boolean success, String message) {
            // 不转发：由 deploy() 返回值决定终态
        }

        @Override
        public void onError(String error, String stage, boolean recoverable) {
            ctx.log("ERROR", "[" + stage + "] " + error);
        }

        @Override
        public void onLog(String level, String message) {
            ctx.log(level, message);
        }

        @Override
        public void onStageStart(String stage, String description) {
            ctx.log("INFO", "[" + stage + "] 开始: " + description);
        }

        @Override
        public void onStageComplete(String stage, boolean success, String message) {
            String level = success ? "INFO" : "WARN";
            ctx.log(level, "[" + stage + "] " + (success ? "完成" : "失败") + ": " + message);
        }
    }
}
