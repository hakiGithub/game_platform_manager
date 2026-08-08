package com.gameplatform.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES加密工具类
 * 用于SSH私钥等敏感信息的加密存储
 * 使用Hutool的SecureUtil进行AES加密解密
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class AesUtil {

    /**
     * 默认密钥(生产环境应从配置中心或环境变量获取)
     * 16字节 = 128位
     */
    private static final String DEFAULT_SECRET_KEY = "GamePlatform2024";

    /**
     * 加密敏感数据
     *
     * @param plainText 明文
     * @return Base64编码的密文
     */
    public static String encrypt(String plainText) {
        return encrypt(plainText, DEFAULT_SECRET_KEY);
    }

    /**
     * 加密敏感数据
     *
     * @param plainText 明文
     * @param secretKey 密钥(16字节)
     * @return Base64编码的密文
     */
    public static String encrypt(String plainText, String secretKey) {
        if (StrUtil.isBlank(plainText)) {
            return plainText;
        }

        try {
            return SecureUtil.aes(secretKey.getBytes(StandardCharsets.UTF_8))
                    .encryptBase64(plainText);
        } catch (Exception e) {
            log.error("AES加密失败: {}", e.getMessage(), e);
            throw new RuntimeException("加密失败", e);
        }
    }

    /**
     * 解密敏感数据
     *
     * @param encryptedText Base64编码的密文
     * @return 明文
     */
    public static String decrypt(String encryptedText) {
        return decrypt(encryptedText, DEFAULT_SECRET_KEY);
    }

    /**
     * 解密敏感数据
     *
     * @param encryptedText Base64编码的密文
     * @param secretKey     密钥(16字节)
     * @return 明文
     */
    public static String decrypt(String encryptedText, String secretKey) {
        if (StrUtil.isBlank(encryptedText)) {
            return encryptedText;
        }

        // 如果不是加密数据，直接返回原文
        if (!isEncrypted(encryptedText)) {
            log.debug("数据未加密，直接返回原文");
            return encryptedText;
        }

        try {
            return SecureUtil.aes(secretKey.getBytes(StandardCharsets.UTF_8))
                    .decryptStr(encryptedText);
        } catch (Exception e) {
            log.error("AES解密失败: {}, 数据可能是明文，直接返回", e.getMessage());
            // 解密失败时，假设数据是明文，直接返回
            return encryptedText;
        }
    }

    /**
     * 生成随机密钥
     *
     * @return Base64编码的16字节密钥
     */
    public static String generateKey() {
        byte[] key = new byte[16]; // 128位
        java.security.SecureRandom random = new java.security.SecureRandom();
        random.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * 判断字符串是否已加密
     * 通过检查是否为有效的Base64编码且长度符合加密特征
     *
     * @param text 待判断的字符串
     * @return 是否已加密
     */
    public static boolean isEncrypted(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }

        try {
            // 尝试Base64解码
            byte[] decoded = Base64.getDecoder().decode(text);
            // 加密后的数据至少应该有一定长度
            // Hutool的AES加密结果长度通常是16的倍数
            return decoded.length >= 16 && decoded.length % 16 == 0;
        } catch (Exception e) {
            // 不是有效的Base64，说明是明文
            return false;
        }
    }

}
