package com.gameplatform.archive;

import com.gameplatform.plugin.service.InstanceArchiveImportException;
import com.gameplatform.plugin.service.InstanceArchiveImportException.CommandCategory;
import com.gameplatform.plugin.service.InstanceArchiveImportListener;
import com.gameplatform.plugin.service.InstanceArchiveImportRequest;
import com.gameplatform.plugin.service.InstanceArchiveImportResult;
import com.gameplatform.plugin.service.InstanceArchiveImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * 单插件归档导入服务（ADR-0031）。
 *
 * <p>平台侧只做清单校验与一次原始归档上传；解压、目录导入和原子落位由执行器
 * 在目标主机/容器内完成。失败时执行器负责恢复旧目录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstanceArchiveImportServiceImpl implements InstanceArchiveImportService {

    private final ZipArchiveManifestReader manifestReader;
    private final InstanceArchiveImportExecutor executor;

    @Override
    public InstanceArchiveImportResult importArchive(InstanceArchiveImportRequest request,
                                                     InstanceArchiveImportListener listener) {
        validateRequest(request, listener);
        Map<Phase, Long> durations = new EnumMap<>(Phase.class);
        String hostArchive = null;
        Phase phase = Phase.UPLOAD;
        boolean cleanupStarted = false;
        try {
            checkCancelled(listener, phase, request.getTargetRelativePath());
            enter(listener, phase, "读取并校验 ZIP 清单");
            ArchiveManifest manifest = readManifest(request, listener);
            long phaseStarted = System.currentTimeMillis();

            hostArchive = executor.uploadArchive(request, Path.of(request.getLocalArchivePath()), listener);
            durations.put(phase, System.currentTimeMillis() - phaseStarted);

            phase = Phase.EXTRACT;
            checkCancelled(listener, phase, request.getStagingRelativePath());
            phaseStarted = System.currentTimeMillis();
            enter(listener, phase, "上传完成，开始在目标主机解压");
            InstanceArchiveImportExecutor.StagingHandle staging =
                    executor.extractToStaging(request, hostArchive, listener);
            durations.put(phase, System.currentTimeMillis() - phaseStarted);

            phase = Phase.IMPORT;
            checkCancelled(listener, phase, request.getTargetRelativePath());
            phaseStarted = System.currentTimeMillis();
            enter(listener, phase, "解压完成，导入实例目录");
            executor.importToStaging(request, staging, listener);
            durations.put(phase, System.currentTimeMillis() - phaseStarted);

            phase = Phase.VALIDATE;
            checkCancelled(listener, phase, request.getTargetRelativePath());
            phaseStarted = System.currentTimeMillis();
            enter(listener, phase, "校验落位后的文件数量与关键文件");
            InstanceArchiveImportExecutor.ImportedTree imported =
                    executor.validateStaging(request, staging, listener);
            validateImported(manifest, imported, request);
            durations.put(phase, System.currentTimeMillis() - phaseStarted);

            phase = Phase.ATOMIC_MOVE;
            phaseStarted = System.currentTimeMillis();
            enter(listener, phase, "准备同卷原子落位");
            checkCancelled(listener, phase, request.getTargetRelativePath());
            InstanceArchiveImportExecutor.AtomicMoveHandle move =
                    executor.atomicMove(request, staging, listener);
            if (listener.isCancelled()) {
                try {
                    executor.rollbackMove(move, listener);
                } catch (Exception rollbackError) {
                    throw failure(Phase.ATOMIC_MOVE, request.getTargetRelativePath(),
                            CommandCategory.FILESYSTEM_MOVE, null,
                            "旧目录恢复失败，请检查 " + move.backup() + " 后手动恢复",
                            "落位后取消且回滚失败: " + rollbackError.getMessage(), rollbackError);
                }
                checkCancelled(listener, phase, request.getTargetRelativePath());
            }
            commit(request, manifest, imported, move, listener);
            durations.put(phase, System.currentTimeMillis() - phaseStarted);

            phase = Phase.CLEANUP;
            phaseStarted = System.currentTimeMillis();
            enter(listener, phase, "清理临时目录");
            checkCancelled(listener, phase, request.getTargetRelativePath());
            cleanupStarted = true;
            executor.cleanup(request, hostArchive, listener);
            hostArchive = null;
            durations.put(phase, System.currentTimeMillis() - phaseStarted);
            listener.onProgress(Phase.CLEANUP, 100, "导入完成");
            return InstanceArchiveImportResult.builder()
                    .pluginName(manifest.pluginName())
                    .fileCount(imported.fileCount())
                    .totalBytes(imported.totalBytes())
                    .relativePaths(imported.relativePaths())
                    .phaseDurationsMs(Map.copyOf(durations))
                    .build();
        } catch (InstanceArchiveImportException e) {
            throw e;
        } catch (ArchiveManifestException e) {
            throw failure(Phase.UPLOAD, request.getLocalArchivePath(),
                    CommandCategory.LOCAL_ARCHIVE_READ, null,
                    "检查 ZIP 是否为单插件归档并确认包含 " + request.getStructureRoot() + " 目录",
                    e.getFailureCode(), e);
        } catch (Exception e) {
            throw failure(phase, phase == Phase.UPLOAD ? request.getLocalArchivePath()
                            : request.getTargetRelativePath(),
                    categoryFor(phase), null,
                    suggestionFor(phase), e);
        } finally {
            try {
                if (hostArchive != null && !cleanupStarted) {
                    cleanupStarted = true;
                    executor.cleanup(request, hostArchive, listener);
                }
            } catch (Exception cleanupError) {
                log.warn("归档导入临时目录清理失败 instanceId={}: {}",
                        request.getInstanceId(), cleanupError.getMessage());
            }
        }
    }

    private void commit(InstanceArchiveImportRequest request,
                        ArchiveManifest manifest,
                        InstanceArchiveImportExecutor.ImportedTree imported,
                        InstanceArchiveImportExecutor.AtomicMoveHandle move,
                        InstanceArchiveImportListener listener) {
        if (request.getCommitAction() == null) {
            try {
                executor.markCommitted(move, listener);
            } catch (Exception commitError) {
                rollbackAfterCommitFailure(request, move, listener, commitError);
                return;
            }
            executor.finalizeMove(move, listener);
            return;
        }
        try {
            request.getCommitAction().afterAtomicMove(
                    InstanceArchiveImportResult.builder()
                            .pluginName(manifest.pluginName())
                            .fileCount(imported.fileCount())
                            .totalBytes(imported.totalBytes())
                            .relativePaths(imported.relativePaths())
                            .build());
            executor.markCommitted(move, listener);
        } catch (Exception commitError) {
            rollbackAfterCommitFailure(request, move, listener, commitError);
        }
        executor.finalizeMove(move, listener);
    }

    private void rollbackAfterCommitFailure(InstanceArchiveImportRequest request,
                                            InstanceArchiveImportExecutor.AtomicMoveHandle move,
                                            InstanceArchiveImportListener listener,
                                            Exception commitError) {
        try {
            executor.rollbackMove(move, listener);
        } catch (Exception rollbackError) {
            commitError.addSuppressed(rollbackError);
            throw failure(Phase.ATOMIC_MOVE, request.getTargetRelativePath(),
                    CommandCategory.FILESYSTEM_MOVE, null,
                    "旧目录恢复失败，请检查 " + move.backup() + " 后手动恢复",
                    "提交动作失败且回滚失败: " + commitError.getMessage(), commitError);
        }
        throw failure(Phase.ATOMIC_MOVE, request.getTargetRelativePath(),
                CommandCategory.FILESYSTEM_MOVE, null,
                "检查实例目录权限后重试",
                "提交动作失败，旧目录已恢复: " + commitError.getMessage(), commitError);
    }

    private ArchiveManifest readManifest(InstanceArchiveImportRequest request,
                                         InstanceArchiveImportListener listener) {
        Path local = Path.of(request.getLocalArchivePath());
        if (!Files.isRegularFile(local)) {
            throw new ArchiveManifestException("本地归档不存在: " + local);
        }
        listener.onProgress(Phase.UPLOAD, 5, "校验 ZIP 条目安全与大小上限");
        return manifestReader.read(local, request.getMaxEntries(), request.getMaxExtractBytes(),
                request.getExpectedPluginName(), request.getStructureRoot());
    }

    private void validateImported(ArchiveManifest manifest,
                                  InstanceArchiveImportExecutor.ImportedTree imported,
                                  InstanceArchiveImportRequest request) {
        String targetPath = request.getTargetRelativePath();
        if (imported.fileCount() > request.getMaxEntries()) {
            throw new InstanceArchiveImportException(Phase.VALIDATE, targetPath,
                    CommandCategory.VALIDATION, null,
                    "移除多余文件后重试",
                    "ARCHIVE_TOO_MANY_ENTRIES",
                    "解压后文件条目数超过请求上限 " + request.getMaxEntries()
                            + "，实际 " + imported.fileCount(), null);
        }
        if (imported.totalBytes() > request.getMaxExtractBytes()) {
            throw new InstanceArchiveImportException(Phase.VALIDATE, targetPath,
                    CommandCategory.VALIDATION, null,
                    "确认归档来源可信并移除超大文件后重试",
                    "ARCHIVE_TOO_LARGE",
                    "解压后总字节数超过请求上限 " + request.getMaxExtractBytes()
                            + "，实际 " + imported.totalBytes(), null);
        }
        if (imported.fileCount() != manifest.fileCount()) {
            throw new InstanceArchiveImportException(Phase.VALIDATE, targetPath,
                    CommandCategory.VALIDATION, null,
                    "检查目标磁盘空间与目录权限后重试",
                    "文件数不一致，期望 " + manifest.fileCount() + "，实际 " + imported.fileCount(), null);
        }
        if (imported.totalBytes() != manifest.totalBytes()) {
            throw new InstanceArchiveImportException(Phase.VALIDATE, targetPath,
                    CommandCategory.VALIDATION, null,
                    "检查目标磁盘空间与复制过程日志后重试",
                    "总字节数不一致，期望 " + manifest.totalBytes() + "，实际 " + imported.totalBytes(), null);
        }
    }

    private void validateRequest(InstanceArchiveImportRequest request,
                                 InstanceArchiveImportListener listener) {
        if (request == null || listener == null) {
            throw new IllegalArgumentException("request/listener 不能为空");
        }
        if (request.getInstanceId() == null || request.getLocalArchivePath() == null
                || request.getStagingRelativePath() == null || request.getTargetRelativePath() == null
                || request.getStructureRoot() == null || request.getStructureRoot().isBlank()) {
            throw new IllegalArgumentException(
                    "instanceId/localArchivePath/staging/target/structureRoot 不能为空");
        }
        String staging = request.getStagingRelativePath().replace('\\', '/');
        String target = request.getTargetRelativePath().replace('\\', '/');
        if (staging.contains("..") || target.contains("..")
                || staging.startsWith("/") || target.startsWith("/")) {
            throw new IllegalArgumentException("staging/target 必须是安全的实例相对路径");
        }
        String structureRoot = request.getStructureRoot().replace('\\', '/');
        if (structureRoot.startsWith("/") || structureRoot.endsWith("/")
                || structureRoot.contains("..")) {
            throw new IllegalArgumentException("structureRoot 必须是安全的相对路径");
        }
        String commitMarker = request.getCommitMarkerFileName();
        if (commitMarker != null
                && (commitMarker.isBlank()
                || commitMarker.contains("/")
                || commitMarker.contains("\\")
                || commitMarker.contains(".."))) {
            throw new IllegalArgumentException("commitMarkerFileName 必须是安全的文件名");
        }
        String targetParent = parentOf(target);
        String stagingParent = parentOf(staging);
        if (!stagingParent.equals(targetParent)
                || staging.equals(target)
                || staging.startsWith(target + "/")
                || target.startsWith(staging + "/")) {
            throw new IllegalArgumentException("staging 必须位于目标目录同一父目录下");
        }
        if (request.getMaxEntries() <= 0 || request.getMaxExtractBytes() <= 0) {
            throw new IllegalArgumentException("maxEntries/maxExtractBytes 必须大于 0");
        }
    }

    private static void enter(InstanceArchiveImportListener listener, Phase phase, String message) {
        listener.onPhaseStart(phase, phase.getStartPercent(), message);
        listener.onProgress(phase, phase.getStartPercent(), message);
    }

    private static void checkCancelled(InstanceArchiveImportListener listener,
                                       Phase phase, String path) {
        if (listener.isCancelled()) {
            throw new InstanceArchiveImportException(phase, path,
                    CommandCategory.VALIDATION, null,
                    "重新提交安装任务", "任务已取消", null);
        }
    }

    private static CommandCategory categoryFor(Phase phase) {
        return switch (phase) {
            case UPLOAD -> CommandCategory.HOST_UPLOAD;
            case EXTRACT -> CommandCategory.HOST_EXTRACT;
            case IMPORT, ATOMIC_MOVE -> CommandCategory.CONTAINER_COPY;
            case VALIDATE -> CommandCategory.VALIDATION;
            case CLEANUP -> CommandCategory.CLEANUP;
        };
    }

    private static String suggestionFor(Phase phase) {
        return switch (phase) {
            case UPLOAD -> "检查主机 SSH/SFTP 连通性和 /tmp 剩余空间";
            case EXTRACT -> "确认主机有 unzip/bsdtar，或 Docker 可拉取 platform-tools";
            case IMPORT -> "确认实例容器运行且目标目录可写";
            case VALIDATE -> "检查磁盘空间和归档完整性";
            case ATOMIC_MOVE -> "检查目标目录权限与同文件系统限制";
            case CLEANUP -> "可在主机上手动清理 .install-* 临时目录";
        };
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash) : "";
    }

    private static InstanceArchiveImportException failure(Phase phase, String path,
                                                           CommandCategory category,
                                                           Integer exitCode, String suggestion,
                                                           Exception cause) {
        return new InstanceArchiveImportException(phase, path, category, exitCode,
                suggestion, cause.getMessage(), cause);
    }

    private static InstanceArchiveImportException failure(Phase phase, String path,
                                                           CommandCategory category,
                                                           Integer exitCode, String suggestion,
                                                           String failureCode,
                                                           Exception cause) {
        return new InstanceArchiveImportException(phase, path, category, exitCode,
                suggestion, failureCode, cause.getMessage(), cause);
    }
}
