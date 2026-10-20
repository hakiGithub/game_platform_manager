package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.service.RconService;
import com.gameplatform.plugin.l4d2.util.StatusParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class L4D2RconServiceChangeMapTest {

    private RconService rcon;
    private L4D2RconService service;

    @BeforeEach
    void setUp() {
        rcon = mock(RconService.class);
        service = new L4D2RconService(rcon, new StatusParser());
    }

    @Test
    void changeMap_rejectedReturnsRawOutputAndError() {
        String output = "CModelLoader::Map_IsValid: No such map 'maps/missing.bsp'\n"
                + "changelevel failed: missing not found";
        when(rcon.executeCommand(1L, "changelevel missing")).thenReturn(output);

        L4D2RconService.ChangeMapResult result = service.changeMap(1L, "missing");

        assertFalse(result.getAccepted());
        assertFalse(result.getConfirmed());
        assertEquals(output, result.getOutput());
        assertTrue(result.getError().contains("No such map"));
        verify(rcon, never()).executeCommand(1L, "status");
    }

    @Test
    void changeMap_confirmsRequestedMapFromStatus() {
        when(rcon.executeCommand(1L, "changelevel c1918_red_square_c1"))
                .thenReturn("Changing map...");
        when(rcon.executeCommand(1L, "status"))
                .thenReturn("map     : c1918_red_square_c1\nplayers : 0 humans, 0 bots (8 max)");

        L4D2RconService.ChangeMapResult result =
                service.changeMap(1L, "c1918_red_square_c1", Duration.ofSeconds(1), Duration.ZERO);

        assertTrue(result.getAccepted());
        assertTrue(result.getConfirmed());
        assertEquals("c1918_red_square_c1", result.getCurrentMap());
        assertEquals(null, result.getError());
    }

    @Test
    void changeMap_timeoutReturnsAcceptedButUnconfirmed() {
        when(rcon.executeCommand(1L, "changelevel c2m1_highway"))
                .thenReturn("Changing map...");
        when(rcon.executeCommand(1L, "status"))
                .thenReturn("map     : c1m1_hotel\nplayers : 0 humans, 0 bots (8 max)");

        L4D2RconService.ChangeMapResult result =
                service.changeMap(1L, "c2m1_highway", Duration.ZERO, Duration.ZERO);

        assertTrue(result.getAccepted());
        assertFalse(result.getConfirmed());
        assertEquals("c1m1_hotel", result.getCurrentMap());
        assertTrue(result.getError().contains("未在"));
    }

    @Test
    void changeMap_transportFailurePropagates() {
        when(rcon.executeCommand(1L, "changelevel c1m1_hotel"))
                .thenThrow(new RuntimeException("RCON connection refused"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.changeMap(1L, "c1m1_hotel"));

        assertTrue(ex.getMessage().contains("connection refused"));
    }

    @Test
    void isMapRegistered_matchesCaseAndBspSuffix() {
        when(rcon.executeCommand(1L, "maps *"))
                .thenReturn("""
                        L4D2_Brooklyn_part4.bsp
                        l4d2_broadway_d4.bsp (fs)
                        """);

        assertTrue(service.isMapRegistered(1L, "L4D2_Brooklyn_part4"));
        assertTrue(service.isMapRegistered(1L, "L4D2_BROADWAY_D4.BSP"));
        assertFalse(service.isMapRegistered(1L, "l4d2_chinatown_d2"));
    }
}
