package com.gameplatform.plugin.l4d2.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * GBK ↔ UTF-8 编码工具，统一处理 L4D2 文件的中文乱码问题。
 *
 * <p>L4D2 大部分配置/日志文件使用 GBK 编码，少数 UTF-8 文件带 BOM。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class GbkCodecUtil {

    private static final Charset GBK = Charset.forName("GBK");
    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    private GbkCodecUtil() {}

    /** GBK 字节 → UTF-8 字符串 */
    public static String gbkToUtf8(byte[] bytes) {
        return new String(bytes, GBK);
    }

    /** UTF-8 字符串 → GBK 字节 */
    public static byte[] utf8ToGbk(String text) {
        return text.getBytes(GBK);
    }

    /**
     * 自动检测 BOM 与编码：
     * <ul>
     *   <li>UTF-8 BOM (EF BB BF) → 去除 BOM 后用 UTF-8 解码</li>
     *   <li>其他 → 用 GBK 解码</li>
     * </ul>
     */
    public static String decodeAuto(byte[] bytes) {
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, UTF_8);
        }
        return new String(bytes, GBK);
    }

    /** 获取 GBK Charset（供外部直接使用） */
    public static Charset gbk() {
        return GBK;
    }
}
