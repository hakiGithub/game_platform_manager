package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.util.VpkParser;
import com.gameplatform.plugin.l4d2.util.VpkParser.Campaign;
import com.gameplatform.plugin.l4d2.util.VpkParser.Chapter;
import com.gameplatform.plugin.l4d2.util.VpkParser.VpkArchive;
import com.gameplatform.plugin.l4d2.util.VpkParser.VpkFileEntry;
import com.gameplatform.plugin.l4d2.vo.MissionInfoVO;
import com.gameplatform.plugin.l4d2.vo.MissionInfoVO.ChapterVO;
import com.gameplatform.plugin.l4d2.vo.VpkTrimResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VPK 二进制裁剪服务
 * 用于裁剪 L4D2 VPK 地图文件中的冗余数据（材质/音频/模型等），保留 mission/bsp 等运行时必需文件。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
public class VpkTrimService {

    private static final int VPK_MAGIC = 0x55AA1234;
    private static final int VPK_VERSION = 1;
    private static final int HEADER_SIZE = 12;
    /** 单条 entry 固定字节数：crc(4) + preBytes(2) + archiveIdx(2) + offset(4) + length(4) + terminator(2) */
    private static final int ENTRY_FIXED_SIZE = 18;

    /** 需移除的扩展名（小写，无点） */
    private static final Set<String> TRIM_EXTENSIONS = new HashSet<>(Arrays.asList("vmf", "vmx"));
    /** 需移除的路径前缀（按 path 字段前缀匹配，小写） */
    private static final List<String> TRIM_PATH_PREFIXES = Arrays.asList(
            "materials", "sound", "sounds", "models"
    );

    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final VpkParser vpkParser = new VpkParser();

    /**
     * 裁剪 VPK 文件
     *
     * @param vpkFile VPK 文件
     * @param backup  是否备份原文件
     * @return 裁剪结果（含大小差异）
     */
    public VpkTrimResultVO trim(File vpkFile, boolean backup) {
        VpkTrimResultVO result = new VpkTrimResultVO();
        result.setFileName(vpkFile.getName());
        long originalSize = vpkFile.length();
        result.setOriginalSize(originalSize);

        VpkArchive archive = vpkParser.parseWithChunkData(vpkFile);
        if (archive == null || archive.getTreeSize() <= 0) {
            // 单文件 VPK 或解析失败，无法裁剪
            result.setTrimmedSize(originalSize);
            result.setSavedBytes(0);
            result.setTotalEntries(archive == null ? 0 : archive.getFileEntries().size());
            result.setTrimmedEntries(0);
            result.setBackupCreated(false);
            return result;
        }

        List<VpkFileEntry> allEntries = archive.getFileEntries();
        List<VpkFileEntry> keptEntries = new ArrayList<>();
        int trimmedCount = 0;
        for (VpkFileEntry entry : allEntries) {
            if (shouldTrim(entry)) {
                trimmedCount++;
            } else {
                keptEntries.add(entry);
            }
        }

        result.setTotalEntries(allEntries.size());
        result.setTrimmedEntries(trimmedCount);

        if (backup) {
            String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP);
            File backupFile = new File(vpkFile.getParentFile(), vpkFile.getName() + ".bak." + timestamp);
            try {
                Files.copy(vpkFile.toPath(), backupFile.toPath());
                result.setBackupCreated(true);
                result.setBackupFileName(backupFile.getName());
            } catch (IOException e) {
                throw new L4D2PluginException("FILE", "备份 VPK 文件失败: " + vpkFile.getName(), e);
            }
        } else {
            result.setBackupCreated(false);
        }

        try {
            rebuildVpk(vpkFile, keptEntries);
        } catch (IOException e) {
            throw new L4D2PluginException("FILE", "重建 VPK 文件失败: " + vpkFile.getName(), e);
        }

        long trimmedSize = vpkFile.length();
        result.setTrimmedSize(trimmedSize);
        result.setSavedBytes(originalSize - trimmedSize);

        log.info("VPK 裁剪完成: {} 共 {} 条目，移除 {} 条目，原大小 {} 字节，新大小 {} 字节，节省 {} 字节",
                vpkFile.getName(), allEntries.size(), trimmedCount, originalSize, trimmedSize, result.getSavedBytes());

        return result;
    }

    /**
     * 解析 VPK mission 信息
     *
     * @param vpkFile VPK 文件
     * @return 战役任务信息
     */
    public MissionInfoVO parseMission(File vpkFile) {
        MissionInfoVO vo = new MissionInfoVO();
        vo.setVpkName(vpkFile.getName());

        VpkArchive archive = vpkParser.parseWithChunkData(vpkFile);
        if (archive == null) {
            return vo;
        }

        List<VpkFileEntry> missionFiles = archive.getMissionFiles();
        for (VpkFileEntry missionFile : missionFiles) {
            byte[] chunkData = missionFile.getChunkData();
            if (chunkData == null || chunkData.length == 0) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(chunkData), StandardCharsets.UTF_8))) {
                Campaign campaign = VpkParser.parseMissionFile(reader);
                if (campaign == null) {
                    continue;
                }
                if (campaign.getTitle() != null && vo.getTitle() == null) {
                    vo.setTitle(campaign.getTitle());
                }
                if (!campaign.getChapters().isEmpty()) {
                    if (vo.getChapters() == null) {
                        vo.setChapters(new ArrayList<>());
                    }
                    for (Chapter chapter : campaign.getChapters()) {
                        ChapterVO chapterVO = new ChapterVO();
                        chapterVO.setCode(chapter.getCode());
                        chapterVO.setTitle(chapter.getTitle());
                        chapterVO.setModes(new ArrayList<>(chapter.getModes()));
                        vo.getChapters().add(chapterVO);
                    }
                }
            } catch (IOException e) {
                log.error("解析 mission 文件失败: {}", missionFile.getFullPath(), e);
            }
        }

        return vo;
    }

    /**
     * 判断文件是否需要裁剪（移除）
     * - 扩展名在 TRIM_EXTENSIONS 中 → true
     * - path 以 TRIM_PATH_PREFIXES 任一开头 → true
     * - 否则 false
     */
    boolean shouldTrim(VpkFileEntry entry) {
        String ext = entry.getExtension() == null ? "" : entry.getExtension().toLowerCase();
        if (TRIM_EXTENSIONS.contains(ext)) {
            return true;
        }
        String path = entry.getPath() == null ? "" : entry.getPath().toLowerCase();
        for (String prefix : TRIM_PATH_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 重新构造 VPK 文件
     * - 新 Header：magic + version + newTreeSize
     * - 新 Tree：遍历保留的 entries，按 extension/path/filename 三层结构输出
     * - 新 Chunk：保留的 entries 的 fileData 按顺序紧凑排列
     * - 关键：重新计算每个 entry 的 fileOffset（在新 Chunk 中的偏移）
     */
    private void rebuildVpk(File target, List<VpkFileEntry> keptEntries) throws IOException {
        // 1. 按 extension -> path -> filenames 分组（保持顺序）
        LinkedHashMap<String, LinkedHashMap<String, List<VpkFileEntry>>> grouped = new LinkedHashMap<>();
        for (VpkFileEntry entry : keptEntries) {
            grouped.computeIfAbsent(entry.getExtension(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(entry.getPath(), k -> new ArrayList<>())
                    .add(entry);
        }

        // 2. 计算新 Tree 大小
        int newTreeSize = computeTreeSize(grouped);

        // 3. 重新计算每个主文件 entry（archiveIndex == 0xFFFF）的 fileOffset
        //    多包 VPK entry 保留原 offset（不在主文件中，无需调整）
        int chunkOffset = 0;
        for (VpkFileEntry entry : keptEntries) {
            if (entry.getArchiveIndex() != 0xFFFF) {
                continue;
            }
            entry.setFileOffset(chunkOffset);
            chunkOffset += entry.getFileSize();
        }

        // 4. 写入临时文件，再替换原文件
        File tempFile = new File(target.getParentFile(), target.getName() + ".trim.tmp");
        try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
            raf.setLength(0);

            // Header
            writeLeInt(raf, VPK_MAGIC);
            writeLeInt(raf, VPK_VERSION);
            writeLeInt(raf, newTreeSize);

            // Tree
            for (Map.Entry<String, LinkedHashMap<String, List<VpkFileEntry>>> extEntry : grouped.entrySet()) {
                writeNullString(raf, extEntry.getKey());
                for (Map.Entry<String, List<VpkFileEntry>> pathEntry : extEntry.getValue().entrySet()) {
                    writeNullString(raf, pathEntry.getKey());
                    for (VpkFileEntry entry : pathEntry.getValue()) {
                        writeNullString(raf, entry.getFilename());
                        int preloadLen = entry.getPreloadData() == null ? 0 : entry.getPreloadData().length;
                        writeLeInt(raf, entry.getCrc());
                        writeLeShort(raf, preloadLen);
                        writeLeShort(raf, entry.getArchiveIndex());
                        writeLeInt(raf, entry.getFileOffset());
                        writeLeInt(raf, entry.getFileSize());
                        if (preloadLen > 0) {
                            raf.write(entry.getPreloadData());
                        }
                        writeLeShort(raf, 0); // terminator
                    }
                    writeNullString(raf, ""); // empty filename (退出 filename 循环)
                }
                writeNullString(raf, ""); // empty path (退出 path 循环)
            }
            writeNullString(raf, ""); // empty extension (退出 extension 循环)

            // Chunk data
            for (VpkFileEntry entry : keptEntries) {
                if (entry.getArchiveIndex() != 0xFFFF) {
                    continue;
                }
                byte[] fileData = extractFileData(entry);
                if (fileData != null && fileData.length > 0) {
                    raf.write(fileData);
                }
            }
        }

        // 替换原文件（Windows 下需先删除目标）
        if (target.exists() && !target.delete()) {
            throw new IOException("无法删除原 VPK 文件: " + target);
        }
        if (!tempFile.renameTo(target)) {
            throw new IOException("无法重命名临时文件到目标: " + target);
        }
    }

    /**
     * 计算新 Tree 大小
     */
    private int computeTreeSize(LinkedHashMap<String, LinkedHashMap<String, List<VpkFileEntry>>> grouped) {
        int size = 0;
        for (Map.Entry<String, LinkedHashMap<String, List<VpkFileEntry>>> extEntry : grouped.entrySet()) {
            size += extEntry.getKey().getBytes(StandardCharsets.UTF_8).length + 1; // ext\0
            for (Map.Entry<String, List<VpkFileEntry>> pathEntry : extEntry.getValue().entrySet()) {
                size += pathEntry.getKey().getBytes(StandardCharsets.UTF_8).length + 1; // path\0
                for (VpkFileEntry entry : pathEntry.getValue()) {
                    size += entry.getFilename().getBytes(StandardCharsets.UTF_8).length + 1; // filename\0
                    int preloadLen = entry.getPreloadData() == null ? 0 : entry.getPreloadData().length;
                    size += ENTRY_FIXED_SIZE + preloadLen;
                }
                size += 1; // empty filename
            }
            size += 1; // empty path
        }
        size += 1; // empty extension
        return size;
    }

    /**
     * 从 entry.chunkData 中提取 fileData（去掉 preload 前缀）
     */
    private byte[] extractFileData(VpkFileEntry entry) {
        byte[] chunkData = entry.getChunkData();
        if (chunkData == null || chunkData.length == 0) {
            return new byte[0];
        }
        byte[] preload = entry.getPreloadData();
        int preloadLen = preload == null ? 0 : preload.length;
        if (preloadLen == 0) {
            return chunkData;
        }
        if (preloadLen >= chunkData.length) {
            return new byte[0];
        }
        byte[] fileData = new byte[chunkData.length - preloadLen];
        System.arraycopy(chunkData, preloadLen, fileData, 0, fileData.length);
        return fileData;
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
}
