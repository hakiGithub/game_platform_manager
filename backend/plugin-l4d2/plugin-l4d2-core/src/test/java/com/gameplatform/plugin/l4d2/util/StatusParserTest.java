package com.gameplatform.plugin.l4d2.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StatusParser 单元测试（对齐 plan §5.1.7）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class StatusParserTest {

    private StatusParser parser;

    @BeforeEach
    void setUp() {
        parser = new StatusParser();
    }

    // ============================================================
    // parse_status_extracts_hostname_map_players
    // ============================================================
    @Test
    void parse_status_extracts_hostname_map_players() {
        String statusText = String.join("\n",
                "hostname: L4D2 Test Server",
                "version : 2.2.3.5",
                "os      :  Linux",
                "players : 4 humans, 0 bots (30 max)",
                "#end");
        StatusParser.Status status = parser.parse(statusText);

        assertEquals("L4D2 Test Server", status.getHostname());
        // map 行未提供 → 保持 null
        assertNull(status.getMap());
        assertEquals(4, status.getPlayerCount());
        assertEquals(30, status.getMaxPlayers());
    }

    // ============================================================
    // parse_status_extracts_users_correctly
    // ============================================================
    @Test
    void parse_status_extracts_users_correctly() {
        String statusText = String.join("\n",
                "hostname: L4D2 Test Server",
                "map     : c1m1_hotel",
                "players : 2 humans, 0 bots (30 max)",
                "# userid name                uniqueid connected ping loss state  rate",
                "#     2 2 \"Player One\"       STEAM_1:0:111 1:23:45  30  0 active 5 1.2.3.4:27005",
                "#     3 3 \"Player Two\"       STEAM_1:0:222 0:05:12  50  1 active 5 5.6.7.8:27006",
                "#end");
        StatusParser.Status status = parser.parse(statusText);

        assertEquals("c1m1_hotel", status.getMap());
        assertEquals(2, status.getPlayerCount());
        assertEquals(30, status.getMaxPlayers());
        List<StatusParser.User> users = status.getUsers();
        assertEquals(2, users.size());

        StatusParser.User u1 = users.get(0);
        assertEquals(2, u1.getId());
        assertEquals("Player One", u1.getName());
        assertEquals("STEAM_1:0:111", u1.getSteamId());
        assertEquals("1:23:45", u1.getDuration());
        assertEquals(30, u1.getDelay());
        assertEquals(0, u1.getLoss());
        assertEquals("active", u1.getStatus());
        assertEquals(5, u1.getLinkRate());
        assertEquals("1.2.3.4:27005", u1.getIp());

        StatusParser.User u2 = users.get(1);
        assertEquals("Player Two", u2.getName());
        assertEquals("STEAM_1:0:222", u2.getSteamId());
        assertEquals(50, u2.getDelay());
        assertEquals(1, u2.getLoss());
        assertEquals("5.6.7.8:27006", u2.getIp());
    }

    // ============================================================
    // parse_status_handles_empty_output
    // ============================================================
    @Test
    void parse_status_handles_empty_output() {
        StatusParser.Status status1 = parser.parse(null);
        assertNotNull(status1);
        assertNull(status1.getHostname());
        assertEquals(0, status1.getPlayerCount());
        assertTrue(status1.getUsers().isEmpty());

        StatusParser.Status status2 = parser.parse("");
        assertNotNull(status2);
        assertNull(status2.getHostname());
        assertTrue(status2.getUsers().isEmpty());
    }

    // ============================================================
    // parse_difficulty_maps_easy_to_chinese
    // ============================================================
    @Test
    void parse_difficulty_maps_easy_to_chinese() {
        String text = "\"z_difficulty\" = \"easy\"";
        assertEquals("简单", parser.parseDifficulty(text));
    }

    @Test
    void parse_difficulty_maps_normal_hard_impossible() {
        assertEquals("普通", parser.parseDifficulty("\"z_difficulty\" = \"normal\""));
        assertEquals("高级", parser.parseDifficulty("\"z_difficulty\" = \"hard\""));
        assertEquals("专家", parser.parseDifficulty("\"z_difficulty\" = \"impossible\""));
    }

    // ============================================================
    // parse_difficulty_returns_unknown_on_no_match
    // ============================================================
    @Test
    void parse_difficulty_returns_unknown_on_no_match() {
        assertEquals("未知", parser.parseDifficulty(null));
        assertEquals("未知", parser.parseDifficulty(""));
        assertEquals("未知", parser.parseDifficulty("unknown cvar"));
        // 未匹配的难度值原样返回（小写匹配走 default 分支）
        assertEquals("HARDCORE", parser.parseDifficulty("\"z_difficulty\" = \"HARDCORE\""));
    }

    // ============================================================
    // parse_gamemode_maps_coop
    // ============================================================
    @Test
    void parse_gamemode_maps_coop() {
        // [SM] 格式
        String smText = "[SM] Value of cvar \"mp_gamemode\": \"coop\"";
        assertEquals("合作", parser.parseGameMode(smText));
        // 标准 cvar 格式
        String cvarText = "\"mp_gamemode\" = \"coop\"";
        assertEquals("合作", parser.parseGameMode(cvarText));
    }

    // ============================================================
    // parse_gamemode_maps_mutation15
    // ============================================================
    @Test
    void parse_gamemode_maps_mutation15() {
        String text = "[SM] Value of cvar \"mp_gamemode\": \"mutation15\"";
        assertEquals("对抗生存", parser.parseGameMode(text));
    }

    @Test
    void parse_gamemode_maps_all_known_modes() {
        assertEquals("写实", parser.parseGameMode("\"mp_gamemode\" = \"realism\""));
        assertEquals("生存", parser.parseGameMode("\"mp_gamemode\" = \"survival\""));
        assertEquals("对抗", parser.parseGameMode("\"mp_gamemode\" = \"versus\""));
        assertEquals("拾荒", parser.parseGameMode("\"mp_gamemode\" = \"scavenge\""));
        assertEquals("坚守", parser.parseGameMode("\"mp_gamemode\" = \"holdout\""));
        assertEquals("地球上最后一人", parser.parseGameMode("\"mp_gamemode\" = \"mutation1\""));
        assertEquals("治疗侏儒", parser.parseGameMode("\"mp_gamemode\" = \"mutation20\""));
        assertEquals("特感速递", parser.parseGameMode("\"mp_gamemode\" = \"community1\""));
        assertEquals("Confogl", parser.parseGameMode("\"mp_gamemode\" = \"community6\""));
    }

    // ============================================================
    // parse_gamemode_returns_unknown_on_no_match
    // ============================================================
    @Test
    void parse_gamemode_returns_unknown_on_no_match() {
        assertEquals("未知", parser.parseGameMode(null));
        assertEquals("未知", parser.parseGameMode(""));
        assertEquals("未知", parser.parseGameMode("unknown cvar"));
    }

    // ============================================================
    // parse_gamemode_default_fallback_preserves_value
    // ============================================================
    @Test
    void parse_gamemode_default_fallback_preserves_value() {
        // 未在翻译表中的模式原样返回
        assertEquals("custom_mode", parser.parseGameMode("\"mp_gamemode\" = \"custom_mode\""));
    }

    // ============================================================
    // parseUser_returns_null_on_invalid_input
    // ============================================================
    @Test
    void parseUser_returns_null_on_invalid_input() {
        assertNull(parser.parseUser(null));
        assertNull(parser.parseUser(""));
        assertNull(parser.parseUser("not a user line"));
        assertNull(parser.parseUser("# 2 2 \"Only Three Fields\" STEAM_1:0:111"));
    }

    // ============================================================
    // translateGameMode_handles_null_and_unknown
    // ============================================================
    @Test
    void translateGameMode_handles_null_and_unknown() {
        assertEquals("未知", parser.translateGameMode(null));
        assertEquals("customX", parser.translateGameMode("customX"));
    }
}
