package com.gameplatform.plugin.l4d2.task;

import com.gameplatform.plugin.l4d2.service.MapRecognitionService;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 地图批量识别任务处理器（ADR-0027 决策 4③）。
 *
 * <p>对实例 addons 目录的索引条目执行识别（容器分析 / 摘要复用）：
 * PENDING 必处理；forceRetry=true 时同时重试共享记录为 FAILED/INVALID 的条目。
 * 触发入口：地图列表页"批量识别"按钮（forceRetry=true）与
 * {@code /maps/refresh} 自动补识别（forceRetry=false）。
 *
 * <p>payload 约定：instanceId（Long）、forceRetry（Boolean，可空默认 false）。
 * 互斥由框架按 (taskType, scopeKey=instanceId) 保证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapRecognizeTaskHandler implements TaskHandler {

    private final MapRecognitionService mapRecognitionService;

    /** 超时：30 分钟（几十个 VPK 逐个走容器分析的量级上限） */
    private static final long DEFAULT_TIMEOUT_MS = 30 * 60 * 1000L;

    @Override
    public String getType() {
        return "map-recognize";
    }

    @Override
    public String getDisplayName() {
        return "地图识别";
    }

    @Override
    public boolean isRetryable() {
        // 识别无副作用（只读 VPK + 写识别记录），重试安全
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
    public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
        long instanceId = Long.parseLong(payload.getString("instanceId"));
        boolean forceRetry = Boolean.parseBoolean(payload.getString("forceRetry"));

        int pending = mapRecognitionService.reconcileIndex(instanceId);
        context.log("索引对账完成，新增待识别 " + pending + " 个");
        context.reportProgress(5, "索引对账完成");

        int ok = mapRecognitionService.recognizePending(instanceId, new TaskLogAdapter(context), forceRetry);
        context.reportProgress(100, "地图识别完成");
        Map<String, Object> data = new HashMap<>();
        data.put("recognized", ok);
        return TaskResult.success(data, "地图识别完成: 成功 " + ok);
    }

    /** TaskContext → 识别服务日志出口适配 */
    private record TaskLogAdapter(TaskContext context) implements MapRecognitionService.TaskLog {
        @Override
        public void log(String message) {
            context.log(message);
        }

        @Override
        public void log(String level, String message) {
            context.log(level, message);
        }
    }
}
