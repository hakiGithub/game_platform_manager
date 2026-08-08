package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FilenameSanitizeUtil 单元测试（对齐 plan §4.1.8）。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
class FilenameSanitizeUtilTest {

    // ===== sanitize =====

    @Test
    void sanitize_normal() {
        assertEquals("test.vpk", FilenameSanitizeUtil.sanitize("test.vpk"));
    }

    @Test
    void sanitize_withPathTraversal() {
        // 路径遍历：取 basename 后剩 passwd
        assertEquals("passwd", FilenameSanitizeUtil.sanitize("../../../etc/passwd"));
    }

    @Test
    void sanitize_withInvalidChars() {
        // 流程：去 \0 → \ 替换为 / → 取 basename → 替换非法字符为 _
        // 输入：a<b>c:d"e/f\g|h?i*j
        // 1) \ 替换为 /：a<b>c:d"e/f/g|h?i*j
        // 2) basename 取最后 / 后：g|h?i*j
        // 3) 替换非法字符 | ? * 为 _：g_h_i_j
        assertEquals("g_h_i_j", FilenameSanitizeUtil.sanitize("a<b>c:d\"e/f\\g|h?i*j"));
    }

    @Test
    void sanitize_tooLong() {
        // 200 字符的 base + 4 字符扩展名 = 204，应截断到 180
        String longBase = "a".repeat(200);
        String longName = longBase + ".vpk";
        String result = FilenameSanitizeUtil.sanitize(longName);
        assertTrue(result.length() <= 180, "结果长度应 <= 180, 实际: " + result.length());
        assertTrue(result.endsWith(".vpk"), "应保留扩展名 .vpk");
        // 基础名应被截断到 160
        assertEquals(160, result.length() - ".vpk".length());
    }

    @Test
    void sanitize_empty() {
        assertNull(FilenameSanitizeUtil.sanitize(""));
        assertNull(FilenameSanitizeUtil.sanitize(null));
    }

    @Test
    void sanitize_dotOrSlash() {
        assertNull(FilenameSanitizeUtil.sanitize("."));
        assertNull(FilenameSanitizeUtil.sanitize("/"));
    }

    @Test
    void sanitize_keepsChinese() {
        assertEquals("地图-01.vpk", FilenameSanitizeUtil.sanitize("地图-01.vpk"));
    }

    @Test
    void sanitize_removesNullByte() {
        assertEquals("test.vpk", FilenameSanitizeUtil.sanitize("te\0st.vpk"));
    }

    // ===== sanitizePath =====

    @Test
    void sanitizePath_normal() {
        assertEquals("addons/test.vpk", FilenameSanitizeUtil.sanitizePath("addons/test.vpk"));
    }

    @Test
    void sanitizePath_backslashConverted() {
        assertEquals("addons/test.vpk", FilenameSanitizeUtil.sanitizePath("addons\\test.vpk"));
    }

    @Test
    void sanitizePath_pathTraversalThrows() {
        L4D2PluginException ex = assertThrows(L4D2PluginException.class,
                () -> FilenameSanitizeUtil.sanitizePath("../etc/passwd"));
        assertEquals(L4D2PluginException.BUSINESS, ex.getCode());
    }

    @Test
    void sanitizePath_emptyReturnsNull() {
        assertNull(FilenameSanitizeUtil.sanitizePath(null));
        assertNull(FilenameSanitizeUtil.sanitizePath(""));
        assertNull(FilenameSanitizeUtil.sanitizePath("   "));
    }

    @Test
    void sanitizePath_replacesInvalidChars() {
        assertEquals("a_b/c_d", FilenameSanitizeUtil.sanitizePath("a<b/c:d"));
    }

    // ===== isSupportedExtension =====

    @Test
    void isSupportedExtension_vpk() {
        assertTrue(FilenameSanitizeUtil.isSupportedExtension("test.vpk"));
    }

    @Test
    void isSupportedExtension_zip() {
        assertTrue(FilenameSanitizeUtil.isSupportedExtension("test.zip"));
    }

    @Test
    void isSupportedExtension_rar() {
        assertTrue(FilenameSanitizeUtil.isSupportedExtension("test.rar"));
    }

    @Test
    void isSupportedExtension_7z() {
        assertTrue(FilenameSanitizeUtil.isSupportedExtension("test.7z"));
    }

    @Test
    void isSupportedExtension_unsupported() {
        assertFalse(FilenameSanitizeUtil.isSupportedExtension("test.txt"));
        assertFalse(FilenameSanitizeUtil.isSupportedExtension("test.exe"));
        assertFalse(FilenameSanitizeUtil.isSupportedExtension("test"));
        assertFalse(FilenameSanitizeUtil.isSupportedExtension(null));
    }

    @Test
    void isSupportedExtension_caseInsensitive() {
        assertTrue(FilenameSanitizeUtil.isSupportedExtension("test.VPK"));
        assertTrue(FilenameSanitizeUtil.isSupportedExtension("test.Zip"));
    }
}
