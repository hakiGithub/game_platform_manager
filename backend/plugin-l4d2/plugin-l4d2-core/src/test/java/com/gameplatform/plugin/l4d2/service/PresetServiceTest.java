package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.vo.PresetDetailVO;
import com.gameplatform.plugin.l4d2.vo.PresetPlugin;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PresetService 单元测试。
 *
 * <p>v2.0：对齐新 preset.yaml 结构（plugins[]/configs[]/values），不再使用
 * enabledPlugins/disabledPlugins/configOverrides 旧字段。
 *
 * @author GamePlatform
 * @version 2.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresetServiceTest {

    @Mock
    private PluginInstallService pluginInstallService;

    @Mock
    private SourceModCfgService cfgService;

    @Mock
    private BackupService backupService;

    @Mock
    private InstanceQueryService instanceQueryService;

    @Mock
    private PlatformPluginInstaller platformPluginInstaller;

    private PresetService presetService;

    @BeforeEach
    void setUp() {
        presetService = new PresetService(pluginInstallService, cfgService, backupService,
                instanceQueryService, platformPluginInstaller);
        presetService.loadPresetYaml();

        // 默认 mock：所有实例均为 Docker 部署（平台插件 = linux）+ 所有插件存在
        InstanceVO dockerInstance = new InstanceVO();
        dockerInstance.setDeployType("docker-compose");
        org.mockito.Mockito.when(instanceQueryService.getInstanceById(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(dockerInstance);
        org.mockito.Mockito.when(pluginInstallService.pluginExists(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    @Test
    void loadPresetYaml_shouldLoadFourPresets() {
        assertEquals(4, presetService.list().size());
    }

    @Test
    void list_shouldReturnAllPresets() {
        List<PresetDetailVO> list = presetService.list();
        assertEquals(4, list.size());
        List<String> ids = list.stream().map(PresetDetailVO::getId).toList();
        org.junit.jupiter.api.Assertions.assertTrue(ids.contains("multi-versus"));
        org.junit.jupiter.api.Assertions.assertTrue(ids.contains("fun-versus"));
        org.junit.jupiter.api.Assertions.assertTrue(ids.contains("pure-coop"));
        org.junit.jupiter.api.Assertions.assertTrue(ids.contains("official-roguelike"));
    }

    @Test
    void detail_shouldReturnPresetWhenIdExists() {
        PresetDetailVO vo = presetService.detail("multi-versus");
        assertNotNull(vo);
        assertEquals("多特战役", vo.getName());
        assertEquals("versus", vo.getGameMode());
        assertEquals(8, vo.getMaxPlayers());
        // 新 yaml 中 multi-versus 的 platform 为空字符串
        assertEquals("", vo.getPlatform());
        // plugins 列表（替代旧的 enabledPlugins）
        assertNotNull(vo.getPlugins());
        assertEquals(3, vo.getPlugins().size());
        List<String> pluginNames = vo.getPlugins().stream().map(PresetPlugin::getName).toList();
        org.junit.jupiter.api.Assertions.assertTrue(pluginNames.contains("l4d2_ai_damagefix"));
        org.junit.jupiter.api.Assertions.assertTrue(pluginNames.contains("l4d2_multi_slot"));
    }

    @Test
    void detail_shouldReturnNullWhenIdNotExists() {
        PresetDetailVO vo = presetService.detail("non-existent");
        assertNull(vo);
    }

    @Test
    void apply_shouldThrowWhenPresetIdNotExists() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> presetService.apply(1L, "non-existent"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    @Test
    void apply_multiVersus_shouldFollowFullFlow() {
        Long instanceId = 1L;

        presetService.apply(instanceId, "multi-versus");

        // 1. 禁用所有插件调用 1 次
        verify(pluginInstallService, times(1)).disableAllPlugins(instanceId);
        // 2. platform 为空字符串 → 不调用 enablePlatformPlugins
        verify(pluginInstallService, never()).enablePlatformPlugins(anyLong(), anyString());
        // 3. 启用预设中每个插件（plugins 数量）
        verify(pluginInstallService, times(1)).enableAndLoad(instanceId, "l4d2_ai_damagefix");
        verify(pluginInstallService, times(1)).enableAndLoad(instanceId, "l4d2_vs_new_item_spawn");
        verify(pluginInstallService, times(1)).enableAndLoad(instanceId, "l4d2_multi_slot");
        // 4. l4d2_multi_slot 有 configs → 调用 updateOrCreateConfig
        verify(cfgService, times(1)).updateOrCreateConfig(
                eq(instanceId), eq("l4d2_multi_slot"), eq("l4d2_multi_slot.cfg"), anyMap());
    }

    @Test
    void apply_pureCoop_shouldNotCallEnablePlatformPlugins() {
        Long instanceId = 2L;
        presetService.apply(instanceId, "pure-coop");

        // 1. 禁用所有插件调用 1 次
        verify(pluginInstallService, times(1)).disableAllPlugins(instanceId);
        // 2. platform 为 null → 不调用 enablePlatformPlugins
        verify(pluginInstallService, never()).enablePlatformPlugins(anyLong(), anyString());
        // 3. 启用预设插件 1 次
        verify(pluginInstallService, times(1)).enableAndLoad(instanceId, "l4d2_ai_damagefix");
        // 4. pure-coop 无 configs → 不调用 updateOrCreateConfig
        verify(cfgService, never()).updateOrCreateConfig(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void apply_officialRoguelike_shouldApplyConfigsWithCorrectValues() {
        Long instanceId = 3L;

        presetService.apply(instanceId, "official-roguelike");

        verify(pluginInstallService, times(1)).disableAllPlugins(instanceId);
        // platform 为 null → 不调用 enablePlatformPlugins
        verify(pluginInstallService, never()).enablePlatformPlugins(anyLong(), anyString());
        verify(pluginInstallService, times(1)).enableAndLoad(instanceId, "l4d2_roguelike_core");
        verify(pluginInstallService, times(1)).enableAndLoad(instanceId, "l4d2_roguelike_buffs");
        verify(pluginInstallService, times(1)).enableAndLoad(instanceId, "l4d2_ai_damagefix");
        // 验证 updateOrCreateConfig 被调用且 values 正确
        Map<String, String> expected = Map.of("difficulty_curve", "1.2", "max_buff_stacks", "5");
        verify(cfgService, times(1)).updateOrCreateConfig(
                eq(instanceId), eq("l4d2_roguelike_core"), eq("l4d2_roguelike_core.cfg"), eq(expected));
    }

    @Test
    void apply_shouldContinueWhenPluginEnableFails() {
        Long instanceId = 4L;
        // 模拟第一个插件启用失败
        org.mockito.Mockito.doThrow(new L4D2PluginException(L4D2PluginException.RCON, "rcon fail"))
                .when(pluginInstallService).enableAndLoad(instanceId, "l4d2_ai_damagefix");

        presetService.apply(instanceId, "pure-coop");

        // disableAllPlugins 仍被调用
        verify(pluginInstallService, times(1)).disableAllPlugins(instanceId);
        // enableAndLoad 调用尝试过（即便抛异常）
        verify(pluginInstallService, times(1)).enableAndLoad(instanceId, "l4d2_ai_damagefix");
    }

    @Test
    void apply_shouldContinueEvenIfBackupFails() {
        Long instanceId = 5L;
        // 模拟备份失败
        org.mockito.Mockito.doThrow(new L4D2PluginException(L4D2PluginException.BUSINESS, "backup fail"))
                .when(backupService).create(eq(instanceId), anyString(), anyString());

        // 备份失败不应阻塞预设应用
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> presetService.apply(instanceId, "multi-versus"));

        // 预设应用流程仍应继续：禁用所有插件
        verify(pluginInstallService, times(1)).disableAllPlugins(instanceId);
    }
}
