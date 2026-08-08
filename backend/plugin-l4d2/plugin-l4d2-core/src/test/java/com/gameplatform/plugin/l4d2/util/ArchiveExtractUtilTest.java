package com.gameplatform.plugin.l4d2.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveExtractUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void isVpkFile_shouldDetectVpkMagic() {
        byte[] vpkHeader = new byte[] {0x34, 0x12, (byte)0xAA, 0x55};
        assertTrue(ArchiveExtractUtil.isVpkFile(vpkHeader));
    }

    @Test
    void isVpkFile_shouldRejectNonVpk() {
        byte[] zipHeader = new byte[] {0x50, 0x4B, 0x03, 0x04};
        assertFalse(ArchiveExtractUtil.isVpkFile(zipHeader));
    }

    @Test
    void extractZip_shouldExtractGbkFilenames() throws Exception {
        File zipFile = tempDir.resolve("test.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile), Charset.forName("GBK"))) {
            zos.putNextEntry(new ZipEntry("插件/文件.txt"));
            zos.write("hello".getBytes());
            zos.closeEntry();
        }
        File destDir = tempDir.resolve("out").toFile();
        List<File> roots = ArchiveExtractUtil.extractZip(zipFile, destDir);
        assertFalse(roots.isEmpty());
        File extracted = new File(destDir, "插件/文件.txt");
        assertTrue(extracted.exists());
    }
}
