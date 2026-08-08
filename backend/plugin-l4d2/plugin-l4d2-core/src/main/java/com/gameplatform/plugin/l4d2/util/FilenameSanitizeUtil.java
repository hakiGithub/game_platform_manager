package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文件名清洗工具，用于安全地处理用户上传/远程下载的文件名与相对路径。
 *
 * <p>对齐源项目 {@code link_parser.go:126-151}：
 * <ul>
 *   <li>{@link #sanitize(String)}：清洗文件名，去 \0、\→/、basename、替换非法字符为 _、限长 180（保留扩展名）、空/./  返回 null</li>
 *   <li>{@link #sanitizePath(String)}：清洗相对路径，禁止 .. 路径遍历</li>
 *   <li>{@link #isSupportedExtension(String)}：判断 .vpk/.zip/.rar/.7z 扩展名</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.1.0
 */
public final class FilenameSanitizeUtil {

    /** 非法字符：< > : " / \ | ? *，全部替换为 _ */
    private static final Pattern INVALID_CHARS = Pattern.compile("[<>:\"/\\\\|?*]");

    /** 文件名最大长度（含扩展名） */
    private static final int MAX_LENGTH = 180;

    /** 基础名（不含扩展名）最大长度 */
    private static final int MAX_BASE_LENGTH = 160;

    /** 支持的下载文件扩展名 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".vpk", ".zip", ".rar", ".7z");

    private FilenameSanitizeUtil() {
    }

    /**
     * 清洗文件名：
     * <ol>
     *   <li>去除 \0</li>
     *   <li>替换 \ 为 /</li>
     *   <li>取 basename（最后一个 / 后的部分）</li>
     *   <li>替换非法字符 {@code < > : " / \ | ? *} 为 _</li>
     *   <li>限长 180（保留扩展名，基础名截断到 160）</li>
     *   <li>空 / . / / 返回 null</li>
     * </ol>
     *
     * @param filename 原始文件名
     * @return 清洗后的文件名；若为空或非法返回 null
     */
    public static String sanitize(String filename) {
        if (filename == null) {
            return null;
        }
        // 去除 \0
        String name = filename.replace("\0", "");
        // 替换 \ 为 /
        name = name.replace('\\', '/');
        // 取 basename
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        // 替换非法字符为 _
        name = INVALID_CHARS.matcher(name).replaceAll("_");
        // 空或 . 或 / 返回 null
        if (name.isEmpty() || ".".equals(name) || "/".equals(name)) {
            return null;
        }
        // 限长 180（保留扩展名）
        return truncate(name);
    }

    /**
     * 清洗相对路径：禁止 .. 路径遍历，替换非法字符（保留 / 作为路径分隔符）。
     *
     * @param path 原始相对路径
     * @return 清洗后的路径；若为空返回 null
     * @throws L4D2PluginException 若路径包含 ..
     */
    public static String sanitizePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        // 替换 \ 为 /
        String normalized = path.replace('\\', '/');
        // 禁止 .. 路径遍历
        if (normalized.contains("..")) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                    "路径包含非法字符 ..: " + path);
        }
        // 替换非法字符为 _（保留 / 作为路径分隔符）
        return normalized.replaceAll("[<>:\"|?*]", "_");
    }

    /**
     * 判断是否为支持的下载文件扩展名（.vpk/.zip/.rar/.7z）。
     *
     * @param filename 文件名
     * @return 支持返回 true，否则 false
     */
    public static boolean isSupportedExtension(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase();
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 限长截断：保留扩展名，基础名截断到 {@value #MAX_BASE_LENGTH}，总长不超过 {@value #MAX_LENGTH}。
     */
    private static String truncate(String name) {
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            // 无扩展名，直接截断到 MAX_LENGTH
            return name.substring(0, Math.min(MAX_LENGTH, MAX_BASE_LENGTH));
        }
        String ext = name.substring(dot);
        String base = name.substring(0, dot);
        if (base.length() > MAX_BASE_LENGTH) {
            base = base.substring(0, MAX_BASE_LENGTH);
        }
        String result = base + ext;
        if (result.length() > MAX_LENGTH) {
            result = result.substring(0, MAX_LENGTH);
        }
        return result;
    }
}
