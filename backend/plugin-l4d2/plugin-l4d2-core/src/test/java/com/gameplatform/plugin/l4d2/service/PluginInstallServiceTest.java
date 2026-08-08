package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.util.GbkCodecUtil;
import com.gameplatform.plugin.l4d2.vo.EnabledPlugin;
import com.gameplatform.plugin.l4d2.vo.PluginListVO;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PluginInstallService 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginInstallServiceTest {

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private InstanceFileService instanceFileService;

    @Mock
    private RconService rconService;

    @Mock
    private FileRefsService fileRefsService;

    @Mock
    private PluginMetaService pluginMetaService;

    @Mock
    private EnabledPluginsService enabledPluginsService;

    private final L4D2PathResolver pathResolver = new L4D2PathResolver();

    private PluginInstallService pluginInstallService;

    private InstanceVO instance;

    @BeforeEach
    void setUp() {
        // 使用真实路径解析器，便于验证拼接结果
        pluginInstallService = new PluginInstallService(
                instanceQueryService, instanceFileService, rconService,
                fileRefsService, pathResolver,
                pluginMetaService, enabledPluginsService);
        instance = new InstanceVO();
        instance.setId(1L);
        instance.setHostId(10L);
        instance.setInstallPath("/home/l4d2");
        lenient().when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);
    }

    private FileInfo file(String name, boolean directory) {
        FileInfo f = new FileInfo();
        f.setName(name);
        f.setDirectory(directory);
        return f;
    }

    @Test
    void listPlugins_shouldThrowWhenInstanceNotFound() {
        when(instanceQueryService.getInstanceById(999L)).thenReturn(null);
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> pluginInstallService.listPlugins(999L));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    @Test
    void disableAllPlugins_shouldDisableAndUnloadAllEnabledPlugins() {
        // 模拟 enabled_plugins.yaml 中有 2 个已启用插件
        EnabledPlugin ep1 = new EnabledPlugin();
        ep1.setName("pluginA");
        EnabledPlugin ep2 = new EnabledPlugin();
        ep2.setName("pluginB");
        when(enabledPluginsService.list(1L)).thenReturn(Arrays.asList(ep1, ep2));
        when(enabledPluginsService.isEnabled(1L, "pluginA")).thenReturn(true);
        when(enabledPluginsService.isEnabled(1L, "pluginB")).thenReturn(true);

        // listPluginSmxIds 扫描库目录（每个插件 1 个 smx）
        when(instanceFileService.listFiles(1L,
                "left4dead2/addons/sourcemod/plugins_store/pluginA/left4dead2/addons/sourcemod/plugins"))
                .thenReturn(Collections.singletonList(file("pluginA.smx", false)));
        when(instanceFileService.listFiles(1L,
                "left4dead2/addons/sourcemod/plugins_store/pluginB/left4dead2/addons/sourcemod/plugins"))
                .thenReturn(Collections.singletonList(file("pluginB.smx", false)));

        // removeRefs 返回空（无归零文件）
        when(fileRefsService.removeRefs(1L, "pluginA")).thenReturn(List.of());
        when(fileRefsService.removeRefs(1L, "pluginB")).thenReturn(List.of());

        pluginInstallService.disableAllPlugins(1L);

        // 验证对每个已启用插件调用了 RCON unload 和 enabledPluginsService.remove
        verify(rconService).executeCommand(eq(1L), eq("sm plugins unload pluginA"));
        verify(rconService).executeCommand(eq(1L), eq("sm plugins unload pluginB"));
        verify(enabledPluginsService).remove(1L, "pluginA");
        verify(enabledPluginsService).remove(1L, "pluginB");
    }

    @Test
    void deletePlugin_shouldRemoveStoreDirAndMetaWhenNotEnabled() {
        // 插件未启用，允许删除
        when(enabledPluginsService.isEnabled(1L, "pluginA")).thenReturn(false);

        pluginInstallService.deletePlugin(1L, "pluginA");

        // 应删除整个 plugins_store/pluginA/ 目录（递归）
        verify(instanceFileService).deleteDirectory(1L,
                "left4dead2/addons/sourcemod/plugins_store/pluginA", true);
        // 应清理 plugin.yaml
        verify(pluginMetaService).delete(1L, "pluginA");
        // 不应再调用旧的 removeRefs / deleteFile 逻辑
        verify(fileRefsService, never()).removeRefs(anyLong(), anyString());
        verify(instanceFileService, never()).deleteFile(anyLong(), anyString());
    }

    @Test
    void deletePlugin_shouldRejectIfEnabled() {
        when(enabledPluginsService.isEnabled(1L, "pluginA")).thenReturn(true);

        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> pluginInstallService.deletePlugin(1L, "pluginA"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        // 不应执行任何删除
        verify(instanceFileService, never()).deleteDirectory(anyLong(), anyString(), anyBoolean());
        verify(pluginMetaService, never()).delete(anyLong(), anyString());
    }

    @Test
    void deletePlugin_shouldRejectInvalidPluginName() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> pluginInstallService.deletePlugin(1L, "../etc/passwd"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        verifyNoInteractions(instanceFileService);
    }

    @Test
    void listEnabledPlugins_shouldReturnOnlyPluginsDirectorySmx() {
        when(instanceFileService.listFiles(1L, "left4dead2/addons/sourcemod/plugins"))
                .thenReturn(Arrays.asList(
                        file("pluginA.smx", false),
                        file("pluginB.smx", false)));

        List<String> names = pluginInstallService.listEnabledPlugins(1L);

        assertEquals(2, names.size());
        assertTrue(names.contains("pluginA"));
        assertTrue(names.contains("pluginB"));
    }
}
