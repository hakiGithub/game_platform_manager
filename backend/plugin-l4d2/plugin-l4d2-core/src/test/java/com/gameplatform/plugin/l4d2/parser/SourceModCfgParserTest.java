package com.gameplatform.plugin.l4d2.parser;

import com.gameplatform.plugin.l4d2.vo.config.ConfigItem;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceModCfgParserTest {

    private final SourceModCfgParser parser = new SourceModCfgParser();

    @Test
    void parse_shouldExtractKeyValuePairs() {
        String content = "\"sm_cvar_dp\" \"1.0\"\n\"sm_enable\" \"1\"\n";
        List<ConfigItem> items = parser.parse(content);
        assertEquals(2, items.size());
        assertEquals("sm_cvar_dp", items.get(0).getKey());
        assertEquals("1.0", items.get(0).getValue());
    }

    @Test
    void parse_shouldExtractMetadataFromComments() {
        String content = "\"sm_cvar_dp\" \"1.0\" // Default: 0.5 Min: 0 Max: 10 伤害倍率\n";
        List<ConfigItem> items = parser.parse(content);
        assertEquals(1, items.size());
        ConfigItem item = items.get(0);
        assertEquals("0.5", item.getDefaultValue());
        assertEquals(0.0, item.getMin());
        assertEquals(10.0, item.getMax());
        assertNotNull(item.getDescription());
    }

    @Test
    void parse_shouldSkipCommentsAndEmptyLines() {
        String content = "// header comment\n\"key\" \"value\"\n// trailing\n";
        List<ConfigItem> items = parser.parse(content);
        assertEquals(1, items.size());
        assertEquals("key", items.get(0).getKey());
    }

    @Test
    void serialize_shouldPreserveComments() {
        String original = "// header\n\"key\" \"1.0\" // Default: 0.5\n";
        List<ConfigItem> items = parser.parse(original);
        items.get(0).setValue("2.0");
        String result = parser.serialize(items, original);
        assertTrue(result.contains("\"key\" \"2.0\""));
        assertTrue(result.contains("// header"));
        assertTrue(result.contains("// Default: 0.5"));
    }

    @Test
    void parse_shouldSkipConsoleCommands() {
        String content = "// Config\n"
                + "\"sm_dp\" \"2.5\"\n"
                + "sm plugins load my_plugin\n"
                + "exec server.cfg\n"
                + "meta list\n"
                + "rcon password\n"
                + "\"mp_gamemode\" \"versus\"\n";
        List<ConfigItem> items = parser.parse(content);

        assertEquals(2, items.size(), "应跳过 sm/exec/meta/rcon 命令");
        assertEquals("sm_dp", items.get(0).getKey());
        assertEquals("mp_gamemode", items.get(1).getKey());
    }

    @Test
    void parse_shouldNotTreatSvarAsCommand() {
        // sm_ 开头的 CVAR（如 sm_dp）不应被误识别为 sm 命令
        String content = "\"sm_dp\" \"2.5\"\n";
        List<ConfigItem> items = parser.parse(content);
        assertEquals(1, items.size());
        assertEquals("sm_dp", items.get(0).getKey());
    }
}
