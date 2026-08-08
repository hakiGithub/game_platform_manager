package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CvarBlacklist 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class CvarBlacklistTest {

    @Test
    void check_shouldRejectRconPassword() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> CvarBlacklist.check("rcon_password"));
        assertTrue(ex.getMessage().contains("rcon_password"));
    }

    @Test
    void check_shouldRejectSvCheats() {
        assertThrows(L4D2PluginException.class,
                () -> CvarBlacklist.check("sv_cheats"));
    }

    @Test
    void check_shouldRejectCaseInsensitive() {
        assertThrows(L4D2PluginException.class,
                () -> CvarBlacklist.check("RCON_PASSWORD"));
        assertThrows(L4D2PluginException.class,
                () -> CvarBlacklist.check("Sv_Cheats"));
    }

    @Test
    void check_shouldPassSafeCvar() {
        assertDoesNotThrow(() -> CvarBlacklist.check("l4d2_max_players"));
        assertDoesNotThrow(() -> CvarBlacklist.check("sm_dp"));
        assertDoesNotThrow(() -> CvarBlacklist.check("mp_gamemode"));
    }

    @Test
    void check_shouldRejectNull() {
        assertThrows(L4D2PluginException.class, () -> CvarBlacklist.check(null));
    }

    @Test
    void check_shouldRejectBlank() {
        assertThrows(L4D2PluginException.class, () -> CvarBlacklist.check("   "));
    }

    @Test
    void isDangerous_shouldReturnBoolean() {
        assertTrue(CvarBlacklist.isDangerous("rcon_password"));
        assertTrue(CvarBlacklist.isDangerous("sv_cheats"));
        assertFalse(CvarBlacklist.isDangerous("sm_dp"));
        assertFalse(CvarBlacklist.isDangerous(null));
        assertFalse(CvarBlacklist.isDangerous(""));
    }
}
