package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
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
}
