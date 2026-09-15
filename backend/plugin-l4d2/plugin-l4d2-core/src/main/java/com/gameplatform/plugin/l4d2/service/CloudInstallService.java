package com.gameplatform.plugin.l4d2.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.exception.OptimisticLockException;
import com.gameplatform.plugin.l4d2.L4D2Constants;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.CloudInstallDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.DownloadTaskResource;
import com.gameplatform.plugin.l4d2.extension.DownloadTaskSpec;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.ArchiveExtractUtil;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.patch.PatchInstallRequest;
import com.gameplatform.plugin.patch.PatchInstallService;
import com.gameplatform.plugin.service.CloudDriveService;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.plugin.task.TaskContext;
import com.gameplatform.plugin.task.TaskPayload;
import com.gameplatform.plugin.task.TaskService;
import com.gameplatform.plugin.task.TaskSubmitRequest;
import com.gameplatform.plugin.task.TaskVO;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 云盘转存安装服务（ADR-0025）：分享链接 → 转存云盘账号 → 安装到实例 addons。
 *
 * <p>任务链路（{@code cloud-install} 任务中心任务）：
 * <ol>
 *   <li>转存（0-40%）：探测转存目录 {@code /maps/{source}-{sourceId}} 已有产物则跳过，
 *       否则同步转存（含提取码，SDK Diff 自动去重）</li>
 *   <li>筛选（40-45%）：list 产物目录，取 .vpk（直装）与 .zip（解压取 vpk），其余忽略</li>
 *   <li>下载安装（45-95%）：probeHost 判定通道——主机可自治下载走 DIRECT
 *       （PatchInstall + headers；UNSUPPORTED_HEADER_FILE 或直链两次失败转 RELAY）；
 *       否则 RELAY（cloudDriveService.download 流式 → VPK 校验 → uploadLocalFile）</li>
 * </ol>
 * 与 URL/Workshop 下载共用 {@link DownloadService} 的 3 并发额度；同步写
 * {@code DownloadTaskResource}（taskType=CLOUD）供下载页展示。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudInstallService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** VPK magic：小端 0x55AA1234，即字节序列 34 12 AA 55 */
    private static final byte[] VPK_MAGIC = {0x34, 0x12, (byte) 0xAA, 0x55};

    public static final String TASK_TYPE_CLOUD = "CLOUD";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_RELAY = "RELAY";

    private final ExtensionClient extensionClient;
    private final CloudDriveService cloudDriveService;
    private final PatchInstallService patchInstallService;
    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final TaskService taskService;
    private final DownloadService downloadService;
    private final L4D2PathResolver pathResolver;
    private final L4D2Config config;

    /** 任务取消/超时信号：中断整个安装流程（不再转兜底通道） */
    static class CancelledSignal extends RuntimeException {
        CancelledSignal(String message) {
            super(message);
        }
    }

    // ===== 提交侧 =====

    /**
     * 创建云盘转存安装任务：写 DownloadTaskResource（PENDING）并提交任务中心。
     *
     * @return 下载任务 ID（DownloadTaskResource.name）
     */
    public String createTask(CloudInstallDTO dto) {
        if (dto == null || dto.getInstanceId() == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "instanceId 不能为空");
        }
        if (isBlank(dto.getAccountName()) || isBlank(dto.getShareUrl())) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "accountName/shareUrl 不能为空");
        }
        InstanceVO instance = instanceQueryService.getInstanceById(dto.getInstanceId());
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在: " + dto.getInstanceId());
        }

        String downloadTaskId = IdUtil.getSnowflakeNextIdStr();
        String cloudPath = cloudPathOf(dto);

        Map<String, Object> payload = new HashMap<>();
        payload.put("downloadTaskId", downloadTaskId);
        payload.put("instanceId", dto.getInstanceId());
        payload.put("accountName", dto.getAccountName());
        payload.put("shareUrl", dto.getShareUrl());
        if (!isBlank(dto.getPasscode())) {
            payload.put("passcode", dto.getPasscode());
        }
        payload.put("cloudPath", cloudPath);
        if (!isBlank(dto.getTitle())) {
            payload.put("title", dto.getTitle());
        }

        String taskCenterId = taskService.submit(TaskSubmitRequest.builder()
                .taskType(L4D2Constants.TASK_TYPE_CLOUD_INSTALL)
                .source(L4D2Constants.TASK_SOURCE)
                .scopeType("INSTANCE")
                .scopeKey(String.valueOf(dto.getInstanceId()))
                .payload(payload)
                .build());

        DownloadTaskResource resource = new DownloadTaskResource();
        resource.setName(downloadTaskId);
        resource.setStatus(DownloadService.STATUS_PENDING);
        DownloadTaskSpec spec = new DownloadTaskSpec();
        spec.setTaskId(downloadTaskId);
        spec.setInstanceId(dto.getInstanceId());
        spec.setTaskType(TASK_TYPE_CLOUD);
        spec.setTaskUrl(dto.getShareUrl());
        spec.setTaskStatus(DownloadService.STATUS_PENDING);
        spec.setProgress(0.0);
        spec.setFilename(isBlank(dto.getTitle()) ? dto.getShareUrl() : dto.getTitle());
        spec.setTargetPath(pathResolver.getAddonsPath() + "/");
        spec.setAccountName(dto.getAccountName());
        spec.setCloudPath(cloudPath);
        spec.setPatchTaskId(taskCenterId);
        spec.setRetryCount(0);
        spec.setMaxRetry(0);
        spec.setIsDeleted(false);
        resource.setSpec(spec);
        extensionClient.create(resource);

        log.info("创建云盘转存安装任务: downloadTaskId={}, taskCenterId={}, account={}, cloudPath={}, instance={}",
                downloadTaskId, taskCenterId, dto.getAccountName(), cloudPath, dto.getInstanceId());
        return downloadTaskId;
    }

    // ===== 执行侧（由 CloudInstallTaskHandler 调用）=====

    public Map<String, Object> execute(TaskContext context, TaskPayload payload) throws Exception {
        String downloadTaskId = payload.getString("downloadTaskId");
        Long instanceId = payload.getLong("instanceId");
        String accountName = payload.getString("accountName");
        String shareUrl = payload.getString("shareUrl");
        String passcode = payload.getString("passcode");
        String cloudPath = payload.getString("cloudPath");

        RecordUpdater updater = new RecordUpdater(downloadTaskId);
        List<String> installed = new ArrayList<>();
        String transferMode = MODE_RELAY;
        downloadService.acquireDownloadSlot();
        try {
            InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
            if (instance == null) {
                updater.fail("实例不存在: " + instanceId);
                throw new IllegalStateException("实例不存在: " + instanceId);
            }
            updater.begin();

            // ===== 阶段 1：转存（0-40%） =====
            context.reportProgress(2, "探测转存目录 " + cloudPath);
            List<CloudDriveService.CloudFileInfo> products = listProducts(accountName, cloudPath);
            boolean transferred = false;
            if (products.isEmpty()) {
                context.reportProgress(5, "转存分享链接到 " + cloudPath);
                List<String> result = cloudDriveService.transfer(accountName, shareUrl, passcode, cloudPath, null);
                context.log("转存完成，引擎回填产物 " + result.size() + " 项");
                context.reportProgress(38, "转存完成，拉取产物列表");
                products = listProducts(accountName, cloudPath);
                transferred = true;
            } else {
                context.log("转存目录已有产物 " + products.size() + " 项，跳过转存（SDK Diff 幂等）");
            }
            if (products.isEmpty()) {
                updater.fail("转存后未发现任何 .vpk/.zip 产物: " + cloudPath);
                throw new IllegalStateException("转存后未发现任何 .vpk/.zip 产物: " + cloudPath);
            }
            updater.progress(40.0);

            // ===== 阶段 2：通道判定（40-45%） =====
            boolean preferDirect = false;
            try {
                HostCapabilities caps = patchInstallService.probeHost(instance.getHostId());
                preferDirect = caps != null && (caps.hasTool("curl") || caps.hasTool("wget") || caps.hasDocker());
            } catch (Exception e) {
                context.log("WARN", "主机能力探测失败，走平台中转: " + e.getMessage());
            }
            transferMode = preferDirect ? MODE_DIRECT : MODE_RELAY;
            updater.transferMode(transferMode);
            context.reportProgress(45, "通道: " + (preferDirect ? "主机直连" : "平台中转")
                    + "，待安装 " + products.size() + " 个文件");

            // ===== 阶段 3：逐文件下载安装（45-95%） =====
            int total = products.size();
            int i = 0;
            for (CloudDriveService.CloudFileInfo item : products) {
                int base = 45 + (95 - 45) * i / total;
                int slice = 45 + (95 - 45) * (i + 1) / total;
                String lower = item.name().toLowerCase(Locale.ROOT);
                // DIRECT 仅覆盖 vpk/zip（PatchInstall 无 rar 格式），rar 一律 RELAY 解压
                if (preferDirect && !lower.endsWith(".rar")) {
                    DirectOutcome outcome = installDirect(context, accountName, instanceId, item, base, slice, updater);
                    if (outcome.fallbackToRelay()) {
                        preferDirect = false;
                        transferMode = MODE_RELAY;
                        updater.transferMode(MODE_RELAY);
                    } else {
                        installed.addAll(outcome.installed());
                    }
                }
                if (!preferDirect || lower.endsWith(".rar")) {
                    installed.addAll(installRelay(context, accountName, instanceId, item, base, slice, updater));
                }
                i++;
            }

            // ===== 完成（95-100%） =====
            updater.complete(installed);
            context.reportProgress(100, "云盘安装完成，共 " + installed.size() + " 个 vpk");
            Map<String, Object> data = new HashMap<>();
            data.put("installed", installed);
            data.put("transferMode", transferMode);
            data.put("transferred", transferred);
            data.put("cloudPath", cloudPath);
            return data;
        } catch (CancelledSignal cs) {
            updater.cancelled(cs.getMessage());
            throw new IllegalStateException(cs.getMessage());
        } catch (Exception e) {
            updater.fail(e.getMessage());
            throw e;
        } finally {
            downloadService.releaseDownloadSlot();
        }
    }

    /** DIRECT 通道结果：安装清单；或标记 fallbackToRelay（直连不可用/直链两次失败） */
    private record DirectOutcome(List<String> installed, boolean fallbackToRelay) {
        static DirectOutcome installed(List<String> files) {
            return new DirectOutcome(files, false);
        }

        static DirectOutcome fallback() {
            return new DirectOutcome(List.of(), true);
        }
    }

    /** DIRECT 通道：拿直链→立即下发 PatchInstall（带 headers）；不可用/两次失败返回 fallback */
    private DirectOutcome installDirect(TaskContext context, String accountName, Long instanceId,
                                        CloudDriveService.CloudFileInfo item, int base, int slice,
                                        RecordUpdater updater) {
        String name = item.name();
        String lower = name.toLowerCase(Locale.ROOT);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // 直链时效契约（ADR-0025）：拿链后立即下发，不缓存
                CloudDriveService.CloudLink link = cloudDriveService.link(accountName, item.path());
                PatchInstallRequest.PatchInstallRequestBuilder builder = PatchInstallRequest.builder()
                        .instanceId(instanceId)
                        .url(link.url())
                        .headers(link.headers());
                if (lower.endsWith(".vpk")) {
                    builder.targetPath(pathResolver.getAddonsPath() + "/" + name);
                } else {
                    // zip/rar（ADR-0025/0026）：直链 URL 无扩展名，显式声明格式；
                    // includePattern 只取 vpk 平铺落 addons（工具容器/原生解压）
                    builder.targetPath(pathResolver.getAddonsPath() + "/")
                            .format(lower.endsWith(".rar") ? "rar" : "zip")
                            .includePattern("*.vpk");
                }
                String patchTaskId = patchInstallService.install(builder.build());
                context.log("直连下载 " + name + "（第 " + attempt + " 次）");
                String failure = pollPatchTask(context, patchTaskId, base, slice, name, updater);
                if (failure == null) {
                    return DirectOutcome.installed(List.of(name));
                }
                if (failure.contains("UNSUPPORTED_HEADER_FILE")) {
                    context.log("WARN", "主机不支持请求头下载（curl<7.55 或无 curl），整体转平台中转");
                    return DirectOutcome.fallback();
                }
                context.log("WARN", "直连下载失败（第 " + attempt + " 次）: " + failure);
            } catch (CancelledSignal cs) {
                throw cs;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (msg.contains("UNSUPPORTED_HEADER_FILE")) {
                    context.log("WARN", "主机不支持请求头下载，整体转平台中转");
                    return DirectOutcome.fallback();
                }
                context.log("WARN", "直连异常（第 " + attempt + " 次）: " + msg);
            }
        }
        // 两次失败（直链可能过期）：转中转兜底（ADR-0025 直链时效契约）
        context.log("WARN", "直连两次失败，" + name + " 转平台中转");
        return DirectOutcome.fallback();
    }

    /** RELAY 通道：平台流式下载 → VPK/压缩包处理 → uploadLocalFile（覆盖语义）；返回安装文件清单 */
    private List<String> installRelay(TaskContext context, String accountName, Long instanceId,
                                      CloudDriveService.CloudFileInfo item, int base, int slice,
                                      RecordUpdater updater) {
        String name = item.name();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean isArchive = lower.endsWith(".zip") || lower.endsWith(".rar");
        Path tempFile = null;
        try {
            context.reportProgress(base, "平台中转下载 " + name + "（" + humanSize(item.size()) + "）");
            tempFile = Files.createTempFile("l4d2_cloud_",
                    lower.endsWith(".zip") ? ".zip" : lower.endsWith(".rar") ? ".rar" : ".vpk");
            long bytes = cloudDriveService.download(accountName, item.path(),
                    new ProgressOutputStream(new FileOutputStream(tempFile.toFile()), item.size(),
                            pct -> context.reportProgress(base + (slice - base) * 2 / 3 * pct / 100,
                                    "下载 " + name + " " + pct + "%")));
            updater.downloaded(bytes);
            context.log("下载完成 " + name + "，" + bytes + " 字节，推送到实例");

            List<String> uploaded = new ArrayList<>();
            if (isArchive) {
                Path extractDir = Files.createTempDirectory("l4d2_cloud_extract_");
                List<File> vpks = ArchiveExtractUtil.extractVpks(tempFile.toFile(), name,
                        extractDir.toFile(), config.getArchive().getMaxExtractBytes(),
                        config.getArchive().getMaxEntries());
                if (vpks.isEmpty()) {
                    throw new IllegalStateException("压缩包内未找到 .vpk: " + name);
                }
                for (File vpk : vpks) {
                    uploadVpk(context, instanceId, vpk, slice);
                    uploaded.add(vpk.getName());
                }
            } else {
                requireVpk(tempFile.toFile(), name);
                uploadVpk(context, instanceId, tempFile.toFile(), slice);
                uploaded.add(name);
            }
            return uploaded;
        } catch (CancelledSignal cs) {
            throw cs;
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "中转安装失败 " + name + ": " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignore) {
                    // 平台侧临时文件清理失败无碍
                }
            }
        }
    }

    private void uploadVpk(TaskContext context, Long instanceId, File file, int slice) {
        context.reportProgress(slice - 1, "推送 " + file.getName() + " 到实例");
        try {
            instanceFileService.uploadLocalFile(instanceId,
                    pathResolver.getAddonsPath() + "/" + file.getName(), file.getAbsolutePath());
        } catch (Exception e) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "推送失败 " + file.getName() + ": " + e.getMessage());
        }
    }

    // ===== 工具 =====

    /** 转存产物筛选：目录下 .vpk（直装）与 .zip/.rar（RELAY 解压取 vpk）；目录不存在视为无产物 */
    private List<CloudDriveService.CloudFileInfo> listProducts(String accountName, String cloudPath) {
        try {
            return cloudDriveService.list(accountName, cloudPath, true).stream()
                    .filter(f -> !f.directory())
                    .filter(f -> {
                        String n = f.name().toLowerCase(Locale.ROOT);
                        return n.endsWith(".vpk") || n.endsWith(".zip") || n.endsWith(".rar");
                    })
                    .toList();
        } catch (Exception e) {
            log.debug("转存目录探测无产物 {}: {}", cloudPath, e.getMessage());
            return List.of();
        }
    }

    /** 轮询 PatchInstall 任务到终态；成功返回 null，失败返回原因；取消/超时抛 CancelledSignal */
    private String pollPatchTask(TaskContext context, String patchTaskId, int base, int slice,
                                 String label, RecordUpdater updater) throws InterruptedException {
        while (true) {
            if (context.isCancelled() || context.isTimeout()) {
                taskService.cancelMyOwn(patchTaskId);
                throw new CancelledSignal(context.isCancelled() ? "任务已取消" : "任务已超时");
            }
            TaskVO task = taskService.getTask(patchTaskId);
            if (task != null) {
                String status = task.getStatus();
                int pct = task.getProgress() == null ? 0 : (int) Math.round(task.getProgress());
                switch (status) {
                    case "PENDING", "RUNNING" -> {
                        int mapped = base + (slice - base) * pct / 100;
                        context.reportProgress(mapped, label + " " + pct + "%");
                        updater.progress((double) mapped);
                    }
                    case "COMPLETED" -> {
                        return null;
                    }
                    case "FAILED" -> {
                        return task.getErrorMessage() != null ? task.getErrorMessage() : "安装失败";
                    }
                    case "CANCELLED" -> {
                        throw new CancelledSignal("任务已取消");
                    }
                    default -> {
                    }
                }
            }
            Thread.sleep(2000);
        }
    }

    private void requireVpk(File file, String name) throws IOException {
        byte[] header = new byte[4];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            if (in.read(header) < 4 || header[0] != VPK_MAGIC[0] || header[1] != VPK_MAGIC[1]
                    || header[2] != VPK_MAGIC[2] || header[3] != VPK_MAGIC[3]) {
                throw new IllegalStateException("非 VPK 文件（magic 校验失败）: " + name);
            }
        }
    }

    /** 转存目录（ADR-0025）：/maps/{source}-{sourceId}；裸链接 share-{hash8} */
    static String cloudPathOf(CloudInstallDTO dto) {
        if (!isBlank(dto.getSource()) && !isBlank(dto.getSourceId())) {
            return "/maps/" + sanitize(dto.getSource() + "-" + dto.getSourceId());
        }
        return "/maps/share-" + DigestUtil.md5Hex(dto.getShareUrl()).substring(0, 8);
    }

    private static String sanitize(String input) {
        return input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "未知大小";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0fKB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024);
        }
        return String.format(Locale.ROOT, "%.2fGB", bytes / 1024.0 / 1024 / 1024);
    }

    // ===== DownloadTaskResource 状态回写（乐观锁重试） =====

    private class RecordUpdater {
        private final String taskId;

        RecordUpdater(String taskId) {
            this.taskId = taskId;
        }

        void begin() {
            withResource(r -> {
                r.getSpec().setTaskStatus(DownloadService.STATUS_DOWNLOADING);
                r.getSpec().setStartTime(LocalDateTime.now().format(TIME_FORMATTER));
            }, DownloadService.STATUS_DOWNLOADING);
        }

        void progress(Double pct) {
            withResource(r -> r.getSpec().setProgress(pct), null);
        }

        void transferMode(String mode) {
            withResource(r -> r.getSpec().setTransferMode(mode), null);
        }

        void downloaded(long bytes) {
            withResource(r -> {
                r.getSpec().setDownloadedSize(bytes);
                r.getSpec().setFileSize(bytes);
            }, null);
        }

        void complete(List<String> installed) {
            withResource(r -> {
                DownloadTaskSpec s = r.getSpec();
                s.setTaskStatus(DownloadService.STATUS_COMPLETED);
                s.setProgress(100.0);
                s.setCompleteTime(LocalDateTime.now().format(TIME_FORMATTER));
                s.setRemark("已安装: " + String.join(", ", installed));
            }, DownloadService.STATUS_COMPLETED);
        }

        void fail(String message) {
            withResource(r -> {
                DownloadTaskSpec s = r.getSpec();
                s.setTaskStatus(DownloadService.STATUS_FAILED);
                s.setErrorMessage(message);
                s.setCompleteTime(LocalDateTime.now().format(TIME_FORMATTER));
            }, DownloadService.STATUS_FAILED);
        }

        void cancelled(String message) {
            withResource(r -> {
                DownloadTaskSpec s = r.getSpec();
                s.setTaskStatus(DownloadService.STATUS_CANCELLED);
                s.setErrorMessage(message);
                s.setCompleteTime(LocalDateTime.now().format(TIME_FORMATTER));
            }, DownloadService.STATUS_CANCELLED);
        }

        /** 每次重新 get→改→update，乐观锁冲突重读重试（进度更新失败不致命） */
        private void withResource(java.util.function.Consumer<DownloadTaskResource> mutator, String status) {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    DownloadTaskResource r = extensionClient.get(DownloadTaskResource.class, taskId).orElse(null);
                    if (r == null) {
                        return;
                    }
                    mutator.accept(r);
                    if (status != null) {
                        r.setStatus(status);
                    }
                    extensionClient.update(r);
                    return;
                } catch (OptimisticLockException e) {
                    // 重读重试
                } catch (Exception e) {
                    log.warn("下载记录更新失败 taskId={}, err={}", taskId, e.getMessage());
                    return;
                }
            }
        }
    }

    /** 按字节数汇报下载进度的输出流 */
    private static class ProgressOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long total;
        private final java.util.function.IntConsumer progressConsumer;
        private long written;
        private int lastReported = -1;

        ProgressOutputStream(OutputStream delegate, long total, java.util.function.IntConsumer progressConsumer) {
            this.delegate = delegate;
            this.total = total;
            this.progressConsumer = progressConsumer;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            written++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            written += len;
            if (total > 0) {
                int pct = (int) (written * 100 / total);
                if (pct != lastReported && pct <= 100) {
                    lastReported = pct;
                    progressConsumer.accept(pct);
                }
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
