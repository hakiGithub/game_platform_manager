package com.gameplatform.plugin.l4d2.task;

import com.gameplatform.plugin.l4d2.service.MapService;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 地图上传任务处理器（ADR-0018）。
 * <p>
 * Controller 同步阶段只做扩展名校验与文件暂存，本 Handler 在执行队列中完成
 * 重活：VPK magic 校验 → SSH 上传到 addons/ → 清缓存 → 可选自动裁剪。
 * 暂存文件在 finally 中清理（成功/失败/取消均不残留）。
 * <p>
 * payload 约定：instanceId（Long）、filename（String）、stagedPath（String）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapUploadTaskHandler implements TaskHandler {

    private final MapService mapService;

    /** 超时：10 分钟（大 VPK 经 SSH 上传 + 自动裁剪耗时较长） */
    private static final long DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L;

    @Override
    public String getType() {
        return "map-upload";
    }

    @Override
    public String getDisplayName() {
        return "地图上传";
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

    @Override
    public int getMaxRetryCount() {
        // 上传有副作用（addons 目录写入），重试覆盖同名文件是安全的，但避免多次重试堆积
        return 1;
    }

    @Override
    public long getDefaultTimeoutMs() {
        return DEFAULT_TIMEOUT_MS;
    }

    @Override
    public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
        long instanceId = Long.parseLong(payload.getString("instanceId"));
        String filename = payload.getString("filename");
        String stagedPath = payload.getString("stagedPath");
        Path stagedFile = Path.of(stagedPath);

        try {
            if (!Files.exists(stagedFile)) {
                context.log("WARN", "暂存文件不存在（可能已被清理）: " + stagedPath);
                return TaskResult.failure("暂存文件不存在: " + filename);
            }

            context.reportProgress(5, "校验暂存文件");
            context.log("开始处理地图上传: " + filename + "（" + Files.size(stagedFile) + " 字节）");

            context.reportProgress(20, "解析 VPK 并上传到 addons 目录");
            mapService.doUpload(instanceId, stagedFile, filename);

            context.reportProgress(95, "上传完成");
            context.log("地图上传完成: " + filename);
            context.reportProgress(100, "地图上传完成");
            return TaskResult.success(Map.of(), "地图上传完成: " + filename);
        } finally {
            try {
                Files.deleteIfExists(stagedFile);
            } catch (Exception e) {
                log.warn("清理暂存文件失败: {}", stagedPath, e);
            }
        }
    }
}
