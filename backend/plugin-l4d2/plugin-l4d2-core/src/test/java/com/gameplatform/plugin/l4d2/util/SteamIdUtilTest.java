package com.gameplatform.plugin.l4d2.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SteamIdUtilTest {

    @Test
    void toSteam64_shouldConvertSteamId2() {
        // STEAM_0:1:1234 → 76561197960265728 + 2*1234 + 1 = 76561197960268197
        // base = 76561197960265728, z=1234, y=1 → base + z*2 + y = 76561197960265728 + 2468 + 1 = 76561197960268197
        assertEquals(76561197960268197L, SteamIdUtil.toSteam64("STEAM_0:1:1234"));
    }

    @Test
    void toSteamId2_shouldConvertSteam64() {
        assertEquals("STEAM_0:1:1234", SteamIdUtil.toSteamId2(76561197960268197L));
    }

    @Test
    void isValid_shouldAcceptSteamId2() {
        assertTrue(SteamIdUtil.isValid("STEAM_0:1:1234"));
    }

    @Test
    void isValid_shouldAcceptSteam64() {
        assertTrue(SteamIdUtil.isValid("76561197960268197"));
    }

    @Test
    void isValid_shouldRejectInvalid() {
        assertFalse(SteamIdUtil.isValid("invalid"));
    }

    @Test
    void isValid_shouldRejectEmpty() {
        assertFalse(SteamIdUtil.isValid(""));
        assertFalse(SteamIdUtil.isValid(null));
    }
}
