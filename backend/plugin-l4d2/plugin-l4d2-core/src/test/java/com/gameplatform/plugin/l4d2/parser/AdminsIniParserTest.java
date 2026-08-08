package com.gameplatform.plugin.l4d2.parser;

import com.gameplatform.plugin.l4d2.vo.admin.AdminEntry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AdminsIniParserTest {

    private final AdminsIniParser parser = new AdminsIniParser();

    @Test
    void parse_shouldExtractEntries() {
        String content = "\"STEAM_0:1:123\" \"99:z\" // 主管理员\n\"STEAM_0:1:456\" \"abc\" // 副管理员\n";
        List<AdminEntry> entries = parser.parse(content);
        assertEquals(2, entries.size());
        assertEquals("STEAM_0:1:123", entries.get(0).getIdentity());
        assertEquals("99:z", entries.get(0).getFlags());
        assertEquals("主管理员", entries.get(0).getRemark());
    }

    @Test
    void parse_shouldSkipCommentsAndEmpty() {
        String content = "// header\n\"STEAM_0:1:1\" \"z\"\n\n";
        List<AdminEntry> entries = parser.parse(content);
        assertEquals(1, entries.size());
    }

    @Test
    void addEntry_shouldAppendNewEntry() {
        String content = "\"STEAM_0:1:1\" \"z\" // existing\n";
        AdminEntry entry = new AdminEntry();
        entry.setIdentity("STEAM_0:1:2");
        entry.setFlags("abc");
        entry.setRemark("new");
        String result = parser.addEntry(content, entry);
        assertTrue(result.contains("STEAM_0:1:2"));
        assertTrue(result.contains("existing"));
    }

    @Test
    void removeEntry_shouldRemoveByIdentity() {
        String content = "\"STEAM_0:1:1\" \"z\"\n\"STEAM_0:1:2\" \"abc\"\n";
        String result = parser.removeEntry(content, "STEAM_0:1:1");
        assertFalse(result.contains("STEAM_0:1:1"));
        assertTrue(result.contains("STEAM_0:1:2"));
    }
}
