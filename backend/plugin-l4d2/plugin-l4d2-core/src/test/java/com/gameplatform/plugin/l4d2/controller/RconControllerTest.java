package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.ChangeMapDTO;
import com.gameplatform.plugin.l4d2.dto.RconCommandDTO;
import com.gameplatform.plugin.l4d2.service.L4D2RconService;
import com.gameplatform.plugin.l4d2.service.MapService;
import com.gameplatform.plugin.l4d2.vo.ChangeMapResultVO;
import com.gameplatform.plugin.l4d2.vo.RconResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RconControllerTest {

    private L4D2RconService service;
    private MapService mapService;
    private RconController controller;

    @BeforeEach
    void setUp() {
        service = mock(L4D2RconService.class);
        mapService = mock(MapService.class);
        controller = new RconController(service, mapService);
    }

    @Test
    void changeMap_confirmedReturnsStructuredSuccess() {
        L4D2RconService.ChangeMapResult outcome =
                new L4D2RconService.ChangeMapResult("c1m1_hotel", "Changing map...");
        outcome.setAccepted(true);
        outcome.setConfirmed(true);
        outcome.setCurrentMap("c1m1_hotel");
        when(mapService.changeMap(1L, "c1m1_hotel")).thenReturn(outcome);

        Result<ChangeMapResultVO> result = controller.changeMap(changeMapDto(1L, "c1m1_hotel"));

        assertEquals(200, result.getCode());
        assertTrue(result.getData().getConfirmed());
        assertEquals("c1m1_hotel", result.getData().getCurrentMap());
    }

    @Test
    void changeMap_rejectedReturnsBusinessFailureAndData() {
        L4D2RconService.ChangeMapResult outcome =
                new L4D2RconService.ChangeMapResult("missing", "No such map 'missing'");
        outcome.setError("服务器拒绝换图: No such map 'missing'");
        when(mapService.changeMap(1L, "missing")).thenReturn(outcome);

        Result<ChangeMapResultVO> result = controller.changeMap(changeMapDto(1L, "missing"));

        assertEquals(400, result.getCode());
        assertFalse(result.getData().getAccepted());
        assertTrue(result.getData().getOutput().contains("No such map"));
        assertTrue(result.getMessage().contains("missing"));
    }

    @Test
    void changeMap_transportFailureReturnsBusinessFailure() {
        when(mapService.changeMap(1L, "c1m1_hotel"))
                .thenThrow(new RuntimeException("RCON unavailable"));

        Result<ChangeMapResultVO> result = controller.changeMap(changeMapDto(1L, "c1m1_hotel"));

        assertEquals(400, result.getCode());
        assertFalse(result.getData().getAccepted());
        assertTrue(result.getMessage().contains("unavailable"));
    }

    @Test
    void executeCommand_marksFailureEchoAsNotSuccessful() {
        when(service.executeCommand(1L, "changelevel missing"))
                .thenReturn("changelevel failed: missing not found");

        Result<RconResultVO> result = controller.executeCommand(commandDto(1L, "changelevel missing"));

        assertEquals(200, result.getCode());
        assertFalse(result.getData().getSuccess());
        assertTrue(result.getData().getError().contains("changelevel failed"));
    }

    @Test
    void executeCommand_successEchoRemainsSuccessful() {
        when(service.executeCommand(1L, "status")).thenReturn("map : c1m1_hotel");

        Result<RconResultVO> result = controller.executeCommand(commandDto(1L, "status"));

        assertTrue(result.getData().getSuccess());
        assertNotNull(result.getData().getOutput());
    }

    private ChangeMapDTO changeMapDto(long instanceId, String mapName) {
        ChangeMapDTO dto = new ChangeMapDTO();
        dto.setInstanceId(instanceId);
        dto.setMapName(mapName);
        return dto;
    }

    private RconCommandDTO commandDto(long instanceId, String command) {
        RconCommandDTO dto = new RconCommandDTO();
        dto.setInstanceId(instanceId);
        dto.setCommand(command);
        return dto;
    }
}
