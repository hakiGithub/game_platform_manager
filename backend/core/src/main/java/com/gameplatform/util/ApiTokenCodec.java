package com.gameplatform.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API 令牌编解码工具（ADR-0029）。
 *
 * <p>三个无状态静态纯函数：生成明文、SHA-256 摘要（小写 hex）、明文前缀。
 * 复用 JDK {@link SecureRandom} / {@link MessageDigest}，不引入任何第三方库。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class ApiTokenCodec {

    /** 明文前缀（与 ApiTokenService.PLAINTEXT_PREFIX 一致）。 */
    private static final String PREFIX = "gpm_";

    /** 随机字节数：32 → Base64url 无填充 43 字符，总长 47。 */
    private static final int RANDOM_BYTES = 32;

    /** 展示前缀长度：明文前 12 字符。 */
    private static final int PREFIX_LENGTH = 12;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ApiTokenCodec() {
    }

    /**
     * 生成一枚明文令牌：{@code gpm_ + 43 位 Base64url(无填充)}。
     */
    public static String generatePlaintext() {
        byte[] bytes = new byte[RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return PREFIX + body;
    }

    /**
     * 明文的 SHA-256 摘要（64 位小写 hex）。
     */
    public static String hash(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 保证存在的算法，正常环境不会走到这里
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 明文展示前缀：前 12 字符（非机密，列表用）。
     */
    public static String prefixOf(String plaintext) {
        if (plaintext == null || plaintext.length() < PREFIX_LENGTH) {
            return plaintext;
        }
        return plaintext.substring(0, PREFIX_LENGTH);
    }

}
