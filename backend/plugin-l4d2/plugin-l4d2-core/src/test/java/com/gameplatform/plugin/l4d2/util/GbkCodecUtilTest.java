package com.gameplatform.plugin.l4d2.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GbkCodecUtilTest {

    @Test
    void gbkToUtf8_shouldDecodeGbkBytes() {
        byte[] gbkBytes = "中文".getBytes(java.nio.charset.Charset.forName("GBK"));
        assertEquals("中文", GbkCodecUtil.gbkToUtf8(gbkBytes));
    }

    @Test
    void utf8ToGbk_shouldEncodeToGbkBytes() {
        byte[] expected = "中文".getBytes(java.nio.charset.Charset.forName("GBK"));
        assertArrayEquals(expected, GbkCodecUtil.utf8ToGbk("中文"));
    }

    @Test
    void decodeAuto_shouldStripUtf8Bom() {
        byte[] withBom = new byte[] {(byte)0xEF, (byte)0xBB, (byte)0xBF, 'h', 'i'};
        assertEquals("hi", GbkCodecUtil.decodeAuto(withBom));
    }

    @Test
    void decodeAuto_shouldFallbackToGbkWhenNoBom() {
        byte[] gbkBytes = "测试".getBytes(java.nio.charset.Charset.forName("GBK"));
        assertEquals("测试", GbkCodecUtil.decodeAuto(gbkBytes));
    }
}
