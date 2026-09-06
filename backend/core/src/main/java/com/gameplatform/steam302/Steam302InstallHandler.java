package com.gameplatform.steam302;

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
 * Steam302 安装任务处理器（主应用 Handler，source=MAIN，taskType=STEAM302_INSTALL）
 *
 * <p>互斥：调用方提交时设 scopeType=HOST、scopeKey=hostId，
 * 同一主机同时只跑一个 Steam302 任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Steam302InstallHandler implements TaskHandler {

    /** 超时：15 分钟（镜像拉取 10 分钟 + 起容器等证书 + 信任 CA） */
    private static final long DEFAULT_TIMEOUT_MS = 15 * 60 * 1000L;

    private final TaskHandlerRegistry registry;
    private final Steam302InstallExecutor executor;

    @PostConstruct
    public void init() {
        registry.register("MAIN", "STEAM302_INSTALL", this);
    }

    @Override
    public String getType() {
        return "STEAM302_INSTALL";
    }

    @Override
    public String getDisplayName() {
        return "Steam302 安装";
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

    @Override
    public int getMaxRetryCount() {
        // 安装有副作用（覆盖容器/配置），手动重试仅 1 次
        return 1;
    }

    @Override
    public long getDefaultTimeoutMs() {
        return DEFAULT_TIMEOUT_MS;
    }

    @Override
    public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
        Long hostId = payload.getLong("hostId");
        if (hostId == null) {
            return TaskResult.failure("payload 缺少 hostId");
        }

        Steam302InstallExecutor.ProgressListener progress = new Steam302InstallExecutor.ProgressListener() {
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

        executor.execute(hostId, progress);
        return TaskResult.success(Map.of("hostId", hostId));
    }

    @Override
    public String getResultSummary(TaskResult result) {
        return result != null && result.isSuccess() ? "Steam302 安装完成" : null;
    }
}
