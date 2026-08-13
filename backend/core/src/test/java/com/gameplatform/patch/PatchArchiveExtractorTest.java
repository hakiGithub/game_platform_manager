package com.gameplatform.patch;

import com.gameplatform.common.exception.BusinessException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 平台侧解压器单测（ADR-0006 决策 5 的「平台解压推散文件」分支）
 */
@DisplayName("补丁解压器测试")
class PatchArchiveExtractorTest {

    private final PatchArchiveExtractor extractor = new PatchArchiveExtractor();

    @TempDir
    Path tempDir;

    private Path makeTarGz(Path dest, String... entries) throws Exception {
        Path archive = dest.resolve("patch.tar.gz");
        try (OutputStream fos = new GzipCompressorOutputStream(
                new BufferedOutputStream(Files.newOutputStream(archive)));
             TarArchiveOutputStream tar = new TarArchiveOutputStream(fos)) {
            for (String entry : entries) {
                TarArchiveEntry e = new TarArchiveEntry(entry);
                byte[] content = ("content-of-" + entry).getBytes(StandardCharsets.UTF_8);
                e.setSize(content.length);
                tar.putArchiveEntry(e);
                tar.write(content);
                tar.closeArchiveEntry();
            }
        }
        return archive;
    }

    @Test
    @DisplayName("tar.gz 解压并返回顶层条目")
    void extractTarGz() throws Exception {
        Path archive = makeTarGz(tempDir, "addons/mod/a.vpk", "addons/cfg/b.cfg", "top.txt");

        Set<String> topLevel = extractor.extract(archive, PatchFormat.TAR_GZ,
                tempDir.resolve("out"));

        assertEquals(Set.of("addons", "top.txt"), topLevel);
        assertTrue(Files.exists(tempDir.resolve("out/addons/mod/a.vpk")));
        assertTrue(Files.exists(tempDir.resolve("out/top.txt")));
    }

    @Test
    @DisplayName("清单列出顶层条目")
    void listTopLevel() throws Exception {
        Path archive = makeTarGz(tempDir, "a/1.txt", "a/2.txt", "b.txt");

        Set<String> topLevel = extractor.listTopLevelEntries(archive, PatchFormat.TAR_GZ);

        assertEquals(Set.of("a", "b.txt"), topLevel);
    }

    @Test
    @DisplayName("条目越界（../）抛 BusinessException")
    void traversalRejected() throws Exception {
        Path archive = makeTarGz(tempDir, "../evil.txt");

        assertThrows(BusinessException.class,
                () -> extractor.extract(archive, PatchFormat.TAR_GZ, tempDir.resolve("out")));
    }
}
