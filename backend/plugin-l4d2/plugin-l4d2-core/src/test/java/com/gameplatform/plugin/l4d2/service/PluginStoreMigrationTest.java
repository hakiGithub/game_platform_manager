package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginStoreMigrationTest {

    @Mock
    private InstanceFileService instanceFileService;

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private L4D2PathResolver pathResolver;

    @InjectMocks
    private PluginStoreMigration migration;

    private InstanceVO instance1;
    private InstanceVO instance2;

    @BeforeEach
    void setUp() {
        instance1 = new InstanceVO();
        instance1.setId(1L);
        instance1.setGameCode("l4d2");
        instance2 = new InstanceVO();
        instance2.setId(2L);
        instance2.setGameCode("l4d2");
        when(pathResolver.getDownloadTempPath()).thenReturn("addons/sourcemod/.download_temp");
        when(pathResolver.getPluginsStorePath())
                .thenReturn("addons/sourcemod/plugins_store");
        when(pathResolver.getArchiveInstallPrefix())
                .thenReturn("addons/sourcemod/plugins_store/.install-");
        when(pathResolver.getArchiveRollbackMarker()).thenReturn(".rollback-");
        when(pathResolver.getArchiveCommitMarker()).thenReturn(".archive-committed");
    }

    @Test
    void cleanDownloadTemp_shouldIterateAllL4d2Instances() {
        when(instanceQueryService.listByGameCode("l4d2")).thenReturn(List.of(instance1, instance2));

        migration.cleanDownloadTemp();

        verify(instanceFileService).deleteDirectory(eq(1L), eq("addons/sourcemod/.download_temp"), eq(true));
        verify(instanceFileService).deleteDirectory(eq(2L), eq("addons/sourcemod/.download_temp"), eq(true));
    }

    @Test
    void cleanDownloadTemp_shouldNotThrowWhenSingleInstanceFails() {
        when(instanceQueryService.listByGameCode("l4d2")).thenReturn(List.of(instance1, instance2));
        doThrow(new RuntimeException("instance 1 cleanup failed"))
                .when(instanceFileService).deleteDirectory(eq(1L), anyString(), anyBoolean());

        // 不应抛异常，且 instance2 仍被清理
        migration.cleanDownloadTemp();

        verify(instanceFileService).deleteDirectory(eq(2L), eq("addons/sourcemod/.download_temp"), eq(true));
    }

    @Test
    void cleanDownloadTemp_shouldNotThrowWhenNoInstances() {
        when(instanceQueryService.listByGameCode("l4d2")).thenReturn(List.of());

        migration.cleanDownloadTemp();

        verify(instanceFileService, never()).deleteDirectory(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void cleanDownloadTemp_shouldNotThrowWhenListThrows() {
        when(instanceQueryService.listByGameCode("l4d2")).thenThrow(new RuntimeException("DB error"));

        // 不应抛异常，避免阻塞应用启动
        migration.cleanDownloadTemp();
    }

    @Test
    void cleanArchiveInstallTemp_shouldDeleteOnlyInstallPrefixes() {
        when(instanceQueryService.listByGameCode("l4d2")).thenReturn(List.of(instance1));
        FileInfo staging = new FileInfo();
        staging.setName(".install-myplugin-123");
        staging.setDirectory(true);
        FileInfo normal = new FileInfo();
        normal.setName("myplugin");
        normal.setDirectory(true);
        when(instanceFileService.listFiles(1L, "addons/sourcemod/plugins_store"))
                .thenReturn(List.of(staging, normal));

        migration.cleanArchiveInstallTemp();

        verify(instanceFileService).deleteDirectory(eq(1L),
                eq("addons/sourcemod/plugins_store/.install-myplugin-123"), eq(true));
        verify(instanceFileService, never()).deleteDirectory(eq(1L),
                eq("addons/sourcemod/plugins_store/myplugin"), anyBoolean());
    }

    @Test
    void cleanArchiveInstallTemp_shouldNotThrowWhenSingleInstanceFails() {
        when(instanceQueryService.listByGameCode("l4d2")).thenReturn(List.of(instance1, instance2));
        when(instanceFileService.listFiles(eq(1L), anyString()))
                .thenThrow(new RuntimeException("list failed"));

        migration.cleanArchiveInstallTemp();

        verify(instanceFileService).listFiles(eq(2L), anyString());
    }

    @Test
    void cleanArchiveInstallTemp_shouldRestoreRollbackWhenTargetMissing() {
        when(instanceQueryService.listByGameCode("l4d2")).thenReturn(List.of(instance1));
        FileInfo rollback = new FileInfo();
        rollback.setName("myplugin.rollback-123");
        rollback.setDirectory(true);
        when(instanceFileService.listFiles(1L, "addons/sourcemod/plugins_store"))
                .thenReturn(List.of(rollback));
        when(instanceFileService.exists(1L,
                "addons/sourcemod/plugins_store/myplugin")).thenReturn(false);

        migration.cleanArchiveInstallTemp();

        verify(instanceFileService).moveFile(1L,
                "addons/sourcemod/plugins_store/myplugin.rollback-123",
                "addons/sourcemod/plugins_store/myplugin");
        verify(instanceFileService, never()).deleteDirectory(eq(1L),
                eq("addons/sourcemod/plugins_store/myplugin.rollback-123"), anyBoolean());
    }

    @Test
    void cleanArchiveInstallTemp_shouldDeleteRollbackWhenCommitMarkerExists() {
        when(instanceQueryService.listByGameCode("l4d2")).thenReturn(List.of(instance1));
        FileInfo rollback = new FileInfo();
        rollback.setName("myplugin.rollback-123");
        rollback.setDirectory(true);
        when(instanceFileService.listFiles(1L, "addons/sourcemod/plugins_store"))
                .thenReturn(List.of(rollback));
        when(instanceFileService.exists(1L,
                "addons/sourcemod/plugins_store/myplugin/.archive-committed"))
                .thenReturn(true);

        migration.cleanArchiveInstallTemp();

        verify(instanceFileService).deleteDirectory(1L,
                "addons/sourcemod/plugins_store/myplugin.rollback-123", true);
        verify(instanceFileService, never()).moveFile(anyLong(), anyString(), anyString());
    }

    @Test
    void cleanArchiveInstallTemp_shouldRestoreRollbackWhenTargetExistsWithoutCommitMarker() {
        when(instanceQueryService.listByGameCode("l4d2")).thenReturn(List.of(instance1));
        FileInfo rollback = new FileInfo();
        rollback.setName("myplugin.rollback-123");
        rollback.setDirectory(true);
        when(instanceFileService.listFiles(1L, "addons/sourcemod/plugins_store"))
                .thenReturn(List.of(rollback));
        when(instanceFileService.exists(1L,
                "addons/sourcemod/plugins_store/myplugin/.archive-committed"))
                .thenReturn(false);
        when(instanceFileService.exists(1L,
                "addons/sourcemod/plugins_store/myplugin")).thenReturn(true);

        migration.cleanArchiveInstallTemp();

        verify(instanceFileService).deleteDirectory(1L,
                "addons/sourcemod/plugins_store/myplugin", true);
        verify(instanceFileService).moveFile(1L,
                "addons/sourcemod/plugins_store/myplugin.rollback-123",
                "addons/sourcemod/plugins_store/myplugin");
        verify(instanceFileService, never()).deleteDirectory(eq(1L),
                eq("addons/sourcemod/plugins_store/myplugin.rollback-123"), anyBoolean());
    }

}
