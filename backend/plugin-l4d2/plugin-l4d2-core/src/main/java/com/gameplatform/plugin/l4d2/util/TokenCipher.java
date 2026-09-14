package com.gameplatform.plugin.l4d2.util;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;

import java.nio.charset.StandardCharsets;

/**
 * 仓库访问令牌加解密与脱敏工具。
 *
 * <p>与主应用 {@code com.gameplatform.util.AesUtil} 保持同一平台密钥与算法
 * （AES/ECB/PKCS5Padding + Base64），插件不依赖 core 模块故独立实现。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class TokenCipher {

    /** 与主应用 AesUtil.DEFAULT_SECRET_KEY 一致（16 字节） */
    private static final String KEY = "GamePlatform2024";

    private static final AES AES = SecureUtil.aes(KEY.getBytes(StandardCharsets.UTF_8));

    private TokenCipher() {
    }

    /** 明文 → Base64 密文；空值原样返回空串 */
    public static String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return "";
        }
        return AES.encryptBase64(plain);
    }

    /** Base64 密文 → 明文；空值返回空串，解密失败视为明文原样返回（兼容脏数据） */
    public static String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return "";
        }
        try {
            return AES.decryptStr(stored);
        } catch (Exception e) {
            return stored;
        }
    }

    /** 脱敏回显：保留末 4 位，形如 {@code ****abcd}；空值返回 null */
    public static String mask(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        String trimmed = plain.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }
}
