package com.gameplatform.plugin.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProgressWindowTest {

    @Test
    void mapsAndClampsChildProgress() {
        ProgressWindow window = new ProgressWindow(20, 60);

        assertEquals(20, window.map(-1));
        assertEquals(40, window.map(50));
        assertEquals(60, window.map(101));
    }

    @Test
    void rejectsInvalidWindow() {
        assertThrows(IllegalArgumentException.class, () -> new ProgressWindow(70, 30));
        assertThrows(IllegalArgumentException.class, () -> new ProgressWindow(-1, 50));
    }
}
