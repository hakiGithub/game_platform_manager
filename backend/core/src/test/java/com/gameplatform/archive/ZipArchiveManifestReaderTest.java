package com.gameplatform.archive;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("单插件 ZIP 清单读取")
class ZipArchiveManifestReaderTest {

    @TempDir
    Path tempDir;

    private final ZipArchiveManifestReader reader = new ZipArchiveManifestReader();

    @Test
    @DisplayName("识别单插件根目录并统计文件")
    void readsSinglePluginRoot() throws IOException {
        Path zip = zip(Map.of(
                "my-plugin/left4dead2/addons/sourcemod/plugins/a.smx", "smx",
                "my-plugin/left4dead2/cfg/sourcemod/a.cfg", "cfg"));

        ArchiveManifest manifest = reader.read(zip, 10, 1024, null, "left4dead2");

        assertEquals("my-plugin", manifest.pluginRoot());
        assertEquals("my-plugin", manifest.pluginName());
        assertEquals(2, manifest.fileCount());
        assertEquals(6, manifest.totalBytes());
        assertTrue(manifest.hasStructureRoot());
        assertEquals(Set.of("left4dead2/addons/sourcemod/plugins/a.smx",
                        "left4dead2/cfg/sourcemod/a.cfg"),
                Set.copyOf(manifest.relativeFiles()));
    }

    @Test
    @DisplayName("无外层插件目录时按归档根作为 left4dead2")
    void readsUnwrappedRoot() throws IOException {
        Path zip = zip(Map.of("left4dead2/addons/sourcemod/plugins/a.smx", "x"));

        ArchiveManifest manifest = reader.read(zip, 10, 1024, "builtin-id", "left4dead2");

        assertNull(manifest.pluginRoot());
        assertEquals("builtin-id", manifest.pluginName());
        assertTrue(manifest.hasStructureRoot());
    }

    @Test
    @DisplayName("拒绝绝对路径")
    void rejectsAbsolutePath() throws IOException {
        Path zip = zip(Map.of("/left4dead2/a.cfg", "x"));
        ArchiveManifestException ex = assertThrows(ArchiveManifestException.class,
                () -> reader.read(zip, 10, 1024, null, "left4dead2"));
        assertTrue(ex.getMessage().contains("绝对路径"));
    }

    @Test
    @DisplayName("拒绝路径穿越")
    void rejectsPathTraversal() throws IOException {
        Path zip = zip(Map.of("../left4dead2/a.cfg", "x"));
        ArchiveManifestException ex = assertThrows(ArchiveManifestException.class,
                () -> reader.read(zip, 10, 1024, null, "left4dead2"));
        assertTrue(ex.getMessage().contains("路径穿越"));
    }

    @Test
    @DisplayName("拒绝符号链接")
    void rejectsSymlink() throws IOException {
        Path zip = tempDir.resolve("symlink.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip.toFile())) {
            ZipArchiveEntry link = new ZipArchiveEntry("p/left4dead2/link");
            link.setUnixMode(0120777);
            out.putArchiveEntry(link);
            out.write("target".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }

        ArchiveManifestException ex = assertThrows(ArchiveManifestException.class,
                () -> reader.read(zip, 10, 1024, null, "left4dead2"));
        assertTrue(ex.getMessage().contains("符号链接"));
    }

    @Test
    @DisplayName("拒绝重复条目")
    void rejectsDuplicateEntry() throws IOException {
        Path zip = tempDir.resolve("dup.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip.toFile())) {
            for (int i = 0; i < 2; i++) {
                ZipArchiveEntry entry = new ZipArchiveEntry("p/left4dead2/a.cfg");
                out.putArchiveEntry(entry);
                out.write('x');
                out.closeArchiveEntry();
            }
        }

        ArchiveManifestException ex = assertThrows(ArchiveManifestException.class,
                () -> reader.read(zip, 10, 1024, null, "left4dead2"));
        assertTrue(ex.getMessage().contains("重复条目"));
    }

    @Test
    @DisplayName("拒绝条目数和解压大小超限")
    void rejectsLimits() throws IOException {
        Path zip = zip(Map.of(
                "p/left4dead2/a.cfg", "12345",
                "p/left4dead2/b.cfg", "12345"));

        ArchiveManifestException tooManyEntries = assertThrows(ArchiveManifestException.class,
                () -> reader.read(zip, 1, 1024, null, "left4dead2"));
        assertEquals("ARCHIVE_TOO_MANY_ENTRIES", tooManyEntries.getFailureCode());
        assertTrue(tooManyEntries.getMessage().contains("条目数超过上限"));
        assertTrue(assertThrows(ArchiveManifestException.class,
                () -> reader.read(zip, 10, 5, null, "left4dead2"))
                .getMessage().contains("总大小超过上限"));
    }

    @Test
    @DisplayName("空条目也计入解压后大小上限")
    void countsEmptyEntriesTowardExtractedSizeLimit() throws IOException {
        Path zip = zip(Map.of(
                "p/left4dead2/addons/sourcemod/plugins/a.smx", "x"));

        ArchiveManifestException ex = assertThrows(ArchiveManifestException.class,
                () -> reader.read(zip, 10, 0, null, "left4dead2"));

        assertEquals("ARCHIVE_TOO_LARGE", ex.getFailureCode());
        assertTrue(ex.getMessage().contains("总大小超过上限"));
    }

    @Test
    @DisplayName("目录条目也计入条目上限，不能靠大量空目录绕过")
    void countsDirectoriesTowardEntryLimit() throws IOException {
        Path zip = tempDir.resolve("too-many-directories.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip.toFile())) {
            for (String name : List.of(
                    "p/left4dead2/",
                    "p/left4dead2/a/",
                    "p/left4dead2/b/",
                    "p/left4dead2/c.cfg")) {
                ZipArchiveEntry entry = new ZipArchiveEntry(name);
                out.putArchiveEntry(entry);
                if (!entry.isDirectory()) {
                    out.write('x');
                }
                out.closeArchiveEntry();
            }
        }

        ArchiveManifestException ex = assertThrows(ArchiveManifestException.class,
                () -> reader.read(zip, 3, 1024, null, "left4dead2"));

        assertEquals("ARCHIVE_TOO_MANY_ENTRIES", ex.getFailureCode());
    }

    @Test
    @DisplayName("忽略 macOS 资源分支目录和文件")
    void ignoresMacOsJunkWithoutChangingFileStats() throws IOException {
        Path zip = zip(Map.of(
                "p/left4dead2/addons/sourcemod/plugins/a.smx", "smx",
                "__MACOSX/p/._a.smx", "metadata",
                "p/.DS_Store", "metadata"));

        ArchiveManifest manifest = reader.read(zip, 10, 1024, null, "left4dead2");

        assertEquals(1, manifest.fileCount());
        assertEquals(3, manifest.totalBytes());
        assertEquals(List.of("left4dead2/addons/sourcemod/plugins/a.smx"),
                manifest.relativeFiles());
    }

    @Test
    @DisplayName("缺少 left4dead2 时拒绝")
    void rejectsMissingLeft4Dead2() throws IOException {
        Path zip = zip(Map.of("p/addons/sourcemod/plugins/a.smx", "x"));
        ArchiveManifestException ex = assertThrows(ArchiveManifestException.class,
                () -> reader.read(zip, 10, 1024, null, "left4dead2"));
        assertFalse(ex.getMessage().isBlank());
        assertTrue(ex.getMessage().contains("left4dead2"));
    }

    @Test
    @DisplayName("只有空目录时拒绝，不能把零文件归档当作成功")
    void rejectsDirectoriesWithoutFiles() throws IOException {
        Path zip = tempDir.resolve("empty-directories.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip.toFile())) {
            for (String name : List.of(
                    "p/left4dead2/",
                    "p/left4dead2/addons/sourcemod/plugins/")) {
                ZipArchiveEntry entry = new ZipArchiveEntry(name);
                out.putArchiveEntry(entry);
                out.closeArchiveEntry();
            }
        }

        ArchiveManifestException ex = assertThrows(ArchiveManifestException.class,
                () -> reader.read(zip, 10, 1024, null, "left4dead2"));

        assertEquals("ARCHIVE_EMPTY", ex.getFailureCode());
    }

    @Test
    @DisplayName("多插件归档返回可回退失败码")
    void rejectsMultiplePluginRootsWithFallbackCode() throws IOException {
        Path zip = zip(Map.of(
                "plugin-a/left4dead2/addons/sourcemod/plugins/a.smx", "a",
                "plugin-b/left4dead2/addons/sourcemod/plugins/b.smx", "b"));

        ArchiveManifestException ex = assertThrows(ArchiveManifestException.class,
                () -> reader.read(zip, 10, 1024, null, "left4dead2"));

        assertEquals("ARCHIVE_MULTIPLE_PLUGIN_ROOTS", ex.getFailureCode());
    }

    @Test
    @DisplayName("兼容缺少 UTF-8 标志的 GBK 中文文件名")
    void readsLegacyGbkEntryName() throws IOException {
        Path zip = tempDir.resolve("gbk.zip");
        Charset gbk = Charset.forName("GBK");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip.toFile())) {
            out.setEncoding(gbk.name());
            ZipArchiveEntry entry = new ZipArchiveEntry(
                    "中文插件/left4dead2/addons/sourcemod/plugins/a.smx");
            out.putArchiveEntry(entry);
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }

        ArchiveManifest manifest = reader.read(zip, 10, 1024, null, "left4dead2");

        assertEquals("中文插件", manifest.pluginRoot());
        assertTrue(manifest.relativeFiles().contains(
                "left4dead2/addons/sourcemod/plugins/a.smx"));
    }

    @Test
    @DisplayName("结构根由调用方指定，核心不依赖具体游戏目录")
    void usesCallerProvidedStructureRoot() throws IOException {
        Path zip = zip(Map.of(
                "my-plugin/game-data/addons/plugin.jar", "jar"));

        ArchiveManifest manifest = reader.read(
                zip, 10, 1024, "my-plugin", "game-data");

        assertEquals("my-plugin", manifest.pluginRoot());
        assertEquals(Set.of("game-data/addons/plugin.jar"),
                Set.copyOf(manifest.relativeFiles()));
    }

    private Path zip(Map<String, String> entries) throws IOException {
        Path zip = tempDir.resolve("archive-" + System.nanoTime() + ".zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip.toFile())) {
            for (Map.Entry<String, String> item : new LinkedHashMap<>(entries).entrySet()) {
                ZipArchiveEntry entry = new ZipArchiveEntry(item.getKey());
                out.putArchiveEntry(entry);
                out.write(item.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeArchiveEntry();
            }
        }
        return zip;
    }

}
