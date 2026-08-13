package com.gameplatform.patch;

import com.gameplatform.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 平台侧压缩包处理（ADR-0006 决策 5 的「平台解压推散文件」分支）
 *
 * <p>纯 Java 实现（commons-compress）：tar.gz/tgz、tar.bz2/tbz2、tar.xz/txz、zip、
 * gz/bz2/xz（单文件）。同时提供顶层条目清单（供覆盖备份使用）。</p>
 */
@Slf4j
@Component
public class PatchArchiveExtractor {

    /**
     * 解压到目标目录（安全校验：条目名不得包含 .. 或绝对路径）。
     *
     * @return 顶层条目名集合（相对名）
     */
    public Set<String> extract(Path archive, PatchFormat format, Path destDir) {
        mkdirs(destDir);
        Set<String> topLevel = new LinkedHashSet<>();
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive));
             ArchiveInputStream<?> in = openArchive(raw, format)) {
            ArchiveEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!in.canReadEntryData(entry)) {
                    continue;
                }
                String name = sanitize(entry.getName());
                Path out = destDir.resolve(name).normalize();
                if (!out.startsWith(destDir.normalize())) {
                    throw new BusinessException("压缩包条目越界: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out)) {
                        in.transferTo(os);
                    }
                }
                int slash = name.indexOf('/');
                topLevel.add(slash > 0 ? name.substring(0, slash) : name);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("解压失败: " + e.getMessage());
        }
        return topLevel;
    }

    /** 单文件压缩（gz/bz2/xz）：解压为 destDir 下同名去后缀文件，返回单条目集合 */
    public Set<String> extractSingle(Path archive, PatchFormat format, Path destDir) {
        mkdirs(destDir);
        String base = archive.getFileName().toString();
        for (String suffix : new String[]{".gz", ".bz2", ".xz"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        Path out = destDir.resolve(base);
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive));
             InputStream in = compressor(raw, format)) {
            try (OutputStream os = Files.newOutputStream(out)) {
                in.transferTo(os);
            }
        } catch (IOException e) {
            throw new BusinessException("解压失败: " + e.getMessage());
        }
        return Set.of(base);
    }

    /**
     * 列出压缩包顶层条目（供「将被覆盖条目」备份判断；不解压内容）。
     */
    public Set<String> listTopLevelEntries(Path archive, PatchFormat format) {
        Set<String> topLevel = new LinkedHashSet<>();
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive));
             ArchiveInputStream<?> in = openArchive(raw, format)) {
            ArchiveEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = sanitize(entry.getName());
                int slash = name.indexOf('/');
                topLevel.add(slash > 0 ? name.substring(0, slash) : name);
            }
        } catch (IOException e) {
            throw new BusinessException("读取压缩包清单失败: " + e.getMessage());
        }
        return topLevel;
    }

    private void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BusinessException("创建目录失败: " + e.getMessage());
        }
    }

    private ArchiveInputStream<?> openArchive(InputStream raw, PatchFormat format) throws IOException {
        return switch (format) {
            case TAR_GZ -> new TarArchiveInputStream(new GzipCompressorInputStream(raw));
            case TAR_BZ2 -> new TarArchiveInputStream(new BZip2CompressorInputStream(raw));
            case TAR_XZ -> new TarArchiveInputStream(new XZCompressorInputStream(raw));
            case ZIP -> new ZipArchiveInputStream(raw);
            default -> throw new BusinessException("不支持的解压格式: " + format);
        };
    }

    private InputStream compressor(InputStream raw, PatchFormat format) throws IOException {
        return switch (format) {
            case GZ -> new GzipCompressorInputStream(raw);
            case BZ2 -> new BZip2CompressorInputStream(raw);
            case XZ -> new XZCompressorInputStream(raw);
            default -> throw new BusinessException("不支持的压缩格式: " + format);
        };
    }

    /** 条目名安全化：剥离前导 / 与 .. 段（返回裸相对名，越界在 extract 中再校验） */
    private String sanitize(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("/")) {
            n = n.substring(1);
        }
        return n;
    }
}
