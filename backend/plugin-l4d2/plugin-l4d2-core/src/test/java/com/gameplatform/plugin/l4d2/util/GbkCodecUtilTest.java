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

    @Test
    void decodeAutoWithCharset_shouldDetectUtf8WithoutBom() {
        byte[] utf8Bytes = "中文描述".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        GbkCodecUtil.DecodedText decoded = GbkCodecUtil.decodeAutoWithCharset(utf8Bytes);
        assertEquals("中文描述", decoded.text());
        assertEquals(java.nio.charset.StandardCharsets.UTF_8, decoded.charset());
    }

    @Test
    void decodeAutoWithCharset_shouldReportGbkFallback() {
        byte[] gbkBytes = "中文描述".getBytes(java.nio.charset.Charset.forName("GBK"));
        GbkCodecUtil.DecodedText decoded = GbkCodecUtil.decodeAutoWithCharset(gbkBytes);
        assertEquals("中文描述", decoded.text());
        assertEquals(java.nio.charset.Charset.forName("GBK"), decoded.charset());
    }
}
