package com.gameplatform.plugin.l4d2.service;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ListOptions;
import com.gameplatform.plugin.extension.exception.OptimisticLockException;
import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.UrlDownloadDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.extension.DownloadTaskResource;
import com.gameplatform.plugin.l4d2.extension.DownloadTaskSpec;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.FilenameSanitizeUtil;
import com.gameplatform.plugin.l4d2.vo.DownloadTaskVO;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L4D2 下载服务：URL 文件下载、任务管理、进度与速度计算、并发控制、超时清理。
 *
 * <p>双层存储：
 * <ul>
 *   <li>内存：{@link ConcurrentHashMap} 保存活跃任务的运行时（VO 快照 + Future + CancelToken）</li>
 *   <li>DB：通过 {@link ExtensionClient} 持久化 {@link DownloadTaskResource}，提供历史查询</li>
 * </ul>
 *
 * <p>对齐源项目 {@code download.go}：
 * <ul>
 *   <li>3 个并发下载（{@link Semaphore}）</li>
 *   <li>5 秒滑动窗口速度计算（{@link ScheduledExecutorService}）</li>
 *   <li>VPK magic 检测（{@code 0x34 0x12 0xaa 0x55}）</li>
 *   <li>启动时清理 IN_PROGRESS 状态记录</li>
 *   <li>每小时清理 6 小时未完成的任务</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DOWNLOADING = "DOWNLOADING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_PENDING_MANUAL = "PENDING_MANUAL";

    /** URL 切分正则：匹配 http(s) 开头的 URL */
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[^\\s\\n\\r]+)");

    /** VPK magic：小端 0x55AA1234，即字节序列 34 12 AA 55 */
    private static final byte[] VPK_MAGIC = {0x34, 0x12, (byte) 0xAA, 0x55};

    /** 磁盘空间使用阈值（90%） */
    private static final double DISK_USAGE_THRESHOLD = 0.9;

    /** 超时阈值：6 小时（毫秒） */
    private static final long TIMEOUT_MS = 6L * 3600 * 1000;

    /** 速度计算间隔（秒） */
    private static final long SPEED_INTERVAL_SECONDS = 5L;

    /** 速度计算初始延迟（秒） */
    private static final long SPEED_INITIAL_DELAY_SECONDS = 5L;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ExternalHttpClient httpClient;
    private final InstanceFileService instanceFileService;
    private final InstanceQueryService instanceQueryService;
    private final ExtensionClient extensionClient;
    private final L4D2Config config;
    private final L4D2PathResolver pathResolver;
    private final ObjectMapper objectMapper;

    /** 内存任务表：taskId → runtime */
    private final Map<String, DownloadTaskRuntime> tasks = new ConcurrentHashMap<>();

    /** 并发下载数限制（3） */
    private final Semaphore downloadSemaphore = new Semaphore(3);

    /** 速度计算定时器（2 个线程） */
    private final ScheduledExecutorService speedExecutor = Executors.newScheduledThreadPool(2);

    // ===== 公共 API =====

    /**
     * 创建 URL 下载任务（支持多 URL 切分）。
     *
     * @param dto 下载请求
     * @return 任务 ID 列表（每个 URL 一个任务）
     */
    public List<String> createUrlTasks(UrlDownloadDTO dto) {
        if (dto == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "下载请求不能为空");
        }
        if (dto.getInstanceId() == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "instanceId 不能为空");
        }
        if (dto.getUrl() == null || dto.getUrl().isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "url 不能为空");
        }

        // 校验实例存在
        InstanceVO instance = requireInstance(dto.getInstanceId());

        // 磁盘空间检查
        checkDiskSpace();

        // 切分多 URL
        List<String> urls = splitURLString(dto.getUrl());
        if (urls.isEmpty()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "未匹配到有效的 URL: " + dto.getUrl());
        }

        // 清洗可选参数
        String filename = FilenameSanitizeUtil.sanitize(dto.getFilename());
        String targetPath = FilenameSanitizeUtil.sanitizePath(dto.getTargetPath());

        List<String> taskIds = new ArrayList<>();
        for (String url : urls) {
            String taskId = IdUtil.getSnowflakeNextIdStr();
            DownloadTaskResource resource = buildUrlResource(taskId, dto, url, filename, targetPath);
            extensionClient.create(resource);

            DownloadTaskVO vo = toVO(resource);
            DownloadTaskRuntime runtime = new DownloadTaskRuntime(vo, resource);
            tasks.put(taskId, runtime);

            runtime.future = CompletableFuture.runAsync(() -> runDownload(runtime));
            taskIds.add(taskId);
            log.info("创建 URL 下载任务（maxConcurrent={}）: taskId={}, url={}, instanceId={}",
                    config.getWorkshop().getMaxConcurrent(), taskId, url, dto.getInstanceId());
        }
        return taskIds;
    }

    /**
     * 创建 Workshop 下载任务（由 WorkshopDownloadService 调用）。
     *
     * <p>本质上与 URL 下载相同，但记录 workshopId / workshopTitle / previewUrl，
     * 并把 taskType 设为 {@code WORKSHOP}。referer 优先用参数，若空则用
     * {@code config.workshop.proxyUrl}（对齐 spec §3.1 降级策略）。
     *
     * @param instanceId    实例 ID
     * @param workshopId    Workshop ID
     * @param workshopTitle Workshop 标题
     * @param previewUrl    预览图 URL
     * @param fileUrl       下载 URL
     * @param filename      文件名（可为空，下载时推断）
     * @param referer       Referer（若 config.workshop.proxyUrl 非空，使用它作为 referer）
     * @return taskId
     */
    public String createWorkshopTask(Long instanceId, String workshopId, String workshopTitle,
                                      String previewUrl, String fileUrl, String filename, String referer) {
        if (instanceId == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "instanceId 不能为空");
        }
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "fileUrl 不能为空");
        }

        // 校验实例存在
        InstanceVO instance = requireInstance(instanceId);

        // 磁盘空间检查
        checkDiskSpace();

        // referer：参数优先，否则用 config.workshop.proxyUrl
        String effectiveReferer = (referer != null && !referer.isBlank()) ? referer : config.getWorkshop().getProxyUrl();
        String sanitizedFilename = FilenameSanitizeUtil.sanitize(filename);
        String targetPath = pathResolver.getAddonsPath() + "/";

        String taskId = IdUtil.getSnowflakeNextIdStr();
        DownloadTaskResource resource = buildWorkshopResource(
                taskId, instanceId, workshopId, workshopTitle, previewUrl,
                fileUrl, sanitizedFilename, targetPath, effectiveReferer, STATUS_PENDING, null);
        extensionClient.create(resource);

        DownloadTaskVO vo = toVO(resource);
        DownloadTaskRuntime runtime = new DownloadTaskRuntime(vo, resource);
        tasks.put(taskId, runtime);

        runtime.future = CompletableFuture.runAsync(() -> runDownload(runtime));
        log.info("创建 Workshop 下载任务: taskId={}, workshopId={}, url={}, instanceId={}",
                taskId, workshopId, fileUrl, instanceId);
        return taskId;
    }

    /**
     * 创建 PENDING_MANUAL 状态任务（无 file_url 时由 WorkshopDownloadService 调用）。
     *
     * <p>不启动异步下载，仅写入 DB（用于历史记录与提示用户配置代理 URL）。
     *
     * @param instanceId    实例 ID
     * @param workshopId    Workshop ID
     * @param workshopTitle Workshop 标题
     * @param previewUrl    预览图 URL
     * @param remark        备注（提示信息）
     * @return taskId
     */
    public String createManualTask(Long instanceId, String workshopId, String workshopTitle,
                                    String previewUrl, String remark) {
        if (instanceId == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "instanceId 不能为空");
        }

        // 校验实例存在
        requireInstance(instanceId);

        String taskId = IdUtil.getSnowflakeNextIdStr();
        DownloadTaskResource resource = buildWorkshopResource(
                taskId, instanceId, workshopId, workshopTitle, previewUrl,
                null, null, null, null, STATUS_PENDING_MANUAL, remark);
        extensionClient.create(resource);

        log.info("创建 PENDING_MANUAL 任务: taskId={}, workshopId={}, instanceId={}",
                taskId, workshopId, instanceId);
        return taskId;
    }

    /**
     * 列出任务（合并 DB 历史与内存活跃任务，按 startTime 倒序）。
     *
     * @param instanceId 实例 ID（null 返回所有）
     * @param status     状态过滤（null 返回所有）
     * @return 任务 VO 列表
     */
    public List<DownloadTaskVO> listTasks(Long instanceId, String status) {
        // 1. 从 DB 查询全部任务
        ListOptions.Builder builder = ListOptions.builder().limit(10000);
        if (instanceId != null) {
            builder.specFilter("$.instanceId", "=", instanceId);
        }
        List<DownloadTaskResource> dbList = extensionClient.list(DownloadTaskResource.class, builder.build());

        // 2. 合并：DB 优先，活跃任务用内存 VO 覆盖
        List<DownloadTaskVO> result = new ArrayList<>();
        for (DownloadTaskResource resource : dbList) {
            DownloadTaskSpec spec = resource.getSpec();
            String taskId = spec == null ? null : spec.getTaskId();
            DownloadTaskRuntime runtime = taskId == null ? null : tasks.get(taskId);
            // 活跃任务的内存 VO 更新（含实时进度/速度）
            result.add(runtime != null ? runtime.vo : toVO(resource));
        }

        // 3. 按 status 过滤
        if (status != null && !status.isBlank()) {
            result = new ArrayList<>(result.stream()
                    .filter(vo -> status.equals(vo.getStatus())).toList());
        }

        // 4. 按 startTime 倒序排序
        result.sort(Comparator.comparing(DownloadTaskVO::getStartTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    /**
     * 获取任务详情。优先查内存，再查 DB。
     *
     * @param taskId 任务 ID
     * @return 任务 VO；不存在返回 null
     */
    public DownloadTaskVO getTask(String taskId) {
        DownloadTaskRuntime runtime = tasks.get(taskId);
        if (runtime != null) {
            return runtime.vo;
        }
        Optional<DownloadTaskResource> opt = extensionClient.getById(DownloadTaskResource.class, taskId);
        return opt.map(this::toVO).orElse(null);
    }

    /**
     * 取消任务：设置内存取消标志 + 更新 DB 状态。
     *
     * @param taskId 任务 ID
     */
    public void cancel(String taskId) {
        DownloadTaskRuntime runtime = tasks.get(taskId);
        if (runtime != null) {
            runtime.cancelled = true;
            if (runtime.future != null) {
                runtime.future.cancel(true);
            }
            log.info("取消下载任务（内存标志已设置）: taskId={}", taskId);
        }
        // 更新 DB 状态为 CANCELLED
        try {
            Optional<DownloadTaskResource> opt = extensionClient.getById(DownloadTaskResource.class, taskId);
            if (opt.isPresent()) {
                DownloadTaskResource resource = opt.get();
                DownloadTaskSpec spec = resource.getSpec();
                if (spec != null) {
                    String current = spec.getTaskStatus();
                    // 仅非终态才更新
                    if (!isTerminal(current)) {
                        spec.setTaskStatus(STATUS_CANCELLED);
                        spec.setCompleteTime(LocalDateTime.now().format(TIME_FORMATTER));
                        resource.setStatus(STATUS_CANCELLED);
                        extensionClient.update(resource);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("取消任务更新 DB 失败 taskId={}, err={}", taskId, e.getMessage());
        }
    }

    /**
     * 删除任务（仅终态可删除）。
     *
     * @param taskId 任务 ID
     */
    public void delete(String taskId) {
        DownloadTaskRuntime runtime = tasks.get(taskId);
        if (runtime != null) {
            // 校验非活跃（已终态）
            String status = runtime.vo.getStatus();
            if (!isTerminal(status)) {
                throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                        "任务非终态，无法删除: taskId=" + taskId + ", status=" + status);
            }
            tasks.remove(taskId);
        }
        extensionClient.deleteById(DownloadTaskResource.class, taskId);
        log.info("删除下载任务: taskId={}", taskId);
    }

    // ===== 异步下载流程 =====

    /**
     * 异步执行下载（对齐源项目 download.go:188-344）。
     */
    private void runDownload(DownloadTaskRuntime runtime) {
        DownloadTaskVO vo = runtime.vo;
        File tempFile = null;
        try {
            // 1. 获取信号量
            downloadSemaphore.acquire();
            try {
                // 2. 检查取消标志
                if (runtime.cancelled) {
                    vo.setStatus(STATUS_CANCELLED);
                    vo.setCompleteTime(LocalDateTime.now().format(TIME_FORMATTER));
                    updateDb(runtime);
                    return;
                }

                // 3. 更新 status=DOWNLOADING
                vo.setStatus(STATUS_DOWNLOADING);
                runtime.lastSpeedUpdate = System.currentTimeMillis();
                runtime.lastSecondBytes = 0L;
                updateDb(runtime);

                // 4. 启动速度定时器
                runtime.speedTimer = speedExecutor.scheduleAtFixedRate(
                        () -> updateSpeed(runtime),
                        SPEED_INITIAL_DELAY_SECONDS, SPEED_INTERVAL_SECONDS, TimeUnit.SECONDS);

                // 5. 调用下载
                tempFile = httpClient.download(
                        vo.getTaskUrl(),
                        vo.getFilename(),
                        null,
                        downloadedBytes -> {
                            vo.setDownloadedSize(downloadedBytes);
                            long total = vo.getFileSize() == null ? 0L : vo.getFileSize();
                            if (total > 0) {
                                vo.setProgress(Math.min(100.0, downloadedBytes * 100.0 / total));
                            }
                        },
                        () -> runtime.cancelled);

                // 6. 停止速度定时器
                stopSpeedTimer(runtime);

                // 7. 再次检查取消标志
                if (runtime.cancelled) {
                    if (tempFile != null) {
                        tempFile.delete();
                    }
                    vo.setStatus(STATUS_CANCELLED);
                    vo.setCompleteTime(LocalDateTime.now().format(TIME_FORMATTER));
                    updateDb(runtime);
                    return;
                }

                // 8. 推断文件名（若未指定）
                if ((vo.getFilename() == null || vo.getFilename().isBlank()) && tempFile != null) {
                    String inferred = FilenameSanitizeUtil.sanitize(tempFile.getName());
                    if (inferred != null) {
                        vo.setFilename(inferred);
                    }
                }

                // 9. VPK magic 检测
                boolean isVpk = isVpkFile(tempFile);
                if (isVpk) {
                    // 若未以 .vpk 结尾，重命名临时文件加 .vpk 后缀
                    String currentName = vo.getFilename();
                    if (currentName == null || !currentName.toLowerCase().endsWith(".vpk")) {
                        String newName = (currentName == null ? "downloaded_file" : currentName) + ".vpk";
                        if (tempFile != null) {
                            File renamed = new File(tempFile.getAbsolutePath() + ".vpk");
                            if (tempFile.renameTo(renamed)) {
                                tempFile = renamed;
                                vo.setFilename(newName);
                            }
                        }
                    }
                    // 若 targetPath 为空，自动设置为 addons/
                    if (vo.getTargetPath() == null || vo.getTargetPath().isBlank()) {
                        InstanceVO instance = requireInstance(vo.getInstanceId());
                        vo.setTargetPath(pathResolver.getAddonsPath() + "/");
                    }
                }

                // 10. 推断 targetPath（非 VPK 时必须显式指定）
                if (vo.getTargetPath() == null || vo.getTargetPath().isBlank()) {
                    throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                            "targetPath 不能为空（非 VPK 文件需显式指定）");
                }

                // 11. 上传到远程
                InstanceVO instance = requireInstance(vo.getInstanceId());
                String remotePath = joinPath(vo.getTargetPath(), vo.getFilename());
                instanceFileService.uploadLocalFile(instance.getId(), remotePath, tempFile.getAbsolutePath());

                // 12. 更新 status=COMPLETED
                if (tempFile != null) {
                    vo.setFileSize(tempFile.length());
                }
                vo.setProgress(100.0);
                vo.setStatus(STATUS_COMPLETED);
                vo.setCompleteTime(LocalDateTime.now().format(TIME_FORMATTER));
                updateDb(runtime);
                log.info("下载任务完成: taskId={}, filename={}", vo.getTaskId(), vo.getFilename());
            } finally {
                downloadSemaphore.release();
            }
        } catch (Exception e) {
            stopSpeedTimer(runtime);
            // 13. 异常处理：优先检查 cancelled 标志（避免中断异常覆盖 CANCELLED 状态）
            if (runtime.cancelled) {
                vo.setStatus(STATUS_CANCELLED);
            } else if (e instanceof L4D2PluginException l4e && l4e.getMessage() != null
                    && l4e.getMessage().contains("已取消")) {
                vo.setStatus(STATUS_CANCELLED);
            } else {
                vo.setStatus(STATUS_FAILED);
                vo.setErrorMessage(e.getMessage());
            }
            vo.setCompleteTime(LocalDateTime.now().format(TIME_FORMATTER));
            try {
                updateDb(runtime);
            } catch (Exception ex) {
                log.warn("更新失败状态到 DB 出错 taskId={}, err={}", vo.getTaskId(), ex.getMessage());
            }
            log.error("下载任务失败: taskId={}, url={}", vo.getTaskId(), vo.getTaskUrl(), e);
        } finally {
            // 14. 删除临时文件
            if (tempFile != null) {
                try {
                    if (!tempFile.delete()) {
                        tempFile.deleteOnExit();
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ===== 速度计算与 DB 同步 =====

    /**
     * 速度计算（对齐源项目 download.go:164-185）：5 秒滑动窗口。
     */
    private void updateSpeed(DownloadTaskRuntime runtime) {
        try {
            long now = System.currentTimeMillis();
            long downloaded = nullToZero(runtime.vo.getDownloadedSize());
            long elapsed = (now - runtime.lastSpeedUpdate) / 1000;
            if (elapsed > 0) {
                double speed = (downloaded - runtime.lastSecondBytes) / (double) elapsed;
                runtime.vo.setDownloadSpeed(speed);
                runtime.lastSecondBytes = downloaded;
                runtime.lastSpeedUpdate = now;
                // 合并写入 DB
                updateDb(runtime);
            }
        } catch (Exception e) {
            log.warn("速度计算异常 taskId={}, err={}", runtime.vo.getTaskId(), e.getMessage());
        }
    }

    /**
     * DB 同步（乐观锁 + 重试一次）。
     */
    private void updateDb(DownloadTaskRuntime runtime) {
        String taskId = runtime.vo.getTaskId();
        try {
            DownloadTaskResource latest = extensionClient.getById(DownloadTaskResource.class, taskId)
                    .orElseThrow(() -> new L4D2PluginException(L4D2PluginException.BUSINESS,
                            "下载任务不存在: " + taskId));
            copyVoToSpec(runtime.vo, latest.getSpec());
            latest.setStatus(runtime.vo.getStatus());
            if (log.isDebugEnabled()) {
                log.debug("DB 同步 taskId={}, spec={}", taskId, specToJson(latest.getSpec()));
            }
            try {
                extensionClient.update(latest);
                runtime.resource = latest;
            } catch (OptimisticLockException e) {
                // 重试一次
                log.debug("乐观锁冲突，重试 taskId={}", taskId);
                DownloadTaskResource retry = extensionClient.getById(DownloadTaskResource.class, taskId)
                        .orElseThrow(() -> new L4D2PluginException(L4D2PluginException.BUSINESS,
                                "下载任务不存在: " + taskId));
                copyVoToSpec(runtime.vo, retry.getSpec());
                retry.setStatus(runtime.vo.getStatus());
                extensionClient.update(retry);
                runtime.resource = retry;
            }
        } catch (Exception e) {
            log.warn("DB 同步失败 taskId={}, err={}", taskId, e.getMessage());
        }
    }

    // ===== 启动与定时清理 =====

    /**
     * 启动时清理：所有 IN_PROGRESS/DOWNLOADING/PENDING 状态记录标记为 FAILED。
     */
    @PostConstruct
    public void cleanupOnStartup() {
        try {
            List<DownloadTaskResource> all = extensionClient.listAll(DownloadTaskResource.class);
            int cleaned = 0;
            for (DownloadTaskResource resource : all) {
                DownloadTaskSpec spec = resource.getSpec();
                if (spec == null) continue;
                String status = spec.getTaskStatus();
                if (STATUS_PENDING.equals(status) || STATUS_DOWNLOADING.equals(status)) {
                    spec.setTaskStatus(STATUS_FAILED);
                    spec.setErrorMessage("服务重启中断");
                    spec.setCompleteTime(LocalDateTime.now().format(TIME_FORMATTER));
                    resource.setStatus(STATUS_FAILED);
                    try {
                        extensionClient.update(resource);
                        cleaned++;
                    } catch (Exception e) {
                        log.warn("启动清理更新失败 taskId={}, err={}",
                                spec.getTaskId(), e.getMessage());
                    }
                }
            }
            if (cleaned > 0) {
                log.info("启动清理：将 {} 个未完成下载任务标记为 FAILED", cleaned);
            }
        } catch (Exception e) {
            log.warn("启动清理异常: {}", e.getMessage());
        }
    }

    /**
     * 定时清理：每小时扫描，6 小时未完成的任务标记为 FAILED。
     */
    @Scheduled(fixedRate = 3600_000)
    public void cleanupTimeoutTasks() {
        try {
            ListOptions opts = ListOptions.builder()
                    .specFilter("$.taskStatus", "=", STATUS_DOWNLOADING)
                    .limit(10000)
                    .build();
            List<DownloadTaskResource> list = extensionClient.list(DownloadTaskResource.class, opts);
            long now = System.currentTimeMillis();
            int cleaned = 0;
            for (DownloadTaskResource resource : list) {
                DownloadTaskSpec spec = resource.getSpec();
                if (spec == null || spec.getStartTime() == null) continue;
                Long startMs = parseTimeToMillis(spec.getStartTime());
                if (startMs == null) continue;
                if (now - startMs > TIMEOUT_MS) {
                    spec.setTaskStatus(STATUS_FAILED);
                    spec.setErrorMessage("任务超时未完成");
                    spec.setCompleteTime(LocalDateTime.now().format(TIME_FORMATTER));
                    resource.setStatus(STATUS_FAILED);
                    try {
                        extensionClient.update(resource);
                        cleaned++;
                    } catch (Exception e) {
                        log.warn("超时清理更新失败 taskId={}, err={}",
                                spec.getTaskId(), e.getMessage());
                    }
                    // 从内存移除
                    if (spec.getTaskId() != null) {
                        DownloadTaskRuntime runtime = tasks.remove(spec.getTaskId());
                        if (runtime != null) {
                            stopSpeedTimer(runtime);
                        }
                    }
                }
            }
            if (cleaned > 0) {
                log.info("超时清理：将 {} 个超时下载任务标记为 FAILED", cleaned);
            }
        } catch (Exception e) {
            log.warn("超时清理异常: {}", e.getMessage());
        }
    }

    // ===== 私有辅助方法 =====

    /**
     * 校验实例存在并返回 InstanceVO。
     */
    private InstanceVO requireInstance(Long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在: " + instanceId);
        }
        return instance;
    }

    /**
     * 磁盘空间检查：使用率超过 90% 抛异常。
     */
    private void checkDiskSpace() {
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        long total = tmpDir.getTotalSpace();
        long usable = tmpDir.getUsableSpace();
        if (total <= 0) {
            return;
        }
        double usage = 1.0 - (double) usable / total;
        if (usage > DISK_USAGE_THRESHOLD) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "磁盘空间不足，当前使用率: " + String.format("%.2f%%", usage * 100));
        }
    }

    /**
     * URL 切分（对齐源项目 download.go:447-463）。
     */
    private List<String> splitURLString(String input) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(input);
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            if (!url.isEmpty()) {
                urls.add(url);
            }
        }
        return urls;
    }

    /**
     * 构造 URL 下载任务 Resource。
     */
    private DownloadTaskResource buildUrlResource(String taskId, UrlDownloadDTO dto, String url,
                                                  String filename, String targetPath) {
        DownloadTaskResource resource = new DownloadTaskResource();
        resource.setName(taskId);
        resource.setStatus(STATUS_PENDING);
        DownloadTaskSpec spec = new DownloadTaskSpec();
        spec.setTaskId(taskId);
        spec.setInstanceId(dto.getInstanceId());
        spec.setTaskType("URL");
        spec.setTaskUrl(url);
        spec.setReferer(dto.getReferer());
        spec.setFilename(filename);
        spec.setTargetPath(targetPath);
        spec.setTaskStatus(STATUS_PENDING);
        spec.setProgress(0.0);
        spec.setDownloadedSize(0L);
        spec.setFileSize(0L);
        spec.setDownloadSpeed(0.0);
        spec.setRetryCount(0);
        spec.setMaxRetry(3);
        spec.setIsDeleted(false);
        spec.setStartTime(LocalDateTime.now().format(TIME_FORMATTER));
        resource.setSpec(spec);
        return resource;
    }

    /**
     * 构造 Workshop 下载任务 Resource（用于 createWorkshopTask / createManualTask）。
     *
     * @param taskId        任务 ID
     * @param instanceId    实例 ID
     * @param workshopId    Workshop ID
     * @param workshopTitle Workshop 标题
     * @param previewUrl    预览图 URL
     * @param fileUrl       下载 URL（PENDING_MANUAL 任务为 null）
     * @param filename      文件名
     * @param targetPath    目标路径
     * @param referer       Referer 头
     * @param status        初始状态（PENDING 或 PENDING_MANUAL）
     * @param remark        备注（PENDING_MANUAL 任务用）
     */
    private DownloadTaskResource buildWorkshopResource(String taskId, Long instanceId, String workshopId,
                                                       String workshopTitle, String previewUrl, String fileUrl,
                                                       String filename, String targetPath, String referer,
                                                       String status, String remark) {
        DownloadTaskResource resource = new DownloadTaskResource();
        resource.setName(taskId);
        resource.setStatus(status);
        DownloadTaskSpec spec = new DownloadTaskSpec();
        spec.setTaskId(taskId);
        spec.setInstanceId(instanceId);
        spec.setTaskType("WORKSHOP");
        spec.setTaskUrl(fileUrl);
        spec.setReferer(referer);
        spec.setFilename(filename);
        spec.setTargetPath(targetPath);
        spec.setWorkshopId(workshopId);
        spec.setWorkshopTitle(workshopTitle);
        spec.setPreviewUrl(previewUrl);
        spec.setTaskStatus(status);
        spec.setRemark(remark);
        spec.setProgress(0.0);
        spec.setDownloadedSize(0L);
        spec.setFileSize(0L);
        spec.setDownloadSpeed(0.0);
        spec.setRetryCount(0);
        spec.setMaxRetry(3);
        spec.setIsDeleted(false);
        spec.setStartTime(LocalDateTime.now().format(TIME_FORMATTER));
        resource.setSpec(spec);
        return resource;
    }

    /**
     * VPK magic 检测：读前 4 字节判断是否为 0x34 0x12 0xaa 0x55。
     */
    private boolean isVpkFile(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        byte[] header = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            int read = in.read(header);
            if (read < 4) {
                return false;
            }
            for (int i = 0; i < 4; i++) {
                if (header[i] != VPK_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            log.warn("读取文件头失败 file={}, err={}", file.getAbsolutePath(), e.getMessage());
            return false;
        }
    }

    /**
     * 停止速度定时器。
     */
    private void stopSpeedTimer(DownloadTaskRuntime runtime) {
        ScheduledFuture<?> timer = runtime.speedTimer;
        if (timer != null) {
            try {
                timer.cancel(false);
            } catch (Exception ignored) {
            }
            runtime.speedTimer = null;
        }
    }

    /**
     * 判断状态是否为终态。
     */
    private boolean isTerminal(String status) {
        return STATUS_COMPLETED.equals(status)
                || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }

    /**
     * 拼接路径（base + relative，使用正斜杠）。
     */
    private String joinPath(String base, String relative) {
        if (base == null) {
            return relative;
        }
        if (relative == null) {
            return base;
        }
        String b = base.endsWith("/") ? base : base + "/";
        return b + relative;
    }

    /**
     * Resource → VO 转换。
     */
    private DownloadTaskVO toVO(DownloadTaskResource resource) {
        DownloadTaskVO vo = new DownloadTaskVO();
        DownloadTaskSpec spec = resource.getSpec();
        if (spec == null) {
            return vo;
        }
        vo.setTaskId(spec.getTaskId());
        vo.setInstanceId(spec.getInstanceId());
        vo.setTaskType(spec.getTaskType());
        vo.setTaskUrl(spec.getTaskUrl());
        vo.setFilename(spec.getFilename());
        vo.setFileSize(spec.getFileSize());
        vo.setDownloadedSize(spec.getDownloadedSize());
        vo.setProgress(spec.getProgress());
        vo.setDownloadSpeed(spec.getDownloadSpeed());
        vo.setStatus(spec.getTaskStatus());
        vo.setErrorMessage(spec.getErrorMessage());
        vo.setTargetPath(spec.getTargetPath());
        vo.setWorkshopId(spec.getWorkshopId());
        vo.setWorkshopTitle(spec.getWorkshopTitle());
        vo.setPreviewUrl(spec.getPreviewUrl());
        vo.setStartTime(spec.getStartTime());
        vo.setCompleteTime(spec.getCompleteTime());
        return vo;
    }

    /**
     * 将 VO 字段复制到 spec（用于 DB 同步）。
     */
    private void copyVoToSpec(DownloadTaskVO vo, DownloadTaskSpec spec) {
        if (spec == null) return;
        spec.setTaskId(vo.getTaskId());
        spec.setInstanceId(vo.getInstanceId());
        spec.setTaskType(vo.getTaskType());
        spec.setTaskUrl(vo.getTaskUrl());
        spec.setFilename(vo.getFilename());
        spec.setFileSize(vo.getFileSize());
        spec.setDownloadedSize(vo.getDownloadedSize());
        spec.setProgress(vo.getProgress());
        spec.setDownloadSpeed(vo.getDownloadSpeed());
        spec.setTaskStatus(vo.getStatus());
        spec.setErrorMessage(vo.getErrorMessage());
        spec.setTargetPath(vo.getTargetPath());
        spec.setWorkshopId(vo.getWorkshopId());
        spec.setWorkshopTitle(vo.getWorkshopTitle());
        spec.setPreviewUrl(vo.getPreviewUrl());
        spec.setStartTime(vo.getStartTime());
        spec.setCompleteTime(vo.getCompleteTime());
    }

    /**
     * ISO 时间字符串 → 毫秒时间戳。
     */
    private Long parseTimeToMillis(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(time, TIME_FORMATTER);
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 序列化 spec 为 JSON（调试用）。
     */
    private String specToJson(DownloadTaskSpec spec) {
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException e) {
            return "<serialization error: " + e.getMessage() + ">";
        }
    }

    // ===== 内部类 =====

    /**
     * 下载任务运行时：保存内存快照与异步控制结构。
     */
    static class DownloadTaskRuntime {
        /** 内存快照（线程安全更新） */
        final DownloadTaskVO vo;
        /** 异步任务 Future */
        volatile CompletableFuture<Void> future;
        /** 取消标志（volatile 保证可见性） */
        volatile boolean cancelled;
        /** 速度计算用：上次已下载字节数 */
        volatile long lastSecondBytes;
        /** 速度计算用：上次速度更新时间戳 */
        volatile long lastSpeedUpdate;
        /** 速度定时器 */
        volatile ScheduledFuture<?> speedTimer;
        /** 对应的 DB 资源（含 version 用于乐观锁） */
        volatile DownloadTaskResource resource;

        DownloadTaskRuntime(DownloadTaskVO vo, DownloadTaskResource resource) {
            this.vo = vo;
            this.resource = resource;
        }
    }
}
