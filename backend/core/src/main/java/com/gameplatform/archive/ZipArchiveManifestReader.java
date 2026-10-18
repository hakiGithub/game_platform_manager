package com.gameplatform.archive;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 单插件 ZIP 清单读取器（ADR-0031）。
 *
 * <p>只读取 ZIP 中央目录与 entry 元数据，不解压正文。路径穿越、绝对路径、
 * 符号链接、硬链接、重复条目、条目数和解压后大小都在上传前拒绝。
 */
@Component
public class ZipArchiveManifestReader {

    private static final Charset GBK = Charset.forName("GBK");

    public ArchiveManifest read(Path zipPath, int maxEntries, long maxBytes,
                                String expectedPluginName, String structureRoot) {
        if (zipPath == null) {
            throw new ArchiveManifestException("归档路径为空");
        }
        if (structureRoot == null || structureRoot.isBlank()) {
            throw new IllegalArgumentException("structureRoot 不能为空");
        }
        List<String> names = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        long totalBytes = 0;
        int fileCount = 0;
        int entryCount = 0;

        try (ZipFile zip = ZipFile.builder().setFile(zipPath.toFile()).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String name = normalize(entryName(entry));
                if (name.isEmpty() || isMacOsJunk(name)) {
                    continue;
                }
                if (++entryCount > maxEntries) {
                    throw new ArchiveManifestException("ARCHIVE_TOO_MANY_ENTRIES",
                            "归档条目数超过上限 " + maxEntries
                                    + "（疑似压缩炸弹）");
                }
                if (!seen.add(name)) {
                    throw new ArchiveManifestException("ARCHIVE_DUPLICATE_ENTRY",
                            "归档包含重复条目: " + name);
                }
                if (entry.isUnixSymlink()) {
                    throw new ArchiveManifestException("ARCHIVE_SYMLINK",
                            "归档包含符号链接: " + name);
                }
                int mode = entry.getUnixMode();
                if ((mode & 0170000) == 0120000) {
                    throw new ArchiveManifestException("ARCHIVE_SYMLINK",
                            "归档包含符号链接: " + name);
                }
                if (!entry.isDirectory()) {
                    fileCount++;
                    long size = entry.getSize();
                    if (size < 0) {
                        throw new ArchiveManifestException("ARCHIVE_UNKNOWN_SIZE",
                                "无法确定条目大小: " + name);
                    }
                    totalBytes += size;
                    if (totalBytes > maxBytes) {
                        throw new ArchiveManifestException("ARCHIVE_TOO_LARGE",
                                "解压后总大小超过上限 "
                                        + (maxBytes >> 20) + "MB（疑似压缩炸弹）");
                    }
                }
                names.add(name);
                sizes.add(entry.isDirectory() ? 0L : entry.getSize());
            }
        } catch (ArchiveManifestException e) {
            throw e;
        } catch (IOException e) {
            throw new ArchiveManifestException("读取 ZIP 清单失败: " + e.getMessage(), e);
        }

        if (names.isEmpty()) {
            throw new ArchiveManifestException("ARCHIVE_EMPTY", "ZIP 为空");
        }
        if (fileCount == 0) {
            throw new ArchiveManifestException("ARCHIVE_EMPTY",
                    "ZIP 只包含空目录，未找到可安装文件");
        }
        Set<String> roots = new LinkedHashSet<>();
        for (String name : names) {
            int slash = name.indexOf('/');
            roots.add(slash > 0 ? name.substring(0, slash) : name);
        }
        if (roots.size() > 1) {
            throw new ArchiveManifestException("ARCHIVE_MULTIPLE_PLUGIN_ROOTS",
                    "归档包含多个插件根目录，需回退逐文件安装");
        }

        String pluginRoot = null;
        if (roots.size() == 1 && !structureRoot.equalsIgnoreCase(roots.iterator().next())) {
            String only = roots.iterator().next();
            if (names.stream().anyMatch(name -> name.startsWith(only + "/"))) {
                pluginRoot = only;
            }
        }
        String pluginName = expectedPluginName != null && !expectedPluginName.isBlank()
                ? expectedPluginName
                : pluginRoot != null ? pluginRoot : "uploaded-" + System.currentTimeMillis();

        List<String> relativeFiles = new ArrayList<>();
        boolean hasStructureRoot = false;
        for (String name : names) {
            String relative = pluginRoot == null ? name : stripPrefix(name, pluginRoot);
            if (relative.isEmpty()) {
                continue;
            }
            if (relative.equals(structureRoot) || relative.startsWith(structureRoot + "/")) {
                hasStructureRoot = true;
            }
            relativeFiles.add(relative);
        }
        if (!hasStructureRoot) {
            throw new ArchiveManifestException("ARCHIVE_MISSING_GAME_ROOT",
                    "归档内未找到结构根目录: " + structureRoot);
        }
        return new ArchiveManifest(pluginRoot, pluginName, fileCount, totalBytes,
                List.copyOf(relativeFiles), hasStructureRoot);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            throw new ArchiveManifestException("归档条目名为空");
        }
        String name = raw.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:/.*")) {
            throw new ArchiveManifestException("ARCHIVE_ABSOLUTE_PATH",
                    "归档包含绝对路径: " + raw);
        }
        String[] parts = name.split("/");
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new ArchiveManifestException("ARCHIVE_PATH_TRAVERSAL",
                        "归档包含路径穿越: " + raw);
            }
            normalized.add(part);
        }
        return String.join("/", normalized);
    }

    private static boolean isMacOsJunk(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("__macosx/") || lower.endsWith("/.ds_store")
                || lower.equals(".ds_store");
    }

    /**
     * ZIP 的 UTF-8 标志缺失时，部分 Windows 压缩工具使用 GBK 文件名。
     * Commons Compress 按默认字符集读出的名称可能含替换字符，此时用原始
     * 字节按 GBK 重解码，兼容中文插件包且不影响标准 UTF-8 条目。
     */
    private static String entryName(ZipArchiveEntry entry) {
        String decoded = entry.getName();
        byte[] raw = entry.getRawName();
        if (raw == null || hasUtf8Flag(entry)
                || (!decoded.contains("\uFFFD") && !decoded.contains("?"))) {
            return decoded;
        }
        return new String(raw, GBK);
    }

    private static boolean hasUtf8Flag(ZipArchiveEntry entry) {
        return (entry.getRawFlag() & 0x0800) != 0;
    }

    private static String stripPrefix(String value, String prefix) {
        return value.equals(prefix) ? "" : value.substring(prefix.length() + 1);
    }
}
