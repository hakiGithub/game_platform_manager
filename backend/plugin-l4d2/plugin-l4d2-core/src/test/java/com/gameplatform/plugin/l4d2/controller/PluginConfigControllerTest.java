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
import static org.mockito.Mockito.verify;

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
}
