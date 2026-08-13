package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginInstallServiceDeleteTest {

    private PluginInstallService service;
    private InstanceFileService instanceFileService;
    private EnabledPluginsService enabledPluginsService;
    private PluginMetaService pluginMetaService;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        enabledPluginsService = mock(EnabledPluginsService.class);
        pluginMetaService = mock(PluginMetaService.class);
        InstanceQueryService instanceQueryService = mock(InstanceQueryService.class);
        InstanceVO instance = new InstanceVO();
        instance.setId(100L);
        instance.setHostId(1L);
        when(instanceQueryService.getInstanceById(100L)).thenReturn(instance);

        service = new PluginInstallService(
                instanceQueryService,
                instanceFileService,

                mock(FileRefsService.class),
                new L4D2PathResolver(),
                pluginMetaService,
                enabledPluginsService);
    }

    @Test
    void deletePlugin_shouldRejectIfEnabled() {
        EnabledPlugin enabled = new EnabledPlugin();
        enabled.setName("l4d2_active");
        when(enabledPluginsService.list(eq(100L))).thenReturn(List.of(enabled));
        when(enabledPluginsService.isEnabled(eq(100L), eq("l4d2_active"))).thenReturn(true);

        assertThatThrownBy(() -> service.deletePlugin(100L, "l4d2_active"))
                .isInstanceOf(L4D2PluginException.class)
                .hasMessageContaining("不能删除已启用的插件");

        // 验证未执行删除
        verify(instanceFileService, never()).deleteDirectory(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void deletePlugin_shouldRemoveStoreDirAndMeta() {
        when(enabledPluginsService.list(eq(100L))).thenReturn(List.of());
        when(enabledPluginsService.isEnabled(eq(100L), eq("l4d2_unused"))).thenReturn(false);

        service.deletePlugin(100L, "l4d2_unused");

        // 验证删除整个 plugins_store/l4d2_unused/ 目录
        verify(instanceFileService).deleteDirectory(eq(100L),
                eq("left4dead2/addons/sourcemod/plugins_store/l4d2_unused"), eq(true));
        // 验证清理 plugin.yaml
        verify(pluginMetaService).delete(eq(100L), eq("l4d2_unused"));
    }
}
