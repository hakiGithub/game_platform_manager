package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.util.VpkParser;
import com.gameplatform.plugin.l4d2.util.VpkParser.VpkArchive;
import com.gameplatform.plugin.l4d2.util.VpkParser.VpkFileEntry;
import com.gameplatform.plugin.l4d2.vo.MissionInfoVO;
import com.gameplatform.plugin.l4d2.vo.VpkTrimResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VpkTrimService 单元测试
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class VpkTrimServiceTest {

    @TempDir
    Path tempDir;

    private VpkTrimService service;

    @BeforeEach
    void setUp() {
        service = new VpkTrimService();
    }

    // ============================================================
    // 测试 VPK 文件构造 helper
    // ============================================================

    /**
     * 构造最小测试 VPK 文件，含 4 个条目（按扩展名字典序）：
     * - bsp / maps / test,        10 字节 chunkData（保留）
     * - mp3 / sound / test,       50 字节 chunkData（裁剪）
     * - txt / missions / test,    5  字节 chunkData（保留）
     * - vtf / materials / test,   100 字节 chunkData（裁剪）
     */
    private File buildTestVpk(File vpkFile) throws IOException {
        // 条目（按 ext/path/filename 字典序排列）
        // chunkData 的物理顺序与 tree 顺序一致
        Object[][] entries = {
                {"bsp", "maps", "test", 0, 10},
                {"mp3", "sound", "test", 10, 50},
                {"txt", "missions", "test", 60, 5},
                {"vtf", "materials", "test", 65, 100}
        };

        // 计算 tree size
        int treeSize = 0;
        for (Object[] e : entries) {
            String ext = (String) e[0];
            String path = (String) e[1];
            String fn = (String) e[2];
            treeSize += ext.getBytes(StandardCharsets.UTF_8).length + 1;
            treeSize += path.getBytes(StandardCharsets.UTF_8).length + 1;
            treeSize += fn.getBytes(StandardCharsets.UTF_8).length + 1;
            treeSize += 18; // crc + preBytes + archiveIdx + offset + length + terminator
            treeSize += 1; // empty filename
            treeSize += 1; // empty path
        }
        treeSize += 1; // empty extension

        try (RandomAccessFile raf = new RandomAccessFile(vpkFile, "rw")) {
            raf.setLength(0);

            // Header
            writeLeInt(raf, 0x55AA1234);
            writeLeInt(raf, 1);
            writeLeInt(raf, treeSize);

            // Tree
            for (Object[] e : entries) {
                String ext = (String) e[0];
                String path = (String) e[1];
                String fn = (String) e[2];
                int offset = (int) e[3];
                int length = (int) e[4];

                writeNullString(raf, ext);
                writeNullString(raf, path);
                writeNullString(raf, fn);

                writeLeInt(raf, 0);          // crc
                writeLeShort(raf, 0);        // preBytes
                writeLeShort(raf, 0xFFFF);   // archiveIdx (主文件)
                writeLeInt(raf, offset);     // offset
                writeLeInt(raf, length);     // length
                writeLeShort(raf, 0);        // terminator

                writeNullString(raf, "");    // empty filename
                writeNullString(raf, "");    // empty path
            }
            writeNullString(raf, "");        // empty extension

            // Chunk data
            for (Object[] e : entries) {
                int length = (int) e[4];
                raf.write(new byte[length]);
            }
        }

        return vpkFile;
    }

    /**
     * 构造含 mission txt 的测试 VPK
     */
    private File buildMissionVpk(File vpkFile, String missionContent) throws IOException {
        byte[] missionBytes = missionContent.getBytes(StandardCharsets.UTF_8);
        int missionLen = missionBytes.length;

        // 单一条目：txt / missions / test
        int treeSize = "txt".length() + 1 + "missions".length() + 1 + "test".length() + 1
                + 18 + 1 + 1 + 1;

        try (RandomAccessFile raf = new RandomAccessFile(vpkFile, "rw")) {
            raf.setLength(0);

            writeLeInt(raf, 0x55AA1234);
            writeLeInt(raf, 1);
            writeLeInt(raf, treeSize);

            writeNullString(raf, "txt");
            writeNullString(raf, "missions");
            writeNullString(raf, "test");
            writeLeInt(raf, 0);
            writeLeShort(raf, 0);
            writeLeShort(raf, 0xFFFF);
            writeLeInt(raf, 0);
            writeLeInt(raf, missionLen);
            writeLeShort(raf, 0);
            writeNullString(raf, "");
            writeNullString(raf, "");
            writeNullString(raf, "");

            raf.write(missionBytes);
        }

        return vpkFile;
    }

    private void writeLeInt(RandomAccessFile raf, int value) throws IOException {
        byte[] b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
        raf.write(b);
    }

    private void writeLeShort(RandomAccessFile raf, int value) throws IOException {
        byte[] b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array();
        raf.write(b);
    }

    private void writeNullString(RandomAccessFile raf, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        raf.write(bytes);
        raf.write(0);
    }

    // ============================================================
    // shouldTrim 测试
    // ============================================================

    @Test
    void shouldTrim_vmfExtension() {
        VpkFileEntry entry = new VpkFileEntry();
        entry.setExtension("vmf");
        entry.setPath("maps");
        entry.setFilename("test");
        assertTrue(service.shouldTrim(entry));
    }

    @Test
    void shouldTrim_vmxExtension() {
        VpkFileEntry entry = new VpkFileEntry();
        entry.setExtension("vmx");
        entry.setPath("maps");
        entry.setFilename("test");
        assertTrue(service.shouldTrim(entry));
    }

    @Test
    void shouldTrim_materialsPath() {
        VpkFileEntry entry = new VpkFileEntry();
        entry.setExtension("vtf");
        entry.setPath("materials");
        entry.setFilename("test");
        assertTrue(service.shouldTrim(entry));
    }

    @Test
    void shouldTrim_soundPath() {
        VpkFileEntry entry = new VpkFileEntry();
        entry.setExtension("mp3");
        entry.setPath("sound");
        entry.setFilename("test");
        assertTrue(service.shouldTrim(entry));
    }

    @Test
    void shouldTrim_modelsPath() {
        VpkFileEntry entry = new VpkFileEntry();
        entry.setExtension("vvd");
        entry.setPath("models");
        entry.setFilename("test");
        assertTrue(service.shouldTrim(entry));
    }

    @Test
    void shouldTrim_soundsPath() {
        VpkFileEntry entry = new VpkFileEntry();
        entry.setExtension("wav");
        entry.setPath("sounds");
        entry.setFilename("test");
        assertTrue(service.shouldTrim(entry));
    }

    @Test
    void shouldTrim_modelsNestedPath() {
        VpkFileEntry entry = new VpkFileEntry();
        entry.setExtension("vvd");
        entry.setPath("models/props");
        entry.setFilename("test");
        assertTrue(service.shouldTrim(entry));
    }

    @Test
    void shouldTrim_notMapsBsp() {
        VpkFileEntry entry = new VpkFileEntry();
        entry.setExtension("bsp");
        entry.setPath("maps");
        entry.setFilename("test");
        assertFalse(service.shouldTrim(entry));
    }

    @Test
    void shouldTrim_notMissionsTxt() {
        VpkFileEntry entry = new VpkFileEntry();
        entry.setExtension("txt");
        entry.setPath("missions");
        entry.setFilename("test");
        assertFalse(service.shouldTrim(entry));
    }

    @Test
    void shouldTrim_notMaterialsSuffix() {
        // 路径名以 materials 开头但不等于且不以 materials/ 开头 → 不裁剪
        VpkFileEntry entry = new VpkFileEntry();
        entry.setExtension("bsp");
        entry.setPath("materials_custom");
        entry.setFilename("test");
        assertFalse(service.shouldTrim(entry));
    }

    // ============================================================
    // trim 测试
    // ============================================================

    @Test
    void trim_withoutBackup() throws Exception {
        File vpkFile = tempDir.resolve("test.vpk").toFile();
        buildTestVpk(vpkFile);
        long originalSize = vpkFile.length();

        VpkTrimResultVO result = service.trim(vpkFile, false);

        assertEquals("test.vpk", result.getFileName());
        assertEquals(originalSize, result.getOriginalSize());
        assertEquals(4, result.getTotalEntries());
        assertEquals(2, result.getTrimmedEntries());
        assertFalse(result.isBackupCreated());
        assertTrue(result.getOriginalSize() > result.getTrimmedSize());
        assertEquals(result.getOriginalSize() - result.getTrimmedSize(), result.getSavedBytes());

        // 验证裁剪后文件可被重新解析
        VpkParser parser = new VpkParser();
        VpkArchive reparsed = parser.parseWithChunkData(vpkFile);
        assertNotNull(reparsed);
        assertEquals(2, reparsed.getFileEntries().size());

        // 验证保留的 entries 是 maps/test.bsp + missions/test.txt
        List<String> fullPaths = new ArrayList<>();
        for (VpkFileEntry e : reparsed.getFileEntries()) {
            fullPaths.add(e.getFullPath());
        }
        assertTrue(fullPaths.contains("maps/test.bsp"));
        assertTrue(fullPaths.contains("missions/test.txt"));

        // 验证 chunkData 长度正确
        for (VpkFileEntry e : reparsed.getFileEntries()) {
            assertNotNull(e.getChunkData());
            if ("bsp".equals(e.getExtension())) {
                assertEquals(10, e.getChunkData().length);
            } else if ("txt".equals(e.getExtension())) {
                assertEquals(5, e.getChunkData().length);
            }
        }
    }

    @Test
    void trim_withBackup() throws Exception {
        File vpkFile = tempDir.resolve("test.vpk").toFile();
        buildTestVpk(vpkFile);
        long originalSize = vpkFile.length();

        VpkTrimResultVO result = service.trim(vpkFile, true);

        assertTrue(result.isBackupCreated());
        assertNotNull(result.getBackupFileName());
        assertTrue(result.getBackupFileName().startsWith("test.vpk.bak."));

        File backupFile = new File(vpkFile.getParentFile(), result.getBackupFileName());
        assertTrue(backupFile.exists());
        assertEquals(originalSize, backupFile.length());
    }

    @Test
    void trim_singleFileVpk_returnsUnchanged() throws Exception {
        // 构造一个单文件 VPK（没有 VPK magic header）
        File vpkFile = tempDir.resolve("single.vpk").toFile();
        try (RandomAccessFile raf = new RandomAccessFile(vpkFile, "rw")) {
            raf.setLength(0);
            raf.write("just some bsp data".getBytes(StandardCharsets.ISO_8859_1));
        }
        long originalSize = vpkFile.length();

        VpkTrimResultVO result = service.trim(vpkFile, false);

        assertEquals(originalSize, result.getOriginalSize());
        assertEquals(originalSize, result.getTrimmedSize());
        assertEquals(0, result.getSavedBytes());
        assertEquals(0, result.getTotalEntries());
        assertEquals(0, result.getTrimmedEntries());
        assertFalse(result.isBackupCreated());
    }

    // ============================================================
    // parseMission 测试
    // ============================================================

    @Test
    void parseMission_extractsTitleAndChapters() throws Exception {
        String missionContent = "\"mission\"\n" +
                "{\n" +
                "    \"displaytitle\" \"Test Campaign\"\n" +
                "    \"coop\"\n" +
                "    {\n" +
                "        \"1\"\n" +
                "        {\n" +
                "            \"map\" \"test_map_1\"\n" +
                "            \"displayname\" \"Chapter 1\"\n" +
                "        }\n" +
                "        \"2\"\n" +
                "        {\n" +
                "            \"map\" \"test_map_2\"\n" +
                "            \"displayname\" \"Chapter 2\"\n" +
                "        }\n" +
                "    }\n" +
                "}\n";

        File vpkFile = tempDir.resolve("mission.vpk").toFile();
        buildMissionVpk(vpkFile, missionContent);

        MissionInfoVO vo = service.parseMission(vpkFile);

        assertNotNull(vo);
        assertEquals("mission.vpk", vo.getVpkName());
        assertEquals("Test Campaign", vo.getTitle());
        assertNotNull(vo.getChapters());
        assertEquals(2, vo.getChapters().size());

        MissionInfoVO.ChapterVO ch1 = vo.getChapters().get(0);
        assertEquals("test_map_1", ch1.getCode());
        assertEquals("Chapter 1", ch1.getTitle());
        assertNotNull(ch1.getModes());
        assertTrue(ch1.getModes().contains("coop"));

        MissionInfoVO.ChapterVO ch2 = vo.getChapters().get(1);
        assertEquals("test_map_2", ch2.getCode());
        assertEquals("Chapter 2", ch2.getTitle());
    }

    @Test
    void parseMission_emptyVpk_returnsEmptyVo() throws Exception {
        // 构造一个只含 bsp/maps 条目（无 mission txt）的 VPK
        File vpkFile = tempDir.resolve("empty.vpk").toFile();
        buildNonMissionVpk(vpkFile);

        MissionInfoVO vo = service.parseMission(vpkFile);

        assertNotNull(vo);
        assertEquals("empty.vpk", vo.getVpkName());
        // 没有 mission txt，title 和 chapters 应为空
        assertEquals(null, vo.getTitle());
        assertEquals(null, vo.getChapters());
    }

    /**
     * 构造只含 bsp/maps 条目（无 mission txt）的 VPK
     */
    private File buildNonMissionVpk(File vpkFile) throws IOException {
        int treeSize = "bsp".length() + 1 + "maps".length() + 1 + "test".length() + 1
                + 18 + 1 + 1 + 1;
        try (RandomAccessFile raf = new RandomAccessFile(vpkFile, "rw")) {
            raf.setLength(0);
            writeLeInt(raf, 0x55AA1234);
            writeLeInt(raf, 1);
            writeLeInt(raf, treeSize);

            writeNullString(raf, "bsp");
            writeNullString(raf, "maps");
            writeNullString(raf, "test");
            writeLeInt(raf, 0);
            writeLeShort(raf, 0);
            writeLeShort(raf, 0xFFFF);
            writeLeInt(raf, 0);
            writeLeInt(raf, 5);
            writeLeShort(raf, 0);
            writeNullString(raf, "");
            writeNullString(raf, "");
            writeNullString(raf, "");

            raf.write(new byte[5]);
        }
        return vpkFile;
    }
}
