package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
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
 * 插件启用单测（无 RCON 语义，重启服务器后生效）。
 *
 * <p>批量启用：每插件一次目录复制 + 登记；无任何 RCON 交互；
 * 单插件失败不影响其余（错误收集返回）。</p>
 */
class PluginInstallServiceEnableTest {

    private PluginInstallService service;
    private InstanceFileService instanceFileService;
    private FileRefsService fileRefsService;
    private EnabledPluginsService enabledPluginsService;
    private PluginMetaService pluginMetaService;

    @BeforeEach
    void setUp() {
        instanceFileService = mock(InstanceFileService.class);
        fileRefsService = mock(FileRefsService.class);
        enabledPluginsService = mock(EnabledPluginsService.class);
        pluginMetaService = mock(PluginMetaService.class);
        InstanceQueryService instanceQueryService = mock(InstanceQueryService.class);
        InstanceVO instance = new InstanceVO();
        instance.setId(100L);
        when(instanceQueryService.getInstanceById(100L)).thenReturn(instance);

        com.gameplatform.plugin.l4d2.vo.PluginMeta meta = new com.gameplatform.plugin.l4d2.vo.PluginMeta();
        meta.setName("p");
        meta.setFileList(List.of("addons/sourcemod/plugins/p.smx"));
        lenient().when(pluginMetaService.load(anyLong(), anyString())).thenReturn(meta);

        service = new PluginInstallService(
                instanceQueryService,
                instanceFileService,
                fileRefsService,
                new L4D2PathResolver(),
                pluginMetaService,
                enabledPluginsService);
    }

    @Test
    void enableAndLoadBatch_copiesAndRegistersEachPlugin() {
        List<String> errors = service.enableAndLoadBatch(100L, List.of("a", "b", "c"));

        assertThat(errors).isEmpty();
        verify(instanceFileService, times(3)).copyDirectory(anyLong(), anyString(), anyString());
        verify(enabledPluginsService, times(3)).add(anyLong(), any());
        verify(fileRefsService, times(3)).addRefs(anyLong(), anyString(), anyList());
    }

    @Test
    void enableAndLoad_singlePluginPassesOneElement() {
        service.enableAndLoad(100L, "a");

        verify(instanceFileService, times(1)).copyDirectory(anyLong(), anyString(), anyString());
        verify(enabledPluginsService, times(1)).add(anyLong(), any());
    }

    @Test
    void enableAndLoadBatch_partialFailureCollectsErrors() {
        doThrow(new L4D2PluginException(L4D2PluginException.BUSINESS, "复制失败"))
                .when(instanceFileService).copyDirectory(eq(100L), contains("plugins_store/b/"), anyString());

        List<String> errors = service.enableAndLoadBatch(100L, List.of("a", "b", "c"));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("b").contains("复制失败");
        // 其余插件正常启用
        verify(enabledPluginsService, times(2)).add(anyLong(), any());
    }

    @Test
    void enableAndLoad_singlePluginFailureThrows() {
        doThrow(new L4D2PluginException(L4D2PluginException.BUSINESS, "复制失败"))
                .when(instanceFileService).copyDirectory(anyLong(), anyString(), anyString());

        assertThatThrownBy(() -> service.enableAndLoad(100L, "a"))
                .isInstanceOf(L4D2PluginException.class)
                .hasMessageContaining("复制失败");
    }
}
