package com.gameplatform.plugin.l4d2.util;

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

/**
 * 压缩包解压工具：ZIP（GBK 文件名）/ 7z。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Component
public class ArchiveExtractUtil {

    private static final Charset GBK = Charset.forName("GBK");

    /** VPK magic（小端 34 12 AA 55 = 0x55AA1234） */
    public static boolean isVpkFile(byte[] header) {
        return header.length >= 4
                && (header[0] & 0xFF) == 0x34
                && (header[1] & 0xFF) == 0x12
                && (header[2] & 0xFF) == 0xAA
                && (header[3] & 0xFF) == 0x55;
    }

    /** 解压 ZIP（GBK 文件名） */
    public static List<File> extractZip(File zipFile, File destDir) throws IOException {
        if (!destDir.exists()) destDir.mkdirs();
        List<File> roots = new ArrayList<>();
        try (ZipFile zip = ZipFile.builder().setFile(zipFile).setCharset(GBK).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                File out = new File(destDir, entry.getName());
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
        File[] children = destDir.listFiles();
        if (children != null) for (File c : children) roots.add(c);
        return roots;
    }

    /** 解压 7z */
    public static List<File> extract7z(File sevenZFile, File destDir) throws IOException {
        if (!destDir.exists()) destDir.mkdirs();
        try (SevenZFile sz = SevenZFile.builder().setFile(sevenZFile).get()) {
            SevenZArchiveEntry entry;
            while ((entry = sz.getNextEntry()) != null) {
                File out = new File(destDir, entry.getName());
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
        List<File> roots = new ArrayList<>();
        File[] children = destDir.listFiles();
        if (children != null) for (File c : children) roots.add(c);
        return roots;
    }

    /** 统一入口：根据扩展名分派 */
    public static List<File> extract(File archiveFile, String originalFilename, File destDir) throws IOException {
        String ext = originalFilename == null ? "" : originalFilename.toLowerCase();
        if (ext.endsWith(".zip")) return extractZip(archiveFile, destDir);
        if (ext.endsWith(".7z"))  return extract7z(archiveFile, destDir);
        if (ext.endsWith(".rar")) {
            throw new IOException("RAR 解压需引入 junrar 依赖（暂未启用）");
        }
        throw new IOException("不支持的压缩格式: " + ext);
    }
}
