package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 插件禁用单测（无 RCON 语义，重启服务器后完成卸载）。
 */
class PluginInstallServiceDisableTest {

    private PluginInstallService service;
    private InstanceFileService instanceFileService;
    private EnabledPluginsService enabledPluginsService;
    private FileRefsService fileRefsService;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        enabledPluginsService = mock(EnabledPluginsService.class);
        fileRefsService = mock(FileRefsService.class);
        InstanceQueryService instanceQueryService = mock(InstanceQueryService.class);
        InstanceVO instance = new InstanceVO();
        instance.setId(100L);
        when(instanceQueryService.getInstanceById(100L)).thenReturn(instance);

        service = new PluginInstallService(
                instanceQueryService,
                instanceFileService,
                fileRefsService,
                new L4D2PathResolver(),
                mock(PluginMetaService.class),
                enabledPluginsService);
    }

    @Test
    void disableAndUnload_shouldFailWhenNotEnabled() {
        when(enabledPluginsService.isEnabled(eq(100L), eq("inactive")))
                .thenReturn(false);

        assertThatThrownBy(() -> service.disableAndUnload(100L, "inactive"))
                .isInstanceOf(L4D2PluginException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    void disableAndUnloadBatch_removesRefsAndRegistersEach() {
        for (String name : List.of("a", "b", "c")) {
            when(enabledPluginsService.isEnabled(eq(100L), eq(name))).thenReturn(true);
            when(fileRefsService.removeRefs(eq(100L), eq(name))).thenReturn(List.of());
        }

        List<String> errors = service.disableAndUnloadBatch(100L, List.of("a", "b", "c"));

        assertThat(errors).isEmpty();
        verify(fileRefsService, times(3)).removeRefs(anyLong(), anyString());
        verify(enabledPluginsService, times(3)).remove(anyLong(), anyString());
    }

    @Test
    void disableAndUnloadBatch_partialFailureCollectsErrors() {
        when(enabledPluginsService.isEnabled(eq(100L), eq("a"))).thenReturn(true);
        when(enabledPluginsService.isEnabled(eq(100L), eq("b"))).thenReturn(false);
        when(fileRefsService.removeRefs(eq(100L), eq("a"))).thenReturn(List.of());

        List<String> errors = service.disableAndUnloadBatch(100L, List.of("a", "b"));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("b").contains("未启用");
        verify(fileRefsService, times(1)).removeRefs(anyLong(), anyString());
        verify(enabledPluginsService, times(1)).remove(anyLong(), anyString());
    }

    @Test
    void disableAndUnload_deletesZeroedFiles() {
        when(enabledPluginsService.isEnabled(eq(100L), eq("p"))).thenReturn(true);
        when(fileRefsService.removeRefs(eq(100L), eq("p")))
                .thenReturn(List.of("addons/sourcemod/plugins/p.smx"));

        service.disableAndUnload(100L, "p");

        verify(instanceFileService).deleteFile(eq(100L),
                eq("left4dead2/addons/sourcemod/plugins/p.smx"));
        verify(enabledPluginsService).remove(eq(100L), eq("p"));
    }
}
