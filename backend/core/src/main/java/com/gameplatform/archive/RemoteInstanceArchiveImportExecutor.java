package com.gameplatform.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.patch.HostCapabilityProber;
import com.gameplatform.patch.PatchFormat;
import com.gameplatform.patch.PatchInstallExecutor;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.service.AbstractInstanceFileService;
import com.gameplatform.plugin.service.AbstractInstanceFileService.FileRoute;
import com.gameplatform.plugin.service.InstanceArchiveImportException;
import com.gameplatform.plugin.service.InstanceArchiveImportException.CommandCategory;
import com.gameplatform.plugin.service.InstanceArchiveImportListener;
import com.gameplatform.plugin.service.InstanceArchiveImportRequest;
import com.gameplatform.plugin.service.InstanceArchiveImportService;
import com.gameplatform.service.FileService;
import com.gameplatform.util.SshUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 通过主机 SSH / Docker 执行单插件归档导入。
 *
 * <p>平台只上传一次原始 ZIP；解压复用 {@link PatchInstallExecutor} 的原生工具与
 * platform-tools 回退。Docker 目标优先识别 bind mount 并直接写宿主挂载源；
 * 命名卷或无法确认挂载时只做一次目录级 {@code docker cp}。最终在目标父目录内用
 * {@code mv} 原子落位，旧目录先改名，失败时恢复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteInstanceArchiveImportExecutor
        implements InstanceArchiveImportExecutor, SmartInitializingSingleton {

    private static final long SSH_TIMEOUT_MS = 600_000L;
    private static final String HOST_TEMP_ROOT_PREFIX = "/tmp/gp-plugin-install-";
    private static final String MACOS_JUNK_ROOT = "__MACOSX";
    private static final String MACOS_JUNK_FILE = ".DS_Store";

    private final AbstractInstanceFileService instanceFileService;
    private final FileService fileService;
    private final HostMapper hostMapper;
    private final HostCapabilityProber capabilityProber;
    private final PatchInstallExecutor patchInstallExecutor;
    private final ObjectMapper objectMapper;

    @Override
    public String uploadArchive(InstanceArchiveImportRequest request, Path localArchive,
                                InstanceArchiveImportListener listener) {
        FileRoute route = instanceFileService.resolveRoute(
                request.getInstanceId(), request.getTargetRelativePath());
        Host host = requireHost(route.hostId);
        String tempDir = HOST_TEMP_ROOT_PREFIX
                + UUID.randomUUID().toString().replace("-", "");
        String hostArchive = tempDir + "/archive.zip";
        listener.onProgress(InstanceArchiveImportService.Phase.UPLOAD, 8, "上传原始 ZIP 到主机临时目录");
        execOk(host, InstanceArchiveImportService.Phase.UPLOAD,
                "mkdir -p " + shellQuote(tempDir), tempDir, CommandCategory.HOST_UPLOAD,
                "检查主机 SSH 连通性和 /tmp 目录权限");
        try {
            fileService.uploadLocalFile(host.getId(), hostArchive, localArchive.toString());
        } catch (Exception e) {
            runQuietly(host, "rm -rf " + shellQuote(tempDir));
            throw archiveFailure(InstanceArchiveImportService.Phase.UPLOAD,
                    hostArchive, CommandCategory.HOST_UPLOAD, null,
                    "检查主机 SSH/SFTP 连通性和 /tmp 剩余空间",
                    "原始 ZIP 上传失败: " + e.getMessage());
        }
        listener.onProgress(InstanceArchiveImportService.Phase.UPLOAD, 15, "原始 ZIP 上传完成");
        return hostArchive;
    }

    @Override
    public StagingHandle extractToStaging(InstanceArchiveImportRequest request, String hostArchive,
                                          InstanceArchiveImportListener listener) {
        StagingPlan plan = plan(request, hostArchive);
        Host host = requireHost(plan.targetRoute.hostId);
        HostCapabilities capabilities = capabilityProber.probe(host.getId());
        listener.onProgress(InstanceArchiveImportService.Phase.EXTRACT, 18,
                "主机侧开始解压 ZIP");
        execOk(host, InstanceArchiveImportService.Phase.EXTRACT,
                "mkdir -p " + shellQuote(plan.hostStaging), plan.hostStaging,
                CommandCategory.HOST_EXTRACT,
                "检查主机 SSH 连通性与 /tmp 目录权限");
        try {
            patchInstallExecutor.toolingExtract(host, capabilities, PatchFormat.ZIP,
                    hostArchive, plan.hostStaging, null);
        } catch (Exception e) {
            throw archiveFailure(InstanceArchiveImportService.Phase.EXTRACT,
                    plan.instanceStaging, CommandCategory.HOST_EXTRACT, null,
                    "确认主机有 unzip/bsdtar，或 Docker 可拉取 platform-tools",
                    e.getMessage());
        }
        removeMacOsJunk(host, plan.hostStaging);
        normalizePluginRoot(host, plan.hostStaging, request.getStructureRoot());
        removeMacOsJunk(host, plan.hostStaging);
        listener.onProgress(InstanceArchiveImportService.Phase.EXTRACT, 40,
                "主机侧解压完成");
        return new StagingHandle(plan.targetRoute.isNative()
                ? request.getStagingRelativePath() : plan.instanceStaging,
                plan.hostStaging,
                plan.hostTarget, plan.containerStaging,
                request.getCommitMarkerFileName());
    }

    @Override
    public void importToStaging(InstanceArchiveImportRequest request, StagingHandle staging,
                                InstanceArchiveImportListener listener) {
        FileRoute targetRoute = resolveTarget(request);
        Host host = requireHost(targetRoute.hostId);
        if (!staging.containerStaging()) {
            listener.onProgress(InstanceArchiveImportService.Phase.IMPORT, 45,
                    "目标目录与 staging 位于同一主机文件系统，无需复制");
            return;
        }

        listener.onProgress(InstanceArchiveImportService.Phase.IMPORT, 45,
                "目录级复制到容器 staging");
        execDockerOk(host, targetRoute.containerId,
                "mkdir -p " + shellQuote(parentOf(staging.instanceStaging())),
                parentOf(staging.instanceStaging()),
                InstanceArchiveImportService.Phase.IMPORT);
        execDockerOk(host, targetRoute.containerId,
                "rm -rf " + shellQuote(staging.instanceStaging()),
                staging.instanceStaging(),
                InstanceArchiveImportService.Phase.IMPORT);
        execOk(host, InstanceArchiveImportService.Phase.IMPORT,
                "docker cp " + shellQuote(staging.hostStaging() + "/.") + " "
                        + shellQuote(targetRoute.containerId + ":"
                        + staging.instanceStaging() + "/"),
                staging.hostStaging(),
                CommandCategory.CONTAINER_COPY,
                "确认实例容器运行且目标目录可写");
        listener.onProgress(InstanceArchiveImportService.Phase.IMPORT, 65,
                "目录级复制完成");
    }

    @Override
    public ImportedTree validateStaging(InstanceArchiveImportRequest request, StagingHandle staging,
                                        InstanceArchiveImportListener listener) {
        FileRoute targetRoute = resolveTarget(request);
        Host host = requireHost(targetRoute.hostId);
        String stagingPath = staging.containerStaging()
                ? staging.instanceStaging() : staging.hostStaging();
        String containerId = staging.containerStaging() ? targetRoute.containerId : null;
        listener.onProgress(InstanceArchiveImportService.Phase.VALIDATE, 68,
                "校验 staging 文件数量、总字节与链接安全");

        requireNoLinks(host, containerId, stagingPath);
        List<String> files = listFiles(host, containerId, stagingPath);
        long totalBytes = sumBytes(host, containerId, stagingPath);
        requireReadableCriticalFiles(host, containerId, stagingPath,
                request.getCriticalFileExtensions());
        requireRequiredFiles(host, containerId, stagingPath,
                request.getRequiredFiles());

        listener.onProgress(InstanceArchiveImportService.Phase.VALIDATE, 80,
                "staging 校验完成：" + files.size() + " 个文件 / " + totalBytes + " 字节");
        return new ImportedTree(files.size(), totalBytes, files);
    }

    @Override
    public AtomicMoveHandle atomicMove(InstanceArchiveImportRequest request, StagingHandle staging,
                                       InstanceArchiveImportListener listener) {
        FileRoute targetRoute = resolveTarget(request);
        Host host = requireHost(targetRoute.hostId);
        String target = targetRoute.resolvedPath;
        listener.onProgress(InstanceArchiveImportService.Phase.ATOMIC_MOVE, 82,
                "准备同卷原子落位");

        if (staging.containerStaging()) {
            String backup = target + ".rollback-" + System.currentTimeMillis();
            boolean hadPrevious = atomicMoveInContainer(host, targetRoute.containerId,
                    staging.instanceStaging(), target, backup);
            listener.onProgress(InstanceArchiveImportService.Phase.ATOMIC_MOVE, 95,
                    "目录已原子落位，等待提交确认");
            return new AtomicMoveHandle(targetRoute.hostId, targetRoute.containerId,
                    target, backup, hadPrevious, staging.commitMarker());
        } else {
            String hostTarget = staging.hostTarget() != null ? staging.hostTarget() : target;
            String backup = hostTarget + ".rollback-" + System.currentTimeMillis();
            boolean hadPrevious = atomicMoveOnHost(host, staging.hostStaging(), hostTarget, backup);
            listener.onProgress(InstanceArchiveImportService.Phase.ATOMIC_MOVE, 95,
                    "目录已原子落位，等待提交确认");
            return new AtomicMoveHandle(targetRoute.hostId, null,
                    hostTarget, backup, hadPrevious, staging.commitMarker());
        }
    }

    @Override
    public void markCommitted(AtomicMoveHandle handle, InstanceArchiveImportListener listener) {
        if (handle.commitMarker() == null || handle.commitMarker().isBlank()) {
            return;
        }
        Host host = requireHost(handle.hostId());
        String markerPath = handle.target() + "/" + handle.commitMarker();
        String command = "mkdir -p " + shellQuote(handle.target())
                + " && touch " + shellQuote(markerPath);
        SshUtil.CommandResult result = handle.containerId() == null
                ? exec(host, command)
                : execDocker(host, handle.containerId(), command);
        if (result == null || !result.isSuccess()) {
            throw archiveFailure(InstanceArchiveImportService.Phase.ATOMIC_MOVE,
                    handle.target(), CommandCategory.FILESYSTEM_MOVE,
                    result == null ? null : result.getExitCode(),
                    "检查目标目录权限后重试",
                    result == null ? "提交标记命令无响应"
                            : "写入提交标记失败: " + safeError(result));
        }
    }

    @Override
    public void finalizeMove(AtomicMoveHandle handle, InstanceArchiveImportListener listener) {
        if (!handle.hadPrevious()) {
            return;
        }
        Host host = requireHost(handle.hostId());
        if (handle.containerId() == null) {
            runQuietly(host, "rm -rf " + shellQuote(handle.backup()));
        } else {
            runDockerQuietly(host, handle.containerId(),
                    "rm -rf " + shellQuote(handle.backup()));
        }
    }

    @Override
    public void rollbackMove(AtomicMoveHandle handle, InstanceArchiveImportListener listener) {
        Host host = requireHost(handle.hostId());
        String inner = handle.hadPrevious()
                ? "rm -rf " + shellQuote(handle.target())
                    + " && mv -f " + shellQuote(handle.backup()) + " "
                    + shellQuote(handle.target())
                : "rm -rf " + shellQuote(handle.target());
        SshUtil.CommandResult result = handle.containerId() == null
                ? exec(host, inner)
                : execDocker(host, handle.containerId(), inner);
        if (result == null || !result.isSuccess()) {
            throw archiveFailure(InstanceArchiveImportService.Phase.ATOMIC_MOVE,
                    handle.target(), CommandCategory.FILESYSTEM_MOVE,
                    result == null ? null : result.getExitCode(),
                    "检查实例目录权限，并手动恢复备份 " + handle.backup(),
                    result == null ? "回滚命令无响应"
                            : "回滚旧目录失败: " + safeError(result));
        }
    }

    @Override
    public void cleanup(InstanceArchiveImportRequest request, String hostArchive,
                        InstanceArchiveImportListener listener) {
        StagingPlan plan;
        try {
            plan = plan(request, hostArchive);
        } catch (Exception e) {
            log.warn("归档导入清理时无法解析路由 instanceId={}: {}",
                    request.getInstanceId(), e.getMessage());
            return;
        }
        Host host = hostMapper.selectById(plan.targetRoute.hostId);
        if (host == null) {
            return;
        }
        if (hostArchive != null && hostArchive.startsWith(HOST_TEMP_ROOT_PREFIX)) {
            runQuietly(host, "rm -rf " + shellQuote(parentOf(hostArchive)));
        }
        if (plan.containerStaging) {
            runDockerQuietly(host, plan.targetRoute.containerId,
                    "rm -rf " + shellQuote(plan.instanceStaging));
            runQuietly(host, "rm -rf " + shellQuote(plan.hostStaging));
        } else if (plan.mount != null) {
            runQuietly(host, "rm -rf " + shellQuote(plan.hostStaging));
        } else {
            runQuietly(host, "rm -rf " + shellQuote(plan.hostStaging));
        }
        listener.onProgress(InstanceArchiveImportService.Phase.CLEANUP, 100,
                "临时归档与 staging 已清理");
    }

    private StagingPlan plan(InstanceArchiveImportRequest request, String hostArchive) {
        FileRoute targetRoute = instanceFileService.resolveRoute(
                request.getInstanceId(), request.getTargetRelativePath());
        FileRoute stagingRoute = instanceFileService.resolveRoute(
                request.getInstanceId(), request.getStagingRelativePath());
        if (targetRoute.isNative() != stagingRoute.isNative()) {
            throw archiveFailure(InstanceArchiveImportService.Phase.UPLOAD,
                    request.getStagingRelativePath(), CommandCategory.VALIDATION, null,
                    "重新选择实例内 staging 路径",
                    "staging 与目标目录路由类型不一致");
        }
        if (targetRoute.isNative()) {
            return new StagingPlan(targetRoute, stagingRoute.resolvedPath,
                    stagingRoute.resolvedPath, targetRoute.resolvedPath, false, null);
        }

        MountMatch targetMount = detectMount(targetRoute.hostId,
                targetRoute.containerId, targetRoute.resolvedPath);
        if (targetMount != null) {
            String hostStaging = mapToHostPath(targetMount, stagingRoute.resolvedPath);
            String hostTarget = mapToHostPath(targetMount, targetRoute.resolvedPath);
            if (!isSafeMappedPath(hostStaging) || !isSafeMappedPath(hostTarget)) {
                throw archiveFailure(InstanceArchiveImportService.Phase.IMPORT,
                        targetRoute.resolvedPath, CommandCategory.CONTAINER_COPY, null,
                        "确认容器挂载源是安全的绝对路径",
                        "容器挂载源路径非法: " + targetMount.source);
            }
            return new StagingPlan(targetRoute, stagingRoute.resolvedPath,
                    hostStaging, hostTarget, false, targetMount);
        }
        String tempRoot = hostArchive == null
                ? HOST_TEMP_ROOT_PREFIX + "staging-"
                + UUID.randomUUID().toString().replace("-", "")
                : parentOf(hostArchive) + "/staging";
        String hostStaging = tempRoot + "/" + basename(stagingRoute.resolvedPath);
        return new StagingPlan(targetRoute, stagingRoute.resolvedPath,
                hostStaging, null, true, null);
    }

    private void normalizePluginRoot(Host host, String staging, String structureRoot) {
        SshUtil.CommandResult root = execOk(host, InstanceArchiveImportService.Phase.EXTRACT,
                "test -d " + shellQuote(staging + "/" + structureRoot)
                        + " && echo ROOT || "
                        + "find " + shellQuote(staging) + " -mindepth 1 -maxdepth 1 -type d "
                        + "-exec sh -c 'test -d \"$1/$2\" && echo \"$1\"' sh {} "
                        + shellQuote(structureRoot) + " \\; | head -n 1",
                staging,
                CommandCategory.VALIDATION, "确认 ZIP 内只有一个插件根目录");
        String pluginRoot = safeOutput(root);
        if ("ROOT".equals(pluginRoot) || pluginRoot.isEmpty()) {
            return;
        }
        if (!pluginRoot.startsWith(staging + "/")) {
            throw archiveFailure(InstanceArchiveImportService.Phase.EXTRACT,
                    staging, CommandCategory.VALIDATION, null,
                    "确认 ZIP 内只有一个插件根目录",
                    "解压结果未找到唯一插件根目录");
        }
        execOk(host, InstanceArchiveImportService.Phase.EXTRACT,
                "find " + shellQuote(pluginRoot)
                        + " -mindepth 1 -maxdepth 1 -exec mv -f {} "
                        + shellQuote(staging + "/") + " \\;",
                pluginRoot,
                CommandCategory.FILESYSTEM_MOVE,
                "检查 staging 目录权限与插件根结构");
        runQuietly(host, "rmdir " + shellQuote(pluginRoot));
    }

    private void removeMacOsJunk(Host host, String staging) {
        execOk(host, InstanceArchiveImportService.Phase.EXTRACT,
                "find " + shellQuote(staging) + " -mindepth 1 "
                        + "-name " + shellQuote(MACOS_JUNK_ROOT)
                        + " -type d -prune -exec rm -rf {} +"
                        + " ; find " + shellQuote(staging) + " -mindepth 1 "
                        + "-name " + shellQuote(MACOS_JUNK_FILE)
                        + " -type f -exec rm -f {} +",
                staging,
                CommandCategory.HOST_EXTRACT,
                "检查 staging 目录权限后重试");
    }

    private void requireNoLinks(Host host, String containerId, String staging) {
        SshUtil.CommandResult links = execPath(host, containerId,
                "find " + shellQuote(staging) + " -type l -print -quit");
        if (links == null || !links.isSuccess()
                || (links.getOutput() != null && !links.getOutput().isBlank())) {
            throw archiveFailure(InstanceArchiveImportService.Phase.VALIDATE,
                    staging, CommandCategory.VALIDATION,
                    links == null ? null : links.getExitCode(),
                    "移除归档中的符号链接后重试",
                    links == null ? "校验符号链接的 SSH 命令无响应"
                            : "staging 包含符号链接: " + safeOutput(links));
        }
        SshUtil.CommandResult hardLinks = execPath(host, containerId,
                "find " + shellQuote(staging) + " -type f -links +1 -print -quit");
        if (hardLinks == null || !hardLinks.isSuccess()
                || (hardLinks.getOutput() != null && !hardLinks.getOutput().isBlank())) {
            throw archiveFailure(InstanceArchiveImportService.Phase.VALIDATE,
                    staging, CommandCategory.VALIDATION,
                    hardLinks == null ? null : hardLinks.getExitCode(),
                    "移除归档中的硬链接后重试",
                    hardLinks == null ? "校验硬链接的 SSH 命令无响应"
                            : "staging 包含硬链接: " + safeOutput(hardLinks));
        }
    }

    private List<String> listFiles(Host host, String containerId, String staging) {
        SshUtil.CommandResult result = execPath(host, containerId,
                "cd " + shellQuote(staging)
                        + " && find . -type f -print | sed 's|^\\./||' | sort");
        if (result == null || !result.isSuccess()) {
            throw archiveFailure(InstanceArchiveImportService.Phase.VALIDATE,
                    staging, CommandCategory.VALIDATION,
                    result == null ? null : result.getExitCode(),
                    "检查 staging 目录权限后重试",
                    result == null ? "读取文件清单的 SSH 命令无响应"
                            : "无法读取 staging 文件清单: " + safeError(result));
        }
        return safeOutput(result).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private long sumBytes(Host host, String containerId, String staging) {
        SshUtil.CommandResult result = execPath(host, containerId,
                "cd " + shellQuote(staging)
                        + " && find . -type f -exec stat -c '%s' {} + 2>/dev/null "
                        + "| awk '{sum += $1} END {print sum + 0}'");
        if (result == null || !result.isSuccess()) {
            throw archiveFailure(InstanceArchiveImportService.Phase.VALIDATE,
                    staging, CommandCategory.VALIDATION,
                    result == null ? null : result.getExitCode(),
                    "检查 staging 文件权限后重试",
                    result == null ? "统计 staging 字节数的 SSH 命令无响应"
                            : "无法统计 staging 字节数: " + safeError(result));
        }
        String output = safeOutput(result);
        try {
            return Long.parseLong(output);
        } catch (NumberFormatException e) {
            throw archiveFailure(InstanceArchiveImportService.Phase.VALIDATE,
                    staging, CommandCategory.VALIDATION, result.getExitCode(),
                    "检查 stat/awk 是否可用后重试",
                    "staging 字节数输出无法解析: " + output);
        }
    }

    private void requireRequiredFiles(Host host, String containerId, String staging,
                                      String[] requiredFiles) {
        if (requiredFiles == null) {
            return;
        }
        for (String relative : requiredFiles) {
            if (relative == null || relative.isBlank()
                    || relative.contains("..") || relative.startsWith("/")) {
                throw archiveFailure(InstanceArchiveImportService.Phase.VALIDATE,
                        staging, CommandCategory.VALIDATION, null,
                        "修正 requiredFiles 后重试",
                        "非法关键文件路径: " + relative);
            }
            String requiredPath = staging + "/" + relative;
            SshUtil.CommandResult result = execPath(host, containerId,
                    "test -e " + shellQuote(requiredPath)
                            + " && echo EXISTS || echo MISSING");
            if (result == null || !result.isSuccess()
                    || !"EXISTS".equals(safeOutput(result))) {
                throw new InstanceArchiveImportException(
                        InstanceArchiveImportService.Phase.VALIDATE,
                        staging, CommandCategory.VALIDATION,
                        result == null ? null : result.getExitCode(),
                        "确认归档完整且关键文件存在后重试",
                        "ARCHIVE_REQUIRED_FILE_MISSING",
                        "缺少关键文件: " + relative, null);
            }
            SshUtil.CommandResult readable = execPath(host, containerId,
                    "test -r " + shellQuote(requiredPath)
                            + " && echo READABLE || echo UNREADABLE");
            if (readable == null || !readable.isSuccess()
                    || !"READABLE".equals(safeOutput(readable))) {
                throw new InstanceArchiveImportException(
                        InstanceArchiveImportService.Phase.VALIDATE,
                        staging, CommandCategory.VALIDATION,
                        readable == null ? null : readable.getExitCode(),
                        "修正关键文件权限后重试",
                        "ARCHIVE_REQUIRED_FILE_UNREADABLE",
                        "关键文件不可读: " + relative, null);
            }
        }
    }

    private void requireReadableCriticalFiles(Host host, String containerId, String staging,
                                              String[] extensions) {
        String extensionPredicate = criticalExtensionPredicate(extensions);
        if (extensionPredicate.isEmpty()) {
            return;
        }
        SshUtil.CommandResult emptyFiles = execPath(host, containerId,
                "find " + shellQuote(staging) + " -type f "
                        + extensionPredicate + " "
                        + "-size 0c -print");
        if (emptyFiles == null || !emptyFiles.isSuccess()) {
            throw archiveFailure(InstanceArchiveImportService.Phase.VALIDATE,
                    staging, CommandCategory.VALIDATION,
                    emptyFiles == null ? null : emptyFiles.getExitCode(),
                    "检查 staging 文件权限后重试",
                    emptyFiles == null ? "校验关键文件内容的 SSH 命令无响应"
                            : "无法校验关键文件内容: " + safeError(emptyFiles));
        }
        String emptyPaths = safeOutput(emptyFiles);
        if (!emptyPaths.isBlank()) {
            throw new InstanceArchiveImportException(
                    InstanceArchiveImportService.Phase.VALIDATE,
                    staging, CommandCategory.VALIDATION,
                    emptyFiles.getExitCode(),
                    "移除空的关键文件或修复归档后重试",
                    "ARCHIVE_REQUIRED_FILE_EMPTY",
                    "关键文件是空文件: " + emptyPaths.lines().limit(3)
                            .reduce((left, right) -> left + ", " + right).orElse(""), null);
        }

        SshUtil.CommandResult unreadable = execPath(host, containerId,
                "find " + shellQuote(staging) + " -type f "
                        + extensionPredicate + " "
                        + "-not -readable -print");
        if (unreadable == null || !unreadable.isSuccess()) {
            throw archiveFailure(InstanceArchiveImportService.Phase.VALIDATE,
                    staging, CommandCategory.VALIDATION,
                    unreadable == null ? null : unreadable.getExitCode(),
                    "检查 staging 文件权限后重试",
                    unreadable == null ? "校验关键文件权限的 SSH 命令无响应"
                            : "无法校验关键文件权限: " + safeError(unreadable));
        }
        String unreadablePaths = safeOutput(unreadable);
        if (!unreadablePaths.isBlank()) {
            throw new InstanceArchiveImportException(
                    InstanceArchiveImportService.Phase.VALIDATE,
                    staging, CommandCategory.VALIDATION,
                    unreadable.getExitCode(),
                    "修正关键文件权限后重试",
                    "ARCHIVE_REQUIRED_FILE_UNREADABLE",
                    "关键文件不可读: " + unreadablePaths.lines().limit(3)
                            .reduce((left, right) -> left + ", " + right).orElse(""), null);
        }
    }

    private static String criticalExtensionPredicate(String[] extensions) {
        if (extensions == null || extensions.length == 0) {
            return "";
        }
        StringBuilder predicate = new StringBuilder("\\(");
        boolean first = true;
        for (String raw : extensions) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String extension = raw.trim();
            if (extension.startsWith(".")) {
                extension = extension.substring(1);
            }
            if (extension.isEmpty() || !extension.matches("[A-Za-z0-9]+")
                    || extension.contains("..")) {
                throw archiveFailure(
                        InstanceArchiveImportService.Phase.VALIDATE, extension,
                        CommandCategory.VALIDATION, null,
                        "修正 criticalFileExtensions 后重试",
                        "非法关键文件扩展名: " + raw);
            }
            if (!first) {
                predicate.append(" -o");
            }
            predicate.append(" -iname ").append(shellQuote("*." + extension));
            first = false;
        }
        return first ? "" : predicate.append(" \\)").toString();
    }

    /**
     * 回收进程异常退出后遗留的宿主临时目录。这里只按固定前缀删除，
     * 每个主机独立 best-effort，失败不影响应用启动。
     */
    @Override
    public void afterSingletonsInstantiated() {
        List<Host> hosts;
        try {
            hosts = hostMapper.selectList(null);
        } catch (Exception e) {
            log.warn("查询主机列表失败（跳过归档临时目录启动清理）: {}", e.getMessage());
            return;
        }
        if (hosts == null || hosts.isEmpty()) {
            return;
        }
        int cleaned = 0;
        int failed = 0;
        for (Host host : hosts) {
            try {
                SshUtil.CommandResult result = exec(host,
                        "find /tmp -mindepth 1 -maxdepth 1 -type d "
                                + "-name " + shellQuote(basename(HOST_TEMP_ROOT_PREFIX) + "*")
                                + " -exec rm -rf {} +");
                if (result == null || !result.isSuccess()) {
                    failed++;
                } else {
                    cleaned++;
                }
            } catch (Exception e) {
                failed++;
                log.debug("清理主机归档临时目录失败 hostId={}: {}", host.getId(), e.getMessage());
            }
        }
        log.info("归档临时目录启动清理完成: total={}, cleaned={}, failed={}",
                hosts.size(), cleaned, failed);
    }

    private boolean atomicMoveOnHost(Host host, String staging, String target, String backup) {
        execOk(host, InstanceArchiveImportService.Phase.ATOMIC_MOVE,
                "mkdir -p " + shellQuote(parentOf(target)), parentOf(target),
                CommandCategory.FILESYSTEM_MOVE,
                "检查目标目录权限");
        SshUtil.CommandResult result = exec(host,
                "if [ -e " + shellQuote(target) + " ]; then mv -f "
                        + shellQuote(target) + " " + shellQuote(backup) + " && echo BACKED_UP; fi"
                        + " && mv -f " + shellQuote(staging) + " " + shellQuote(target));
        if (result == null || !result.isSuccess()) {
            runQuietly(host, "if [ -e " + shellQuote(backup) + " ]; then mv -f "
                    + shellQuote(backup) + " " + shellQuote(target) + "; fi");
            throw archiveFailure(InstanceArchiveImportService.Phase.ATOMIC_MOVE,
                    target, CommandCategory.FILESYSTEM_MOVE,
                    result == null ? null : result.getExitCode(),
                    "检查目标目录权限与同文件系统限制",
                    result == null ? "SSH 命令无响应" : "原子落位失败: " + safeError(result));
        }
        return safeOutput(result).contains("BACKED_UP");
    }

    private boolean atomicMoveInContainer(Host host, String containerId,
                                          String staging, String target, String backup) {
        SshUtil.CommandResult result = execDocker(host, containerId,
                "mkdir -p " + shellQuote(parentOf(target))
                        + " && if [ -e " + shellQuote(target) + " ]; then mv -f "
                        + shellQuote(target) + " " + shellQuote(backup)
                        + " && echo BACKED_UP; fi"
                        + " && mv -f " + shellQuote(staging) + " " + shellQuote(target));
        if (result == null || !result.isSuccess()) {
            runDockerQuietly(host, containerId,
                    "if [ -e " + shellQuote(backup) + " ]; then mv -f "
                            + shellQuote(backup) + " " + shellQuote(target) + "; fi");
            throw archiveFailure(InstanceArchiveImportService.Phase.ATOMIC_MOVE,
                    target, CommandCategory.CONTAINER_COPY,
                    result == null ? null : result.getExitCode(),
                    "确认容器运行且目标目录可写",
                    result == null ? "容器命令无响应"
                            : "容器内原子落位失败: " + safeError(result));
        }
        return safeOutput(result).contains("BACKED_UP");
    }

    private MountMatch detectMount(long hostId, String containerId, String containerPath) {
        Host host = requireHost(hostId);
        SshUtil.CommandResult result = exec(host,
                "docker inspect " + shellQuote(containerId)
                        + " --format '{{json .Mounts}}'");
        if (result == null || !result.isSuccess()) {
            throw archiveFailure(InstanceArchiveImportService.Phase.IMPORT,
                    containerPath, CommandCategory.CONTAINER_COPY,
                    result == null ? null : result.getExitCode(),
                    "确认实例容器运行后重试",
                    result == null ? "docker inspect 命令无响应"
                            : "无法解析容器挂载信息: " + safeError(result));
        }
        try {
            JsonNode mounts = objectMapper.readTree(result.getOutput());
            if (mounts != null && mounts.isArray()) {
                for (JsonNode mount : mounts) {
                    String type = mount.path("Type").asText("");
                    String destination = mount.path("Destination").asText("");
                    String source = mount.path("Source").asText("");
                    // 命名卷的 Source 是 Docker 卷名而非可直接写入的宿主绝对路径。
                    // 只有 bind mount 可以安全映射；其余统一走一次目录级 docker cp。
                    if ("bind".equals(type)
                            && !destination.isEmpty()
                            && isSafeMappedPath(source)
                            && (containerPath.equals(destination)
                            || containerPath.startsWith(destination + "/"))) {
                        return new MountMatch(source, destination);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw archiveFailure(InstanceArchiveImportService.Phase.IMPORT,
                    containerPath, CommandCategory.CONTAINER_COPY, null,
                    "确认 docker inspect 输出可解析",
                    "容器挂载信息解析失败: " + e.getMessage());
        }
    }

    private SshUtil.CommandResult execPath(Host host, String containerId, String inner) {
        return containerId == null ? exec(host, inner) : execDocker(host, containerId, inner);
    }

    private SshUtil.CommandResult exec(Host host, String command) {
        return fileService.executeCommand(host.getId(), command, SSH_TIMEOUT_MS);
    }

    private SshUtil.CommandResult execOk(Host host, InstanceArchiveImportService.Phase phase,
                                         String command, String displayPath, CommandCategory category,
                                         String suggestion) {
        SshUtil.CommandResult result = exec(host, command);
        if (result == null || !result.isSuccess()) {
            throw archiveFailure(phase, displayPath, category,
                    result == null ? null : result.getExitCode(),
                    suggestion,
                    result == null ? "SSH 命令无响应" : safeError(result));
        }
        return result;
    }

    private SshUtil.CommandResult execDocker(Host host, String containerId, String inner) {
        String escaped = inner.replace("'", "'\\''");
        return exec(host, "docker exec " + shellQuote(containerId)
                + " sh -c '" + escaped + "'");
    }

    private void execDockerOk(Host host, String containerId, String inner, String displayPath,
                              InstanceArchiveImportService.Phase phase) {
        SshUtil.CommandResult result = execDocker(host, containerId, inner);
        if (result == null || !result.isSuccess()) {
            throw archiveFailure(phase,
                    displayPath, CommandCategory.CONTAINER_COPY,
                    result == null ? null : result.getExitCode(),
                    "确认实例容器运行且目标目录可写",
                    result == null ? "容器命令无响应" : safeError(result));
        }
    }

    private void runQuietly(Host host, String command) {
        try {
            exec(host, command);
        } catch (Exception e) {
            log.debug("归档导入清理命令失败 hostId={}: {}", host.getId(), e.getMessage());
        }
    }

    private void runDockerQuietly(Host host, String containerId, String inner) {
        try {
            execDocker(host, containerId, inner);
        } catch (Exception e) {
            log.debug("归档导入容器清理命令失败 hostId={}: {}", host.getId(), e.getMessage());
        }
    }

    private Host requireHost(long hostId) {
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            throw new IllegalArgumentException("主机不存在: " + hostId);
        }
        return host;
    }

    private FileRoute resolveTarget(InstanceArchiveImportRequest request) {
        return instanceFileService.resolveRoute(
                request.getInstanceId(), request.getTargetRelativePath());
    }

    private static String mapToHostPath(MountMatch mount, String containerPath) {
        String remainder = containerPath.substring(mount.destinationPrefix.length());
        if (remainder.startsWith("/")) {
            remainder = remainder.substring(1);
        }
        return mount.source + (remainder.isEmpty() ? "" : "/" + remainder);
    }

    private static boolean isSafeMappedPath(String path) {
        return path != null && path.startsWith("/") && !path.contains("..");
    }

    private static String parentOf(String path) {
        int idx = path.lastIndexOf('/');
        return idx > 0 ? path.substring(0, idx) : "/";
    }

    private static String basename(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String safeOutput(SshUtil.CommandResult result) {
        return result.getOutput() == null ? "" : result.getOutput().trim();
    }

    private static String safeError(SshUtil.CommandResult result) {
        return result.getError() == null ? "" : result.getError();
    }

    private static InstanceArchiveImportException archiveFailure(
            InstanceArchiveImportService.Phase phase, String path,
            CommandCategory category, Integer exitCode, String suggestion, String detail) {
        return archiveFailure(phase, path, category, exitCode, suggestion, detail, null);
    }

    private static InstanceArchiveImportException archiveFailure(
            InstanceArchiveImportService.Phase phase, String path,
            CommandCategory category, Integer exitCode, String suggestion, String detail,
            Throwable cause) {
        return new InstanceArchiveImportException(phase, path, category, exitCode,
                suggestion, detail, cause);
    }

    private record MountMatch(String source, String destinationPrefix) {
    }

    private record StagingPlan(FileRoute targetRoute, String instanceStaging,
                               String hostStaging, String hostTarget, boolean containerStaging,
                               MountMatch mount) {
    }
}
