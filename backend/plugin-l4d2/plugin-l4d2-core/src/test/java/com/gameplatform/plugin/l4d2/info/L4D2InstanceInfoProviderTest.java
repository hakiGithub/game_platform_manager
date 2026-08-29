package com.gameplatform.plugin.l4d2.info;

import com.gameplatform.plugin.l4d2.service.L4D2RconService;
import com.gameplatform.plugin.service.RconService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * L4D2InstanceInfoProvider 解析测试（ADR-0017）。
 * <p>
 * 只 mock 宿主 {@link RconService} 接口；语义层与 Provider 用真实实例，
 * 保证解析逻辑不被 mock 默认值干扰。
 */
@ExtendWith(MockitoExtension.class)
class L4D2InstanceInfoProviderTest {

    @Mock
    private RconService hostRconService;

    private L4D2RconService l4d2RconService;
    private L4D2InstanceInfoProvider provider;

    @BeforeEach
    void setUp() {
        l4d2RconService = new L4D2RconService(hostRconService);
        provider = new L4D2InstanceInfoProvider(l4d2RconService);
    }

    private static final String STATUS_OUTPUT = """
            hostname: Local Server
            version : 2.0.0.1 8291 secure
            udp/ip  : 0.0.0.0:27015
            map     : c1m1_hotel at: 0 x, 0 y, 0 z
            players : 3 humans, 1 bots (8 max)

            # userid name uniqueid connected ping loss state rate adr
            # 2 "Player1" STEAM_1:0:123 12:34 45 0 active 30000
            """;

    @Test
    void extractPlayerCount_fromStatus() {
        assertEquals(3, l4d2RconService.extractPlayerCount(STATUS_OUTPUT));
    }

    @Test
    void extractPlayerCount_zeroHumans() {
        assertEquals(0, l4d2RconService.extractPlayerCount("players : 0 humans, 0 bots (8 max)"));
    }

    @Test
    void extractPlayerCount_unparseable_returnsNull() {
        assertNull(l4d2RconService.extractPlayerCount("Server not running"));
        assertNull(l4d2RconService.extractPlayerCount(""));
        assertNull(l4d2RconService.extractPlayerCount(null));
    }

    @Test
    void getInstanceInfo_returnsPlayerCount() {
        when(hostRconService.executeCommand(56L, "status")).thenReturn(STATUS_OUTPUT);

        var info = provider.getInstanceInfo(56L);

        assertNotNull(info);
        assertEquals(3, info.playerCount());
        assertTrue(info.extras().isEmpty());
    }

    @Test
    void getInstanceInfo_unparseableStatus_returnsNull() {
        when(hostRconService.executeCommand(56L, "status")).thenReturn("Server not running");

        assertNull(provider.getInstanceInfo(56L));
    }

    @Test
    void getInstanceInfo_rconFailure_propagates() {
        when(hostRconService.executeCommand(56L, "status")).thenThrow(new RuntimeException("rcon down"));

        assertThrows(RuntimeException.class, () -> provider.getInstanceInfo(56L));
    }
}
