package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.l4d2.vo.PluginListVO;
import com.gameplatform.plugin.l4d2.vo.PluginMeta;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginInstallServiceListTest {

    private PluginInstallService service;
    private InstanceFileService instanceFileService;
    private PluginMetaService pluginMetaService;
    private EnabledPluginsService enabledPluginsService;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        pluginMetaService = mock(PluginMetaService.class);
        enabledPluginsService = mock(EnabledPluginsService.class);
        InstanceQueryService instanceQueryService = mock(InstanceQueryService.class);
        InstanceVO instance = new InstanceVO();
        instance.setId(100L);
        instance.setHostId(1L);
        when(instanceQueryService.getInstanceById(100L)).thenReturn(instance);

        service = new PluginInstallService(
                instanceQueryService,
                instanceFileService,
                mock(RconService.class),
                mock(FileRefsService.class),
                new L4D2PathResolver(),
                pluginMetaService,
                enabledPluginsService);
    }

    @Test
    void listPlugins_shouldScanPluginsStoreAndMergeEnabledState() {
        when(instanceFileService.listFiles(eq(100L), eq("left4dead2/addons/sourcemod/plugins_store")))
                .thenReturn(List.of(
                        dirInfo("plugin_a"),
                        dirInfo("plugin_b")));

        EnabledPlugin enabledA = new EnabledPlugin();
        enabledA.setName("plugin_a");
        enabledA.setSource("upload");
        when(enabledPluginsService.list(eq(100L))).thenReturn(List.of(enabledA));

        PluginMeta metaA = new PluginMeta();
        metaA.setName("plugin_a");
        metaA.setSource("upload");
        metaA.setDescription("desc A");
        when(pluginMetaService.load(eq(100L), eq("plugin_a"))).thenReturn(metaA);
        when(pluginMetaService.load(eq(100L), eq("plugin_b"))).thenReturn(null);

        List<PluginListVO> result = service.listPlugins(100L);

        assertThat(result).hasSize(2);
        PluginListVO a = result.stream().filter(v -> "plugin_a".equals(v.getName())).findFirst().orElseThrow();
        PluginListVO b = result.stream().filter(v -> "plugin_b".equals(v.getName())).findFirst().orElseThrow();
        assertThat(a.getStatus()).isEqualTo("enabled");
        assertThat(a.getSource()).isEqualTo("upload");
        assertThat(a.getDescription()).isEqualTo("desc A");
        assertThat(b.getStatus()).isEqualTo("disabled");
        assertThat(b.getSource()).isEqualTo("panel");
    }

    private FileInfo dirInfo(String name) {
        FileInfo f = new FileInfo();
        f.setName(name);
        f.setDirectory(true);
        return f;
    }
}
