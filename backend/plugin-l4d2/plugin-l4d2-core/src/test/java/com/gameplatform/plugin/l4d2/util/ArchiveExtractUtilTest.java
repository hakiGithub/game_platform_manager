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

    /** 写一个最小 zip */
    private File zipOf(String... entries) throws Exception {
        File zipFile = tempDir.resolve("in_" + entries.length + "_" + System.nanoTime() + ".zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (String entry : entries) {
                zos.putNextEntry(new ZipEntry(entry));
                zos.write(("content-of-" + entry).getBytes());
                zos.closeEntry();
            }
        }
        return zipFile;
    }

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

    // ===== ADR-0018：slip 防护 / 解压上限 / vpk-only 提取 =====

    @Test
    void extractZip_shouldRejectPathTraversalEntry() throws Exception {
        File zipFile = zipOf("../evil.txt");
        File destDir = tempDir.resolve("slip-out").toFile();

        assertThrows(Exception.class, () -> ArchiveExtractUtil.extractZip(zipFile, destDir));
        // 越界文件不应存在
        assertFalse(tempDir.resolve("evil.txt").toFile().exists());
    }

    @Test
    void extractZip_shouldSkipMacosJunk() throws Exception {
        File zipFile = zipOf("__MACOSX/map._vpk", ".DS_Store", "a.vpk");
        File destDir = tempDir.resolve("junk-out").toFile();

        List<File> roots = ArchiveExtractUtil.extractZip(zipFile, destDir);

        assertTrue(roots.stream().noneMatch(f -> f.getName().contains("__MACOSX") || f.getName().equals(".DS_Store")));
    }

    @Test
    void extractZip_shouldAbortWhenEntryLimitExceeded() throws Exception {
        File zipFile = zipOf("f1.txt", "f2.txt", "f3.txt", "f4.txt", "f5.txt");
        File destDir = tempDir.resolve("limit-out").toFile();

        assertThrows(Exception.class,
                () -> ArchiveExtractUtil.extractZip(zipFile, destDir, 4L * 1024 * 1024 * 1024, 3));
    }

    @Test
    void extractVpks_shouldOnlyExtractVpkAndFlatten() throws Exception {
        File zipFile = zipOf("maps/campaign.vpk", "maps/readme.txt", "maps/sub/extra.vpk", "__MACOSX/x._vpk");
        File destDir = tempDir.resolve("vpks-out").toFile();

        List<File> vpks = ArchiveExtractUtil.extractVpks(zipFile, "pack.zip", destDir, 1024 * 1024, 100);

        assertEquals(2, vpks.size());
        assertTrue(vpks.stream().allMatch(f -> f.getParentFile().equals(destDir)));
        assertTrue(destDir.toPath().resolve("maps").toFile().exists() == false, "目录结构应被剥离");
        assertFalse(destDir.toPath().resolve("readme.txt").toFile().exists(), "非 vpk 条目应被丢弃");
    }

    @Test
    void extractVpks_shouldAbortOnBudgetExceeded() throws Exception {
        File zipFile = zipOf("a.vpk", "b.vpk");
        File destDir = tempDir.resolve("budget-out").toFile();

        assertThrows(Exception.class,
                () -> ArchiveExtractUtil.extractVpks(zipFile, "pack.zip", destDir, 10L, 100));
    }

    @Test
    void extractRar_shouldRejectGarbageWithFriendlyError() throws Exception {
        File garbage = tempDir.resolve("garbage.rar").toFile();
        java.nio.file.Files.write(garbage.toPath(), "not-a-rar".getBytes());

        Exception e = assertThrows(Exception.class,
                () -> ArchiveExtractUtil.extractRar(garbage, tempDir.resolve("rar-out").toFile()));
        assertTrue(e.getMessage().contains("RAR"), "应给出 RAR 相关明确报错: " + e.getMessage());
    }

    @Test
    void extractVpks_shouldRejectUnsupportedFormat() throws Exception {
        File fake = tempDir.resolve("x.tar").toFile();
        java.nio.file.Files.write(fake.toPath(), new byte[0]);

        assertThrows(Exception.class,
                () -> ArchiveExtractUtil.extractVpks(fake, "x.tar", tempDir.resolve("tar-out").toFile(), 1024, 10));
    }
}
