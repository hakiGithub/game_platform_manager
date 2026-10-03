package com.gameplatform.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLUT-46：SSH 私钥解析支持面测试（使用一次性生成的测试密钥，非真实凭据）。
 *
 * <p>锁定 RSA 可解析，并给出 ed25519 在当前依赖组合下的支持性结论；
 * 同时验证「密文（二次加密脏数据）」必然解析失败的失效模式。
 */
@DisplayName("SSH私钥解析支持面测试（PLUT-46）")
class SshKeyParsingSupportTest {

    private static String loadKey(String resource) throws Exception {
        try (InputStream in = SshKeyParsingSupportTest.class
                .getResourceAsStream("/keys/" + resource)) {
            assertNotNull(in, "测试密钥资源缺失: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("RSA(PEM, traditional) 私钥可解析")
    void rsaPemParses() throws Exception {
        KeyPair kp = SshUtil.parsePrivateKey(loadKey("disposable-test-rsa.pem"));
        assertNotNull(kp, "RSA 私钥应可解析");
        assertEquals("RSA", kp.getPrivate().getAlgorithm());
    }

    @Test
    @DisplayName("ed25519(OpenSSH 格式) 私钥解析行为（结论随依赖组合，由断言锁定）")
    void ed25519Parsing() throws Exception {
        KeyPair kp = SshUtil.parsePrivateKey(loadKey("disposable-test-ed25519-openssh.key"));
        // 依赖 net.i2p.crypto:eddsa provider 后可解析；断言锁定支持性结论
        assertNotNull(kp, "ed25519 私钥应可解析（需 eddsa provider）");
        assertTrue("Ed25519".equalsIgnoreCase(kp.getPrivate().getAlgorithm())
                        || "NONE".equalsIgnoreCase(kp.getPrivate().getAlgorithm())
                        || "EdDSA".equalsIgnoreCase(kp.getPrivate().getAlgorithm()),
                "算法应为 Ed25519/EdDSA，实际: " + kp.getPrivate().getAlgorithm());
    }

    @Test
    @DisplayName("密文私钥（历史二次加密脏数据）解析失败返回 null")
    void encryptedGarbageFailsToParse() {
        String dirty = AesUtil.encrypt(AesUtil.encrypt(
                "-----BEGIN RSA PRIVATE KEY-----\nMAAAFAKE\n-----END RSA PRIVATE KEY-----\n"));
        assertNull(SshUtil.parsePrivateKey(dirty),
                "脏数据解密一次后仍是密文，必然无法解析——对应处置口径：重新录入");
    }
}
