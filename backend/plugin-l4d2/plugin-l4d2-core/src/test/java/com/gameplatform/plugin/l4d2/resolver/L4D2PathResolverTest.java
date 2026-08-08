package com.gameplatform.plugin.l4d2.resolver;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class L4D2PathResolverTest {

    private final L4D2PathResolver resolver = new L4D2PathResolver();

    @Test
    void getGamePath_returnsRelativePath() {
        assertEquals("left4dead2", resolver.getGamePath());
    }

    @Test
    void getAddonsPath_returnsRelativePath() {
        assertEquals("left4dead2/addons", resolver.getAddonsPath());
    }

    @Test
    void getSourceModPluginsPath_returnsRelativePath() {
        assertEquals("left4dead2/addons/sourcemod/plugins", resolver.getSourceModPluginsPath());
    }

    @Test
    void getServerCfgPath_returnsRelativePath() {
        assertEquals("left4dead2/cfg/server.cfg", resolver.getServerCfgPath());
    }

    @Test
    void getAdminsIniPath_returnsRelativePath() {
        assertEquals("left4dead2/addons/sourcemod/configs/admins_simple.ini", resolver.getAdminsIniPath());
    }

    @Test
    void getFileRefsPath_returnsRelativePath() {
        assertEquals("left4dead2/addons/sourcemod/.file_refs.json", resolver.getFileRefsPath());
    }
}
