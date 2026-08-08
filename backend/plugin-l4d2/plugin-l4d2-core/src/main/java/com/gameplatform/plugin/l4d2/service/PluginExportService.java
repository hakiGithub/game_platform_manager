package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.PluginExportTaskVO;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * L4D2 插件全量导出服务。
 *
 * <p>扫描实例的 plugins/cfg/translations 目录，下载到本地临时目录并打包为 ZIP。
 * 每个实例同时只允许一个导出任务，30 分钟后自动清理。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginExportService {

    /** 任务过期时间（30 分钟） */
    private static final long EXPIRE_MS = 30L * 60 * 1000;

    /** 任务状态常量 */
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 导出根目录下的子目录（相对 left4dead2/） */
    private static final String[] SCAN_RELATIVE_PATHS = {
            "addons/sourcemod/plugins",
            "cfg/sourcemod",
            "addons/sourcemod/translations"
    };

    private static final String LEFT_4_DEAD_2 = "left4dead2";

    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;

    /** 实例ID → 任务 */
    private final Map<Long, PluginExportTaskVO> tasks = new ConcurrentHashMap<>();
    /** 任务ID → 本地工作目录 */
    private final Map<String, File> taskWorkDirs = new ConcurrentHashMap<>();

    /**
     * 启动导出任务：异步执行，返回 taskId。
     */
    public String startExport(Long instanceId) {
        PluginExportTaskVO existing = tasks.get(instanceId);
        if (existing != null && STATUS_RUNNING.equals(existing.getStatus())) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "实例已有正在进行的导出任务: " + instanceId);
        }

        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        PluginExportTaskVO task = new PluginExportTaskVO();
        task.setTaskId(taskId);
        task.setInstanceId(instanceId);
        task.setStatus(STATUS_RUNNING);
        task.setStartedAt(LocalDateTime.now());
        tasks.put(instanceId, task);

        CompletableFuture.runAsync(() -> runExport(task));
        return taskId;
    }

    /**
     * 查询任务状态。
     */
    public PluginExportTaskVO getStatus(Long instanceId) {
        return tasks.get(instanceId);
    }

    /**
     * 下载导出文件：返回本地 ZIP 文件。
     */
    public File download(Long instanceId) {
        PluginExportTaskVO task = tasks.get(instanceId);
        if (task == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "导出任务不存在: " + instanceId);
        }
        if (!STATUS_COMPLETED.equals(task.getStatus())) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "导出任务未完成，当前状态: " + task.getStatus());
        }
        File zipFile = getZipFile(task.getTaskId());
        if (!zipFile.exists()) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "导出文件不存在或已被清理: " + task.getTaskId());
        }
        return zipFile;
    }

    /**
     * 取消任务。
     */
    public void cancel(Long instanceId) {
        PluginExportTaskVO task = tasks.get(instanceId);
        if (task == null) {
            return;
        }
        if (STATUS_RUNNING.equals(task.getStatus())) {
            task.setStatus(STATUS_CANCELLED);
            task.setFinishedAt(LocalDateTime.now());
            log.info("导出任务已取消: instanceId={}, taskId={}", instanceId, task.getTaskId());
        }
    }

    /**
     * 定时清理 30 分钟过期任务。
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanupExpiredTasks() {
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<Long, PluginExportTaskVO> entry : tasks.entrySet()) {
            PluginExportTaskVO task = entry.getValue();
            if (STATUS_RUNNING.equals(task.getStatus())) {
                continue;
            }
            LocalDateTime finished = task.getFinishedAt();
            if (finished == null) {
                continue;
            }
            if (Duration.between(finished, now).toMillis() > EXPIRE_MS) {
                tasks.remove(entry.getKey());
                File workDir = taskWorkDirs.remove(task.getTaskId());
                deleteRecursive(workDir);
                File zipFile = getZipFile(task.getTaskId());
                if (zipFile.exists()) {
                    zipFile.delete();
                }
                log.info("清理过期导出任务: taskId={}", task.getTaskId());
            }
        }
    }

    // ========== 内部实现 ==========

    private void runExport(PluginExportTaskVO task) {
        Long instanceId = task.getInstanceId();
        if (isCancelled(task)) {
            return;
        }
        File workDir = createWorkDir(task.getTaskId());
        taskWorkDirs.put(task.getTaskId(), workDir);
        try {
            InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
            if (instance == null) {
                throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在");
            }
            String gamePath = pathResolver.getGamePath();

            // 收集所有待下载文件
            List<RemoteFile> remoteFiles = new ArrayList<>();
            for (String relPath : SCAN_RELATIVE_PATHS) {
                if (isCancelled(task)) {
                    return;
                }
                String remoteBase = gamePath + "/" + relPath;
                collectRemoteFiles(instanceId, remoteBase, relPath, remoteFiles);
            }
            task.setTotalFiles(remoteFiles.size());

            // 下载到本地工作目录（保留 left4dead2/ 前缀）
            File localBase = new File(workDir, LEFT_4_DEAD_2);
            for (int i = 0; i < remoteFiles.size(); i++) {
                if (isCancelled(task)) {
                    return;
                }
                RemoteFile rf = remoteFiles.get(i);
                File localFile = new File(localBase, rf.relativePath);
                localFile.getParentFile().mkdirs();
                try {
                    instanceFileService.downloadFile(instanceId, rf.absolutePath, localFile.getAbsolutePath());
                    task.setProcessedFiles(i + 1);
                } catch (Exception e) {
                    log.warn("下载文件失败 path={}, err={}", rf.absolutePath, e.getMessage());
                }
            }

            // 打包 ZIP
            File zipFile = getZipFile(task.getTaskId());
            try (ZipOutputStream zos = new ZipOutputStream(
                    new FileOutputStream(zipFile), StandardCharsets.UTF_8)) {
                zipDirectory(localBase, LEFT_4_DEAD_2, zos, task);
            }

            task.setStatus(STATUS_COMPLETED);
            task.setFinishedAt(LocalDateTime.now());
            task.setDownloadUrl("/api/plugin/l4d2/plugins/export-all/download?instanceId=" + instanceId);
            log.info("导出任务完成: instanceId={}, taskId={}, files={}",
                    instanceId, task.getTaskId(), remoteFiles.size());
        } catch (Exception e) {
            log.error("导出任务失败: instanceId={}, taskId={}", instanceId, task.getTaskId(), e);
            if (!isCancelled(task)) {
                task.setStatus(STATUS_FAILED);
                task.setError(e.getMessage());
                task.setFinishedAt(LocalDateTime.now());
            }
        }
    }

    private boolean isCancelled(PluginExportTaskVO task) {
        return STATUS_CANCELLED.equals(task.getStatus());
    }

    private void collectRemoteFiles(Long instanceId, String remoteBase, String relativeBase,
                                    List<RemoteFile> out) {
        List<FileInfo> files;
        try {
            files = instanceFileService.listFiles(instanceId, remoteBase);
        } catch (Exception e) {
            log.warn("列出远程目录失败 path={}, err={}", remoteBase, e.getMessage());
            return;
        }
        if (files == null) {
            return;
        }
        for (FileInfo f : files) {
            String name = f.getName();
            if (name == null) {
                continue;
            }
            String childAbs = remoteBase + "/" + name;
            String childRel = relativeBase + "/" + name;
            if (f.isDirectory()) {
                collectRemoteFiles(instanceId, childAbs, childRel, out);
            } else {
                out.add(new RemoteFile(childAbs, childRel));
            }
        }
    }

    private void zipDirectory(File dir, String entryPrefix, ZipOutputStream zos, PluginExportTaskVO task)
            throws IOException {
        if (!dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (isCancelled(task)) {
                return;
            }
            String entryName = entryPrefix + "/" + child.getName();
            if (child.isDirectory()) {
                zipDirectory(child, entryName, zos, task);
            } else {
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(child)) {
                    fis.transferTo(zos);
                }
                zos.closeEntry();
            }
        }
    }

    private File createWorkDir(String taskId) {
        String home = System.getProperty("user.home");
        File base = new File(home, "game-platform-l4d2/export-tasks");
        File dir = new File(base, taskId);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "创建导出工作目录失败: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private File getZipFile(String taskId) {
        String home = System.getProperty("user.home");
        File base = new File(home, "game-platform-l4d2/export-tasks");
        return new File(base, taskId + ".zip");
    }

    private void deleteRecursive(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        try (var stream = Files.walk(dir.toPath())) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    /** 远程文件描述：相对根目录路径 + 相对 left4dead2/ 的路径 */
    private record RemoteFile(String absolutePath, String relativePath) {
    }
}
