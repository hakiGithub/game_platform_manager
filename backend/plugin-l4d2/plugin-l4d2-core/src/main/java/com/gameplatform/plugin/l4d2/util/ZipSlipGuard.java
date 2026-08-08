package com.gameplatform.plugin.l4d2.util;

/**
 * Zip Slip 防护工具类（对齐 l4d2-server-next extractFiles 安全检查）。
 *
 * <p>防止恶意 zip 文件通过 {@code ../} 路径遍历到目标目录之外，同时过滤 macOS 垃圾文件。
 *
 * <p>使用方法：
 * <pre>{@code
 * try (ZipInputStream zis = new ZipInputStream(...)) {
 *     ZipEntry entry;
 *     while ((entry = zis.getNextEntry()) != null) {
 *         if (entry.isDirectory() || ZipSlipGuard.isMacOSJunk(entry.getName())) continue;
 *         String targetRel = ZipSlipGuard.normalizeAndCheck(entry.getName(), targetDir);
 *         // 写入 targetRel ...
 *     }
 * }
 * }</pre>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public final class ZipSlipGuard {

    private ZipSlipGuard() {
    }

    /**
     * 归一化并校验 zip entry 路径，拼接目标目录。
     *
     * <p>规则：
     * <ul>
     *   <li>反斜杠 → 正斜杠</li>
     *   <li>禁止绝对路径（以 / 开头）</li>
     *   <li>禁止路径遍历（包含 ..）</li>
     *   <li>剥离前导 {@code ./} 与段内 {@code /./}</li>
     * </ul>
     *
     * @param entryName zip entry 名称
     * @param targetDir 目标目录（相对路径，可空）
     * @return 完整的相对路径
     * @throws IllegalArgumentException 如果路径试图越界
     */
    public static String normalizeAndCheck(String entryName, String targetDir) {
        String normalized = entryName.replace('\\', '/');

        // 拒绝绝对路径
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Zip Slip 检测：禁止绝对路径: " + entryName);
        }

        // 拒绝 .. 路径段
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Zip Slip 检测：禁止路径遍历: " + entryName);
        }

        // 去除前导 ./
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }

        // 去除段内 /./
        while (normalized.contains("/./")) {
            normalized = normalized.replace("/./", "/");
        }

        // 拼接
        if (targetDir == null || targetDir.isEmpty()) {
            return normalized;
        }
        String separator = targetDir.endsWith("/") ? "" : "/";
        return targetDir + separator + normalized;
    }

    /**
     * 检测是否为 macOS 垃圾文件。
     *
     * <p>包括：
     * <ul>
     *   <li>{@code __MACOSX/} 目录及其下所有文件（macOS 压缩时附带的元数据）</li>
     *   <li>{@code .DS_Store} 文件（macOS Finder 目录元数据）</li>
     * </ul>
     *
     * @param entryName zip entry 名称（可能为 null）
     * @return true 表示应跳过
     */
    public static boolean isMacOSJunk(String entryName) {
        if (entryName == null) {
            return false;
        }
        String normalized = entryName.replace('\\', '/');
        return normalized.startsWith("__MACOSX/")
                || normalized.endsWith(".DS_Store");
    }
}
