package com.gameplatform.plugin.l4d2.task;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.service.MapService;
import com.gameplatform.plugin.l4d2.util.ArchiveExtractUtil;
import com.gameplatform.plugin.service.FileTransferProgressCallback;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskHandler;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 地图上传任务处理器（ADR-0018）。
 * <p>
 * Controller 同步阶段只做扩展名校验与文件暂存，本 Handler 在执行队列中完成重活：
 * <ul>
 *   <li>.vpk：missions 校验 → SSH 上传 addons → 清缓存 → 可选自动裁剪</li>
 *   <li>.zip/.rar/.7z：解压到临时目录只提取 .vpk（其余丢弃、目录剥离、slip 防护、
 *       解压上限），逐个上传；部分失败不回滚已成功者（ADR-0018 决策 4）</li>
 * </ul>
 * payload 约定：instanceId（Long）、filename（String）、stagedPath（String）。
 * 暂存/临时文件在 finally 中清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapUploadTaskHandler implements TaskHandler {

    private final MapService mapService;
    private final L4D2Config config;

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

            if (MapService.isArchiveFilename(filename)) {
                return processArchive(context, instanceId, filename, stagedFile);
            }
            return processSingleVpk(context, instanceId, filename, stagedFile);
        } finally {
            cleanupQuietly(stagedFile);
        }
    }

    /** 单 VPK 直传 */
    private TaskResult processSingleVpk(TaskContext context, long instanceId,
                                        String filename, Path stagedFile) throws Exception {
        context.reportProgress(20, "解析 VPK 并上传到 addons 目录");
        // SSH 传输进度映射到任务进度 20→95 区间
        mapService.doUpload(instanceId, stagedFile, filename,
                transferProgressCallback(context, 20, 95, "上传 " + filename));
        context.reportProgress(95, "上传完成");
        context.log("地图上传完成: " + filename);
        context.reportProgress(100, "地图上传完成");
        return TaskResult.success(Map.of(), "地图上传完成: " + filename);
    }

    /** 压缩包：解包提取 .vpk，逐个上传，部分成功语义（ADR-0018 决策 4） */
    private TaskResult processArchive(TaskContext context, long instanceId,
                                      String filename, Path stagedFile) throws Exception {
        L4D2Config.Archive archiveCfg = config.getArchive();
        context.reportProgress(10, "解压压缩包（只提取 .vpk）");
        Path extractDir = Files.createTempDirectory("l4d2_map_extract_");
        try {
            List<File> vpks = ArchiveExtractUtil.extractVpks(stagedFile.toFile(), filename,
                    extractDir.toFile(), archiveCfg.getMaxExtractBytes(), archiveCfg.getMaxEntries());
            if (vpks.isEmpty()) {
                // 框架语义：只有抛异常才标记 FAILED（正常返回一律 COMPLETED）
                throw new IllegalStateException("压缩包中未找到 .vpk 地图文件");
            }
            context.log("提取到 " + vpks.size() + " 个 VPK: "
                    + vpks.stream().map(File::getName).toList());

            int success = 0;
            List<String> failures = new ArrayList<>();
            for (int i = 0; i < vpks.size(); i++) {
                if (context.isCancelled()) {
                    context.log("WARN", "任务已取消，停止剩余上传");
                    break;
                }
                File vpk = vpks.get(i);
                int startPercent = 20 + (int) (i * 70.0 / vpks.size());
                int endPercent = 20 + (int) ((i + 1) * 70.0 / vpks.size());
                String label = "上传 " + vpk.getName() + "（" + (i + 1) + "/" + vpks.size() + "）";
                context.reportProgress(startPercent, label);
                try {
                    // SSH 传输进度映射到当前 VPK 分到的任务进度区间
                    mapService.doUpload(instanceId, vpk.toPath(), vpk.getName(),
                            transferProgressCallback(context, startPercent, endPercent, label));
                    success++;
                    context.log("上传成功: " + vpk.getName());
                } catch (Exception e) {
                    failures.add(vpk.getName() + ": " + e.getMessage());
                    context.log("WARN", "上传失败（跳过继续）: " + vpk.getName() + " - " + e.getMessage());
                }
            }

            Map<String, Object> data = Map.of(
                    "total", vpks.size(),
                    "success", success,
                    "failed", failures.size(),
                    "failures", failures);
            if (success > 0) {
                String summary = "压缩包上传完成: 成功 " + success + "/" + vpks.size()
                        + (failures.isEmpty() ? "" : "，失败 " + failures);
                context.reportProgress(100, summary);
                return TaskResult.success(data, summary);
            }
            throw new IllegalStateException("压缩包内所有 VPK 上传失败: " + failures);
        } finally {
            cleanupDirQuietly(extractDir);
        }
    }

    /**
     * 把 SSH 传输进度映射为任务进度：传输百分比 0→100 线性映射到
     * [startPercent, endPercent] 区间，持续调用 {@code context.reportProgress}。
     * 节流由两层保证：传输层（64KB / 1%）+ 任务框架内部 1s 节流（ADR-014）。
     */
    private FileTransferProgressCallback transferProgressCallback(TaskContext context,
                                                                  int startPercent, int endPercent,
                                                                  String label) {
        return new FileTransferProgressCallback() {
            @Override
            public void onStart(long totalBytes) {
                // 区间起点已由调用方 reportProgress 报告，此处无需重复
            }

            @Override
            public void onProgress(long bytesTransferred, long totalBytes) {
                if (totalBytes <= 0) {
                    return;
                }
                int percent = startPercent + (int) ((endPercent - startPercent)
                        * bytesTransferred / (double) totalBytes);
                context.reportProgress(Math.min(percent, endPercent), label + " " + percent + "%");
            }

            @Override
            public void onComplete() {
                context.reportProgress(endPercent, label + " 100%");
            }

            @Override
            public void onError(Throwable error) {
                // 失败详情由 doUpload 抛出的异常链路记录（context.log / 任务失败信息）
            }
        };
    }

    private void cleanupQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            log.warn("清理暂存文件失败: {}", file, e);
        }
    }

    private void cleanupDirQuietly(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    log.warn("清理临时文件失败: {}", p, e);
                }
            });
        } catch (Exception e) {
            log.warn("清理临时目录失败: {}", dir, e);
        }
    }
}
