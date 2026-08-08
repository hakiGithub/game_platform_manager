package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
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

class PluginInstallServiceEnableTest {

    private PluginInstallService service;
    private InstanceFileService instanceFileService;
    private RconService rconService;
    private EnabledPluginsService enabledPluginsService;
    private PluginMetaService pluginMetaService;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        rconService = mock(RconService.class);
        enabledPluginsService = mock(EnabledPluginsService.class);
        pluginMetaService = mock(PluginMetaService.class);
        InstanceQueryService instanceQueryService = mock(InstanceQueryService.class);
        InstanceVO instance = new InstanceVO();
        instance.setId(100L);
        when(instanceQueryService.getInstanceById(100L)).thenReturn(instance);

        service = new PluginInstallService(
                instanceQueryService,
                instanceFileService,
                rconService,
                mock(FileRefsService.class),
                new L4D2PathResolver(),
                pluginMetaService,
                enabledPluginsService);
    }

    @Test
    void enableAndLoad_shouldFailWhenNoSmxFiles() {
        when(instanceFileService.listFiles(eq(100L), contains("plugins_store/empty_plugin/")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.enableAndLoad(100L, "empty_plugin"))
                .isInstanceOf(L4D2PluginException.class)
                .hasMessageContaining("不包含 .smx");
    }

    @Test
    void enableAndLoad_shouldRollbackWhenRconLoadFails() {
        when(instanceFileService.listFiles(eq(100L),
                eq("left4dead2/addons/sourcemod/plugins_store/myplugin/left4dead2/addons/sourcemod/plugins")))
                .thenReturn(List.of(fileInfo("myplugin.smx", false)));

        com.gameplatform.plugin.l4d2.vo.PluginMeta meta = new com.gameplatform.plugin.l4d2.vo.PluginMeta();
        meta.setName("myplugin");
        meta.setFileList(List.of("addons/sourcemod/plugins/myplugin.smx"));
        when(pluginMetaService.load(eq(100L), eq("myplugin"))).thenReturn(meta);

        when(rconService.executeCommand(eq(100L), eq("sm plugins load myplugin")))
                .thenReturn("[SM] Failed to load plugin myplugin.smx");

        assertThatThrownBy(() -> service.enableAndLoad(100L, "myplugin"))
                .isInstanceOf(L4D2PluginException.class);

        verify(instanceFileService).deleteFile(eq(100L),
                eq("left4dead2/addons/sourcemod/plugins/myplugin.smx"));
        verify(enabledPluginsService, never()).add(anyLong(), any());
    }

    private FileInfo fileInfo(String name, boolean isDir) {
        FileInfo f = new FileInfo();
        f.setName(name);
        f.setDirectory(isDir);
        return f;
    }
}
