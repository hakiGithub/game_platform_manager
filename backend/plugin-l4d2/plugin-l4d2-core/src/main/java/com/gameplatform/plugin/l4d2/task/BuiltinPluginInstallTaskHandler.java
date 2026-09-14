package com.gameplatform.plugin.l4d2.task;

import com.gameplatform.plugin.l4d2.L4D2Constants;
import com.gameplatform.plugin.l4d2.service.BuiltinPluginInstaller;
import com.gameplatform.plugin.l4d2.service.InstallProgressListener;
import com.gameplatform.plugin.l4d2.vo.BuiltinPluginVO;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置插件安装任务处理器（支持单个与批量）。
 *
 * <p>内置插件 ZIP 体积悬殊（平台框架约 63MB，SSH 上传耗时可达数分钟），
 * 同步接口会长时间占用 HTTP 连接，故改走任务中心异步执行（与 map-upload 同模式）：
 * Controller 同步阶段仅校验清单并提交任务，本 Handler 在执行队列中完成
 * 解压 + 上传到 plugins_store 的重活。
 *
 * <p>payload 约定（二选一）：
 * <ul>
 *   <li>单个：instanceId（Long）、pluginId（String）、pluginName（String，展示用）</li>
 *   <li>批量：instanceId（Long）、pluginIds（List&lt;String&gt;）</li>
 * </ul>
 *
 * <p>批量语义：按提交顺序逐个安装，单个失败不影响其他（部分成功，ADR 与 installBatch 一致）；
 * 全部失败才标记任务 FAILED。每装完一个上报一次进度（10% 起，80% 区间均分）。
 *
 * <p>幂等：{@link BuiltinPluginInstaller#install} 内部已处理"已安装直接返回"。
 * 互斥：同实例的单装/批装共用一把互斥键，避免并发写 plugins_store。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinPluginInstallTaskHandler implements TaskHandler {

    private final BuiltinPluginInstaller builtinPluginInstaller;

    /** 超时：30 分钟（批装可能包含 63MB 平台框架 + 多个小插件，逐个 SSH 上传） */
    private static final long DEFAULT_TIMEOUT_MS = 30 * 60 * 1000L;

    @Override
    public String getType() {
        return L4D2Constants.TASK_TYPE_BUILTIN_PLUGIN_INSTALL;
    }

    @Override
    public String getDisplayName() {
        return "内置插件安装";
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

    @Override
    public int getMaxRetryCount() {
        // 安装有副作用（plugins_store 目录写入），install 幂等可覆盖重装，但避免多次重试堆积
        return 1;
    }

    @Override
    public long getDefaultTimeoutMs() {
        return DEFAULT_TIMEOUT_MS;
    }

    /**
     * 互斥键：单装与批装同实例互斥（两种 payload 均含 instanceId）。
     */
    @Override
    public String getMutexKey(TaskPayload payload) {
        String instanceId = payload.getString("instanceId");
        return instanceId == null ? null : "builtin-install:" + instanceId;
    }

    @Override
    public TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
        long instanceId = Long.parseLong(payload.getString("instanceId"));
        if (payload.get("pluginIds") instanceof List<?> ids && !ids.isEmpty()) {
            return executeBatch(context, instanceId, ids.stream().map(String::valueOf).toList());
        }
        return executeSingle(context, instanceId,
                payload.getString("pluginId"), payload.getString("pluginName"));
    }

    /**
     * 单个安装：安装链路（解压 + 逐文件上传）实时上报进度并响应取消；
     * 取消导致的安装中止按"正常返回"处理，由框架按 ctx.isCancelled 落 CANCELLED。
     */
    private TaskResult executeSingle(TaskContext context, long instanceId,
                                     String pluginId, String pluginName) throws Exception {
        if (context.isCancelled()) {
            context.log("WARN", "任务已取消，停止安装");
            return TaskResult.failure("任务已取消，停止安装");
        }

        context.reportProgress(10, "准备安装内置插件: " + pluginName);
        context.log("开始安装内置插件: instanceId=" + instanceId + ", plugin=" + pluginId);

        try {
            String msg = builtinPluginInstaller.install(instanceId, pluginId, taskListener(context));
            context.reportProgress(100, msg);
            context.log("内置插件安装完成: " + pluginId + " - " + msg);
            return TaskResult.success(
                    Map.of("pluginId", pluginId, "pluginName", pluginName), msg);
        } catch (Exception e) {
            if (context.isCancelled()) {
                context.log("WARN", "任务已取消，停止安装: " + e.getMessage());
                return TaskResult.failure("任务已取消，停止安装");
            }
            throw e;
        }
    }

    /** 批量安装：逐个执行，单个失败不影响其他，全部失败才 FAILED */
    private TaskResult executeBatch(TaskContext context, long instanceId, List<String> pluginIds) {
        if (context.isCancelled()) {
            context.log("WARN", "任务已取消，停止安装");
            return TaskResult.failure("任务已取消，停止安装");
        }

        int total = pluginIds.size();
        context.reportProgress(10, "准备批量安装 " + total + " 个内置插件");
        context.log("开始批量安装内置插件: instanceId=" + instanceId + ", count=" + total);

        int success = 0;
        boolean cancelled = false;
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (context.isCancelled() || context.isTimeout()) {
                cancelled = context.isCancelled();
                context.log("WARN", (cancelled ? "任务已取消" : "任务已超时")
                        + "，停止剩余安装（已处理 " + i + "/" + total + "）");
                break;
            }
            String pluginId = pluginIds.get(i);
            int percent = 10 + (int) (((i + 1) * 80.0) / total);
            BuiltinPluginVO vo = builtinPluginInstaller.findById(pluginId);
            String pluginName = vo != null ? vo.getName() : pluginId;
            context.reportProgress(percent, "安装 " + pluginName + "（" + (i + 1) + "/" + total + "）");

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pluginId", pluginId);
            item.put("pluginName", pluginName);
            try {
                String msg = builtinPluginInstaller.install(instanceId, pluginId, taskListener(context));
                success++;
                item.put("status", "SUCCESS");
                item.put("message", msg);
                context.log("安装成功: " + pluginId + " - " + msg);
            } catch (Exception e) {
                // 单插件安装中途取消：记失败项并置取消标志，循环下一轮终止
                if (context.isCancelled()) {
                    cancelled = true;
                }
                item.put("status", "FAILED");
                item.put("message", e.getMessage());
                context.log("WARN", "安装失败（跳过继续）: " + pluginId + " - " + e.getMessage());
            }
            results.add(item);
        }

        int processed = results.size();
        int failed = processed - success;

        // 取消且零成功 / 全部失败：按框架语义抛异常标记 FAILED（正常返回一律 COMPLETED）
        if (success == 0 && cancelled) {
            throw new IllegalStateException("任务已取消，0/" + total + " 个插件安装成功");
        }
        if (success == 0 && processed > 0) {
            List<String> errors = results.stream()
                    .filter(r -> "FAILED".equals(r.get("status")))
                    .map(r -> r.get("pluginId") + ": " + r.get("message"))
                    .toList();
            throw new IllegalStateException("批量安装全部失败（0/" + processed + " 成功）: " + errors);
        }

        Map<String, Object> data = Map.of(
                "total", total,
                "processed", processed,
                "success", success,
                "failed", failed,
                "results", results);

        if (processed < total) {
            // 取消/超时中断但已有部分成功，按成功处理（剩余插件可重新提交）
            String summary = (cancelled ? "批量安装已取消" : "批量安装超时中断")
                    + "：成功 " + success + "/" + total + "，未处理 " + (total - processed) + " 个";
            context.reportProgress(100, summary);
            return TaskResult.success(data, summary);
        }
        String summary;
        if (success == total) {
            summary = "全部 " + total + " 个插件安装成功";
        } else if (cancelled) {
            summary = "批量安装已取消：成功 " + success + "/" + total + "，失败 " + failed;
        } else {
            summary = "批量安装完成：成功 " + success + "/" + total + "，失败 " + failed;
        }
        context.reportProgress(100, summary);
        return TaskResult.success(data, summary);
    }

    /**
     * TaskContext → InstallProgressListener 桥接：安装链路的进度/日志直达任务详情，
     * 取消检查直达协作式取消标志（上传循环逐文件生效）。
     */
    private InstallProgressListener taskListener(TaskContext context) {
        return new InstallProgressListener() {
            @Override
            public boolean isCancelled() {
                return context.isCancelled();
            }

            @Override
            public void onProgress(int percent, String message) {
                context.reportProgress(percent, message);
            }

            @Override
            public void onLog(String level, String message) {
                context.log(level, message);
            }
        };
    }

    @Override
    public String getResultSummary(TaskResult result) {
        return result == null ? null : result.getMessage();
    }
}
