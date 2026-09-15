package com.gameplatform.plugin.l4d2.task;

import com.gameplatform.plugin.l4d2.service.CloudInstallService;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import com.gameplatform.plugin.task.TaskSubmitContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 云盘转存安装任务处理器（ADR-0025）。
 *
 * <p>流程实现在 {@link CloudInstallService#execute}：转存（0-40%）→ 产物筛选（40-45%）→
 * 主机直连/平台中转下载安装（45-95%）→ 完成。取消/超时经 CancelledSignal 中断，
 * DownloadTaskResource（taskType=CLOUD）随阶段同步更新。
 *
 * <p>任务参数（payload）：
 * <ul>
 *   <li>{@code downloadTaskId}：关联的下载记录 ID</li>
 *   <li>{@code instanceId} / {@code accountName} / {@code shareUrl}（/ {@code passcode}）</li>
 *   <li>{@code cloudPath}：转存目标目录</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CloudInstallTaskHandler implements TaskHandler {

    private final CloudInstallService cloudInstallService;

    /** 超时：与主应用云盘转存默认超时对齐后放宽（转存 10min + 下载上传余量） */
    private static final long DEFAULT_TIMEOUT_MS = 45 * 60 * 1000L;

    @Override
    public String getType() {
        return "cloud-install";
    }

    @Override
    public String getDisplayName() {
        return "云盘转存安装";
    }

    @Override
    public boolean isRetryable() {
        // 转存幂等（SDK Diff）、安装覆盖，重试安全
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
        if (payload.getLong("instanceId") == null) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        if (payload.getString("accountName") == null || payload.getString("accountName").isBlank()) {
            throw new IllegalArgumentException("accountName 不能为空");
        }
        if (payload.getString("shareUrl") == null || payload.getString("shareUrl").isBlank()) {
            throw new IllegalArgumentException("shareUrl 不能为空");
        }
        if (payload.getString("cloudPath") == null || payload.getString("cloudPath").isBlank()) {
            throw new IllegalArgumentException("cloudPath 不能为空");
        }
    }

    @Override
    public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
        Map<String, Object> data = cloudInstallService.execute(context, payload);
        return TaskResult.success(data, "云盘转存安装完成");
    }

    @Override
    public String getResultSummary(TaskResult result) {
        if (result == null || !result.isSuccess() || result.getData() == null) {
            return result != null ? result.getMessage() : null;
        }
        Object installed = result.getData().get("installed");
        Object mode = result.getData().get("transferMode");
        return "已安装 " + (installed instanceof java.util.List<?> list ? list.size() : 0)
                + " 个文件（通道 " + mode + "）";
    }
}
