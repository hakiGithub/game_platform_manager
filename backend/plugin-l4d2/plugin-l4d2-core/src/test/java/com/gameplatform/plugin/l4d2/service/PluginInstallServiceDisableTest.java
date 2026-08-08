package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginInstallServiceDisableTest {

    private PluginInstallService service;
    private InstanceFileService instanceFileService;
    private RconService rconService;
    private EnabledPluginsService enabledPluginsService;
    private FileRefsService fileRefsService;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        rconService = mock(RconService.class);
        enabledPluginsService = mock(EnabledPluginsService.class);
        fileRefsService = mock(FileRefsService.class);
        InstanceQueryService instanceQueryService = mock(InstanceQueryService.class);
        InstanceVO instance = new InstanceVO();
        instance.setId(100L);
        when(instanceQueryService.getInstanceById(100L)).thenReturn(instance);

        service = new PluginInstallService(
                instanceQueryService,
                instanceFileService,
                rconService,
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
    void disableAndUnload_shouldUnloadInReverseOrderAndRemoveFiles() {
        EnabledPlugin enabled = new EnabledPlugin();
        enabled.setName("myplugin");
        enabled.setFiles(List.of("addons/sourcemod/plugins/myplugin.smx"));
        when(enabledPluginsService.isEnabled(eq(100L), eq("myplugin"))).thenReturn(true);
        when(enabledPluginsService.loadYaml(eq(100L))).thenReturn(List.of(enabled));
        when(fileRefsService.removeRefs(eq(100L), eq("myplugin"))).thenReturn(List.of());

        // listPluginSmxIds 扫描库目录，需返回 myplugin.smx
        when(instanceFileService.listFiles(eq(100L),
                eq("left4dead2/addons/sourcemod/plugins_store/myplugin/left4dead2/addons/sourcemod/plugins")))
                .thenReturn(List.of(fileInfo("myplugin.smx", false)));

        service.disableAndUnload(100L, "myplugin");

        verify(rconService).executeCommand(eq(100L), eq("sm plugins unload myplugin"));
        verify(fileRefsService).removeRefs(eq(100L), eq("myplugin"));
        verify(enabledPluginsService).remove(eq(100L), eq("myplugin"));
    }

    private FileInfo fileInfo(String name, boolean isDir) {
        FileInfo f = new FileInfo();
        f.setName(name);
        f.setDirectory(isDir);
        return f;
    }
}
