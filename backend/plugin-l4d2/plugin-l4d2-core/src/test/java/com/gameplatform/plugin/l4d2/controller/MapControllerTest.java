package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.MapService;
import com.gameplatform.plugin.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MapControllerTest {

    private MapService mapService;
    private MapController controller;

    @BeforeEach
    void setUp() {
        mapService = mock(MapService.class);
        controller = new MapController(mapService, mock(TaskService.class));
    }

    @Test
    void getMission_pluginBusinessFailureBecomesReadableBusinessException() {
        when(mapService.getMission(97L, "missing.vpk"))
                .thenThrow(new L4D2PluginException(
                        L4D2PluginException.BUSINESS, "地图不存在: missing.vpk"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.getMission(97L, "missing.vpk"));

        assertEquals("地图不存在: missing.vpk", ex.getMessage());
    }

    @Test
    void getMission_pluginFileFailureKeepsSpecificReason() {
        when(mapService.getMission(97L, "empty.vpk"))
                .thenThrow(new L4D2PluginException(
                        L4D2PluginException.FILE, "VPK 中未解析到有效的战役信息: empty.vpk"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.getMission(97L, "empty.vpk"));

        assertEquals("VPK 中未解析到有效的战役信息: empty.vpk", ex.getMessage());
    }
}
