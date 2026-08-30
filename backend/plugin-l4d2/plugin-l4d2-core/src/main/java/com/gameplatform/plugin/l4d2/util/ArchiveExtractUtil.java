package com.gameplatform.plugin.l4d2.util;

import net.sf.sevenzipjbinding.ExtractAskMode;
import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 压缩包解压工具：ZIP（GBK 文件名）/ 7z / RAR（junrar）。
 * <p>
 * 安全约定（ADR-0018）：
 * <ul>
 *   <li>所有 entry 落盘前过 {@link ZipSlipGuard#normalizeAndCheck}，拒绝路径穿越</li>
 *   <li>跳过 macOS 垃圾（__MACOSX/、.DS_Store）</li>
 *   <li>解压总字节数与条目数硬上限（默认 4GB / 10000），超限立即中止</li>
 * </ul>
 * {@link #extractVpks} 为地图链路专用：只提取 .vpk、目录结构剥离（entry 取 base 名），
 * 与参考实现（l4d2-server-next）策略一致；其余条目不解压直接丢弃。
 */
@Component
public class ArchiveExtractUtil {

    private static final Charset GBK = Charset.forName("GBK");

    /** 默认解压总字节数上限：4GB（压缩比按 2:1 对齐 2GB 上传限额） */
    public static final long DEFAULT_MAX_EXTRACT_BYTES = 4L * 1024 * 1024 * 1024;

    /** 默认解压条目数上限 */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    /** 计数器：解压累计字节数与条目数（超限即中止） */
    private static class ExtractBudget {
        long bytes;
        int entries;
        final long maxBytes;
        final int maxEntries;

        ExtractBudget(long maxBytes, int maxEntries) {
            this.maxBytes = maxBytes;
            this.maxEntries = maxEntries;
        }

        void charge(long entryBytes) throws IOException {
            entries++;
            if (entries > maxEntries) {
                throw new IOException("解压条目数超过上限 " + maxEntries + "（疑似压缩炸弹，已中止）");
            }
            bytes += entryBytes;
            if (bytes > maxBytes) {
                throw new IOException("解压总大小超过上限 " + (maxBytes >> 20) + "MB（疑似压缩炸弹，已中止）");
            }
        }
    }

    /** VPK magic（小端 34 12 AA 55 = 0x55AA1234） */
    public static boolean isVpkFile(byte[] header) {
        return header.length >= 4
                && (header[0] & 0xFF) == 0x34
                && (header[1] & 0xFF) == 0x12
                && (header[2] & 0xFF) == 0xAA
                && (header[3] & 0xFF) == 0x55;
    }

    // ========== 全量解压（插件安装链路使用） ==========

    /** 解压 ZIP（GBK 文件名），带 slip 防护与默认上限 */
    public static List<File> extractZip(File zipFile, File destDir) throws IOException {
        return extractZip(zipFile, destDir, DEFAULT_MAX_EXTRACT_BYTES, DEFAULT_MAX_ENTRIES);
    }

    /** 解压 ZIP（GBK 文件名），带 slip 防护与自定义上限 */
    public static List<File> extractZip(File zipFile, File destDir, long maxBytes, int maxEntries) throws IOException {
        if (!destDir.exists()) destDir.mkdirs();
        ExtractBudget budget = new ExtractBudget(maxBytes, maxEntries);
        try (ZipFile zip = ZipFile.builder().setFile(zipFile).setCharset(GBK).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (ZipSlipGuard.isMacOSJunk(entry.getName())) continue;
                File out = resolveEntryFile(destDir, entry.getName());
                budget.charge(entry.getSize());
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    try (InputStream is = zip.getInputStream(entry);
                         OutputStream os = new FileOutputStream(out)) {
                        is.transferTo(os);
                    }
                }
            }
        }
        return listRoots(destDir);
    }

    /** 解压 7z，带 slip 防护与默认上限 */
    public static List<File> extract7z(File sevenZFile, File destDir) throws IOException {
        if (!destDir.exists()) destDir.mkdirs();
        ExtractBudget budget = new ExtractBudget(DEFAULT_MAX_EXTRACT_BYTES, DEFAULT_MAX_ENTRIES);
        try (SevenZFile sz = SevenZFile.builder().setFile(sevenZFile).get()) {
            SevenZArchiveEntry entry;
            while ((entry = sz.getNextEntry()) != null) {
                if (ZipSlipGuard.isMacOSJunk(entry.getName())) continue;
                File out = resolveEntryFile(destDir, entry.getName());
                budget.charge(entry.getSize());
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = sz.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
            }
        }
        return listRoots(destDir);
    }

    /** 解压 RAR（7-Zip JBinding，RAR4/5 均支持；加密包抛明确异常） */
    public static List<File> extractRar(File rarFile, File destDir) throws IOException {
        if (!destDir.exists()) destDir.mkdirs();
        ExtractBudget budget = new ExtractBudget(DEFAULT_MAX_EXTRACT_BYTES, DEFAULT_MAX_ENTRIES);
        try (RandomAccessFile raf = new RandomAccessFile(rarFile, "r");
             IInArchive archive = SevenZip.openInArchive(null, new RandomAccessFileInStream(raf))) {
            int count = archive.getNumberOfItems();
            for (int i = 0; i < count; i++) {
                String entryName = (String) archive.getProperty(i, PropID.PATH);
                if (ZipSlipGuard.isMacOSJunk(entryName)) continue;
                File out = resolveEntryFile(destDir, entryName);
                Object sizeObj = archive.getProperty(i, PropID.SIZE);
                budget.charge(sizeObj instanceof Long ? (Long) sizeObj : 0L);
                if (Boolean.TRUE.equals(archive.getProperty(i, PropID.IS_FOLDER))) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                extractItem(archive, i, out, entryName);
            }
        } catch (net.sf.sevenzipjbinding.SevenZipException e) {
            throw new IOException("RAR 解压失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("RAR 解压失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        return listRoots(destDir);
    }

    /** 提取单个条目到文件（7-Zip JBinding extractSlow，逐条目） */
    private static void extractItem(IInArchive archive, int index, File out, String entryName) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            ISequentialOutStream stream = data -> {
                try {
                    os.write(data);
                    return data.length;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
            ExtractOperationResult result = archive.extractSlow(index, stream);
            if (result == ExtractOperationResult.WRONG_PASSWORD) {
                throw new IOException("压缩包已加密，暂不支持（请解压后上传）");
            }
            if (result != ExtractOperationResult.OK) {
                throw new IOException("条目解压失败: " + entryName + " (" + result + ")");
            }
        }
    }

    // ========== VPK 提取（地图上传链路专用，ADR-0018 决策 2） ==========

    /**
     * 只提取压缩包内的 .vpk 文件到 destDir（平铺，取 base 名），其余条目不解压直接丢弃。
     * 跳过 macOS 垃圾；带 slip 防护与自定义上限。
     *
     * @return 提取出的 .vpk 文件列表（可能为空）
     */
    public static List<File> extractVpks(File archiveFile, String originalFilename, File destDir,
                                         long maxBytes, int maxEntries) throws IOException {
        if (!destDir.exists()) destDir.mkdirs();
        List<File> vpks = new ArrayList<>();
        ExtractBudget budget = new ExtractBudget(maxBytes, maxEntries);
        String ext = extension(originalFilename);

        switch (ext) {
            case ".zip": {
                try (ZipFile zip = ZipFile.builder().setFile(archiveFile).setCharset(GBK).get()) {
                    Enumeration<ZipArchiveEntry> entries = zip.getEntries();
                    while (entries.hasMoreElements()) {
                        ZipArchiveEntry entry = entries.nextElement();
                        if (entry.isDirectory() || ZipSlipGuard.isMacOSJunk(entry.getName())) continue;
                        if (!isVpkName(entry.getName())) continue;
                        File out = new File(destDir, baseName(entry.getName()));
                        budget.charge(entry.getSize());
                        try (InputStream is = zip.getInputStream(entry);
                             OutputStream os = new FileOutputStream(out)) {
                            is.transferTo(os);
                        }
                        vpks.add(out);
                    }
                }
                break;
            }
            case ".7z": {
                try (SevenZFile sz = SevenZFile.builder().setFile(archiveFile).get()) {
                    SevenZArchiveEntry entry;
                    while ((entry = sz.getNextEntry()) != null) {
                        if (entry.isDirectory() || ZipSlipGuard.isMacOSJunk(entry.getName())) continue;
                        if (!isVpkName(entry.getName())) continue;
                        File out = new File(destDir, baseName(entry.getName()));
                        budget.charge(entry.getSize());
                        try (OutputStream os = new FileOutputStream(out)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = sz.read(buf)) > 0) os.write(buf, 0, n);
                        }
                        vpks.add(out);
                    }
                }
                break;
            }
            case ".rar": {
                try (RandomAccessFile raf = new RandomAccessFile(archiveFile, "r");
                     IInArchive archive = SevenZip.openInArchive(null, new RandomAccessFileInStream(raf))) {
                    int count = archive.getNumberOfItems();
                    for (int i = 0; i < count; i++) {
                        String entryName = (String) archive.getProperty(i, PropID.PATH);
                        if ((Boolean.TRUE.equals(archive.getProperty(i, PropID.IS_FOLDER)))
                                || ZipSlipGuard.isMacOSJunk(entryName)) continue;
                        if (!isVpkName(entryName)) continue;
                        File out = new File(destDir, baseName(entryName));
                        Object sizeObj = archive.getProperty(i, PropID.SIZE);
                        budget.charge(sizeObj instanceof Long ? (Long) sizeObj : 0L);
                        extractItem(archive, i, out, entryName);
                        vpks.add(out);
                    }
                } catch (net.sf.sevenzipjbinding.SevenZipException e) {
                    throw new IOException("RAR 解压失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("RAR 解压失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                }
                break;
            }
            default:
                throw new IOException("不支持的压缩格式: " + originalFilename);
        }
        return vpks;
    }

    // ========== 内部方法 ==========

    /** entry 名落盘路径：slip 防护后拼接（保留目录结构场景；normalizeAndCheck 返回完整路径） */
    private static File resolveEntryFile(File destDir, String entryName) {
        String safe = ZipSlipGuard.normalizeAndCheck(entryName, destDir.getAbsolutePath());
        return new File(safe);
    }

    private static boolean isVpkName(String entryName) {
        return entryName != null && entryName.toLowerCase(Locale.ROOT).endsWith(".vpk");
    }

    /** entry 名取 base 名（剥离目录结构，参考实现同策略） */
    private static String baseName(String entryName) {
        String name = entryName.replace('\\', '/');
        int idx = name.lastIndexOf('/');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        String name = filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private static List<File> listRoots(File destDir) {
        List<File> roots = new ArrayList<>();
        File[] children = destDir.listFiles();
        if (children != null) for (File c : children) roots.add(c);
        return roots;
    }
}
