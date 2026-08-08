package com.gameplatform.plugin.l4d2.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VPK 文件解析工具
 * 用于解析 L4D2 的 VPK 文件格式，提取地图章节信息
 * 
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
public class VpkParser {

    // VPK 文件签名
    private static final int VPK_SIGNATURE = 0x55AA1234;
    // VPK 文件头大小（magic + version + treeSize）
    private static final int HEADER_SIZE = 12;
    
    // 正则表达式用于解析键值对
    private static final Pattern KV_PATTERN = Pattern.compile("\"([^\"]+)\"\\s+\"([^\"]+)\"");

    /**
     * 解析 VPK 文件
     *
     * @param vpkFile VPK 文件
     * @return VPK 归档信息
     */
    public VpkArchive parse(File vpkFile) {
        try (RandomAccessFile raf = new RandomAccessFile(vpkFile, "r")) {
            return parseVpkArchive(raf, vpkFile.getName());
        } catch (IOException e) {
            log.error("解析 VPK 文件失败: {}", vpkFile.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 解析 VPK 文件并填充每个 entry 的 chunkData（含 preload + file 数据）
     * 用于 VPK 裁剪场景，需要拿到完整文件字节以便重建。
     *
     * @param vpkFile VPK 文件
     * @return VPK 归档信息（每个主文件 entry 的 chunkData 已被填充）
     */
    public VpkArchive parseWithChunkData(File vpkFile) {
        try (RandomAccessFile raf = new RandomAccessFile(vpkFile, "r")) {
            VpkArchive archive = parseVpkArchive(raf, vpkFile.getName());
            if (archive != null && archive.getTreeSize() > 0) {
                fillChunkData(raf, archive);
            }
            return archive;
        } catch (IOException e) {
            log.error("解析 VPK 文件失败 (with chunk data): {}", vpkFile.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 为 archive 中 archiveIndex == 0xFFFF（主 VPK 文件）的 entry 填充 chunkData
     * chunkData = preloadData + fileData（如果两者都有）
     */
    private void fillChunkData(RandomAccessFile raf, VpkArchive archive) throws IOException {
        long chunkBase = (long) HEADER_SIZE + archive.getTreeSize();
        for (VpkFileEntry entry : archive.getFileEntries()) {
            if (entry.getArchiveIndex() != 0xFFFF) {
                // 多包 VPK，chunk 数据不在主 VPK 文件中，跳过
                continue;
            }
            byte[] fileData = null;
            if (entry.getFileSize() > 0) {
                raf.seek(chunkBase + entry.getFileOffset());
                fileData = new byte[entry.getFileSize()];
                raf.readFully(fileData);
            }
            byte[] preload = entry.getPreloadData();
            byte[] chunkData;
            if (preload != null && preload.length > 0) {
                if (fileData != null && fileData.length > 0) {
                    chunkData = new byte[preload.length + fileData.length];
                    System.arraycopy(preload, 0, chunkData, 0, preload.length);
                    System.arraycopy(fileData, 0, chunkData, preload.length, fileData.length);
                } else {
                    chunkData = preload.clone();
                }
            } else if (fileData != null) {
                chunkData = fileData;
            } else {
                chunkData = new byte[0];
            }
            entry.setChunkData(chunkData);
        }
    }

    /**
     * 解析 VPK 归档
     */
    private VpkArchive parseVpkArchive(RandomAccessFile raf, String fileName) throws IOException {
        VpkArchive archive = new VpkArchive();
        archive.setFileName(fileName);

        // 读取文件头
        int signature = readInt32(raf);
        if (signature != VPK_SIGNATURE) {
            // 单文件 VPK，直接读取
            raf.seek(0);
            return parseSingleFileVpk(raf, fileName);
        }

        // 读取版本
        int version = readInt32(raf);
        archive.setVersion(version);

        // 读取目录树大小
        int treeSize = readInt32(raf);
        archive.setTreeSize(treeSize);

        // 读取目录树
        parseDirectoryTree(raf, archive);

        return archive;
    }

    /**
     * 解析单文件 VPK（地图 VPK 通常是这种格式）
     */
    private VpkArchive parseSingleFileVpk(RandomAccessFile raf, String fileName) throws IOException {
        VpkArchive archive = new VpkArchive();
        archive.setFileName(fileName);
        archive.setVersion(1);

        // 单文件 VPK 直接包含数据
        // 我们需要扫描整个文件来查找 missions/*.txt 文件
        raf.seek(0);
        long fileLength = raf.length();
        
        // 简化处理：直接读取整个文件内容并查找 mission 文件
        byte[] fileData = new byte[(int) fileLength];
        raf.readFully(fileData);
        
        // 查找 missions 目录标记
        String content = new String(fileData, StandardCharsets.ISO_8859_1);
        parseMissionFilesFromContent(content, archive);

        return archive;
    }

    /**
     * 解析目录树
     */
    private void parseDirectoryTree(RandomAccessFile raf, VpkArchive archive) throws IOException {
        while (true) {
            // 读取扩展名
            String extension = readNullTerminatedString(raf);
            if (extension.isEmpty()) {
                break;
            }

            // 读取路径
            while (true) {
                String path = readNullTerminatedString(raf);
                if (path.isEmpty()) {
                    break;
                }

                // 读取文件名
                while (true) {
                    String filename = readNullTerminatedString(raf);
                    if (filename.isEmpty()) {
                        break;
                    }

                    // 读取文件元数据
                    VpkFileEntry entry = new VpkFileEntry();
                    entry.setExtension(extension);
                    entry.setPath(path);
                    entry.setFilename(filename);
                    entry.setCrc(readInt32(raf));
                    
                    // 读取预加载数据大小
                    int preloadBytes = readInt16(raf);
                    entry.setPreloadBytes(preloadBytes);

                    // 读取归档索引
                    int archiveIndex = readInt16(raf);
                    entry.setArchiveIndex(archiveIndex);

                    // 读取文件偏移和大小
                    entry.setFileOffset(readInt32(raf));
                    entry.setFileSize(readInt32(raf));

                    // 读取预加载数据
                    if (preloadBytes > 0) {
                        byte[] preloadData = new byte[preloadBytes];
                        raf.readFully(preloadData);
                        entry.setPreloadData(preloadData);
                    }

                    // 跳过终结符
                    readInt16(raf);

                    archive.addFileEntry(entry);
                }
            }
        }
    }

    /**
     * 从内容中解析任务文件
     */
    private void parseMissionFilesFromContent(String content, VpkArchive archive) {
        // 查找 missions/*.txt 文件的内容
        // 这是一个简化的实现，实际 VPK 格式更复杂
        int missionsIndex = content.indexOf("missions/");
        while (missionsIndex != -1) {
            // 查找 .txt 结尾
            int txtIndex = content.indexOf(".txt", missionsIndex);
            if (txtIndex == -1) {
                break;
            }

            String missionPath = content.substring(missionsIndex, txtIndex + 4);
            
            // 创建文件条目
            VpkFileEntry entry = new VpkFileEntry();
            entry.setPath("missions");
            entry.setFilename(missionPath.substring(missionPath.lastIndexOf('/') + 1));
            entry.setExtension("txt");
            
            archive.addFileEntry(entry);

            // 查找下一个
            missionsIndex = content.indexOf("missions/", txtIndex);
        }
    }

    /**
     * 读取 null 终止字符串
     */
    private String readNullTerminatedString(RandomAccessFile raf) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = raf.read()) != 0 && b != -1) {
            baos.write(b);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    /**
     * 读取 32 位整数（小端序）
     */
    private int readInt32(RandomAccessFile raf) throws IOException {
        byte[] bytes = new byte[4];
        raf.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /**
     * 读取 16 位整数（小端序）
     */
    private int readInt16(RandomAccessFile raf) throws IOException {
        byte[] bytes = new byte[2];
        raf.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    /**
     * VPK 归档信息
     */
    @Data
    public static class VpkArchive {
        private String fileName;
        private int version;
        private int treeSize;
        private List<VpkFileEntry> fileEntries = new ArrayList<>();

        public void addFileEntry(VpkFileEntry entry) {
            fileEntries.add(entry);
        }

        /**
         * 获取所有任务文件
         */
        public List<VpkFileEntry> getMissionFiles() {
            List<VpkFileEntry> missionFiles = new ArrayList<>();
            for (VpkFileEntry entry : fileEntries) {
                if ("missions".equals(entry.getPath()) && "txt".equals(entry.getExtension())) {
                    missionFiles.add(entry);
                }
            }
            return missionFiles;
        }
    }

    /**
     * VPK 文件条目
     */
    @Data
    public static class VpkFileEntry {
        private String extension;
        private String path;
        private String filename;
        private int crc;
        private int preloadBytes;
        private int archiveIndex;
        private int fileOffset;
        private int fileSize;
        private byte[] preloadData;
        /** chunkData = preloadData + fileData（懒加载，仅 parseWithChunkData 填充） */
        private byte[] chunkData;

        public String getFullPath() {
            return path + "/" + filename + "." + extension;
        }
    }

    /**
     * 解析任务文件内容
     *
     * @param reader 文件读取器
     * @return 战役信息
     */
    public static Campaign parseMissionFile(BufferedReader reader) throws IOException {
        Campaign campaign = new Campaign();
        Map<String, Chapter> chapterMap = new HashMap<>();

        boolean inGameModeSection = false;
        int braceLevel = 0;
        String tempMapName = null;
        String currentMode = null;

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }

            // 移除行内注释
            int commentIndex = line.indexOf("//");
            if (commentIndex != -1) {
                line = line.substring(0, commentIndex).trim();
            }

            String lowerLine = line.toLowerCase();

            // 检测游戏模式区域
            if (!inGameModeSection && isGameModeStart(lowerLine)) {
                inGameModeSection = true;
                braceLevel = 0;
                currentMode = extractGameMode(lowerLine);
                continue;
            }

            if (inGameModeSection) {
                // 计算大括号层级
                for (char c : line.toCharArray()) {
                    if (c == '{') braceLevel++;
                    else if (c == '}') braceLevel--;
                }

                // 退出游戏模式区域
                if (braceLevel <= 0) {
                    inGameModeSection = false;
                    currentMode = null;
                    continue;
                }
            }

            // 解析键值对
            Matcher matcher = KV_PATTERN.matcher(line);
            if (matcher.find()) {
                String key = matcher.group(1).toLowerCase();
                String value = matcher.group(2);

                // 战役标题
                if ("displaytitle".equals(key) && campaign.getTitle() == null) {
                    campaign.setTitle(value);
                }

                // 在游戏模式区域内
                if (inGameModeSection && currentMode != null) {
                    if ("map".equals(key)) {
                        tempMapName = value;
                    }

                    if ("displayname".equals(key) && tempMapName != null) {
                        Chapter chapter = chapterMap.get(tempMapName);
                        if (chapter == null) {
                            chapter = new Chapter();
                            chapter.setCode(tempMapName);
                            chapter.setTitle(value);
                            chapter.addMode(currentMode);
                            campaign.addChapter(chapter);
                            chapterMap.put(tempMapName, chapter);
                        } else {
                            chapter.addMode(currentMode);
                        }
                        tempMapName = null;
                    }
                }
            }
        }

        return campaign;
    }

    /**
     * 判断是否是游戏模式开始
     */
    private static boolean isGameModeStart(String line) {
        return line.equals("\"coop\"") || line.equals("\"survival\"") ||
               line.equals("\"halftank\"") || line.equals("\"brawler\"") ||
               line.equals("\"versus\"") || line.equals("\"scavenge\"") ||
               line.equals("\"realism\"");
    }

    /**
     * 提取游戏模式名称
     */
    private static String extractGameMode(String line) {
        return line.replace("\"", "").trim();
    }

    /**
     * 战役信息
     */
    @Data
    public static class Campaign {
        private String title;
        private String vpkName;
        private List<Chapter> chapters = new ArrayList<>();

        public void addChapter(Chapter chapter) {
            chapters.add(chapter);
        }
    }

    /**
     * 章节信息
     */
    @Data
    public static class Chapter {
        private String code;
        private String title;
        private List<String> modes = new ArrayList<>();

        public void addMode(String mode) {
            if (!modes.contains(mode)) {
                modes.add(mode);
            }
        }
    }
}
