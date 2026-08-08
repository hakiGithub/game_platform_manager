package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.vo.PresetDetailVO;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PresetServiceApplyTest {

    private PresetService service;
    private PluginInstallService pluginInstallService;
    private SourceModCfgService cfgService;
    private BackupService backupService;
    private InstanceQueryService instanceQueryService;
    private PlatformPluginInstaller platformPluginInstaller;

    @BeforeEach
    void setUp() {
        pluginInstallService = mock(PluginInstallService.class);
        cfgService = mock(SourceModCfgService.class);
        backupService = mock(BackupService.class);
        instanceQueryService = mock(InstanceQueryService.class);
        platformPluginInstaller = mock(PlatformPluginInstaller.class);
        service = new PresetService(pluginInstallService, cfgService, backupService,
                instanceQueryService, platformPluginInstaller);
        service.loadPresetYaml();

        // 默认 mock：实例存在 + Docker 部署（平台插件 = linux）+ 所有插件存在
        InstanceVO dockerInstance = new InstanceVO();
        dockerInstance.setId(100L);
        dockerInstance.setGameCode("l4d2");
        dockerInstance.setDeployType("docker-compose");
        when(instanceQueryService.getInstanceById(eq(100L))).thenReturn(dockerInstance);
        // 所有插件均存在（包含平台插件 "1.11插件平台linux版"）
        when(pluginInstallService.pluginExists(eq(100L), anyString())).thenReturn(true);
    }

    @Test
    void apply_shouldDisableAllThenEnablePluginsAndApplyConfigs() {
        PresetDetailVO preset = service.detail("multi-versus");

        service.apply(100L, "multi-versus");

        // 1. 禁用所有插件
        verify(pluginInstallService).disableAllPlugins(eq(100L));
        // 2. 启用预设中每个插件
        verify(pluginInstallService, atLeastOnce()).enableAndLoad(eq(100L), eq("l4d2_ai_damagefix"));
        verify(pluginInstallService, atLeastOnce()).enableAndLoad(eq(100L), eq("l4d2_vs_new_item_spawn"));
        verify(pluginInstallService, atLeastOnce()).enableAndLoad(eq(100L), eq("l4d2_multi_slot"));
        // 3. 应用配置覆盖（multi-versus 中 l4d2_multi_slot 有 configs）
        verify(cfgService, atLeastOnce()).updateOrCreateConfig(eq(100L),
                eq("l4d2_multi_slot"), eq("l4d2_multi_slot.cfg"), anyMap());
    }

    @Test
    void apply_shouldThrowWhenPresetNotFound() {
        assertThrows(L4D2PluginException.class,
                () -> service.apply(100L, "nonexistent-preset"));
    }

    @Test
    void apply_shouldPreValidateAllPluginsExistAndThrowIfMissing() {
        // 假设 l4d2_multi_slot 不存在
        when(pluginInstallService.pluginExists(eq(100L), eq("l4d2_multi_slot"))).thenReturn(false);

        assertThrows(L4D2PluginException.class,
                () -> service.apply(100L, "multi-versus"));

        // 预校验失败应直接返回，不调用任何 enable/disable
        verify(pluginInstallService, never()).enableAndLoad(anyLong(), anyString());
        verify(pluginInstallService, never()).disableAllPlugins(anyLong());
    }

    @Test
    void apply_shouldEnablePlatformPluginFirstBeforeOthers() {
        service.apply(100L, "multi-versus");

        // 验证平台插件最先被启用（在 disableAllPlugins 之后、其他插件之前）
        InOrder inOrder = inOrder(pluginInstallService);
        inOrder.verify(pluginInstallService).disableAllPlugins(eq(100L));
        // Docker 部署 → 平台插件为 "1.11插件平台linux版"
        inOrder.verify(pluginInstallService).enableAndLoad(eq(100L), eq("1.11插件平台linux版"));
        inOrder.verify(pluginInstallService).enableAndLoad(eq(100L), eq("l4d2_ai_damagefix"));
    }

    @Test
    void apply_shouldThrowWhenPlatformPluginFails() {
        // 平台插件启用失败
        doThrow(new RuntimeException("platform plugin load failed"))
                .when(pluginInstallService).enableAndLoad(eq(100L), eq("1.11插件平台linux版"));

        assertThrows(L4D2PluginException.class,
                () -> service.apply(100L, "multi-versus"));

        // 平台插件失败后，不应继续启用其他插件
        verify(pluginInstallService, never()).enableAndLoad(eq(100L), eq("l4d2_ai_damagefix"));
    }

    @Test
    void apply_shouldThrowWhenPlatformPluginInstallFails() {
        // 平台插件不存在 + 内置安装失败
        when(pluginInstallService.pluginExists(eq(100L), eq("1.11插件平台linux版"))).thenReturn(false);
        doThrow(new L4D2PluginException(L4D2PluginException.FILE, "内置 ZIP 损坏"))
                .when(platformPluginInstaller).install(eq(100L));

        assertThrows(L4D2PluginException.class,
                () -> service.apply(100L, "multi-versus"));

        // 安装失败，不应调用任何 enable/disable
        verify(pluginInstallService, never()).disableAllPlugins(anyLong());
        verify(pluginInstallService, never()).enableAndLoad(anyLong(), anyString());
    }

    @Test
    void apply_shouldAutoInstallPlatformPluginWhenMissing() {
        // 平台插件不存在 + 内置安装成功（platform = PLATFORM_PLUGIN_NAME）
        when(pluginInstallService.pluginExists(eq(100L), eq("1.11插件平台linux版"))).thenReturn(false);
        when(platformPluginInstaller.install(eq(100L))).thenReturn("平台插件安装成功");

        service.apply(100L, "multi-versus");

        // 应该自动调用内置安装
        verify(platformPluginInstaller, times(1)).install(eq(100L));
        // 安装后应继续启用平台插件
        verify(pluginInstallService, times(1)).enableAndLoad(eq(100L), eq("1.11插件平台linux版"));
    }

    @Test
    void apply_shouldResolvePlatformPluginByDeployType() {
        // Native 部署 → 使用后端 OS 判断（测试运行在 Windows 上 → windows 平台插件）
        InstanceVO nativeInstance = new InstanceVO();
        nativeInstance.setId(200L);
        nativeInstance.setDeployType("native");
        when(instanceQueryService.getInstanceById(eq(200L))).thenReturn(nativeInstance);
        when(pluginInstallService.pluginExists(eq(200L), anyString())).thenReturn(true);

        service.apply(200L, "multi-versus");

        // Native + Windows 后端 → 平台插件 = "1.11插件平台windows版"
        // Docker 部署会返回 linux，但 Native 使用后端 OS 判断
        String expectedPlatform = System.getProperty("os.name", "").toLowerCase().contains("windows")
                ? "1.11插件平台windows版" : "1.11插件平台linux版";
        verify(pluginInstallService).enableAndLoad(eq(200L), eq(expectedPlatform));
    }
}
