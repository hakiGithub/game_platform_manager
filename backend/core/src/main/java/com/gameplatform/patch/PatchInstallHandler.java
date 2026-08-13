package com.gameplatform.patch;

import com.gameplatform.plugin.patch.PatchInstallRequest;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import com.gameplatform.task.TaskHandlerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 补丁安装任务处理器（主应用 Handler，source=MAIN，taskType=PATCH_INSTALL，ADR-0006 决策 1）
 *
 * <p>无状态：payload 解析为 {@link PatchInstallRequest}，执行委托 {@link PatchInstallExecutor}，
 * TaskContext 适配为执行器的 ProgressListener。</p>
 *
 * <p>互斥：调用方提交时设 scopeType=HOST、scopeKey=hostId，
 * 任务中心默认按 (taskType, scopeKey) 互斥 → 同一主机同时只跑一个补丁任务（ADR-0006 决策 8）。
 * 全局并发上限 3 由执行器内部信号量承担。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatchInstallHandler implements TaskHandler {

    /** 超时：1 小时（大补丁下载 + 解压 + 推送） */
    private static final long DEFAULT_TIMEOUT_MS = 60 * 60 * 1000L;

    private final TaskHandlerRegistry registry;
    private final PatchInstallExecutor executor;

    @PostConstruct
    public void init() {
        registry.register("MAIN", "PATCH_INSTALL", this);
    }

    @Override
    public String getType() {
        return "PATCH_INSTALL";
    }

    @Override
    public String getDisplayName() {
        return "补丁安装";
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

    @Override
    public int getMaxRetryCount() {
        // 安装有副作用（覆盖文件），手动重试仅 1 次
        return 1;
    }

    @Override
    public long getDefaultTimeoutMs() {
        return DEFAULT_TIMEOUT_MS;
    }

    @Override
    public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
        PatchInstallRequest request = PatchInstallRequest.builder()
                .instanceId(payload.getLong("instanceId"))
                .url(payload.getString("url"))
                .targetPath(payload.getString("targetPath"))
                .format(payload.getString("format"))
                .sha256(payload.getString("sha256"))
                .build();

        PatchInstallExecutor.ProgressListener progress = new PatchInstallExecutor.ProgressListener() {
            @Override
            public void onProgress(int percent, String message) {
                if (context.isTimeout()) {
                    return;
                }
                context.reportProgress(percent, message);
            }

            @Override
            public void onLog(String message) {
                context.log(message);
            }

            @Override
            public boolean isCancelled() {
                return context.isCancelled() || context.isTimeout();
            }
        };

        executor.execute(request, progress);
        return TaskResult.success(Map.of("instanceId", request.getInstanceId()));
    }

    @Override
    public String getResultSummary(TaskResult result) {
        return result != null && result.isSuccess() ? "补丁安装完成" : null;
    }
}
