package com.gameplatform.plugin.l4d2.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RconFailureDetector 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class RconFailureDetectorTest {

    @Test
    void shouldDetectFailureMarkers() {
        assertTrue(RconFailureDetector.isFailed("unknown command: sm plugins load"));
        assertTrue(RconFailureDetector.isFailed("No such command: foo"));
        assertTrue(RconFailureDetector.isFailed("Failed to load plugin"));
        assertTrue(RconFailureDetector.isFailed("Error: permission denied"));
        assertTrue(RconFailureDetector.isFailed("Plugin not found"));
        assertTrue(RconFailureDetector.isFailed("invalid argument"));
        assertTrue(RconFailureDetector.isFailed("could not connect"));
        assertTrue(RconFailureDetector.isFailed("unable to load"));
        assertTrue(RconFailureDetector.isFailed("Plugin is not loaded"));
        assertTrue(RconFailureDetector.isFailed("no matching plugin"));
    }

    @Test
    void shouldNotFlagSuccessOutput() {
        assertFalse(RconFailureDetector.isFailed(""));
        assertFalse(RconFailureDetector.isFailed("Plugin loaded successfully"));
        assertFalse(RconFailureDetector.isFailed("[SM] Plugin plugin-a.smx loaded"));
        assertFalse(RconFailureDetector.isFailed(null));
    }

    @Test
    void shouldBeCaseInsensitive() {
        assertTrue(RconFailureDetector.isFailed("FAILED TO LOAD"));
        assertTrue(RconFailureDetector.isFailed("ERROR"));
    }
}
