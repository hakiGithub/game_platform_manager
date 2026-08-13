package com.gameplatform.patch;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.http.HttpUtil;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.patch.PatchInstallRequest;
import com.gameplatform.plugin.service.AbstractInstanceFileService;
import com.gameplatform.plugin.service.AbstractInstanceFileService.FileRoute;
import com.gameplatform.service.FileService;
import com.gameplatform.util.SshUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * 补丁安装执行管道（ADR-0006）
 *
 * <p>承载：全局并发闸门（3）、同主机互斥（由任务中心 scopeKey=hostId 承担）、
 * 可重试错误自动重试（2 次指数退避）、四条策略执行、覆盖备份与失败回滚、
 * 容器挂载判定（docker inspect）与 docker cp。</p>
 *
 * <p>与任务中心解耦：进度/取消经 {@link ProgressListener} 回调（handler 适配 TaskContext），
 * 便于用假替身测试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatchInstallExecutor {

    /** 全局并发上限（ADR-0006 决策 8） */
    private static final int GLOBAL_CONCURRENCY = 3;
    /** 可重试错误自动重试次数（ADR-0006 决策 8） */
    private static final int MAX_AUTO_RETRY = 2;
    private static final long[] RETRY_BACKOFF_MS = {5_000L, 20_000L};
    /** 备份保留份数（ADR-0006 决策 7） */
    private static final int BACKUP_KEEP = 5;
    private static final long SSH_TIMEOUT_MS = 600_000L;

    private final Semaphore globalSemaphore = new Semaphore(GLOBAL_CONCURRENCY);

    private final AbstractInstanceFileService instanceFileService;
    private final FileService fileService;
    private final HostMapper hostMapper;
    private final HostCapabilityProber prober;
    private final PatchDecisionEngine decisionEngine;
    private final PatchArchiveExtractor extractor;

    /** 进度回调接口（handler 适配任务中心 TaskContext） */
    public interface ProgressListener {
        void onProgress(int percent, String message);
        void onLog(String message);
        boolean isCancelled();
    }

    /** 可重试错误（下载失败 / SSH 瞬断），重试有意义的失败以此包装 */
    public static class RetryableException extends RuntimeException {
        public RetryableException(String message) {
            super(message);
        }
    }

    public void execute(PatchInstallRequest request, ProgressListener progress) {
        boolean acquired = false;
        try {
            globalSemaphore.acquire();
            acquired = true;
            if (progress.isCancelled()) {
                throw new BusinessException("任务已取消");
            }
            doExecute(request, progress);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("等待并发额度被中断");
        } finally {
            if (acquired) {
                globalSemaphore.release();
            }
        }
    }

    private void doExecute(PatchInstallRequest request, ProgressListener progress) {
        // 1. 参数校验
        if (request.getInstanceId() == null || request.getUrl() == null
                || request.getUrl().isBlank() || request.getTargetPath() == null) {
            throw new BusinessException("instanceId/url/targetPath 不能为空");
        }

        // 2. 路由（复用 buildRoute：native 路径 / docker 容器路径，ADR-0006 决策 4）
        FileRoute route = instanceFileService.resolveRoute(request.getInstanceId(), request.getTargetPath());
        progress.onLog("路由: " + (route.isNative() ? "宿主机目录 " : "容器 " + route.containerId + " ")
                + route.resolvedPath);

        // 3. 主机与 isLanHost（平台代劳门控，ADR-0004）
        Host host = hostMapper.selectById(route.hostId);
        if (host == null) {
            throw new BusinessException("主机不存在: " + route.hostId);
        }

        // 4. 格式、探测、决策（ADR-0006 决策 5）
        PatchFormat format = PatchFormat.detect(request.getUrl(), request.getFormat());
        HostCapabilities caps = prober.probe(route.hostId);
        PatchStrategy strategy = decisionEngine.decide(caps, format, Boolean.TRUE.equals(host.getIsLanHost()));
        progress.onLog("补丁格式: " + format + ", 策略: " + strategy + ", isLanHost: " + host.getIsLanHost());
        if (strategy == PatchStrategy.ERROR_WAN_NOT_SELF_SUFFICIENT) {
            throw new BusinessException("目标主机不能自治（不能下载或不能解压）且为公网主机，平台不跨公网代劳（ADR-0004/ADR-0006）");
        }

        // 5. 执行（可重试错误自动重试）
        runWithRetry(() -> executeStrategy(route, host, request, format, caps, strategy, progress), progress);
    }

    // ==================== 策略执行 ====================

    private void executeStrategy(FileRoute route, Host host, PatchInstallRequest request,
                                 PatchFormat format, HostCapabilities caps,
                                 PatchStrategy strategy, ProgressListener progress) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String hostTmpDir = "/tmp/patch_install_" + timestamp;
        String hostArchive = hostTmpDir + "/patch" + archiveSuffix(format);
        Set<String> topLevel = null;

        try {
            switch (strategy) {
                case TARGET_DOWNLOAD_TARGET_EXTRACT -> {
                    progress.onProgress(10, "目标主机远程下载补丁");
                    remoteDownload(host, caps, request.getUrl(), hostArchive);
                    verifyRemoteChecksum(host, caps, hostArchive, request.getSha256());
                    topLevel = listRemoteEntries(host, format, hostArchive);
                    backup(route, host, topLevel, request.getTargetPath());
                    if (format.isArchive()) {
                        progress.onProgress(40, "目标主机远程解压");
                        remoteExtract(host, format, hostArchive, hostTmpDir + "/extracted");
                        moveEntriesInto(route, host, topLevel, hostTmpDir + "/extracted");
                    } else {
                        progress.onProgress(40, "推送文件到目标位置");
                        moveSingleInto(route, host, hostArchive);
                    }
                }
                case PLATFORM_DOWNLOAD_TARGET_EXTRACT -> {
                    Path localArchive = platformDownload(request, progress);
                    progress.onProgress(40, "推送压缩包到目标主机");
                    fileService.uploadLocalFile(route.hostId, hostArchive, localArchive.toString());
                    topLevel = extractor.listTopLevelEntries(localArchive, format);
                    backup(route, host, topLevel, request.getTargetPath());
                    progress.onProgress(60, "目标主机远程解压");
                    remoteExtract(host, format, hostArchive, hostTmpDir + "/extracted");
                    moveEntriesInto(route, host, topLevel, hostTmpDir + "/extracted");
                }
                case PLATFORM_DOWNLOAD_PLATFORM_EXTRACT -> {
                    Path localArchive = platformDownload(request, progress);
                    if (format.isArchive()) {
                        Path extractedDir = Files.createTempDirectory("patch_extract_");
                        topLevel = extractor.extract(localArchive, format, extractedDir);
                        backup(route, host, topLevel, request.getTargetPath());
                        progress.onProgress(60, "推送解压后的文件");
                        pushExtracted(route, host, extractedDir);
                    } else {
                        progress.onProgress(60, "推送文件到目标位置");
                        pushSingleFile(route, host, localArchive);
                    }
                }
                case ERROR_WAN_NOT_SELF_SUFFICIENT -> throw new BusinessException(
                        "目标主机不能自治且为公网主机，平台不代劳");
            }
            progress.onProgress(100, "补丁安装完成");
        } catch (Exception e) {
            // 已备份则尝试回滚（ADR-0006 决策 7：失败自动恢复备份，尽力而为）
            if (topLevel != null && !topLevel.isEmpty()) {
                try {
                    rollback(route, host, topLevel);
                    progress.onLog("已回滚备份");
                } catch (Exception rb) {
                    log.warn("回滚失败: {}", rb.getMessage());
                    progress.onLog("回滚失败: " + rb.getMessage());
                }
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new BusinessException(e.getMessage());
        } finally {
            // 临时目录清理（尽力）
            runRemoteQuietly(host, "rm -rf " + hostTmpDir);
        }
    }

    // ==================== 下载 ====================

    private void remoteDownload(Host host, HostCapabilities caps, String url, String destPath) {
        String cmd = caps.hasTool("curl")
                ? "curl -fsSL --max-time 600 -o " + shellQuote(destPath) + " " + shellQuote(url)
                : "wget -q --timeout=600 -O " + shellQuote(destPath) + " " + shellQuote(url);
        SshUtil.CommandResult r = execOnHost(host, cmd);
        if (r == null || !r.isSuccess()) {
            throw new RetryableException("远程下载失败: " + (r != null ? r.getError() : "无响应"));
        }
    }

    private Path platformDownload(PatchInstallRequest request, ProgressListener progress) {
        progress.onProgress(10, "平台下载补丁");
        try {
            Path dir = Files.createTempDirectory("patch_download_");
            Path dest = dir.resolve("patch" + archiveSuffix(PatchFormat.detect(request.getUrl(), request.getFormat())));
            long size = HttpUtil.downloadFile(request.getUrl(), dest.toFile(), 600_000);
            if (size <= 0) {
                throw new RetryableException("平台下载失败: 响应为空");
            }
            // sha256 校验（ADR-0006 决策 7：平台下载路径强制）
            if (request.getSha256() != null && !request.getSha256().isBlank()) {
                String actual = DigestUtil.sha256Hex(dest.toFile());
                if (!request.getSha256().equalsIgnoreCase(actual)) {
                    throw new BusinessException("补丁 sha256 校验失败: 期望 " + request.getSha256() + "，实际 " + actual);
                }
            }
            return dest;
        } catch (BusinessException | RetryableException e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableException("平台下载失败: " + e.getMessage());
        }
    }

    private void verifyRemoteChecksum(Host host, HostCapabilities caps, String remoteFile, String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return;
        }
        String cmd;
        if (caps.hasTool("sha256sum")) {
            cmd = "sha256sum " + shellQuote(remoteFile) + " | awk '{print $1}'";
        } else if (caps.hasTool("shasum")) {
            cmd = "shasum -a 256 " + shellQuote(remoteFile) + " | awk '{print $1}'";
        } else {
            log.warn("目标主机无校验工具，跳过 sha256 校验（仅警告）");
            return;
        }
        SshUtil.CommandResult r = execOnHost(host, cmd);
        if (r == null || !r.isSuccess() || !sha256.equalsIgnoreCase(r.getOutput().trim())) {
            throw new BusinessException("补丁 sha256 校验失败");
        }
    }

    // ==================== 解压与移动（远程） ====================

    private Set<String> listRemoteEntries(Host host, PatchFormat format, String remoteArchive) {
        SshUtil.CommandResult r = execOnHost(host, "tar -tzf " + shellQuote(remoteArchive));
        if (r == null || !r.isSuccess()) {
            throw new BusinessException("读取压缩包清单失败: " + (r != null ? r.getError() : ""));
        }
        Set<String> topLevel = new java.util.LinkedHashSet<>();
        for (String line : r.getOutput().split("\n")) {
            String name = line.trim();
            if (name.isEmpty() || name.equals("./")) {
                continue;
            }
            int slash = name.indexOf('/');
            topLevel.add(slash > 0 ? name.substring(0, slash) : name);
        }
        return topLevel;
    }

    private void remoteExtract(Host host, PatchFormat format, String archive, String destDir) {
        execOk(host, "mkdir -p " + shellQuote(destDir));
        String cmd = switch (format) {
            case TAR_GZ -> "tar -xzf";
            case TAR_BZ2 -> "tar -xjf";
            case TAR_XZ -> "tar -xJf";
            case ZIP -> "unzip -o";
            default -> throw new BusinessException("不支持的目标侧解压格式: " + format);
        };
        SshUtil.CommandResult r = execOnHost(host,
                cmd + " " + shellQuote(archive) + " -C " + shellQuote(destDir));
        if (r == null || !r.isSuccess()) {
            throw new BusinessException("远程解压失败: " + (r != null ? r.getError() : ""));
        }
    }

    /** 把解压出的顶层条目移动到目标位置（native → mv；docker → docker cp / 挂载写） */
    private void moveEntriesInto(FileRoute route, Host host, Set<String> topLevel, String sourceDir) {
        for (String entry : topLevel) {
            moveSingle(route, host, sourceDir + "/" + entry, entry);
        }
    }

    private void moveSingleInto(FileRoute route, Host host, String sourcePath) {
        String fileName = sourcePath.substring(sourcePath.lastIndexOf('/') + 1);
        moveSingle(route, host, sourcePath, fileName);
    }

    private void moveSingle(FileRoute route, Host host, String hostSourcePath, String entryName) {
        if (route.isNative()) {
            execOk(host, "cp -r " + shellQuote(hostSourcePath) + " "
                    + shellQuote(route.resolvedPath + "/" + entryName));
        } else {
            pushIntoContainer(route, host, hostSourcePath, route.resolvedPath + "/" + entryName);
        }
    }

    // ==================== 平台解压推送 ====================

    private void pushExtracted(FileRoute route, Host host, Path extractedDir) throws Exception {
        try (Stream<Path> walk = Files.walk(extractedDir)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
            for (Path file : files) {
                Path rel = extractedDir.relativize(file);
                if (route.isNative()) {
                    fileService.uploadLocalFile(route.hostId,
                            route.resolvedPath + "/" + rel.toString().replace('\\', '/'), file.toString());
                } else {
                    // docker：先 SFTP 到宿主临时目录，再 docker cp（复用 InstanceFileServiceImpl 模式）
                    String hostTmp = "/tmp/patch_push/" + file.getFileName();
                    fileService.uploadLocalFile(route.hostId, hostTmp, file.toString());
                    pushIntoContainer(route, host, hostTmp, route.resolvedPath + "/" + rel.toString().replace('\\', '/'));
                }
            }
        }
    }

    private void pushSingleFile(FileRoute route, Host host, Path localFile) {
        if (route.isNative()) {
            fileService.uploadLocalFile(route.hostId, route.resolvedPath, localFile.toString());
        } else {
            String hostTmp = "/tmp/patch_push/" + localFile.getFileName();
            fileService.uploadLocalFile(route.hostId, hostTmp, localFile.toString());
            pushIntoContainer(route, host, hostTmp, route.resolvedPath);
        }
    }

    /**
     * 容器写入：挂载目录 → 写宿主机挂载源目录；非挂载 → docker cp（ADR-0006 决策 4）。
     */
    private void pushIntoContainer(FileRoute route, Host host, String hostSourcePath, String containerDestPath) {
        MountMatch mount = detectMount(host, route.containerId, containerDestPath);
        if (mount != null) {
            // 目标落在挂载目录内：容器路径尾部拼到宿主机挂载源上
            String remainder = containerDestPath.substring(mount.destinationPrefix.length());
            if (remainder.startsWith("/")) {
                remainder = remainder.substring(1);
            }
            String hostDest = mount.source + (remainder.isEmpty() ? "" : "/" + remainder);
            execOk(host, "mkdir -p " + shellQuote(parentOf(hostDest)));
            execOk(host, "cp -r " + shellQuote(hostSourcePath) + " " + shellQuote(hostDest));
            return;
        }
        // 非挂载：docker cp（先 mkdir -p 父目录）
        execOk(host, "docker exec " + route.containerId + " mkdir -p " + shellQuote(parentOf(containerDestPath)));
        execOk(host, "docker cp " + shellQuote(hostSourcePath) + " " + route.containerId + ":" + shellQuote(containerDestPath));
    }

    /** 挂载匹配：source=宿主路径，destinationPrefix=容器内挂载点 */
    private record MountMatch(String source, String destinationPrefix) {
    }

    /** docker inspect 解析 Mounts，返回目标路径命中的挂载（未命中返回 null；inspect 失败不猜测路径） */
    private MountMatch detectMount(Host host, String containerId, String containerPath) {
        SshUtil.CommandResult r = execOnHostQuietly(host,
                "docker inspect " + containerId + " --format '{{json .Mounts}}'");
        if (r == null || !r.isSuccess()) {
            throw new BusinessException("容器未运行或无法解析挂载信息，无法可靠判定写入路径");
        }
        try {
            com.fasterxml.jackson.databind.JsonNode mounts =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getOutput());
            for (com.fasterxml.jackson.databind.JsonNode mount : mounts) {
                String destination = mount.path("Destination").asText("");
                if (!destination.isEmpty() && (containerPath.equals(destination)
                        || containerPath.startsWith(destination + "/"))) {
                    return new MountMatch(mount.path("Source").asText(""), destination);
                }
            }
            return null;
        } catch (Exception e) {
            throw new BusinessException("挂载信息解析失败");
        }
    }

    // ==================== 备份与回滚 ====================

    /** 覆盖前备份：native/docker 目标中将被覆盖的顶层条目 → <installPath>/.patch_backup/<ts>/ */
    private void backup(FileRoute route, Host host, Set<String> topLevel, String targetRel) {
        if (topLevel == null || topLevel.isEmpty()) {
            return;
        }
        String backupDir = backupDir(route);
        execOk(host, "mkdir -p " + shellQuote(backupDir));
        for (String entry : topLevel) {
            String src = route.resolvedPath + "/" + entry;
            SshUtil.CommandResult exists = execOnHostQuietly(host,
                    "test -e " + shellQuote(src) + " && echo yes || echo no");
            if (exists == null || !"yes".equals(exists.getOutput().trim())) {
                continue; // 新条目，无需备份
            }
            if (route.isNative()) {
                // 宿主机：tar 打包（无 tar 时 cp -r 兜底）
                SshUtil.CommandResult tar = execOnHostQuietly(host,
                        "tar -czf " + shellQuote(backupDir + "/" + entry + ".tar.gz")
                                + " -C " + shellQuote(route.resolvedPath) + " " + shellQuote(entry));
                if (tar == null || !tar.isSuccess()) {
                    execOk(host, "cp -r " + shellQuote(src) + " " + shellQuote(backupDir + "/" + entry));
                }
            } else {
                // docker：docker cp 出容器到宿主备份目录
                execOk(host, "mkdir -p " + shellQuote(backupDir + "/" + entry));
                execOk(host, "docker cp " + route.containerId + ":" + shellQuote(src) + " "
                        + shellQuote(backupDir + "/" + entry));
            }
        }
        pruneBackups(route, host);
    }

    private void rollback(FileRoute route, Host host, Set<String> topLevel) {
        // 使用最近一次备份目录（backupDir 基于时间戳——回滚需找到最新；简化：恢复最近一个）
        SshUtil.CommandResult r = execOnHostQuietly(host,
                "ls -1 " + shellQuote(backupRoot(route)) + " 2>/dev/null | tail -1");
        if (r == null || !r.isSuccess() || r.getOutput().isBlank()) {
            return;
        }
        String latest = backupRoot(route) + "/" + r.getOutput().trim();
        for (String entry : topLevel) {
            String backupEntry = latest + "/" + entry;
            if (route.isNative()) {
                String archive = backupEntry + ".tar.gz";
                SshUtil.CommandResult has = execOnHostQuietly(host, "test -e " + shellQuote(archive) + " && echo yes || echo no");
                if (has != null && "yes".equals(has.getOutput().trim())) {
                    execOk(host, "tar -xzf " + shellQuote(archive) + " -C " + shellQuote(route.resolvedPath));
                } else {
                    execOk(host, "cp -r " + shellQuote(backupEntry) + " " + shellQuote(route.resolvedPath + "/" + entry));
                }
            } else {
                execOk(host, "docker cp " + shellQuote(backupEntry) + " " + route.containerId + ":"
                        + shellQuote(route.resolvedPath + "/" + entry));
            }
        }
    }

    private String backupRoot(FileRoute route) {
        // 备份根：宿主机 <installPath>/.patch_backup（docker 场景使用实例安装路径根——以 resolvedPath 父目录近似）
        String base = route.isNative() ? route.resolvedPath : parentOf(route.resolvedPath);
        return base + "/.patch_backup";
    }

    private String backupDir(FileRoute route) {
        return backupRoot(route) + "/" + System.currentTimeMillis();
    }

    private void pruneBackups(FileRoute route, Host host) {
        SshUtil.CommandResult r = execOnHostQuietly(host,
                "ls -1t " + shellQuote(backupRoot(route)) + " 2>/dev/null | tail -n +" + (BACKUP_KEEP + 1));
        if (r == null || !r.isSuccess() || r.getOutput().isBlank()) {
            return;
        }
        for (String old : r.getOutput().split("\n")) {
            if (!old.isBlank()) {
                runRemoteQuietly(host, "rm -rf " + shellQuote(backupRoot(route) + "/" + old.trim()));
            }
        }
    }

    // ==================== 重试与 SSH 辅助 ====================

    private void runWithRetry(Runnable action, ProgressListener progress) {
        int attempt = 0;
        while (true) {
            try {
                action.run();
                return;
            } catch (RetryableException e) {
                if (attempt >= MAX_AUTO_RETRY || progress.isCancelled()) {
                    throw new BusinessException(e.getMessage() + "（已重试 " + attempt + " 次）");
                }
                attempt++;
                long backoff = RETRY_BACKOFF_MS[attempt - 1];
                progress.onLog("可重试错误，第 " + attempt + " 次重试（" + backoff + "ms 后）: " + e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("重试等待被中断");
                }
            }
        }
    }

    private SshUtil.CommandResult execOnHost(Host host, String command) {
        return execOnHostQuietly(host, command);
    }

    private void execOk(Host host, String command) {
        SshUtil.CommandResult r = execOnHostQuietly(host, command);
        if (r == null || !r.isSuccess()) {
            throw new RetryableException("SSH 命令执行失败: " + command + " - " + (r != null ? r.getError() : "无响应"));
        }
    }

    private SshUtil.CommandResult execOnHostQuietly(Host host, String command) {
        try {
            return fileService.executeCommand(host.getId(), command, SSH_TIMEOUT_MS);
        } catch (Exception e) {
            log.warn("SSH 命令异常: {} - {}", command, e.getMessage());
            return null;
        }
    }

    private void runRemoteQuietly(Host host, String command) {
        execOnHostQuietly(host, command);
    }

    private static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String archiveSuffix(PatchFormat format) {
        return switch (format) {
            case TAR_GZ -> ".tar.gz";
            case TAR_BZ2 -> ".tar.bz2";
            case TAR_XZ -> ".tar.xz";
            case ZIP -> ".zip";
            case GZ -> ".gz";
            case BZ2 -> ".bz2";
            case XZ -> ".xz";
            case PLAIN -> "";
        };
    }

    private static String parentOf(String path) {
        int idx = path.lastIndexOf('/');
        return idx > 0 ? path.substring(0, idx) : "/";
    }
}
