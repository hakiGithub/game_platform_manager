package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.util.StatusParser;
import com.gameplatform.plugin.service.RconService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class L4D2RconServiceDifficultyTest {

    private RconService rcon;
    private L4D2RconService service;

    @BeforeEach
    void setUp() {
        rcon = mock(RconService.class);
        service = new L4D2RconService(rcon, new StatusParser());
    }

    @Test
    void changeDifficulty_usesSourceModSetterAndVerifiesReadback() {
        when(rcon.executeCommand(1L, "sm_cvar z_difficulty Normal"))
                .thenReturn("[SM] Changed cvar \"z_difficulty\" to \"normal\".");
        when(rcon.executeCommand(1L, "z_difficulty"))
                .thenReturn("\"z_difficulty\" = \"normal\"");

        service.changeDifficulty(1L, "普通");

        verify(rcon).executeCommand(1L, "sm_cvar z_difficulty Normal");
        verify(rcon).executeCommand(1L, "z_difficulty");
    }

    @Test
    void changeDifficulty_fallsBackToNativeSetterWithoutSourceMod() {
        when(rcon.executeCommand(1L, "sm_cvar z_difficulty Hard"))
                .thenReturn("Unknown command \"sm_cvar\"");
        when(rcon.executeCommand(1L, "z_difficulty Hard")).thenReturn("");
        when(rcon.executeCommand(1L, "z_difficulty"))
                .thenReturn("\"z_difficulty\" = \"hard\"");

        service.changeDifficulty(1L, "高级");

        verify(rcon).executeCommand(1L, "z_difficulty Hard");
        verify(rcon).executeCommand(1L, "z_difficulty");
    }

    @Test
    void changeDifficulty_rejectsUnchangedCvar() {
        when(rcon.executeCommand(1L, "sm_cvar z_difficulty Hard"))
                .thenReturn("[SM] Changed cvar \"z_difficulty\" to \"hard\".");
        when(rcon.executeCommand(1L, "z_difficulty"))
                .thenReturn("\"z_difficulty\" = \"normal\"");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.changeDifficulty(1L, "高级"));

        assertTrue(error.getMessage().contains("期望 高级"));
        assertTrue(error.getMessage().contains("实际 普通"));
        verify(rcon, never()).executeCommand(1L, "z_difficulty Hard");
    }
}
