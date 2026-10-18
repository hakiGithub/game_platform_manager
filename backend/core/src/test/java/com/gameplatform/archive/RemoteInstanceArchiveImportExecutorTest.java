package com.gameplatform.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.patch.HostCapabilityProber;
import com.gameplatform.patch.PatchInstallExecutor;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.plugin.service.AbstractInstanceFileService;
import com.gameplatform.plugin.service.AbstractInstanceFileService.FileRoute;
import com.gameplatform.plugin.service.InstanceArchiveImportException;
import com.gameplatform.plugin.service.InstanceArchiveImportListener;
import com.gameplatform.plugin.service.InstanceArchiveImportRequest;
import com.gameplatform.plugin.service.InstanceArchiveImportService;
import com.gameplatform.service.FileService;
import com.gameplatform.util.SshUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("远程实例归档导入执行器")
class RemoteInstanceArchiveImportExecutorTest {

    @TempDir
    Path tempDir;

    private AbstractInstanceFileService instanceFileService;
    private FileService fileService;
    private HostMapper hostMapper;
    private HostCapabilityProber capabilityProber;
    private PatchInstallExecutor patchInstallExecutor;
    private RemoteInstanceArchiveImportExecutor executor;
    private CommandRecorder commands;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(AbstractInstanceFileService.class);
        fileService = mock(FileService.class);
        hostMapper = mock(HostMapper.class);
        capabilityProber = mock(HostCapabilityProber.class);
        patchInstallExecutor = mock(PatchInstallExecutor.class);
        commands = new CommandRecorder();

        Host host = new Host();
        host.setId(1L);
        when(hostMapper.selectById(1L)).thenReturn(host);
        when(capabilityProber.probe(1L)).thenReturn(new HostCapabilities());
        when(fileService.executeCommand(eq(1L), anyString(), anyLong()))
                .thenAnswer(invocation -> commands.execute(invocation.getArgument(1)));

        executor = new RemoteInstanceArchiveImportExecutor(instanceFileService, fileService,
                hostMapper, capabilityProber, patchInstallExecutor, new ObjectMapper());
    }

    @Test
    @DisplayName("Native 目标在固定 staging 解压、校验并落位")
    void nativeImportUsesSameStagingAcrossPhases() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        FileRoute staging = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/.install-p-1", "/srv/game/plugins_store/.install-p-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("mkdir -p", result(0, ""));
        commands.add("test -d '/srv/game/plugins_store/.install-p-1/left4dead2'",
                result(0, "ROOT\n"));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -mindepth", result(0, ""));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -type l", result(0, ""));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -type f -links", result(0, ""));
        commands.add("cd '/srv/game/plugins_store/.install-p-1' && find . -type f -exec stat", result(0, "5\n"));
        commands.add("cd '/srv/game/plugins_store/.install-p-1' && find . -type f", result(0, "a.smx\n"));
        commands.add("iname '*.so'", result(0, ""));
        commands.add("-not -readable", result(0, ""));
        commands.add("test -e '/srv/game/plugins_store/.install-p-1/left4dead2", result(0, "EXISTS\n"));
        commands.add("test -r '/srv/game/plugins_store/.install-p-1/left4dead2", result(0, "READABLE\n"));
        commands.add("if [ -e '/srv/game/plugins_store/p'", result(0, "BACKED_UP\n"));
        commands.add("rm -rf '/srv/game/plugins_store/p.rollback-", result(0, ""));

        InstanceArchiveImportExecutor.StagingHandle stagingRoot = executor.extractToStaging(
                request(), "/tmp/gp-plugin-install-1/archive.zip",
                listener());
        assertEquals("plugins_store/.install-p-1", stagingRoot.instanceStaging());

        InstanceArchiveImportExecutor.ImportedTree imported =
                executor.validateStaging(request(), stagingRoot, listener());
        assertEquals(1, imported.fileCount());
        assertEquals(5, imported.totalBytes());

        InstanceArchiveImportExecutor.AtomicMoveHandle move =
                executor.atomicMove(request(), stagingRoot, listener());
        executor.finalizeMove(move, listener());
        assertTrue(commands.contains("mv -f '/srv/game/plugins_store/.install-p-1' '/srv/game/plugins_store/p'"));
        assertFalse(commands.contains("gp-plugin-install-staging-"));
        assertTrue(commands.contains("rm -rf '/srv/game/plugins_store/p.rollback-"));
    }

    @Test
    @DisplayName("解压后清理 macOS 资源分支目录与 .DS_Store")
    void removesMacOsJunkBeforeImport() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        FileRoute staging = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/.install-p-1", "/srv/game/plugins_store/.install-p-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("mkdir -p", result(0, ""));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -mindepth 1 -name '__MACOSX'",
                result(0, ""));
        commands.add("test -d '/srv/game/plugins_store/.install-p-1/left4dead2'",
                result(0, "ROOT\n"));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -mindepth", result(0, ""));

        executor.extractToStaging(request(), "/tmp/gp-plugin-install-1/archive.zip",
                listener());

        assertTrue(commands.contains("name '__MACOSX'"));
        assertTrue(commands.contains("name '.DS_Store'"));
    }

    @Test
    @DisplayName("插件结构根作为位置参数传入，不拼入 shell 脚本")
    void structureRootIsPassedAsShellArgument() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        FileRoute staging = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/.install-p-1", "/srv/game/plugins_store/.install-p-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("mkdir -p", result(0, ""));
        commands.add("name '__MACOSX'", result(0, ""));
        commands.add("test -d '/srv/game/plugins_store/.install-p-1/", result(0, "ROOT\n"));
        String maliciousRoot = "left4dead2'$(touch /tmp/pwn)'";
        String quotedRoot = "'" + maliciousRoot.replace("'", "'\\''") + "'";
        InstanceArchiveImportRequest request = InstanceArchiveImportRequest.builder()
                .instanceId(7L)
                .localArchivePath(tempDir.resolve("archive.zip").toString())
                .targetRelativePath("plugins_store/p")
                .stagingRelativePath("plugins_store/.install-p-1")
                .structureRoot(maliciousRoot)
                .build();

        InstanceArchiveImportExecutor.StagingHandle root = executor.extractToStaging(
                request, "/tmp/gp-plugin-install-1/archive.zip", listener());

        assertFalse(root.containerStaging());
        assertTrue(commands.contains("-exec sh -c 'test -d \"$1/$2\" && echo \"$1\"' sh {} "
                + quotedRoot));
        assertFalse(commands.contains("-exec sh -c 'test -d \"$1/" + maliciousRoot));
    }

    @Test
    @DisplayName("挂载目录直接写宿主机挂载源，不向容器路径移动 staging")
    void mountedTargetWritesToHostSource() {
        FileRoute target = FileRoute.dockerRoute(7L, 1L, "docker",
                "plugins_store/p", "/data/plugins_store/p", "container-1");
        FileRoute staging = FileRoute.dockerRoute(7L, 1L, "docker",
                "plugins_store/.install-p-1", "/data/plugins_store/.install-p-1", "container-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("docker inspect", result(0,
                "[{\"Type\":\"bind\",\"Destination\":\"/data\",\"Source\":\"/srv/docker/data\"}]"));
        commands.add("mkdir -p", result(0, ""));
        commands.add("test -d '/srv/docker/data/plugins_store/.install-p-1/left4dead2'",
                result(0, "ROOT\n"));
        commands.add("find '/srv/docker/data/plugins_store/.install-p-1' -mindepth", result(0, ""));
        commands.add("find '/srv/docker/data/plugins_store/.install-p-1' -type l", result(0, ""));
        commands.add("find '/srv/docker/data/plugins_store/.install-p-1' -type f -links", result(0, ""));
        commands.add("cd '/srv/docker/data/plugins_store/.install-p-1' && find . -type f -exec stat", result(0, "5\n"));
        commands.add("cd '/srv/docker/data/plugins_store/.install-p-1' && find . -type f", result(0, "a.smx\n"));
        commands.add("iname '*.so'", result(0, ""));
        commands.add("-not -readable", result(0, ""));
        commands.add("test -e '/srv/docker/data/plugins_store/.install-p-1/left4dead2", result(0, "EXISTS\n"));
        commands.add("test -r '/srv/docker/data/plugins_store/.install-p-1/left4dead2", result(0, "READABLE\n"));
        commands.add("if [ -e '/srv/docker/data/plugins_store/p'", result(0, "BACKED_UP\n"));
        commands.add("rm -rf '/srv/docker/data/plugins_store/p.rollback-", result(0, ""));

        InstanceArchiveImportExecutor.StagingHandle stagingRoot = executor.extractToStaging(
                request(), "/tmp/gp-plugin-install-1/archive.zip",
                listener());
        executor.validateStaging(request(), stagingRoot, listener());
        InstanceArchiveImportExecutor.AtomicMoveHandle move =
                executor.atomicMove(request(), stagingRoot, listener());
        executor.finalizeMove(move, listener());

        assertTrue(commands.contains(
                "mv -f '/srv/docker/data/plugins_store/.install-p-1' '/srv/docker/data/plugins_store/p'"));
        assertFalse(commands.contains("mv -f '/srv/docker/data/plugins_store/.install-p-1' '/data/plugins_store/p'"));
        assertFalse(commands.contains("docker cp"));
        assertTrue(commands.contains("rm -rf '/srv/docker/data/plugins_store/p.rollback-"));
    }

    @Test
    @DisplayName("命名卷不误写宿主路径，回退一次目录级 docker cp")
    void namedVolumeFallsBackToDirectoryCopy() {
        FileRoute target = FileRoute.dockerRoute(7L, 1L, "docker",
                "plugins_store/p", "/data/plugins_store/p", "container-1");
        FileRoute staging = FileRoute.dockerRoute(7L, 1L, "docker",
                "plugins_store/.install-p-1", "/data/plugins_store/.install-p-1", "container-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("docker inspect", result(0,
                "[{\"Type\":\"volume\",\"Name\":\"l4d2-data\","
                        + "\"Destination\":\"/data\",\"Source\":\"/var/lib/docker/volumes/l4d2-data/_data\"}]"));
        commands.add("mkdir -p", result(0, ""));
        commands.add("test -d '/tmp/gp-plugin-install-1/staging/.install-p-1/left4dead2'",
                result(0, "ROOT\n"));
        commands.add("find '/tmp/gp-plugin-install-1/staging/.install-p-1' -mindepth", result(0, ""));
        commands.add("docker cp", result(0, ""));
        commands.add("docker exec", result(0, ""));

        InstanceArchiveImportExecutor.StagingHandle stagingRoot = executor.extractToStaging(
                request(), "/tmp/gp-plugin-install-1/archive.zip", listener());
        executor.importToStaging(request(), stagingRoot, listener());

        assertTrue(stagingRoot.containerStaging());
        assertEquals(1, commands.count("docker cp"));
        assertTrue(commands.contains(
                "docker cp '/tmp/gp-plugin-install-1/staging/.install-p-1/.' "
                        + "'container-1:/data/plugins_store/.install-p-1/'"));
        assertFalse(commands.contains("/var/lib/docker/volumes/l4d2-data"));
    }

    @Test
    @DisplayName("无法识别挂载时仅做一次目录级 docker cp")
    void unmountedTargetUsesOneDirectoryCopy() {
        FileRoute target = FileRoute.dockerRoute(7L, 1L, "docker",
                "plugins_store/p", "/data/plugins_store/p", "container-1");
        FileRoute staging = FileRoute.dockerRoute(7L, 1L, "docker",
                "plugins_store/.install-p-1", "/data/plugins_store/.install-p-1", "container-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("docker inspect", result(0, "[]"));
        commands.add("mkdir -p", result(0, ""));
        commands.add("test -d '/tmp/gp-plugin-install-1/staging/.install-p-1/left4dead2'",
                result(0, "ROOT\n"));
        commands.add("find '/tmp/gp-plugin-install-1/staging/.install-p-1' -mindepth", result(0, ""));
        commands.add("find . -type f -exec stat", result(0, "5\n"));
        commands.add("find . -type f", result(0, "a.smx\n"));
        commands.add("iname '*.so'", result(0, ""));
        commands.add("-not -readable", result(0, ""));
        commands.add("/data/plugins_store/.install-p-1/"
                + "left4dead2/addons/sourcemod/plugins/a.smx", result(0, "EXISTS\n"));
        commands.add("test -r '/data/plugins_store/.install-p-1/left4dead2", result(0, "READABLE\n"));
        commands.add("-type l -print -quit", result(0, ""));
        commands.add("-type f -links +1 -print -quit", result(0, ""));
        commands.add("docker cp", result(0, ""));
        commands.add("docker exec", result(0, ""));

        InstanceArchiveImportExecutor.StagingHandle stagingRoot = executor.extractToStaging(
                request(), "/tmp/gp-plugin-install-1/archive.zip",
                listener());
        executor.importToStaging(request(), stagingRoot, listener());

        assertEquals(1, commands.count("docker cp"));
        assertTrue(commands.contains(
                "docker cp '/tmp/gp-plugin-install-1/staging/.install-p-1/.' "
                        + "'container-1:/data/plugins_store/.install-p-1/'"));
    }

    @Test
    @DisplayName("原子落位失败时恢复旧目录并返回落位阶段")
    void failedMoveRestoresBackup() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        FileRoute staging = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/.install-p-1", "/srv/game/plugins_store/.install-p-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("mkdir -p '/srv/game/plugins_store'", result(0, ""));
        commands.add("if [ -e", result(0, ""));
        commands.add("mv -f '/srv/game/plugins_store/.install-p-1' '/srv/game/plugins_store/p'",
                result(1, "permission denied"));
        commands.add("rm -rf '/srv/game/plugins_store/p.rollback-", result(0, ""));

        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> executor.atomicMove(request(), nativeStaging(), listener()));

        assertEquals(InstanceArchiveImportService.Phase.ATOMIC_MOVE, ex.getPhase());
        assertTrue(ex.getMessage().contains("原子落位失败"));
        assertTrue(commands.contains("if [ -e '/srv/game/plugins_store/p.rollback-"));
    }

    @Test
    @DisplayName("原子落位 SSH 无响应时恢复旧目录并返回明确错误")
    void nullMoveResultRestoresBackup() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        FileRoute staging = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/.install-p-1", "/srv/game/plugins_store/.install-p-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("mkdir -p '/srv/game/plugins_store'", result(0, ""));
        commands.add("if [ -e '/srv/game/plugins_store/p'", null);
        commands.add("if [ -e '/srv/game/plugins_store/p.rollback-", result(0, ""));

        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> executor.atomicMove(request(), nativeStaging(), listener()));

        assertEquals(InstanceArchiveImportService.Phase.ATOMIC_MOVE, ex.getPhase());
        assertTrue(ex.getMessage().contains("SSH 命令无响应"));
        assertTrue(commands.contains("if [ -e '/srv/game/plugins_store/p.rollback-"));
    }

    @Test
    @DisplayName("关键文件检查 SSH 无响应时返回稳定原因码而非空指针")
    void nullRequiredFileCheckHasFailureCode() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        FileRoute staging = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/.install-p-1", "/srv/game/plugins_store/.install-p-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("mkdir -p", result(0, ""));
        commands.add("test -d '/srv/game/plugins_store/.install-p-1/left4dead2'",
                result(0, "ROOT\n"));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -mindepth", result(0, ""));
        commands.add("-type l -print -quit", result(0, ""));
        commands.add("-type f -links +1 -print -quit", result(0, ""));
        commands.add("find . -type f -exec stat", result(0, "1\n"));
        commands.add("find . -type f", result(0, "readme.txt\n"));
        commands.add("iname '*.so'", result(0, ""));
        commands.add("-not -readable", result(0, ""));
        commands.add("test -e '/srv/game/plugins_store/.install-p-1/left4dead2",
                null);

        InstanceArchiveImportExecutor.StagingHandle stagingRoot = executor.extractToStaging(
                request(), "/tmp/gp-plugin-install-1/archive.zip", listener());
        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> executor.validateStaging(request(), stagingRoot, listener()));

        assertEquals("ARCHIVE_REQUIRED_FILE_MISSING", ex.getFailureCode());
        assertTrue(ex.getMessage().contains("关键文件"));
    }

    @Test
    @DisplayName("上传失败返回上传阶段与 SSH 建议")
    void uploadFailureReportsUploadPhase() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        commands.add("mkdir -p", result(1, "permission denied"));

        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> executor.uploadArchive(request(), tempDir.resolve("archive.zip"), listener()));

        assertEquals(InstanceArchiveImportService.Phase.UPLOAD, ex.getPhase());
        assertTrue(ex.getMessage().contains("SSH"));
        assertTrue(ex.getPath().startsWith("/tmp/gp-plugin-install-"));
        assertFalse(ex.getPath().contains("mkdir -p"));
        assertFalse(ex.getMessage().contains("mkdir -p"));
    }

    @Test
    @DisplayName("容器命令失败只报告安全路径而不泄露原始命令")
    void dockerCommandFailureDoesNotLeakInnerCommand() {
        FileRoute target = FileRoute.dockerRoute(7L, 1L, "docker",
                "plugins_store/p", "/data/plugins_store/p", "container-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        commands.add("docker exec", result(1, "permission denied"));

        InstanceArchiveImportExecutor.StagingHandle staging =
                new InstanceArchiveImportExecutor.StagingHandle(
                        "/data/plugins_store/.install-p-1",
                        "/tmp/gp-plugin-install-1/staging/.install-p-1",
                        null, true, null);

        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> executor.importToStaging(request(), staging, listener()));

        assertEquals(InstanceArchiveImportService.Phase.IMPORT, ex.getPhase());
        assertEquals("/data/plugins_store", ex.getPath());
        assertFalse(ex.getPath().contains("mkdir -p"));
        assertFalse(ex.getMessage().contains("mkdir -p"));
    }

    @Test
    @DisplayName("上传失败立即清理本次创建的主机临时目录")
    void uploadFailureCleansTemporaryDirectory() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        commands.add("mkdir -p", result(0, ""));
        commands.add("rm -rf '/tmp/gp-plugin-install-", result(0, ""));
        doThrow(new IllegalStateException("SFTP interrupted"))
                .when(fileService)
                .uploadLocalFile(eq(1L), anyString(), anyString());

        assertThrows(InstanceArchiveImportException.class,
                () -> executor.uploadArchive(request(), tempDir.resolve("archive.zip"), listener()));

        assertTrue(commands.contains("rm -rf '/tmp/gp-plugin-install-"));
    }

    @Test
    @DisplayName("缺少关键文件时返回稳定原因码")
    void missingRequiredFileHasFailureCode() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        FileRoute staging = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/.install-p-1", "/srv/game/plugins_store/.install-p-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("mkdir -p", result(0, ""));
        commands.add("test -d '/srv/game/plugins_store/.install-p-1/left4dead2'",
                result(0, "ROOT\n"));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -mindepth", result(0, ""));
        commands.add("-type l -print -quit", result(0, ""));
        commands.add("-type f -links +1 -print -quit", result(0, ""));
        commands.add("find . -type f -exec stat", result(0, "1\n"));
        commands.add("find . -type f", result(0, "readme.txt\n"));
        commands.add("iname '*.so'", result(0, ""));
        commands.add("-not -readable", result(0, ""));
        commands.add("test -e '/srv/game/plugins_store/.install-p-1/left4dead2",
                result(0, "MISSING\n"));

        InstanceArchiveImportExecutor.StagingHandle stagingRoot = executor.extractToStaging(
                request(), "/tmp/gp-plugin-install-1/archive.zip", listener());
        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> executor.validateStaging(request(), stagingRoot, listener()));

        assertEquals(InstanceArchiveImportService.Phase.VALIDATE, ex.getPhase());
        assertEquals("ARCHIVE_REQUIRED_FILE_MISSING", ex.getFailureCode());
        assertTrue(ex.getMessage().contains("关键文件"));
    }

    @Test
    @DisplayName("关键文件存在但不可读时返回权限原因码")
    void unreadableRequiredFileHasFailureCode() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        FileRoute staging = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/.install-p-1", "/srv/game/plugins_store/.install-p-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("mkdir -p", result(0, ""));
        commands.add("test -d '/srv/game/plugins_store/.install-p-1/left4dead2'",
                result(0, "ROOT\n"));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -mindepth", result(0, ""));
        commands.add("-type l -print -quit", result(0, ""));
        commands.add("-type f -links +1 -print -quit", result(0, ""));
        commands.add("find . -type f -exec stat", result(0, "1\n"));
        commands.add("find . -type f", result(0, "a.smx\n"));
        commands.add("iname '*.so'", result(0, ""));
        commands.add("-not -readable", result(0, ""));
        commands.add("test -e '/srv/game/plugins_store/.install-p-1/left4dead2",
                result(0, "EXISTS\n"));
        commands.add("test -r '/srv/game/plugins_store/.install-p-1/left4dead2",
                result(0, "UNREADABLE\n"));

        InstanceArchiveImportExecutor.StagingHandle stagingRoot = executor.extractToStaging(
                request(), "/tmp/gp-plugin-install-1/archive.zip", listener());
        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> executor.validateStaging(request(), stagingRoot, listener()));

        assertEquals(InstanceArchiveImportService.Phase.VALIDATE, ex.getPhase());
        assertEquals("ARCHIVE_REQUIRED_FILE_UNREADABLE", ex.getFailureCode());
        assertTrue(ex.getMessage().contains("不可读"));
    }

    @Test
    @DisplayName("staging 中检测到硬链接时拒绝落位")
    void rejectsHardLinkedStaging() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        FileRoute staging = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/.install-p-1", "/srv/game/plugins_store/.install-p-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("mkdir -p", result(0, ""));
        commands.add("test -d '/srv/game/plugins_store/.install-p-1/left4dead2'",
                result(0, "ROOT\n"));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -mindepth", result(0, ""));
        commands.add("-type l -print -quit", result(0, ""));
        commands.add("-type f -links +1 -print -quit",
                result(0, "/srv/game/plugins_store/.install-p-1/a.smx\n"));

        InstanceArchiveImportExecutor.StagingHandle stagingRoot = executor.extractToStaging(
                request(), "/tmp/gp-plugin-install-1/archive.zip", listener());
        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> executor.validateStaging(request(), stagingRoot, listener()));

        assertEquals(InstanceArchiveImportService.Phase.VALIDATE, ex.getPhase());
        assertTrue(ex.getMessage().contains("硬链接"));
    }

    @Test
    @DisplayName("SO 与 CFG 的关键扩展名必须可读且非空")
    void validatesCriticalFileExtensionsAndContents() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        FileRoute staging = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/.install-p-1", "/srv/game/plugins_store/.install-p-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("mkdir -p", result(0, ""));
        commands.add("test -d '/srv/game/plugins_store/.install-p-1/left4dead2'",
                result(0, "ROOT\n"));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -mindepth", result(0, ""));
        commands.add("-type l -print -quit", result(0, ""));
        commands.add("-type f -links +1 -print -quit", result(0, ""));
        commands.add("find . -type f -exec stat", result(0, "1\n"));
        commands.add("find . -type f", result(0,
                "addons/sourcemod/extensions/empty.so\ncfg/sourcemod/empty.cfg\n"));
        commands.add("iname '*.so'", result(0, "cfg/sourcemod/empty.cfg\n"));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -type f "
                        + "\\( -iname '*.smx' -o -iname '*.so' -o -iname '*.cfg' \\) "
                        + "-size 0c",
                result(0, "cfg/sourcemod/empty.cfg\n"));
        commands.add("-not -readable", result(0, ""));

        InstanceArchiveImportExecutor.StagingHandle stagingRoot = executor.extractToStaging(
                request(), "/tmp/gp-plugin-install-1/archive.zip", listener());
        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> executor.validateStaging(request(), stagingRoot, listener()));

        assertEquals(InstanceArchiveImportService.Phase.VALIDATE, ex.getPhase());
        assertEquals("ARCHIVE_REQUIRED_FILE_EMPTY", ex.getFailureCode());
        assertTrue(ex.getMessage().contains("空文件"));
    }

    @Test
    @DisplayName("所有 SMX、SO、CFG 关键文件都必须可读")
    void rejectsUnreadableCriticalExtensionFiles() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        FileRoute staging = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/.install-p-1", "/srv/game/plugins_store/.install-p-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("mkdir -p", result(0, ""));
        commands.add("test -d '/srv/game/plugins_store/.install-p-1/left4dead2'",
                result(0, "ROOT\n"));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -mindepth", result(0, ""));
        commands.add("-type l -print -quit", result(0, ""));
        commands.add("-type f -links +1 -print -quit", result(0, ""));
        commands.add("find . -type f -exec stat", result(0, "1\n"));
        commands.add("find . -type f", result(0, "a.smx\nb.so\nc.cfg\n"));
        commands.add("iname '*.so'", result(0, ""));
        commands.add("test -e '/srv/game/plugins_store/.install-p-1/left4dead2",
                result(0, "EXISTS\n"));
        commands.add("test -r '/srv/game/plugins_store/.install-p-1/left4dead2",
                result(0, "READABLE\n"));
        commands.add("find '/srv/game/plugins_store/.install-p-1' -type f "
                + "\\( -iname '*.smx' -o -iname '*.so' -o -iname '*.cfg' \\) "
                + "-not -readable", result(0, "c.cfg\n"));

        InstanceArchiveImportExecutor.StagingHandle stagingRoot = executor.extractToStaging(
                request(), "/tmp/gp-plugin-install-1/archive.zip", listener());
        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> executor.validateStaging(request(), stagingRoot, listener()));

        assertEquals(InstanceArchiveImportService.Phase.VALIDATE, ex.getPhase());
        assertEquals("ARCHIVE_REQUIRED_FILE_UNREADABLE", ex.getFailureCode());
        assertTrue(ex.getMessage().contains("不可读"));
        assertTrue(ex.getMessage().contains("c.cfg"));
    }

    @Test
    @DisplayName("清理只删除本次临时归档和 staging")
    void cleanupRemovesOnlyOwnedPaths() {
        FileRoute target = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/p", "/srv/game/plugins_store/p");
        FileRoute staging = FileRoute.nativeRoute(7L, 1L, "linuxgsm",
                "plugins_store/.install-p-1", "/srv/game/plugins_store/.install-p-1");
        when(instanceFileService.resolveRoute(7L, "plugins_store/p")).thenReturn(target);
        when(instanceFileService.resolveRoute(7L, "plugins_store/.install-p-1"))
                .thenReturn(staging);
        commands.add("rm -rf", result(0, ""));

        executor.cleanup(request(), "/tmp/gp-plugin-install-1/archive.zip", listener());

        verify(fileService, never()).uploadLocalFile(anyLong(), anyString(), anyString());
        assertTrue(commands.contains("rm -rf '/tmp/gp-plugin-install-1'"));
        assertTrue(commands.contains("rm -rf '/srv/game/plugins_store/.install-p-1'"));
    }

    @Test
    @DisplayName("启动时清理所有主机遗留的归档临时目录")
    void startupCleanupRemovesOwnedHostTempDirectories() {
        Host first = hostMapper.selectById(1L);
        Host second = new Host();
        second.setId(2L);
        when(hostMapper.selectList(null)).thenReturn(List.of(first, second));
        when(fileService.executeCommand(eq(1L), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(1);
                    return command.startsWith("find /tmp ")
                            ? result(0, "") : commands.execute(command);
                });
        when(fileService.executeCommand(eq(2L), anyString(), anyLong()))
                .thenReturn(result(0, ""));

        executor.afterSingletonsInstantiated();

        verify(fileService).executeCommand(eq(1L), contains(
                "find /tmp -mindepth 1 -maxdepth 1 -type d "
                        + "-name 'gp-plugin-install-*' -exec rm -rf {} +"), anyLong());
        verify(fileService).executeCommand(eq(2L), contains(
                "find /tmp -mindepth 1 -maxdepth 1 -type d "
                        + "-name 'gp-plugin-install-*' -exec rm -rf {} +"), anyLong());
    }

    @Test
    @DisplayName("启动清理单主机失败不影响其他主机")
    void startupCleanupContinuesAfterHostFailure() {
        Host first = hostMapper.selectById(1L);
        Host second = new Host();
        second.setId(2L);
        when(hostMapper.selectList(null)).thenReturn(List.of(first, second));
        when(fileService.executeCommand(eq(1L), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("SSH unavailable"));

        executor.afterSingletonsInstantiated();

        verify(fileService).executeCommand(eq(2L), contains("gp-plugin-install-*"), anyLong());
    }

    private InstanceArchiveImportRequest request() {
        return InstanceArchiveImportRequest.builder()
                .instanceId(7L)
                .localArchivePath(tempDir.resolve("archive.zip").toString())
                .targetRelativePath("plugins_store/p")
                .stagingRelativePath("plugins_store/.install-p-1")
                .structureRoot("left4dead2")
                .requiredFiles(new String[]{"left4dead2/addons/sourcemod/plugins/a.smx"})
                .criticalFileExtensions(new String[]{"smx", "so", "cfg"})
                .build();
    }

    private static InstanceArchiveImportExecutor.StagingHandle nativeStaging() {
        return new InstanceArchiveImportExecutor.StagingHandle(
                "/srv/game/plugins_store/.install-p-1",
                "/srv/game/plugins_store/.install-p-1",
                "/srv/game/plugins_store/p", false, null);
    }

    private static InstanceArchiveImportListener listener() {
        return new InstanceArchiveImportListener() {
            @Override
            public void onPhaseStart(InstanceArchiveImportService.Phase phase, int percent, String message) {
            }

            @Override
            public void onProgress(InstanceArchiveImportService.Phase phase, int percent, String message) {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }

    private static SshUtil.CommandResult result(int exitCode, String output) {
        SshUtil.CommandResult result = new SshUtil.CommandResult();
        result.setExitCode(exitCode);
        result.setSuccess(exitCode == 0);
        result.setOutput(output);
        result.setError(exitCode == 0 ? "" : output);
        return result;
    }

    private static final class CommandRecorder {
        private final List<CommandRule> rules = new ArrayList<>();
        private final List<String> executed = new ArrayList<>();

        void add(String contains, SshUtil.CommandResult result) {
            rules.add(new CommandRule(contains, result));
        }

        SshUtil.CommandResult execute(String command) {
            executed.add(command);
            CommandRule match = rules.stream()
                    .filter(rule -> command.contains(rule.needle()))
                    .max(java.util.Comparator.comparingInt(
                            rule -> rule.needle().length()))
                    .orElse(null);
            if (match != null) {
                return match.result();
            }
            throw new AssertionError("未配置命令响应: " + command);
        }

        boolean contains(String fragment) {
            return executed.stream().anyMatch(command -> command.contains(fragment));
        }

        int count(String fragment) {
            return (int) executed.stream().filter(command -> command.contains(fragment)).count();
        }
    }

    private record CommandRule(String needle, SshUtil.CommandResult result) {
    }
}
