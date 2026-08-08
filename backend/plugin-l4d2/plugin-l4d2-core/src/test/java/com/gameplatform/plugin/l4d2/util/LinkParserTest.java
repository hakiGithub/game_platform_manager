package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LinkParser 单元测试（对齐 plan §4.2.6）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>parseWorkshopId：纯数字 ID、Steam URL、query 参数、正则匹配、非法输入</li>
 *   <li>isValidWorkshopId：边界值 99999 / 100000 / 字母 / 空</li>
 *   <li>isWorkshopLink：纯数字、URL、非法链接</li>
 *   <li>parse：workshop / unknown 两种结果</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class LinkParserTest {

    // ===== parseWorkshopId =====

    @Test
    void parse_workshop_id_pure_number() {
        assertEquals("123456", LinkParser.parseWorkshopId("123456"));
    }

    @Test
    void parse_workshop_id_below_threshold() {
        // 99999 < 100000，应抛异常
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> LinkParser.parseWorkshopId("99999"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    @Test
    void parse_workshop_id_from_sharedfiles() {
        assertEquals("123456", LinkParser.parseWorkshopId(
                "https://steamcommunity.com/sharedfiles/filedetails/?id=123456"));
    }

    @Test
    void parse_workshop_id_from_workshop() {
        assertEquals("123456", LinkParser.parseWorkshopId(
                "https://steamcommunity.com/workshop/browse?id=123456"));
    }

    @Test
    void parse_workshop_id_from_query() {
        assertEquals("123456", LinkParser.parseWorkshopId(
                "https://steamcommunity.com/workshop/?id=123456"));
    }

    @Test
    void parse_workshop_id_from_path() {
        // 路径中的纯数字：URL 包含 steamcommunity.com/workshop，正则匹配 123456
        assertEquals("123456", LinkParser.parseWorkshopId(
                "https://steamcommunity.com/workshop/123456"));
    }

    @Test
    void parse_workshop_id_invalid_url() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> LinkParser.parseWorkshopId("https://example.com/foo"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
        assertTrue(ex.getMessage().contains("未找到有效的工坊 ID"));
    }

    @Test
    void parse_workshop_id_empty() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> LinkParser.parseWorkshopId(""));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    @Test
    void parse_workshop_id_null() {
        assertThrows(L4D2PluginException.class, () -> LinkParser.parseWorkshopId(null));
    }

    @Test
    void parse_workshop_id_trimmed() {
        // 前后空格应被 trim
        assertEquals("123456", LinkParser.parseWorkshopId("  123456  "));
    }

    @Test
    void parse_workshop_id_from_steamworkshop_download() {
        // steamworkshop.download 主机
        assertEquals("123456", LinkParser.parseWorkshopId(
                "https://steamworkshop.download/download/view/?id=123456"));
    }

    @Test
    void parse_workshop_id_from_steampowered() {
        // steampowered.com 主机
        assertEquals("100000", LinkParser.parseWorkshopId(
                "https://store.steampowered.com/app/?id=100000"));
    }

    // ===== isValidWorkshopId =====

    @Test
    void is_valid_workshop_id_valid() {
        assertTrue(LinkParser.isValidWorkshopId("100000"));
        assertTrue(LinkParser.isValidWorkshopId("999999"));
        assertTrue(LinkParser.isValidWorkshopId("123456789012345"));
    }

    @Test
    void is_valid_workshop_id_below_threshold() {
        assertFalse(LinkParser.isValidWorkshopId("99999"));
        assertFalse(LinkParser.isValidWorkshopId("0"));
    }

    @Test
    void is_valid_workshop_id_non_numeric() {
        assertFalse(LinkParser.isValidWorkshopId("abc"));
        assertFalse(LinkParser.isValidWorkshopId("123abc"));
        assertFalse(LinkParser.isValidWorkshopId("12.34"));
    }

    @Test
    void is_valid_workshop_id_empty() {
        assertFalse(LinkParser.isValidWorkshopId(""));
        assertFalse(LinkParser.isValidWorkshopId(null));
        assertFalse(LinkParser.isValidWorkshopId("   "));
    }

    // ===== isWorkshopLink =====

    @Test
    void is_workshop_link_pure_id() {
        assertTrue(LinkParser.isWorkshopLink("123456"));
    }

    @Test
    void is_workshop_link_url() {
        assertTrue(LinkParser.isWorkshopLink("https://steamcommunity.com/sharedfiles/?id=123456"));
    }

    @Test
    void is_workshop_link_invalid() {
        assertFalse(LinkParser.isWorkshopLink("https://example.com"));
    }

    @Test
    void is_workshop_link_empty() {
        assertFalse(LinkParser.isWorkshopLink(""));
        assertFalse(LinkParser.isWorkshopLink(null));
    }

    @Test
    void is_workshop_link_below_threshold() {
        assertFalse(LinkParser.isWorkshopLink("99999"));
    }

    // ===== parse =====

    @Test
    void parse_workshop_pure_id() {
        LinkParser.LinkParseResult result = LinkParser.parse("123456");
        assertEquals(LinkParser.SOURCE_WORKSHOP, result.sourceType());
        assertEquals("123456", result.sourceId());
        assertEquals("123456", result.originalLink());
    }

    @Test
    void parse_workshop_url() {
        LinkParser.LinkParseResult result = LinkParser.parse(
                "https://steamcommunity.com/sharedfiles/filedetails/?id=123456");
        assertEquals(LinkParser.SOURCE_WORKSHOP, result.sourceType());
        assertEquals("123456", result.sourceId());
    }

    @Test
    void parse_unknown_link() {
        LinkParser.LinkParseResult result = LinkParser.parse("https://example.com/foo");
        assertEquals(LinkParser.SOURCE_UNKNOWN, result.sourceType());
        assertNull(result.sourceId());
    }

    @Test
    void parse_empty() {
        LinkParser.LinkParseResult result = LinkParser.parse("");
        assertEquals(LinkParser.SOURCE_UNKNOWN, result.sourceType());
        assertNull(result.sourceId());
    }

    @Test
    void parse_null() {
        LinkParser.LinkParseResult result = LinkParser.parse(null);
        assertEquals(LinkParser.SOURCE_UNKNOWN, result.sourceType());
        assertNull(result.sourceId());
    }
}
