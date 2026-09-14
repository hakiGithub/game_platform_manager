package com.gameplatform.plugin.l4d2.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenCipher} 单元测试：加解密往返与脱敏。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class TokenCipherTest {

    @Test
    void encrypt_decrypt_roundtrip() {
        String plain = "github_pat_11AAAAAAA0abcdefgh1234567890";
        String stored = TokenCipher.encrypt(plain);
        assertTrue(stored.startsWith("")); // 密文非空
        assertTrue(!stored.equals(plain), "密文不应等于明文");
        assertEquals(plain, TokenCipher.decrypt(stored));
    }

    @Test
    void encrypt_blank_returns_empty() {
        assertEquals("", TokenCipher.encrypt(null));
        assertEquals("", TokenCipher.encrypt(""));
        assertEquals("", TokenCipher.encrypt("  "));
    }

    @Test
    void decrypt_blank_returns_empty() {
        assertEquals("", TokenCipher.decrypt(null));
        assertEquals("", TokenCipher.decrypt(""));
    }

    @Test
    void decrypt_garbage_falls_back_to_input() {
        String garbage = "not-a-valid-ciphertext!!";
        assertEquals(garbage, TokenCipher.decrypt(garbage));
    }

    @Test
    void mask_keeps_last_four() {
        assertEquals("****7890", TokenCipher.mask("abcdefgh1234567890"));
        assertNull(TokenCipher.mask(null));
        assertNull(TokenCipher.mask("  "));
    }

    @Test
    void mask_short_token_fully_masked() {
        assertEquals("****", TokenCipher.mask("abc"));
        assertEquals("****", TokenCipher.mask("abcd"));
    }
}
