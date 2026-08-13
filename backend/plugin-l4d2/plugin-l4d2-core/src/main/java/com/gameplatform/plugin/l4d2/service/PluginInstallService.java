package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.ArchiveExtractUtil;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.l4d2.util.ZipSlipGuard;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.l4d2.vo.PluginListVO;
import com.gameplatform.plugin.l4d2.vo.PluginMeta;
import com.gameplatform.plugin.l4d2.vo.PluginReadmeVO;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * L4D2 插件安装/启用/禁用/删除服务。
 *
 * <p>负责插件二进制（.smx）的远程部署、RCON 加载/卸载，以及共享文件（cfg/translations）的引用登记。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
public class PluginInstallService {

    private static final String SMX_SUFFIX = ".smx";
    private static final String LEFT_4_DEAD_2 = "left4dead2";

    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final FileRefsService fileRefsService;
    private final L4D2PathResolver pathResolver;
    private final Charset gbk = GbkCodecUtil.gbk();
    private final PluginMetaService pluginMetaService;
    private final EnabledPluginsService enabledPluginsService;

    // 用于复制并发的信号量（对齐 l4d2-server-next ants 协程池，容量 3）
    private final Semaphore copySemaphore = new Semaphore(3);
    private final ExecutorService copyExecutor = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors()));

    public PluginInstallService(InstanceQueryService instanceQueryService,
                                InstanceFileService instanceFileService,
                                FileRefsService fileRefsService,
                                L4D2PathResolver pathResolver,
                                PluginMetaService pluginMetaService,
                                EnabledPluginsService enabledPluginsService) {
        this.instanceQueryService = instanceQueryService;
        this.instanceFileService = instanceFileService;
        this.fileRefsService = fileRefsService;
        this.pathResolver = pathResolver;
        this.pluginMetaService = pluginMetaService;
        this.enabledPluginsService = enabledPluginsService;
    }

    @PreDestroy
    public void shutdown() {
        copyExecutor.shutdownNow();
    }

    /**
     * 上传安装：保存到临时文件 → 调用 installFromLocalFile。
     */
    public PluginListVO installFromUpload(Long instanceId, MultipartFile file) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "上传文件名为空");
        }
        try {
            File tempFile = File.createTempFile("l4d2-upload-", "-" + sanitizeFilename(originalName));
            file.transferTo(tempFile);
            try {
                installFromLocalFile(instanceId, tempFile);
            } finally {
                if (!tempFile.delete()) {
                    tempFile.deleteOnExit();
                }
            }
            PluginListVO vo = new PluginListVO();
            String baseName = stripExtension(originalName);
            vo.setName(baseName);
            vo.setStatus("enabled");
            vo.setSource("upload");
            vo.setDescription("用户上传的插件");
            vo.setCreateTime(LocalDateTime.now());
            vo.setUpdateTime(LocalDateTime.now());
            return vo;
        } catch (L4D2PluginException e) {
            throw e;
        } catch (Exception e) {
            log.error("上传安装插件失败 instanceId={}, fileName={}", instanceId, originalName, e);
            throw new L4D2PluginException(L4D2PluginException.FILE, "上传安装失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从本地文件安装（供 PluginStoreService 调用）。
     *
     * <p>检测 VPK magic（地图）vs ZIP/RAR/7z（插件）：
     * <ul>
     *   <li>VPK → 复制到 addons/</li>
     *   <li>ZIP/7z → 解压 → 归档到 plugins_store/&lt;name&gt;/left4dead2/...</li>
     *   <li>.smx → 包装为 plugins_store/&lt;name&gt;/left4dead2/addons/sourcemod/plugins/&lt;name&gt;.smx</li>
     * </ul>
     */
    public void installFromLocalFile(Long instanceId, File localFile) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在");
        }

        byte[] header = readFileHeader(localFile, 4);
        if (ArchiveExtractUtil.isVpkFile(header)) {
            // VPK 地图：仍然复制到 addons/（与插件分离）
            String addonsPath = pathResolver.getAddonsPath();
            String targetPath = addonsPath + "/" + localFile.getName();
            instanceFileService.uploadLocalFile(instanceId, targetPath, localFile.getAbsolutePath());
            log.info("VPK 地图已上传: instanceId={}, path={}", instanceId, targetPath);
            return;
        }

        String lowerName = localFile.getName().toLowerCase();
        if (lowerName.endsWith(SMX_SUFFIX)) {
            // 单 .smx 文件：包装为插件目录 plugins_store/<name>/left4dead2/addons/sourcemod/plugins/<name>.smx
            String pluginName = stripExtension(localFile.getName());
            installSingleSmxToStore(instanceId, pluginName, localFile);
            return;
        }

        // ZIP/7z：解压到 plugins_store/<pluginName>/left4dead2/...
        File tempDir = createTempDir("l4d2-extract-");
        try {
            extractArchive(localFile, tempDir);
            installZipToStore(instanceId, tempDir);
        } finally {
            deleteRecursive(tempDir);
        }
    }

    /**
     * 单 .smx 包装为最小插件目录：plugins_store/<pluginName>/left4dead2/addons/sourcemod/plugins/<pluginName>.smx
     * 并写 plugin.yaml（source=upload, fileList=[addons/sourcemod/plugins/<pluginName>.smx]）。
     */
    private void installSingleSmxToStore(Long instanceId, String pluginName, File smxFile) {
        String storePath = pathResolver.getPluginLeft4Dead2Path(pluginName)
                + "/addons/sourcemod/plugins/" + pluginName + SMX_SUFFIX;
        instanceFileService.uploadLocalFile(instanceId, storePath, smxFile.getAbsolutePath());

        PluginMeta meta = new PluginMeta();
        meta.setName(pluginName);
        meta.setSource("upload");
        meta.setFileList(List.of("addons/sourcemod/plugins/" + pluginName + SMX_SUFFIX));
        meta.setConfigFiles(List.of());
        pluginMetaService.save(instanceId, meta);
        log.info("单 SMX 已安装到 plugins_store: instanceId={}, plugin={}", instanceId, pluginName);
    }

    /**
     * ZIP 解压后归档到 plugins_store/<pluginName>/left4dead2/...
     * 1. 寻找 left4dead2/ 子目录作为基准
     * 2. 检测插件名（解压根目录名，或第一个 .smx 文件名）
     * 3. 上传所有文件到 plugins_store/<pluginName>/left4dead2/...
     * 4. 写 plugin.yaml
     */
    private void installZipToStore(Long instanceId, File extractRoot) {
        List<File> pluginRoots = findPluginRoots(extractRoot);

        if (pluginRoots.isEmpty()) {
            // 单插件 zip：直接用解压根作为 left4dead2
            String pluginName = derivePluginNameFromExtract(extractRoot);
            installSinglePluginArchive(instanceId, pluginName, extractRoot);
            return;
        }

        for (File pluginRoot : pluginRoots) {
            String pluginName = pluginRoot.getParentFile().getName();
            File left4dead2Dir = pluginRoot; // 即 left4dead2 目录本身
            installSinglePluginArchive(instanceId, pluginName, left4dead2Dir);
        }
    }

    /**
     * 在解压根中查找 left4dead2/ 子目录（最多两层深度）。
     */
    private List<File> findPluginRoots(File extractRoot) {
        List<File> roots = new ArrayList<>();
        File[] top = extractRoot.listFiles();
        if (top == null) return roots;

        for (File child : top) {
            if (!child.isDirectory()) continue;
            if (LEFT_4_DEAD_2.equalsIgnoreCase(child.getName())) {
                roots.add(child);
            } else {
                File[] sub = child.listFiles();
                if (sub != null) {
                    for (File subChild : sub) {
                        if (subChild.isDirectory() && LEFT_4_DEAD_2.equalsIgnoreCase(subChild.getName())) {
                            roots.add(subChild);
                        }
                    }
                }
            }
        }
        return roots;
    }

    private String derivePluginNameFromExtract(File extractRoot) {
        try (Stream<Path> walk = Files.walk(extractRoot.toPath())) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase().endsWith(SMX_SUFFIX))
                    .map(this::stripExtension)
                    .findFirst()
                    .orElse("uploaded-" + System.currentTimeMillis());
        } catch (IOException e) {
            return "uploaded-" + System.currentTimeMillis();
        }
    }

    private void installSinglePluginArchive(Long instanceId, String pluginName, File left4dead2Dir) {
        String storeLeft4Dead2 = pathResolver.getPluginLeft4Dead2Path(pluginName);

        List<String> fileList = new ArrayList<>();
        List<String> configFiles = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(left4dead2Dir.toPath())) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path filePath : files) {
                String relative = left4dead2Dir.toPath().relativize(filePath).toString().replace('\\', '/');
                if (relative.isEmpty()) continue;

                if (relative.contains("..") || relative.startsWith("/")) {
                    log.warn("Zip Slip 检测：跳过越界路径 {}", relative);
                    continue;
                }
                if (ZipSlipGuard.isMacOSJunk(relative)) {
                    continue;
                }

                String remotePath = storeLeft4Dead2 + "/" + relative;
                try {
                    instanceFileService.uploadLocalFile(instanceId, remotePath, filePath.toString());
                    fileList.add(relative);
                    if (relative.toLowerCase().endsWith(".cfg")
                            && relative.toLowerCase().startsWith("cfg/sourcemod/")) {
                        configFiles.add(relative);
                    }
                } catch (Exception e) {
                    log.warn("上传插件文件失败 relPath={}, err={}", relative, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "遍历插件归档失败: " + e.getMessage(), e);
        }

        PluginMeta meta = new PluginMeta();
        meta.setName(pluginName);
        meta.setSource("upload");
        meta.setFileList(fileList);
        meta.setConfigFiles(configFiles);
        pluginMetaService.save(instanceId, meta);
        log.info("ZIP 插件已归档到 plugins_store: instanceId={}, plugin={}, files={}",
                instanceId, pluginName, fileList.size());
    }

    /**
     * 批量启用插件（无 RCON）：
     * <ol>
     *   <li>复制库文件到游戏目录（每插件一次 cp -r）</li>
     *   <li>登记已启用 + 引用计数</li>
     *   <li>插件在游戏服务器重启后生效（不再通过 RCON 运行时加载）</li>
     * </ol>
     *
     * <p>单个插件只传一个元素即可；单插件失败不影响其余（错误收集在返回值中）。</p>
     *
     * @param instanceId  实例 ID
     * @param pluginNames 插件名列表
     * @return 失败项列表（"插件名: 原因"），空表示全部成功
     */
    public List<String> enableAndLoadBatch(Long instanceId, List<String> pluginNames) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在");
        }
        List<String> errors = new ArrayList<>();
        for (String pluginName : pluginNames) {
            try {
                enableOne(instanceId, pluginName);
            } catch (Exception e) {
                log.warn("批量启用插件失败: plugin={}, err={}", pluginName, e.getMessage());
                errors.add(pluginName + ": " + e.getMessage());
            }
        }
        return errors;
    }

    /**
     * 启用单个插件（兼容旧签名；无 RCON，重启服务器后生效）。
     */
    public void enableAndLoad(Long instanceId, String pluginName) {
        List<String> errors = enableAndLoadBatch(instanceId, List.of(pluginName));
        if (!errors.isEmpty()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, errors.get(0));
        }
    }

    private void enableOne(Long instanceId, String pluginName) {
        validatePluginName(pluginName);

        // 1. 复制库文件到游戏目录（一次性 cp -r）
        List<String> copiedFiles = copyPluginFilesConcurrently(instanceId, pluginName);

        // 2. 登记已启用 + 引用计数（重启服务器后生效）
        EnabledPlugin enabled = new EnabledPlugin();
        enabled.setName(pluginName);
        enabled.setSource(resolveSourceForEnable(instanceId, pluginName));
        enabled.setEnabledAt(System.currentTimeMillis());
        enabled.setFiles(copiedFiles);
        enabledPluginsService.add(instanceId, enabled);
        fileRefsService.addRefs(instanceId, pluginName, copiedFiles);

        log.info("插件已启用（重启服务器后生效）: instanceId={}, pluginName={}, files={}",
                instanceId, pluginName, copiedFiles.size());
    }

    /**
     * 并发复制库文件到游戏目录（对齐 l4d2-server-next ants 协程池）。
     * 使用 Semaphore(3) 限制并发，AtomicReference 捕获首个错误。
     * 失败时由调用方负责回滚已复制的文件。
     */
    private List<String> copyPluginFilesConcurrently(Long instanceId, String pluginName) {
        PluginMeta meta = pluginMetaService.load(instanceId, pluginName);
        if (meta == null || meta.getFileList() == null || meta.getFileList().isEmpty()) {
            // 历史遗留插件目录（旧版 PlatformPluginInstaller 直接上传未写元数据）：
            // 扫描 plugins_store/<name>/left4dead2/ 重建 fileList 并持久化，避免直接抛 500
            log.warn("插件元数据缺失，尝试扫描目录重建: instanceId={}, plugin={}", instanceId, pluginName);
            meta = rebuildMetaByScanningStore(instanceId, pluginName);
            if (meta == null || meta.getFileList() == null || meta.getFileList().isEmpty()) {
                throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                        "插件元数据缺失或文件列表为空: " + pluginName);
            }
        }

        String storeLeft4Dead2 = pathResolver.getPluginLeft4Dead2Path(pluginName);
        String gameLeft4Dead2 = pathResolver.getGamePath();

        // 一次性递归复制整个 left4dead2 目录（Native 用 cp -r，Docker 用 docker exec cp -r），
        // 避免 682 个文件各自走 download+upload 导致超时。
        instanceFileService.copyDirectory(instanceId, storeLeft4Dead2, gameLeft4Dead2);

        log.info("插件目录已复制: instanceId={}, plugin={}, files={}",
                instanceId, pluginName, meta.getFileList().size());
        return new ArrayList<>(meta.getFileList());
    }

    /**
     * 扫描 plugins_store/&lt;name&gt;/left4dead2/ 递归重建 PluginMeta（fileList + configFiles）。
     *
     * <p>用于历史遗留插件目录（旧版 PlatformPluginInstaller 直接上传未写元数据）的元数据修复。
     * 重建后会持久化到 plugin.yaml，避免后续每次启用都重新扫描。
     *
     * @return 重建后的 PluginMeta；目录不存在或为空返回 null
     */
    private PluginMeta rebuildMetaByScanningStore(Long instanceId, String pluginName) {
        String storeLeft4Dead2 = pathResolver.getPluginLeft4Dead2Path(pluginName);

        List<String> fileList = new ArrayList<>();
        List<String> configFiles = new ArrayList<>();

        // BFS 遍历：每项为 [相对路径, 绝对路径]
        java.util.Deque<String[]> queue = new java.util.ArrayDeque<>();
        queue.add(new String[]{"", storeLeft4Dead2});

        while (!queue.isEmpty()) {
            String[] cur = queue.poll();
            String relDir = cur[0];
            String absDir = cur[1];
            List<FileInfo> children;
            try {
                children = instanceFileService.listFiles(instanceId, absDir);
            } catch (Exception e) {
                log.warn("重建元数据：列出目录失败 path={}, err={}", absDir, e.getMessage());
                continue;
            }
            if (children == null) continue;
            for (FileInfo f : children) {
                String fname = f.getName();
                if (fname == null || fname.isEmpty()) continue;
                String relPath = relDir.isEmpty() ? fname : relDir + "/" + fname;
                if (f.isDirectory()) {
                    queue.add(new String[]{relPath, absDir + "/" + fname});
                } else {
                    if (ZipSlipGuard.isMacOSJunk(relPath)) continue;
                    fileList.add(relPath);
                    if (relPath.toLowerCase().endsWith(".cfg")
                            && relPath.toLowerCase().startsWith("cfg/sourcemod/")) {
                        configFiles.add(relPath);
                    }
                }
            }
        }

        if (fileList.isEmpty()) {
            return null;
        }
        java.util.Collections.sort(fileList);
        java.util.Collections.sort(configFiles);

        PluginMeta meta = new PluginMeta();
        meta.setName(pluginName);
        meta.setSource("panel");
        meta.setFileList(fileList);
        meta.setConfigFiles(configFiles);
        try {
            pluginMetaService.save(instanceId, meta);
            log.info("元数据已重建: instanceId={}, plugin={}, files={}",
                    instanceId, pluginName, fileList.size());
        } catch (Exception e) {
            log.warn("元数据重建后持久化失败 instanceId={}, plugin={}, err={}",
                    instanceId, pluginName, e.getMessage());
        }
        return meta;
    }

    private String resolveSourceForEnable(Long instanceId, String pluginName) {
        PluginMeta meta = pluginMetaService.load(instanceId, pluginName);
        if (meta != null && meta.getSource() != null) {
            return meta.getSource();
        }
        return "panel";
    }

    /**
     * 批量禁用插件（无 RCON）：
     * <ol>
     *   <li>校验插件已启用</li>
     *   <li>fileRefsService.removeRefs 获取归零文件 → 删除游戏目录文件 →
     *       enabledPluginsService.remove</li>
     *   <li>插件在游戏服务器重启后完成卸载（不再通过 RCON 运行时卸载）</li>
     * </ol>
     *
     * <p>单个插件只传一个元素即可；单插件失败不影响其余（错误收集在返回值中）。</p>
     *
     * @return 失败项列表（"插件名: 原因"），空表示全部成功
     */
    public List<String> disableAndUnloadBatch(Long instanceId, List<String> pluginNames) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在");
        }
        List<String> errors = new ArrayList<>();
        for (String pluginName : pluginNames) {
            try {
                disableOne(instanceId, pluginName);
            } catch (Exception e) {
                log.warn("批量禁用插件失败: plugin={}, err={}", pluginName, e.getMessage());
                errors.add(pluginName + ": " + e.getMessage());
            }
        }
        return errors;
    }

    /**
     * 禁用单个插件（兼容旧签名；无 RCON，重启服务器后完成卸载）。
     */
    public void disableAndUnload(Long instanceId, String pluginName) {
        List<String> errors = disableAndUnloadBatch(instanceId, List.of(pluginName));
        if (!errors.isEmpty()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, errors.get(0));
        }
    }

    private void disableOne(Long instanceId, String pluginName) {
        validatePluginName(pluginName);

        if (!enabledPluginsService.isEnabled(instanceId, pluginName)) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "插件未启用，无需禁用: " + pluginName);
        }

        // 1. 移除文件引用，获取归零的共享文件列表
        List<String> zeroedFiles = fileRefsService.removeRefs(instanceId, pluginName);

        // 2. 删除归零的共享文件
        String gameLeft4Dead2 = pathResolver.getGamePath();
        for (String relPath : zeroedFiles) {
            try {
                instanceFileService.deleteFile(instanceId, gameLeft4Dead2 + "/" + relPath);
            } catch (Exception e) {
                log.warn("删除共享文件失败 instanceId={}, path={}, err={}",
                        instanceId, relPath, e.getMessage());
            }
        }

        // 3. 从 enabled_plugins 移除
        enabledPluginsService.remove(instanceId, pluginName);

        log.info("插件已禁用（重启服务器后完成卸载）: instanceId={}, pluginName={}, removedFiles={}",
                instanceId, pluginName, zeroedFiles.size());
    }

    /**
     * 列出已安装插件（扫描 plugins_store 目录，合并 enabled_plugins 状态）。
     */
    public List<PluginListVO> listPlugins(Long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在");
        }
        String storePath = pathResolver.getPluginsStorePath();
        List<FileInfo> entries = listFilesSafe(instanceId, storePath);

        // 启用状态加载与元数据加载互不依赖，与目录列表后并行执行
        java.util.concurrent.CompletableFuture<List<EnabledPlugin>> enabledFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> enabledPluginsService.list(instanceId), copyExecutor);

        java.util.Map<String, EnabledPlugin> enabledMap = new java.util.HashMap<>();

        List<PluginListVO> result = new ArrayList<>();
        if (entries == null) return result;

        // 收集目录名并并行加载元数据（每次远程读 ~0.4s，串行 N 次 → 并行 1 轮）
        List<String> names = new ArrayList<>();
        for (FileInfo entry : entries) {
            if (entry.isDirectory() && entry.getName() != null && !entry.getName().isBlank()) {
                names.add(entry.getName());
            }
        }
        java.util.Map<String, PluginMeta> metaMap = new java.util.concurrent.ConcurrentHashMap<>();
        List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
        for (String name : names) {
            futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                // ConcurrentHashMap 不允许 null 值：无元数据时跳过
                PluginMeta meta = pluginMetaService.load(instanceId, name);
                if (meta != null) {
                    metaMap.put(name, meta);
                }
            }, copyExecutor));
        }
        try {
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
            for (EnabledPlugin ep : enabledFuture.join()) {
                if (ep.getName() != null) {
                    enabledMap.put(ep.getName(), ep);
                }
            }
        } catch (Exception e) {
            log.warn("并行加载插件元数据异常: {}", e.getMessage());
        }

        for (String name : names) {
            PluginMeta meta = metaMap.get(name);
            EnabledPlugin enabled = enabledMap.get(name);
            String status = enabled != null ? "enabled" : "disabled";
            String source = meta != null && meta.getSource() != null ? meta.getSource()
                    : (enabled != null && enabled.getSource() != null ? enabled.getSource() : "panel");

            PluginListVO vo = new PluginListVO();
            vo.setName(name);
            vo.setStatus(status);
            vo.setSource(source);
            if (meta != null) {
                vo.setDescription(meta.getDescription());
                vo.setVersion(meta.getVersion());
                vo.setAuthor(meta.getAuthor());
                vo.setFileList(meta.getFileList());
                vo.setConfigFiles(meta.getConfigFiles());
                vo.setHasSmx(meta.getFileList() != null && meta.getFileList().stream()
                        .anyMatch(f -> f.toLowerCase().endsWith(SMX_SUFFIX)));
                vo.setHasConfig(meta.getConfigFiles() != null && !meta.getConfigFiles().isEmpty());
            }
            if (enabled != null && enabled.getEnabledAt() != null) {
                vo.setEnableTime(java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(enabled.getEnabledAt()),
                        java.time.ZoneId.systemDefault()));
            }
            result.add(vo);
        }
        return result;
    }

    private List<FileInfo> listFilesSafe(Long instanceId, String path) {
        try {
            return instanceFileService.listFiles(instanceId, path);
        } catch (Exception e) {
            log.warn("列出目录失败 path={}, err={}", path, e.getMessage());
            return List.of();
        }
    }

    /**
     * 列出已启用插件名（仅扫描 plugins 目录）。
     */
    public List<String> listEnabledPlugins(Long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在");
        }
        return scanSmxFiles(instanceId, pathResolver.getSourceModPluginsPath());
    }

    /**
     * 禁用所有插件（供 PresetService 调用）：一次性批量禁用 enabled_plugins 中的全部插件。
     *
     * <p>对齐 l4d2-server-next ApplyPreset 中的 DisablePlugins(toDisable)。
     */
    public void disableAllPlugins(Long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在");
        }
        List<EnabledPlugin> enabled = enabledPluginsService.list(instanceId);
        List<String> names = enabled.stream().map(EnabledPlugin::getName).toList();
        List<String> errors = disableAndUnloadBatch(instanceId, names);
        if (!errors.isEmpty()) {
            log.warn("禁用所有插件部分失败: {}", String.join("; ", errors));
        }
        log.info("已禁用所有插件: instanceId={}, count={}", instanceId, names.size());
    }

    /**
     * 列出已安装到 plugins_store 的插件目录名（一次远程目录列表）。
     *
     * <p>供内置清单/预设等「批量判定 installed」的场景使用，
     * 避免对每个插件逐个远程检查（60+ 条目 × 每次 ~0.2-0.7s 的慢接口根因）。</p>
     *
     * @param instanceId 实例 ID
     * @return 已安装插件名集合（目录名）
     */
    public java.util.Set<String> listInstalledPluginNames(Long instanceId) {
        List<FileInfo> entries = listFilesSafe(instanceId, pathResolver.getPluginsStorePath());
        if (entries == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        for (FileInfo entry : entries) {
            if (entry.isDirectory() && entry.getName() != null && !entry.getName().isBlank()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    /**
     * 检查插件是否存在于 plugins_store 中。
     *
     * @param instanceId 实例 ID
     * @param pluginName 插件名
     * @return true 表示存在
     */
    public boolean pluginExists(Long instanceId, String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            return false;
        }
        try {
            String storePath = pathResolver.getPluginStorePath(pluginName);
            return instanceFileService.exists(instanceId, storePath);
        } catch (Exception e) {
            log.warn("检查插件存在性失败 instanceId={}, plugin={}, err={}",
                    instanceId, pluginName, e.getMessage());
            return false;
        }
    }

    /**
     * 启用指定平台插件（供 PresetService 调用）。
     *
     * <p>当前实现：扫描 plugins_store，启用名称中包含 platform 字符串的插件。
     */
    public void enablePlatformPlugins(Long instanceId, String platform) {
        if (platform == null || platform.isBlank()) {
            return;
        }
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在");
        }
        List<FileInfo> entries = listFilesSafe(instanceId, pathResolver.getPluginsStorePath());
        if (entries == null) return;
        String lower = platform.toLowerCase();
        for (FileInfo entry : entries) {
            if (!entry.isDirectory()) continue;
            String name = entry.getName();
            if (name == null) continue;
            if (name.toLowerCase().contains(lower)) {
                try {
                    enableAndLoad(instanceId, name);
                } catch (Exception e) {
                    log.warn("启用平台插件失败 instanceId={}, plugin={}, platform={}, err={}",
                            instanceId, name, platform, e.getMessage());
                }
            }
        }
    }

    /**
     * 删除插件：拒绝已启用插件；删除整个 plugins_store/<name>/ 目录；清理 plugin.yaml。
     *
     * <p>对齐 l4d2-server-next DeletePlugin：
     * <ol>
     *   <li>遍历 enabled_plugins，命中则拒绝（错误信息 "cannot delete enabled plugin, disable it first"）</li>
     *   <li>os.RemoveAll(plugins_store/<name>) 删除整个库目录</li>
     *   <li>清理 plugin_sources map（本项目为 plugin.yaml）</li>
     * </ol>
     *
     * <p>注意：删除只针对库目录，不动游戏目录中的文件 —— 因为前置校验保证了
     * 已启用插件无法删除，所以游戏目录中该插件的文件已经通过 disableAndUnload 清理过。
     */
    public void deletePlugin(Long instanceId, String pluginName) {
        validatePluginName(pluginName);
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在");
        }

        // 1. 拒绝删除已启用插件
        if (enabledPluginsService.isEnabled(instanceId, pluginName)) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "不能删除已启用的插件，请先禁用: " + pluginName);
        }

        // 2. 删除整个库目录 plugins_store/<pluginName>/
        String storeDir = pathResolver.getPluginStorePath(pluginName);
        try {
            instanceFileService.deleteDirectory(instanceId, storeDir, true);
        } catch (Exception e) {
            log.warn("删除插件库目录失败 instanceId={}, pluginName={}, err={}",
                    instanceId, pluginName, e.getMessage());
        }

        // 3. 清理 plugin.yaml
        pluginMetaService.delete(instanceId, pluginName);

        log.info("插件已删除: instanceId={}, pluginName={}", instanceId, pluginName);
    }

    // ========== 私有方法 ==========

    private void validatePluginName(String pluginName) {
        if (pluginName == null || pluginName.isBlank()
                || pluginName.contains("..") || pluginName.contains("/") || pluginName.contains("\\")) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "无效的插件名称: " + pluginName);
        }
    }

    private List<String> scanSmxFiles(Long instanceId, String remotePath) {
        List<String> names = new ArrayList<>();
        try {
            List<FileInfo> files = instanceFileService.listFiles(instanceId, remotePath);
            if (files == null) {
                return names;
            }
            for (FileInfo f : files) {
                if (f.isDirectory()) {
                    continue;
                }
                String name = f.getName();
                if (name != null && name.toLowerCase().endsWith(SMX_SUFFIX)) {
                    names.add(name.substring(0, name.length() - SMX_SUFFIX.length()));
                }
            }
        } catch (Exception e) {
            log.warn("扫描 .smx 目录失败 path={}, err={}", remotePath, e.getMessage());
        }
        return names;
    }

    private PluginListVO buildPluginVO(String name, String status) {
        PluginListVO vo = new PluginListVO();
        vo.setName(name);
        vo.setStatus(status);
        vo.setSource("panel");
        if ("enabled".equals(status)) {
            vo.setEnableTime(LocalDateTime.now());
        }
        vo.setCreateTime(LocalDateTime.now());
        vo.setUpdateTime(LocalDateTime.now());
        return vo;
    }

    private byte[] readFileHeader(File file, int length) {
        byte[] header = new byte[length];
        try (FileInputStream fis = new FileInputStream(file)) {
            int read = fis.read(header);
            if (read < length) {
                byte[] trimmed = new byte[read];
                System.arraycopy(header, 0, trimmed, 0, read);
                return trimmed;
            }
        } catch (IOException e) {
            log.warn("读取文件头失败 file={}, err={}", file.getAbsolutePath(), e.getMessage());
        }
        return header;
    }

    private void extractArchive(File archiveFile, File destDir) {
        String name = archiveFile.getName().toLowerCase();
        try {
            if (name.endsWith(".zip")) {
                ArchiveExtractUtil.extractZip(archiveFile, destDir);
            } else if (name.endsWith(".7z")) {
                ArchiveExtractUtil.extract7z(archiveFile, destDir);
            } else {
                // 兜底按 ZIP 处理
                ArchiveExtractUtil.extractZip(archiveFile, destDir);
            }
        } catch (IOException e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "解压失败: " + e.getMessage(), e);
        }
    }

    /**
     * 遍历解压目录，将 .smx 上传到 plugins/，将 cfg/translations 上传到对应目录并登记引用。
     */
    private void processExtractedArchive(Long instanceId, File extractRoot) {
        String gamePath = pathResolver.getGamePath();

        // 寻找 left4dead2 子目录作为基准
        File baseDir = findBaseDir(extractRoot);

        List<String> installedPlugins = new ArrayList<>();
        List<String> sharedFiles = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(baseDir.toPath())) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .toList();
            for (Path filePath : files) {
                String relative = baseDir.toPath().relativize(filePath).toString().replace('\\', '/');
                if (relative.isEmpty()) {
                    continue;
                }
                String lower = relative.toLowerCase();
                if (lower.endsWith(SMX_SUFFIX) && lower.contains("addons/sourcemod/plugins/")) {
                    // 上传到 plugins/ 目录
                    String fileName = filePath.getFileName().toString();
                    String remotePath = pathResolver.getSourceModPluginsPath() + "/" + fileName;
                    instanceFileService.uploadLocalFile(instanceId, remotePath, filePath.toString());
                    installedPlugins.add(stripExtension(fileName));
                } else if (lower.startsWith("addons/sourcemod/configs/")
                        || lower.startsWith("addons/sourcemod/translations/")
                        || lower.startsWith("cfg/sourcemod/")) {
                    // 共享文件：上传到对应目录，并登记引用
                    String remotePath = gamePath + "/" + relative;
                    instanceFileService.uploadLocalFile(instanceId, remotePath, filePath.toString());
                    sharedFiles.add(relative);
                } else {
                    // 其他文件：按相对路径上传到 left4dead2/
                    String remotePath = gamePath + "/" + relative;
                    try {
                        instanceFileService.uploadLocalFile(instanceId, remotePath, filePath.toString());
                    } catch (Exception e) {
                        log.warn("上传文件失败 relPath={}, err={}", relative, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "遍历解压目录失败: " + e.getMessage(), e);
        }

        // 登记共享文件引用
        for (String pluginName : installedPlugins) {
            fileRefsService.addRefs(instanceId, pluginName, sharedFiles);
        }
        log.info("归档安装完成: instanceId={}, plugins={}, sharedFiles={}",
                instanceId, installedPlugins, sharedFiles.size());
    }

    /**
     * 寻找解压根目录下的 left4dead2 子目录；若无则使用解压根。
     */
    private File findBaseDir(File extractRoot) {
        File[] children = extractRoot.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && LEFT_4_DEAD_2.equalsIgnoreCase(child.getName())) {
                    return child;
                }
            }
        }
        // 兜底：尝试在两层目录内查找 left4dead2
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    File[] grandchildren = child.listFiles();
                    if (grandchildren != null) {
                        for (File grand : grandchildren) {
                            if (grand.isDirectory() && LEFT_4_DEAD_2.equalsIgnoreCase(grand.getName())) {
                                return grand;
                            }
                        }
                    }
                }
            }
        }
        return extractRoot;
    }

    private File createTempDir(String prefix) {
        try {
            Path tmp = Files.createTempDirectory(prefix);
            return tmp.toFile();
        } catch (IOException e) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "创建临时目录失败: " + e.getMessage(), e);
        }
    }

    private void deleteRecursive(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private String sanitizeFilename(String name) {
        return name == null ? "unknown" : name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }

    private String stripExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ===== 本地插件 README 读取（对齐 l4d2-server-next POST /plugins/readme）=====

    /**
     * 读取本地插件库中插件的 README.md 内容。
     *
     * @param instanceId 实例 ID
     * @param pluginName 插件名
     * @return PluginReadmeVO（exists=false 时 content 为空）
     */
    public PluginReadmeVO readReadme(Long instanceId, String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "pluginName 不能为空");
        }
        PluginReadmeVO vo = new PluginReadmeVO();
        vo.setPluginName(pluginName);

        String readmeRelPath = pathResolver.getPluginReadmePath(pluginName);
        if (!instanceFileService.exists(instanceId, readmeRelPath)) {
            vo.setExists(false);
            vo.setContent("");
            return vo;
        }

        try {
            String content = instanceFileService.readTextFile(instanceId, readmeRelPath, gbk);
            vo.setExists(true);
            vo.setContent(content != null ? content : "");
        } catch (Exception e) {
            log.warn("读取 README 失败: instanceId={}, plugin={}, err={}",
                    instanceId, pluginName, e.getMessage());
            vo.setExists(false);
            vo.setContent("");
        }
        return vo;
    }

    // ===== 商店下载临时目录模式（对齐 l4d2-server-next StartStorePluginDownload）=====

    /**
     * 将本地 ZIP 文件解压到远程临时目录（不直接入正式库）。
     *
     * <p>对齐 l4d2-server-next 的临时目录模式：
     * 解压到 {storePath}/.download_temp/{taskId}/，校验结构成功后由调用方调用 atomicMoveToStore。
     *
     * @param instanceId 实例 ID
     * @param localFile 本地 ZIP 文件
     * @param taskId 下载任务 ID（用作临时目录名）
     * @return 解压后的插件名列表（扫描临时目录下的子目录）
     */
    public List<String> installFromLocalFileToTempDir(Long instanceId, File localFile, String taskId) {
        if (instanceId == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "instanceId 不能为空");
        }
        if (localFile == null || !localFile.exists()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "本地文件不存在");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "taskId 不能为空");
        }

        // 1. 创建远程临时目录
        String tempDirRel = pathResolver.getDownloadTaskTempPath(taskId);
        instanceFileService.createDirectory(instanceId, tempDirRel);

        // 2. 本地解压到临时目录
        File localExtractDir = createTempDir("l4d2-store-extract-" + sanitizeFilename(taskId) + "-");
        try {
            extractArchive(localFile, localExtractDir);

            // 3. 上传解压后的文件到远程临时目录
            //    期望结构：localExtractDir/<pluginName>/left4dead2/...
            File[] pluginDirs = localExtractDir.listFiles(File::isDirectory);
            if (pluginDirs == null || pluginDirs.length == 0) {
                throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                        "ZIP 中未找到有效插件目录");
            }

            List<String> pluginNames = new ArrayList<>();
            for (File pluginDir : pluginDirs) {
                String pluginName = pluginDir.getName();
                String remotePluginTempDir = tempDirRel + "/" + pluginName;
                instanceFileService.createDirectory(instanceId, remotePluginTempDir);
                uploadDirectoryRecursive(instanceId, pluginDir, remotePluginTempDir);
                pluginNames.add(pluginName);
            }
            return pluginNames;
        } finally {
            deleteRecursive(localExtractDir);
        }
    }

    /**
     * 将临时目录中的插件原子移动到正式插件库。
     *
     * <p>使用 instanceFileService.moveFile（底层 mv 命令，同文件系统下原子）。
     * 失败时调用方负责清理临时目录。
     *
     * @param instanceId 实例 ID
     * @param taskId 下载任务 ID
     * @param pluginName 插件名（=临时目录下的子目录名 = 目标目录名）
     */
    public void atomicMoveToStore(Long instanceId, String taskId, String pluginName) {
        String tempPluginDir = pathResolver.getDownloadTaskTempPath(taskId) + "/" + pluginName;
        String targetPluginDir = pathResolver.getPluginStorePath(pluginName);

        if (instanceFileService.exists(instanceId, targetPluginDir)) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "插件已存在: " + pluginName + "，请先删除");
        }

        // 确保父目录存在
        instanceFileService.createDirectory(instanceId, pathResolver.getPluginsStorePath());

        // 远程原子移动（mv 对目录有效，同文件系统下原子）
        instanceFileService.moveFile(instanceId, tempPluginDir, targetPluginDir);

        // 写 plugin.yaml 元数据
        PluginMeta meta = new PluginMeta();
        meta.setName(pluginName);
        meta.setSource("store");
        meta.setCreatedAt(System.currentTimeMillis());
        meta.setUpdatedAt(System.currentTimeMillis());
        pluginMetaService.save(instanceId, meta);

        log.info("插件已原子移动到正式库: instanceId={}, plugin={}", instanceId, pluginName);
    }

    /**
     * 递归上传本地目录到远程目录。
     *
     * @param instanceId 实例 ID
     * @param localDir 本地目录
     * @param remoteDirRel 远程目录相对路径
     */
    private void uploadDirectoryRecursive(Long instanceId, File localDir, String remoteDirRel) {
        File[] children = localDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String childRemotePath = remoteDirRel + "/" + child.getName();
            if (child.isDirectory()) {
                instanceFileService.createDirectory(instanceId, childRemotePath);
                uploadDirectoryRecursive(instanceId, child, childRemotePath);
            } else {
                instanceFileService.uploadLocalFile(instanceId, childRemotePath, child.getAbsolutePath());
            }
        }
    }
}
