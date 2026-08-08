package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.PluginStoreDownloadDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GitHubApiClient;
import com.gameplatform.plugin.l4d2.util.GitHubApiClient.TreeEntry;
import com.gameplatform.plugin.l4d2.vo.PluginStoreDetailVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreDownloadTaskVO;
import com.gameplatform.plugin.l4d2.vo.PluginStoreItemVO;
import com.gameplatform.plugin.l4d2.vo.PluginMeta;
import com.gameplatform.plugin.service.InstanceFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * L4D2 插件商店服务：从 GitHub 仓库浏览、检索、下载插件。
 *
 * <p>仓库结构约定：每个插件位于仓库根的子目录中，需包含 {@code README.md} 与 {@code plugin.zip}。
 * {@code plugin.zip} 通过 Git LFS 存储，下载时需先经 LFS BatchAPI 解析真实下载 URL。
 *
 * <p>列表缓存 10 分钟（可通过 {@code plugin.l4d2.plugin-store.cache-ttl-ms} 调整）；
 * 下载任务用内存 Map 跟踪，最多 3 个并发下载。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginStoreService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DOWNLOADING = "DOWNLOADING";
    public static final String STATUS_INSTALLING = "INSTALLING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private static final String README_FILE = "README.md";
    private static final String DEFAULT_CATEGORY = "plugin";
    /**
     * 仓库插件目录前缀（对齐 l4d2-server-next 仓库约定：plugins/{pluginName}/...）
     */
    private static final String PLUGINS_DIR_PREFIX = "plugins/";

    private final GitHubApiClient gitHubApiClient;
    private final ExternalHttpClient httpClient;
    private final PluginInstallService pluginInstallService;
    private final PluginMetaService pluginMetaService;
    private final L4D2Config config;
    private final L4D2PathResolver pathResolver;
    private final InstanceFileService instanceFileService;

    /** 商店列表缓存（10 分钟） */
    private volatile List<PluginStoreItemVO> cachedItems;
    private volatile long cachedTimestamp;

    /** Tree 缓存（与 items 缓存同步过期，避免 detail/readme 重复请求 GitHub API） */
    private volatile List<TreeEntry> cachedTree;
    private volatile long cachedTreeTimestamp;

    /** 下载任务：taskId → task */
    private final Map<String, PluginStoreDownloadTaskVO> tasks = new ConcurrentHashMap<>();

    /** 限制 3 个并发下载（任务级） */
    private final Semaphore downloadSemaphore = new Semaphore(3);

    /** 文件级并发下载线程池（单个任务内多文件并发，对齐 l4d2-server-next worker=min(files,3)） */
    private final java.util.concurrent.ExecutorService downloadExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(1, Runtime.getRuntime().availableProcessors()));

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        downloadExecutor.shutdownNow();
    }

    /**
     * 商店列表（缓存 10 分钟）。
     *
     * @param keyword  关键词（匹配 pluginId / name / description），可为空
     * @param category 分类，可为空
     * @return 过滤后的插件列表
     */
    public List<PluginStoreItemVO> list(String keyword, String category) {
        List<PluginStoreItemVO> items = getCachedItems();
        return items.stream()
                .filter(item -> matchesKeyword(item, keyword))
                .filter(item -> matchesCategory(item, category))
                .toList();
    }

    /**
     * 商店详情（含 README Markdown 与文件列表）。
     *
     * @param pluginId 插件 ID（目录名）
     */
    public PluginStoreDetailVO detail(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "pluginId 不能为空");
        }
        PluginStoreItemVO item = findItem(pluginId);

        List<TreeEntry> tree = getTreeForRead();
        if (tree == null || tree.isEmpty()) {
            throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "GitHub 仓库目录树为空");
        }

        // 仓库结构：plugins/{pluginId}/...
        String pluginPrefix = PLUGINS_DIR_PREFIX + pluginId + "/";
        String readmePath = pluginPrefix + README_FILE;
        TreeEntry readmeEntry = tree.stream()
                .filter(e -> readmePath.equals(e.path()))
                .findFirst()
                .orElse(null);

        PluginStoreDetailVO vo = new PluginStoreDetailVO();
        vo.setPluginId(pluginId);
        vo.setName(item.getName());
        vo.setDescription(item.getDescription());
        vo.setCategory(item.getCategory());
        vo.setSize(item.getSize());
        vo.setUpdatedAt(item.getUpdatedAt());
        vo.setReadme(fetchReadme(readmeEntry));

        List<PluginStoreDetailVO.FileEntry> fileList = new ArrayList<>();
        for (TreeEntry e : tree) {
            if (e.path() == null || !e.path().startsWith(pluginPrefix)) {
                continue;
            }
            if (!"blob".equals(e.type())) {
                continue;
            }
            PluginStoreDetailVO.FileEntry fe = new PluginStoreDetailVO.FileEntry();
            fe.setPath(e.path());
            fe.setSize(e.size());
            fileList.add(fe);
        }
        vo.setFileList(fileList);
        return vo;
    }

    /**
     * README 内容（Markdown 原文）。
     */
    public String readme(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "pluginId 不能为空");
        }
        List<TreeEntry> tree = getTreeForRead();
        if (tree == null || tree.isEmpty()) {
            throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API, "GitHub 仓库目录树为空");
        }
        String readmePath = PLUGINS_DIR_PREFIX + pluginId + "/" + README_FILE;
        TreeEntry readmeEntry = tree.stream()
                .filter(e -> readmePath.equals(e.path()))
                .findFirst()
                .orElse(null);
        return fetchReadme(readmeEntry);
    }

    /**
     * 下载插件到指定实例（异步执行）。
     *
     * <p>流程（对齐 l4d2-server-next StartStorePluginDownload 临时目录+原子重命名模式）：
     * <ol>
     *   <li>创建 DownloadTaskVO（status=PENDING）</li>
     *   <li>异步：获取 plugin.zip SHA → 读取 blob 判断是否 LFS 指针 →
     *       调用 LFS BatchAPI 获取真实 URL → 下载到本地临时文件（带 LFS 大小校验）</li>
     *   <li>解压到远程临时目录 {@code .download_temp/{taskId}/}（不污染正式库）</li>
     *   <li>原子移动到正式库 {@code plugins_store/{pluginName}/}（失败时自动清理临时目录）</li>
     *   <li>更新任务状态 + 标记 source=store</li>
     * </ol>
     *
     * @return taskId
     */
    public String download(PluginStoreDownloadDTO dto) {
        if (dto == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "下载请求不能为空");
        }
        if (dto.getInstanceId() == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "instanceId 不能为空");
        }
        if (dto.getPluginId() == null || dto.getPluginId().isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "pluginId 不能为空");
        }

        // 任务去重：相同 instanceId+pluginId 且未结束的任务直接返回已有 taskId
        for (PluginStoreDownloadTaskVO t : tasks.values()) {
            if (!isTerminal(t)
                    && dto.getInstanceId().equals(t.getInstanceId())
                    && dto.getPluginId().equals(t.getPluginId())) {
                log.info("插件下载任务已存在，返回已有 taskId: {}", t.getTaskId());
                return t.getTaskId();
            }
        }

        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        PluginStoreDownloadTaskVO task = new PluginStoreDownloadTaskVO();
        task.setTaskId(taskId);
        task.setInstanceId(dto.getInstanceId());
        task.setPluginId(dto.getPluginId());
        task.setStatus(STATUS_PENDING);
        task.setProgress(0);
        task.setFilename(dto.getPluginId() + "-store-download");
        task.setStartedAt(LocalDateTime.now());
        tasks.put(taskId, task);

        CompletableFuture.runAsync(() -> runDownload(task, dto));
        return taskId;
    }

    /**
     * 获取下载任务列表。
     *
     * @param instanceId 实例 ID，传 null 返回所有任务
     */
    public List<PluginStoreDownloadTaskVO> listTasks(Long instanceId) {
        return tasks.values().stream()
                .filter(t -> instanceId == null || instanceId.equals(t.getInstanceId()))
                .toList();
    }

    /**
     * 取消下载任务。
     */
    public void cancel(String taskId) {
        PluginStoreDownloadTaskVO task = tasks.get(taskId);
        if (task == null) {
            return;
        }
        if (!isTerminal(task)) {
            task.setStatus(STATUS_CANCELLED);
            task.setFinishedAt(LocalDateTime.now());
            log.info("插件下载任务已取消: taskId={}", taskId);
        }
    }

    // ========== 内部实现 ==========

    private List<PluginStoreItemVO> getCachedItems() {
        long ttl = config.getPluginStore().getCacheTtlMs();
        long now = System.currentTimeMillis();
        if (cachedItems != null && (now - cachedTimestamp) < ttl) {
            return cachedItems;
        }
        List<PluginStoreItemVO> fresh = fetchItems();
        cachedItems = fresh;
        cachedTimestamp = now;
        return fresh;
    }

    /**
     * 获取 tree 缓存（TTL 与 items 缓存一致）。
     *
     * <p>对齐 l4d2-server-next treeCache：detail/readme/fetchItems 共享同一份 tree，
     * 避免每次调用都请求 GitHub Trees API 浪费配额。
     *
     * @return tree 条目列表（缓存未命中时返回空列表，不抛异常）
     */
    private List<TreeEntry> getCachedTree() {
        long ttl = config.getPluginStore().getCacheTtlMs();
        long now = System.currentTimeMillis();
        if (cachedTree != null && (now - cachedTreeTimestamp) < ttl) {
            return cachedTree;
        }
        List<TreeEntry> fresh = gitHubApiClient.getTree();
        if (fresh != null && !fresh.isEmpty()) {
            cachedTree = fresh;
            cachedTreeTimestamp = now;
            return fresh;
        }
        return fresh != null ? fresh : List.of();
    }

    /**
     * 强制刷新 tree 缓存（供 detail/readme 在缓存未命中时使用）。
     *
     * <p>缓存为空时直接请求 GitHub API（不写回缓存，避免脏数据）。
     */
    private List<TreeEntry> getTreeForRead() {
        List<TreeEntry> cached = getCachedTree();
        if (cached.isEmpty()) {
            // 缓存为空时直接请求
            List<TreeEntry> fresh = gitHubApiClient.getTree();
            return fresh != null ? fresh : List.of();
        }
        return cached;
    }

    private List<PluginStoreItemVO> fetchItems() {
        List<TreeEntry> tree = getCachedTree();
        if (tree == null || tree.isEmpty()) {
            return List.of();
        }
        // 对齐 l4d2-server-next 仓库约定：plugins/{pluginName}/...
        // 按 pluginName 分组，统计每个插件的文件数和总大小
        Map<String, List<TreeEntry>> byPlugin = new HashMap<>();
        for (TreeEntry e : tree) {
            String path = e.path();
            if (path == null || !path.startsWith(PLUGINS_DIR_PREFIX)) {
                continue;
            }
            if (!"blob".equals(e.type())) {
                continue;
            }
            // 路径格式：plugins/{pluginName}/...
            String rest = path.substring(PLUGINS_DIR_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            String pluginName = rest.substring(0, slash);
            byPlugin.computeIfAbsent(pluginName, k -> new ArrayList<>()).add(e);
        }

        List<PluginStoreItemVO> items = new ArrayList<>();
        for (Map.Entry<String, List<TreeEntry>> entry : byPlugin.entrySet()) {
            String pluginId = entry.getKey();
            List<TreeEntry> files = entry.getValue();
            long totalSize = files.stream().mapToLong(TreeEntry::size).sum();
            PluginStoreItemVO vo = new PluginStoreItemVO();
            vo.setPluginId(pluginId);
            vo.setName(pluginId);
            vo.setDescription("");
            vo.setCategory(DEFAULT_CATEGORY);
            vo.setSize(totalSize);
            vo.setFileCount(files.size());
            vo.setUpdatedAt("");
            items.add(vo);
        }
        return items;
    }

    private PluginStoreItemVO findItem(String pluginId) {
        return getCachedItems().stream()
                .filter(i -> pluginId.equals(i.getPluginId()))
                .findFirst()
                .orElseThrow(() -> new L4D2PluginException(
                        L4D2PluginException.BUSINESS, "插件不存在: " + pluginId));
    }

    private String fetchReadme(TreeEntry readmeEntry) {
        if (readmeEntry == null) {
            return "";
        }
        try {
            String content = gitHubApiClient.getBlobContent(readmeEntry.sha());
            return content != null ? content : "";
        } catch (Exception e) {
            log.warn("获取 README 失败 sha={}, err={}", readmeEntry.sha(), e.getMessage());
            return "";
        }
    }

    /**
     * 下载任务主流程：逐文件并发下载（对齐 l4d2-server-next downloadFiles）。
     *
     * <p>仓库结构为 {@code plugins/{pluginName}/...} 多文件目录，每个文件可能：
     * <ul>
     *   <li>普通文件：通过 raw.githubusercontent.com 直接下载</li>
     *   <li>LFS 文件：检测 LFS 指针 → LFS BatchAPI 获取真实 URL → 下载并校验大小</li>
     * </ul>
     *
     * <p>流程：
     * <ol>
     *   <li>过滤 tree 中 {@code plugins/{pluginId}/} 前缀的所有 blob</li>
     *   <li>创建远程临时目录 {@code .download_temp/{taskId}/{pluginId}/}</li>
     *   <li>并发下载每个文件（Semaphore=3），上传到远程临时目录</li>
     *   <li>原子移动到正式库 {@code plugins_store/{pluginId}/}</li>
     *   <li>写 plugin.yaml（source=store）</li>
     * </ol>
     */
    private void runDownload(PluginStoreDownloadTaskVO task, PluginStoreDownloadDTO dto) {
        try {
            if (isCancelled(task)) {
                return;
            }
            downloadSemaphore.acquire();
            try {
                if (isCancelled(task)) {
                    return;
                }
                task.setStatus(STATUS_DOWNLOADING);
                task.setMessage("获取插件文件列表");

                String pluginId = dto.getPluginId();
                String pluginPrefix = PLUGINS_DIR_PREFIX + pluginId + "/";
                List<TreeEntry> allFiles = getTreeForRead().stream()
                        .filter(e -> e.path() != null
                                && e.path().startsWith(pluginPrefix)
                                && "blob".equals(e.type()))
                        .toList();

                if (allFiles.isEmpty()) {
                    throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                            "插件目录下无文件: " + pluginId);
                }

                // 统计总大小
                long totalBytes = allFiles.stream().mapToLong(TreeEntry::size).sum();
                task.setTotalBytes(totalBytes);
                task.setMessage("下载中 (" + allFiles.size() + " 个文件)");

                // 创建远程临时目录：.download_temp/{taskId}/{pluginId}/
                String tempDirRel = pathResolver.getDownloadTaskTempPath(task.getTaskId());
                instanceFileService.createDirectory(dto.getInstanceId(), tempDirRel);
                String pluginTempDir = tempDirRel + "/" + pluginId;
                instanceFileService.createDirectory(dto.getInstanceId(), pluginTempDir);

                // 逐文件下载并上传（并发，Semaphore=3 限制）
                Semaphore fileSemaphore = new Semaphore(3);
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                java.util.concurrent.atomic.AtomicLong downloadedBytes = new java.util.concurrent.atomic.AtomicLong(0);
                java.util.concurrent.atomic.AtomicInteger failedCount = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicReference<String> firstError = new java.util.concurrent.atomic.AtomicReference<>(null);

                for (TreeEntry fileEntry : allFiles) {
                    if (isCancelled(task)) {
                        break;
                    }
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            fileSemaphore.acquire();
                            try {
                                if (isCancelled(task)) {
                                    return;
                                }
                                downloadAndUploadOneFile(dto.getInstanceId(), pluginId, pluginTempDir, fileEntry);
                                long done = downloadedBytes.addAndGet(fileEntry.size());
                                long total = task.getTotalBytes();
                                if (total > 0) {
                                    int progress = (int) (done * 99 / total);
                                    task.setProgress(progress);
                                }
                            } finally {
                                fileSemaphore.release();
                            }
                        } catch (Exception e) {
                            log.error("下载文件失败 path={}, err={}", fileEntry.path(), e.getMessage());
                            failedCount.incrementAndGet();
                            firstError.compareAndSet(null, e.getMessage());
                        }
                    }, downloadExecutor));
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                if (isCancelled(task)) {
                    return;
                }

                if (failedCount.get() > 0) {
                    throw new L4D2PluginException(L4D2PluginException.NETWORK,
                            "下载失败: " + failedCount.get() + "/" + allFiles.size() + " 个文件失败，首个错误: "
                                    + firstError.get());
                }

                // 原子移动到正式库
                task.setStatus(STATUS_INSTALLING);
                task.setMessage("提交到插件库");
                pluginInstallService.atomicMoveToStore(dto.getInstanceId(), task.getTaskId(), pluginId);

                // 标记 source=store
                try {
                    PluginMeta existing = pluginMetaService.load(dto.getInstanceId(), pluginId);
                    if (existing != null) {
                        existing.setSource("store");
                        pluginMetaService.save(dto.getInstanceId(), existing);
                    }
                } catch (Exception e) {
                    log.warn("标记 source=store 失败 pluginId={}, err={}", pluginId, e.getMessage());
                }

                task.setStatus(STATUS_COMPLETED);
                task.setProgress(100);
                task.setMessage("完成");
                task.setFinishedAt(LocalDateTime.now());
                log.info("插件下载完成: taskId={}, pluginId={}, files={}",
                        task.getTaskId(), pluginId, allFiles.size());
            } finally {
                downloadSemaphore.release();
            }
        } catch (Exception e) {
            log.error("插件下载失败: taskId={}, pluginId={}", task.getTaskId(), dto.getPluginId(), e);
            // 失败时清理远程临时目录
            try {
                String tempDir = pathResolver.getDownloadTaskTempPath(task.getTaskId());
                instanceFileService.deleteDirectory(dto.getInstanceId(), tempDir, true);
            } catch (Exception cleanupErr) {
                log.warn("清理临时目录失败: taskId={}, err={}", task.getTaskId(), cleanupErr.getMessage());
            }
            if (!isCancelled(task)) {
                task.setStatus(STATUS_FAILED);
                task.setError(e.getMessage());
                task.setMessage("失败: " + e.getMessage());
                task.setFinishedAt(LocalDateTime.now());
            }
        }
    }

    /**
     * 下载单个文件并上传到远程临时目录。
     *
     * <p>处理 LFS 指针文件：检测 → LFS BatchAPI → 下载真实内容。
     * 非 LFS 文件：通过 raw.githubusercontent.com 直接下载。
     *
     * @param instanceId 实例 ID
     * @param pluginId 插件 ID
     * @param pluginTempDir 远程临时目录（.download_temp/{taskId}/{pluginId}）
     * @param fileEntry tree 条目
     */
    private void downloadAndUploadOneFile(Long instanceId, String pluginId,
                                           String pluginTempDir, TreeEntry fileEntry) {
        String filePath = fileEntry.path();
        // 计算相对路径：plugins/{pluginId}/left4dead2/... → left4dead2/...
        String pluginPrefix = PLUGINS_DIR_PREFIX + pluginId + "/";
        String relPath = filePath.substring(pluginPrefix.length());

        // 远程目标路径：.download_temp/{taskId}/{pluginId}/{relPath}
        String remotePath = pluginTempDir + "/" + relPath;

        // 确保远程父目录存在
        int lastSlash = remotePath.lastIndexOf('/');
        if (lastSlash > 0) {
            String parentDir = remotePath.substring(0, lastSlash);
            try {
                instanceFileService.createDirectory(instanceId, parentDir);
            } catch (Exception ignored) {
                // 目录可能已存在，忽略
            }
        }

        // 先尝试通过 blob API 获取内容，检测是否为 LFS 指针
        String blob = gitHubApiClient.getBlobContent(fileEntry.sha());
        if (blob == null) {
            // blob API 失败，直接用 raw URL 下载
            File tempFile = downloadViaRawUrl(filePath);
            try {
                instanceFileService.uploadLocalFile(instanceId, remotePath, tempFile.getAbsolutePath());
            } finally {
                tempFile.delete();
            }
            return;
        }

        // 检测是否为 LFS 指针
        GitHubApiClient.LfsPointer pointer = gitHubApiClient.parseLfsPointer(blob);
        if (pointer == null) {
            // 非 LFS 文件：如果内容是文本且大小匹配，直接上传内容
            // 但为避免大文件 base64 解码内存问题，对大于 1MB 的文件用 raw URL 下载
            if (fileEntry.size() > 1024 * 1024) {
                File tempFile = downloadViaRawUrl(filePath);
                try {
                    instanceFileService.uploadLocalFile(instanceId, remotePath, tempFile.getAbsolutePath());
                } finally {
                    tempFile.delete();
                }
            } else {
                // 小文件：直接将内容转为字节数组上传
                byte[] data = blob.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                File tempFile = writeTempFile(data, pluginId);
                try {
                    instanceFileService.uploadLocalFile(instanceId, remotePath, tempFile.getAbsolutePath());
                } finally {
                    tempFile.delete();
                }
            }
            return;
        }

        // LFS 文件：通过 LFS BatchAPI 获取真实 URL 并下载
        Map<String, String> urls = gitHubApiClient.batchLfsObjects(List.of(pointer.oid()));
        String downloadUrl = urls.get(pointer.oid());
        if (downloadUrl == null) {
            throw new L4D2PluginException(L4D2PluginException.EXTERNAL_API,
                    "LFS 对象不存在或无可下载链接: " + pointer.oid());
        }

        File tempFile = httpClient.downloadWithRetry(
                downloadUrl,
                pluginId + "-" + new File(relPath).getName(),
                null, null, () -> false, 3);

        // LFS 大小校验
        if (pointer.size() > 0 && tempFile.length() != pointer.size()) {
            tempFile.delete();
            throw new L4D2PluginException(L4D2PluginException.NETWORK,
                    "LFS 大小校验失败: 期望=" + pointer.size() + ", 实际=" + tempFile.length());
        }

        try {
            instanceFileService.uploadLocalFile(instanceId, remotePath, tempFile.getAbsolutePath());
        } finally {
            tempFile.delete();
        }
    }

    /**
     * 通过 raw.githubusercontent.com 下载文件到本地临时文件。
     */
    private File downloadViaRawUrl(String filePath) {
        String url = gitHubApiClient.getRawDownloadUrl(filePath);
        String filename = new File(filePath).getName();
        return httpClient.downloadWithRetry(url, filename, null, null, () -> false, 3);
    }

    /**
     * 将字节数组写入临时文件。
     */
    private File writeTempFile(byte[] data, String prefix) {
        try {
            File tempFile = File.createTempFile("l4d2-store-" + sanitizeFilename(prefix) + "-", ".tmp");
            java.nio.file.Files.write(tempFile.toPath(), data);
            return tempFile;
        } catch (IOException e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "写入临时文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 清理文件名中的特殊字符（用于临时文件前缀）。
     */
    private String sanitizeFilename(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private boolean isCancelled(PluginStoreDownloadTaskVO task) {
        return STATUS_CANCELLED.equals(task.getStatus());
    }

    private boolean isTerminal(PluginStoreDownloadTaskVO task) {
        String s = task.getStatus();
        return STATUS_COMPLETED.equals(s)
                || STATUS_FAILED.equals(s)
                || STATUS_CANCELLED.equals(s);
    }

    private boolean matchesKeyword(PluginStoreItemVO item, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.toLowerCase();
        return Stream.of(item.getPluginId(), item.getName(), item.getDescription())
                .anyMatch(v -> v != null && v.toLowerCase().contains(kw));
    }

    private boolean matchesCategory(PluginStoreItemVO item, String category) {
        if (category == null || category.isBlank()) {
            return true;
        }
        return category.equals(item.getCategory());
    }
}
