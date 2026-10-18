package com.gameplatform.archive;

import com.gameplatform.plugin.service.InstanceArchiveImportException;
import com.gameplatform.plugin.service.InstanceArchiveImportListener;
import com.gameplatform.plugin.service.InstanceArchiveImportRequest;
import com.gameplatform.plugin.service.InstanceArchiveImportResult;
import com.gameplatform.plugin.service.InstanceArchiveImportService;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("实例归档导入服务")
class InstanceArchiveImportServiceImplTest {

    @TempDir
    Path tempDir;

    private InstanceArchiveImportExecutor executor;
    private InstanceArchiveImportServiceImpl service;
    private Path archive;

    @BeforeEach
    void setUp() throws IOException {
        executor = mock(InstanceArchiveImportExecutor.class);
        service = new InstanceArchiveImportServiceImpl(
                new ZipArchiveManifestReader(), executor);
        archive = tempDir.resolve("plugin.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(archive.toFile())) {
            for (String name : List.of(
                    "p/left4dead2/addons/sourcemod/plugins/a.smx",
                    "p/left4dead2/cfg/sourcemod/a.cfg")) {
                ZipArchiveEntry entry = new ZipArchiveEntry(name);
                out.putArchiveEntry(entry);
                out.write('x');
                out.closeArchiveEntry();
            }
        }
    }

    @Test
    @DisplayName("按六阶段执行并返回文件统计")
    void importsThroughAllPhases() {
        when(executor.uploadArchive(any(), any(), any())).thenReturn("/tmp/archive.zip");
        InstanceArchiveImportExecutor.StagingHandle staging = staging();
        when(executor.extractToStaging(any(), anyString(), any())).thenReturn(staging);
        InstanceArchiveImportExecutor.ImportedTree tree =
                new InstanceArchiveImportExecutor.ImportedTree(2, 2,
                        List.of("left4dead2/addons/sourcemod/plugins/a.smx",
                                "left4dead2/cfg/sourcemod/a.cfg"));
        doNothing().when(executor).importToStaging(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
        when(executor.validateStaging(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(tree);
        when(executor.atomicMove(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(moveHandle());
        doNothing().when(executor).finalizeMove(any(), any());
        RecordingListener listener = new RecordingListener();

        InstanceArchiveImportResult result = service.importArchive(request(), listener);

        assertEquals("p", result.getPluginName());
        assertEquals(2, result.getFileCount());
        assertEquals(6, listener.phases.size());
        assertEquals(List.of(InstanceArchiveImportService.Phase.UPLOAD,
                        InstanceArchiveImportService.Phase.EXTRACT,
                        InstanceArchiveImportService.Phase.IMPORT,
                        InstanceArchiveImportService.Phase.VALIDATE,
                        InstanceArchiveImportService.Phase.ATOMIC_MOVE,
                        InstanceArchiveImportService.Phase.CLEANUP),
                listener.phases);
        verify(executor).atomicMove(any(), eq(staging), any());
        verify(executor).finalizeMove(any(), any());
        verify(executor).cleanup(any(), eq("/tmp/archive.zip"), any());
        verify(executor, times(1)).cleanup(any(), anyString(), any());
    }

    @Test
    @DisplayName("清理阶段失败只清理一次且返回清理错误")
    void cleanupFailureIsReportedWithoutDuplicateCleanup() {
        when(executor.uploadArchive(any(), any(), any())).thenReturn("/tmp/archive.zip");
        InstanceArchiveImportExecutor.StagingHandle staging = staging();
        when(executor.extractToStaging(any(), anyString(), any())).thenReturn(staging);
        InstanceArchiveImportExecutor.ImportedTree tree =
                new InstanceArchiveImportExecutor.ImportedTree(2, 2, List.of());
        doNothing().when(executor).importToStaging(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
        when(executor.validateStaging(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(tree);
        when(executor.atomicMove(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(moveHandle());
        doNothing().when(executor).finalizeMove(any(), any());
        doThrow(new IllegalStateException("cleanup failed"))
                .when(executor).cleanup(any(), anyString(), any());

        RecordingListener listener = new RecordingListener();
        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> service.importArchive(request(), listener));

        assertEquals(InstanceArchiveImportService.Phase.CLEANUP, ex.getPhase());
        assertTrue(ex.getMessage().contains("cleanup failed"));
        assertTrue(listener.progress.stream().noneMatch(
                progress -> progress.phase == InstanceArchiveImportService.Phase.CLEANUP
                        && progress.percent == 100));
        verify(executor, times(1)).cleanup(any(), anyString(), any());
    }

    @Test
    @DisplayName("提交动作失败时恢复旧目录且不删除备份")
    void commitFailureRollsBackBeforeCleanup() {
        when(executor.uploadArchive(any(), any(), any())).thenReturn("/tmp/archive.zip");
        when(executor.extractToStaging(any(), anyString(), any())).thenReturn(staging());
        InstanceArchiveImportExecutor.ImportedTree tree =
                new InstanceArchiveImportExecutor.ImportedTree(2, 2, List.of());
        doNothing().when(executor).importToStaging(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
        when(executor.validateStaging(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(tree);
        when(executor.atomicMove(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(moveHandle());
        InstanceArchiveImportRequest request = InstanceArchiveImportRequest.builder()
                .instanceId(1L)
                .localArchivePath(archive.toString())
                .stagingRelativePath("plugins_store/.install-1")
                .targetRelativePath("plugins_store/p")
                .structureRoot("left4dead2")
                .maxEntries(100)
                .maxExtractBytes(1024)
                .commitAction(imported -> {
                    throw new IllegalStateException("plugin.yaml write failed");
                })
                .build();

        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> service.importArchive(request, new RecordingListener()));

        assertEquals(InstanceArchiveImportService.Phase.ATOMIC_MOVE, ex.getPhase());
        assertTrue(ex.getMessage().contains("旧目录已恢复"));
        verify(executor).rollbackMove(any(), any());
        verify(executor, never()).finalizeMove(any(), any());
    }

    @Test
    @DisplayName("清理阶段边界仍检查取消")
    void cleanupPhaseChecksCancellation() {
        when(executor.uploadArchive(any(), any(), any())).thenReturn("/tmp/archive.zip");
        when(executor.extractToStaging(any(), anyString(), any())).thenReturn(staging());
        InstanceArchiveImportExecutor.ImportedTree tree =
                new InstanceArchiveImportExecutor.ImportedTree(2, 2, List.of());
        doNothing().when(executor).importToStaging(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
        when(executor.validateStaging(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(tree);
        when(executor.atomicMove(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(moveHandle());
        RecordingListener listener = new RecordingListener();
        listener.cancelOnPhaseStart = InstanceArchiveImportService.Phase.CLEANUP;

        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> service.importArchive(request(), listener));

        assertEquals(InstanceArchiveImportService.Phase.CLEANUP, ex.getPhase());
        verify(executor).finalizeMove(any(), any());
        verify(executor).cleanup(any(), eq("/tmp/archive.zip"), any());
    }

    @Test
    @DisplayName("落位期间取消时不写元数据")
    void cancellationDuringAtomicMoveSkipsCommit() {
        when(executor.uploadArchive(any(), any(), any())).thenReturn("/tmp/archive.zip");
        when(executor.extractToStaging(any(), anyString(), any())).thenReturn(staging());
        InstanceArchiveImportExecutor.ImportedTree tree =
                new InstanceArchiveImportExecutor.ImportedTree(2, 2, List.of());
        doNothing().when(executor).importToStaging(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
        when(executor.validateStaging(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(tree);
        RecordingListener listener = new RecordingListener();
        when(executor.atomicMove(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenAnswer(invocation -> {
                    listener.cancelAt = InstanceArchiveImportService.Phase.ATOMIC_MOVE;
                    return moveHandle();
                });
        AtomicBoolean committed = new AtomicBoolean();
        InstanceArchiveImportRequest request = InstanceArchiveImportRequest.builder()
                .instanceId(1L)
                .localArchivePath(archive.toString())
                .stagingRelativePath("plugins_store/.install-1")
                .targetRelativePath("plugins_store/p")
                .structureRoot("left4dead2")
                .maxEntries(100)
                .maxExtractBytes(1024)
                .commitAction(imported -> committed.set(true))
                .build();

        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> service.importArchive(request, listener));

        assertEquals(InstanceArchiveImportService.Phase.ATOMIC_MOVE, ex.getPhase());
        assertFalse(committed.get());
        verify(executor, never()).finalizeMove(any(), any());
    }

    @Test
    @DisplayName("取消发生在落位前时不调用 atomicMove")
    void cancelBeforeAtomicMoveDoesNotMove() {
        when(executor.uploadArchive(any(), any(), any())).thenReturn("/tmp/archive.zip");
        InstanceArchiveImportExecutor.StagingHandle staging = staging();
        when(executor.extractToStaging(any(), anyString(), any())).thenReturn(staging);
        InstanceArchiveImportExecutor.ImportedTree tree =
                new InstanceArchiveImportExecutor.ImportedTree(2, 2, List.of());
        doNothing().when(executor).importToStaging(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
        when(executor.validateStaging(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(tree);
        RecordingListener listener = new RecordingListener();
        listener.cancelAt = InstanceArchiveImportService.Phase.ATOMIC_MOVE;

        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> service.importArchive(request(), listener));

        assertEquals(InstanceArchiveImportService.Phase.ATOMIC_MOVE, ex.getPhase());
        verify(executor, never()).atomicMove(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
        verify(executor).cleanup(any(), eq("/tmp/archive.zip"), any());
    }

    @Test
    @DisplayName("staging 位于目标内部时拒绝导入")
    void rejectsStagingInsideTarget() {
        InstanceArchiveImportRequest request = InstanceArchiveImportRequest.builder()
                .instanceId(1L)
                .localArchivePath(archive.toString())
                .targetRelativePath("plugins_store/p")
                .stagingRelativePath("plugins_store/p/.install-1")
                .structureRoot("left4dead2")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.importArchive(request, new RecordingListener()));

        assertTrue(ex.getMessage().contains("同一父目录"));
        verify(executor, never()).uploadArchive(any(), any(), any());
    }

    @Test
    @DisplayName("根目标也必须与 staging 位于同一父目录")
    void rejectsUnrelatedStagingForRootTarget() {
        InstanceArchiveImportRequest request = InstanceArchiveImportRequest.builder()
                .instanceId(1L)
                .localArchivePath(archive.toString())
                .targetRelativePath("left4dead2")
                .stagingRelativePath("other/.install-1")
                .structureRoot("left4dead2")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.importArchive(request, new RecordingListener()));

        assertTrue(ex.getMessage().contains("同一父目录"));
        verify(executor, never()).uploadArchive(any(), any(), any());
    }

    @Test
    @DisplayName("提交标记必须是单个安全文件名")
    void rejectsUnsafeCommitMarkerFileName() {
        for (String marker : List.of("", " ", "nested/.archive-committed",
                "nested\\.archive-committed", "../escape", "..", "/tmp/marker")) {
            InstanceArchiveImportRequest request = InstanceArchiveImportRequest.builder()
                    .instanceId(1L)
                    .localArchivePath(archive.toString())
                    .stagingRelativePath("plugins_store/.install-1")
                    .targetRelativePath("plugins_store/p")
                    .structureRoot("left4dead2")
                    .commitMarkerFileName(marker)
                    .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.importArchive(request, new RecordingListener()),
                    () -> "应拒绝提交标记: " + marker);

            assertTrue(ex.getMessage().contains("commitMarkerFileName"));
        }
        verify(executor, never()).uploadArchive(any(), any(), any());
    }

    @Test
    @DisplayName("进入落位阶段前取消不执行 atomicMove")
    void cancelOnAtomicMovePhaseStartDoesNotMove() {
        when(executor.uploadArchive(any(), any(), any())).thenReturn("/tmp/archive.zip");
        InstanceArchiveImportExecutor.StagingHandle staging = staging();
        when(executor.extractToStaging(any(), anyString(), any())).thenReturn(staging);
        InstanceArchiveImportExecutor.ImportedTree tree =
                new InstanceArchiveImportExecutor.ImportedTree(2, 2, List.of());
        doNothing().when(executor).importToStaging(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
        when(executor.validateStaging(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(tree);
        RecordingListener listener = new RecordingListener();
        listener.cancelOnPhaseStart = InstanceArchiveImportService.Phase.ATOMIC_MOVE;

        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> service.importArchive(request(), listener));

        assertEquals(InstanceArchiveImportService.Phase.ATOMIC_MOVE, ex.getPhase());
        verify(executor, never()).atomicMove(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
        verify(executor).cleanup(any(), eq("/tmp/archive.zip"), any());
    }

    @Test
    @DisplayName("校验文件数不一致时失败并保留可操作提示")
    void rejectsMismatchedFileCount() {
        when(executor.uploadArchive(any(), any(), any())).thenReturn("/tmp/archive.zip");
        when(executor.extractToStaging(any(), anyString(), any())).thenReturn(staging());
        InstanceArchiveImportExecutor.ImportedTree tree =
                new InstanceArchiveImportExecutor.ImportedTree(1, 2, List.of());
        doNothing().when(executor).importToStaging(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
        when(executor.validateStaging(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(tree);

        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> service.importArchive(request(), new RecordingListener()));

        assertEquals(InstanceArchiveImportService.Phase.VALIDATE, ex.getPhase());
        assertTrue(ex.getMessage().contains("文件数不一致"));
        assertTrue(ex.getMessage().contains("建议="));
        verify(executor, never()).atomicMove(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
    }

    @Test
    @DisplayName("实际解压字节超过请求上限时失败")
    void rejectsExtractedBytesOverRequestLimit() {
        when(executor.uploadArchive(any(), any(), any())).thenReturn("/tmp/archive.zip");
        when(executor.extractToStaging(any(), anyString(), any())).thenReturn(staging());
        InstanceArchiveImportExecutor.ImportedTree tree =
                new InstanceArchiveImportExecutor.ImportedTree(2, 1025, List.of());
        doNothing().when(executor).importToStaging(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
        when(executor.validateStaging(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(tree);

        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> service.importArchive(request(), new RecordingListener()));

        assertEquals(InstanceArchiveImportService.Phase.VALIDATE, ex.getPhase());
        assertEquals("ARCHIVE_TOO_LARGE", ex.getFailureCode());
        assertTrue(ex.getMessage().contains("请求上限"));
        verify(executor, never()).atomicMove(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
    }

    @Test
    @DisplayName("实际解压条目超过请求上限时失败")
    void rejectsExtractedEntriesOverRequestLimit() {
        when(executor.uploadArchive(any(), any(), any())).thenReturn("/tmp/archive.zip");
        when(executor.extractToStaging(any(), anyString(), any())).thenReturn(staging());
        InstanceArchiveImportExecutor.ImportedTree tree =
                new InstanceArchiveImportExecutor.ImportedTree(101, 2, List.of());
        doNothing().when(executor).importToStaging(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
        when(executor.validateStaging(any(), any(InstanceArchiveImportExecutor.StagingHandle.class), any()))
                .thenReturn(tree);

        InstanceArchiveImportException ex = assertThrows(InstanceArchiveImportException.class,
                () -> service.importArchive(request(), new RecordingListener()));

        assertEquals(InstanceArchiveImportService.Phase.VALIDATE, ex.getPhase());
        assertEquals("ARCHIVE_TOO_MANY_ENTRIES", ex.getFailureCode());
        assertTrue(ex.getMessage().contains("条目数超过请求上限"));
        verify(executor, never()).atomicMove(any(),
                any(InstanceArchiveImportExecutor.StagingHandle.class), any());
    }

    private InstanceArchiveImportRequest request() {
        return InstanceArchiveImportRequest.builder()
                .instanceId(1L)
                .localArchivePath(archive.toString())
                .stagingRelativePath("plugins_store/.install-1")
                .targetRelativePath("plugins_store/p")
                .structureRoot("left4dead2")
                .maxEntries(100)
                .maxExtractBytes(1024)
                .build();
    }

    private static InstanceArchiveImportExecutor.StagingHandle staging() {
        return new InstanceArchiveImportExecutor.StagingHandle(
                "plugins_store/.install-1", "plugins_store/.install-1",
                "plugins_store/p", false, null);
    }

    private static InstanceArchiveImportExecutor.AtomicMoveHandle moveHandle() {
        return new InstanceArchiveImportExecutor.AtomicMoveHandle(
                1L, null, "plugins_store/p", "plugins_store/p.rollback-1", true, null);
    }

    private static final class RecordingListener implements InstanceArchiveImportListener {
        final List<InstanceArchiveImportService.Phase> phases = new ArrayList<>();
        final List<ProgressRecord> progress = new ArrayList<>();
        InstanceArchiveImportService.Phase cancelAt;
        InstanceArchiveImportService.Phase cancelOnPhaseStart;
        InstanceArchiveImportService.Phase currentPhase;
        int currentPercent;

        @Override
        public void onPhaseStart(InstanceArchiveImportService.Phase phase, int percent, String message) {
            phases.add(phase);
            currentPhase = phase;
            currentPercent = percent;
            if (cancelOnPhaseStart == phase) {
                cancelAt = phase;
            }
        }

        @Override
        public void onProgress(InstanceArchiveImportService.Phase phase, int percent, String message) {
            assertTrue(percent >= currentPercent);
            currentPercent = percent;
            progress.add(new ProgressRecord(phase, percent));
        }

        @Override
        public boolean isCancelled() {
            return cancelAt != null && cancelAt == currentPhase;
        }
    }

    private record ProgressRecord(InstanceArchiveImportService.Phase phase, int percent) {
    }
}
