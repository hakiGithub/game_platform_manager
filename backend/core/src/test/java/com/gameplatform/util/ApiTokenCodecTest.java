package com.gameplatform.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-1: API 令牌编解码工具测试（ADR-0029）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class ApiTokenCodecTest {

    @Test
    @DisplayName("明文格式：gpm_ + 43 位 Base64url 无填充字符")
    void plaintextFormat() {
        for (int i = 0; i < 50; i++) {
            String plaintext = ApiTokenCodec.generatePlaintext();
            assertTrue(plaintext.matches("^gpm_[A-Za-z0-9_-]{43}$"),
                    "明文应匹配 gpm_ + 43 位 Base64url: " + plaintext);
            assertFalse(plaintext.contains("="), "不应含 = 填充");
            assertEquals(47, plaintext.length(), "总长应为 47");
        }
    }

    @Test
    @DisplayName("哈希：64 位小写 hex，对固定输入稳定，不同输入不同")
    void hashFormatAndStability() {
        String h1 = ApiTokenCodec.hash("gpm_fixed_input");
        String h2 = ApiTokenCodec.hash("gpm_fixed_input");
        String h3 = ApiTokenCodec.hash("gpm_other_input");

        assertTrue(h1.matches("^[0-9a-f]{64}$"), "应为 64 位小写 hex: " + h1);
        assertEquals(h1, h2, "固定输入哈希应稳定");
        assertNotEquals(h1, h3, "不同输入哈希应不同");

        // 与 JDK MessageDigest 直算结果一致（SHA-256("abc") 的已知值）
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ApiTokenCodec.hash("abc"));
    }

    @Test
    @DisplayName("前缀：取明文前 12 字符（含 gpm_）")
    void prefixOf() {
        String plaintext = ApiTokenCodec.generatePlaintext();
        assertEquals(plaintext.substring(0, 12), ApiTokenCodec.prefixOf(plaintext));
        assertEquals("gpm_", ApiTokenCodec.prefixOf(plaintext).substring(0, 4));
    }

    @Test
    @DisplayName("随机性：连抽 1000 次无重复")
    void noCollisionsIn1000() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(ApiTokenCodec.generatePlaintext()), "第 " + i + " 次出现重复");
        }
        assertEquals(1000, seen.size());
    }

}
