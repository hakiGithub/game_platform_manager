package com.gameplatform.plugin.l4d2.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ZipSlipGuard 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class ZipSlipGuardTest {

    private static final String TARGET_DIR = "addons/sourcemod/plugins_store/plugin-a/left4dead2";

    @Test
    void shouldAcceptNormalPath() {
        String safe = ZipSlipGuard.normalizeAndCheck(
                "addons/sourcemod/plugins/plugin-a.smx", TARGET_DIR);
        assertEquals(
                "addons/sourcemod/plugins_store/plugin-a/left4dead2/addons/sourcemod/plugins/plugin-a.smx",
                safe);
    }

    @Test
    void shouldRejectPathTraversal() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ZipSlipGuard.normalizeAndCheck("../../etc/passwd", TARGET_DIR));
        assertTrue(ex.getMessage().contains("Zip Slip"));
    }

    @Test
    void shouldRejectAbsolutePath() {
        assertThrows(IllegalArgumentException.class,
                () -> ZipSlipGuard.normalizeAndCheck("/etc/passwd", TARGET_DIR));
    }

    @Test
    void shouldNormalizeBackslash() {
        String safe = ZipSlipGuard.normalizeAndCheck(
                "addons\\sourcemod\\plugins\\plugin-a.smx", TARGET_DIR);
        assertEquals(
                "addons/sourcemod/plugins_store/plugin-a/left4dead2/addons/sourcemod/plugins/plugin-a.smx",
                safe);
    }

    @Test
    void shouldStripLeadingDotSlash() {
        String safe = ZipSlipGuard.normalizeAndCheck(
                "./addons/sourcemod/plugins/plugin-a.smx", TARGET_DIR);
        assertEquals(
                "addons/sourcemod/plugins_store/plugin-a/left4dead2/addons/sourcemod/plugins/plugin-a.smx",
                safe);
    }

    @Test
    void shouldCollapseInternalDotSlash() {
        String safe = ZipSlipGuard.normalizeAndCheck(
                "addons/./sourcemod/plugins/plugin-a.smx", TARGET_DIR);
        assertEquals(
                "addons/sourcemod/plugins_store/plugin-a/left4dead2/addons/sourcemod/plugins/plugin-a.smx",
                safe);
    }

    @Test
    void shouldRejectMacosxJunk() {
        assertTrue(ZipSlipGuard.isMacOSJunk("__MACOSX/._plugin-a.smx"));
        assertTrue(ZipSlipGuard.isMacOSJunk("__MACOSX/foo/bar"));
        assertTrue(ZipSlipGuard.isMacOSJunk(".DS_Store"));
        assertTrue(ZipSlipGuard.isMacOSJunk("path/to/.DS_Store"));
    }

    @Test
    void shouldNotFlagNonMacosxEntries() {
        assertFalse(ZipSlipGuard.isMacOSJunk("addons/sourcemod/plugins/plugin-a.smx"));
        assertFalse(ZipSlipGuard.isMacOSJunk(null));
    }

    @Test
    void shouldHandleEmptyTargetDir() {
        String safe = ZipSlipGuard.normalizeAndCheck("plugin-a.smx", "");
        assertEquals("plugin-a.smx", safe);
    }

    @Test
    void shouldHandleNullTargetDir() {
        String safe = ZipSlipGuard.normalizeAndCheck("plugin-a.smx", null);
        assertEquals("plugin-a.smx", safe);
    }
}
