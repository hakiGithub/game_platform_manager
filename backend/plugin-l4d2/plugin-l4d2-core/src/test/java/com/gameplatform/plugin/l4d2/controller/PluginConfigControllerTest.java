package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.plugin.l4d2.dto.PluginRestoreDefaultsDTO;
import com.gameplatform.plugin.l4d2.dto.PluginTempConfigDTO;
import com.gameplatform.plugin.l4d2.service.SourceModCfgService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PluginConfigController 单元测试。
 *
 * <p>验证 apply-temp 与 restore-defaults 端点正确委托给 SourceModCfgService。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class PluginConfigControllerTest {

    @Mock
    private SourceModCfgService sourceModCfgService;

    @InjectMocks
    private PluginConfigController controller;

    @Test
    void applyTemp_shouldDelegateToService() {
        PluginTempConfigDTO dto = new PluginTempConfigDTO();
        dto.setInstanceId(1L);
        dto.setCvarName("l4d2_max_players");
        dto.setCvarValue("8");

        assertDoesNotThrow(() -> controller.applyTemp(dto));

        verify(sourceModCfgService).applyTempConfig(1L, "l4d2_max_players", "8");
    }

    @Test
    void restoreDefaults_shouldDelegateToService() {
        PluginRestoreDefaultsDTO dto = new PluginRestoreDefaultsDTO();
        dto.setInstanceId(1L);
        dto.setPluginName("l4d2_multi_slot");

        assertDoesNotThrow(() -> controller.restoreDefaults(dto));

        verify(sourceModCfgService).restoreDefaults(1L, "l4d2_multi_slot");
    }

    @Test
    void getConfig_插件无候选cfg文件时应返回空配置结构而非null() {
        // 插件没有任何候选 cfg 文件：service 返回 null
        when(sourceModCfgService.getConfig(9L, "no-cfg-plugin")).thenReturn(null);

        com.gameplatform.common.result.Result<com.gameplatform.plugin.l4d2.vo.PluginConfigVO> result =
                controller.get(9L, "no-cfg-plugin");

        // 回归背景：曾返回 data=null，前端 data.items 抛 TypeError 被"加载配置失败"误导
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().getPluginName()).isEqualTo("no-cfg-plugin");
        assertThat(result.getData().getConfigPath()).isNull();
        assertThat(result.getData().getItems()).isEmpty();
    }

    @Test
    void getConfig_存在cfg文件时应映射完整VO() {
        com.gameplatform.plugin.l4d2.extension.PluginConfigResource resource =
                new com.gameplatform.plugin.l4d2.extension.PluginConfigResource();
        com.gameplatform.plugin.l4d2.extension.PluginConfigSpec spec =
                new com.gameplatform.plugin.l4d2.extension.PluginConfigSpec();
        spec.setPluginName("my-plugin");
        spec.setConfigName("my-plugin.cfg");
        spec.setConfigPath("cfg/my-plugin.cfg");
        spec.setItems(java.util.List.of());
        resource.setSpec(spec);
        when(sourceModCfgService.getConfig(9L, "my-plugin")).thenReturn(resource);

        com.gameplatform.common.result.Result<com.gameplatform.plugin.l4d2.vo.PluginConfigVO> result =
                controller.get(9L, "my-plugin");

        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().getPluginName()).isEqualTo("my-plugin");
        assertThat(result.getData().getConfigPath()).isEqualTo("cfg/my-plugin.cfg");
    }
}
